/*
 * Copyright 2019 ACINQ SAS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fr.acinq.eclair.io

import java.net.InetSocketAddress

import akka.actor.{ActorRef, FSM, OneForOneStrategy, PoisonPill, Props, Status, SupervisorStrategy, Terminated}
import akka.event.Logging.MDC
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.eclair.Logs.LogCategory
import fr.acinq.eclair.crypto.Noise.KeyPair
import fr.acinq.eclair.crypto.TransportHandler
import fr.acinq.eclair.io.Monitoring.{Metrics, Tags}
import fr.acinq.eclair.io.Peer.CHANNELID_ZERO
import fr.acinq.eclair.io.PeerConnection.PeerConnectionConf
import fr.acinq.eclair.router._
import fr.acinq.eclair.wire._
import fr.acinq.eclair.{wire, _}
import scodec.Attempt
import scodec.bits.{BitVector, ByteVector}

import scala.compat.Platform
import scala.concurrent.duration._
import scala.util.Random

/**
 * This actor represents a protocol-level connection to another lightning node. As soon as a TCP connection is established
 * with a peer, a new [[PeerConnection]] will be spawned and the raw connection actor will be passed to it. This is done by
 * the [[Peer]] or a [[Client]] depending on whether it is an incoming or outgoing connection.
 *
 * There is one [[PeerConnection]] per TCP connection, and the [[PeerConnection]] actor dies when the underlying TCP connection
 * dies.
 *
 * The [[PeerConnection]] will be responsible for handling:
 * - the crypto handshake (BOLT 8)
 * - the application handshake (exchange of [[Init]] messages, BOLT 1)
 * - the routing messages (syncing and gossip, BOLT 7)
 *
 * Higher level messages (e.g. BOLT 2) are forwarded to the [[Peer]] actor, which is unique per logical peer (i.e. unique per
 * node id).
 *
 * Most of the time there will only be one [[PeerConnection]] per logical peer, but there is no strict constraint and the
 * [[Peer]] is responsible for deciding which [[PeerConnection]] stays and which should be killed.
 *
 * Created by PM on 11/03/2020.
 */
class PeerConnection(keyPair: KeyPair, conf: PeerConnectionConf, switchboard: ActorRef, router: ActorRef) extends FSMDiagnosticActorLogging[PeerConnection.State, PeerConnection.Data] {

  import PeerConnection._

  startWith(BEFORE_AUTH, Nothing)

  when(BEFORE_AUTH) {
    case Event(p: PendingAuth, _) =>
      val transport = p.transport_opt match {
        case Some(transport) => transport // used in tests to bypass encryption
        case None =>
          Metrics.PeerConnectionsConnecting.withTag(Tags.ConnectionState, Tags.ConnectionStates.Authenticating).increment()
          context.actorOf(TransportHandler.props(
            keyPair = keyPair,
            rs = p.remoteNodeId_opt.map(_.value),
            connection = p.connection,
            codec = LightningMessageCodecs.meteredLightningMessageCodec),
            name = "transport")
      }
      context.watch(transport)
      setTimer(AUTH_TIMER, AuthTimeout, conf.authTimeout)
      goto(AUTHENTICATING) using AuthenticatingData(p, transport)
  }

  when(AUTHENTICATING) {
    case Event(TransportHandler.HandshakeCompleted(remoteNodeId), d: AuthenticatingData) =>
      cancelTimer(AUTH_TIMER)
      import d.pendingAuth.address
      log.info(s"connection authenticated with $remoteNodeId@${address.getHostString}:${address.getPort} direction=${if (d.pendingAuth.outgoing) "outgoing" else "incoming"}")
      Metrics.PeerConnectionsConnecting.withTag(Tags.ConnectionState, Tags.ConnectionStates.Authenticated).increment()
      switchboard ! Authenticated(self, remoteNodeId, address, d.pendingAuth.outgoing, d.pendingAuth.origin_opt)
      goto(BEFORE_INIT) using BeforeInitData(remoteNodeId, d.pendingAuth, d.transport)

    case Event(AuthTimeout, _) =>
      log.warning(s"authentication timed out after ${conf.authTimeout}")
      stop(FSM.Normal)
  }

