package no.roadnotifications.notification

/**
 * One priority-road alert per stay: the 206 at the entrance, or stretch
 * enter if joining mid-way. Further 206 plates along the same 596 are silent
 * until the stretch is left and no entrance sign remains in the match window.
 */
class ForkjoersveiStayTracker {
    var alertedThisStay: Boolean = false
        private set

    fun onTick(onPriorityRoad: Boolean, prioritySignInWindow: Boolean) {
        if (!onPriorityRoad && !prioritySignInWindow) {
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
}
