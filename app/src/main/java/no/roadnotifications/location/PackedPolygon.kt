package no.roadnotifications.location

import java.nio.ByteBuffer
import java.nio.ByteOrder

object PackedPolygon {
    fun unpackRings(blob: ByteArray?): List<List<RoadLatLon>> {
        if (blob == null || blob.size < 8) {
            return emptyList()
        }
        val buffer = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN)
        val ringCount = buffer.int
        if (ringCount <= 0) {
            return emptyList()
        }
        val rings = ArrayList<List<RoadLatLon>>(ringCount)
        repeat(ringCount) {
            if (buffer.remaining() < 4) {
                return rings
            }
            val pointCount = buffer.int
            if (pointCount < 3 || buffer.remaining() < pointCount * 8) {
                return rings
            }
            val ring = ArrayList<RoadLatLon>(pointCount)
            repeat(pointCount) {
                val latitude = buffer.int / 1_000_000.0
                val longitude = buffer.int / 1_000_000.0
                ring += RoadLatLon(latitude = latitude, longitude = longitude)
            }
            rings += ring
        }
        return rings
    }

    fun contains(rings: List<List<RoadLatLon>>, latitude: Double, longitude: Double): Boolean {
        var inside = false
        for (ring in rings) {
            if (ringContains(ring, latitude, longitude)) {
                inside = !inside
            }
        }
        return inside
    }

    private fun ringContains(
        ring: List<RoadLatLon>,
        latitude: Double,
        longitude: Double,
    ): Boolean {
        if (ring.size < 3) {
            return false
        }
        var inside = false
        var previousIndex = ring.lastIndex
        for (index in ring.indices) {
            val current = ring[index]
            val previous = ring[previousIndex]
            val crossesLatitude =
                (current.latitude > latitude) != (previous.latitude > latitude)
            if (crossesLatitude) {
                val latitudeSpan = previous.latitude - current.latitude
                val interceptLongitude = current.longitude +
                    (previous.longitude - current.longitude) *
                    (latitude - current.latitude) / latitudeSpan
                if (longitude < interceptLongitude) {
                    inside = !inside
                }
            }
            previousIndex = index
        }
        return inside
    }
}
