package dev.timekeeper.core

import org.junit.Assert.*
import org.junit.Test

class SuspensionTrialTest {
    private val pkg = "com.example.player"
    private val token = "e98d4fa0-5f9c-4e16-aa60-8b5d6ed2d4b2"
    private class Fake : SuspensionBackend {
        var current = SuspensionState(false, null)
        var releases = 0
        var failRelease = false
        var failAfterApply = false
        override fun state(packageName: String) = current
        override fun suspend(packageName: String, token: String) {
            current = SuspensionState(true, token)
            if (failAfterApply) error("Caller lost its reply")
        }
        override fun unsuspend(packageName: String) {
            if (failRelease) error("Backend unavailable")
            releases++; current = SuspensionState(false, null)
        }
    }
    @Test fun expiresUsingElapsedTimeAndReleasesOnce() {
        val backend = Fake(); var elapsed = 50L; val trial = SuspensionTrial(backend) { elapsed }
        trial.start(pkg, token); elapsed += 9_999
        assertNull(trial.expire()); elapsed++
        assertTrue(trial.expire()!!.finished); assertNull(trial.pending)
        assertNull(trial.expire()); assertEquals(1, backend.releases)
    }
    @Test fun doesNotTouchPreexistingRestriction() {
        val backend = Fake().apply { current = SuspensionState(true, "other") }
        val trial = SuspensionTrial(backend) { 0 }
        assertThrows(IllegalStateException::class.java) { trial.start(pkg, token) }
        assertEquals("other", backend.current.marker); assertEquals(0, backend.releases); assertNull(trial.pending)
    }
    @Test fun failedReleaseKeepsLeaseForRetry() {
        val backend = Fake(); var elapsed = 0L; val trial = SuspensionTrial(backend) { elapsed }
        trial.start(pkg, token); elapsed = 10_000; backend.failRelease = true
        assertThrows(IllegalStateException::class.java) { trial.expire() }
        assertNotNull(trial.pending); backend.failRelease = false
        assertTrue(trial.expire()!!.finished); assertNull(trial.pending)
    }
    @Test fun callerFailureAfterMutationTriggersRollback() {
        val backend = Fake().apply { failAfterApply = true }; val trial = SuspensionTrial(backend) { 0 }
        assertThrows(IllegalStateException::class.java) { trial.start(pkg, token) }
        assertFalse(backend.current.suspended); assertEquals(1, backend.releases); assertNull(trial.pending)
    }
    @Test fun unknownOrChangedOwnershipIsNeverClearedOrForgotten() {
        for (marker in listOf(null, "7da95509-338c-482b-bdc1-444444444444")) {
            val backend = Fake(); val trial = SuspensionTrial(backend) { 0 }
            trial.start(pkg, token); backend.current = SuspensionState(true, marker)
            assertFalse(trial.release(pkg, token).finished)
            assertNotNull(trial.pending); assertEquals(0, backend.releases)
        }
    }
    @Test fun reconnectCanRecoverFromDurableClientRecordWithoutInMemoryLease() {
        val backend = Fake().apply { current = SuspensionState(true, token) }
        val replacement = SuspensionTrial(backend) { 0 }
        assertTrue(replacement.release(pkg, token).finished); assertFalse(backend.current.suspended)
    }
    @Test fun recoveryBeforeDelayedStartPreventsUntrackedSuspension() {
        val backend = Fake(); val trial = SuspensionTrial(backend) { 0 }
        assertTrue(trial.release(pkg, token).finished)
        assertThrows(IllegalStateException::class.java) { trial.start(pkg, token) }
        assertFalse(backend.current.suspended); assertNull(trial.pending)
    }
    @Test fun onlyOneTrialMayRunAndReleaseIsIdempotent() {
        val backend = Fake(); val trial = SuspensionTrial(backend) { 0 }
        trial.start(pkg, token)
        assertThrows(IllegalStateException::class.java) { trial.start("com.example.other", token) }
        assertTrue(trial.release(pkg, token).finished); assertTrue(trial.release(pkg, token).finished)
        assertEquals(1, backend.releases)
    }
}
