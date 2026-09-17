package dev.imirror.receiver.airplay

import dev.imirror.receiver.util.Logger
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.Inet6Address
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
                    it.inetAddresses.toList().any { address ->
                        address is Inet4Address || address is Inet6Address
                    } }
            require(interfaces.isNotEmpty()) { "No multicast LAN interface" }
            var memberships = 0
            interfaces.forEach { nif ->
                val addresses = nif.inetAddresses.toList()
                listOf(
                    GROUP_V4 to addresses.any { it is Inet4Address },
                    GROUP_V6 to addresses.any { it is Inet6Address }
                ).forEach { (group, supported) ->
                    if (supported) {
                        runCatching { sock.joinGroup(InetSocketAddress(group, 5353), nif) }
                            .onSuccess { memberships++ }
                            .onFailure {
                                Logger.w("Supplemental mDNS could not join $group on ${nif.name}: ${it.message}")
                            }
                    }
                }
            }
            require(memberships > 0) { "No usable multicast membership" }
            Logger.i("Supplemental mDNS joined $memberships IPv4/IPv6 multicast memberships")
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
                if ((source !is Inet4Address && source !is Inet6Address) ||
                    source.isMulticastAddress || source.isAnyLocalAddress || source.isLoopbackAddress) continue
                // Only answer neighbors on an interface's actual same-family subnet.
                val nif = interfaces.firstOrNull { candidate -> candidate.interfaceAddresses.any { address ->
                    sameIpSubnet(source, address.address, address.networkPrefixLength.toInt())
                } } ?: continue
                val now = System.nanoTime() / 1_000_000
                if (now - (lastReplies[source] ?: Long.MIN_VALUE / 2) < 100) continue
                val reply = cache.answer(bytes, packet.length, packet.port != 5353) ?: continue
                sock.networkInterface = nif
                val multicastGroup = if (source is Inet4Address) GROUP_V4 else GROUP_V6
                sock.send(DatagramPacket(reply.bytes, reply.bytes.size,
                    if (reply.unicast) source else multicastGroup,
                    if (reply.unicast) packet.port else 5353))
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

    companion object {
        private val GROUP_V4 = InetAddress.getByName("224.0.0.251")
        private val GROUP_V6 = InetAddress.getByName("ff02::fb")
    }
}

/** Same-family subnet comparison for IPv4 and IPv6 mDNS neighbors. */
internal fun sameIpSubnet(a: InetAddress, b: InetAddress, prefix: Int): Boolean {
    val left = a.address
    val right = b.address
    if (left.size != right.size || prefix !in 1..left.size * 8) return false
    return left.indices.all { index ->
        val bits = (prefix - index * 8).coerceIn(0, 8)
        val mask = if (bits == 0) 0 else (255 shl (8 - bits)) and 255
        (left[index].toInt() and mask) == (right[index].toInt() and mask)
    }
}
