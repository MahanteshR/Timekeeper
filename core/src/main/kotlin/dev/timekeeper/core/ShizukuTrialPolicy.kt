package dev.timekeeper.core

/** Safety rules for manual experiments, separate from allowance enforcement. */
object ShizukuTrialPolicy {
    const val SHELL_UID = 2000
    const val SUSPENSION_MS = 10_000L
    const val OBSERVATION_MS = 30_000L
    const val MAX_TARGETS = 500
    const val MARKER = "dev.timekeeper.shizuku.lease"
    private val packagePattern = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
    private val tokenPattern = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

    fun validPackage(value: String): Boolean = value.length in 3..255 && packagePattern.matches(value)
    fun validToken(value: String): Boolean = tokenPattern.matches(value)
    fun supportedIdentity(serverUid: Int, clientUid: Int): Boolean = serverUid == SHELL_UID && clientUid in 10_000..99_999

    fun rejection(packageName: String, allowed: Set<String>, protected: Set<String>,
        systemApp: Boolean, enabled: Boolean, sameUser: Boolean, suspended: Boolean): String? = when {
        !validPackage(packageName) -> "Invalid app identifier."
        packageName in protected -> "This app is protected from experiments."
        packageName !in allowed -> "Choose an app already listed in App limits."
        systemApp -> "Only installed third-party apps can be tested."
        !sameUser -> "Only apps in the primary Android user can be tested."
        !enabled -> "This app is disabled; it cannot be tested."
        suspended -> "This app is already suspended. Its existing restriction was left alone."
        else -> null
    }

    enum class Recovery { RELEASE_OWNED, ALREADY_CLEAR, OWNERSHIP_CHANGED }
    fun recovery(suspended: Boolean, marker: String?, expectedToken: String): Recovery {
        require(validToken(expectedToken))
        return when {
            suspended && marker == expectedToken -> Recovery.RELEASE_OWNED
            !suspended -> Recovery.ALREADY_CLEAR
            else -> Recovery.OWNERSHIP_CHANGED
        }
    }
}