  when(BEFORE_INIT) {
    case Event(InitializeConnection(peer), d: BeforeInitData) =>
      d.transport ! TransportHandler.Listener(self)
      Metrics.PeerConnectionsConnecting.withTag(Tags.ConnectionState, Tags.ConnectionStates.Initializing).increment()
      val localFeatures = conf.overrideFeatures.get(d.remoteNodeId) match {
        case Some(f) => f
        case None =>
          // Eclair-mobile thinks feature bit 15 (payment_secret) is gossip_queries_ex which creates issues, so we mask
          // off basic_mpp and payment_secret. As long as they're provided in the invoice it's not an issue.
          // We use a long enough mask to account for future features.
          // TODO: remove that once eclair-mobile is patched.
          val tweakedFeatures = BitVector.bits(conf.features.bits.reverse.toIndexedSeq.zipWithIndex.map {
            // we disable those bits if they are set...
            case (true, 14) => false
            case (true, 15) => false
            case (true, 16) => false
            case (true, 17) => false
            // ... and leave the others untouched
            case (value, _) => value
          }).reverse.bytes.dropWhile(_ == 0)
          tweakedFeatures
      }
      log.info(s"using features=${localFeatures.toBin}")
      val localInit = wire.Init(localFeatures, TlvStream(InitTlv.Networks(conf.chainHash :: Nil)))
      d.transport ! localInit
      setTimer(INIT_TIMER, InitTimeout, conf.initTimeout)
      goto(INITIALIZING) using InitializingData(d.pendingAuth, d.remoteNodeId, d.transport, peer, localInit)
  }

  when(INITIALIZING) {
    heartbeat { // receiving the init message from remote will start the first ping timer
      case Event(remoteInit: wire.Init, d: InitializingData) =>
        cancelTimer(INIT_TIMER)
        d.transport ! TransportHandler.ReadAck(remoteInit)

        log.info(s"peer is using features=${remoteInit.features.toBin}, networks=${remoteInit.networks.mkString(",")}")

        if (remoteInit.networks.nonEmpty && !remoteInit.networks.contains(conf.chainHash)) {
          log.warning(s"incompatible networks (${remoteInit.networks}), disconnecting")
          d.pendingAuth.origin_opt.foreach(origin => origin ! Status.Failure(new RuntimeException("incompatible networks")))
          d.transport ! PoisonPill
          stay
        } else if (!Features.areSupported(remoteInit.features)) {
          log.warning("incompatible features, disconnecting")
          d.pendingAuth.origin_opt.foreach(origin => origin ! Status.Failure(new RuntimeException("incompatible features")))
          d.transport ! PoisonPill
          stay
        } else {
          Metrics.PeerConnectionsConnecting.withTag(Tags.ConnectionState, Tags.ConnectionStates.Initialized).increment()
          d.peer ! ConnectionReady(self, d.remoteNodeId, d.pendingAuth.address, d.pendingAuth.outgoing, d.localInit, remoteInit)

          d.pendingAuth.origin_opt.foreach(origin => origin ! "connected")

          def localHasFeature(f: Feature): Boolean = Features.hasFeature(d.localInit.features, f)

          def remoteHasFeature(f: Feature): Boolean = Features.hasFeature(remoteInit.features, f)

          val canUseChannelRangeQueries = localHasFeature(Features.ChannelRangeQueries) && remoteHasFeature(Features.ChannelRangeQueries)
          val canUseChannelRangeQueriesEx = localHasFeature(Features.ChannelRangeQueriesExtended) && remoteHasFeature(Features.ChannelRangeQueriesExtended)
          if (canUseChannelRangeQueries || canUseChannelRangeQueriesEx) {
            // if they support channel queries we don't send routing info yet, if they want it they will query us
            // we will query them, using extended queries if supported
            val flags_opt = if (canUseChannelRangeQueriesEx) Some(QueryChannelRangeTlv.QueryFlags(QueryChannelRangeTlv.QueryFlags.WANT_ALL)) else None
            if (conf.syncWhitelist.isEmpty || conf.syncWhitelist.contains(d.remoteNodeId)) {
              log.info(s"sending sync channel range query with flags_opt=$flags_opt")
              router ! SendChannelQuery(d.remoteNodeId, self, flags_opt = flags_opt)
            } else {
              log.info("not syncing with this peer")
            }
          } else if (remoteHasFeature(Features.InitialRoutingSync)) {
            // "old" nodes, do as before
            log.info("peer requested a full routing table dump")
            router ! GetRoutingState
          }

          // we will delay all rebroadcasts with this value in order to prevent herd effects (each peer has a different delay)
          val rebroadcastDelay = Random.nextInt(conf.maxRebroadcastDelay.toSeconds.toInt).seconds
          log.info(s"rebroadcast will be delayed by $rebroadcastDelay")
          context.system.eventStream.subscribe(self, classOf[Rebroadcast])

          goto(CONNECTED) using ConnectedData(d.remoteNodeId, d.transport, d.peer, d.localInit, remoteInit, rebroadcastDelay)
        }

      case Event(InitTimeout, _) =>
        log.warning(s"initialization timed out after ${conf.initTimeout}")
        stop(FSM.Normal)
    }
  }

