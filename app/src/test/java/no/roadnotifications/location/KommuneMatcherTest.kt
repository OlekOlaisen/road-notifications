package no.roadnotifications.location

import java.nio.ByteBuffer
import java.nio.ByteOrder
import no.roadnotifications.data.VegObjektEntity
import no.roadnotifications.data.VegObjektType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.roundToInt

class KommuneMatcherTest {
    @Test
    fun pickCurrentFindsTheMunicipalityContainingThePoint() {
        val oslo = kommune(
            id = 1L,
            name = "Oslo",
            ring = square(minLat = 59.8, maxLat = 60.0, minLon = 10.6, maxLon = 10.9),
        )
        val lorenskog = kommune(
            id = 2L,
            name = "Lørenskog",
            ring = square(minLat = 59.9, maxLat = 60.0, minLon = 10.9, maxLon = 11.1),
        )
        val current = KommuneMatcher.pickCurrent(
            candidates = listOf(oslo, lorenskog),
            latitude = 59.91,
            longitude = 10.75,
        )
        assertEquals("Oslo", current?.verdi)
    }

    @Test
    fun pickCurrentPrefersTheSmallerContainingPolygon() {
        val large = kommune(
            id = 10L,
            name = "Stor",
            ring = square(minLat = 59.0, maxLat = 61.0, minLon = 10.0, maxLon = 12.0),
        )
        val small = kommune(
            id = 11L,
            name = "Liten",
            ring = square(minLat = 59.8, maxLat = 60.0, minLon = 10.6, maxLon = 10.9),
        )
        val current = KommuneMatcher.pickCurrent(
            candidates = listOf(large, small),
            latitude = 59.91,
            longitude = 10.75,
        )
        assertEquals("Liten", current?.verdi)
    }

    @Test
    fun pickCurrentReturnsNullOutsideAllPolygons() {
        val oslo = kommune(
            id = 1L,
            name = "Oslo",
            ring = square(minLat = 59.8, maxLat = 60.0, minLon = 10.6, maxLon = 10.9),
        )
        val current = KommuneMatcher.pickCurrent(
            candidates = listOf(oslo),
            latitude = 63.4,
            longitude = 10.4,
        )
        assertNull(current)
    }

    @Test
    fun holeInPolygonIsOutside() {
        val outer = square(minLat = 59.8, maxLat = 60.0, minLon = 10.6, maxLon = 10.9)
        val hole = square(minLat = 59.88, maxLat = 59.92, minLon = 10.72, maxLon = 10.78)
        val kommuneWithLake = kommune(
            id = 3L,
            name = "Med vann",
            rings = listOf(outer, hole),
        )
        assertNull(
            KommuneMatcher.pickCurrent(
                candidates = listOf(kommuneWithLake),
                latitude = 59.90,
                longitude = 10.75,
            ),
        )
        assertEquals(
            "Med vann",
            KommuneMatcher.pickCurrent(
                candidates = listOf(kommuneWithLake),
                latitude = 59.85,
                longitude = 10.70,
            )?.verdi,
        )
    }

    private fun kommune(
        id: Long,
        name: String,
        ring: List<RoadLatLon>? = null,
        rings: List<List<RoadLatLon>> = listOfNotNull(ring),
    ): VegObjektEntity {
        val latitudes = rings.flatten().map { point -> point.latitude }
        val longitudes = rings.flatten().map { point -> point.longitude }
        return VegObjektEntity(
            id = id,
            type = VegObjektType.KOMMUNE.name,
            verdi = name,
            lat = latitudes.average(),
            lon = longitudes.average(),
            minLat = latitudes.min(),
            maxLat = latitudes.max(),
            minLon = longitudes.min(),
            maxLon = longitudes.max(),
            points = packRings(rings),
        )
    }

    private fun square(
        minLat: Double,
        maxLat: Double,
        minLon: Double,
        maxLon: Double,
    ): List<RoadLatLon> {
        return listOf(
            RoadLatLon(minLat, minLon),
            RoadLatLon(minLat, maxLon),
            RoadLatLon(maxLat, maxLon),
            RoadLatLon(maxLat, minLon),
            RoadLatLon(minLat, minLon),
        )
    }

    private fun packRings(rings: List<List<RoadLatLon>>): ByteArray {
        val pointCount = rings.sumOf { ring -> ring.size }
        val buffer = ByteBuffer.allocate(4 + rings.size * 4 + pointCount * 8)
            .order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(rings.size)
        for (ring in rings) {
            buffer.putInt(ring.size)
            for (point in ring) {
                buffer.putInt((point.latitude * 1_000_000.0).roundToInt())
                buffer.putInt((point.longitude * 1_000_000.0).roundToInt())
            }
        }
        return buffer.array()
    }
}
