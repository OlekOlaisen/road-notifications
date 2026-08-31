package no.roadnotifications.simulation

import java.io.File
import java.sql.DriverManager
import no.roadnotifications.data.VegObjektType
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test

class DriveReplayTest {
    @Test
    fun replaysOsloCityAndE6RuralRoutesAgainstVegdata() {
        val databaseFile = vegdataFile()
        Assume.assumeTrue(
            "vegdata.db mangler — kjør import før GPS-replay",
            databaseFile != null && databaseFile.exists(),
        )
        val jdbcUrl = "jdbc:sqlite:${databaseFile!!.absolutePath}"
        DriverManager.getConnection(jdbcUrl).use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("PRAGMA query_only = ON")
                statement.execute("PRAGMA cache_size = -200000")
                statement.execute("PRAGMA mmap_size = 268435456")
            }
            val store = SqliteVegObjektStore(connection)
            val reports = listOf(
                replayResource("gps/oslo_ring2.txt", store),
                replayResource("gps/oslo_ostby_ulstrud.txt", store),
                replayResource("gps/e6_jessheim_grua.txt", store),
                replayResource("gps/rv7_hol_curves.txt", store),
            )
            val reportText = reports.joinToString("\n") { report ->
                DriveReplay.format(report)
            }
            val outputFile = File("build/reports/gps-replay.txt")
            outputFile.parentFile?.mkdirs()
            outputFile.writeText(reportText)
            println(reportText)
            for (report in reports) {
                assertTrue(report.routeName, report.tickCount > 50)
                assertTrue(report.routeName, report.distanceMeters > 1_000f)
                val syntheticSluttForkjoersvei = report.played.filter { event ->
                    event.candidate.vegObjekt.type == VegObjektType.SLUTT_FORKJOERSVEI.name &&
                        event.candidate.vegObjekt.id < 0L
                }
                assertTrue(
                    "${report.routeName} skal ikke varsle syntetisk 208: $syntheticSluttForkjoersvei",
                    syntheticSluttForkjoersvei.isEmpty(),
                )
            }
        }
    }

    private fun replayResource(
        resourcePath: String,
        store: SqliteVegObjektStore,
    ): DriveReplayReport {
        val route = GpsRouteLoader.load(resourcePath)
        val ticks = GpsRouteLoader.sample(route)
        return DriveReplay.replay(
            routeName = route.name,
            ticks = ticks,
            store = store,
        )
    }

    private fun vegdataFile(): File? {
        val candidates = listOf(
            File("src/main/assets/vegdata.db"),
            File("app/src/main/assets/vegdata.db"),
        )
        return candidates.firstOrNull { file -> file.exists() }
    }
}
