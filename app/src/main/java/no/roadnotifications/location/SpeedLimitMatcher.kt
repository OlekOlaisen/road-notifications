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
    const val MAX_SEGMENT_HEADING_DELTA_DEGREES = 35f
    const val HYSTERESIS_METERS = 8f

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
        val closest = aligned.minWith(
            compareBy(
                { candidate -> candidate.distanceMeters },
                { candidate -> candidate.headingDeltaDegrees },
            ),
        )
        val previousSpeed = previousVerdi?.trim().orEmpty()
        if (previousSpeed.isEmpty()) {
            return closest
        }
        val previousBest = aligned
            .filter { candidate ->
                candidate.vegObjekt.verdi?.trim() == previousSpeed
            }
            .minWithOrNull(
                compareBy(
                    { candidate -> candidate.distanceMeters },
                    { candidate -> candidate.headingDeltaDegrees },
                ),
            ) ?: return closest
        val stillOnPreviousRoad =
            previousBest.distanceMeters <= closest.distanceMeters + HYSTERESIS_METERS
        return if (stillOnPreviousRoad) {
            previousBest
        } else {
            closest
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
}
