package no.roadnotifications.location

import no.roadnotifications.data.VegObjektEntity
import no.roadnotifications.data.VegObjektType
import kotlin.math.abs

object KommuneMatcher {
    fun pickCurrent(
        candidates: List<VegObjektEntity>,
        latitude: Double,
        longitude: Double,
    ): VegObjektEntity? {
        val inside = candidates.filter { vegObjekt ->
            vegObjekt.type == VegObjektType.KOMMUNE.name &&
                PackedPolygon.contains(
                    rings = PackedPolygon.unpackRings(vegObjekt.points),
                    latitude = latitude,
                    longitude = longitude,
                )
        }
        if (inside.isEmpty()) {
            return null
        }
        return inside.minBy { vegObjekt -> bboxArea(vegObjekt) }
    }

    private fun bboxArea(vegObjekt: VegObjektEntity): Double {
        val latitudeSpan = abs(vegObjekt.maxLat - vegObjekt.minLat)
        val longitudeSpan = abs(vegObjekt.maxLon - vegObjekt.minLon)
        return latitudeSpan * longitudeSpan
    }
}
