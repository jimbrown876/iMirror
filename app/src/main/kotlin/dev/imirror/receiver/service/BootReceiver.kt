package dev.imirror.receiver.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.imirror.receiver.settings.SettingsRepository
import dev.imirror.receiver.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import timber.log.Timber

/**
 * BootReceiver — restores [MirrorService] after boot or update when automatic start is enabled.
 *
 * WHY: Android kills all services on reboot. For a receiver app to work without
 * the user manually reopening the app, we register for BOOT_COMPLETED.
 * The setting is checked asynchronously before starting the service to avoid
 * unnecessary foreground service starts that would show an unexpected notification.
 *
 * HOW:
 * 1. System fires BOOT_COMPLETED ~30–60 seconds after boot.
 * 2. We read [AppSettings.startOnBoot] from DataStore.
 * 3. If enabled, call [ServiceController.start] — that fires a foreground service
 *    intent which is safe to call from a BroadcastReceiver.
 *
 * Declared in AndroidManifest.xml with `exported="false"` — only the system
 * can fire BOOT_COMPLETED, so no external app can trigger this receiver.
 *
 * goAsync keeps the broadcast pending during the settings read; it does not remove
 * the broadcast timeout. Bound that work and always call PendingResult.finish().
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (!isReceiverRestoreAction(intent.action)) return

        Timber.d("BootReceiver: %s received", intent.action)

        val pendingResult: PendingResult = goAsync()
        val appContext = context.applicationContext

        CoroutineScope(Dispatchers.IO).launch {
            try {
                restoreReceiverAfterSystemEvent(
                    intent.action,
                    readSettings = { SettingsRepository(appContext).settingsFlow.first() },
                    startService = { ServiceController.start(appContext) }
                )
            } catch (e: Exception) {
                Timber.e(e, "BootReceiver: failed to read settings or start service")
            } finally {
                pendingResult.finish()
            }
        }
    }
}

internal fun isReceiverRestoreAction(action: String?): Boolean =
    action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED

/** Shared production policy keeps boot and post-update restoration identical and testable. */
internal suspend fun restoreReceiverAfterSystemEvent(
    action: String?,
    readSettings: suspend () -> AppSettings,
    startService: () -> Unit,
    timeoutMillis: Long = 7_500L,
) {
    if (!isReceiverRestoreAction(action)) return
    withTimeout(timeoutMillis) {
        val settings = readSettings()
        if (settings.startOnBoot && settings.anyProtocolEnabled) {
            Timber.i("Automatic receiver restoration after %s", action)
            startService()
        }
    }
}
