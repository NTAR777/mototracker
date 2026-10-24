package com.mototracker.app.tracker

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** IDLE — приложение ничего не слушает; STANDBY — живой GPS для UI без записи. */
enum class TrackingStatus { IDLE, STANDBY, RECORDING, PAUSED }

/** Точка, которую UI рисует на карте как текущую позицию. */
data class LivePoint(
    val lat: Double,
    val lon: Double,
    val speedKmh: Double,
    val timeMs: Long,
    val segment: Int,
    val bearingDeg: Double
)

/**
 * Живое состояние записи. Единый источник правды для UI:
 * сервис-рекордер обновляет, экраны читают.
 */
data class LiveState(
    val status: TrackingStatus = TrackingStatus.IDLE,
    val trackId: Long? = null,
    val autoPaused: Boolean = false,
    val speedKmh: Double = 0.0,
    val distanceM: Double = 0.0,
    val totalTimeSec: Double = 0.0,
    val movingTimeSec: Double = 0.0,
    val maxSpeedKmh: Double = 0.0,
    /** Точность последнего фикс-а, метры. */
    val accuracyM: Double = Double.MAX_VALUE,
    /** Возраст последнего фикс-а, мс. -1 — фикс-ов ещё не было. */
    val fixAgeMs: Long = -1L,
    val altitudeM: Double? = null,
    val lastPoint: LivePoint? = null,
    val pointsCount: Int = 0
) {
    /** Средняя скорость в движении (стоянки и паузы исключены). */
    val avgMovingKmh: Double
        get() = if (movingTimeSec > 5.0) distanceM / movingTimeSec * 3.6 else 0.0

    /** Средняя скорость на всё активное время записи (без пауз). */
    val avgTotalKmh: Double
        get() = if (totalTimeSec > 5.0) distanceM / totalTimeSec * 3.6 else 0.0
}

object TrackingBus {

    private val _state = MutableStateFlow(LiveState())
    val state: StateFlow<LiveState> = _state.asStateFlow()

    fun update(transform: (LiveState) -> LiveState) = _state.update(transform)

    fun set(state: LiveState) {
        _state.value = state
    }

    fun reset() {
        _state.value = LiveState()
    }
}
