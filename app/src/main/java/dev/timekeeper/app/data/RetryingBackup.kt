package dev.timekeeper.app.data

import dev.timekeeper.core.BackupRetryPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay

internal suspend fun retryBackup(action: suspend () -> Unit, failed: (Int) -> Unit,
    wait: suspend (Long) -> Unit = { delay(it) }) {
    var failures = 0
    while (true) {
        try { action(); return }
        catch (e: CancellationException) { if (e !is TimeoutCancellationException) throw e }
        catch (_: Exception) { /* Retry transient provider failures without another config edit. */ }
        failures++
        failed(failures)
        wait(BackupRetryPolicy.delayMs(failures))
    }
}
