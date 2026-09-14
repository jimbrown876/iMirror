package dev.imirror.receiver

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import dev.imirror.receiver.service.MirrorService
import dev.imirror.receiver.service.PhotoFrame
import dev.imirror.receiver.service.ProtocolState
import dev.imirror.receiver.service.ServiceController
import dev.imirror.receiver.airplay.NowPlayingInfo
import dev.imirror.receiver.ui.HomeFragment
import dev.imirror.receiver.ui.NowPlayingScreen
import dev.imirror.receiver.ui.PhotoScreen
import dev.imirror.receiver.ui.PinScreen
import dev.imirror.receiver.ui.SettingsFragment
import dev.imirror.receiver.ui.StreamingScreen
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * MainActivity — The single Activity hosting iMirror's navigation and fragments.
 *
 * WHY: iMirror uses a single-Activity architecture with Fragment-based navigation.
 * This is the recommended pattern for Android TV apps: one Activity with swappable
 * Fragments avoids the overhead of Activity transitions and keeps the Leanback
 * launcher integration simple.
 *
 * Layout structure:
 *   ┌─ Nav Panel ──┬─ Content (FrameLayout) ─────────────────┐
 *   │  Home        │  HomeFragment  OR  SettingsFragment      │
 *   │  Settings    │                                          │
 *   └──────────────┴──────────────────────────────────────────┘
 *   [streaming_container] — full-screen overlay (GONE when idle)
 *
 * HOW: D-pad left/right navigation between nav panel and content area.
 * The nav panel items switch fragments. MirrorService is started on app launch.
 */
class MainActivity : AppCompatActivity() {

    // UI references
    private lateinit var navItemHome: TextView
    private lateinit var navItemSettings: TextView
    private lateinit var contentContainer: FrameLayout
    private lateinit var streamingContainer: FrameLayout

    // The SurfaceView for full-screen video output
    private lateinit var streamingScreen: StreamingScreen
    private lateinit var photoScreen: PhotoScreen
    private lateinit var nowPlayingScreen: NowPlayingScreen
    private lateinit var pinScreen: PinScreen

