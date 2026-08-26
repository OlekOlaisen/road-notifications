package no.roadnotifications.notification

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertPassTrackerTest {
    @Test
    fun sameIdIsSilentWhileStillMatching() {
        val tracker = AlertPassTracker()
        tracker.prepareTick(setOf(10L))
        assertTrue(tracker.shouldNotify(10L))
        tracker.remember(10L)

        tracker.prepareTick(setOf(10L))
        assertFalse(tracker.shouldNotify(10L))
    }

    @Test
    fun sameIdCanAlertAgainAfterLeavingTheWindow() {
        val tracker = AlertPassTracker()
        tracker.prepareTick(setOf(10L))
        tracker.remember(10L)

        tracker.prepareTick(emptySet())
        tracker.prepareTick(setOf(10L))
        assertTrue(tracker.shouldNotify(10L))
    }

    @Test
    fun differentIdsAreIndependent() {
        val tracker = AlertPassTracker()
        tracker.prepareTick(setOf(10L, 11L))
        assertTrue(tracker.shouldNotify(10L))
        tracker.remember(10L)

        tracker.prepareTick(setOf(10L, 11L))
        assertFalse(tracker.shouldNotify(10L))
        assertTrue(tracker.shouldNotify(11L))
    }
}
