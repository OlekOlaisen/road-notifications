package no.roadnotifications.notification

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EveryOtherSignStayTrackerTest {
    @Test
    fun firstSignAlertsLaterReminderPlatesAreSilent() {
        val tracker = EveryOtherSignStayTracker()
        tracker.onTick(
            onPriorityRoad = true,
            prioritySignInWindow = true,
            sluttInWindow = false,
            nowElapsedRealtimeMs = 0L,
        )
        assertTrue(tracker.stayActive)
        assertTrue(tracker.shouldAlertSign(101L))
        assertFalse(tracker.shouldAlertSign(102L))
        assertFalse(tracker.shouldAlertSign(103L))
        assertFalse(tracker.shouldAlertSign(104L))
    }

    @Test
    fun joiningFromASideRoadAlertsOnceThenStaysSilent() {
        val tracker = EveryOtherSignStayTracker()
        tracker.onTick(
            onPriorityRoad = false,
            prioritySignInWindow = false,
            sluttInWindow = false,
            nowElapsedRealtimeMs = 0L,
        )
        assertFalse(tracker.stayActive)

        tracker.onTick(
            onPriorityRoad = true,
            prioritySignInWindow = false,
            sluttInWindow = false,
            nowElapsedRealtimeMs = 1_000L,
        )
        assertTrue(tracker.shouldAlertSign(596L))
        assertFalse(tracker.shouldAlertSign(102L))
    }

    @Test
    fun sameSignIsNotCountedTwice() {
        val tracker = EveryOtherSignStayTracker()
        tracker.onTick(
            onPriorityRoad = true,
            prioritySignInWindow = true,
            sluttInWindow = false,
            nowElapsedRealtimeMs = 0L,
        )
        assertTrue(tracker.shouldAlertSign(101L))
        assertTrue(tracker.shouldAlertSign(101L))
        assertFalse(tracker.shouldAlertSign(102L))
    }

    @Test
    fun leavingAfterGraceEndsStaySoANewRoadCanAlert() {
        val tracker = EveryOtherSignStayTracker()
        tracker.onTick(
            onPriorityRoad = true,
            prioritySignInWindow = false,
            sluttInWindow = false,
            nowElapsedRealtimeMs = 0L,
        )
        assertTrue(tracker.shouldAlertSign(101L))

        tracker.onTick(
            onPriorityRoad = false,
            prioritySignInWindow = false,
            sluttInWindow = false,
            nowElapsedRealtimeMs = 1_000L,
        )
        assertTrue(tracker.stayActive)

        tracker.onTick(
            onPriorityRoad = false,
            prioritySignInWindow = false,
            sluttInWindow = false,
            nowElapsedRealtimeMs = EveryOtherSignStayTracker.GRACE_AFTER_LEAVE_MS,
        )
        assertFalse(tracker.stayActive)
        assertTrue(tracker.shouldAlertSign(201L))
    }

    @Test
    fun returningWithinGraceDoesNotReAlert() {
        val tracker = EveryOtherSignStayTracker()
        tracker.onTick(
            onPriorityRoad = true,
            prioritySignInWindow = false,
            sluttInWindow = false,
            nowElapsedRealtimeMs = 0L,
        )
        tracker.shouldAlertSign(101L)
        tracker.onTick(
            onPriorityRoad = false,
            prioritySignInWindow = false,
            sluttInWindow = false,
            nowElapsedRealtimeMs = 20_000L,
        )
        tracker.onTick(
            onPriorityRoad = true,
            prioritySignInWindow = false,
            sluttInWindow = false,
            nowElapsedRealtimeMs = 40_000L,
        )
        assertFalse(tracker.shouldAlertSign(102L))
    }

    @Test
    fun sluttPlateEndsTheStaySoANewRoadCanAlert() {
        val tracker = EveryOtherSignStayTracker()
        tracker.onTick(
            onPriorityRoad = true,
            prioritySignInWindow = false,
            sluttInWindow = false,
            nowElapsedRealtimeMs = 0L,
        )
        tracker.shouldAlertSign(101L)
        tracker.onTick(
            onPriorityRoad = false,
            prioritySignInWindow = false,
            sluttInWindow = true,
            nowElapsedRealtimeMs = 2_000L,
        )
        assertFalse(tracker.stayActive)
        assertTrue(tracker.shouldAlertSign(201L))
    }

    @Test
    fun stayStaysActiveThroughGeometryGapsUntilGrace() {
        val tracker = EveryOtherSignStayTracker()
        tracker.onTick(
            onPriorityRoad = true,
            prioritySignInWindow = false,
            sluttInWindow = false,
            nowElapsedRealtimeMs = 0L,
        )
        assertTrue(tracker.stayActive)
        tracker.onTick(
            onPriorityRoad = false,
            prioritySignInWindow = false,
            sluttInWindow = false,
            nowElapsedRealtimeMs = 5_000L,
        )
        assertTrue(tracker.stayActive)
        tracker.onTick(
            onPriorityRoad = false,
            prioritySignInWindow = false,
            sluttInWindow = false,
            nowElapsedRealtimeMs = EveryOtherSignStayTracker.GRACE_AFTER_LEAVE_MS,
        )
        assertFalse(tracker.stayActive)
    }

    @Test
    fun aSignInTheWindowKeepsStayWithoutBeingOnTheStretch() {
        val tracker = EveryOtherSignStayTracker()
        tracker.onTick(
            onPriorityRoad = true,
            prioritySignInWindow = false,
            sluttInWindow = false,
            nowElapsedRealtimeMs = 0L,
        )
        tracker.shouldAlertSign(101L)
        tracker.onTick(
            onPriorityRoad = false,
            prioritySignInWindow = true,
            sluttInWindow = false,
            nowElapsedRealtimeMs = EveryOtherSignStayTracker.GRACE_AFTER_LEAVE_MS + 5_000L,
        )
        assertTrue(tracker.stayActive)
        assertFalse(tracker.shouldAlertSign(102L))
    }
}
