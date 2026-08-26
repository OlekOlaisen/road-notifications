package no.roadnotifications.location

import android.content.Context
import android.util.Log
import io.requery.android.database.sqlite.SQLiteDatabase
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import no.roadnotifications.data.RtreeSqlite
import no.roadnotifications.log.TripLog

data class RoadLatLon(
    val latitude: Double,
    val longitude: Double,
)

data class RoadEdgeRecord(
    val id: Int,
    val name: String?,
    val forwardAccess: Boolean,
    val backwardAccess: Boolean,
    val points: List<RoadLatLon>,
)

class RoadGraphStore private constructor(
    private val database: SQLiteDatabase,
) {
    fun nearbyEdges(
        minLat: Double,
        maxLat: Double,
        minLon: Double,
        maxLon: Double,
    ): List<RoadEdgeRecord> {
        val edges = mutableListOf<RoadEdgeRecord>()
        try {
            database.rawQuery(
                """
                SELECT e.id, e.fwd, e.bwd, e.name, e.points
                FROM road_edge_rtree AS r
                JOIN road_edge AS e ON e.id = r.id
                WHERE r.minLat <= ? AND r.maxLat >= ?
                  AND r.minLon <= ? AND r.maxLon >= ?
                LIMIT $MAX_NEARBY_EDGES
                """.trimIndent(),
                arrayOf(
                    maxLat.toString(),
                    minLat.toString(),
                    maxLon.toString(),
                    minLon.toString(),
                ),
            ).use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow("id")
                val forwardIndex = cursor.getColumnIndexOrThrow("fwd")
                val backwardIndex = cursor.getColumnIndexOrThrow("bwd")
                val nameIndex = cursor.getColumnIndexOrThrow("name")
                val pointsIndex = cursor.getColumnIndexOrThrow("points")
                while (cursor.moveToNext()) {
                    val points = unpackPoints(cursor.getBlob(pointsIndex))
                    if (points.size < 2) {
                        continue
                    }
                    edges += RoadEdgeRecord(
                        id = cursor.getInt(idIndex),
                        name = cursor.getString(nameIndex),
                        forwardAccess = cursor.getInt(forwardIndex) != 0,
                        backwardAccess = cursor.getInt(backwardIndex) != 0,
                        points = points,
                    )
                }
            }
        } catch (error: Exception) {
            Log.e(TAG, "Klarte ikke å lese veinett nær posisjonen", error)
            TripLog.append("ERROR roadgraph ${error.javaClass.simpleName}: ${error.message}")
        }
        return edges
    }

    fun close() {
        database.close()
    }

    companion object {
        private const val TAG = "RoadGraphStore"
        private const val ASSET_NAME = "roadgraph.db"
        private const val DATABASE_NAME = "roadgraph.db"
        private const val MAX_NEARBY_EDGES = 64

        fun open(context: Context): RoadGraphStore? {
            return try {
                openOrNull(context.applicationContext)
            } catch (error: Throwable) {
                Log.e(TAG, "Klarte ikke å åpne roadgraph.db", error)
                null
            }
        }

        private fun openOrNull(applicationContext: Context): RoadGraphStore? {
            if (!assetExists(applicationContext)) {
                Log.w(TAG, "roadgraph.db mangler i assets. Kjør ./gradlew :importer:run")
                return null
            }
            val destination = applicationContext.getDatabasePath(DATABASE_NAME)
            val parent = destination.parentFile
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                Log.e(TAG, "Klarte ikke å opprette ${parent.absolutePath}")
                return null
            }
            RtreeSqlite.load(applicationContext)
            val expectedLength = applicationContext.assets.openFd(ASSET_NAME).use { descriptor ->
                descriptor.length
            }
            if (!destination.exists() || destination.length() != expectedLength) {
                copyAsset(applicationContext, destination)
            }
            val database = RtreeSqlite.openReadonly(destination.path)
            return RoadGraphStore(database)
        }

        private fun assetExists(context: Context): Boolean {
            val names = context.assets.list("") ?: return false
            return names.contains(ASSET_NAME)
        }

        private fun copyAsset(context: Context, destination: File) {
            val temporary = File(destination.parentFile, "${destination.name}.tmp")
            if (temporary.exists() && !temporary.delete()) {
                throw IOException("Klarte ikke å slette ${temporary.absolutePath}")
            }
            try {
                copyUncompressedAsset(context, temporary)
            } catch (error: IOException) {
                temporary.delete()
                throw error
            }
            if (destination.exists() && !destination.delete()) {
                temporary.delete()
                throw IOException("Klarte ikke å erstatte ${destination.absolutePath}")
            }
            if (!temporary.renameTo(destination)) {
                temporary.delete()
                throw IOException("Klarte ikke å flytte roadgraph.db på plass")
            }
        }

        /**
         * openFd only works for uncompressed assets. Opening a 390 MB compressed
         * asset with assets.open() inflates the whole file into RAM and kills the app.
         */
        private fun copyUncompressedAsset(context: Context, destination: File) {
            val assetFile = context.assets.openFd(ASSET_NAME)
            assetFile.use { descriptor ->
                FileInputStream(descriptor.fileDescriptor).channel.use { inputChannel ->
                    destination.outputStream().channel.use { outputChannel ->
                        var position = descriptor.startOffset
                        var remaining = descriptor.length
                        while (remaining > 0L) {
                            val transferred = inputChannel.transferTo(
                                position,
                                remaining,
                                outputChannel,
                            )
                            if (transferred <= 0L) {
                                throw IOException("Avbrutt kopiering av roadgraph.db")
                            }
                            position += transferred
                            remaining -= transferred
                        }
                    }
                }
            }
        }

        internal fun unpackPoints(blob: ByteArray): List<RoadLatLon> {
            val buffer = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN)
            if (buffer.remaining() < 4) {
                return emptyList()
            }
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
    }
}
