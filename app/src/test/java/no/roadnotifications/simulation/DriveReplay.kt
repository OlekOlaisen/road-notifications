package no.roadnotifications.simulation

import java.util.Locale
import kotlinx.coroutines.runBlocking
import no.roadnotifications.alert.AlertTickProcessor
import no.roadnotifications.alert.PathSkip
import no.roadnotifications.data.VegObjektEntity
import no.roadnotifications.data.VegObjektStore
import no.roadnotifications.data.VegObjektType
import no.roadnotifications.location.GpsFix
import no.roadnotifications.notification.AlertCandidate
import no.roadnotifications.notification.AlertPassTracker
import no.roadnotifications.notification.AlertSelector

data class PlayedAlert(
    val elapsedMs: Long,
    val distanceAlongRouteMeters: Float,
    val candidate: AlertCandidate,
)

data class DriveReplayReport(
    val routeName: String,
    val tickCount: Int,
    val durationMs: Long,
    val distanceMeters: Float,
    val averageSpeedKmh: Float,
    val played: List<PlayedAlert>,
    val droppedByQueue: List<AlertCandidate>,
    val alreadyAlerted: List<AlertCandidate>,
    val heldForLookahead: List<AlertCandidate>,
    val alongRoadByType: Map<String, Int>,
    val playedByType: Map<String, Int>,
    val warningPlatesAlongRoad: List<VegObjektEntity>,
    val warningPlatesPlayedIds: Set<Long>,
    val firstSkipById: Map<Long, PathSkip>,
)

object DriveReplay {
    private const val ALONG_ROAD_CROSS_TRACK_METERS = 35f
    private const val ALONG_ROAD_MAX_ALONG_TRACK_METERS = 150f

    fun replay(
        routeName: String,
        ticks: List<GpsFix>,
        store: VegObjektStore,
    ): DriveReplayReport {
        val processor = AlertTickProcessor()
        val passTracker = AlertPassTracker()
        val playQueue = AlertPlayQueue()
        val played = ArrayList<PlayedAlert>()
        val droppedByQueue = ArrayList<AlertCandidate>()
        val alreadyAlerted = ArrayList<AlertCandidate>()
        val heldForLookahead = ArrayList<AlertCandidate>()
        val alongRoad = LinkedHashMap<Long, VegObjektEntity>()
        val firstSkipById = LinkedHashMap<Long, PathSkip>()
        var previous: GpsFix? = null
        var distanceAlongRouteMeters = 0f
        runBlocking {
            for (tick in ticks) {
                val lastFix = previous
                if (lastFix != null) {
                    distanceAlongRouteMeters += no.roadnotifications.location.GeoMath.distanceMeters(
                        lastFix.latitude,
                        lastFix.longitude,
                        tick.latitude,
                        tick.longitude,
                    )
                }
                val result = processor.process(
                    store = store,
                    current = tick,
                    previous = previous,
                    travelHeadingOverrideDegrees = tick.headingDegrees,
                )
                for ((vegObjekt, offset) in result.nearbyPathOffsets) {
                    val onThisRoad = offset.crossTrackMeters <= ALONG_ROAD_CROSS_TRACK_METERS &&
                        offset.alongTrackMeters >= -40f &&
                        offset.alongTrackMeters <= ALONG_ROAD_MAX_ALONG_TRACK_METERS
                    if (onThisRoad) {
                        alongRoad.putIfAbsent(vegObjekt.id, vegObjekt)
                    }
                }
                for (skip in result.pathSkips) {
                    firstSkipById.putIfAbsent(skip.vegObjekt.id, skip)
                }
                val selection = AlertSelector.select(
                    passTracker = passTracker,
                    enabledCandidates = result.candidates,
                    matchingObjektIds = result.matchingObjektIds,
                    higherImportanceApproaching = result.higherImportanceApproaching,
                )
                alreadyAlerted += selection.alreadyAlertedThisPass
                heldForLookahead += selection.heldForLookahead
                val ingest = playQueue.ingest(
                    nowElapsedMs = tick.elapsedRealtimeMs,
                    incoming = selection.selected,
                )
                processor.acknowledgeNotifications(selection.selected)
                droppedByQueue += ingest.dropped
                for (alert in ingest.played) {
                    played += PlayedAlert(
                        elapsedMs = tick.elapsedRealtimeMs,
                        distanceAlongRouteMeters = distanceAlongRouteMeters,
                        candidate = alert,
                    )
                }
                previous = tick
            }
        }
        val durationMs = ticks.lastOrNull()?.elapsedRealtimeMs ?: 0L
        val averageSpeedKmh = if (durationMs <= 0L) {
            0f
        } else {
            (distanceAlongRouteMeters / (durationMs / 1000f)) * 3.6f
        }
        val warningPlatesAlongRoad = alongRoad.values.filter { vegObjekt ->
            vegObjekt.type == VegObjektType.FARLIG_SVING.name ||
                vegObjekt.type == VegObjektType.FARLIG_VEGKRYSS.name
        }
        return DriveReplayReport(
            routeName = routeName,
            tickCount = ticks.size,
            durationMs = durationMs,
            distanceMeters = distanceAlongRouteMeters,
            averageSpeedKmh = averageSpeedKmh,
            played = played,
            droppedByQueue = droppedByQueue.distinctBy { candidate -> candidate.vegObjekt.id },
            alreadyAlerted = alreadyAlerted.distinctBy { candidate -> candidate.vegObjekt.id },
            heldForLookahead = heldForLookahead.distinctBy { candidate -> candidate.vegObjekt.id },
            alongRoadByType = alongRoad.values.groupingBy { vegObjekt -> vegObjekt.type }.eachCount(),
            playedByType = played.groupingBy { event -> event.candidate.vegObjekt.type }.eachCount(),
            warningPlatesAlongRoad = warningPlatesAlongRoad,
            warningPlatesPlayedIds = played
                .filter { event ->
                    event.candidate.vegObjekt.type == VegObjektType.FARLIG_SVING.name ||
                        event.candidate.vegObjekt.type == VegObjektType.FARLIG_VEGKRYSS.name
                }
                .map { event -> event.candidate.vegObjekt.id }
                .toSet(),
            firstSkipById = firstSkipById,
        )
    }

