package no.roadnotifications.location

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * WGS84 helpers used by matching and GPS replay. Avoids
 * [android.location.Location.distanceBetween], which is unavailable in JVM tests.
 */
object GeoMath {
    private const val EARTH_RADIUS_METERS = 6_371_000.0

    fun distanceMeters(
        fromLatitude: Double,
        fromLongitude: Double,
        toLatitude: Double,
        toLongitude: Double,
    ): Float {
        val fromLatitudeRadians = Math.toRadians(fromLatitude)
        val toLatitudeRadians = Math.toRadians(toLatitude)
        val deltaLatitudeRadians = Math.toRadians(toLatitude - fromLatitude)
        val deltaLongitudeRadians = Math.toRadians(toLongitude - fromLongitude)
        val haversine = sin(deltaLatitudeRadians / 2.0) * sin(deltaLatitudeRadians / 2.0) +
            cos(fromLatitudeRadians) * cos(toLatitudeRadians) *
            sin(deltaLongitudeRadians / 2.0) * sin(deltaLongitudeRadians / 2.0)
        val angularDistance = 2.0 * atan2(sqrt(haversine), sqrt(1.0 - haversine))
        return (EARTH_RADIUS_METERS * angularDistance).toFloat()
    }

    fun bearingDegrees(
        fromLatitude: Double,
        fromLongitude: Double,
        toLatitude: Double,
        toLongitude: Double,
    ): Float {
        val fromLatitudeRadians = Math.toRadians(fromLatitude)
        val toLatitudeRadians = Math.toRadians(toLatitude)
        val deltaLongitudeRadians = Math.toRadians(toLongitude - fromLongitude)
        val y = sin(deltaLongitudeRadians) * cos(toLatitudeRadians)
        val x = cos(fromLatitudeRadians) * sin(toLatitudeRadians) -
            sin(fromLatitudeRadians) * cos(toLatitudeRadians) * cos(deltaLongitudeRadians)
        val bearingDegrees = Math.toDegrees(atan2(y, x))
        return ((bearingDegrees + 360.0) % 360.0).toFloat()
    }
}
