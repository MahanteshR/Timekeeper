package dev.timekeeper.core

import org.junit.Assert.*
import org.junit.Test

class ShizukuTrialPolicyTest {
    private val target = "com.example.player"
    private val token = "e98d4fa0-5f9c-4e16-aa60-8b5d6ed2d4b2"
    private fun reject(name: String = target, allowed: Set<String> = setOf(target), protected: Set<String> = emptySet(),
        system: Boolean = false, enabled: Boolean = true, sameUser: Boolean = true, suspended: Boolean = false) =
        ShizukuTrialPolicy.rejection(name, allowed, protected, system, enabled, sameUser, suspended)

    @Test fun acceptsOnlyAnExplicitEligibleTarget() { assertNull(reject()); assertNotNull(reject(allowed = emptySet())) }
    @Test fun protectsHomeAndOwnPackagesEvenWhenTheyHaveRules() { assertNotNull(reject(protected = setOf(target))) }
    @Test fun blocksSystemDisabledAndOtherUserTargets() {
        assertNotNull(reject(system = true)); assertNotNull(reject(enabled = false)); assertNotNull(reject(sameUser = false))
    }
    @Test fun refusesExistingSuspensions() { assertNotNull(reject(suspended = true)) }
    @Test fun rejectsRootAndOtherIdentities() {
        assertTrue(ShizukuTrialPolicy.supportedIdentity(2000, 10123))
        for (uid in listOf(0, 1000, 10123)) assertFalse(ShizukuTrialPolicy.supportedIdentity(uid, 10123))
        assertFalse(ShizukuTrialPolicy.supportedIdentity(2000, 110123))
    }
    @Test fun identifiersCannotContainOptionsCommandsOrWhitespace() {
        for (name in listOf("--user", "com.example;reboot", "com.example app", "com.example/app", "a", "a..b"))
            assertFalse(name, ShizukuTrialPolicy.validPackage(name))
        assertTrue(ShizukuTrialPolicy.validPackage(target))
    }
    @Test fun onlyAMatchingMarkerPermitsUnsuspension() {
        assertEquals(ShizukuTrialPolicy.Recovery.RELEASE_OWNED, ShizukuTrialPolicy.recovery(true, token, token))
        assertEquals(ShizukuTrialPolicy.Recovery.OWNERSHIP_CHANGED, ShizukuTrialPolicy.recovery(true, null, token))
        assertEquals(ShizukuTrialPolicy.Recovery.OWNERSHIP_CHANGED,
            ShizukuTrialPolicy.recovery(true, "7da95509-338c-482b-bdc1-444444444444", token))
    }
    @Test fun anAlreadyClearPackageNeedsNoMutation() {
        assertEquals(ShizukuTrialPolicy.Recovery.ALREADY_CLEAR, ShizukuTrialPolicy.recovery(false, null, token))
    }
    @Test fun malformedLeaseTokensAreRejected() {
        assertFalse(ShizukuTrialPolicy.validToken("")); assertFalse(ShizukuTrialPolicy.validToken("anything"))
        assertTrue(ShizukuTrialPolicy.validToken(token))
    }
}