    fun format(report: DriveReplayReport): String {
        val builder = StringBuilder()
        builder.appendLine("=== ${report.routeName} ===")
        builder.appendLine(
            "GPS-punkter: ${report.tickCount}, " +
                "lengde: ${report.distanceMeters.toInt()} m, " +
                "varighet: ${report.durationMs / 1000} s, " +
                "snittfart: ${"%.1f".format(Locale.US, report.averageSpeedKmh)} km/t",
        )
        builder.appendLine()
        builder.appendLine("Varslet (spilt, med 3 s kø):")
        if (report.played.isEmpty()) {
            builder.appendLine("  (ingen)")
        } else {
            for (event in report.played) {
                val objekt = event.candidate.vegObjekt
                val along = event.candidate.alongTrackMeters?.toInt()?.let { meters -> "${meters} m" }
                    ?: "-"
                builder.appendLine(
                    "  t=${event.elapsedMs / 1000}s km=${"%.2f".format(Locale.US, event.distanceAlongRouteMeters / 1000f)} " +
                        "${objekt.type}:${objekt.verdi ?: "-"}#${objekt.id} avstand=$along",
                )
            }
        }
        builder.appendLine()
        builder.appendLine("Telling langs veien vs spilt:")
        val types = (report.alongRoadByType.keys + report.playedByType.keys).sorted()
        for (type in types) {
            val along = report.alongRoadByType[type] ?: 0
            val playedCount = report.playedByType[type] ?: 0
            builder.appendLine("  $type: langs veien=$along spilt=$playedCount")
        }
        builder.appendLine()
        builder.appendLine("Farlig sving / farlig vegkryss langs veien:")
        if (report.warningPlatesAlongRoad.isEmpty()) {
            builder.appendLine("  (ingen)")
        } else {
            for (vegObjekt in report.warningPlatesAlongRoad) {
                val status = if (vegObjekt.id in report.warningPlatesPlayedIds) {
                    "VARSLET"
                } else {
                    val skip = report.firstSkipById[vegObjekt.id]
                    "IKKE VARSLET (${skip?.reason ?: "ingen match / annen filter"})"
                }
                builder.appendLine(
                    "  ${vegObjekt.type}:${vegObjekt.verdi ?: "-"}#${vegObjekt.id} $status",
                )
            }
        }
        builder.appendLine()
        builder.appendLine("Kø-dropp (maks 2 ventende): ${report.droppedByQueue.size}")
        for (candidate in report.droppedByQueue) {
            builder.appendLine("  ${formatCandidate(candidate)}")
        }
        builder.appendLine("Holdt av lookahead: ${report.heldForLookahead.size}")
        for (candidate in report.heldForLookahead) {
            builder.appendLine("  ${formatCandidate(candidate)}")
        }
        return builder.toString()
    }

    private fun formatCandidate(candidate: AlertCandidate): String {
        val objekt = candidate.vegObjekt
        return "${objekt.type}:${objekt.verdi ?: "-"}#${objekt.id}"
    }
}
