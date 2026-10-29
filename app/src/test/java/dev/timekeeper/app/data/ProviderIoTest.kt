package dev.timekeeper.app.data

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ProviderIoTest {
    @Test fun timeoutDoesNotWaitForUninterruptibleProviderAndDiscardsLateResult() {
        val release = CountDownLatch(1)
        val abandoned = CountDownLatch(1)
        val io = ProviderIo(150)
        val start = System.nanoTime()
        try {
            assertThrows(TimeoutCancellationException::class.java) {
                runBlocking { io.run(abandoned = { abandoned.countDown() }) {
                    while (release.count > 0) { try { release.await() } catch (_: InterruptedException) {} }
                    "uncommitted copy"
                } }
            }
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 2_000)
        } finally { release.countDown() }
        assertTrue(abandoned.await(2, TimeUnit.SECONDS))
    }

    @Test fun stalledWriteDoesNotBlockIndependentReadOrCancellation() = runBlocking {
        val release = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val io = ProviderIo(2_000)
        val writer = launch(start = CoroutineStart.UNDISPATCHED) {
            io.run { entered.countDown(); while (release.count > 0) {
                try { release.await() } catch (_: InterruptedException) {}
            }; "copy" }
        }
        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            assertEquals("restored settings", io.run { "restored settings" })
            withTimeout(500) { writer.cancelAndJoin() }
        } finally { release.countDown() }
    }

    @Test fun failedBackupRetriesWithoutAnyConfigurationChange() = runBlocking {
        var calls = 0
        val waits = mutableListOf<Long>()
        val failures = mutableListOf<Int>()
        retryBackup(action = { calls++; if (calls < 4) throw IOException("Provider offline") },
            failed = { failures += it }, wait = { waits += it })
        assertEquals(4, calls)
        assertEquals(listOf(1, 2, 3), failures)
        assertEquals(listOf(5_000L, 10_000L, 20_000L), waits)
    }

    @Test fun explicitCancellationStopsRetries() {
        var failures = 0
        assertThrows(java.util.concurrent.CancellationException::class.java) {
            runBlocking { retryBackup(action = { throw java.util.concurrent.CancellationException("Disconnected") },
                failed = { failures++ }, wait = { fail("Do not retry a disconnection") }) }
        }
        assertEquals(0, failures)
    }
}
