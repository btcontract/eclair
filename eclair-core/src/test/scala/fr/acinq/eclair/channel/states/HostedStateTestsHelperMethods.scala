package fr.acinq.eclair.channel.states

import java.util.UUID

import akka.testkit.{TestFSMRef, TestKitBase, TestProbe}
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.{ByteVector32, Crypto}
import fr.acinq.eclair.TestConstants.{Alice, Bob, TestPgDatabases}
import fr.acinq.eclair.channel.Channel.OutgoingMessage
import fr.acinq.eclair.channel._
import fr.acinq.eclair.payment.OutgoingPacket
import fr.acinq.eclair.router.Router.ChannelHop
import fr.acinq.eclair.wire.Onion.FinalLegacyPayload
import fr.acinq.eclair.wire.{ChannelUpdate, InitHostedChannel, InvokeHostedChannel, StateUpdate}
import fr.acinq.eclair.{CltvExpiryDelta, MilliSatoshi, NodeParams, TestConstants, randomBytes32}
import org.scalatest.{FixtureTestSuite, ParallelTestExecution}

trait HostedStateTestsHelperMethods extends TestKitBase with FixtureTestSuite with ParallelTestExecution {
  case class HostedSetupFixture(alice: TestFSMRef[State, HostedData, HostedChannel],
                                bob: TestFSMRef[State, HostedData, HostedChannel],
                                alice2bob: TestProbe,
                                bob2alice: TestProbe,
                                router: TestProbe,
                                relayerA: TestProbe,
                                relayerB: TestProbe,
                                channelUpdateListener: TestProbe) {
    def currentBlockHeight: Long = alice.underlyingActor.nodeParams.currentBlockHeight
  }

  def init(nodeParamsA: NodeParams = TestConstants.Alice.nodeParams, nodeParamsB: NodeParams = TestConstants.Bob.nodeParams): HostedSetupFixture = {
    val alice2bob = TestProbe()
    val bob2alice = TestProbe()
    val relayerA = TestProbe()
    val relayerB = TestProbe()
    val channelUpdateListener = TestProbe()
    system.eventStream.subscribe(channelUpdateListener.ref, classOf[LocalChannelUpdate])
    system.eventStream.subscribe(channelUpdateListener.ref, classOf[LocalChannelDown])
    val router = TestProbe()

    val alice: TestFSMRef[State, HostedData, HostedChannel] = TestFSMRef(new HostedChannel(nodeParamsA, Bob.nodeParams.nodeId, router.ref, relayerA.ref))
    val bob: TestFSMRef[State, HostedData, HostedChannel] = TestFSMRef(new HostedChannel(nodeParamsB, Alice.nodeParams.nodeId, router.ref, relayerB.ref))
    HostedSetupFixture(alice, bob, alice2bob, bob2alice, router, relayerA, relayerB, channelUpdateListener)
  }

  def makeCmdAdd(amount: MilliSatoshi, destination: PublicKey, currentBlockHeight: Long): (ByteVector32, CMD_ADD_HTLC) = {
    val payment_preimage: ByteVector32 = randomBytes32
    val payment_hash: ByteVector32 = Crypto.sha256(payment_preimage)
    val expiry = CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight)
    val cmd = OutgoingPacket.buildCommand(Upstream.Local(UUID.randomUUID), payment_hash, ChannelHop(null, destination, null) :: Nil, FinalLegacyPayload(amount, expiry))._1.copy(commit = false)
    (payment_preimage, cmd)
  }

  def reachNormal(setup: HostedSetupFixture, channelId: ByteVector32, tags: Set[String] = Set.empty): Unit = {
    import setup._
    bob ! CMD_HOSTED_INPUT_RECONNECTED(channelId, Alice.nodeParams.nodeId, bob2alice.ref, bob2alice.ref)
    alice ! CMD_HOSTED_INPUT_RECONNECTED(channelId, Bob.nodeParams.nodeId, alice2bob.ref, alice2bob.ref)
    awaitCond(bob.stateName == WAIT_FOR_INIT_INTERNAL)
    awaitCond(alice.stateName == WAIT_FOR_INIT_INTERNAL)
    bob ! CMD_HOSTED_INVOKE_CHANNEL(channelId, Alice.nodeParams.nodeId, Bob.channelParams.defaultFinalScriptPubKey)
    val bobInvokeHostedChannel = bob2alice.expectMsgType[OutgoingMessage].msg.asInstanceOf[InvokeHostedChannel]
    bob2alice.forward(alice, CMD_HOSTED_MESSAGE(channelId, bobInvokeHostedChannel))
    awaitCond(alice.stateData.isInstanceOf[HOSTED_DATA_HOST_WAIT_CLIENT_STATE_UPDATE])
    val aliceInitHostedChannel = alice2bob.expectMsgType[OutgoingMessage].msg.asInstanceOf[InitHostedChannel]
    alice2bob.forward(bob, CMD_HOSTED_MESSAGE(channelId, aliceInitHostedChannel))
    awaitCond(bob.stateData.isInstanceOf[HOSTED_DATA_CLIENT_WAIT_HOST_STATE_UPDATE])
    val bobStateUpdate = bob2alice.expectMsgType[OutgoingMessage].msg.asInstanceOf[StateUpdate]
    bob2alice.forward(alice, CMD_HOSTED_MESSAGE(channelId, bobStateUpdate))
    awaitCond(alice.stateName == NORMAL)
    val aliceStateUpdate = alice2bob.expectMsgType[OutgoingMessage].msg.asInstanceOf[StateUpdate]
    alice2bob.forward(bob, CMD_HOSTED_MESSAGE(channelId, aliceStateUpdate))
    awaitCond(bob.stateName == NORMAL)
    alice2bob.expectMsgType[OutgoingMessage].msg.asInstanceOf[ChannelUpdate]
    bob2alice.expectMsgType[OutgoingMessage].msg.asInstanceOf[ChannelUpdate]
  }
}
