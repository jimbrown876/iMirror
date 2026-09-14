package dev.imirror.receiver.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RtspClientAdmissionTest {
    @Test fun `first client is accepted`() {
        assertEquals(IncomingClientDecision.ACCEPT, decideIncomingClient(false, false, 0))
    }

    @Test fun `incomplete socket can never block the next phone`() {
        assertEquals(IncomingClientDecision.REPLACE_STALE, decideIncomingClient(true, false, 0))
    }

    @Test fun `healthy active playback rejects a probing second phone`() {
        assertEquals(IncomingClientDecision.REJECT_BUSY, decideIncomingClient(true, true, 60_000, true))
        assertEquals(IncomingClientDecision.REJECT_BUSY, decideIncomingClient(true, true, 4_999, false))
    }

    @Test fun `idle established sender is replaced at the bounded handoff deadline`() {
        assertEquals(IncomingClientDecision.REPLACE_STALE, decideIncomingClient(true, true, 5_000, false))
        assertEquals(IncomingClientDecision.REPLACE_STALE, decideIncomingClient(true, true, 600_000, false))
    }

    @Test fun `media packet liveness can be reset between senders`() {
        StreamStats.resetStreams()
        assertFalse(StreamStats.hasRecentMediaPacket(5_000))
        StreamStats.markMediaPacket()
        assertTrue(StreamStats.hasRecentMediaPacket(5_000))
        StreamStats.resetStreams()
        assertFalse(StreamStats.hasRecentMediaPacket(5_000))
    }
}
