package dev.timekeeper.app.data

import java.io.IOException
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*

internal class IoCancellation {
    private val active = AtomicBoolean(true)
    private val cancellations = ConcurrentLinkedQueue<() -> Unit>()
    fun checkActive() { if (!active.get() || Thread.currentThread().isInterrupted) throw java.util.concurrent.CancellationException("Operation cancelled") }
    fun onCancel(action: () -> Unit) {
        cancellations.add(action)
        if (!active.get()) cancelActions()
    }
    fun cancel() { active.set(false); cancelActions() }
    private fun cancelActions() {
        while (true) {
            val action = cancellations.poll() ?: break
            ProviderIo.cleanup { runCatching(action) }
        }
    }
}

/** Await a bounded external operation without making coroutine cancellation wait for a stuck provider. */
internal class ProviderIo(private val timeoutMs: Long = 15_000) {
    private val executor = ThreadPoolExecutor(4, 4, 30, TimeUnit.SECONDS, ArrayBlockingQueue(4),
        ThreadFactory { task -> Thread(task, "Timekeeper documents").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy()).apply { allowCoreThreadTimeOut(true) }

    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun <T> run(abandoned: (T) -> Unit = {}, block: (IoCancellation) -> T): T = withTimeout(timeoutMs) {
        suspendCancellableCoroutine { continuation ->
            val cancellation = IoCancellation()
            val task = FutureTask<Unit> {
                val result = runCatching { cancellation.checkActive(); block(cancellation) }
                result.fold(onSuccess = { value ->
                    if (continuation.isActive) continuation.resume(value, onCancellation = { cleanup { abandoned(value) } })
                    else cleanup { abandoned(value) }
                }, onFailure = { if (continuation.isActive) continuation.resumeWith(Result.failure(it)) })
            }
            continuation.invokeOnCancellation { cancellation.cancel(); task.cancel(true) }
            try { executor.execute(task) }
            catch (_: RejectedExecutionException) {
                continuation.resumeWith(Result.failure(IOException("Documents provider is still busy. Disconnect the backup or try again later.")))
            }
        }
    }

    companion object {
        private val cleanupExecutor = Executors.newFixedThreadPool(2) { task ->
            Thread(task, "Timekeeper document cleanup").apply { isDaemon = true }
        }
        fun cleanup(action: () -> Unit) { cleanupExecutor.execute { runCatching(action) } }
    }
}
