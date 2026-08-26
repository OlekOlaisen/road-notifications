package no.roadnotifications.log

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.location.Location
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import no.roadnotifications.data.VegObjektEntity

/**
 * Persistent trip log so a drive can be shared after the fact.
 * Written from the tracking service; shown on the Logg tab.
 */
object TripLog {
    private const val MAX_FILE_BYTES = 1_500_000L
    private const val MAX_UI_LINES = 500
    private const val FILE_PROVIDER_SUFFIX = ".fileprovider"

    private val writer = Executors.newSingleThreadExecutor()
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val uiLinesState = MutableStateFlow<List<String>>(emptyList())
    private val lock = Any()

    @Volatile
    private var applicationContext: Context? = null

    @Volatile
    private var logFile: File? = null

    val lines: StateFlow<List<String>> = uiLinesState.asStateFlow()

    fun init(context: Context) {
        val appContext = context.applicationContext
        applicationContext = appContext
        val directory = File(appContext.filesDir, "logs")
        if (!directory.exists()) {
            directory.mkdirs()
        }
        val file = File(directory, "trip-log.txt")
        logFile = file
        writer.execute {
            val tail = readTail(file, MAX_UI_LINES)
            uiLinesState.value = tail
        }
    }

    fun append(message: String) {
        val line = "${timeFormat.format(Date())} $message"
        uiLinesState.update { current ->
            (current + line).takeLast(MAX_UI_LINES)
        }
        writer.execute {
            val file = logFile ?: return@execute
            synchronized(lock) {
                rotateIfNeeded(file)
                file.appendText(line + "\n")
            }
        }
    }

    fun clear() {
        uiLinesState.value = emptyList()
        writer.execute {
            val file = logFile ?: return@execute
            synchronized(lock) {
                file.writeText("")
            }
        }
        append("CLEARED")
    }

    fun shareIntent(context: Context): Intent? {
        val file = logFile
        if (file == null || !file.exists() || file.length() == 0L) {
            return null
        }
        val uri = FileProvider.getUriForFile(
            context,
            context.packageName + FILE_PROVIDER_SUFFIX,
            file,
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Vegassistent turlogg")
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("turlogg", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(intent, "Del turlogg")
    }

    fun copyText(): String {
        val file = logFile
        synchronized(lock) {
            if (file != null && file.exists()) {
                val fromFile = file.readText()
                if (fromFile.isNotBlank()) {
                    return fromFile
                }
            }
        }
        return uiLinesState.value.joinToString("\n")
    }

    fun hasContent(): Boolean {
        val file = logFile
        return (file != null && file.exists() && file.length() > 0L) ||
            uiLinesState.value.isNotEmpty()
    }

    fun formatObjekt(vegObjekt: VegObjektEntity): String {
        val verdi = vegObjekt.verdi?.trim().orEmpty()
        return if (verdi.isEmpty()) {
            "${vegObjekt.type}#${vegObjekt.id}"
        } else {
            "${vegObjekt.type}:$verdi#${vegObjekt.id}"
        }
    }

    fun formatLocation(location: Location): String {
        val accuracy = if (location.hasAccuracy()) {
            String.format(Locale.US, "%.1f", location.accuracy)
        } else {
            "-"
        }
        val speedKmh = if (location.hasSpeed()) {
            String.format(Locale.US, "%.0f", location.speed * 3.6f)
        } else {
            "-"
        }
        val bearing = if (location.hasBearing()) {
            String.format(Locale.US, "%.0f", location.bearing)
        } else {
            "-"
        }
        return String.format(
            Locale.US,
            "%.6f,%.6f acc=%sm spd=%skm/h brg=%s",
            location.latitude,
            location.longitude,
            accuracy,
            speedKmh,
            bearing,
        )
    }

    private fun rotateIfNeeded(file: File) {
        if (!file.exists() || file.length() <= MAX_FILE_BYTES) {
            return
        }
        val kept = readTail(file, MAX_UI_LINES * 4)
        file.writeText(kept.joinToString("\n", postfix = "\n"))
    }

    private fun readTail(file: File, maxLines: Int): List<String> {
        if (!file.exists()) {
            return emptyList()
        }
        return try {
            file.readLines().takeLast(maxLines)
        } catch (_: Exception) {
            emptyList()
        }
    }
}
