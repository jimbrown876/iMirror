package dev.imirror.receiver.service

import android.content.Context
import android.media.AudioManager
import android.os.Looper
import dev.imirror.receiver.MainActivity
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class BackgroundHandoffTest {
    private lateinit var service: MirrorService

    @Before fun setUp() { service = Robolectric.buildService(MirrorService::class.java).create().get() }
    @After fun tearDown() { service.onDestroy(); shadowOf(Looper.getMainLooper()).idle() }

    @Suppress("UNCHECKED_CAST")
    private fun setState(state: ProtocolState) {
        val field = MirrorService::class.java.getDeclaredField("_airPlayState").apply { isAccessible = true }
        (field.get(service) as MutableStateFlow<ProtocolState>).value = state
        reconcile()
    }

    private fun reconcile() {
        MirrorService::class.java.getDeclaredMethod("reconcilePresentation").apply { isAccessible = true }.invoke(service)
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun `advertising never takes foreground or audio focus`() {
        setState(ProtocolState.ADVERTISING)
        assertNull(shadowOf(service).nextStartedActivity)
        assertFalse(service.presentationActive.value)
        assertNull(shadowOf(service.getSystemService(Context.AUDIO_SERVICE) as AudioManager).lastAudioFocusRequest)
    }

    @Test fun `accepted background playback opens one auto-return screen`() {
        setState(ProtocolState.CONNECTED)
        val intent = shadowOf(service).nextStartedActivity
        assertEquals(MainActivity::class.java.name, intent.component?.className)
        assertTrue(intent.getBooleanExtra(MainActivity.EXTRA_RETURN_AFTER_PLAYBACK, false))
        assertTrue(service.presentationActive.value)
        setState(ProtocolState.CONNECTED)
        assertNull("Metadata/repeated connection events cannot steal foreground again", shadowOf(service).nextStartedActivity)
    }

    @Test fun `visible receiver does not create an automatic return task`() {
        service.setPresentationVisible(true)
        setState(ProtocolState.CONNECTED)
        assertNull(shadowOf(service).nextStartedActivity)
        assertTrue(service.presentationActive.value)
    }

    @Test fun `disconnect releases focus and next connection can take over again`() {
        val manager = shadowOf(service.getSystemService(Context.AUDIO_SERVICE) as AudioManager)
        setState(ProtocolState.CONNECTED)
        shadowOf(service).nextStartedActivity
        assertEquals(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT, manager.lastAudioFocusRequest.durationHint)
        setState(ProtocolState.ADVERTISING)
        assertFalse(service.presentationActive.value)
        val owned = MirrorService::class.java.getDeclaredField("ownsAudioFocus").apply { isAccessible = true }
        assertEquals(false, owned.get(service))
        setState(ProtocolState.CONNECTED)
        assertNotNull(shadowOf(service).nextStartedActivity)
    }

    @Test fun `removing activity task does not stop foreground service`() {
        service.onTaskRemoved(null)
        assertFalse(shadowOf(service).isStoppedBySelf)
    }
}
