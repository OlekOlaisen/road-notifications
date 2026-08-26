package no.roadnotifications.location

import android.content.Context
import android.location.Location
import android.util.Log
import kotlin.math.abs
import kotlin.math.cos

data class RoadMatch(
    val edgeId: Int,
    val name: String?,
    val latitude: Double,
    val longitude: Double,
    val travelHeadingDegrees: Float,
    val goingForward: Boolean,
    val segmentIndex: Int,
    val segmentFraction: Float,
    val distanceFromGpsMeters: Float,
    val elapsedRealtimeNanos: Long,
    val points: List<RoadLatLon>,
    val forwardAccess: Boolean,
    val backwardAccess: Boolean,
) {
    fun toSnappedLocation(source: Location): Location {
        val snapped = Location(source)
        snapped.latitude = latitude
        snapped.longitude = longitude
        snapped.bearing = travelHeadingDegrees
        return snapped
    }
}

/**
 * Snaps GPS onto the offline GraphHopper-derived road graph.
 *
 * Poor GPS (tunnels, bridges) keeps the last road and advances along it
 * instead of jumping to a parallel or overlying road.
 */
class RoadMatcher(private val roadGraphStore: RoadGraphStore) {
    private var lastMatch: RoadMatch? = null

    @Synchronized
    fun match(location: Location, previousLocation: Location?): RoadMatch? {
        return try {
            matchOrNull(location, previousLocation)
        } catch (error: Exception) {
            Log.e(TAG, "Veinett-matching feilet", error)
            lastMatch
        }
    }

