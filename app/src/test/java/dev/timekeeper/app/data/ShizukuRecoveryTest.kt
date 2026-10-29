package dev.timekeeper.app.data

import org.junit.Assert.*
import org.junit.Test

class ShizukuRecoveryTest {
    private val record = ShizukuRecovery("com.example.player", "12345678-1234-1234-1234-123456789abc")
    @Test fun roundTripHasNoExpiry() { assertEquals(record, ShizukuRecovery.decode(record.encode())); assertFalse(record.encode().contains("expiry")) }
    @Test fun refusesCorruptOrIncompatibleRecord() {
        listOf("{}", record.encode().replace("\"version\":1", "\"version\":2"), record.encode().replace("com.example.player", "--user 0"),
            record.encode().replace(record.token, "bad"), record.encode().dropLast(1) + ",\"expiry\":1}", "x".repeat(1025)).forEach {
            assertTrue(runCatching { ShizukuRecovery.decode(it) }.isFailure)
        }
    }
}
