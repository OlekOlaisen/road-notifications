package no.roadnotifications.location

import org.junit.Assert.assertEquals
import org.junit.Test

class GeoMathTest {
    @Test
    fun oneThousandthDegreeLatitudeIsAbout111Meters() {
        val meters = GeoMath.distanceMeters(60.0, 10.0, 60.001, 10.0)
        assertEquals(111.2f, meters, 1.5f)
    }

    @Test
    fun northboundBearingIsZero() {
        val bearing = GeoMath.bearingDegrees(60.0, 10.0, 60.001, 10.0)
        assertEquals(0f, bearing, 1f)
    }
}
