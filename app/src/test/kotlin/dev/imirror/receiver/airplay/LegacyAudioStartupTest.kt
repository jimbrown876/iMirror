package dev.imirror.receiver.airplay

import android.content.Context
import android.net.nsd.NsdManager
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.InvocationTargetException

@OptIn(ExperimentalCoroutinesApi::class)
class LegacyAudioStartupTest {
    private val session = SessionDescription(hasVideo = false, hasAudio = true, audioCodec = AudioCodec.ALAC)

    @Test fun `audio readiness precedes RECORD callback return even when main is busy`() {
        val main = StandardTestDispatcher()
        Dispatchers.setMain(main)
        val receiver = receiver()
        var audioReady = false
        every { receiver["startAudioPlayer"](session) } answers { audioReady = true; Unit }
        try {
            start(receiver)
            // The main dispatcher deliberately has not advanced. RTSP must not acknowledge
            // RECORD while this socket/player preparation is still waiting behind UI work.
            assertTrue("RECORD callback returned before preparing audio", audioReady)
            verify(exactly = 1) { receiver["startAudioPlayer"](session) }
        } finally {
            release(receiver)
            Dispatchers.resetMain()
        }
    }

    @Test fun `audio preparation failure reaches the RTSP caller instead of acknowledging success`() {
        Dispatchers.setMain(StandardTestDispatcher())
        val receiver = receiver()
        every { receiver["startAudioPlayer"](session) } throws IllegalStateException("test audio unavailable")
        try {
            try {
                start(receiver)
                fail("Failed audio preparation must prevent a successful RECORD response")
            } catch (e: InvocationTargetException) {
                assertTrue(e.cause is IllegalStateException)
            }
        } finally {
            release(receiver)
            Dispatchers.resetMain()
        }
    }

    private fun receiver(): AirPlayReceiver {
        val context = mockk<Context>(relaxed = true)
        every { context.getSystemService(Context.NSD_SERVICE) } returns mockk<NsdManager>(relaxed = true)
        return spyk(AirPlayReceiver(context, videoSurfaceProvider = { null }, onStateChanged = {}),
            recordPrivateCalls = true)
    }

    private fun start(receiver: AirPlayReceiver) = AirPlayReceiver::class.java
        .getDeclaredMethod("onStreamingStarted", SessionDescription::class.java)
        .apply { isAccessible = true }.invoke(receiver, session)

    private fun release(receiver: AirPlayReceiver) = AirPlayReceiver::class.java
        .getDeclaredMethod("releaseMediaComponents").apply { isAccessible = true }.invoke(receiver)
}