  when(CONNECTED) {
    heartbeat {
      case Event(msg: LightningMessage, d: ConnectedData) if sender != d.transport => // if the message doesn't originate from the transport, it is an outgoing message
        d.transport forward msg
        stay

      case Event(SendPing, d: ConnectedData) =>
        if (d.expectedPong_opt.isEmpty) {
          // no need to use secure random here
          val pingSize = Random.nextInt(1000)
          val pongSize = Random.nextInt(1000)
          val ping = wire.Ping(pongSize, ByteVector.fill(pingSize)(0))
          setTimer(PingTimeout.toString, PingTimeout(ping), conf.pingTimeout, repeat = false)
          d.transport ! ping
          stay using d.copy(expectedPong_opt = Some(ExpectedPong(ping)))
        } else {
          log.warning(s"can't send ping, already have one in flight")
          stay
        }

      case Event(PingTimeout(ping), d: ConnectedData) =>
        if (conf.pingDisconnect) {
          log.warning(s"no response to ping=$ping, closing connection")
          d.transport ! PoisonPill
        } else {
          log.warning(s"no response to ping=$ping (ignored)")
        }
        stay

      case Event(ping@wire.Ping(pongLength, _), d: ConnectedData) =>
        d.transport ! TransportHandler.ReadAck(ping)
        if (pongLength <= 65532) {
          // see BOLT 1: we reply only if requested pong length is acceptable
          d.transport ! wire.Pong(ByteVector.fill(pongLength)(0.toByte))
        } else {
          log.warning(s"ignoring invalid ping with pongLength=${ping.pongLength}")
        }
        stay

      case Event(pong@wire.Pong(data), d: ConnectedData) =>
        d.transport ! TransportHandler.ReadAck(pong)
        d.expectedPong_opt match {
          case Some(ExpectedPong(ping, timestamp)) if ping.pongLength == data.length =>
            // we use the pong size to correlate between pings and pongs
            val latency = Platform.currentTime - timestamp
            log.debug(s"received pong with latency=$latency")
            cancelTimer(PingTimeout.toString())
          // we don't need to call scheduleNextPing here, the next ping was already scheduled when we received that pong
          case None =>
            log.debug(s"received unexpected pong with size=${data.length}")
        }
        stay using d.copy(expectedPong_opt = None)

      case Event(RoutingState(channels, nodes), d: ConnectedData) =>
        // let's send the messages
        def send(announcements: Iterable[_ <: LightningMessage]) = announcements.foldLeft(0) {
          case (c, ann) =>
            d.transport ! ann
            c + 1
        }

        log.info("sending the full routing table")
        val channelsSent = send(channels.map(_.ann))
        val nodesSent = send(nodes)
        val updatesSent = send(channels.flatMap(c => c.update_1_opt.toSeq ++ c.update_2_opt.toSeq))
        log.info("sent the full routing table: channels={} updates={} nodes={}", channelsSent, updatesSent, nodesSent)
        stay

      case Event(rebroadcast: Rebroadcast, d: ConnectedData) =>
        context.system.scheduler.scheduleOnce(d.rebroadcastDelay, self, DelayedRebroadcast(rebroadcast))(context.dispatcher)
        stay

      case Event(DelayedRebroadcast(rebroadcast), d: ConnectedData) =>

        /**
         * Send and count in a single iteration
         */
        def sendAndCount(msgs: Map[_ <: RoutingMessage, Set[GossipOrigin]]): Int = msgs.foldLeft(0) {
          case (count, (_, origins)) if origins.contains(RemoteGossip(self)) =>
            // the announcement came from this peer, we don't send it back
            count
          case (count, (msg, origins)) if !timestampInRange(msg, origins, d.gossipTimestampFilter) =>
            // the peer has set up a filter on timestamp and this message is out of range
            count
          case (count, (msg, _)) =>
            d.transport ! msg
            count + 1
        }

        val channelsSent = sendAndCount(rebroadcast.channels)
        val updatesSent = sendAndCount(rebroadcast.updates)
        val nodesSent = sendAndCount(rebroadcast.nodes)

        if (channelsSent > 0 || updatesSent > 0 || nodesSent > 0) {
          log.info(s"sent announcements: channels={} updates={} nodes={}", channelsSent, updatesSent, nodesSent)
        }
        stay

      case Event(msg: GossipTimestampFilter, d: ConnectedData) =>
        // special case: time range filters are peer specific and must not be sent to the router
        d.transport ! TransportHandler.ReadAck(msg)
        if (msg.chainHash != conf.chainHash) {
          log.warning("received gossip_timestamp_range message for chain {}, we're on {}", msg.chainHash, conf.chainHash)
          stay
        } else {
          log.info(s"setting up gossipTimestampFilter=$msg")
          // update their timestamp filter
          stay using d.copy(gossipTimestampFilter = Some(msg))
        }

      case Event(msg: wire.RoutingMessage, d: ConnectedData) =>
        msg match {
          case _: AnnouncementSignatures =>
            // this is actually for the channel
            d.transport ! TransportHandler.ReadAck(msg)
            d.peer ! msg
            stay
          case _: ChannelAnnouncement | _: ChannelUpdate | _: NodeAnnouncement if d.behavior.ignoreNetworkAnnouncement =>
            // this peer is currently under embargo!
            d.transport ! TransportHandler.ReadAck(msg)
          case _ =>
            // Note: we don't ack messages here because we don't want them to be stacked in the router's mailbox
            router ! Peer.PeerRoutingMessage(self, d.remoteNodeId, msg)
        }
        stay

      case Event(msg: LightningMessage, d: ConnectedData) =>
        // we acknowledge and pass all other messages to the peer
        d.transport ! TransportHandler.ReadAck(msg)
        d.peer ! msg
        stay

      case Event(readAck: TransportHandler.ReadAck, d: ConnectedData) =>
        // we just forward acks to the transport (e.g. from the router)
        d.transport forward readAck
        stay

      case Event(badMessage: BadMessage, d: ConnectedData) =>
        val behavior1 = badMessage match {
          case InvalidSignature(r) =>
            val bin: String = LightningMessageCodecs.meteredLightningMessageCodec.encode(r) match {
              case Attempt.Successful(b) => b.toHex
              case _ => "unknown"
            }
            log.error(s"peer sent us a routing message with invalid sig: r=$r bin=$bin")
            // for now we just return an error, maybe ban the peer in the future?
            // TODO: this doesn't actually disconnect the peer, once we introduce peer banning we should actively disconnect
            d.transport ! Error(CHANNELID_ZERO, ByteVector.view(s"bad announcement sig! bin=$bin".getBytes()))
            d.behavior
          case InvalidAnnouncement(c) =>
            // they seem to be sending us fake announcements?
            log.error(s"couldn't find funding tx with valid scripts for shortChannelId=${c.shortChannelId}")
            // for now we just return an error, maybe ban the peer in the future?
            // TODO: this doesn't actually disconnect the peer, once we introduce peer banning we should actively disconnect
            d.transport ! Error(CHANNELID_ZERO, ByteVector.view(s"couldn't verify channel! shortChannelId=${c.shortChannelId}".getBytes()))
            d.behavior
          case ChannelClosed(_) =>
            if (d.behavior.ignoreNetworkAnnouncement) {
              // we already are ignoring announcements, we may have additional notifications for announcements that were received right before our ban
              d.behavior.copy(fundingTxAlreadySpentCount = d.behavior.fundingTxAlreadySpentCount + 1)
            } else if (d.behavior.fundingTxAlreadySpentCount < MAX_FUNDING_TX_ALREADY_SPENT) {
              d.behavior.copy(fundingTxAlreadySpentCount = d.behavior.fundingTxAlreadySpentCount + 1)
            } else {
              log.warning(s"peer sent us too many channel announcements with funding tx already spent (count=${d.behavior.fundingTxAlreadySpentCount + 1}), ignoring network announcements for $IGNORE_NETWORK_ANNOUNCEMENTS_PERIOD")
              setTimer(ResumeAnnouncements.toString, ResumeAnnouncements, IGNORE_NETWORK_ANNOUNCEMENTS_PERIOD, repeat = false)
              d.behavior.copy(fundingTxAlreadySpentCount = d.behavior.fundingTxAlreadySpentCount + 1, ignoreNetworkAnnouncement = true)
            }
        }
        stay using d.copy(behavior = behavior1)

      case Event(ResumeAnnouncements, d: ConnectedData) =>
        log.info(s"resuming processing of network announcements for peer")
        stay using d.copy(behavior = d.behavior.copy(fundingTxAlreadySpentCount = 0, ignoreNetworkAnnouncement = false))
    }
  }

