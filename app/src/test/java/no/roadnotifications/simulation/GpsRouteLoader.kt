package no.roadnotifications.simulation

import no.roadnotifications.location.GeoMath
import no.roadnotifications.location.GpsFix
import no.roadnotifications.location.RoadLatLon

data class GpsRoute(
    val name: String,
    val fallbackSpeedKmh: Float,
    val osrmDistanceMeters: Float,
    val coordinates: List<RoadLatLon>,
    val recordedFixes: List<GpsFix> = emptyList(),
)

object GpsRouteLoader {
    fun load(resourcePath: String): GpsRoute {
        val stream = checkNotNull(javaClass.classLoader?.getResourceAsStream(resourcePath)) {
            "Mangler GPS-rute $resourcePath"
        }
        var name = resourcePath
        var fallbackSpeedKmh = 50f
        var osrmDistanceMeters = 0f
        var playbackRecorded = false
        val coordinates = ArrayList<RoadLatLon>()
        val recordedFixes = ArrayList<GpsFix>()
        stream.bufferedReader().use { reader ->
            reader.lineSequence().forEach { line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty()) {
                    return@forEach
                }
                if (trimmed.startsWith("# name:")) {
                    name = trimmed.removePrefix("# name:").trim()
                    return@forEach
                }
                if (trimmed.startsWith("# fallbackSpeedKmh:")) {
                    fallbackSpeedKmh = trimmed.removePrefix("# fallbackSpeedKmh:").trim().toFloat()
                    return@forEach
                }
                if (trimmed.startsWith("# osrmDistanceMeters:")) {
                    osrmDistanceMeters = trimmed.removePrefix("# osrmDistanceMeters:").trim().toFloat()
                    return@forEach
                }
                if (trimmed.startsWith("# playback:")) {
                    playbackRecorded = trimmed.removePrefix("# playback:").trim() == "recorded"
                    return@forEach
                }
                if (trimmed.startsWith("#")) {
                    return@forEach
                }
                val parts = trimmed.split(',')
                val latitude = parts[0].toDouble()
                val longitude = parts[1].toDouble()
                coordinates += RoadLatLon(
                    latitude = latitude,
                    longitude = longitude,
                )
                if (playbackRecorded && parts.size >= 6) {
                    recordedFixes += GpsFix(
                        latitude = latitude,
                        longitude = longitude,
                        elapsedRealtimeMs = parts[5].toLong(),
                        speedMetersPerSecond = parts[2].ifBlank { null }
                            ?.toFloat()
                            ?.div(3.6f),
                        headingDegrees = parts[3].ifBlank { null }?.toFloat(),
                        accuracyMeters = parts[4].ifBlank { null }?.toFloat(),
                    )
                }
            }
        }
        return GpsRoute(
            name = name,
            fallbackSpeedKmh = fallbackSpeedKmh,
            osrmDistanceMeters = osrmDistanceMeters,
            coordinates = coordinates,
            recordedFixes = recordedFixes,
        )
    }

    fun sample(
        route: GpsRoute,
        intervalMs: Long = 1_000L,
    ): List<GpsFix> {
        if (route.recordedFixes.isNotEmpty()) {
            return route.recordedFixes
        }
        if (route.coordinates.size < 2) {
            return emptyList()
        }
        val vertices = ArrayList<SampleVertex>()
        var cumulativeMeters = 0f
        val first = route.coordinates.first()
        val firstHeading = GeoMath.bearingDegrees(
            first.latitude,
            first.longitude,
            route.coordinates[1].latitude,
            route.coordinates[1].longitude,
        )
        vertices += SampleVertex(
            latitude = first.latitude,
            longitude = first.longitude,
            headingDegrees = firstHeading,
            cumulativeMeters = 0f,
        )
        for (index in 0 until route.coordinates.lastIndex) {
            val start = route.coordinates[index]
            val end = route.coordinates[index + 1]
            val segmentMeters = GeoMath.distanceMeters(
                start.latitude,
                start.longitude,
                end.latitude,
                end.longitude,
            )
            if (segmentMeters < 0.5f) {
                continue
            }
            cumulativeMeters += segmentMeters
            vertices += SampleVertex(
                latitude = end.latitude,
                longitude = end.longitude,
                headingDegrees = GeoMath.bearingDegrees(
                    start.latitude,
                    start.longitude,
                    end.latitude,
                    end.longitude,
                ),
                cumulativeMeters = cumulativeMeters,
            )
        }
        val speedMetersPerSecond = route.fallbackSpeedKmh / 3.6f
        val stepMeters = speedMetersPerSecond * (intervalMs / 1000f)
        val ticks = ArrayList<GpsFix>()
        var distanceMeters = 0f
        var elapsedMs = 0L
        var vertexIndex = 1
        while (distanceMeters <= cumulativeMeters) {
            while (
                vertexIndex < vertices.lastIndex &&
                vertices[vertexIndex].cumulativeMeters < distanceMeters
            ) {
                vertexIndex += 1
            }
            val end = vertices[vertexIndex]
            val start = vertices[vertexIndex - 1]
            val spanMeters = (end.cumulativeMeters - start.cumulativeMeters).coerceAtLeast(0.001f)
            val fraction = ((distanceMeters - start.cumulativeMeters) / spanMeters).coerceIn(0f, 1f)
            ticks += GpsFix(
                latitude = start.latitude + (end.latitude - start.latitude) * fraction,
                longitude = start.longitude + (end.longitude - start.longitude) * fraction,
                elapsedRealtimeMs = elapsedMs,
                speedMetersPerSecond = speedMetersPerSecond,
                headingDegrees = end.headingDegrees,
                accuracyMeters = 8f,
            )
            distanceMeters += stepMeters
            elapsedMs += intervalMs
        }
        return ticks
    }

    private data class SampleVertex(
        val latitude: Double,
        val longitude: Double,
        val headingDegrees: Float,
        val cumulativeMeters: Float,
    )
}
