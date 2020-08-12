package fr.acinq.eclair.channel

import akka.actor.{ActorRef, FSM, Props}
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.eclair.wire.UpdateMessage
import fr.acinq.eclair.{FSMDiagnosticActorLogging, NodeParams}

object HostedChannel {
  def props(nodeParams: NodeParams, remoteNodeId: PublicKey, router: ActorRef, relayer: ActorRef): Props = Props(new HostedChannel(nodeParams, remoteNodeId, router, relayer))
}

class HostedChannel(val nodeParams: NodeParams, remoteNodeId: PublicKey, router: ActorRef, relayer: ActorRef) extends FSM[State, HostedData] with FSMDiagnosticActorLogging[State, HostedData] {

}