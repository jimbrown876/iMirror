package dev.imirror.receiver.airplay

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import dev.imirror.receiver.service.ProtocolState
import dev.imirror.receiver.util.NetworkUtils
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MdnsServiceTest {
    private val context = mockk<Context>(relaxed = true)
    private val nsd = mockk<NsdManager>(relaxed = true)
    private val responder = mockk<MdnsDiscoveryResponder>(relaxed = true)
    private val registrations = mutableListOf<Pair<NsdServiceInfo, NsdManager.RegistrationListener>>()
    private val states = mutableListOf<ProtocolState>()
    private lateinit var service: MdnsService

    @Before
    fun setup() {
        mockkObject(NetworkUtils)
        every { NetworkUtils.getMacAddress(context) } returns "02:11:22:33:44:55"
        every { NetworkUtils.getPersistentUuid(context) } returns "fixture-uuid"
        every { NetworkUtils.getDeviceName(context) } returns "Bedroom"
        every { context.getSystemService(Context.NSD_SERVICE) } returns nsd
        every { nsd.registerService(any(), any(), any()) } answers {
            registrations.add(firstArg<NsdServiceInfo>() to thirdArg<NsdManager.RegistrationListener>())
        }
        service = MdnsService(context, { states.add(it) }, discoveryResponderFactory = { responder })
    }

    @After
    fun teardown() { unmockkAll() }

    private fun registered(index: Int, name: String? = null) {
        val (info, listener) = registrations[index]
        if (name != null) info.serviceName = name
        listener.onServiceRegistered(info)
    }

    @Test
    fun `registration is serialized and only complete after both callbacks`() {
        service.start()
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
    }

    @Test
    fun `duplicate start and callbacks cannot create duplicate registrations`() {
        service.start()
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
        service.start()
        registered(0, "021122334455@Bedroom (2)")
        registered(1, "Bedroom (3)")
        verify { responder.activate("Bedroom (3)", "021122334455@Bedroom (2)") }
    }

    @Test
    fun `late callback after stop cannot register the next service`() {
        service.start()
        service.stop()
        registered(0)
        assertEquals(1, registrations.size)
        assertEquals(ProtocolState.DISABLED, states.last())
        verify(exactly = 0) { responder.activate(any(), any()) }
    }

    @Test
    fun `restart ignores prior generation callbacks`() {
        service.start()
        service.restart("Living Room")
        registered(0)
        assertEquals(2, registrations.size)
        registered(1)
        registered(2)
        verify { responder.activate("Living Room", "021122334455@Living Room") }
    }

    @Test
    fun `stop unregisters second listener even when first unregister throws`() {
        service.start()
        registered(0)
        registered(1)
        every { nsd.unregisterService(registrations[1].second) } throws IllegalArgumentException("gone")
        service.stop()
        verify { nsd.unregisterService(registrations[0].second) }
        verify { responder.stop() }
    }

    @Test
    fun `already active error is not treated as a successful advertisement`() {
        service.start()
        registrations[0].second.onRegistrationFailed(registrations[0].first, NsdManager.FAILURE_ALREADY_ACTIVE)
        assertEquals(1, registrations.size)
        assertEquals(ProtocolState.ERROR, states.last())
        assertFalse(states.contains(ProtocolState.ADVERTISING))
    }
}
