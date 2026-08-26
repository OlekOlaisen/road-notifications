package no.roadnotifications.data

import android.content.Context
import android.util.Log
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import no.roadnotifications.log.TripLog

@Database(
    entities = [VegObjektEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class VegDatabase : RoomDatabase() {
    abstract fun vegObjektDao(): VegObjektDao

    companion object {
        private const val TAG = "VegDatabase"
        private const val DATABASE_NAME = "vegdata.db"
        private const val ASSET_NAME = "vegdata.db"
        private const val PREFS_NAME = "vegdata"
        private const val PREFS_ASSET_SIZE = "asset_size"
        private const val EXPECTED_USER_VERSION = 2

        @Volatile
        private var instance: VegDatabase? = null

        fun getInstance(context: Context): VegDatabase {
            return instance ?: synchronized(this) {
                instance ?: run {
                    val appContext = context.applicationContext
                    RtreeSqlite.load(appContext)
                    copyAssetIfChanged(appContext)
                    Room.databaseBuilder(
                        appContext,
                        VegDatabase::class.java,
                        DATABASE_NAME,
                    )
                        .openHelperFactory(RtreeSqlite.openHelperFactory())
                        .fallbackToDestructiveMigration()
                        .build()
                        .also { instance = it }
                }
            }
        }

        /**
         * Room's createFromAsset will not recopy on schema upgrades, and
         * fallbackToDestructiveMigration would then open an empty database.
         * Copy the bundled file ourselves when the schema or asset size changes.
         */
        private fun copyAssetIfChanged(context: Context) {
            val destination = context.getDatabasePath(DATABASE_NAME)
            val expectedLength = context.assets.openFd(ASSET_NAME).use { descriptor ->
                descriptor.length
            }
            val storedAssetSize = context
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getLong(PREFS_ASSET_SIZE, -1L)
            if (destination.exists() &&
                storedAssetSize == expectedLength &&
                hasCurrentSchema(destination)
            ) {
                return
            }
            TripLog.append(
                "DB copy vegdata.db size=$expectedLength stored=$storedAssetSize exists=${destination.exists()}",
            )
            context.deleteDatabase(DATABASE_NAME)
            val parent = destination.parentFile
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                throw IOException("Klarte ikke å opprette ${parent.absolutePath}")
            }
            copyUncompressedAsset(context, destination)
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putLong(PREFS_ASSET_SIZE, expectedLength)
                .apply()
        }

        private fun hasCurrentSchema(databaseFile: File): Boolean {
            return try {
                RtreeSqlite.openReadonly(databaseFile.path).use { database ->
                    val userVersion = database.version
                    val hasPointsColumn = database.rawQuery(
                        "PRAGMA table_info(vegobjekt)",
                        null,
                    ).use { cursor ->
                        val nameIndex = cursor.getColumnIndex("name")
                        var found = false
                        while (cursor.moveToNext()) {
                            if (cursor.getString(nameIndex) == "points") {
                                found = true
                                break
                            }
                        }
                        found
                    }
                    userVersion == EXPECTED_USER_VERSION && hasPointsColumn
                }
            } catch (error: Exception) {
                Log.w(TAG, "Kunne ikke lese vegdata-skjema, kopierer på nytt", error)
                false
            }
        }

        private fun copyUncompressedAsset(context: Context, destination: File) {
            val temporary = File(destination.parentFile, "${destination.name}.tmp")
            if (temporary.exists() && !temporary.delete()) {
                throw IOException("Klarte ikke å slette ${temporary.absolutePath}")
            }
            try {
                context.assets.openFd(ASSET_NAME).use { descriptor ->
                    FileInputStream(descriptor.fileDescriptor).channel.use { inputChannel ->
                        temporary.outputStream().channel.use { outputChannel ->
                            var position = descriptor.startOffset
                            var remaining = descriptor.length
                            while (remaining > 0L) {
                                val transferred = inputChannel.transferTo(
                                    position,
                                    remaining,
                                    outputChannel,
                                )
                                if (transferred <= 0L) {
                                    throw IOException("Avbrutt kopiering av vegdata.db")
                                }
                                position += transferred
                                remaining -= transferred
                            }
                        }
                    }
                }
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
                throw IOException("Klarte ikke å flytte vegdata.db på plass")
            }
        }
    }
}
