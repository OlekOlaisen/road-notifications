package no.roadnotifications.notification

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForkjoersveiStayTrackerTest {
    @Test
    fun firstApproachAllowsPathMatchAndEnter() {
        val tracker = ForkjoersveiStayTracker()
        tracker.onTick(onPriorityRoad = false, prioritySignInWindow = true)
        assertFalse(tracker.suppressPathMatch(wasOnPriorityRoad = false))
        assertFalse(tracker.suppressEnter())
    }

    @Test
    fun afterEntranceAlertFurther206AndStretchEnterAreSilent() {
        val tracker = ForkjoersveiStayTracker()
        tracker.onTick(onPriorityRoad = false, prioritySignInWindow = true)
        tracker.markAlerted()

        tracker.onTick(onPriorityRoad = true, prioritySignInWindow = true)
        assertTrue(tracker.suppressPathMatch(wasOnPriorityRoad = true))
        assertTrue(tracker.suppressEnter())
    }

    @Test
    fun joiningMidStretchWithoutSignStillSuppressesLater206() {
        val tracker = ForkjoersveiStayTracker()
        tracker.onTick(onPriorityRoad = true, prioritySignInWindow = false)
        assertFalse(tracker.suppressEnter())
        tracker.markAlerted()

        tracker.onTick(onPriorityRoad = true, prioritySignInWindow = true)
        assertTrue(tracker.suppressPathMatch(wasOnPriorityRoad = true))
    }

    @Test
    fun leavingTheStretchAllowsTheNextPriorityRoad() {
        val tracker = ForkjoersveiStayTracker()
        tracker.markAlerted()
        tracker.onTick(onPriorityRoad = true, prioritySignInWindow = false)
        assertTrue(tracker.alertedThisStay)

        tracker.onTick(onPriorityRoad = false, prioritySignInWindow = false)
        assertFalse(tracker.alertedThisStay)
        assertFalse(tracker.suppressPathMatch(wasOnPriorityRoad = false))
        assertFalse(tracker.suppressEnter())
    }

    @Test
    fun stayIsKeptWhileEntranceSignRemainsInWindow() {
        val tracker = ForkjoersveiStayTracker()
        tracker.markAlerted()
        tracker.onTick(onPriorityRoad = false, prioritySignInWindow = true)
        assertTrue(tracker.alertedThisStay)
        assertTrue(tracker.suppressPathMatch(wasOnPriorityRoad = false))
        assertTrue(tracker.suppressEnter())
    }
}
