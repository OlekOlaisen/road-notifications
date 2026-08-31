package no.roadnotifications.location

import no.roadnotifications.data.VegObjektEntity
import no.roadnotifications.data.VegObjektType

data class AlignedSpeedLimit(
    val vegObjekt: VegObjektEntity,
    val distanceMeters: Float,
    val headingDeltaDegrees: Float,
)

/**
 * Current speed limit is the FART stretch on the road being followed,
 * not the nearest speed-limit geometry in the GPS corridor.
 *
 * Side streets at T-junctions are ignored until travel heading matches
 * their nearest segment. Alert only when that on-road value changes.
 */
object SpeedLimitMatcher {
    const val MAX_SEGMENT_HEADING_DELTA_DEGREES = 22f
    const val SWITCH_CLOSER_METERS = 15f
    const val SWITCH_MAX_HEADING_DELTA_DEGREES = 12f
    /**
     * While the current limit polyline is still this close, a parallel
     * zone with similar heading (side street, opposite verge) must not
     * steal it. Same-road changes happen when the old zone has faded
     * beyond this distance.
     */
    const val STICKY_METERS = 18f

    fun headingDeltaToSegment(
        travelHeadingDegrees: Float,
        segmentHeadingDegrees: Float,
        retning: String?,
    ): Float {
        val forwardDelta = LocationDistance.headingDeltaDegrees(
            travelHeadingDegrees,
            segmentHeadingDegrees,
        )
        val reverseHeading = (segmentHeadingDegrees + 180f) % 360f
        val reverseDelta = LocationDistance.headingDeltaDegrees(
            travelHeadingDegrees,
            reverseHeading,
        )
        return when (retning?.trim()?.uppercase()) {
            "MED" -> forwardDelta
            "MOT" -> reverseDelta
            else -> minOf(forwardDelta, reverseDelta)
        }
    }

    fun matchesSegmentHeading(
        travelHeadingDegrees: Float?,
        segmentHeadingDegrees: Float,
        retning: String?,
    ): Boolean {
        if (travelHeadingDegrees == null) {
            return true
        }
        return headingDeltaToSegment(
            travelHeadingDegrees = travelHeadingDegrees,
            segmentHeadingDegrees = segmentHeadingDegrees,
            retning = retning,
        ) <= MAX_SEGMENT_HEADING_DELTA_DEGREES
    }

    fun pickCurrent(
        aligned: List<AlignedSpeedLimit>,
        previousVerdi: String?,
    ): AlignedSpeedLimit? {
        if (aligned.isEmpty()) {
            return null
        }
        val best = aligned.minWith(scoreComparator())
        val previousSpeed = previousVerdi?.trim().orEmpty()
        if (previousSpeed.isEmpty()) {
            return best
        }
        val previousBest = aligned
            .filter { candidate ->
                candidate.vegObjekt.verdi?.trim() == previousSpeed
            }
            .minWithOrNull(scoreComparator())
        if (previousBest == null) {
            return if (isClearlyOnRoad(best)) best else null
        }
        if (!stillOnPreviousRoad(previousBest)) {
            return if (isClearlyOnRoad(best)) best else null
        }
        if (previousBest.distanceMeters <= STICKY_METERS) {
            return previousBest
        }
        val differentLimit = best.vegObjekt.verdi?.trim() != previousSpeed
        val clearlyCloser = previousBest.distanceMeters - best.distanceMeters >=
            SWITCH_CLOSER_METERS
        val headingAgrees = best.headingDeltaDegrees <= SWITCH_MAX_HEADING_DELTA_DEGREES
        return if (differentLimit && clearlyCloser && headingAgrees) {
            best
        } else {
            previousBest
        }
    }

    fun shouldAlert(currentVerdi: String?, previousVerdi: String?): Boolean {
        val normalizedCurrent = currentVerdi?.trim().orEmpty()
        if (normalizedCurrent.isEmpty()) {
            return false
        }
        return normalizedCurrent != previousVerdi?.trim().orEmpty()
    }

    fun alignedLimits(
        onStretch: List<VegObjektEntity>,
        latitude: Double,
        longitude: Double,
        travelHeadingDegrees: Float?,
    ): List<AlignedSpeedLimit> {
        return onStretch.mapNotNull { vegObjekt ->
            if (vegObjekt.type != VegObjektType.FART.name) {
                return@mapNotNull null
            }
            if (vegObjekt.verdi.isNullOrBlank()) {
                return@mapNotNull null
            }
            val points = PackedPolyline.unpack(vegObjekt.points)
            val closest = PackedPolyline.closestSegment(
                latitude = latitude,
                longitude = longitude,
                points = points,
            ) ?: return@mapNotNull null
            if (!matchesSegmentHeading(
                    travelHeadingDegrees = travelHeadingDegrees,
                    segmentHeadingDegrees = closest.segmentHeadingDegrees,
                    retning = vegObjekt.retning,
                )
            ) {
                return@mapNotNull null
            }
            val headingDeltaDegrees = if (travelHeadingDegrees == null) {
                0f
            } else {
                headingDeltaToSegment(
                    travelHeadingDegrees = travelHeadingDegrees,
                    segmentHeadingDegrees = closest.segmentHeadingDegrees,
                    retning = vegObjekt.retning,
                )
            }
            AlignedSpeedLimit(
                vegObjekt = vegObjekt,
                distanceMeters = closest.distanceMeters,
                headingDeltaDegrees = headingDeltaDegrees,
            )
        }
    }

    private fun stillOnPreviousRoad(previousBest: AlignedSpeedLimit): Boolean {
        return previousBest.headingDeltaDegrees <= MAX_SEGMENT_HEADING_DELTA_DEGREES &&
            previousBest.distanceMeters <= LocationDistance.STRETCH_ON_ROAD_METERS
    }

    private fun isClearlyOnRoad(candidate: AlignedSpeedLimit): Boolean {
        return candidate.headingDeltaDegrees <= SWITCH_MAX_HEADING_DELTA_DEGREES &&
            candidate.distanceMeters <= STICKY_METERS
    }

    private fun scoreComparator(): Comparator<AlignedSpeedLimit> {
        return compareBy { candidate ->
            candidate.headingDeltaDegrees * 2f + candidate.distanceMeters
        }
    }
}
