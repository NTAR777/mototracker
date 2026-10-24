package com.mototracker.app.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object Format {

    fun distance(m: Double): String = when {
        m < 1000.0 -> "${m.toInt()} м"
        else -> "%.1f км".format(Locale.getDefault(), m / 1000.0)
    }

    /** 3725 -> «1:02:05», 305 -> «5:05» */
    fun duration(sec: Double): String {
        val total = sec.toInt()
        val h = total / 3600
        val min = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(Locale.getDefault(), h, min, s)
        else "%d:%02d".format(Locale.getDefault(), min, s)
    }

    /** «96 км/ч» */
    fun speed(kmh: Double): String = "%.0f км/ч".format(Locale.getDefault(), kmh)

    /** «96» — крупная цифра для спидометра */
    fun speedRaw(kmh: Double): String = "%.0f".format(Locale.getDefault(), kmh)

    /** «87» без единиц — для плиток статистики */
    fun avg(kmh: Double): String = "%.0f".format(Locale.getDefault(), kmh)

    fun dateTime(ms: Long): String =
        SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(ms))

    fun gpxTime(ms: Long): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }.format(Date(ms))

    fun fileStamp(ms: Long): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(ms))
}
