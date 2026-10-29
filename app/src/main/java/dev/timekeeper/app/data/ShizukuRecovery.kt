package dev.timekeeper.app.data

import dev.timekeeper.core.ShizukuTrialPolicy
import org.json.JSONObject

/** Private journal contains no time-based expiry: only a verified release may remove it. */
internal data class ShizukuRecovery(val packageName: String, val token: String) {
    fun encode(): String = JSONObject().put("version", 1).put("package", packageName).put("token", token).toString()
    companion object {
        fun decode(raw: String): ShizukuRecovery {
            require(raw.length <= 1024) { "Recovery record is too large." }
            val json = JSONObject(raw)
            require(json.get("version") == 1 && json.keys().asSequence().toSet() == setOf("version", "package", "token"))
            val pkg = json.get("package") as? String ?: error("Invalid recovery package")
            val token = json.get("token") as? String ?: error("Invalid recovery token")
            require(ShizukuTrialPolicy.validPackage(pkg) && ShizukuTrialPolicy.validToken(token))
            return ShizukuRecovery(pkg, token)
        }
    }
}
