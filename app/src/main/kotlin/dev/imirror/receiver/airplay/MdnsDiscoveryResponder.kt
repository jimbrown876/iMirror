package dev.imirror.receiver.airplay

import dev.imirror.receiver.util.Logger
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface

/** Supplemental complete answers using records learned from the local NSD daemon. */
class MdnsDiscoveryResponder(
    private val onUnexpectedTermination: () -> Unit = {}
) {
    private val cache = MdnsResponseCache()
    private var socket: MulticastSocket? = null

    @Synchronized
    fun start(): Boolean {
        if (socket != null) return true
        var sock: MulticastSocket? = null
        try {
            sock = MulticastSocket(null)
            sock.reuseAddress = true
            sock.bind(InetSocketAddress(5353))
            sock.timeToLive = 255
            val interfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { it.isUp && !it.isLoopback && it.supportsMulticast() &&
                    it.inetAddresses.toList().any { address -> address is Inet4Address } }
            require(interfaces.isNotEmpty()) { "No multicast LAN interface" }
            interfaces.forEach { sock.joinGroup(InetSocketAddress(GROUP, 5353), it) }
            socket = sock
            Thread({ receive(sock, interfaces) }, "iMirror-MdnsDiscovery").apply {
                isDaemon = true
                start()
            }
            return true
        } catch (e: Exception) {
            sock?.close()
            Logger.w("Supplemental mDNS failed to start: ${e.message}")
            return false
        }
    }

    fun activate(airPlayName: String, raopName: String) = cache.activate(airPlayName, raopName)

    @Synchronized
    fun stop() {
        socket?.close()
        socket = null
    }

    private fun receive(sock: MulticastSocket, interfaces: List<NetworkInterface>) {
        val bytes = ByteArray(9000)
        val lastReplies = linkedMapOf<InetAddress, Long>()
        try {
            while (!sock.isClosed) {
                val packet = DatagramPacket(bytes, bytes.size)
                sock.receive(packet)
                val source = packet.address
                // Enumerate on each packet: Wi-Fi Direct interfaces can appear during registration.
                val own = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                    .flatMap { it.inetAddresses.toList() }
                if (source in own) {
                    if (packet.port == 5353) cache.rememberLocalResponse(bytes, packet.length)
                    continue
                }
                if (source !is Inet4Address || source.isMulticastAddress || source.isAnyLocalAddress) continue
                // Only answer neighbors on an interface's actual IPv4 subnet.
                val nif = interfaces.firstOrNull { candidate -> candidate.interfaceAddresses.any { address ->
                    val local = address.address as? Inet4Address ?: return@any false
                    sameSubnet(source, local, address.networkPrefixLength.toInt())
                } } ?: continue
                val now = System.nanoTime() / 1_000_000
                if (now - (lastReplies[source] ?: Long.MIN_VALUE / 2) < 100) continue
                val reply = cache.answer(bytes, packet.length, packet.port != 5353) ?: continue
                sock.networkInterface = nif
                sock.send(DatagramPacket(reply.bytes, reply.bytes.size,
                    if (reply.unicast) source else GROUP, if (reply.unicast) packet.port else 5353))
                lastReplies[source] = now
                while (lastReplies.size > 64) lastReplies.remove(lastReplies.keys.first())
            }
        } catch (e: Exception) {
            if (!sock.isClosed) Logger.w("Supplemental mDNS ended: ${e.message}")
        } finally {
            sock.close()
            val endedUnexpectedly = synchronized(this) {
                if (socket === sock) {
                    socket = null
                    true
                } else {
                    false
                }
            }
            if (endedUnexpectedly) onUnexpectedTermination()
        }
    }

    private fun sameSubnet(a: Inet4Address, b: Inet4Address, prefix: Int): Boolean {
        if (prefix !in 1..32) return false
        return (0..3).all { i ->
            val bits = (prefix - i * 8).coerceIn(0, 8)
            val mask = if (bits == 0) 0 else (255 shl (8 - bits)) and 255
            (a.address[i].toInt() and mask) == (b.address[i].toInt() and mask)
        }
    }

    companion object {
        private val GROUP = InetAddress.getByName("224.0.0.251")
    }
}
