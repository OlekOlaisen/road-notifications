package no.roadnotifications.location

import java.nio.ByteBuffer
import java.nio.ByteOrder

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
        if (points.size < 2) {
            return Float.MAX_VALUE
        }
        var closestMeters = Float.MAX_VALUE
        for (index in 0 until points.lastIndex) {
            val distanceMeters = distanceToSegmentMeters(
                latitude = latitude,
                longitude = longitude,
                start = points[index],
                end = points[index + 1],
            )
            if (distanceMeters < closestMeters) {
                closestMeters = distanceMeters
            }
        }
        return closestMeters
    }

    private fun distanceToSegmentMeters(
        latitude: Double,
        longitude: Double,
        start: RoadLatLon,
        end: RoadLatLon,
    ): Float {
        val segment = FloatArray(2)
        android.location.Location.distanceBetween(
            start.latitude,
            start.longitude,
            end.latitude,
            end.longitude,
            segment,
        )
        val segmentLengthMeters = segment[0]
        if (segmentLengthMeters < 0.5f) {
            return LocationDistance.distanceMeters(
                latitude,
                longitude,
                start.latitude,
                start.longitude,
            )
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
            LocationDistance.headingDeltaDegrees(segment[1], fromStart[1]).toDouble(),
        )
        val alongTrackMeters = fromStart[0] * kotlin.math.cos(headingDeltaRadians)
        val fraction = (alongTrackMeters / segmentLengthMeters).toFloat().coerceIn(0f, 1f)
        val snappedLatitude = start.latitude + ((end.latitude - start.latitude) * fraction)
        val snappedLongitude = start.longitude + ((end.longitude - start.longitude) * fraction)
        return LocationDistance.distanceMeters(
            latitude,
            longitude,
            snappedLatitude,
            snappedLongitude,
        )
    }
}
