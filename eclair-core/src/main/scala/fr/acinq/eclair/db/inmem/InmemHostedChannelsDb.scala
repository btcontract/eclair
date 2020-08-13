package fr.acinq.eclair.db.inmem

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.eclair.channel.HOSTED_DATA_COMMITMENTS
import fr.acinq.eclair.db.HostedChannelsDb
import scodec.bits.ByteVector

class InmemHostedChannelsDb extends HostedChannelsDb {
  var store = Map.empty[ByteVector32, HOSTED_DATA_COMMITMENTS]

  def addOrUpdateChannel(state: HOSTED_DATA_COMMITMENTS): Unit = store = store.updated(state.channelId, state)

  def updateSecret(channelId: ByteVector32, secret: ByteVector): Unit = {}

  def getChannel(channelId: ByteVector32): Option[HOSTED_DATA_COMMITMENTS] = store.get(channelId)

  def getChannelBySecret(secret: ByteVector): Option[HOSTED_DATA_COMMITMENTS] = None

  def listHotChannels(): Seq[HOSTED_DATA_COMMITMENTS] = store.values.toList
}