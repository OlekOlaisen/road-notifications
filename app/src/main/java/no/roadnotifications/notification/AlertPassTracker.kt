package no.roadnotifications.notification

/**
 * Alert each object id once while it stays in the match window.
 * Leaving the window (no longer matching) allows the same id again,
 * for example after a U-turn back to the same plate.
 */
class AlertPassTracker {
    private val alertedThisPassIds = mutableSetOf<Long>()

    fun prepareTick(matchingObjektIds: Set<Long>) {
        alertedThisPassIds.retainAll(matchingObjektIds)
    }

    fun shouldNotify(objektId: Long): Boolean {
        return objektId !in alertedThisPassIds
    }

    fun remember(objektId: Long) {
        alertedThisPassIds.add(objektId)
    }
}
