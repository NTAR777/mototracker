package com.mototracker.app.util

import androidx.core.graphics.ColorUtils

/**
 * Плавный градиент «скорость → цвет»:
 * зелёный (стоим/тихо) → жёлтый → оранжевый → красный → пурпурный (быстро).
 */
object SpeedGradient {

    // (км/ч, ARGB-цвет)
    private val stops = listOf(
        0f to 0xFF43A047.toInt(),
        40f to 0xFFC0CA33.toInt(),
        80f to 0xFFFFC107.toInt(),
        120f to 0xFFFF7043.toInt(),
        160f to 0xFFE53935.toInt(),
        200f to 0xFFD500F9.toInt()
    )

    fun colorFor(speedKmh: Double): Int {
        val speed = speedKmh.coerceIn(stops.first().first.toDouble(), stops.last().first.toDouble()).toFloat()
        var i = 0
        while (i < stops.size - 1 && speed > stops[i + 1].first) i++
        val from = stops[i]
        val to = stops[minOf(i + 1, stops.size - 1)]
        val t = if (to.first > from.first) {
            ((speed - from.first) / (to.first - from.first)).coerceIn(0f, 1f)
        } else 0f
        return ColorUtils.blendARGB(from.second, to.second, t)
    }
}
