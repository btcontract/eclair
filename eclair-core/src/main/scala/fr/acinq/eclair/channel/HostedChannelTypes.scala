package fr.acinq.eclair.channel

import akka.actor.ActorRef
import fr.acinq.bitcoin.{ByteVector32, ByteVector64, Crypto, Satoshi}
import fr.acinq.bitcoin.Crypto.{PrivateKey, PublicKey}
import fr.acinq.eclair._
import fr.acinq.eclair.channel.HOSTED_DATA_COMMITMENTS.LocalOrRemoteUpdate
import fr.acinq.eclair.payment.relay.Origin
import fr.acinq.eclair.transactions.{CommitmentSpec, DirectedHtlc, OutgoingHtlc}
import fr.acinq.eclair.wire._
import scodec.bits.ByteVector

import scala.util.{Failure, Success, Try}

sealed trait HasHostedChanIdCommand {
  def channelId: ByteVector32
}
case object CMD_HOSTED_REMOVE_IDLE_CHANNELS
case class CMD_HOSTED_INPUT_DISCONNECTED(channelId: ByteVector32) extends HasHostedChanIdCommand
case class CMD_HOSTED_INPUT_RECONNECTED(channelId: ByteVector32, remoteNodeId: PublicKey, peerConnection: ActorRef) extends HasHostedChanIdCommand
case class CMD_HOSTED_INVOKE_CHANNEL(channelId: ByteVector32, remoteNodeId: PublicKey, refundScriptPubKey: ByteVector) extends HasHostedChanIdCommand
case class CMD_HOSTED_MESSAGE(channelId: ByteVector32, message: LightningMessage) extends HasHostedChanIdCommand
case class CMD_HOSTED_OVERRIDE(channelId: ByteVector32, newLocalBalance: MilliSatoshi) extends HasHostedChanIdCommand
case class CMD_HOSTED_EXTERNAL_FULFILL(channelId: ByteVector32, htlcId: Long, paymentPreimage: ByteVector32) extends HasHostedChanIdCommand {
  val fulfillCmd: CMD_HOSTED_MESSAGE = CMD_HOSTED_MESSAGE(channelId, UpdateFulfillHtlc(channelId, htlcId, paymentPreimage))
}

sealed trait HostedData
case object HostedNothing extends HostedData
case class HOSTED_DATA_CLIENT_WAIT_HOST_INIT(refundScriptPubKey: ByteVector) extends HostedData
case class HOSTED_DATA_CLIENT_WAIT_HOST_STATE_UPDATE(commits: HOSTED_DATA_COMMITMENTS) extends HostedData
case class HOSTED_DATA_HOST_WAIT_CLIENT_STATE_UPDATE(init: InitHostedChannel, invoke: InvokeHostedChannel) extends HostedData {
  require(Helpers.Closing.isValidFinalScriptPubkey(invoke.refundScriptPubKey), "invalid refundScriptPubKey when opening a hosted channel")
}
final case class RES_HOSTED_GETINFO(nodeId: PublicKey, channelId: ByteVector32, state: State, commits: HOSTED_DATA_COMMITMENTS, nextLocalSpec: CommitmentSpec)

object HOSTED_DATA_COMMITMENTS {
  // Left is locally sent from us to peer, Right is remotely sent from from peer to us
  type LocalOrRemoteUpdate = Either[UpdateMessage, UpdateMessage]
}