    // Service binding — gives access to state flows for showing/hiding the streaming overlay
    private var service: MirrorService? = null
    private var isBound = false
    private var bindRequested = false
    private var currentAirPlayState = ProtocolState.DISABLED
    private var currentPhotoFrame: PhotoFrame? = null
    private var currentNowPlaying: NowPlayingInfo? = null
    private var currentPin: String? = null
    private var overlayJob: Job? = null
    private var returnAfterPlayback = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (!bindRequested || !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
            service = (binder as? MirrorService.LocalBinder)?.getService()
            isBound = true
            Timber.d("MainActivity: bound to MirrorService")

            // Wire the streaming Surface so the service can pass it to VideoDecoder
            service?.setVideoSurfaceProvider { getVideoSurface() }
            service?.setPresentationVisible(true)

            // Show/hide the full-screen overlay for video streams and photos.
            observeOverlayState()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            overlayJob?.cancel()
            overlayJob = null
            service = null
            isBound = false
            Timber.d("MainActivity: unbound from MirrorService")
        }
    }

    // Currently selected nav item index (0 = Home, 1 = Settings)
    private var selectedNavIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        returnAfterPlayback = savedInstanceState?.getBoolean(EXTRA_RETURN_AFTER_PLAYBACK)
            ?: intent.getBooleanExtra(EXTRA_RETURN_AFTER_PLAYBACK, false)
        if (Build.VERSION.SDK_INT >= 27 && returnAfterPlayback) setTurnScreenOn(true)
        setContentView(R.layout.activity_main)

        Timber.d("MainActivity created")
        bindViews()
        setupOverlayScreens()
        setupNavigation()

        // Show HomeFragment on first launch
        if (savedInstanceState == null) {
            navigateTo(HomeFragment(), navItemHome)
        }

        // Start the service immediately so it's running before any sender discovers us
        ServiceController.start(this)

        // Android 13+ requires an explicit runtime grant for POST_NOTIFICATIONS
        requestNotificationPermission()
    }

    override fun onStart() {
        super.onStart()
        // Bind so we can observe StateFlows and supply the video Surface
        val intent = Intent(this, MirrorService::class.java)
        bindRequested = bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        super.onStop()
        overlayJob?.cancel()
        overlayJob = null
        service?.setPresentationVisible(false)
        // Clear surface reference before unbinding to avoid holding a dead Surface
        service?.setVideoSurfaceProvider { null }
        if (bindRequested) {
            unbindService(serviceConnection)
            bindRequested = false
        }
        isBound = false
        service = null
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        returnAfterPlayback = intent.getBooleanExtra(EXTRA_RETURN_AFTER_PLAYBACK, false)
        if (Build.VERSION.SDK_INT >= 27) setTurnScreenOn(returnAfterPlayback)
        observeOverlayState()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(EXTRA_RETURN_AFTER_PLAYBACK, returnAfterPlayback)
        super.onSaveInstanceState(outState)
    }

    // ─── View Setup ──────────────────────────────────────────────────────────

    private fun bindViews() {
        navItemHome       = findViewById(R.id.nav_item_home)
        navItemSettings   = findViewById(R.id.nav_item_settings)
        contentContainer  = findViewById(R.id.content_container)
        streamingContainer = findViewById(R.id.streaming_container)
    }

    /**
     * Creates the StreamingScreen (SurfaceView for video) and adds it to the
     * streaming_container. Created eagerly so the Surface is ready before streaming starts.
     */
    private fun setupOverlayScreens() {
        streamingScreen = StreamingScreen(this)
        photoScreen = PhotoScreen(this)
        nowPlayingScreen = NowPlayingScreen(this)
        pinScreen = PinScreen(this)
        streamingContainer.addView(streamingScreen)
        streamingContainer.addView(photoScreen)
        streamingContainer.addView(nowPlayingScreen)
        streamingContainer.addView(pinScreen)
        photoScreen.visibility = View.GONE
        nowPlayingScreen.visibility = View.GONE
        pinScreen.visibility = View.GONE
    }

    /**
     * Sets up click listeners for the navigation panel items.
     * Also updates the visual selected state (text color) of the active item.
     */
    private fun setupNavigation() {
        navItemHome.setOnClickListener {
            if (selectedNavIndex != 0) {
                navigateTo(HomeFragment(), navItemHome)
            }
        }
        navItemSettings.setOnClickListener {
            if (selectedNavIndex != 1) {
                navigateTo(SettingsFragment(), navItemSettings)
            }
        }

        // Set initial selected state
        setNavSelected(navItemHome, true)
        setNavSelected(navItemSettings, false)
    }

    /**
     * Replaces the content_container fragment with [fragment] and updates
     * the nav panel selection highlight.
     *
     * @param fragment  The Fragment to show in the content area.
     * @param navItem   The nav panel TextView that was clicked (for highlight update).
     */
    private fun navigateTo(fragment: Fragment, navItem: TextView) {
        // Update nav highlight
        setNavSelected(navItemHome, navItem == navItemHome)
        setNavSelected(navItemSettings, navItem == navItemSettings)
        selectedNavIndex = if (navItem == navItemHome) 0 else 1

        // Replace fragment
        supportFragmentManager.beginTransaction()
            .replace(R.id.content_container, fragment)
            .commit()
    }

    /**
     * Updates the nav panel item's visual state.
     *
     * @param item     The nav item TextView.
     * @param selected True if this item is currently active.
     */
    private fun setNavSelected(item: TextView, selected: Boolean) {
        item.isSelected = selected
        item.setTextColor(
            getColor(if (selected) R.color.text_primary else R.color.nav_item_normal)
        )
    }

    /**
     * Shows the full-screen streaming overlay (called by MirrorService
     * via a state update or broadcast when a stream becomes active).
     *
     * Hides the nav panel and content area to give the stream the full screen.
     */
    fun showStreamingScreen() {
        photoScreen.visibility = View.GONE
        nowPlayingScreen.visibility = View.GONE
        nowPlayingScreen.clear()
        pinScreen.visibility = View.GONE
        streamingScreen.visibility = View.VISIBLE
        streamingContainer.visibility = View.VISIBLE
        streamingContainer.bringToFront()
    }

    fun showPhotoScreen(photoFrame: PhotoFrame) {
        if (photoScreen.showPhoto(photoFrame.bytes)) {
            streamingScreen.visibility = View.GONE
            nowPlayingScreen.visibility = View.GONE
            pinScreen.visibility = View.GONE
            photoScreen.visibility = View.VISIBLE
            streamingContainer.visibility = View.VISIBLE
            streamingContainer.bringToFront()
        }
    }

    /** Shows the audio-only now-playing card (AirPlay audio with no video). */
    fun showNowPlayingScreen(info: NowPlayingInfo) {
        nowPlayingScreen.update(info)
        streamingScreen.visibility = View.GONE
        photoScreen.visibility = View.GONE
        pinScreen.visibility = View.GONE
        nowPlayingScreen.visibility = View.VISIBLE
        streamingContainer.visibility = View.VISIBLE
        streamingContainer.bringToFront()
    }

    /**
     * Hides the streaming overlay and returns to the normal app UI.
     * Called when a stream ends.
     */
    fun hideStreamingScreen() {
        photoScreen.clearPhoto()
        photoScreen.visibility = View.GONE
        nowPlayingScreen.clear()
        nowPlayingScreen.visibility = View.GONE
        pinScreen.visibility = View.GONE
        streamingScreen.visibility = View.VISIBLE
        streamingContainer.visibility = View.GONE
    }

    /** Returns the SurfaceView Surface for the VideoDecoder. */
    fun getVideoSurface() = streamingScreen.getSurface()

    /**
     * Routes TV-remote media keys to the AirPlay sender (DACP reverse control) while audio-only or a
     * stream is showing — so the remote can play/pause/skip what the Mac/iPhone is streaming. Returns
     * false for other keys so normal navigation is unaffected.
     */
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        val overlayActive = currentNowPlaying != null || currentAirPlayState == ProtocolState.CONNECTED
        if (overlayActive) {
            val command = when (keyCode) {
                android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                android.view.KeyEvent.KEYCODE_MEDIA_PLAY,
                android.view.KeyEvent.KEYCODE_MEDIA_PAUSE,
                android.view.KeyEvent.KEYCODE_DPAD_CENTER -> dev.imirror.receiver.airplay.DacpClient.CMD_PLAY_PAUSE
                android.view.KeyEvent.KEYCODE_MEDIA_NEXT,
                android.view.KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD -> dev.imirror.receiver.airplay.DacpClient.CMD_NEXT
                android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS,
                android.view.KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD -> dev.imirror.receiver.airplay.DacpClient.CMD_PREV
                android.view.KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> dev.imirror.receiver.airplay.DacpClient.CMD_FF
                android.view.KeyEvent.KEYCODE_MEDIA_REWIND -> dev.imirror.receiver.airplay.DacpClient.CMD_REW
                else -> null
            }
            if (command != null) {
                service?.sendAirPlayRemoteCommand(command)
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    /**
     * Requests POST_NOTIFICATIONS permission on Android 13+ (API 33+).
     * On older versions the permission is granted automatically with the manifest declaration.
     */
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this, android.Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                    PERMISSION_REQUEST_NOTIFICATIONS
                )
            }
        }
    }

    companion object {
        private const val PERMISSION_REQUEST_NOTIFICATIONS = 1001
        const val EXTRA_RETURN_AFTER_PLAYBACK = "dev.imirror.receiver.RETURN_AFTER_PLAYBACK"
    }

    // ─── Streaming overlay ────────────────────────────────────────────────────

    /**
     * Observes [MirrorService.airPlayState] and [MirrorService.photoFrame]
     * and shows the appropriate full-screen overlay.
     *
     * One combined collector is explicitly cancelled onStop, so rebinding cannot leave
     * duplicate collectors or an old Activity dismissing a newer playback window.
     */
    private fun observeOverlayState() {
        val svc = service ?: return
        overlayJob?.cancel()
        overlayJob = lifecycleScope.launch {
            combine(svc.airPlayState, svc.photoFrame, svc.nowPlaying, svc.pairingPin,
                svc.presentationActive) { state, frame, info, pin, active ->
                currentAirPlayState = state
                currentPhotoFrame = frame
                currentNowPlaying = info
                currentPin = pin
                active
            }.collectLatest { active ->
                updateOverlay()
                window.decorView.keepScreenOn = active
                if (returnAfterPlayback && !active) {
                    // Pairing-to-playback and teardown callbacks may arrive separately.
                    // A new active state cancels this short return grace period.
                    delay(500)
                    if (!svc.presentationActive.value && !isFinishing) {
                        Timber.i("AirPlay ended — returning to previous TV task")
                        finishAndRemoveTask()
                    }
                }
            }
        }
    }

    private fun updateOverlay() {
        val photoFrame = currentPhotoFrame
        val nowPlaying = currentNowPlaying
        val pin = currentPin
        when {
            // PIN pairing (access control) happens before streaming — show the code over everything.
            pin != null -> showPinScreen(pin)
            // Audio-only AirPlay (system audio, Music, podcasts): show the now-playing card instead
            // of the black video surface. Set whenever audio plays without video.
            nowPlaying != null -> showNowPlayingScreen(nowPlaying)
            currentAirPlayState == ProtocolState.CONNECTED -> showStreamingScreen()
            photoFrame != null -> showPhotoScreen(photoFrame)
            else -> hideStreamingScreen()
        }
    }

    /** Shows the AirPlay pairing PIN over the full screen during SRP pair-setup. */
    fun showPinScreen(pin: String) {
        pinScreen.setPin(pin)
        streamingScreen.visibility = View.GONE
        photoScreen.visibility = View.GONE
        nowPlayingScreen.visibility = View.GONE
        pinScreen.visibility = View.VISIBLE
        streamingContainer.visibility = View.VISIBLE
        streamingContainer.bringToFront()
    }
}
