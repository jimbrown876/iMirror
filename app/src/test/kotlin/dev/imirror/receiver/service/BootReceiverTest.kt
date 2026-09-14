package dev.imirror.receiver.service

import android.content.Intent
import dev.imirror.receiver.settings.AppSettings
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class BootReceiverTest {
    @Test
    fun `boot and update each restore the background receiver once`() = runTest {
        for (action in listOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)) {
            var starts = 0
            restoreReceiverAfterSystemEvent(action, { AppSettings() }, { starts++ })
            assertEquals(1, starts)
        }
    }

    @Test
    fun `explicit opt-out and disabled AirPlay both prevent unattended restart`() = runTest {
        for (settings in listOf(AppSettings(startOnBoot = false), AppSettings(airPlayEnabled = false))) {
            var starts = 0
            restoreReceiverAfterSystemEvent(Intent.ACTION_BOOT_COMPLETED, { settings }, { starts++ })
            assertEquals(0, starts)
        }
    }

    @Test
    fun `unrelated broadcast cannot read settings or start the receiver`() = runTest {
        for (action in listOf(null, Intent.ACTION_SCREEN_ON, Intent.ACTION_PACKAGE_REPLACED)) {
            var reads = 0
            var starts = 0
            restoreReceiverAfterSystemEvent(action, { reads++; AppSettings() }, { starts++ })
            assertEquals(0, reads)
            assertEquals(0, starts)
        }
    }

    @Test
    fun `stalled settings read cannot keep boot broadcast alive indefinitely`() = runTest {
        var starts = 0
        val result = runCatching {
            restoreReceiverAfterSystemEvent(
                Intent.ACTION_BOOT_COMPLETED,
                readSettings = { awaitCancellation() },
                startService = { starts++ },
                timeoutMillis = 100,
            )
        }
        assertTrue(result.exceptionOrNull() is TimeoutCancellationException)
        assertEquals(0, starts)
    }
}
