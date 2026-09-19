package dev.imirror.receiver.airplay

import android.media.MediaPlayer
import android.view.Surface
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
@OptIn(ExperimentalCoroutinesApi::class)
class AirPlayVideoLifecycleTest {
    private class Engine {
        val player = mockk<MediaPlayer>(relaxed = true)
        val prepared = slot<MediaPlayer.OnPreparedListener>()
        val completed = slot<MediaPlayer.OnCompletionListener>()
        val error = slot<MediaPlayer.OnErrorListener>()
        var playing = false
        init {
            every { player.duration } returns 52_000
            every { player.currentPosition } returns 12_000
            every { player.isPlaying } answers { playing }
            every { player.start() } answers { playing = true }
            every { player.pause() } answers { playing = false }
            every { player.setOnPreparedListener(capture(prepared)) } just Runs
            every { player.setOnCompletionListener(capture(completed)) } just Runs
            every { player.setOnErrorListener(capture(error)) } just Runs
        }
    }

    private fun surface() = mockk<Surface> { every { isValid } returns true }

    @Test fun `completion retains route and media for replay`() = runTest {
        val engine = Engine()
        var failures = 0
        val output = surface()
        val video = AirPlayVideoPlayer({ output }, { failures++ }, StandardTestDispatcher(testScheduler), { engine.player })
        video.play("https://example.test/video.mp4", 0.0)
        assertEquals("loading", videoEventState(true, video.info()))
        runCurrent()
        engine.prepared.captured.onPrepared(engine.player)
        runCurrent()
        assertEquals(1.0, video.info()!!.rate, 0.0)
        engine.playing = false
        engine.completed.captured.onCompletion(engine.player)
        runCurrent()
        assertTrue(video.info()!!.completed)
        assertEquals("stopped", videoEventState(true, video.info()))
        assertEquals(0, failures)
        verify(exactly = 0) { engine.player.release() }
        video.setRate(1f)
        runCurrent()
        assertFalse(video.info()!!.completed)
        assertEquals("playing", videoEventState(true, video.info()))
        verify(exactly = 2) { engine.player.start() }
        video.release()
        runCurrent()
        assertNull(video.info())
    }

    @Test fun `late callbacks cannot resurrect a released player`() = runTest {
        val engine = Engine()
        val video = AirPlayVideoPlayer({ surface() }, playerDispatcher = StandardTestDispatcher(testScheduler),
            playerFactory = { engine.player })
        video.play("https://example.test/video.mp4", 0.0)
        verify(exactly = 0) { engine.player.setDataSource(any<String>()) }
        runCurrent()
        video.release()
        runCurrent()
        engine.prepared.captured.onPrepared(engine.player)
        engine.completed.captured.onCompletion(engine.player)
        runCurrent()
        assertNull(video.info())
        verify(exactly = 0) { engine.player.start() }
        verify(exactly = 1) { engine.player.release() }
    }

    @Test fun `error clears loading state once and ignores stale callbacks after replacement`() = runTest {
        val old = Engine()
        val next = Engine()
        var calls = 0
        var failures = 0
        val video = AirPlayVideoPlayer({ surface() }, { failures++ }, StandardTestDispatcher(testScheduler),
            { if (calls++ == 0) old.player else next.player })
        video.play("https://example.test/old.mp4", 0.0)
        runCurrent()
        assertFalse(video.info()!!.readyToPlay)
        old.error.captured.onError(old.player, 1, 0)
        runCurrent()
        assertNull(video.info())
        assertEquals(1, failures)
        video.play("https://example.test/new.mp4", 0.0)
        runCurrent()
        old.completed.captured.onCompletion(old.player)
        old.error.captured.onError(old.player, 1, 0)
        next.prepared.captured.onPrepared(next.player)
        runCurrent()
        assertEquals(1, failures)
        assertEquals("playing", videoEventState(true, video.info()))
        video.release()
        runCurrent()
    }

    @Test fun `pause and seek received during preparation survive surface startup`() = runTest {
        val engine = Engine()
        val output = surface()
        val video = AirPlayVideoPlayer({ output }, playerDispatcher = StandardTestDispatcher(testScheduler),
            playerFactory = { engine.player })
        video.play("https://example.test/video.mp4", 0.0)
        video.setRate(0f)
        video.scrub(8.0)
        runCurrent()
        engine.prepared.captured.onPrepared(engine.player)
        runCurrent()
        verify { engine.player.seekTo(8000) }
        verify(exactly = 0) { engine.player.start() }
        assertEquals(0.0, video.info()!!.rate, 0.0)
        video.release()
        runCurrent()
    }

    @Test fun `player creation failure stops loading without an uncaught command failure`() = runTest {
        var failures = 0
        val video = AirPlayVideoPlayer({ null }, { failures++ }, StandardTestDispatcher(testScheduler),
            { throw IllegalStateException("unavailable") })
        video.play("https://example.test/video.mp4", 0.0)
        assertEquals("loading", videoEventState(true, video.info()))
        runCurrent()
        assertEquals(1, failures)
        assertNull(video.info())
        assertEquals("stopped", videoEventState(true, video.info()))
    }
}
