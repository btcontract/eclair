package fr.acinq.eclair.db

import java.util.UUID

import fr.acinq.bitcoin.{ByteVector32, Satoshi}
import fr.acinq.eclair.TestConstants.TestPgDatabases
import fr.acinq.eclair.channel.{Channel, ChannelVersion, HOSTED_DATA_COMMITMENTS}
import fr.acinq.eclair.{MilliSatoshi, ShortChannelId, TestConstants, UInt64, randomBytes32, randomBytes64, randomKey}
import fr.acinq.eclair.router.Announcements
import fr.acinq.eclair.transactions.{CommitmentSpec, IncomingHtlc, OutgoingHtlc}
import fr.acinq.eclair.wire.{ChannelUpdate, Error, InitHostedChannel, LastCrossSignedState, StateOverride, UpdateAddHtlc}
import scodec.bits.ByteVector
import fr.acinq.eclair._
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import fr.acinq.eclair.payment.relay.Origin
import org.scalatest.funsuite.AnyFunSuite

import scala.util.Random

class PgHostedChannelsDbSpec extends AnyFunSuite {

  def bin(len: Int, fill: Byte): ByteVector = ByteVector.fill(len)(fill)

  def bin32(fill: Byte): ByteVector32 = ByteVector32(bin(32, fill))

  val add1 = UpdateAddHtlc(
    channelId = randomBytes32,
    id = Random.nextInt(Int.MaxValue),
    amountMsat = MilliSatoshi(Random.nextInt(Int.MaxValue)),
    cltvExpiry = CltvExpiry(Random.nextInt(Int.MaxValue)),
    paymentHash = randomBytes32,
    onionRoutingPacket = TestConstants.emptyOnionPacket)
  val add2 = UpdateAddHtlc(
    channelId = randomBytes32,
    id = Random.nextInt(Int.MaxValue),
    amountMsat = MilliSatoshi(Random.nextInt(Int.MaxValue)),
    cltvExpiry = CltvExpiry(Random.nextInt(Int.MaxValue)),
    paymentHash = randomBytes32,
    onionRoutingPacket = TestConstants.emptyOnionPacket)

  val init_hosted_channel = InitHostedChannel(UInt64(6), 10 msat, 20, 500000000L msat, 5000, 1000000 sat, 1000000 msat, ByteVector.empty)
  val lcss1 = LastCrossSignedState(bin(47, 0), init_hosted_channel, 10000, 10000 msat, 20000 msat, 10, 20, List(add2, add1), List(add1, add2), randomBytes64, randomBytes64)

  val htlc1 = IncomingHtlc(add1)
  val htlc2 = OutgoingHtlc(add2)
  val cs = CommitmentSpec(
    htlcs = Set(htlc1, htlc2),
    feeratePerKw = FeeratePerKw(Satoshi(0L)),
    toLocal = MilliSatoshi(Random.nextInt(Int.MaxValue)),
    toRemote = MilliSatoshi(Random.nextInt(Int.MaxValue))
  )

  val channelUpdate: ChannelUpdate = Announcements.makeChannelUpdate(ByteVector32(ByteVector.fill(32)(1)), randomKey, randomKey.publicKey, ShortChannelId(142553), CltvExpiryDelta(42), MilliSatoshi(15), MilliSatoshi(575), 53, Channel.MAX_FUNDING.toMilliSatoshi)

  val error: Error = Error(ByteVector32.Zeroes, ByteVector.fromValidHex("0000"))

  val hdc = HOSTED_DATA_COMMITMENTS(
    remoteNodeId = randomKey.publicKey,
    channelVersion = ChannelVersion.HOSTED_PRIVATE,
    lastCrossSignedState = lcss1,
    futureUpdates = List(Right(add1), Left(add2)),
    originChannels = Map(42L -> Origin.Local(UUID.randomUUID, None), 15000L -> Origin.Relayed(ByteVector32(ByteVector.fill(32)(42)), 43, MilliSatoshi(11000000L), MilliSatoshi(10000000L))),
    localSpec = cs,
    channelId = ByteVector32.Zeroes,
    isHost = false,
    channelUpdate = channelUpdate,
    localError = None,
    remoteError = Some(error),
    failedToPeerHtlcLeftoverIds = Set(12, 67, 79, 119),
    fulfilledByPeerHtlcLeftoverIds = Set(1, 2, 3),
    overrideProposal = Some(StateOverride(50000L, 500000 msat, 70000, 700000, randomBytes64)))

  test("get / insert / update a hosted commits") {
    val db = TestPgDatabases().hostedChannels()
    assert(db.getChannel(ByteVector32.Zeroes).isEmpty)
    val newShortChannelId = randomHostedChanShortId
    val hdc1 = hdc.copy(channelUpdate = channelUpdate.copy(shortChannelId = newShortChannelId))
    val secret = randomBytes32.bytes

    db.addOrUpdateChannel(hdc1) // insert channel data
    db.addOrUpdateChannel(hdc1) // update, same data
    assert(db.getChannel(ByteVector32.Zeroes).contains(hdc1))

    val hdc2 = hdc1.copy(futureUpdates = Nil)
    db.addOrUpdateChannel(hdc2) // update, new data
    db.updateSecret(hdc1.channelId, secret)
    assert(db.getChannel(ByteVector32.Zeroes).contains(hdc2))
    assert(db.getChannelBySecret(secret).contains(hdc2))
  }

  test("list hot channels (with HTLCs in-flight)") {
    val db = TestPgDatabases().hostedChannels()
    val newShortChannelId1 = randomHostedChanShortId
    val newShortChannelId2 = randomHostedChanShortId
    val newShortChannelId3 = randomHostedChanShortId

    val hdc1 = hdc.copy(channelUpdate = channelUpdate.copy(shortChannelId = newShortChannelId1), channelId = randomBytes32)
    val hdc2 = hdc.copy(channelUpdate = channelUpdate.copy(shortChannelId = newShortChannelId2), channelId = randomBytes32)
    val hdc3 = hdc.copy(channelUpdate = channelUpdate.copy(shortChannelId = newShortChannelId3), channelId = randomBytes32,
      futureUpdates = Nil, localSpec = CommitmentSpec(htlcs = Set.empty, feeratePerKw = FeeratePerKw(Satoshi(0L)), toLocal = MilliSatoshi(Random.nextInt(Int.MaxValue)),
        toRemote = MilliSatoshi(Random.nextInt(Int.MaxValue))))

    db.addOrUpdateChannel(hdc1)
    db.addOrUpdateChannel(hdc2)
    db.addOrUpdateChannel(hdc3)

    assert(db.listHotChannels().toSet === Set(hdc1, hdc2))
  }
}