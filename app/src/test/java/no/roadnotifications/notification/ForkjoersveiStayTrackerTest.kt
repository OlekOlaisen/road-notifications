package no.roadnotifications.notification

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForkjoersveiStayTrackerTest {
    @Test
    fun firstApproachAllowsPathMatchAndEnter() {
        val tracker = ForkjoersveiStayTracker()
        tracker.onTick(
            onPriorityRoad = false,
            prioritySignInWindow = true,
            nowElapsedRealtimeMs = 0L,
        )
        assertFalse(tracker.suppressPathMatch(wasOnPriorityRoad = false))
        assertFalse(tracker.suppressEnter())
    }

    @Test
    fun afterEntranceAlertFurther206AndStretchEnterAreSilent() {
        val tracker = ForkjoersveiStayTracker()
        tracker.onTick(
            onPriorityRoad = false,
            prioritySignInWindow = true,
            nowElapsedRealtimeMs = 0L,
        )
        tracker.markAlerted()

        tracker.onTick(
            onPriorityRoad = true,
            prioritySignInWindow = true,
            nowElapsedRealtimeMs = 1_000L,
        )
        assertTrue(tracker.suppressPathMatch(wasOnPriorityRoad = true))
        assertTrue(tracker.suppressEnter())
    }

    @Test
    fun joiningMidStretchWithoutSignStillSuppressesLater206() {
        val tracker = ForkjoersveiStayTracker()
        tracker.onTick(
            onPriorityRoad = true,
            prioritySignInWindow = false,
            nowElapsedRealtimeMs = 0L,
        )
        assertFalse(tracker.suppressEnter())
        tracker.markAlerted()

        tracker.onTick(
            onPriorityRoad = true,
            prioritySignInWindow = true,
            nowElapsedRealtimeMs = 1_000L,
        )
        assertTrue(tracker.suppressPathMatch(wasOnPriorityRoad = true))
    }

    @Test
    fun reminderPlateAfterGeometryGapStaysSilent() {
        val tracker = ForkjoersveiStayTracker()
        tracker.onTick(
            onPriorityRoad = false,
            prioritySignInWindow = true,
            nowElapsedRealtimeMs = 0L,
        )
        tracker.markAlerted()

        tracker.onTick(
            onPriorityRoad = false,
            prioritySignInWindow = false,
            nowElapsedRealtimeMs = 20_000L,
        )
        assertTrue(tracker.alertedThisStay)

        tracker.onTick(
            onPriorityRoad = false,
            prioritySignInWindow = true,
            nowElapsedRealtimeMs = 40_000L,
        )
        assertTrue(tracker.suppressPathMatch(wasOnPriorityRoad = false))
        assertTrue(tracker.suppressEnter())
    }

    @Test
    fun leavingTheStretchKeepsStayDuringGrace() {
        val tracker = ForkjoersveiStayTracker()
        tracker.onTick(
            onPriorityRoad = true,
            prioritySignInWindow = false,
            nowElapsedRealtimeMs = 0L,
        )
        tracker.markAlerted()

        tracker.onTick(
            onPriorityRoad = false,
            prioritySignInWindow = false,
            nowElapsedRealtimeMs = 1_000L,
        )
        assertTrue(tracker.alertedThisStay)
        assertTrue(tracker.suppressPathMatch(wasOnPriorityRoad = false))
        assertTrue(tracker.suppressEnter())
    }

    @Test
    fun leavingTheStretchAllowsTheNextPriorityRoadAfterGrace() {
        val tracker = ForkjoersveiStayTracker()
        tracker.onTick(
            onPriorityRoad = true,
            prioritySignInWindow = false,
            nowElapsedRealtimeMs = 0L,
        )
        tracker.markAlerted()

        tracker.onTick(
            onPriorityRoad = false,
            prioritySignInWindow = false,
            nowElapsedRealtimeMs = ForkjoersveiStayTracker.GRACE_AFTER_LEAVE_MS,
        )
        assertFalse(tracker.alertedThisStay)
        assertFalse(tracker.suppressPathMatch(wasOnPriorityRoad = false))
        assertFalse(tracker.suppressEnter())
    }

    @Test
    fun stayIsKeptWhileEntranceSignRemainsInWindow() {
        val tracker = ForkjoersveiStayTracker()
        tracker.onTick(
            onPriorityRoad = false,
            prioritySignInWindow = true,
            nowElapsedRealtimeMs = 0L,
        )
        tracker.markAlerted()
        tracker.onTick(
            onPriorityRoad = false,
            prioritySignInWindow = true,
            nowElapsedRealtimeMs = ForkjoersveiStayTracker.GRACE_AFTER_LEAVE_MS + 5_000L,
        )
        assertTrue(tracker.alertedThisStay)
        assertTrue(tracker.suppressPathMatch(wasOnPriorityRoad = false))
        assertTrue(tracker.suppressEnter())
    }
}
