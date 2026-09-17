package dev.imirror.receiver.airplay

import dev.imirror.receiver.service.ProtocolState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaSessionGateTest {
    @Test fun `discovery transitions are suppressed only while media is active`() {
        val gate = MediaSessionGate()
        assertEquals(ProtocolState.ADVERTISING, gate.filterDiscoveryState(ProtocolState.ADVERTISING))
        assertEquals(ProtocolState.ERROR, gate.filterDiscoveryState(ProtocolState.ERROR))
        gate.activate()
        assertNull(gate.filterDiscoveryState(ProtocolState.ADVERTISING))
        assertNull(gate.filterDiscoveryState(ProtocolState.ERROR))
        assertEquals(MediaSessionEnd(true, ProtocolState.ERROR), gate.endOnce())
        assertEquals(ProtocolState.ADVERTISING, gate.filterDiscoveryState(ProtocolState.ADVERTISING))
        assertEquals(ProtocolState.ERROR, gate.filterDiscoveryState(ProtocolState.ERROR))
    }

    @Test fun `duplicate teardown performs cleanup once`() {
        val gate = MediaSessionGate()
        assertFalse(gate.endOnce().ended)
        gate.activate()
        assertEquals(MediaSessionEnd(true, ProtocolState.ERROR), gate.endOnce())
        assertFalse(gate.endOnce().ended)
    }

    @Test fun `mDNS failure during playback remains visible after teardown`() {
        val gate = MediaSessionGate()
        assertEquals(ProtocolState.ADVERTISING, gate.filterDiscoveryState(ProtocolState.ADVERTISING))
        gate.activate()
        assertNull(gate.filterDiscoveryState(ProtocolState.ERROR))
        assertEquals(MediaSessionEnd(true, ProtocolState.ERROR), gate.endOnce())
    }

    @Test fun `Wi-Fi loss replaces ready state while playback is active`() {
        val gate = MediaSessionGate()
        gate.recordDiscoveryState(ProtocolState.ADVERTISING)
        gate.activate()
        gate.recordDiscoveryState(ProtocolState.ERROR)
        assertEquals(MediaSessionEnd(true, ProtocolState.ERROR), gate.endOnce())
    }

    @Test fun `rapid new playback invalidates a queued teardown state`() {
        val gate = MediaSessionGate()
        gate.recordDiscoveryState(ProtocolState.ADVERTISING)
        gate.activate()
        val teardown = gate.endOnce()
        assertTrue(gate.shouldPublishTeardown(requireNotNull(teardown.state)))
        gate.activate()
        assertFalse(gate.shouldPublishTeardown(requireNotNull(teardown.state)))
    }
}
