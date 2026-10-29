package dev.timekeeper.core

data class SuspensionState(val suspended: Boolean, val marker: String?)
data class TrialLease(val packageName: String, val token: String, val untilElapsedMs: Long)
data class TrialRelease(val finished: Boolean, val stillSuspended: Boolean, val ownershipVerified: Boolean)

interface SuspensionBackend {
    fun state(packageName: String): SuspensionState
    fun suspend(packageName: String, token: String)
    fun unsuspend(packageName: String)
}

/** Monotonic rollback guard. A failed or unverifiable release retains its recovery record. */
class SuspensionTrial(private val backend: SuspensionBackend, private val elapsed: () -> Long) {
    private val cancelledTokens = HashSet<String>()
    @Volatile var pending: TrialLease? = null
        private set

    @Synchronized fun start(packageName: String, token: String): TrialLease {
        require(ShizukuTrialPolicy.validPackage(packageName) && ShizukuTrialPolicy.validToken(token))
        check(token !in cancelledTokens && cancelledTokens.size < 512) { "Reconnect the lab before another trial." }
        check(pending == null) { "Recover the previous test before starting another." }
        check(!backend.state(packageName).suspended) { "An existing suspension was left unchanged." }
        val lease = TrialLease(packageName, token, elapsed() + ShizukuTrialPolicy.SUSPENSION_MS)
        pending = lease
        try {
            backend.suspend(packageName, token)
            val after = backend.state(packageName)
            check(after.suspended && after.marker == token) { "Suspension ownership could not be verified. Check recovery." }
        } catch (e: Exception) {
            runCatching { release(packageName, token) }
            throw e
        }
        return lease
    }

    @Synchronized fun release(packageName: String, token: String): TrialRelease {
        require(ShizukuTrialPolicy.validPackage(packageName) && ShizukuTrialPolicy.validToken(token))
        // A recovery request may reach the helper before a timed-out start is dispatched.
        cancelledTokens.add(token)
        val before = backend.state(packageName)
        val result = when (ShizukuTrialPolicy.recovery(before.suspended, before.marker, token)) {
            ShizukuTrialPolicy.Recovery.ALREADY_CLEAR -> TrialRelease(true, false, true)
            ShizukuTrialPolicy.Recovery.OWNERSHIP_CHANGED -> TrialRelease(false, before.suspended, false)
            ShizukuTrialPolicy.Recovery.RELEASE_OWNED -> {
                backend.unsuspend(packageName)
                val after = backend.state(packageName)
                check(after.marker != token) { "The test restriction is still present. Retry recovery." }
                TrialRelease(true, after.suspended, true)
            }
        }
        if (result.finished && pending?.let { it.packageName == packageName && it.token == token } == true) pending = null
        return result
    }

    @Synchronized fun expire(): TrialRelease? = pending?.takeIf { elapsed() >= it.untilElapsedMs }
        ?.let { release(it.packageName, it.token) }
}
