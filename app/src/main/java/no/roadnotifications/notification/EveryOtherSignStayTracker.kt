package no.roadnotifications.notification

/**
 * One 206 alert per stay: the entrance plate, or stretch enter if joining
 * from a side road. Reminder plates along the through road stay silent.
 * After the stretch has been left for [GRACE_AFTER_LEAVE_MS],
 * [consumeLeaveAlert] is true once so slutt can fire even when the 208
 * plate was missed.
 *
 * NVDB 596 geometry often has gaps between reminder plates. Without a grace
 * period those gaps look like "left the road".
 */
class EveryOtherSignStayTracker {
    var pendingLeaveAlert: Boolean = false
        private set

    var stayActive: Boolean = false
        private set
    private var alertedThisStay: Boolean = false
    private var lastCountedSignId: Long? = null
    private var lastCountedWasAlert: Boolean = false
    private var lastActiveElapsedRealtimeMs: Long = Long.MIN_VALUE / 2

    fun onTick(
        onPriorityRoad: Boolean,
        prioritySignInWindow: Boolean,
        sluttInWindow: Boolean,
        nowElapsedRealtimeMs: Long,
    ) {
        if (sluttInWindow) {
            lastActiveElapsedRealtimeMs = nowElapsedRealtimeMs
            pendingLeaveAlert = false
            resetStay()
            return
        }
        if (onPriorityRoad || prioritySignInWindow) {
            stayActive = true
            lastActiveElapsedRealtimeMs = nowElapsedRealtimeMs
            pendingLeaveAlert = false
            return
        }
        if (!stayActive) {
            return
        }
        val inactiveForMs = nowElapsedRealtimeMs - lastActiveElapsedRealtimeMs
        if (inactiveForMs >= GRACE_AFTER_LEAVE_MS) {
            pendingLeaveAlert = true
            resetStay()
        }
    }

    fun consumeLeaveAlert(): Boolean {
        if (!pendingLeaveAlert) {
            return false
        }
        pendingLeaveAlert = false
        return true
    }

    fun shouldAlertSign(signId: Long): Boolean {
        stayActive = true
        if (signId == lastCountedSignId) {
            return lastCountedWasAlert
        }
        lastCountedSignId = signId
        lastCountedWasAlert = !alertedThisStay
        alertedThisStay = true
        return lastCountedWasAlert
    }

    private fun resetStay() {
        stayActive = false
        alertedThisStay = false
        lastCountedSignId = null
        lastCountedWasAlert = false
    }

    companion object {
        const val GRACE_AFTER_LEAVE_MS = 90_000L
        const val SYNTHETIC_SLUTT_ID = -208L
    }
}
