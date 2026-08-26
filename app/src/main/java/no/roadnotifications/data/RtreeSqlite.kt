package no.roadnotifications.data

import android.content.Context
import android.util.Log
import io.requery.android.database.sqlite.RequerySQLiteOpenHelperFactory
import io.requery.android.database.sqlite.SQLiteDatabase
import no.roadnotifications.log.TripLog

/**
 * Android's system SQLite is built without R-tree. The bundled requery
 * SQLite enables it so vegdata and roadgraph spatial indexes work.
 */
object RtreeSqlite {
    private const val TAG = "RtreeSqlite"

    @Volatile
    private var loaded = false

    fun load(context: Context) {
        if (loaded) {
            return
        }
        synchronized(this) {
            if (loaded) {
                return
            }
            SQLiteDatabase.create(null).close()
            loaded = true
            val rtreeEnabled = probeRtree()
            TripLog.append(if (rtreeEnabled) "SQLITE rtree=on" else "SQLITE rtree=OFF")
            if (!rtreeEnabled) {
                Log.e(TAG, "Bundled SQLite har ikke R-tree")
            }
        }
    }

    fun openHelperFactory(): RequerySQLiteOpenHelperFactory {
        return RequerySQLiteOpenHelperFactory()
    }

    fun openReadonly(path: String): SQLiteDatabase {
        return SQLiteDatabase.openDatabase(
            path,
            null,
            SQLiteDatabase.OPEN_READONLY,
        )
    }

    private fun probeRtree(): Boolean {
        return try {
            SQLiteDatabase.create(null).use { database ->
                database.execSQL(
                    "CREATE VIRTUAL TABLE temp.rtree_probe USING rtree(id, minX, maxX, minY, maxY)",
                )
                true
            }
        } catch (error: Exception) {
            Log.e(TAG, "R-tree-probe feilet", error)
            false
        }
    }
}