  whenUnhandled {
    case Event(unhandledMsg: LightningMessage, d: HasTransport) =>
      // we ack unhandled messages because we don't want to block further reads on the connection
      d.transport ! TransportHandler.ReadAck(unhandledMsg)
      log.warning(s"acking unhandled message $unhandledMsg in state $stateName from sender=$sender")
      stay

    case Event(Terminated(actor), d: HasTransport) if actor == d.transport =>
      Logs.withMdc(diagLog)(Logs.mdc(category_opt = Some(Logs.LogCategory.CONNECTION))) {
        log.info("transport died, stopping")
      }
      stop(FSM.Normal)

    case Event(_: Rebroadcast, _) => stay // ignored

    case Event(_: DelayedRebroadcast, _) => stay // ignored

    case Event(_: RoutingState, _) => stay // ignored

    case Event(_: TransportHandler.ReadAck, _) => stay // ignored

    case Event(SendPing, _) => stay // we got disconnected in the meantime

    case Event(_: Pong, _) => stay // we got disconnected before receiving the pong

    case Event(_: PingTimeout, _) => stay // we got disconnected after sending a ping

    case Event(_: BadMessage, _) => stay // we got disconnected while syncing

  }

  onTransition {
    case _ -> CONNECTED => Metrics.PeerConnectionsConnected.withoutTags().increment()
  }

