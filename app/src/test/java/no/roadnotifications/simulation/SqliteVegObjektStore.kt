package no.roadnotifications.simulation

import java.sql.Connection
import java.sql.ResultSet
import no.roadnotifications.data.VegObjektEntity
import no.roadnotifications.data.VegObjektStore
import no.roadnotifications.location.BoundingBox

class SqliteVegObjektStore(
    private val connection: Connection,
) : VegObjektStore {
    override suspend fun nearbyAlertPoints(box: BoundingBox): List<VegObjektEntity> {
        val sql = """
            SELECT * FROM vegobjekt
            WHERE lat BETWEEN ? AND ?
              AND lon BETWEEN ? AND ?
        """.trimIndent()
        connection.prepareStatement(sql).use { statement ->
            statement.setDouble(1, box.minLat)
            statement.setDouble(2, box.maxLat)
            statement.setDouble(3, box.minLon)
            statement.setDouble(4, box.maxLon)
            statement.executeQuery().use { resultSet ->
                return readAll(resultSet)
            }
        }
    }

    override suspend fun nearbyStretchIds(box: BoundingBox): List<Long> {
        val sql = """
            SELECT DISTINCT s.objektId AS id FROM vegobjekt_seg AS s
            INNER JOIN vegobjekt_seg_rtree AS r ON r.segId = s.segId
            WHERE r.minLat <= ? AND r.maxLat >= ?
              AND r.minLon <= ? AND r.maxLon >= ?
            LIMIT 192
        """.trimIndent()
        connection.prepareStatement(sql).use { statement ->
            statement.setDouble(1, box.maxLat)
            statement.setDouble(2, box.minLat)
            statement.setDouble(3, box.maxLon)
            statement.setDouble(4, box.minLon)
            statement.executeQuery().use { resultSet ->
                val ids = ArrayList<Long>()
                while (resultSet.next()) {
                    ids += resultSet.getLong("id")
                }
                return ids
            }
        }
    }

    override suspend fun byIds(ids: List<Long>): List<VegObjektEntity> {
        if (ids.isEmpty()) {
            return emptyList()
        }
        val loaded = ArrayList<VegObjektEntity>(ids.size)
        for (chunk in ids.chunked(400)) {
            val placeholders = chunk.joinToString(",") { "?" }
            val sql = "SELECT * FROM vegobjekt WHERE id IN ($placeholders)"
            connection.prepareStatement(sql).use { statement ->
                chunk.forEachIndexed { index, id ->
                    statement.setLong(index + 1, id)
                }
                statement.executeQuery().use { resultSet ->
                    loaded += readAll(resultSet)
                }
            }
        }
        return loaded
    }

    override suspend fun kommunerContaining(
        latitude: Double,
        longitude: Double,
    ): List<VegObjektEntity> {
        val sql = """
            SELECT * FROM vegobjekt
            WHERE type = 'KOMMUNE'
              AND minLat <= ? AND maxLat >= ?
              AND minLon <= ? AND maxLon >= ?
        """.trimIndent()
        connection.prepareStatement(sql).use { statement ->
            statement.setDouble(1, latitude)
            statement.setDouble(2, latitude)
            statement.setDouble(3, longitude)
            statement.setDouble(4, longitude)
            statement.executeQuery().use { resultSet ->
                return readAll(resultSet)
            }
        }
    }

    private fun readAll(resultSet: ResultSet): List<VegObjektEntity> {
        val objects = ArrayList<VegObjektEntity>()
        while (resultSet.next()) {
            objects += readOne(resultSet)
        }
        return objects
    }

    private fun readOne(resultSet: ResultSet): VegObjektEntity {
        val vegRetningGrader = resultSet.getFloat("vegRetningGrader")
        val vegRetningMissing = resultSet.wasNull()
        return VegObjektEntity(
            id = resultSet.getLong("id"),
            type = resultSet.getString("type"),
            verdi = resultSet.getString("verdi"),
            lat = resultSet.getDouble("lat"),
            lon = resultSet.getDouble("lon"),
            minLat = resultSet.getDouble("minLat"),
            maxLat = resultSet.getDouble("maxLat"),
            minLon = resultSet.getDouble("minLon"),
            maxLon = resultSet.getDouble("maxLon"),
            retning = resultSet.getString("retning"),
            vegRetningGrader = if (vegRetningMissing) null else vegRetningGrader,
            points = resultSet.getBytes("points"),
        )
    }
}
