package com.musicd.lite.roon

import com.musicd.lite.Log
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.util.UUID

/**
 * Roon Core discovery.
 *
 * SOOD is Roon's own UDP discovery protocol: a query is multicast to
 * 239.255.90.90:9003 (and broadcast, since multicast is unreliable on consumer
 * Wi-Fi), and every Core on the segment answers with its `unique_id` and the
 * TCP port its extension API listens on.
 *
 * Packet layout, from `sood.js` in node-roon-api:
 *
 *     "SOOD" | 0x02 | 'Q' or 'R' | property*
 *     property := name_len:u8 | name | value_len:u16be | value
 *
 * A value length of 0xFFFF means null.
 */
object Sood {

    private const val TAG = "Sood"
    const val PORT = 9003
    const val MULTICAST_IP = "239.255.90.90"

    /** The service id every Roon Core answers to. */
    private const val ROON_SERVICE_ID = "00720724-5143-4a9b-abac-0e50cba674bb"

    data class Found(
        val host: String,
        val port: Int,
        val uniqueId: String,
        val displayName: String?
    )

    private fun writeProp(out: ByteArrayOutputStream, name: String, value: String) {
        val n = name.toByteArray(Charsets.UTF_8)
        out.write(n.size and 0xFF)
        out.write(n)
        val v = value.toByteArray(Charsets.UTF_8)
        out.write((v.size shr 8) and 0xFF)
        out.write(v.size and 0xFF)
        out.write(v)
    }

    fun buildQuery(): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("SOOD".toByteArray(Charsets.UTF_8))
        out.write(2)
        out.write('Q'.code)
        writeProp(out, "_tid", UUID.randomUUID().toString())
        writeProp(out, "query_service_id", ROON_SERVICE_ID)
        return out.toByteArray()
    }

    /** Returns the properties of a SOOD reply, or null if this isn't one. */
    fun parseReply(buf: ByteArray, length: Int): Map<String, String?>? {
        if (length < 6) return null
        if (String(buf, 0, 4, Charsets.UTF_8) != "SOOD") return null
        if (buf[4].toInt() != 2) return null
        if (buf[5].toInt().toChar() != 'R') return null

        val props = HashMap<String, String?>()
        var pos = 6
        while (pos < length) {
            val nameLen = buf[pos++].toInt() and 0xFF
            if (nameLen == 0 || pos + nameLen > length) return null
            val name = String(buf, pos, nameLen, Charsets.UTF_8)
            pos += nameLen
            if (pos + 2 > length) return null
            val valLen = ((buf[pos++].toInt() and 0xFF) shl 8) or (buf[pos++].toInt() and 0xFF)
            when {
                valLen == 0xFFFF -> props[name] = null
                valLen == 0 -> props[name] = ""
                pos + valLen > length -> return null
                else -> {
                    props[name] = String(buf, pos, valLen, Charsets.UTF_8)
                    pos += valLen
                }
            }
        }
        return props
    }

    /**
     * Blocks for up to [timeoutMs], reporting every Core that answers.
     * Re-sends the query every 1.5s because the first burst is easily lost.
     *
     * The caller must hold a WifiManager.MulticastLock, otherwise the replies
     * are filtered out before they reach us.
     */
    fun discover(timeoutMs: Long, onFound: (Found) -> Unit) {
        val socket = MulticastSocket()
        try {
            socket.timeToLive = 32
            socket.broadcast = true
            socket.soTimeout = 400

            val query = buildQuery()
            val targets = ArrayList<InetAddress>()
            targets += InetAddress.getByName(MULTICAST_IP)
            targets += InetAddress.getByName("255.255.255.255")
            targets += subnetBroadcasts()

            val deadline = System.currentTimeMillis() + timeoutMs
            var nextSend = 0L
            val seen = HashSet<String>()
            val buf = ByteArray(2048)

            while (System.currentTimeMillis() < deadline) {
                val now = System.currentTimeMillis()
                if (now >= nextSend) {
                    for (target in targets) {
                        try {
                            socket.send(DatagramPacket(query, query.size, target, PORT))
                        } catch (e: Exception) {
                            Log.d(TAG, "send to $target failed: ${e.message}")
                        }
                    }
                    nextSend = now + 1500
                }

                val packet = DatagramPacket(buf, buf.size)
                try {
                    socket.receive(packet)
                } catch (e: SocketTimeoutException) {
                    continue
                }

                val props = parseReply(packet.data, packet.length) ?: continue
                val uniqueId = props["unique_id"] ?: continue
                val httpPort = props["http_port"]?.toIntOrNull() ?: continue
                val host = props["_replyaddr"] ?: packet.address.hostAddress ?: continue
                if (!seen.add(uniqueId)) continue

                Log.i(TAG, "found core $uniqueId at $host:$httpPort")
                onFound(Found(host, httpPort, uniqueId, props["name"] ?: props["display_name"]))
            }
        } finally {
            try { socket.close() } catch (_: Exception) { }
        }
    }

    private fun subnetBroadcasts(): List<InetAddress> {
        val out = ArrayList<InetAddress>()
        try {
            for (iface in NetworkInterface.getNetworkInterfaces()) {
                if (!iface.isUp || iface.isLoopback) continue
                for (addr in iface.interfaceAddresses) {
                    addr.broadcast?.let { out += it }
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "enumerating interfaces failed: ${e.message}")
        }
        return out
    }
}
