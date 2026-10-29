package dev.timekeeper.app.data

import dev.timekeeper.core.Rule
import dev.timekeeper.core.TimerConfiguration
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

data class TimerBackup(val configuration: TimerConfiguration, val savedAtMs: Long)

/** A bounded, versioned document. Validate the whole document before changing any app state. */
object TimerBackupCodec {
    const val MAX_BYTES = 512 * 1024

    fun encode(configuration: TimerConfiguration, savedAtMs: Long): ByteArray = JSONObject().apply {
        put("format", "dev.timekeeper.timer-backup")
        put("version", 2)
        put("savedAt", savedAtMs)
        put("rules", JSONArray().apply { configuration.rules.forEach { rule ->
            put(JSONObject().apply {
                put("package", rule.packageName); put("label", rule.label)
                put("dailyMs", rule.dailyMs); put("chunkMs", rule.chunkMs)
                put("cooldownMs", rule.cooldownMs); put("enabled", rule.enabled)
                put("activeDays", rule.activeDays)
            })
        } })
        put("settings", JSONObject().apply {
            put("resetMinute", configuration.resetMinute); put("zoneId", configuration.zoneId)
            put("notifications", configuration.notifications); put("emergency", configuration.emergency)
            put("appearance", configuration.appearance); put("strict", configuration.strict)
        })
    }.toString(2).toByteArray(Charsets.UTF_8).also {
        require(it.size <= MAX_BYTES) { "This setup is too large to back up." }
    }

    fun decode(bytes: ByteArray): TimerBackup {
        require(bytes.size in 1..MAX_BYTES) { "Choose a Timekeeper backup smaller than 512 KB." }
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        // Avoid parser stack exhaustion before handing the document to JSONObject.
        var depth = 0
        var quoted = false
        var escaped = false
        text.forEach { ch ->
            if (quoted) {
                if (escaped) escaped = false else if (ch == '\\') escaped = true else if (ch == '"') quoted = false
            } else when (ch) {
                '"' -> quoted = true
                '{', '[' -> { depth++; require(depth <= 12) { "Invalid backup structure." } }
                '}', ']' -> { depth--; require(depth >= 0) { "Invalid backup structure." } }
            }
        }
        require(!quoted && depth == 0) { "Incomplete backup file." }
        val tokener = JSONTokener(text)
        val root = tokener.nextValue() as? JSONObject ?: error("This is not a Timekeeper backup.")
        require(tokener.nextClean() == '\u0000') { "Unexpected data after the backup." }
        require(root.string("format") == "dev.timekeeper.timer-backup") { "This is not a Timekeeper backup." }
        val version = root.integer("version")
        require(version in 1L..2L) { "This backup needs a newer version of Timekeeper." }
        val savedAt = root.integer("savedAt")
        require(savedAt > 0) { "Invalid backup date." }
        val array = root.getJSONArray("rules")
        require(array.length() <= 500) { "Too many app limits in this backup." }
        val rules = (0 until array.length()).map { index -> array.getJSONObject(index).let {
            Rule(it.string("package"), it.string("label"), it.integer("dailyMs"),
                it.integer("chunkMs"), it.integer("cooldownMs"), it.boolean("enabled"),
                if (version == 1L) dev.timekeeper.core.Weekdays.EVERY_DAY else it.integer("activeDays").also { days ->
                    require(days in 1L..127L) { "Invalid repeat days in this backup." }
                }.toInt())
        } }
        val settings = root.getJSONObject("settings")
        val reset = settings.integer("resetMinute")
        require(reset in 0..1439) { "Invalid reset time in this backup." }
        return TimerBackup(TimerConfiguration(rules, reset.toInt(), settings.string("zoneId"),
            settings.boolean("notifications"), settings.boolean("emergency"), settings.string("appearance"),
            settings.boolean("strict")), savedAt)
    }

    private fun JSONObject.string(key: String): String = get(key) as? String
        ?: error("Invalid $key in this backup.")
    private fun JSONObject.boolean(key: String): Boolean = get(key) as? Boolean
        ?: error("Invalid $key in this backup.")
    private fun JSONObject.integer(key: String): Long = when (val value = get(key)) {
        is Int -> value.toLong()
        is Long -> value
        else -> error("Invalid $key in this backup.")
    }
}
