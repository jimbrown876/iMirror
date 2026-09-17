package dev.imirror.receiver.airplay

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.LinkProperties
import android.net.Network
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Looper
import android.system.OsConstants
import dev.imirror.receiver.service.ProtocolState
import dev.imirror.receiver.util.NetworkUtils
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.net.InetAddress
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class MdnsServiceTest {
    private val context = mockk<Context>(relaxed = true)
    private val nsd = mockk<NsdManager>(relaxed = true)
    private val connectivity = mockk<ConnectivityManager>(relaxed = true)
    private val wifi = mockk<WifiManager>(relaxed = true)
    private val multicastLock = mockk<WifiManager.MulticastLock>(relaxed = true)
    private val wifiPerformanceLock = mockk<WifiManager.WifiLock>(relaxed = true)
    private val network = mockk<Network>()
    private val replacementNetwork = mockk<Network>()
    private val responder = mockk<MdnsDiscoveryResponder>(relaxed = true)
    private val registrations = mutableListOf<Pair<NsdServiceInfo, NsdManager.RegistrationListener>>()
    private val states = mutableListOf<ProtocolState>()
    private val networkCallback = slot<ConnectivityManager.NetworkCallback>()
    private var responderEnded: (() -> Unit)? = null
    private lateinit var service: MdnsService

    @Before
    fun setup() {
        mockkObject(NetworkUtils)
        every { NetworkUtils.getMacAddress(context) } returns "02:11:22:33:44:55"
        every { NetworkUtils.getPersistentUuid(context) } returns "fixture-uuid"
        every { NetworkUtils.getDeviceName(context) } returns "Bedroom"
        every { context.getSystemService(Context.NSD_SERVICE) } returns nsd
        every { context.getSystemService(Context.CONNECTIVITY_SERVICE) } returns connectivity
        every { context.getSystemService(Context.WIFI_SERVICE) } returns wifi
        every { wifi.createMulticastLock(any()) } returns multicastLock
        every { wifi.createWifiLock(any<Int>(), any()) } returns wifiPerformanceLock
        every { network.hashCode() } returns 101
        every { replacementNetwork.hashCode() } returns 202
        every { connectivity.registerDefaultNetworkCallback(capture(networkCallback)) } just Runs
        every { connectivity.activeNetwork } returns network
        every { connectivity.getLinkProperties(network) } returns lan("192.168.1.180/24")
        every { responder.start() } returns true
        every { nsd.registerService(any(), any(), any()) } answers {
            registrations.add(firstArg<NsdServiceInfo>() to thirdArg<NsdManager.RegistrationListener>())
        }
        service = MdnsService(context, { states.add(it) }, discoveryResponderFactory = { onEnded ->
            responderEnded = onEnded
            responder
        })
    }

    @After fun teardown() {
        if (::service.isInitialized) service.stop()
        shadowOf(Looper.getMainLooper()).idle()
        unmockkAll()
    }

    private fun lan(cidr: String): LinkProperties = lanWithFlags(cidr to 0)

    private fun lanWithFlags(vararg entries: Pair<String, Int>): LinkProperties {
        val addresses = entries.map { (cidr, flags) ->
            val (host, prefix) = cidr.split("/", limit = 2)
            mockk<LinkAddress>().also { address ->
                every { address.address } returns InetAddress.getByName(host)
                every { address.prefixLength } returns prefix.toInt()
                every { address.flags } returns flags
            }
        }
        return mockk<LinkProperties>().also { properties ->
            every { properties.interfaceName } returns "wlan0"
            every { properties.linkAddresses } returns addresses
        }
    }

    private fun startOnline(name: String? = null) {
        service.start(name)
        shadowOf(Looper.getMainLooper()).idleFor(400, TimeUnit.MILLISECONDS)
    }

    private fun registered(index: Int, name: String? = null) {
        val (info, listener) = registrations[index]
        if (name != null) info.serviceName = name
        listener.onServiceRegistered(info)
    }

    @Test
    fun `registration is serialized and only complete after both callbacks`() {
        startOnline()
        assertEquals(1, registrations.size)
        assertEquals("_raop._tcp", registrations[0].first.serviceType)
        assertFalse(states.contains(ProtocolState.ADVERTISING))
        registered(0)
        assertEquals(2, registrations.size)
        assertEquals("_airplay._tcp", registrations[1].first.serviceType)
        assertEquals(7000, registrations[1].first.port)
        assertFalse(states.contains(ProtocolState.ADVERTISING))
        registered(1)
        assertEquals(listOf(ProtocolState.ADVERTISING), states)
        verify(exactly = 1) { responder.activate("Bedroom", "021122334455@Bedroom") }
        verify(exactly = 1) { multicastLock.acquire() }
        verify(exactly = 1) { wifiPerformanceLock.acquire() }
    }

    @Test
    fun `duplicate start and callbacks cannot create duplicate registrations`() {
        startOnline()
        service.start()
        registered(0)
        registered(0)
        registered(1)
        registered(1)
        assertEquals(2, registrations.size)
        assertEquals(1, states.count { it == ProtocolState.ADVERTISING })
    }

    @Test
    fun `responder uses actual names after independent collisions`() {
        startOnline()
        registered(0, "021122334455@Bedroom (2)")
        registered(1, "Bedroom (3)")
        verify { responder.activate("Bedroom (3)", "021122334455@Bedroom (2)") }
    }

    @Test
    fun `late callback after stop cannot register the next service`() {
        startOnline()
        service.stop()
        registered(0)
        assertEquals(1, registrations.size)
        assertEquals(ProtocolState.DISABLED, states.last())
        verify(exactly = 0) { responder.activate(any(), any()) }
        verify(exactly = 1) { multicastLock.release() }
        verify(exactly = 1) { wifiPerformanceLock.release() }
    }

    @Test
    fun `restart ignores prior generation callbacks`() {
        startOnline()
        service.restart("Living Room")
        shadowOf(Looper.getMainLooper()).idleFor(400, TimeUnit.MILLISECONDS)
        registered(0)
        assertEquals(2, registrations.size)
        registered(1)
        registered(2)
        verify { responder.activate("Living Room", "021122334455@Living Room") }
    }

    @Test
    fun `stop unregisters second listener even when first unregister throws`() {
        startOnline()
        registered(0)
        registered(1)
        every { nsd.unregisterService(registrations[1].second) } throws IllegalArgumentException("gone")
        service.stop()
        verify { nsd.unregisterService(registrations[0].second) }
        verify { responder.stop() }
    }

    @Test
    fun `already active error is not treated as a successful advertisement`() {
        startOnline()
        registrations[0].second.onRegistrationFailed(registrations[0].first, NsdManager.FAILURE_ALREADY_ACTIVE)
        assertEquals(1, registrations.size)
        assertEquals(ProtocolState.ERROR, states.last())
        assertFalse(states.contains(ProtocolState.ADVERTISING))

        shadowOf(Looper.getMainLooper()).idleFor(999, TimeUnit.MILLISECONDS)
        assertEquals(1, registrations.size)
        shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.MILLISECONDS)
        assertEquals(2, registrations.size)
    }

    @Test
    fun `offline start waits and one Wi-Fi rejoin refreshes both advertisements`() {
        every { connectivity.activeNetwork } returns null
        service.start()
        shadowOf(Looper.getMainLooper()).idleFor(5, TimeUnit.SECONDS)
        assertTrue(registrations.isEmpty())
        assertEquals(ProtocolState.ERROR, states.last())

        every { connectivity.getLinkProperties(network) } returns lan("192.168.1.181/24")
        networkCallback.captured.onAvailable(network)
        networkCallback.captured.onLinkPropertiesChanged(network, lan("192.168.1.181/24"))
        shadowOf(Looper.getMainLooper()).idleFor(400, TimeUnit.MILLISECONDS)
        assertEquals(1, registrations.size)
        registered(0)
        registered(1)

        networkCallback.captured.onLost(network)
        assertEquals(ProtocolState.ERROR, states.last())
        val oldRegistrationCount = registrations.size
        networkCallback.captured.onAvailable(network)
        networkCallback.captured.onLinkPropertiesChanged(network, lan("192.168.1.182/24"))
        shadowOf(Looper.getMainLooper()).idleFor(400, TimeUnit.MILLISECONDS)
        assertEquals(oldRegistrationCount + 1, registrations.size)
        verify(atLeast = 1) { responder.stop() }
    }

    @Test
    fun `unexpected responder termination schedules one bounded refresh`() {
        startOnline()
        registered(0)
        registered(1)

        responderEnded!!.invoke()
        responderEnded!!.invoke()
        shadowOf(Looper.getMainLooper()).idleFor(999, TimeUnit.MILLISECONDS)
        assertEquals(2, registrations.size)
        shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.MILLISECONDS)
        assertEquals(2, registrations.size)
        verify(exactly = 2) { responder.start() }
        assertFalse(states.contains(ProtocolState.ERROR))

        service.stop()
        shadowOf(Looper.getMainLooper()).idleFor(30, TimeUnit.SECONDS)
        assertEquals(2, registrations.size)
    }

    @Test
    fun `same address on a replacement Network refreshes stale advertisements`() {
        startOnline()
        registered(0)
        registered(1)
        val oldRegistrationCount = registrations.size

        networkCallback.captured.onAvailable(replacementNetwork)
        networkCallback.captured.onLinkPropertiesChanged(
            replacementNetwork,
            lan("192.168.1.180/24")
        )
        shadowOf(Looper.getMainLooper()).idleFor(399, TimeUnit.MILLISECONDS)
        assertEquals(oldRegistrationCount, registrations.size)
        shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.MILLISECONDS)
        assertEquals(oldRegistrationCount + 1, registrations.size)
        verify(atLeast = 1) { responder.stop() }
    }

    @Test
    fun `silent NSD callback is timed out and retried`() {
        startOnline()
        assertEquals(1, registrations.size)

        shadowOf(Looper.getMainLooper()).idleFor(4_999, TimeUnit.MILLISECONDS)
        assertEquals(1, registrations.size)
        shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.MILLISECONDS)
        assertEquals(ProtocolState.ERROR, states.last())
        assertEquals(1, registrations.size)

        shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS)
        assertEquals(2, registrations.size)
        registrations[0].second.onServiceRegistered(registrations[0].first)
        assertEquals(2, registrations.size)
    }

    @Test
    fun `supplemental responder startup failure is retried`() {
        every { responder.start() } returnsMany listOf(false, true)
        startOnline()
        assertEquals(1, registrations.size)
        registered(0)
        registered(1)
        assertEquals(ProtocolState.ADVERTISING, states.last())

        shadowOf(Looper.getMainLooper()).idleFor(999, TimeUnit.MILLISECONDS)
        assertEquals(2, registrations.size)
        shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.MILLISECONDS)
        assertEquals(2, registrations.size)
        verify(exactly = 2) { responder.start() }
        verify(exactly = 1) { responder.activate("Bedroom", "021122334455@Bedroom") }
    }

    @Test
    fun `LAN fingerprint tracks usable IPv4 and IPv6 address rotation`() {
        assertEquals("wlan0|4:192.168.1.180/24", MdnsService.lanFingerprint(lan("192.168.1.180/24")))

        val linkLocal = requireNotNull(MdnsService.lanFingerprint(lan("fe80::1/64")))
        assertTrue(linkLocal.startsWith("wlan0|6:"))

        val before = MdnsService.lanFingerprint(lanWithFlags(
            "192.168.1.180/24" to 0,
            "2603:1::10/64" to 0
        ))
        val after = MdnsService.lanFingerprint(lanWithFlags(
            "192.168.1.180/24" to 0,
            "2603:1::10/64" to OsConstants.IFA_F_DEPRECATED,
            "2603:1::11/64" to 0
        ))
        assertNotEquals(before, after)
        assertFalse(requireNotNull(after).contains("0:0:0:10/64"))
    }

    @Test
    fun `subnet matching supports IPv6 and rejects cross-family addresses`() {
        assertTrue(sameIpSubnet(
            InetAddress.getByName("fe80::1234"),
            InetAddress.getByName("fe80::5678"),
            64
        ))
        assertFalse(sameIpSubnet(
            InetAddress.getByName("fe80::1234"),
            InetAddress.getByName("fe81::5678"),
            64
        ))
        assertFalse(sameIpSubnet(
            InetAddress.getByName("192.168.1.10"),
            InetAddress.getByName("fe80::10"),
            64
        ))
    }
}
