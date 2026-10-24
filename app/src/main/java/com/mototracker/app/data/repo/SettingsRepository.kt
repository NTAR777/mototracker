package com.mototracker.app.data.repo

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class TrackerSettings(
    /** Автопауза на длительных остановках. */
    val autoPauseEnabled: Boolean = true,
    /** Сколько минут стоим, прежде чем включить автопаузу. */
    val autoPauseMinutes: Int = 3,
    /** Отбрасывать точки с точностью хуже этой (метры). Важно для региона с плохим GPS. */
    val accuracyThresholdM: Double = 30.0,
    /** «Телепорты» быстрее этой скорости (км/ч) считаются ошибкой GPS и отбрасываются. */
    val maxPlausibleSpeedKmh: Double = 250.0,
    /** Не давать экрану гаснуть. */
    val keepScreenOn: Boolean = true
)

class SettingsRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("moto_tracker_settings", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<TrackerSettings> = _settings.asStateFlow()

    fun current(): TrackerSettings = _settings.value

    fun update(transform: (TrackerSettings) -> TrackerSettings) {
        val next = transform(_settings.value)
        prefs.edit()
            .putBoolean(KEY_AUTO_PAUSE, next.autoPauseEnabled)
            .putInt(KEY_AUTO_PAUSE_MIN, next.autoPauseMinutes)
            .putFloat(KEY_ACCURACY, next.accuracyThresholdM.toFloat())
            .putFloat(KEY_MAX_SPEED, next.maxPlausibleSpeedKmh.toFloat())
            .putBoolean(KEY_KEEP_SCREEN, next.keepScreenOn)
            .apply()
        _settings.value = next
    }

    private fun read(): TrackerSettings = TrackerSettings(
        autoPauseEnabled = prefs.getBoolean(KEY_AUTO_PAUSE, true),
        autoPauseMinutes = prefs.getInt(KEY_AUTO_PAUSE_MIN, 3),
        accuracyThresholdM = prefs.getFloat(KEY_ACCURACY, 30f).toDouble(),
        maxPlausibleSpeedKmh = prefs.getFloat(KEY_MAX_SPEED, 250f).toDouble(),
        keepScreenOn = prefs.getBoolean(KEY_KEEP_SCREEN, true)
    )

    private companion object {
        const val KEY_AUTO_PAUSE = "auto_pause_enabled"
        const val KEY_AUTO_PAUSE_MIN = "auto_pause_minutes"
        const val KEY_ACCURACY = "accuracy_threshold_m"
        const val KEY_MAX_SPEED = "max_plausible_kmh"
        const val KEY_KEEP_SCREEN = "keep_screen_on"
    }
}
