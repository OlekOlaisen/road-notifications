package no.roadnotifications.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StrekningsAtkTripTrackerTest {
    @Test
    fun shortGpsBlipDoesNotFireEndAlert() {
        val tracker = StrekningsAtkTripTracker()
        tracker.onTick(
            onSection = true,
            nearExit = false,
            signInWindow = false,
            latitude = 60.0,
            longitude = 10.0,
            nowElapsedRealtimeMs = 0L,
        )
        tracker.onTick(
            onSection = true,
            nearExit = false,
            signInWindow = false,
            latitude = 60.001,
            longitude = 10.0,
            nowElapsedRealtimeMs = 5_000L,
        )
        tracker.onTick(
            onSection = false,
            nearExit = false,
            signInWindow = false,
            latitude = 60.001,
            longitude = 10.0,
            nowElapsedRealtimeMs = 5_000L + StrekningsAtkTripTracker.END_AFTER_LEAVE_MS,
        )
        assertNull(tracker.consumeEndAlert())
    }

    @Test
    fun longTripFiresEndAtExitWithAverageSpeed() {
        val tracker = StrekningsAtkTripTracker()
        tracker.onTick(
            onSection = true,
            nearExit = false,
            signInWindow = true,
            latitude = 60.0,
            longitude = 10.0,
            nowElapsedRealtimeMs = 0L,
        )
        tracker.markAlerted()
        // ~111 m north per 0.001 deg latitude, 10 steps = ~1.1 km in 20 s → ~198 km/t
        var latitude = 60.0
        var nowMs = 0L
        repeat(10) { step ->
            nowMs = (step + 1) * 2_000L
            latitude = 60.0 + (step + 1) * 0.001
            tracker.onTick(
                onSection = true,
                nearExit = step == 9,
                signInWindow = false,
                latitude = latitude,
                longitude = 10.0,
                nowElapsedRealtimeMs = nowMs,
            )
        }
        val kmh = tracker.consumeEndAlert()
        assertTrue("expected end alert, got $kmh", kmh != null && kmh > 50)
        assertNull(tracker.consumeEndAlert())
    }

    @Test
    fun pathMatchIsSuppressedAfterStart() {
        val tracker = StrekningsAtkTripTracker()
        assertFalse(tracker.suppressPathMatch(wasOnSection = false))
        tracker.markAlerted()
        assertTrue(tracker.suppressPathMatch(wasOnSection = false))
        assertTrue(tracker.suppressEnter())
    }

    @Test
    fun sluttVerdiPrefixIsStable() {
        assertEquals("SLUTT:", StrekningsAtkTripTracker.SLUTT_VERDI_PREFIX)
    }
}