  onTermination {
    case StopEvent(_, CONNECTED, _: ConnectedData) => Metrics.PeerConnectionsConnected.withoutTags().decrement()
  }

  /**
   * As long as we receive messages from our peer, we consider it is online and don't send ping requests. If we don't
   * hear from the peer, we send pings and expect timely answers, otherwise we'll close the connection.
   *
   * This is implemented by scheduling a ping request every 30 seconds, and pushing it back every time we receive a
   * message from the peer.
   *
   */
  def heartbeat(s: StateFunction): StateFunction = {
    case event if s.isDefinedAt(event) =>
      event match {
        case Event(_: LightningMessage, d: HasTransport) if sender == d.transport =>
          // this is a message from the peer, he's alive, we can delay the next ping
          scheduleNextPing()
        case _ => ()
      }
      s(event)
  }

  def scheduleNextPing(): Unit = {
    setTimer(SEND_PING_TIMER, SendPing, 30 seconds)
  }

  initialize()

  // we should not restart a failing transport-handler (NB: logging is handled in the transport)
  override val supervisorStrategy: OneForOneStrategy = OneForOneStrategy(loggingEnabled = false) { case _ => SupervisorStrategy.Stop }

  override def mdc(currentMessage: Any): MDC = {
    val (category_opt, remoteNodeId_opt) = stateData match {
      case Nothing => (Some(LogCategory.CONNECTION), None)
      case d: AuthenticatingData => (Some(LogCategory.CONNECTION), d.pendingAuth.remoteNodeId_opt)
      case d: BeforeInitData => (Some(LogCategory.CONNECTION), Some(d.remoteNodeId))
      case d: InitializingData => (Some(LogCategory.CONNECTION), Some(d.remoteNodeId))
      case d: ConnectedData => (LogCategory(currentMessage), Some(d.remoteNodeId))
    }
    Logs.mdc(category_opt, remoteNodeId_opt)
  }
}

object PeerConnection {

  // @formatter:off
  val AUTH_TIMER = "auth"
  val INIT_TIMER = "init"
  val SEND_PING_TIMER = "send_ping"
  // @formatter:on

  // @formatter:off
  case object AuthTimeout
  case object InitTimeout
  case object SendPing
  case object ResumeAnnouncements
  // @formatter:on

  val IGNORE_NETWORK_ANNOUNCEMENTS_PERIOD: FiniteDuration = 5 minutes

  // @formatter:off
  val MAX_FUNDING_TX_ALREADY_SPENT = 10
  // @formatter:on

  def props(keyPair: KeyPair, conf: PeerConnectionConf, switchboard: ActorRef, router: ActorRef): Props = Props(new PeerConnection(keyPair, conf, switchboard, router))

