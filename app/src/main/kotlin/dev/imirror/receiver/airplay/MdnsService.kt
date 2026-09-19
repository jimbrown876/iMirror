package dev.imirror.receiver.airplay

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.system.OsConstants
import dev.imirror.receiver.service.ProtocolState
import dev.imirror.receiver.util.Logger
import dev.imirror.receiver.util.NetworkUtils
import dev.imirror.receiver.airplay.handshake.PairingKeys
import java.net.Inet4Address
import java.net.Inet6Address

/**
 * MdnsService — Advertises iMirror as an AirPlay 2 receiver on the local network.
 *
 * WHY: For macOS/iOS to show iMirror in the AirPlay menu, the device must announce
 * itself using mDNS (Multicast DNS, the same protocol as Apple's Bonjour).
 * Without this advertisement, no sender would know iMirror exists.
 *
 * HOW: Registers two mDNS services using Android's [NsdManager]:
 * - `_airplay._tcp` — main AirPlay service with feature flags and device info
 * - `_raop._tcp`    — audio streaming service (required even for screen mirroring)
 *
 * Both services use port [AIRPLAY_PORT] (7000), which is where [RtspHandler] listens.
 *
 * The service name shown in AirPlay pickers is determined by [displayNameOverride]:
 * - If set: uses the user-configured name from Settings
 * - If blank/null: falls back to [NetworkUtils.getDeviceName]
 *
 * State changes are reported via [onStateChange] callback.
 *
 * Example:
 *   val mdns = MdnsService(context, onStateChange = { state -> /* update UI */ })
 *   mdns.start(displayNameOverride = "Living Room TV")
 *   mdns.stop()
 *   mdns.restart(displayNameOverride = "Living Room TV")
 */
