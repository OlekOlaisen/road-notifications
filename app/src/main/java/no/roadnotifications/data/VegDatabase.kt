package no.roadnotifications.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [VegObjektEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class VegDatabase : RoomDatabase() {
    abstract fun vegObjektDao(): VegObjektDao

    companion object {
        @Volatile
        private var instance: VegDatabase? = null

        fun getInstance(context: Context): VegDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    VegDatabase::class.java,
                    "vegdata.db",
                )
                    .createFromAsset("vegdata.db")
                    .build()
                    .also { instance = it }
            }
        }
    }
}