case class HOSTED_DATA_COMMITMENTS(remoteNodeId: PublicKey,
                                   channelVersion: ChannelVersion,
                                   lastCrossSignedState: LastCrossSignedState,
                                   futureUpdates: List[LocalOrRemoteUpdate], // For CLOSED channel we need to look here for UpdateFail/Fulfill messages from network for in-flight (client -> we -> network) payments
                                   localSpec: CommitmentSpec,
                                   originChannels: Map[Long, Origin],
                                   channelId: ByteVector32,
                                   isHost: Boolean,
                                   channelUpdate: ChannelUpdate,
                                   localError: Option[Error],
                                   remoteError: Option[Error],
                                   failedToPeerHtlcLeftoverIds: Set[Long], // CLOSED channel may have in-flight HTLCs (network -> we -> client) which can later be failed, collect their IDs here
                                   fulfilledByPeerHtlcLeftoverIds: Set[Long], // CLOSED channel may have in-flight HTLCs (network -> we -> client) which can later be fulfilled, collect their IDs here
                                   overrideProposal: Option[StateOverride] // CLOSED channel override can be initiated by Host, a new proposed balance should be retained once this happens
                                  ) extends ChannelCommitments with HostedData {

  val (nextLocalUpdates, nextRemoteUpdates, nextTotalLocal, nextTotalRemote) =
    futureUpdates.foldLeft((List.empty[UpdateMessage], List.empty[UpdateMessage], lastCrossSignedState.localUpdates, lastCrossSignedState.remoteUpdates)) {
      case ((localMessages, remoteMessages, totalLocalNumber, totalRemoteNumber), Left(msg)) => (localMessages :+ msg, remoteMessages, totalLocalNumber + 1, totalRemoteNumber)
      case ((localMessages, remoteMessages, totalLocalNumber, totalRemoteNumber), Right(msg)) => (localMessages, remoteMessages :+ msg, totalLocalNumber, totalRemoteNumber + 1)
    }

  val nextLocalSpec: CommitmentSpec = CommitmentSpec.reduce(localSpec, nextLocalUpdates, nextRemoteUpdates)

  val availableBalanceForSend: MilliSatoshi = nextLocalSpec.toLocal

  val availableBalanceForReceive: MilliSatoshi = nextLocalSpec.toRemote

  val currentAndNextInFlightHtlcs: Set[DirectedHtlc] = localSpec.htlcs ++ nextLocalSpec.htlcs

  override val announceChannel: Boolean = false

  def getError: Option[Error] = localError.orElse(remoteError)

  def addProposal(update: LocalOrRemoteUpdate): HOSTED_DATA_COMMITMENTS = copy(futureUpdates = futureUpdates :+ update)

  // Find a cross-signed (in localSpec) and still not resolved (also in nextLocalSpec)
  def getOutgoingHtlcCrossSigned(htlcId: Long): Option[UpdateAddHtlc] = for {
    localSigned <- localSpec.findOutgoingHtlcById(htlcId)
    remoteSigned <- nextLocalSpec.findOutgoingHtlcById(htlcId)
  } yield {
    require(localSigned.add == remoteSigned.add)
    localSigned.add
  }

  def getIncomingHtlcCrossSigned(htlcId: Long): Option[UpdateAddHtlc] = for {
    localSigned <- localSpec.findIncomingHtlcById(htlcId)
    remoteSigned <- nextLocalSpec.findIncomingHtlcById(htlcId)
  } yield {
    require(localSigned.add == remoteSigned.add)
    localSigned.add
  }

  // Meaning sent from us to client
  def timedOutOutgoingHtlcs(blockheight: Long): Set[UpdateAddHtlc] = for {
    OutgoingHtlc(add) <- currentAndNextInFlightHtlcs if blockheight > add.cltvExpiry.toLong
    if !failedToPeerHtlcLeftoverIds.contains(add.id) && !fulfilledByPeerHtlcLeftoverIds.contains(add.id)
  } yield add

  def nextLocalUnsignedLCSS(blockDay: Long): LastCrossSignedState = {
    val (incomingHtlcs, outgoingHtlcs) = nextLocalSpec.htlcs.toList.partition(DirectedHtlc.incoming.isDefinedAt)
    LastCrossSignedState(lastCrossSignedState.refundScriptPubKey, lastCrossSignedState.initHostedChannel, blockDay, nextLocalSpec.toLocal, nextLocalSpec.toRemote,
      nextTotalLocal, nextTotalRemote, incomingHtlcs.map(_.add), outgoingHtlcs.map(_.add), localSigOfRemote = ByteVector64.Zeroes, remoteSigOfLocal = ByteVector64.Zeroes)
  }

  // Rebuild all messaging and state history starting from local LCSS,
  // then try to find a future state with same update numbers as remote LCSS
  def findState(remoteLCSS: LastCrossSignedState): Seq[HOSTED_DATA_COMMITMENTS] = for {
    previousIndex <- futureUpdates.indices drop 1
    previousHC = copy(futureUpdates = futureUpdates take previousIndex)
    if previousHC.nextLocalUnsignedLCSS(remoteLCSS.blockDay).isEven(remoteLCSS)
  } yield previousHC

  def sendAdd(cmd: CMD_ADD_HTLC, origin: Origin, blockHeight: Long): Try[(HOSTED_DATA_COMMITMENTS, UpdateAddHtlc)] = {
    val minExpiry = Channel.MIN_CLTV_EXPIRY_DELTA.toCltvExpiry(blockHeight)
    if (cmd.cltvExpiry < minExpiry) {
      return Failure(ExpiryTooSmall(channelId, minimum = minExpiry, actual = cmd.cltvExpiry, blockCount = blockHeight))
    }

    val maxExpiry = Channel.MAX_CLTV_EXPIRY_DELTA.toCltvExpiry(blockHeight)
    if (cmd.cltvExpiry >= maxExpiry) {
      return Failure(ExpiryTooBig(channelId, maximum = maxExpiry, actual = cmd.cltvExpiry, blockCount = blockHeight))
    }

    if (cmd.amount < lastCrossSignedState.initHostedChannel.htlcMinimumMsat) {
      return Failure(HtlcValueTooSmall(channelId, minimum = lastCrossSignedState.initHostedChannel.htlcMinimumMsat, actual = cmd.amount))
    }

    val add = UpdateAddHtlc(channelId, nextTotalLocal + 1, cmd.amount, cmd.paymentHash, cmd.cltvExpiry, cmd.onion)
    val commits1 = addProposal(Left(add)).copy(originChannels = originChannels + (add.id -> origin))
    val outgoingHtlcs = commits1.nextLocalSpec.htlcs.collect(DirectedHtlc.outgoing)

    if (commits1.nextLocalSpec.toLocal < 0.msat) {
      return Failure(InsufficientFunds(channelId, amount = cmd.amount, missing = -commits1.nextLocalSpec.toLocal.truncateToSatoshi, reserve = 0 sat, fees = 0 sat))
    }

    // NB: we need the `toSeq` because otherwise duplicate amountMsat would be removed (since incomingHtlcs is a Set).
    val htlcValueInFlight = outgoingHtlcs.toSeq.map(_.amountMsat).sum
    if (lastCrossSignedState.initHostedChannel.maxHtlcValueInFlightMsat < htlcValueInFlight) {
      return Failure(HtlcValueTooHighInFlight(channelId, maximum = lastCrossSignedState.initHostedChannel.maxHtlcValueInFlightMsat, actual = htlcValueInFlight))
    }

    if (outgoingHtlcs.size > lastCrossSignedState.initHostedChannel.maxAcceptedHtlcs) {
      return Failure(TooManyAcceptedHtlcs(channelId, maximum = lastCrossSignedState.initHostedChannel.maxAcceptedHtlcs))
    }

    Success(commits1, add)
  }

  def receiveAdd(add: UpdateAddHtlc): Try[HOSTED_DATA_COMMITMENTS] = Try {
    if (add.id != nextTotalRemote + 1) {
      throw UnexpectedHtlcId(channelId, expected = nextTotalRemote + 1, actual = add.id)
    }

    if (add.amountMsat < lastCrossSignedState.initHostedChannel.htlcMinimumMsat) {
      throw HtlcValueTooSmall(channelId, minimum = lastCrossSignedState.initHostedChannel.htlcMinimumMsat, actual = add.amountMsat)
    }

    val commits1 = addProposal(Right(add))
    val incomingHtlcs = commits1.nextLocalSpec.htlcs.collect(DirectedHtlc.incoming)

    if (commits1.nextLocalSpec.toRemote < 0.msat) {
      throw InsufficientFunds(channelId, amount = add.amountMsat, missing = -commits1.nextLocalSpec.toRemote.truncateToSatoshi, reserve = 0 sat, fees = 0 sat)
    }

    // NB: we need the `toSeq` because otherwise duplicate amountMsat would be removed (since incomingHtlcs is a Set).
    val htlcValueInFlight = incomingHtlcs.toSeq.map(_.amountMsat).sum
    if (lastCrossSignedState.initHostedChannel.maxHtlcValueInFlightMsat < htlcValueInFlight) {
      throw HtlcValueTooHighInFlight(channelId, maximum = lastCrossSignedState.initHostedChannel.maxHtlcValueInFlightMsat, actual = htlcValueInFlight)
    }

    if (incomingHtlcs.size > lastCrossSignedState.initHostedChannel.maxAcceptedHtlcs) {
      throw TooManyAcceptedHtlcs(channelId, maximum = lastCrossSignedState.initHostedChannel.maxAcceptedHtlcs)
    }

    commits1
  }

  def sendFulfill(cmd: CMD_FULFILL_HTLC): Try[(HOSTED_DATA_COMMITMENTS, UpdateFulfillHtlc)] =
    getIncomingHtlcCrossSigned(cmd.id) match {
      case Some(add) if add.paymentHash == Crypto.sha256(cmd.r) =>
        val fulfill = UpdateFulfillHtlc(channelId, cmd.id, cmd.r)
        Success(addProposal(Left(fulfill)), fulfill)
      case Some(_) => Failure(InvalidHtlcPreimage(channelId, cmd.id))
      case None => Failure(UnknownHtlcId(channelId, cmd.id))
    }

  def receiveFulfill(fulfill: UpdateFulfillHtlc): Try[(HOSTED_DATA_COMMITMENTS, Origin, UpdateAddHtlc)] =
    // Technically peer may send a preimage any moment, even if new LCSS has not been reached yet so do our best and always resolve on getting it
    nextLocalSpec.findOutgoingHtlcById(fulfill.id) match {
      // We do not accepts fulfills after payment to peer has been failed (probably due to timeout so we failed in upstream already)
      case _ if failedToPeerHtlcLeftoverIds.contains(fulfill.id) || fulfilledByPeerHtlcLeftoverIds.contains(fulfill.id) => throw UnknownHtlcId(channelId, fulfill.id)
      case Some(htlc) if htlc.add.paymentHash == Crypto.sha256(fulfill.paymentPreimage) => Try((addProposal(Right(fulfill)), originChannels(fulfill.id), htlc.add))
      case Some(_) => Failure(InvalidHtlcPreimage(channelId, fulfill.id))
      case None => Failure(UnknownHtlcId(channelId, fulfill.id))
    }

  def sendFail(cmd: CMD_FAIL_HTLC, nodeSecret: PrivateKey): Try[(HOSTED_DATA_COMMITMENTS, UpdateFailHtlc)] =
    getIncomingHtlcCrossSigned(cmd.id) match {
      case Some(add) =>
        val failTry: Try[UpdateFailHtlc] = failHtlc(nodeSecret, cmd, add)
        failTry.map(updateFail => (addProposal(Left(updateFail)), updateFail))
      case None => Failure(UnknownHtlcId(channelId, cmd.id))
    }

  def sendFailMalformed(cmd: CMD_FAIL_MALFORMED_HTLC): Try[(HOSTED_DATA_COMMITMENTS, UpdateFailMalformedHtlc)] = {
    // BADONION bit must be set in failure_code
    if ((cmd.failureCode & FailureMessageCodecs.BADONION) == 0) {
      Failure(InvalidFailureCode(channelId))
    } else {
      getIncomingHtlcCrossSigned(cmd.id) match {
        case Some(_) =>
          val fail = UpdateFailMalformedHtlc(channelId, cmd.id, cmd.onionHash, cmd.failureCode)
          Success(addProposal(Left(fail)), fail)
        case None => Failure(UnknownHtlcId(channelId, cmd.id))
      }
    }
  }

  def receiveFail(fail: UpdateFailHtlc): Try[(HOSTED_DATA_COMMITMENTS, Origin, UpdateAddHtlc)] =
    // Unlike Fulfill for Fail/FailMalformed here we make sure they fail our cross-signed outgoing payment
    getOutgoingHtlcCrossSigned(fail.id) match {
      case Some(add) => Try((addProposal(Right(fail)), originChannels(fail.id), add))
      case None => Failure(UnknownHtlcId(channelId, fail.id))
    }

  def receiveFailMalformed(fail: UpdateFailMalformedHtlc): Try[(HOSTED_DATA_COMMITMENTS, Origin, UpdateAddHtlc)] = {
    // A receiving node MUST fail the channel if the BADONION bit in failure_code is not set for update_fail_malformed_htlc.
    if ((fail.failureCode & FailureMessageCodecs.BADONION) == 0) {
      Failure(InvalidFailureCode(channelId))
    } else {
      getOutgoingHtlcCrossSigned(fail.id) match {
        case Some(add) => Try((addProposal(Right(fail)), originChannels(fail.id), add))
        case None => Failure(UnknownHtlcId(channelId, fail.id))
      }
    }
  }
}

case class HostedState(channelId: ByteVector32, nextLocalUpdates: List[UpdateMessage], nextRemoteUpdates: List[UpdateMessage], lastCrossSignedState: LastCrossSignedState)

case class RemoteHostedStateResult(state: HostedState, isLocalSigValid: Boolean)

case class HostedParams(feeBase: MilliSatoshi, feeProportionalMillionth: Int, cltvDelta: CltvExpiryDelta, onChainRefundThreshold: Satoshi,
                        liabilityDeadlineBlockdays: Int, defaultCapacity: MilliSatoshi, defaultClientBalance: MilliSatoshi,
                        maxHtlcValueInFlightMsat: UInt64, htlcMinimum: MilliSatoshi, maxAcceptedHtlcs: Int)