package no.roadnotifications.notification

/**
 * One priority-road alert per stay: the 206 at the entrance, or stretch
 * enter if joining mid-way. Further 206 plates along the same 596 are silent
 * until the stretch has been left for [GRACE_AFTER_LEAVE_MS].
 *
 * NVDB 596 geometry often has gaps between reminder plates. Without a grace
 * period those gaps look like "left the road", so every 206 would alert again.
 */
class ForkjoersveiStayTracker {
    var alertedThisStay: Boolean = false
        private set

    private var lastActiveElapsedRealtimeMs: Long = Long.MIN_VALUE / 2

    fun onTick(
        onPriorityRoad: Boolean,
        prioritySignInWindow: Boolean,
        nowElapsedRealtimeMs: Long,
    ) {
        if (onPriorityRoad || prioritySignInWindow) {
            lastActiveElapsedRealtimeMs = nowElapsedRealtimeMs
            return
        }
        if (!alertedThisStay) {
            return
        }
        val inactiveForMs = nowElapsedRealtimeMs - lastActiveElapsedRealtimeMs
        if (inactiveForMs >= GRACE_AFTER_LEAVE_MS) {
            alertedThisStay = false
        }
    }

    fun markAlerted() {
        alertedThisStay = true
    }

    fun suppressPathMatch(wasOnPriorityRoad: Boolean): Boolean {
        return wasOnPriorityRoad || alertedThisStay
    }

    fun suppressEnter(): Boolean {
        return alertedThisStay
    }

    companion object {
        const val GRACE_AFTER_LEAVE_MS = 90_000L
    }
}
