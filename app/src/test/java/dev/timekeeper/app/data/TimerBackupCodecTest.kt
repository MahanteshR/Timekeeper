package dev.timekeeper.app.data

import dev.timekeeper.core.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TimerBackupCodecTest {
    private val config = TimerConfiguration((1..10).map { Rule("app.number$it", "ಆ್ಯಪ್ $it") },
        694, "Asia/Kolkata", true, true, "DARK", true)
    private val savedAt = 1_793_257_000_000L
    private fun encoded() = TimerBackupCodec.encode(config, savedAt)
    private fun modified(edit: (JSONObject) -> Unit): ByteArray = JSONObject(String(encoded(), Charsets.UTF_8))
        .also(edit).toString().toByteArray(Charsets.UTF_8)
    private fun rejects(bytes: ByteArray) { assertThrows(Exception::class.java) { TimerBackupCodec.decode(bytes) } }

    @Test fun tenAppSetupRoundTripsIncludingUnicodeAndPreferences() {
        assertEquals(TimerBackup(config, savedAt), TimerBackupCodec.decode(encoded()))
    }
    @Test fun usageDeadlinesAndPermissionGrantsAreNotExported() {
        val json = JSONObject(String(encoded(), Charsets.UTF_8))
        assertEquals(setOf("format", "version", "savedAt", "rules", "settings"), json.keys().asSequence().toSet())
        assertFalse(json.getJSONObject("settings").has("strictUntil"))
        assertFalse(json.has("usage"))
        assertFalse(json.has("permissions"))
    }
    @Test fun rejectsUnrelatedFilesAndNewerFormats() {
        rejects("{}".toByteArray())
        rejects(modified { it.put("format", "unrelated") })
        rejects(modified { it.put("version", 3) })
    }
    @Test fun rejectsPartialAndTrailingDocuments() {
        rejects(encoded().copyOf(encoded().size / 2))
        rejects(encoded() + "{}".toByteArray())
        rejects(ByteArray(0))
    }
    @Test fun rejectsWrongTypesRatherThanCoercingThem() {
        rejects(modified { it.getJSONObject("settings").put("strict", "true") })
        rejects(modified { it.getJSONObject("settings").put("resetMinute", 694.5) })
        rejects(modified { it.getJSONArray("rules").getJSONObject(0).put("dailyMs", "60000") })
        rejects(modified { it.getJSONArray("rules").getJSONObject(0).put("enabled", 1) })
    }
    @Test fun rejectsDuplicatePackagesAndInvalidDurations() {
        rejects(modified { it.getJSONArray("rules").put(it.getJSONArray("rules").getJSONObject(0)) })
        rejects(modified { it.getJSONArray("rules").getJSONObject(0).put("dailyMs", 0) })
        rejects(modified { it.getJSONArray("rules").getJSONObject(0).put("chunkMs", 1) })
        rejects(modified { it.getJSONArray("rules").getJSONObject(0).put("chunkMs", 24 * 60 * MINUTE) })
        rejects(modified { it.getJSONArray("rules").getJSONObject(0).put("cooldownMs", -1) })
    }
    @Test fun rejectsBadSettingsBeforeRestore() {
        rejects(modified { it.getJSONObject("settings").put("resetMinute", Long.MAX_VALUE) })
        rejects(modified { it.getJSONObject("settings").put("zoneId", "unknown/zone") })
        rejects(modified { it.getJSONObject("settings").put("appearance", "UNKNOWN") })
    }
    @Test fun rejectsOversizedDeepAndMalformedUtf8Input() {
        rejects(ByteArray(TimerBackupCodec.MAX_BYTES + 1) { 32 })
        rejects(("[".repeat(100) + "]".repeat(100)).toByteArray())
        rejects(byteArrayOf(0xc3.toByte(), 0x28))
    }
    @Test fun repeatDaysRoundTripAndOldBackupsDefaultToEveryDay() {
        val selected = config.copy(rules = listOf(config.rules.first().copy(activeDays = Weekdays.WEEKDAYS)))
        assertEquals(selected, TimerBackupCodec.decode(TimerBackupCodec.encode(selected, savedAt)).configuration)
        val legacy = modified { root ->
            root.put("version", 1)
            val rules = root.getJSONArray("rules")
            for (i in 0 until rules.length()) rules.getJSONObject(i).remove("activeDays")
        }
        assertTrue(TimerBackupCodec.decode(legacy).configuration.rules.all { it.activeDays == Weekdays.EVERY_DAY })
        rejects(modified { it.getJSONArray("rules").getJSONObject(0).put("activeDays", 0) })
        rejects(modified { it.getJSONArray("rules").getJSONObject(0).put("activeDays", 128) })
    }
    @Test fun bracesInAppNamesDoNotConfuseDepthValidation() {
        val special = config.copy(rules = listOf(Rule("app.special", "App { [ \\\"name\\\" ] }")))
        assertEquals(special, TimerBackupCodec.decode(TimerBackupCodec.encode(special, savedAt)).configuration)
    }
}
