package dev.timekeeper.app.data

import java.io.InputStream
import java.io.OutputStream

/** Write a new document and verify it before advancing the backup pointer. */
internal object VerifiedBackupCopy {
    fun <T> save(bytes: ByteArray, create: () -> T, output: (T) -> OutputStream?,
        input: (T) -> InputStream?, accept: (T) -> Unit, discard: (T) -> Unit): T {
        val document = create()
        try {
            output(document)?.use { it.write(bytes); it.flush() }
                ?: error("The backup folder is unavailable.")
            val verified = input(document)?.use { stream ->
                val actual = ByteArray(bytes.size + 1)
                var total = 0
                while (total < actual.size) {
                    val count = stream.read(actual, total, actual.size - total)
                    if (count == -1) break
                    total += count
                }
                total == bytes.size && actual.copyOf(total).contentEquals(bytes)
            } ?: false
            check(verified) { "The saved backup could not be verified." }
            accept(document)
            return document
        } catch (e: Exception) {
            runCatching { discard(document) }
            throw e
        }
    }
}
