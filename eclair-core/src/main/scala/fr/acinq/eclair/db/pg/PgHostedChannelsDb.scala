package fr.acinq.eclair.db.pg

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.eclair.wire.HostedChannelCodecs.HOSTED_DATA_COMMITMENTS_Codec
import fr.acinq.eclair.channel.HOSTED_DATA_COMMITMENTS
import fr.acinq.eclair.db.HostedChannelsDb
import fr.acinq.eclair.db.pg.PgUtils.DatabaseLock
import fr.acinq.eclair.db.Monitoring.Metrics.withMetrics
import grizzled.slf4j.Logging
import javax.sql.DataSource
import scodec.bits.{BitVector, ByteVector}

class PgHostedChannelsDb(implicit ds: DataSource, lock: DatabaseLock) extends HostedChannelsDb with Logging {

  import PgUtils._
  import lock._

  val DB_NAME = "hosted_channels"
  val CURRENT_VERSION = 1

  inTransaction { pg =>
    using(pg.createStatement()) { statement =>
      getVersion(statement, DB_NAME, CURRENT_VERSION) match {
        case CURRENT_VERSION =>
          statement.executeUpdate("CREATE TABLE IF NOT EXISTS local_hosted_channels (channel_id TEXT NOT NULL PRIMARY KEY, short_channel_id BIGINT NOT NULL UNIQUE, in_flight_htlcs BIGINT NOT NULL, in_flight_incoming_amount BIGINT NOT NULL, in_flight_outgoing_amount BIGINT NOT NULL, capacity BIGINT NOT NULL, created_at BIGINT NOT NULL, data BYTEA NOT NULL, secret BYTEA NOT NULL)")
          statement.executeUpdate("CREATE INDEX IF NOT EXISTS in_flight_htlcs_idx ON local_hosted_channels(in_flight_htlcs)")
          statement.executeUpdate("CREATE INDEX IF NOT EXISTS secret_idx ON local_hosted_channels(secret)")
        case unknownVersion => throw new RuntimeException(s"Unknown version of DB $DB_NAME found, version=$unknownVersion")
      }
    }
  }

  override def addOrUpdateChannel(state: HOSTED_DATA_COMMITMENTS): Unit = withMetrics("hosted-channels/add-or-update-channel") {
    withLock { pg =>
      val data = HOSTED_DATA_COMMITMENTS_Codec.encode(state).require.toByteArray
      val shortChannelId = state.channelUpdate.shortChannelId.toLong
      val inFlightHtlcs = state.currentAndNextInFlightHtlcs.size
      val inFlightIncomingAmount = state.localSpec.inFlightIncoming.toLong
      val inFlightOutgoingAmount = state.localSpec.inFlightOutgoing.toLong
      val channelId = state.channelId.toHex

      using(pg.prepareStatement("UPDATE local_hosted_channels SET short_channel_id=?, in_flight_htlcs=?, in_flight_incoming_amount=?, in_flight_outgoing_amount=?, data=? WHERE channel_id=?")) { update =>
        update.setLong(1, shortChannelId)
        update.setLong(2, inFlightHtlcs)
        update.setLong(3, inFlightIncomingAmount)
        update.setLong(4, inFlightOutgoingAmount)
        update.setBytes(5, data)
        update.setString(6, channelId)
        if (update.executeUpdate() == 0) {
          val capacity = state.lastCrossSignedState.initHostedChannel.channelCapacityMsat.toLong
          using(pg.prepareStatement("INSERT INTO local_hosted_channels VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) { statement =>
            statement.setString(1, channelId)
            statement.setLong(2, shortChannelId)
            statement.setLong(3, inFlightHtlcs)
            statement.setLong(4, inFlightIncomingAmount)
            statement.setLong(5, inFlightOutgoingAmount)
            statement.setLong(6, capacity)
            statement.setLong(7, System.currentTimeMillis())
            statement.setBytes(8, data)
            statement.setBytes(9, Array.emptyByteArray)
            statement.executeUpdate()
          }
        }
      }
    }
  }

  def updateSecret(channelId: ByteVector32, secret: ByteVector): Unit = withMetrics("hosted-channels/update-secret") {
    withLock { pg =>
      using(pg.prepareStatement("UPDATE local_hosted_channels SET secret=? WHERE channel_id=?")) { statement =>
        statement.setBytes(1, secret.toArray)
        statement.setString(2, channelId.toHex)
        statement.executeUpdate()
      }
    }
  }

  def getChannel(channelId: ByteVector32): Option[HOSTED_DATA_COMMITMENTS] = withMetrics("hosted-channels/get-channel") {
    withLock { pg =>
      using(pg.prepareStatement("SELECT data FROM local_hosted_channels WHERE channel_id=?")) { statement =>
        statement.setString(1, channelId.toHex)
        val rs = statement.executeQuery()
        if (rs.next()) {
          Some(HOSTED_DATA_COMMITMENTS_Codec.decode(BitVector(rs.getBytes("data"))).require.value)
        } else {
          None
        }
      }
    }
  }

  def getChannelBySecret(secret: ByteVector): Option[HOSTED_DATA_COMMITMENTS] = withMetrics("hosted-channels/get-channel-by-secret") {
    withLock { pg =>
      using(pg.prepareStatement("SELECT data FROM local_hosted_channels WHERE secret=?")) { statement =>
        statement.setBytes(1, secret.toArray)
        val rs = statement.executeQuery()
        if (rs.next()) {
          Some(HOSTED_DATA_COMMITMENTS_Codec.decode(BitVector(rs.getBytes("data"))).require.value)
        } else {
          None
        }
      }
    }
  }

  def listHotChannels(): Seq[HOSTED_DATA_COMMITMENTS] = withMetrics("hosted-channels/list-hot-channels") {
    withLock { pg =>
      using(pg.createStatement) { statement =>
        val rs = statement.executeQuery("SELECT data FROM local_hosted_channels WHERE in_flight_htlcs > 0")
        codecSequence(rs, HOSTED_DATA_COMMITMENTS_Codec)
      }
    }
  }
}
