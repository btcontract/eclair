package fr.acinq.eclair.wire

import java.util.UUID

import fr.acinq.eclair._
import fr.acinq.bitcoin.{ByteVector32, Satoshi}
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import fr.acinq.eclair.{MilliSatoshi, ShortChannelId, UInt64, randomBytes32, randomBytes64, randomKey}
import fr.acinq.eclair.channel.{Channel, ChannelVersion, HOSTED_DATA_COMMITMENTS, HostedState}
import fr.acinq.eclair.payment.relay.Origin
import fr.acinq.eclair.router.Announcements
import fr.acinq.eclair.transactions.{CommitmentSpec, OutgoingHtlc, IncomingHtlc}
import org.scalatest.funsuite.AnyFunSuite
import scodec.bits.ByteVector

import scala.util.Random

class HostedChannelCodecsSpec extends AnyFunSuite {

  def bin(len: Int, fill: Byte): ByteVector = ByteVector.fill(len)(fill)

  def bin32(fill: Byte): ByteVector32 = ByteVector32(bin(32, fill))

  test("encode/decode hosted commitments") {
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

    val channelUpdate = Announcements.makeChannelUpdate(ByteVector32(ByteVector.fill(32)(1)), randomKey, randomKey.publicKey, ShortChannelId(142553),
      CltvExpiryDelta(42), MilliSatoshi(15), MilliSatoshi(575), 53, Channel.MAX_FUNDING.toMilliSatoshi)

    val error = Error(ByteVector32.Zeroes, ByteVector.fromValidHex("0000"))

    val hdc = HOSTED_DATA_COMMITMENTS(
      remoteNodeId = randomKey.publicKey,
      channelVersion = ChannelVersion.HOSTED_PRIVATE,
      lastCrossSignedState = lcss1,
      futureUpdates = List(Right(add1), Left(add2)),
      originChannels = Map(42L -> Origin.Local(UUID.randomUUID, None), 15000L -> Origin.Relayed(ByteVector32(ByteVector.fill(32)(42)), 43, MilliSatoshi(11000000L), MilliSatoshi(10000000L))),
      localSpec = cs,
      channelId = ByteVector32.Zeroes,
      isHost = true,
      channelUpdate = channelUpdate,
      localError = None,
      remoteError = Some(error),
      failedToPeerHtlcLeftoverIds = Set.empty,
      fulfilledByPeerHtlcLeftoverIds = Set(1, 2, 10000),
      overrideProposal = None)

    {
      val binary = HostedChannelCodecs.HOSTED_DATA_COMMITMENTS_Codec.encode(hdc).require
      val check = HostedChannelCodecs.HOSTED_DATA_COMMITMENTS_Codec.decodeValue(binary).require
      assert(hdc.localSpec === check.localSpec)
      assert(hdc === check)
    }

    val state = HostedState(ByteVector32.Zeroes, List(add1, add2), List.empty, lcss1)

    {
      val binary = HostedChannelCodecs.hostedStateCodec.encode(state).require
      val check = HostedChannelCodecs.hostedStateCodec.decodeValue(binary).require
      assert(state === check)
    }
  }
}
