package no.roadnotifications.notification

/**
 * One 206 alert per stay: the entrance plate, or stretch enter if joining
 * from a side road. Reminder plates along the through road stay silent.
 *
 * Stay stays active while on the 596 or any 206 is in the travel window.
 * After [GRACE_AFTER_LEAVE_MS] off both, the stay ends so a later road
 * can alert. 208 is only a real plate — leaving does not synthesize one.
 *
 * NVDB 596 geometry often has gaps between reminder plates. Without a grace
 * period those gaps look like "left the road".
 */
class EveryOtherSignStayTracker {
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
            resetStay()
            return
        }
        if (onPriorityRoad || prioritySignInWindow) {
            stayActive = true
            lastActiveElapsedRealtimeMs = nowElapsedRealtimeMs
            return
        }
        if (!stayActive) {
            return
        }
        val inactiveForMs = nowElapsedRealtimeMs - lastActiveElapsedRealtimeMs
        if (inactiveForMs >= GRACE_AFTER_LEAVE_MS) {
            resetStay()
        }
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
    }
}