  case class PeerConnectionConf(chainHash: ByteVector32,
                                authTimeout: FiniteDuration,
                                initTimeout: FiniteDuration,
                                pingInterval: FiniteDuration,
                                pingTimeout: FiniteDuration,
                                pingDisconnect: Boolean,
                                features: ByteVector,
                                overrideFeatures: Map[PublicKey, ByteVector],
                                syncWhitelist: Set[PublicKey],
                                maxRebroadcastDelay: FiniteDuration
                               )

  // @formatter:off

  sealed trait Data
  sealed trait HasTransport { this: Data => def transport: ActorRef }
  case object Nothing extends Data
  case class AuthenticatingData(pendingAuth: PendingAuth, transport: ActorRef) extends Data with HasTransport
  case class BeforeInitData(remoteNodeId: PublicKey, pendingAuth: PendingAuth, transport: ActorRef) extends Data with HasTransport
  case class InitializingData(pendingAuth: PendingAuth, remoteNodeId: PublicKey, transport: ActorRef, peer: ActorRef, localInit: wire.Init) extends Data with HasTransport
  case class ConnectedData(remoteNodeId: PublicKey, transport: ActorRef, peer: ActorRef, localInit: wire.Init, remoteInit: wire.Init, rebroadcastDelay: FiniteDuration, gossipTimestampFilter: Option[GossipTimestampFilter] = None, behavior: Behavior = Behavior(), expectedPong_opt: Option[ExpectedPong] = None) extends Data with HasTransport
  case class ExpectedPong(ping: Ping, timestamp: Long = Platform.currentTime)
  case class PingTimeout(ping: Ping)

  sealed trait State
  case object BEFORE_AUTH extends State
  case object AUTHENTICATING extends State
  case object BEFORE_INIT extends State
  case object INITIALIZING extends State
  case object CONNECTED extends State

  case class PendingAuth(connection: ActorRef, remoteNodeId_opt: Option[PublicKey], address: InetSocketAddress, origin_opt: Option[ActorRef], transport_opt: Option[ActorRef] = None) {
    def outgoing: Boolean = remoteNodeId_opt.isDefined // if this is an outgoing connection, we know the node id in advance
  }
  case class Authenticated(peerConnection: ActorRef, remoteNodeId: PublicKey, address: InetSocketAddress, outgoing: Boolean, origin_opt: Option[ActorRef])
  case class InitializeConnection(peer: ActorRef)
  case class ConnectionReady(peerConnection: ActorRef, remoteNodeId: PublicKey, address: InetSocketAddress, outgoing: Boolean, localInit: wire.Init, remoteInit: wire.Init)

  case class DelayedRebroadcast(rebroadcast: Rebroadcast)

  sealed trait BadMessage
  case class InvalidSignature(r: RoutingMessage) extends BadMessage
  case class InvalidAnnouncement(c: ChannelAnnouncement) extends BadMessage
  case class ChannelClosed(c: ChannelAnnouncement) extends BadMessage

  case class Behavior(fundingTxAlreadySpentCount: Int = 0, ignoreNetworkAnnouncement: Boolean = false)

  // @formatter:on

  /**
   * PeerConnection may want to filter announcements based on timestamp.
   *
   * @param gossipTimestampFilter_opt optional gossip timestamp range
   * @return
   *         - true if this is our own gossip
   *         - true if there is a filter and msg has no timestamp, or has one that matches the filter
   *         - false otherwise
   */
  def timestampInRange(msg: RoutingMessage, origins: Set[GossipOrigin], gossipTimestampFilter_opt: Option[GossipTimestampFilter]): Boolean = {
    // For our own gossip, we should ignore the peer's timestamp filter.
    val isOurGossip = msg match {
      case _ if origins.contains(LocalGossip) => true
      case _ => false
    }
    // Otherwise we check if this message has a timestamp that matches the timestamp filter.
    val matchesFilter = (msg, gossipTimestampFilter_opt) match {
      case (_, None) => false // BOLT 7: A node which wants any gossip messages would have to send this, otherwise [...] no gossip messages would be received.
      case (hasTs: HasTimestamp, Some(GossipTimestampFilter(_, firstTimestamp, timestampRange))) => hasTs.timestamp >= firstTimestamp && hasTs.timestamp <= firstTimestamp + timestampRange
      case _ => true // if there is a filter and message doesn't have a timestamp (e.g. channel_announcement), then we send it
    }
    isOurGossip || matchesFilter
  }

}

