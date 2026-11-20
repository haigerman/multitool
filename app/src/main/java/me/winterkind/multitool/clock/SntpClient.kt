package me.winterkind.multitool.clock

import android.os.SystemClock
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/** Result of one SNTP exchange. All values in milliseconds. */
data class SntpResult(
    val server: String,
    val address: String,
    /** Correction to add to the device clock to get the reference time. */
    val offsetMs: Long,
    /** Network round-trip delay without server processing time. */
    val roundTripMs: Long,
    val stratum: Int,
    val referenceId: String,
)

/**
 * Minimal SNTP client (RFC 4330). The PTB (Physikalisch-Technische Bundesanstalt) runs public
 * stratum-1 NTP servers ptbtime1..4.ptb.de that are directly disciplined by its caesium clocks.
 */
object SntpClient {
    val PTB_SERVERS = listOf("ptbtime1.ptb.de", "ptbtime2.ptb.de", "ptbtime3.ptb.de")

    private const val NTP_PORT = 123
    private const val PACKET_SIZE = 48
    private const val OFFSET_1900_TO_1970 = 2_208_988_800L

    fun query(host: String, timeoutMs: Int = 3000): SntpResult {
        val address = InetAddress.getByName(host)
        DatagramSocket().use { socket ->
            socket.soTimeout = timeoutMs
            val buffer = ByteArray(PACKET_SIZE)
            buffer[0] = 0x23 // LI = 0, VN = 4, Mode = 3 (client)

            // Wall clock for the absolute timestamps, monotonic clock for the elapsed time,
            // so a clock adjustment during the request cannot falsify the result.
            val t1 = System.currentTimeMillis()
            val t1Mono = SystemClock.elapsedRealtimeNanos()
            writeTimestamp(buffer, 40, t1)

            socket.send(DatagramPacket(buffer, buffer.size, address, NTP_PORT))
            val response = DatagramPacket(ByteArray(PACKET_SIZE), PACKET_SIZE)
            socket.receive(response)
            val t4 = t1 + (SystemClock.elapsedRealtimeNanos() - t1Mono) / 1_000_000L

            val data = response.data
            val leap = (data[0].toInt() shr 6) and 0x3
            val mode = data[0].toInt() and 0x7
            val stratum = data[1].toInt() and 0xff
            require(mode == 4 || mode == 5) { "Unerwarteter NTP-Modus $mode" }
            require(leap != 3 && stratum in 1..15) { "Server nicht synchronisiert (Stratum $stratum)" }

            val t2 = readTimestamp(data, 32)
            val t3 = readTimestamp(data, 40)
            // The server must echo our transmit timestamp as its originate timestamp.
            require((0..7).all { data[24 + it] == buffer[40 + it] }) { "Antwort passt nicht zur Anfrage" }

            val offset = ((t2 - t1) + (t3 - t4)) / 2
            val delay = (t4 - t1) - (t3 - t2)
            val refId = if (stratum == 1) {
                String(data, 12, 4, Charsets.US_ASCII).trim { it <= ' ' }
            } else {
                (12..15).joinToString(".") { (data[it].toInt() and 0xff).toString() }
            }
            return SntpResult(host, address.hostAddress ?: "", offset, delay, stratum, refId)
        }
    }

    private fun readTimestamp(buf: ByteArray, offset: Int): Long {
        val seconds = readUInt32(buf, offset)
        val fraction = readUInt32(buf, offset + 4)
        return (seconds - OFFSET_1900_TO_1970) * 1000L + (fraction * 1000L shr 32)
    }

    private fun writeTimestamp(buf: ByteArray, offset: Int, timeMs: Long) {
        val seconds = timeMs / 1000L + OFFSET_1900_TO_1970
        val fraction = (timeMs % 1000L shl 32) / 1000L
        for (i in 0..3) buf[offset + i] = (seconds shr (24 - 8 * i)).toByte()
        for (i in 0..3) buf[offset + 4 + i] = (fraction shr (24 - 8 * i)).toByte()
    }

    private fun readUInt32(buf: ByteArray, offset: Int): Long =
        ((buf[offset].toLong() and 0xff) shl 24) or
            ((buf[offset + 1].toLong() and 0xff) shl 16) or
            ((buf[offset + 2].toLong() and 0xff) shl 8) or
            (buf[offset + 3].toLong() and 0xff)
}