    private fun matchOrNull(location: Location, previousLocation: Location?): RoadMatch? {
        val gpsAccuracyMeters = if (location.hasAccuracy()) location.accuracy else 0f
        val previousMatch = lastMatch
        if (previousMatch != null && gpsAccuracyMeters > LocationDistance.MAX_GPS_ACCURACY_METERS) {
            val advanced = advanceAlongLastMatch(previousMatch, location)
            lastMatch = advanced
            return advanced
        }
        val travelHeadingDegrees = LocationDistance.headingDegrees(location, previousLocation)
        val boundingBox = LocationDistance.boundingBoxAround(
            location.latitude,
            location.longitude,
            QUERY_RADIUS_METERS,
        )
        val candidates = roadGraphStore.nearbyEdges(
            minLat = boundingBox.minLat,
            maxLat = boundingBox.maxLat,
            minLon = boundingBox.minLon,
            maxLon = boundingBox.maxLon,
        )
        val scored = candidates.mapNotNull { edge ->
            scoreEdge(
                edge = edge,
                latitude = location.latitude,
                longitude = location.longitude,
                travelHeadingDegrees = travelHeadingDegrees,
            )
        }
        val preferredLastMatch = previousMatch?.let { match ->
            scored.find { candidate -> candidate.edgeId == match.edgeId }
        }
        val bestMatch = if (
            preferredLastMatch != null &&
            preferredLastMatch.distanceFromGpsMeters <= HYSTERESIS_METERS
        ) {
            preferredLastMatch
        } else {
            scored.minByOrNull { candidate -> candidate.score }
        }
        if (bestMatch == null || bestMatch.distanceFromGpsMeters > MAX_SNAP_METERS) {
            return previousMatch
        }
        val nextMatch = bestMatch.toRoadMatch(location.elapsedRealtimeNanos)
        if (previousMatch?.edgeId != nextMatch.edgeId) {
            Log.i(TAG, "På vei ${nextMatch.name ?: "#${nextMatch.edgeId}"}")
        }
        lastMatch = nextMatch
        return nextMatch
    }

    private fun advanceAlongLastMatch(match: RoadMatch, location: Location): RoadMatch {
        val elapsedNanos = location.elapsedRealtimeNanos - match.elapsedRealtimeNanos
        val elapsedSeconds = if (elapsedNanos > 0L) elapsedNanos / 1_000_000_000.0 else 1.0
        val speedMetersPerSecond = if (location.hasSpeed()) {
            location.speed
        } else {
            0f
        }
        val distanceMeters = (speedMetersPerSecond * elapsedSeconds).toFloat()
        if (distanceMeters < 0.5f) {
            return match.copy(elapsedRealtimeNanos = location.elapsedRealtimeNanos)
        }
        val advanced = moveAlongPolyline(
            points = match.points,
            segmentIndex = match.segmentIndex,
            segmentFraction = match.segmentFraction,
            goingForward = match.goingForward,
            distanceMeters = distanceMeters,
        )
        return match.copy(
            latitude = advanced.latitude,
            longitude = advanced.longitude,
            segmentIndex = advanced.segmentIndex,
            segmentFraction = advanced.segmentFraction,
            travelHeadingDegrees = advanced.headingDegrees,
            distanceFromGpsMeters = LocationDistance.distanceMeters(
                location.latitude,
                location.longitude,
                advanced.latitude,
                advanced.longitude,
            ),
            elapsedRealtimeNanos = location.elapsedRealtimeNanos,
        )
    }

    private fun scoreEdge(
        edge: RoadEdgeRecord,
        latitude: Double,
        longitude: Double,
        travelHeadingDegrees: Float?,
    ): ScoredEdge? {
        var bestProjection: SegmentProjection? = null
        var bestHeadingDelta = 180f
        var bestGoingForward = true
        for (index in 0 until edge.points.lastIndex) {
            val projection = projectOntoSegment(
                latitude = latitude,
                longitude = longitude,
                start = edge.points[index],
                end = edge.points[index + 1],
                segmentIndex = index,
            ) ?: continue
            val forwardHeadingDelta = travelHeadingDegrees?.let { heading ->
                LocationDistance.headingDeltaDegrees(heading, projection.segmentHeadingDegrees)
            } ?: 0f
            val reverseHeading = (projection.segmentHeadingDegrees + 180f) % 360f
            val reverseHeadingDelta = travelHeadingDegrees?.let { heading ->
                LocationDistance.headingDeltaDegrees(heading, reverseHeading)
            } ?: 0f
            val useForward = when {
                !edge.backwardAccess -> true
                !edge.forwardAccess -> false
                else -> forwardHeadingDelta <= reverseHeadingDelta
            }
            if (useForward && !edge.forwardAccess) {
                continue
            }
            if (!useForward && !edge.backwardAccess) {
                continue
            }
            val headingDelta = if (useForward) forwardHeadingDelta else reverseHeadingDelta
            if (headingDelta > MAX_HEADING_DELTA_DEGREES) {
                continue
            }
            val closerThanBest = bestProjection == null ||
                projection.distanceMeters < bestProjection.distanceMeters - 0.5f ||
                (
                    abs(projection.distanceMeters - bestProjection.distanceMeters) <= 0.5f &&
                        headingDelta < bestHeadingDelta
                    )
            if (closerThanBest) {
                bestProjection = projection
                bestHeadingDelta = headingDelta
                bestGoingForward = useForward
            }
        }
        val projection = bestProjection ?: return null
        val travelHeading = if (bestGoingForward) {
            projection.segmentHeadingDegrees
        } else {
            (projection.segmentHeadingDegrees + 180f) % 360f
        }
        return ScoredEdge(
            edge = edge,
            latitude = projection.latitude,
            longitude = projection.longitude,
            travelHeadingDegrees = travelHeading,
            goingForward = bestGoingForward,
            segmentIndex = projection.segmentIndex,
            segmentFraction = projection.fraction,
            distanceFromGpsMeters = projection.distanceMeters,
            headingDeltaDegrees = bestHeadingDelta,
        )
    }

    private fun projectOntoSegment(
        latitude: Double,
        longitude: Double,
        start: RoadLatLon,
        end: RoadLatLon,
        segmentIndex: Int,
    ): SegmentProjection? {
        val segment = FloatArray(2)
        Location.distanceBetween(
            start.latitude,
            start.longitude,
            end.latitude,
            end.longitude,
            segment,
        )
        val segmentLengthMeters = segment[0]
        val segmentHeadingDegrees = segment[1]
        if (segmentLengthMeters < 0.5f) {
            return null
        }
        val fromStart = FloatArray(2)
        Location.distanceBetween(
            start.latitude,
            start.longitude,
            latitude,
            longitude,
            fromStart,
        )
        val headingDeltaRadians = Math.toRadians(
            LocationDistance.headingDeltaDegrees(segmentHeadingDegrees, fromStart[1]).toDouble(),
        )
        val alongTrackMeters = (fromStart[0] * cos(headingDeltaRadians)).toFloat()
        val fraction = (alongTrackMeters / segmentLengthMeters).coerceIn(0f, 1f)
        val snappedLatitude = start.latitude + ((end.latitude - start.latitude) * fraction)
        val snappedLongitude = start.longitude + ((end.longitude - start.longitude) * fraction)
        return SegmentProjection(
            latitude = snappedLatitude,
            longitude = snappedLongitude,
            distanceMeters = LocationDistance.distanceMeters(
                latitude,
                longitude,
                snappedLatitude,
                snappedLongitude,
            ),
            fraction = fraction,
            segmentHeadingDegrees = segmentHeadingDegrees,
            segmentIndex = segmentIndex,
        )
    }

    private fun moveAlongPolyline(
        points: List<RoadLatLon>,
        segmentIndex: Int,
        segmentFraction: Float,
        goingForward: Boolean,
        distanceMeters: Float,
    ): AdvancedPosition {
        if (points.size < 2) {
            return AdvancedPosition(
                latitude = points.firstOrNull()?.latitude ?: 0.0,
                longitude = points.firstOrNull()?.longitude ?: 0.0,
                segmentIndex = 0,
                segmentFraction = 0f,
                headingDegrees = 0f,
            )
        }
        var remainingMeters = distanceMeters
        var index = segmentIndex.coerceIn(0, points.lastIndex - 1)
        var fraction = segmentFraction.coerceIn(0f, 1f)
        var headingDegrees = headingOfSegment(points, index, goingForward)
        while (remainingMeters > 0f) {
            val start = points[index]
            val end = points[index + 1]
            val segmentLength = LocationDistance.distanceMeters(
                start.latitude,
                start.longitude,
                end.latitude,
                end.longitude,
            )
            if (segmentLength < 0.5f) {
                if (goingForward && index < points.lastIndex - 1) {
                    index += 1
                    fraction = 0f
                    continue
                }
                if (!goingForward && index > 0) {
                    index -= 1
                    fraction = 1f
                    continue
                }
                break
            }
            headingDegrees = headingOfSegment(points, index, goingForward)
            if (goingForward) {
                val metersLeftOnSegment = (1f - fraction) * segmentLength
                if (remainingMeters < metersLeftOnSegment) {
                    fraction += remainingMeters / segmentLength
                    remainingMeters = 0f
                    break
                }
                remainingMeters -= metersLeftOnSegment
                if (index >= points.lastIndex - 1) {
                    fraction = 1f
                    break
                }
                index += 1
                fraction = 0f
            } else {
                val metersLeftOnSegment = fraction * segmentLength
                if (remainingMeters < metersLeftOnSegment) {
                    fraction -= remainingMeters / segmentLength
                    remainingMeters = 0f
                    break
                }
                remainingMeters -= metersLeftOnSegment
                if (index <= 0) {
                    fraction = 0f
                    break
                }
                index -= 1
                fraction = 1f
            }
        }
        val start = points[index]
        val end = points[index + 1]
        return AdvancedPosition(
            latitude = start.latitude + ((end.latitude - start.latitude) * fraction),
            longitude = start.longitude + ((end.longitude - start.longitude) * fraction),
            segmentIndex = index,
            segmentFraction = fraction,
            headingDegrees = headingDegrees,
        )
    }

    private fun headingOfSegment(
        points: List<RoadLatLon>,
        segmentIndex: Int,
        goingForward: Boolean,
    ): Float {
        val start = points[segmentIndex]
        val end = points[segmentIndex + 1]
        val result = FloatArray(2)
        Location.distanceBetween(
            start.latitude,
            start.longitude,
            end.latitude,
            end.longitude,
            result,
        )
        val forwardHeading = result[1]
        return if (goingForward) {
            forwardHeading
        } else {
            (forwardHeading + 180f) % 360f
        }
    }

    @Synchronized
    fun close() {
        roadGraphStore.close()
        lastMatch = null
    }

    private data class SegmentProjection(
        val latitude: Double,
        val longitude: Double,
        val distanceMeters: Float,
        val fraction: Float,
        val segmentHeadingDegrees: Float,
        val segmentIndex: Int,
    )

    private data class ScoredEdge(
        val edge: RoadEdgeRecord,
        val latitude: Double,
        val longitude: Double,
        val travelHeadingDegrees: Float,
        val goingForward: Boolean,
        val segmentIndex: Int,
        val segmentFraction: Float,
        val distanceFromGpsMeters: Float,
        val headingDeltaDegrees: Float,
    ) {
        val edgeId: Int
            get() = edge.id

        val score: Float
            get() = distanceFromGpsMeters + (headingDeltaDegrees * HEADING_SCORE_METERS_PER_DEGREE)

        fun toRoadMatch(elapsedRealtimeNanos: Long): RoadMatch {
            return RoadMatch(
                edgeId = edge.id,
                name = edge.name,
                latitude = latitude,
                longitude = longitude,
                travelHeadingDegrees = travelHeadingDegrees,
                goingForward = goingForward,
                segmentIndex = segmentIndex,
                segmentFraction = segmentFraction,
                distanceFromGpsMeters = distanceFromGpsMeters,
                elapsedRealtimeNanos = elapsedRealtimeNanos,
                points = edge.points,
                forwardAccess = edge.forwardAccess,
                backwardAccess = edge.backwardAccess,
            )
        }
    }

    private data class AdvancedPosition(
        val latitude: Double,
        val longitude: Double,
        val segmentIndex: Int,
        val segmentFraction: Float,
        val headingDegrees: Float,
    )

    companion object {
        private const val TAG = "RoadMatcher"
        private const val QUERY_RADIUS_METERS = 40.0
        private const val MAX_SNAP_METERS = 28f
        private const val HYSTERESIS_METERS = 18f
        private const val MAX_HEADING_DELTA_DEGREES = 50f
        private const val HEADING_SCORE_METERS_PER_DEGREE = 0.35f

        fun open(context: Context): RoadMatcher? {
            val store = RoadGraphStore.open(context) ?: return null
            Log.i(TAG, "Offline veinett lastet")
            return RoadMatcher(store)
        }
    }
}
