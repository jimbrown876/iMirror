package dev.imirror.receiver.airplay

import dev.imirror.receiver.service.ProtocolState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaSessionGateTest {
    @Test fun `late advertising is suppressed only while media is active`() {
        val gate = MediaSessionGate()
        assertTrue(gate.shouldPublish(ProtocolState.ADVERTISING))
        gate.activate()
        assertFalse(gate.shouldPublish(ProtocolState.ADVERTISING))
        assertTrue(gate.shouldPublish(ProtocolState.ERROR))
        assertTrue(gate.endOnce())
        assertTrue(gate.shouldPublish(ProtocolState.ADVERTISING))
    }

    @Test fun `duplicate teardown performs cleanup once`() {
        val gate = MediaSessionGate()
        assertFalse(gate.endOnce())
        gate.activate()
        assertTrue(gate.endOnce())
        assertFalse(gate.endOnce())
    }
}
