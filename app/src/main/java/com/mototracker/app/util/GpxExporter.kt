package com.mototracker.app.util

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.mototracker.app.data.db.TrackEntity
import com.mototracker.app.data.db.TrackPointEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * Экспорт в GPX 1.1. Паузы разделяют трек на <trkseg> —
 * Strava, Яндекс.Навигатор и OsmAnd открывают такие файлы.
 */
object GpxExporter {

    suspend fun export(
        context: Context,
        track: TrackEntity,
        points: List<TrackPointEntity>
    ): Result<Uri?> = withContext(Dispatchers.IO) {
        runCatching {
            val gpx = buildGpx(track, points)
            val fileName = "MotoTracker_${Format.fileStamp(track.startTime)}.gpx"
            if (Build.VERSION.SDK_INT >= 29) {
                saveToDownloads(context, fileName, gpx)
            } else {
                saveToAppDir(context, fileName, gpx)
            }
        }
    }

    private fun buildGpx(track: TrackEntity, points: List<TrackPointEntity>): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<gpx version=\"1.1\" creator=\"Moto Tracker\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
        sb.append("  <trk>\n")
        sb.append("    <name>${escape(track.name)}</name>\n")
        points.groupBy { it.segment }.toSortedMap().forEach { (_, segment) ->
            sb.append("    <trkseg>\n")
            for (p in segment) {
                sb.append("      <trkpt lat=\"")
                    .append("%.6f".format(Locale.US, p.lat))
                    .append("\" lon=\"")
                    .append("%.6f".format(Locale.US, p.lon))
                    .append("\">\n")
                    .append("        <ele>")
                    .append("%.1f".format(Locale.US, p.altitudeM))
                    .append("</ele>\n")
                    .append("        <time>")
                    .append(Format.gpxTime(p.timeMs))
                    .append("</time>\n")
                    .append("        <speed>")
                    .append("%.2f".format(Locale.US, p.speedKmh / 3.6))
                    .append("</speed>\n")
                    .append("      </trkpt>\n")
            }
            sb.append("    </trkseg>\n")
        }
        sb.append("  </trk>\n")
        sb.append("</gpx>\n")
        return sb.toString()
    }

    /** Android 10+: пишем прямо в общую папку «Загрузки». */
    private fun saveToDownloads(context: Context, fileName: String, gpx: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, "application/gpx+xml")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("Не удалось создать файл в «Загрузках»")
        resolver.openOutputStream(uri)?.use { out ->
            out.write(gpx.toByteArray(Charsets.UTF_8))
        } ?: error("Не удалось открыть файл на запись")
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return uri
    }

    /** Android 8–9: файл в приватной папке + расшариваем через FileProvider. */
    private fun saveToAppDir(context: Context, fileName: String, gpx: String): Uri {
        val dir = File(context.getExternalFilesDir(null), "export").apply { mkdirs() }
        val file = File(dir, fileName)
        file.writeText(gpx, Charsets.UTF_8)
        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
    }

    private fun escape(s: String): String {
        val amp = "&"
        return s
            .replace(amp, "${amp}amp;")
            .replace("<", "${amp}lt;")
            .replace(">", "${amp}gt;")
    }
}
