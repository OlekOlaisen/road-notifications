package no.roadnotifications.notification

import no.roadnotifications.data.VegObjektType
import no.roadnotifications.location.LocationDistance

/**
 * 202/204 on a joining side road sit in the travel corridor of the through
 * road. They do not apply while following a 206 stretch. After leaving,
 * they apply again once the plate faces the new heading (turning onto
 * that side road).
 */
object PriorityRoadGiveWay {
    fun isGiveWayType(objektType: String): Boolean {
        return objektType == VegObjektType.STOPP.name ||
            objektType == VegObjektType.VIKEPLIKT.name
    }

    fun shouldSuppress(
        objektType: String,
        onPriorityRoad: Boolean,
        stayActive: Boolean,
        travelHeadingDegrees: Float?,
        retning: String?,
        vegRetningGrader: Float?,
    ): Boolean {
        if (!isGiveWayType(objektType)) {
            return false
        }
        if (onPriorityRoad) {
            return true
        }
        if (!stayActive) {
            return false
        }
        if (facesTravelDirection(
                travelHeadingDegrees = travelHeadingDegrees,
                retning = retning,
                vegRetningGrader = vegRetningGrader,
            )
        ) {
            return false
        }
        return true
    }

    private fun facesTravelDirection(
        travelHeadingDegrees: Float?,
        retning: String?,
        vegRetningGrader: Float?,
    ): Boolean {
        if (travelHeadingDegrees == null) {
            return false
        }
        if (retning.isNullOrBlank() || vegRetningGrader == null) {
            return false
        }
        return LocationDistance.matchesLokRetning(
            travelHeadingDegrees = travelHeadingDegrees,
            retning = retning,
            vegRetningGrader = vegRetningGrader,
        )
    }
}
