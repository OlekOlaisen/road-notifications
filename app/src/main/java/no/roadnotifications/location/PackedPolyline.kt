package no.roadnotifications.location

import java.nio.ByteBuffer
import java.nio.ByteOrder

data class ClosestSegment(
    val distanceMeters: Float,
    val segmentHeadingDegrees: Float,
)

object PackedPolyline {
    fun unpack(blob: ByteArray?): List<RoadLatLon> {
        if (blob == null || blob.size < 12) {
            return emptyList()
        }
        val buffer = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN)
        val pointCount = buffer.int
        if (pointCount < 2 || buffer.remaining() < pointCount * 8) {
            return emptyList()
        }
        val points = ArrayList<RoadLatLon>(pointCount)
        repeat(pointCount) {
            val latitude = buffer.int / 1_000_000.0
            val longitude = buffer.int / 1_000_000.0
            points += RoadLatLon(latitude = latitude, longitude = longitude)
        }
        return points
    }

    fun minDistanceMeters(
        latitude: Double,
        longitude: Double,
        points: List<RoadLatLon>,
    ): Float {
        return closestSegment(
            latitude = latitude,
            longitude = longitude,
            points = points,
        )?.distanceMeters ?: Float.MAX_VALUE
    }

    fun closestSegment(
        latitude: Double,
        longitude: Double,
        points: List<RoadLatLon>,
    ): ClosestSegment? {
        if (points.size < 2) {
            return null
        }
        var closest: ClosestSegment? = null
        for (index in 0 until points.lastIndex) {
            val candidate = projectOntoSegment(
                latitude = latitude,
                longitude = longitude,
                start = points[index],
                end = points[index + 1],
            ) ?: continue
            val closerThanBest = closest == null ||
                candidate.distanceMeters < closest.distanceMeters
            if (closerThanBest) {
                closest = candidate
            }
        }
        return closest
    }

    private fun projectOntoSegment(
        latitude: Double,
        longitude: Double,
        start: RoadLatLon,
        end: RoadLatLon,
    ): ClosestSegment? {
        val segment = FloatArray(2)
        android.location.Location.distanceBetween(
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
        android.location.Location.distanceBetween(
            start.latitude,
            start.longitude,
            latitude,
            longitude,
            fromStart,
        )
        val headingDeltaRadians = Math.toRadians(
            LocationDistance.headingDeltaDegrees(segmentHeadingDegrees, fromStart[1]).toDouble(),
        )
        val alongTrackMeters = fromStart[0] * kotlin.math.cos(headingDeltaRadians)
        val fraction = (alongTrackMeters / segmentLengthMeters).toFloat().coerceIn(0f, 1f)
        val snappedLatitude = start.latitude + ((end.latitude - start.latitude) * fraction)
        val snappedLongitude = start.longitude + ((end.longitude - start.longitude) * fraction)
        return ClosestSegment(
            distanceMeters = LocationDistance.distanceMeters(
                latitude,
                longitude,
                snappedLatitude,
                snappedLongitude,
            ),
            segmentHeadingDegrees = segmentHeadingDegrees,
        )
    }
}
