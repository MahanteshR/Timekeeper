package dev.timekeeper.app.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import org.junit.Assert.*
import org.junit.Test

class VerifiedBackupCopyTest {
    private val bytes = "saved timer settings".toByteArray()

    @Test fun successfulCopyIsAcceptedOnlyAfterItsBytesCanBeRead() {
        val saved = ByteArrayOutputStream()
        var accepted = false
        var read = false
        val result = VerifiedBackupCopy.save(bytes, { "new" }, { saved }, {
            read = true; ByteArrayInputStream(saved.toByteArray())
        }, { assertTrue(read); accepted = true }, { fail("A valid copy should be retained") })
        assertEquals("new", result)
        assertTrue(accepted)
    }

    @Test fun interruptedWriteKeepsOldBackupAndDiscardsOnlyNewCopy() {
        val documents = mutableMapOf("old" to bytes)
        var pointer = "old"
        assertThrows(IOException::class.java) {
            VerifiedBackupCopy.save<String>(bytes, { documents["new"] = byteArrayOf(); "new" }, {
                object : OutputStream() {
                    override fun write(value: Int) { throw IOException("Provider disconnected") }
                }
            }, { ByteArrayInputStream(documents.getValue(it)) }, { pointer = it }, { documents.remove(it); Unit })
        }
        assertEquals("old", pointer)
        assertArrayEquals(bytes, documents["old"])
        assertFalse(documents.containsKey("new"))
    }

    @Test fun partialOrAppendedContentNeverBecomesTheCurrentBackup() {
        for (actual in listOf(bytes.copyOf(3), bytes + byteArrayOf(0))) {
            var accepted = false
            var discarded = false
            assertThrows(IllegalStateException::class.java) {
                VerifiedBackupCopy.save<String>(bytes, { "new" }, { ByteArrayOutputStream() },
                    { ByteArrayInputStream(actual) }, { accepted = true }, { discarded = true })
            }
            assertFalse(accepted)
            assertTrue(discarded)
        }
    }

    @Test fun failedMetadataSaveDoesNotReplaceOldBackupPointer() {
        val saved = ByteArrayOutputStream()
        val pointer = "old"
        var discarded = false
        assertThrows(IllegalStateException::class.java) {
            VerifiedBackupCopy.save<String>(bytes, { "new" }, { saved }, { ByteArrayInputStream(saved.toByteArray()) },
                { error("Disk full") }, { discarded = true })
        }
        assertEquals("old", pointer)
        assertTrue(discarded)
    }
}
