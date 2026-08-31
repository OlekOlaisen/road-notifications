package no.roadnotifications.notification

data class AlertSelectionResult(
    val selected: List<AlertCandidate>,
    val alreadyAlertedThisPass: List<AlertCandidate>,
    val heldForLookahead: List<AlertCandidate>,
)

object AlertSelector {
    fun select(
        passTracker: AlertPassTracker,
        enabledCandidates: List<AlertCandidate>,
        matchingObjektIds: Set<Long>,
        higherImportanceApproaching: Boolean,
    ): AlertSelectionResult {
        passTracker.prepareTick(matchingObjektIds)
        val passingOncePerPass = enabledCandidates.filter { candidate ->
            passTracker.shouldNotify(candidate.vegObjekt.id)
        }
        val alreadyAlertedThisPass = enabledCandidates.filter { candidate ->
            candidate !in passingOncePerPass
        }
        val selected = AlertPriority.selectToNotify(
            passingOncePerPass = passingOncePerPass,
            higherImportanceApproaching = higherImportanceApproaching,
        )
        val heldForLookahead = passingOncePerPass.filter { candidate ->
            candidate !in selected
        }
        passingOncePerPass.forEach { candidate ->
            if (candidate !in heldForLookahead) {
                passTracker.remember(candidate.vegObjekt.id)
            }
        }
        return AlertSelectionResult(
            selected = selected,
            alreadyAlertedThisPass = alreadyAlertedThisPass,
            heldForLookahead = heldForLookahead,
        )
    }
}
