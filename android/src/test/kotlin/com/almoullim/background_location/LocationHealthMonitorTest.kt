package com.almoullim.background_location

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class LocationHealthMonitorTest {
    private var clock = 0L
    private var blocked: String? = null
    private var probes = 0
    private var renewals = 0
    private var cancellations = 0
    private var probeDone: (() -> Unit)? = null
    private var renewDone: ((Boolean) -> Unit)? = null
    private lateinit var health: LocationHealthMonitor

    @Before fun setup() {
        health = LocationHealthMonitor({ clock }, { blocked }, { done ->
            probes++; probeDone = done; { cancellations++ }
        }, { done -> renewals++; renewDone = done; { cancellations++ } })
        health.subscriptionResult(true)
    }
    private fun advance(ms: Long) { clock += ms; health.tick() }

    @Test fun stationarySilenceChecksFreshFixWithoutAutomaticResubscription() {
        health.accepted(0)
        advance(30_000)
        assertEquals("STALE", health.snapshot()["state"])
        assertEquals(0, probes)
        advance(30_000)
        assertEquals(1, probes)
        assertEquals("CHECKING", health.snapshot()["state"])
        health.callbackReceived()
        health.accepted(clock) // Same coordinates are permitted; only freshness matters.
        probeDone!!()
        assertEquals(0, renewals)
        assertEquals("FRESH", health.snapshot()["state"])
        assertEquals(0, health.attempts)
    }

    @Test fun oldAcceptedFixAndSnapshotReadsNeverMakeLocationFresh() {
        clock = 100_000
        health.accepted(50_000)
        repeat(3) { assertEquals(50_000L, health.snapshot()["fixAgeMs"]) }
        assertEquals("STALE", health.snapshot()["state"])
        health.callbackReceived()
        assertEquals(50_000L, health.snapshot()["fixAgeMs"])
        health.accepted(40_000)
        assertEquals(50_000L, health.snapshot()["fixAgeMs"])
    }

    @Test fun absentProviderGetsOnlyThreeSerializedAttemptsWithBackoff() {
        advance(60_000)
        health.tick()
        assertEquals(1, probes)
        probeDone!!()
        assertEquals(1, renewals)
        renewDone!!(true)
        advance(119_999)
        assertEquals(1, probes)
        advance(1)
        probeDone!!(); renewDone!!(true)
        advance(300_000)
        probeDone!!(); renewDone!!(true)
        repeat(600) { advance(30_000) } // Five-hour gap, no unbounded recovery loop.
        assertEquals(3, probes)
        assertEquals(3, renewals)
        health.accepted(clock)
        advance(60_000)
        assertEquals(4, probes) // A genuinely fresh fix starts a new outage budget.
    }

    @Test fun poorAccuracyCallbacksKeepWarningWithoutChurningSubscription() {
        advance(60_000)
        health.callbackReceived() // Rejected by existing validator, not accepted here.
        probeDone!!()
        assertEquals(0, renewals)
        assertEquals("WAITING", health.snapshot()["state"])
    }

    @Test fun successfulOneOffFixDoesNotHideAFailedContinuousSubscription() {
        health.subscriptionResult(false)
        advance(60_000)
        health.callbackReceived()
        health.accepted(clock)
        probeDone!!()
        assertEquals(1, renewals)
        renewDone!!(true)
        assertEquals("FRESH", health.snapshot()["state"])
    }

    @Test fun probeTimeoutCancelsAndLateCompletionCannotDuplicateRenewal() {
        advance(60_000)
        val late = probeDone!!
        advance(30_000)
        assertEquals(1, cancellations)
        assertEquals(1, renewals)
        late()
        assertEquals(1, renewals)
        advance(30_000)
        assertEquals("SUBSCRIPTION_ERROR", health.snapshot()["state"])
        renewDone!!(true)
        assertEquals("SUBSCRIPTION_ERROR", health.snapshot()["state"])
    }

    @Test fun stopInvalidatesProbeRenewalAndFutureTicks() {
        advance(60_000)
        val late = probeDone!!
        health.stop()
        late()
        health.accepted(clock)
        advance(600_000)
        assertEquals(0, renewals)
        assertEquals(1, probes)
        assertEquals("STOPPED", health.snapshot()["state"])
    }

    @Test fun permissionOrProviderLossPreventsRecoveryAndCancelsPendingProbe() {
        blocked = "LOCATION_DISABLED"
        advance(60_000)
        assertEquals(0, probes)
        assertEquals(blocked, health.snapshot()["state"])
        blocked = null
        health.tick()
        assertEquals(1, probes)
        blocked = "PERMISSION_REQUIRED"
        health.tick()
        probeDone!!()
        assertEquals(1, cancellations)
        assertEquals(0, renewals)
        assertEquals(blocked, health.snapshot()["state"])
    }

    @Test fun ringingCancelsRecoveryButStillRecordsFreshGps() {
        advance(60_000)
        health.disableRecovery()
        probeDone!!()
        health.accepted(clock)
        assertEquals("FRESH", health.snapshot()["state"])
        advance(600_000)
        assertEquals(1, probes)
        assertEquals(0, renewals)
    }

    @Test fun recreationStartsUnverifiedAndDoesNotReusePriorFreshness() {
        health.accepted(0)
        health.stop()
        val recreated = LocationHealthMonitor({ clock }, { null }, { {} }, { {} })
        assertNull(recreated.snapshot()["fixAgeMs"])
        assertEquals("WAITING", recreated.snapshot()["state"])
    }
}
