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
    fun leavingAfterGraceRequestsSluttAlert() {
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
        assertFalse(tracker.consumeLeaveAlert())

        tracker.onTick(
            onPriorityRoad = false,
            prioritySignInWindow = false,
            sluttInWindow = false,
            nowElapsedRealtimeMs = EveryOtherSignStayTracker.GRACE_AFTER_LEAVE_MS,
        )
        assertTrue(tracker.consumeLeaveAlert())
        assertFalse(tracker.consumeLeaveAlert())
    }

    @Test
    fun returningWithinGraceDoesNotFireSlutt() {
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
        assertFalse(tracker.consumeLeaveAlert())
        assertFalse(tracker.shouldAlertSign(102L))
    }

    @Test
    fun sluttPlateEndsTheStayWithoutADelayedLeave() {
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
        assertFalse(tracker.consumeLeaveAlert())
        tracker.onTick(
            onPriorityRoad = false,
            prioritySignInWindow = false,
            sluttInWindow = false,
            nowElapsedRealtimeMs = EveryOtherSignStayTracker.GRACE_AFTER_LEAVE_MS + 2_000L,
        )
        assertFalse(tracker.consumeLeaveAlert())
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
}
