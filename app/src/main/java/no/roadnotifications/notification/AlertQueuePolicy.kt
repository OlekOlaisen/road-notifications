package no.roadnotifications.notification

/**
 * At most [MAX_QUEUED] waiting alerts. Higher importance displaces lower
 * when the cap is exceeded; same importance keeps [AlertPriority.messageOrder].
 */
object AlertQueuePolicy {
    const val MAX_QUEUED = 2

    fun merge(
        queued: List<AlertCandidate>,
        incoming: List<AlertCandidate>,
    ): List<AlertCandidate> {
        val byId = LinkedHashMap<Long, AlertCandidate>()
        for (alert in queued + incoming) {
            byId.putIfAbsent(alert.vegObjekt.id, alert)
        }
        return byId.values
            .sortedWith(
                compareBy(
                    { candidate -> importanceRank(candidate.vegObjekt.type) },
                    { candidate -> AlertPriority.messageOrder(candidate.vegObjekt.type) },
                ),
            )
            .take(MAX_QUEUED)
    }

    private fun importanceRank(objektType: String): Int {
        return when (AlertPriority.importance(objektType)) {
            AlertImportance.HIGH -> 0
            AlertImportance.MEDIUM -> 1
            AlertImportance.LOW -> 2
        }
    }
}
