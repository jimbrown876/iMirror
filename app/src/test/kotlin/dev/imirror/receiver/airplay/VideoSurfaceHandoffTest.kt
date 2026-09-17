package dev.imirror.receiver.airplay

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VideoSurfaceHandoffTest {
    private data class Output(val valid: Boolean)

    @Test
    fun `startup waits for valid output without blocking another coroutine`() = runTest {
        var output: Output? = null
        var controlResponded = false
        val handoff = async { awaitValidOutput({ output }, { it.valid }, { true }) }
        launch {
            delay(10)
            controlResponded = true
            output = Output(false)
            delay(290)
            output = Output(true)
        }
        advanceTimeBy(20)
        assertTrue(controlResponded)
        assertFalse(handoff.isCompleted)
        assertTrue(handoff.await()!!.valid)
        assertTrue(currentTime in 300L..350L)
    }

    @Test
    fun `unavailable output stops waiting at five second deadline`() = runTest {
        val result = awaitValidOutput<Output>({ null }, { it.valid }, { true })
        assertNull(result)
        assertEquals(5_000L, currentTime)
    }

    @Test
    fun `replacement invalidates pending startup before a late surface appears`() = runTest {
        var generation = 1
        var output: Output? = null
        val old = async { awaitValidOutput({ output }, { it.valid }, { generation == 1 }) }
        runCurrent()
        generation = 2
        output = Output(true)
        assertNull(old.await())
        assertSame(output, awaitValidOutput({ output }, { it.valid }, { generation == 2 }))
    }

    @Test
    fun `stop cancellation prevents pending playback from being started`() = runTest {
        var output: Output? = null
        var started = false
        val pending = launch {
            awaitValidOutput({ output }, { it.valid }, { true })?.let { started = true }
        }
        runCurrent()
        pending.cancelAndJoin()
        output = Output(true)
        advanceTimeBy(5_000)
        assertFalse(started)
    }
}
