package no.roadnotifications.notification

import no.roadnotifications.data.VegObjektType
import no.roadnotifications.location.LocationDistance
import no.roadnotifications.location.TravelPathOffset

enum class AlertImportance {
    HIGH,
    MEDIUM,
    LOW,
}

/**
 * Action-required signs outrank advance warnings so a curve or junction
 * plate cannot take the single heads-up slot from stop, yield, or cameras.
 */
object AlertPriority {
    const val LOOKAHEAD_METERS = 90f
    private const val LOOKAHEAD_CROSS_TRACK_METERS = 35f
    private const val LOOKAHEAD_HEADING_DELTA_DEGREES = 32f

    fun importance(objektType: String): AlertImportance {
        return when (objektType) {
            VegObjektType.STOPP.name,
            VegObjektType.VIKEPLIKT.name,
            VegObjektType.JERNBANE.name,
            VegObjektType.FOTOBOKS.name,
            VegObjektType.STREKNINGS_ATK.name -> AlertImportance.HIGH
            VegObjektType.FART.name,
            VegObjektType.FORKJOERSVEI.name -> AlertImportance.MEDIUM
            else -> AlertImportance.LOW
        }
    }

    fun messageOrder(objektType: String): Int {
        return when (objektType) {
            VegObjektType.JERNBANE.name -> 0
            VegObjektType.STOPP.name -> 1
            VegObjektType.VIKEPLIKT.name -> 2
            VegObjektType.FOTOBOKS.name -> 3
            VegObjektType.STREKNINGS_ATK.name -> 3
            VegObjektType.FORKJOERSVEI.name -> 4
            VegObjektType.FART.name -> 5
            VegObjektType.FARLIG_SVING.name -> 6
            VegObjektType.FARLIG_VEGKRYSS.name -> 7
            VegObjektType.TUNNEL.name -> 8
            VegObjektType.SMALERE_VEG.name -> 9
            VegObjektType.BOM.name -> 10
            VegObjektType.FERJEKAI.name -> 11
            VegObjektType.VILTFARE.name -> 12
            VegObjektType.SLUTT_FORKJOERSVEI.name -> 13
            else -> 14
        }
    }

    fun iconPriority(objektType: String): Int {
        return when (objektType) {
            VegObjektType.JERNBANE.name -> 0
            VegObjektType.STOPP.name -> 1
            VegObjektType.VIKEPLIKT.name -> 2
            VegObjektType.FOTOBOKS.name -> 3
            VegObjektType.STREKNINGS_ATK.name -> 3
            VegObjektType.FORKJOERSVEI.name -> 4
            VegObjektType.FARLIG_SVING.name -> 5
            VegObjektType.FARLIG_VEGKRYSS.name -> 6
            VegObjektType.VILTFARE.name -> 7
            VegObjektType.TUNNEL.name -> 8
            VegObjektType.SLUTT_FORKJOERSVEI.name -> 9
            VegObjektType.SMALERE_VEG.name -> 10
            VegObjektType.BOM.name -> 11
            VegObjektType.FERJEKAI.name -> 12
            VegObjektType.FART.name -> 13
            else -> 14
        }
    }

    fun canSuppressLowFromLookahead(objektType: String): Boolean {
        return importance(objektType) == AlertImportance.HIGH ||
            objektType == VegObjektType.FORKJOERSVEI.name
    }

    fun isLookaheadSuppressor(
        objektType: String,
        offset: TravelPathOffset,
        retning: String? = null,
        vegRetningGrader: Float? = null,
    ): Boolean {
        if (!canSuppressLowFromLookahead(objektType)) {
            return false
        }
        if (offset.alongTrackMeters < 0f || offset.alongTrackMeters > LOOKAHEAD_METERS) {
            return false
        }
        if (offset.crossTrackMeters > LOOKAHEAD_CROSS_TRACK_METERS) {
            return false
        }
        if (offset.headingDeltaDegrees > LOOKAHEAD_HEADING_DELTA_DEGREES) {
            return false
        }
        return LocationDistance.matchesLokRetning(
            travelHeadingDegrees = offset.travelHeadingDegrees,
            retning = retning,
            vegRetningGrader = vegRetningGrader,
        )
    }

    fun shouldSuppressLow(
        candidatesInWindow: List<AlertCandidate>,
        higherImportanceApproaching: Boolean,
    ): Boolean {
        if (higherImportanceApproaching) {
            return true
        }
        return candidatesInWindow.any { candidate ->
            importance(candidate.vegObjekt.type) != AlertImportance.LOW
        }
    }

    fun selectToNotify(
        passingOncePerPass: List<AlertCandidate>,
        candidatesInWindow: List<AlertCandidate>,
        higherImportanceApproaching: Boolean,
    ): List<AlertCandidate> {
        if (!shouldSuppressLow(candidatesInWindow, higherImportanceApproaching)) {
            return passingOncePerPass
        }
        return passingOncePerPass.filter { candidate ->
            importance(candidate.vegObjekt.type) != AlertImportance.LOW
        }
    }
}