class MdnsService(
    private val context: Context,
    private val onStateChange: (ProtocolState) -> Unit = {},
    /**
     * Called with the actual mDNS service name after registration completes.
     *
     * Android's NsdManager resolves name collisions automatically: if another device
     * on the network is already registered as "iMirror", Android will register us as
     * "iMirror (2)" instead. The [onActualNameRegistered] callback delivers the name
     * that was actually registered (which may differ from the requested name).
     *
     * The caller can use this to update the UI (e.g., show "Registered as: iMirror (2)")
     * or log the divergence for debugging.
     *
     * Only the `_airplay._tcp` service name is reported (not the `_raop._tcp` name,
     * which has a MAC address prefix and is not shown to users).
     */
    private val onActualNameRegistered: (String) -> Unit = {},
    private val discoveryResponderFactory: ((() -> Unit) -> MdnsDiscoveryResponder) = { onEnded ->
        MdnsDiscoveryResponder(onEnded)
    }
) {

    // Android's built-in mDNS manager — handles multicast registration
    private val nsdManager: NsdManager =
        context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val connectivityManager: ConnectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val recoveryHandler = Handler(Looper.getMainLooper())

    // Listeners track registration state; held to enable unregistration later
    private var airPlayListener: NsdManager.RegistrationListener? = null
    private var raopListener: NsdManager.RegistrationListener? = null
    private var discoveryResponder: MdnsDiscoveryResponder? = null
    private var generation = 0
    private var actualAirPlayName = ""
    private var actualRaopName = ""
    private var multicastLock: WifiManager.MulticastLock? = null
    private var wifiPerformanceLock: WifiManager.WifiLock? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var candidateNetwork: Network? = null
    private var activeNetwork: Network? = null
    private var activeNetworkFingerprint: String? = null
    private var networkStateKnown = false
    private var recoveryTask: Runnable? = null
    private var responderRecoveryTask: Runnable? = null
    private var registrationWatchdog: Runnable? = null
    private var recoveryAttempt = 0
    private var responderRecoveryAttempt = 0

    // Count of how many services have confirmed registration.
    // Only when both reach 2 do we emit ProtocolState.ADVERTISING.
    @Volatile
    private var registeredCount = 0

    // Guard against double-start
    @Volatile
    private var isStarted = false

    // The name we requested to register — compared against the actual registered name
    // in onServiceRegistered to detect mDNS collision auto-renaming.
    @Volatile
    private var requestedName: String = ""

    // Name captured at start(), used when _airplay is chained after _raop registers.
    @Volatile
    private var pendingName: String = ""

    /**
     * Starts mDNS advertising.
     *
     * Registers both the `_airplay._tcp` and `_raop._tcp` services.
     * The device will appear in the macOS/iOS AirPlay menu within ~1-3 seconds.
     *
     * Idempotent: calling it twice without [stop] in between is a no-op.
     *
     * @param displayNameOverride User-configured display name from Settings.
     *   Pass `null` or blank to use the Android system device name.
     */
    @Synchronized
    fun start(displayNameOverride: String? = null) {
        if (isStarted) {
            Logger.w("MdnsService.start() called but already registered — ignoring")
            return
        }
        isStarted = true
        val effectiveName = resolveDisplayName(displayNameOverride)
        Logger.i("Starting resilient mDNS advertising as '$effectiveName'")
        requestedName = effectiveName
        pendingName = effectiveName
        recoveryAttempt = 0
        responderRecoveryAttempt = 0
        acquireMulticastLock()
        acquireWifiPerformanceLock()
        startNetworkMonitoring()
    }

    /**
     * Stops mDNS advertising.
     *
     * Unregisters both mDNS services. The device disappears from sender pickers
     * within ~5-10 seconds (mDNS goodbye packet sent immediately, but senders cache briefly).
     *
     * Safe to call even if [start] was never called.
     */
    @Synchronized
    fun stop() {
        Logger.i("Stopping mDNS advertising")
        isStarted = false
        generation++
        cancelRecovery()
        stopNetworkMonitoring()
        clearAdvertisingResources()
        releaseWifiPerformanceLock()
        releaseMulticastLock()
        onStateChange(ProtocolState.DISABLED)
    }

    /**
     * Restarts mDNS advertising.
     *
     * Used after a streaming session ends to immediately re-advertise the device
     * in sender pickers.
     *
     * @param displayNameOverride Updated display name, if changed in Settings.
     */
    fun restart(displayNameOverride: String? = null) {
        Logger.d("Restarting mDNS advertising")
        stop()
        start(displayNameOverride)
    }

    // ─── Private helpers ─────────────────────────────────────────────────────

    /**
     * Starts one generation of the two serialized NSD registrations. The media sockets are not
     * touched, so recovering discovery cannot interrupt an active AirPlay session.
     */
    @Synchronized
    private fun beginAdvertising() {
        if (!isStarted || activeNetworkFingerprint == null) return
        generation++
        clearAdvertisingResources()
        registeredCount = 0
        actualAirPlayName = ""
        actualRaopName = ""
        val advertisingGeneration = generation

        // IMPORTANT: these two registrations MUST be serialised, not fired back-to-back.
        // Android's NsdManager cannot reliably handle two registrations in flight at once.
        // Listen before NSD announces so supplemental answers reuse its exact SRV hostname,
        // TXT data and address records. It answers only after both registrations complete.
        try {
            startSupplementalResponder(advertisingGeneration)
            registerRaopService(pendingName)
            scheduleRegistrationWatchdog(advertisingGeneration)
        } catch (e: Exception) {
            handleAdvertisingFailure("registration start", e)
        }
    }

    /** Release only advertising resources; keep the desired always-ready lifecycle alive. */
    private fun clearAdvertisingResources() {
        cancelRegistrationWatchdog()
        cancelResponderRecovery()
        discoveryResponder?.stop()
        discoveryResponder = null
        listOfNotNull(airPlayListener, raopListener).forEach { listener ->
            try {
                nsdManager.unregisterService(listener)
            } catch (e: Exception) {
                // A listener can already be gone after a Wi-Fi transition. Continue cleaning up.
                Logger.w("Unable to unregister stale mDNS service: ${e.message}")
            }
        }
        airPlayListener = null
        raopListener = null
        registeredCount = 0
    }

    /**
     * Observe LAN identity, not merely process lifetime. Android can keep this foreground service
     * alive while NSD registrations and multicast memberships become stale after Wi-Fi rejoins.
     */
    private fun startNetworkMonitoring() {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // Android guarantees LinkProperties after onAvailable for registered callbacks.
                // Remember the candidate, but do not tear down a healthy registration because a
                // race-prone synchronous getLinkProperties() lookup happens to return null.
                synchronized(this@MdnsService) {
                    if (isStarted) candidateNetwork = network
                }
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                updateNetwork(network, linkProperties)
            }

            override fun onLost(network: Network) {
                synchronized(this@MdnsService) {
                    if (!isStarted) return
                    if (candidateNetwork == network) candidateNetwork = null
                    if (activeNetwork != network) return
                    activeNetwork = null
                    updateNetworkFingerprint(null)
                }
            }
        }
        networkCallback = callback
        try {
            connectivityManager.registerDefaultNetworkCallback(callback)
            val network = connectivityManager.activeNetwork
            updateNetwork(network, network?.let(connectivityManager::getLinkProperties))
        } catch (e: Exception) {
            // If the observer itself is unavailable, retain the old behavior with bounded retry.
            Logger.w("Unable to monitor LAN changes; starting mDNS directly: ${e.message}")
            activeNetworkFingerprint = FALLBACK_NETWORK
            scheduleRecovery("network monitor unavailable", 0L, resetBackoff = true)
        }
    }

    @Synchronized
    private fun stopNetworkMonitoring() {
        networkCallback?.let { callback ->
            runCatching { connectivityManager.unregisterNetworkCallback(callback) }
        }
        networkCallback = null
        candidateNetwork = null
        activeNetwork = null
        activeNetworkFingerprint = null
        networkStateKnown = false
    }

    private fun updateNetwork(network: Network?, linkProperties: LinkProperties?) {
        synchronized(this) {
            if (!isStarted) return
            if (network == null) {
                updateNetworkFingerprint(null)
                return
            }
            // Ignore a late properties callback from an older default network after a handoff.
            val candidate = candidateNetwork
            if (candidate != null && network != candidate && network != activeNetwork) return
            val fingerprint = linkProperties?.let(::lanFingerprint)
                ?.let { "${network.hashCode()}|$it" }
            if (fingerprint != null) {
                activeNetwork = network
                if (candidateNetwork == network) candidateNetwork = null
            }
            updateNetworkFingerprint(fingerprint)
        }
    }

    private fun updateNetworkFingerprint(fingerprint: String?) {
        if (networkStateKnown && fingerprint == activeNetworkFingerprint) return
        networkStateKnown = true
        activeNetworkFingerprint = fingerprint
        generation++
        cancelRecovery()
        clearAdvertisingResources()
        if (fingerprint == null) {
            Logger.i("LAN unavailable; mDNS recovery is waiting for a usable address")
            onStateChange(ProtocolState.ERROR)
            return
        }
        scheduleRecovery("LAN available or changed", NETWORK_DEBOUNCE_MS, resetBackoff = true)
    }

    private fun handleResponderEnded(listenerGeneration: Int) {
        synchronized(this) {
            if (!isStarted || generation != listenerGeneration) return
            Logger.w("Supplemental mDNS responder ended unexpectedly")
            discoveryResponder = null
            scheduleResponderRecovery(listenerGeneration)
        }
    }

    /** Android NSD remains the primary advertisement if the supplemental responder is unavailable. */
    private fun startSupplementalResponder(responderGeneration: Int) {
        val candidate = discoveryResponderFactory {
            handleResponderEnded(responderGeneration)
        }
        if (candidate.start()) {
            discoveryResponder = candidate
            responderRecoveryAttempt = 0
            if (registeredCount >= 2) candidate.activate(actualAirPlayName, actualRaopName)
        } else {
            discoveryResponder = null
            scheduleResponderRecovery(responderGeneration)
        }
    }

    @Synchronized
    private fun scheduleResponderRecovery(responderGeneration: Int) {
        if (!isStarted || generation != responderGeneration ||
            activeNetworkFingerprint == null || responderRecoveryTask != null) return
        val delayMs = RETRY_DELAYS_MS[
            responderRecoveryAttempt.coerceAtMost(RETRY_DELAYS_MS.lastIndex)
        ]
        if (responderRecoveryAttempt < RETRY_DELAYS_MS.lastIndex) responderRecoveryAttempt++
        lateinit var task: Runnable
        task = Runnable {
            synchronized(this) {
                if (responderRecoveryTask !== task) return@Runnable
                responderRecoveryTask = null
                if (!isStarted || generation != responderGeneration ||
                    activeNetworkFingerprint == null || discoveryResponder != null) return@Runnable
                Logger.i("Retrying supplemental mDNS responder")
                startSupplementalResponder(responderGeneration)
            }
        }
        responderRecoveryTask = task
        recoveryHandler.postDelayed(task, delayMs)
    }

    private fun cancelResponderRecovery() {
        responderRecoveryTask?.let(recoveryHandler::removeCallbacks)
        responderRecoveryTask = null
    }

    private fun handleAdvertisingFailure(reason: String, error: Throwable? = null) {
        if (!isStarted) return
        if (error != null) Logger.e("mDNS $reason failed", error)
        else Logger.e("mDNS $reason failed")
        generation++
        clearAdvertisingResources()
        onStateChange(ProtocolState.ERROR)
        scheduleRecovery(reason)
    }

    @Synchronized
    private fun scheduleRecovery(
        reason: String,
        requestedDelayMs: Long? = null,
        resetBackoff: Boolean = false
    ) {
        if (!isStarted || activeNetworkFingerprint == null || recoveryTask != null) return
        if (resetBackoff) recoveryAttempt = 0
        val delayMs = requestedDelayMs ?: RETRY_DELAYS_MS[recoveryAttempt.coerceAtMost(RETRY_DELAYS_MS.lastIndex)]
        if (requestedDelayMs == null && recoveryAttempt < RETRY_DELAYS_MS.lastIndex) recoveryAttempt++
        lateinit var task: Runnable
        task = Runnable {
            synchronized(this) {
                if (recoveryTask !== task || !isStarted || activeNetworkFingerprint == null) return@Runnable
                recoveryTask = null
                Logger.i("Refreshing mDNS advertising after $reason")
                beginAdvertising()
            }
        }
        recoveryTask = task
        recoveryHandler.postDelayed(task, delayMs)
    }

    private fun cancelRecovery() {
        recoveryTask?.let(recoveryHandler::removeCallbacks)
        recoveryTask = null
    }

    private fun scheduleRegistrationWatchdog(watchdogGeneration: Int) {
        cancelRegistrationWatchdog()
        lateinit var watchdog: Runnable
        watchdog = Runnable {
            synchronized(this) {
                if (registrationWatchdog !== watchdog) return@Runnable
                registrationWatchdog = null
                if (!isStarted || generation != watchdogGeneration || registeredCount >= 2) return@Runnable
                handleAdvertisingFailure("registration callback timeout")
            }
        }
        registrationWatchdog = watchdog
        recoveryHandler.postDelayed(watchdog, REGISTRATION_TIMEOUT_MS)
    }

    private fun cancelRegistrationWatchdog() {
        registrationWatchdog?.let(recoveryHandler::removeCallbacks)
        registrationWatchdog = null
    }

    private fun acquireMulticastLock() {
        if (multicastLock != null) return
        runCatching {
            val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiManager.createMulticastLock(MULTICAST_LOCK_TAG).apply {
                setReferenceCounted(false)
                acquire()
                multicastLock = this
            }
        }.onFailure { Logger.w("Unable to acquire mDNS multicast lock: ${it.message}") }
    }

    private fun releaseMulticastLock() {
        multicastLock?.let { lock -> runCatching { lock.release() } }
        multicastLock = null
    }

    /** Keep the plugged-in TV radio out of power save while it is an active LAN receiver. */
    @Suppress("DEPRECATION")
    private fun acquireWifiPerformanceLock() {
        if (wifiPerformanceLock != null) return
        runCatching {
            val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, WIFI_LOCK_TAG).apply {
                setReferenceCounted(false)
                acquire()
                wifiPerformanceLock = this
            }
        }.onFailure { Logger.w("Unable to acquire AirPlay Wi-Fi performance lock: ${it.message}") }
    }

    private fun releaseWifiPerformanceLock() {
        wifiPerformanceLock?.let { lock -> runCatching { lock.release() } }
        wifiPerformanceLock = null
    }

    /**
     * Determines the effective name to advertise.
     * Uses [override] if non-blank; otherwise reads from the Android system.
     */
    private fun resolveDisplayName(override: String?): String {
        val trimmed = override?.trim() ?: ""
        return if (trimmed.isNotEmpty()) trimmed else NetworkUtils.getDeviceName(context)
    }

    /**
     * Registers the `_airplay._tcp` mDNS service.
     *
     * TXT records tell senders what features iMirror supports.
     * See TECHNICAL_SPEC.md §8 for bit-level breakdown of the `features` value.
     *
     * @param displayName The name shown in sender AirPlay pickers.
     */
    private fun pairingPublicKeyHex(): String =
        PairingKeys.get(context).edPublic.joinToString("") { "%02x".format(it) }

    private fun registerAirPlayService(displayName: String) {
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = displayName
            serviceType = SERVICE_TYPE_AIRPLAY
            port = AIRPLAY_PORT

            // Core identity TXT records
            setAttribute("deviceid", NetworkUtils.getMacAddress(context))
            setAttribute("features", AIRPLAY_FEATURES)
            setAttribute("model", AIRPLAY_MODEL)
            setAttribute("srcvers", AIRPLAY_SERVER_VERSION)
            setAttribute("vv", "2")                             // AirPlay protocol version 2
            setAttribute("pi", NetworkUtils.getPersistentUuid(context))
            setAttribute("pk", pairingPublicKeyHex())
            setAttribute("flags", "0x4")                        // Screen-mirroring receiver
        }

        airPlayListener = createRegistrationListener(
            serviceLabel = "_airplay._tcp",
            onRegisteredName = { actualName ->
                actualAirPlayName = actualName
                // Detect collision auto-renaming: NsdManager appended " (2)", " (3)", etc.
                if (actualName != requestedName) {
                    Logger.w("mDNS name collision detected: requested='$requestedName' " +
                             "actual='$actualName' — NsdManager resolved automatically")
                }
                onActualNameRegistered(actualName)
            },
            onSuccess = { incrementAndCheckBothRegistered() },
            onFailure = {}
        )
        nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, airPlayListener!!)
    }

    /**
     * Registers the `_raop._tcp` mDNS service.
     *
     * RAOP (Remote Audio Output Protocol) is the audio component of AirPlay.
     * macOS and iOS require it even for screen mirroring — not only for audio-only streams.
     *
     * RAOP service name format required by the AirPlay protocol:
     *   `"<MACADDRESS_NOCOLONS>@<DeviceName>"`
     *   e.g., `"AABBCCDDEEFF@Living Room TV"`
     *
     * @param displayName The device name portion of the RAOP service name.
     */
    private fun registerRaopService(displayName: String) {
        val macHex = NetworkUtils.getMacAddress(context).replace(":", "").uppercase()

        val serviceInfo = NsdServiceInfo().apply {
            serviceName = "$macHex@$displayName"  // required RAOP format
            serviceType = SERVICE_TYPE_RAOP
            port = AIRPLAY_PORT

            setAttribute("cn", "0,1")             // Legacy PCM/ALAC; AAC is on the modern audio path
            setAttribute("da", "true")             // Digest authentication capable
            // Legacy RAOP: use the implemented RSA key exchange, as shairport-sync does.
            // Advertising FairPlay here sends Music.app down the unverified v2 key path.
            // Modern /info, _airplay discovery and v3 mirroring/video remain unchanged.
            setAttribute("et", "0,1")
            setAttribute("md", "0,1,2")            // Metadata types supported
            setAttribute("sv", "false")            // Software volume control
            setAttribute("tp", "UDP")              // Transport for audio RTP
            setAttribute("vn", "65537")            // Version number (required)
            setAttribute("vs", AIRPLAY_SERVER_VERSION)
            setAttribute("am", AIRPLAY_MODEL)
            setAttribute("pk", pairingPublicKeyHex())
        }

        raopListener = createRegistrationListener(
            serviceLabel = "_raop._tcp",
            onRegisteredName = { actualRaopName = it },
            onSuccess = {
                incrementAndCheckBothRegistered()
                // Only now is it safe to register the second service — see start().
                registerAirPlayService(pendingName)
                scheduleRegistrationWatchdog(generation)
            },
            onFailure = {}
        )
        nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, raopListener!!)
    }

    /**
     * Emits [ProtocolState.ADVERTISING] only after both services have confirmed registration.
     * This prevents a brief "advertising" state where only one of the two required services
     * is live.
     */
    @Synchronized
    private fun incrementAndCheckBothRegistered() {
        registeredCount++
        if (registeredCount >= 2) {
            cancelRegistrationWatchdog()
            recoveryAttempt = 0
            discoveryResponder?.activate(actualAirPlayName, actualRaopName)
            onStateChange(ProtocolState.ADVERTISING)
        }
    }

    /**
     * Creates an [NsdManager.RegistrationListener] with logging and callbacks.
     *
     * @param serviceLabel     Human-readable service type for log messages.
     * @param onRegisteredName Called with the actual registered service name (may differ from
     *   requested due to collision resolution). Pass null if the name is not user-visible.
     * @param onSuccess        Called on [onServiceRegistered].
     * @param onFailure        Called on [onRegistrationFailed].
     */
    private fun createRegistrationListener(
        serviceLabel: String,
        onRegisteredName: ((String) -> Unit)?,
        onSuccess: () -> Unit,
        onFailure: () -> Unit
    ): NsdManager.RegistrationListener {
        val listenerGeneration = generation
        return object : NsdManager.RegistrationListener {
            private var completed = false

            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                synchronized(this@MdnsService) {
                    if (!isStarted || generation != listenerGeneration) {
                        try { nsdManager.unregisterService(this) } catch (_: Exception) { }
                        return
                    }
                    if (completed) return
                    completed = true
                    // NsdManager may append " (2)" to resolve name conflicts.
                    // Log the actual name so we can debug picker-visibility issues.
                    Logger.i("mDNS registered: $serviceLabel as '${serviceInfo.serviceName}'")
                    onRegisteredName?.invoke(serviceInfo.serviceName)
                    try { onSuccess() } catch (e: Exception) {
                        handleAdvertisingFailure("registration completion", e)
                        onFailure()
                    }
                }
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                synchronized(this@MdnsService) {
                    if (!isStarted || generation != listenerGeneration || completed) return
                    completed = true
                    // FAILURE_ALREADY_ACTIVE is a failed operation, not proof of a
                    // successful registration under the requested service identity.
                    Logger.e("mDNS registration FAILED for $serviceLabel, errorCode=$errorCode")
                    handleAdvertisingFailure("registration for $serviceLabel (code $errorCode)")
                    onFailure()
                }
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                synchronized(this@MdnsService) {
                    if (!isStarted || generation != listenerGeneration) {
                        Logger.d("mDNS unregistered: $serviceLabel")
                        return
                    }
                    handleAdvertisingFailure("unexpected unregistration of $serviceLabel")
                }
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                // Non-fatal: stale records expire according to their DNS TTL.
                Logger.w("mDNS unregistration failed for $serviceLabel, errorCode=$errorCode (non-fatal)")
            }
        }
    }

    companion object {
        /** Standard mDNS service type for AirPlay receivers. */
        private const val SERVICE_TYPE_AIRPLAY = "_airplay._tcp"

        /** Standard mDNS service type for RAOP (audio). Required alongside AirPlay. */
        private const val SERVICE_TYPE_RAOP = "_raop._tcp"

        /** AirPlay RTSP port — [RtspHandler] must listen on this port. */
        const val AIRPLAY_PORT = 7000

        /**
         * AirPlay feature bitmask: advertise screen mirroring, video, and audio support.
         * See TECHNICAL_SPEC.md §8 for the full bit-level breakdown.
         */
        private const val AIRPLAY_FEATURES = "0x5A7FFFF7,0x1E"

        /** Pretend to be an Apple TV so macOS uses the screen mirroring protocol. */
        private const val AIRPLAY_MODEL = "AppleTV5,3"

        /** AirPlay server version — matches a real Apple TV for maximum compatibility. */
        private const val AIRPLAY_SERVER_VERSION = "220.68"

        private const val MULTICAST_LOCK_TAG = "iMirror:AirPlayDiscovery"
        private const val WIFI_LOCK_TAG = "iMirror:AirPlayTransport"
        private const val NETWORK_DEBOUNCE_MS = 400L
        private const val REGISTRATION_TIMEOUT_MS = 5_000L
        private const val FALLBACK_NETWORK = "network-monitor-fallback"
        private val RETRY_DELAYS_MS = longArrayOf(1_000L, 2_000L, 5_000L, 10_000L, 30_000L)

        internal fun lanFingerprint(linkProperties: LinkProperties): String? {
            val addresses = linkProperties.linkAddresses.mapNotNull { linkAddress ->
                val address = linkAddress.address
                val unusableFlags = OsConstants.IFA_F_DADFAILED or OsConstants.IFA_F_DEPRECATED or
                    OsConstants.IFA_F_OPTIMISTIC or OsConstants.IFA_F_TENTATIVE
                if ((linkAddress.flags and unusableFlags) != 0 || address.isLoopbackAddress ||
                    address.isAnyLocalAddress || address.isMulticastAddress) return@mapNotNull null
                when (address) {
                    is Inet4Address -> if (address.isLinkLocalAddress) null
                        else "4:${address.hostAddress}/${linkAddress.prefixLength}"
                    is Inet6Address -> "6:${address.hostAddress}/${linkAddress.prefixLength}"
                    else -> null
                }
            }.sorted()
            if (addresses.isEmpty()) return null
            return "${linkProperties.interfaceName.orEmpty()}|${addresses.joinToString(",")}"
        }
    }
}
