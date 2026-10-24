package com.mototracker.app.tracker

import android.location.Location
import android.util.Log
import com.mototracker.app.data.db.TrackEntity
import com.mototracker.app.data.db.TrackPointEntity
import com.mototracker.app.di.ServiceLocator
import com.mototracker.app.util.GeoUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Ядро записи. Все команды обрабатываются строго по очереди в одном
 * корутинном «потоке-потребителе», поэтому состояние не гоняется между потоками.
 *
 * Защита от плохого GPS (регион с проблемным приёмом):
 * 1. Точки с точностью хуже [TrackerSettings.accuracyThresholdM] выбрасываются.
 * 2. «Телепорты»: если расстояние за интервал подразумевает скорость больше
 *    правдоподобной — точка выбрасывается.
 * 3. Протухшие фикс-ы (старше 8 с) и фикс-ы «из будущего» игнорируются.
 * 4. На стоянке шум GPS не накручивает дистанцию: адаптивный порог шума
 *    по точности двух соседних фикс-ов.
 */
object TrackRecorder {

    private const val TAG = "TrackRecorder"

    private const val MIN_FIX_INTERVAL_MS = 250L
    private const val NOISE_MIN_M = 4.0
    private const val MOVING_SPEED_MS = 0.7      // ~2.5 км/ч — «едем»
    private const val STANDING_SPEED_MS = 0.6    // ~2.2 км/ч — «стоим»
    private const val RESUME_SPEED_MS = 1.5      // ~5.4 км/ч — сняться с автопаузы
    private const val STALE_FIX_MS = 8_000L
    private const val FUTURE_FIX_MS = 2_000L
    private const val STATS_FLUSH_MS = 5_000L
    private const val MAX_DT_SEC = 10.0
    private const val STANDING_SAVE_INTERVAL_MS = 5_000L

    private sealed interface Command {
        data class Fix(val location: Location) : Command
        data class Pause(val auto: Boolean) : Command
        data object Resume : Command
        data object Stop : Command
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val commands = Channel<Command>(capacity = 128)

    private var trackId = -1L
    private var trackName = ""
    private var startTimeMs = 0L
    private var segment = 0
    private var seq = 0

    private var lastLat = 0.0
    private var lastLon = 0.0
    private var lastAlt = 0.0
    private var lastTimeMs = 0L
    private var lastAccuracyM = 0.0
    private var lastSpeedMs = 0.0
    private var haveLast = false

    /** true после resume/восстановления: первый фикс не должен «сшивать» пропасть. */
    private var skipNextAccumulation = false

    private var distanceM = 0.0
    private var totalTimeSec = 0.0
    private var movingTimeSec = 0.0
    private var maxSpeedKmh = 0.0

    private var standingSinceMs = -1L
    private var statsFlushAt = 0L

    val isAttached: Boolean get() = trackId > 0

    init {
        scope.launch {
            for (command in commands) {
                try {
                    when (command) {
                        is Command.Fix -> process(command.location)
                        is Command.Pause -> doPause(command.auto)
                        Command.Resume -> doResume()
                        Command.Stop -> doStop()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Ошибка обработки команды трекера", e)
                }
            }
        }
    }

    /** Начать новую поездку. */
    suspend fun startNew(): Long {
        if (isAttached) return trackId
        val name = "Поездка " + SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()).format(Date())
        val now = System.currentTimeMillis()
        val id = ServiceLocator.trackRepository.createTrack(name, now)
        trackId = id
        trackName = name
        startTimeMs = now
        resetCounters()
        TrackingBus.set(LiveState(status = TrackingStatus.RECORDING, trackId = id))
        Log.i(TAG, "Новая поездка: id=$id")
        return id
    }

    /**
     * Продолжить «висящий» трек после сбоя/перезапуска процесса
     * (сервис перезапускается системой со START_STICKY).
     */
    suspend fun attachOpenTrack(id: Long) {
        if (isAttached) return
        val track = ServiceLocator.trackRepository.getTrack(id) ?: return
        val last = ServiceLocator.trackRepository.getLastPoint(id)

        trackId = id
        trackName = track.name
        startTimeMs = track.startTime
        resetCounters()
        distanceM = track.distanceM
        totalTimeSec = track.totalTimeSec
        movingTimeSec = track.movingTimeSec
        maxSpeedKmh = track.maxSpeedKmh
        skipNextAccumulation = true

        if (last != null) {
            haveLast = true
            seq = last.seq + 1
            segment = last.segment
            lastLat = last.lat
            lastLon = last.lon
            lastAlt = last.altitudeM
            lastTimeMs = last.timeMs
            lastAccuracyM = last.accuracyM
            lastSpeedMs = last.speedKmh / 3.6
        }

        TrackingBus.set(
            LiveState(
                status = TrackingStatus.RECORDING,
                trackId = id,
                distanceM = distanceM,
                totalTimeSec = totalTimeSec,
                movingTimeSec = movingTimeSec,
                maxSpeedKmh = maxSpeedKmh,
                pointsCount = seq,
                lastPoint = last?.let {
                    LivePoint(it.lat, it.lon, it.speedKmh, it.timeMs, it.segment, 0.0)
                }
            )
        )
        Log.i(TAG, "Продолжаю незакрытый трек id=$id")
    }

    fun onLocationUpdate(location: Location) {
        if (trackId <= 0) return
        commands.trySend(Command.Fix(location))
    }

    fun pause() {
        commands.trySend(Command.Pause(auto = false))
    }

    fun resume() {
        commands.trySend(Command.Resume)
    }

    fun stop() {
        commands.trySend(Command.Stop)
    }

    // ---------------------------------------------------------------- private

    private suspend fun process(location: Location) {
        val settings = ServiceLocator.settingsRepository.current()
        val accuracy = location.accuracy.coerceAtLeast(0f).toDouble()
        val fixAge = System.currentTimeMillis() - location.time

        val state = TrackingBus.state.value
        if (state.status == TrackingStatus.PAUSED) {
            // После автопаузы сами снимаемся, как только поехали.
            if (state.autoPaused &&
                accuracy <= settings.accuracyThresholdM &&
                location.hasSpeed() && location.speed > RESUME_SPEED_MS
            ) {
                doResume()
            }
            return
        }
        if (state.status != TrackingStatus.RECORDING) return

        // --- фильтрация мусора ------------------------------------------------
        if (accuracy > settings.accuracyThresholdM) return
        if (fixAge < -FUTURE_FIX_MS || fixAge > STALE_FIX_MS) return

        var dtSec = 0.0
        var distM = 0.0
        if (haveLast) {
            val dtMs = location.time - lastTimeMs
            if (dtMs < MIN_FIX_INTERVAL_MS) return
            if (!skipNextAccumulation) {
                dtSec = dtMs / 1000.0
                distM = GeoUtils.distanceM(lastLat, lastLon, location.latitude, location.longitude)
                if (distM / dtSec * 3.6 > settings.maxPlausibleSpeedKmh) return // «телепорт»
            }
        }

        // --- скорость ----------------------------------------------------------
        val rawSpeedMs = when {
            location.hasSpeed() -> location.speed.toDouble()
            haveLast && !skipNextAccumulation -> distM / dtSec
            else -> 0.0
        }
        val speedMs = if (haveLast) rawSpeedMs * 0.65 + lastSpeedMs * 0.35 else rawSpeedMs
        val speedKmh = speedMs * 3.6

        // --- накопление статистики --------------------------------------------
        val countedDt = if (haveLast && !skipNextAccumulation) minOf(dtSec, MAX_DT_SEC) else 0.0
        totalTimeSec += countedDt
        if (speedMs > MOVING_SPEED_MS) movingTimeSec += countedDt

        val noiseM = if (haveLast) maxOf(NOISE_MIN_M, 0.8 * minOf(lastAccuracyM, accuracy)) else 0.0
        val countedDist = if (haveLast && !skipNextAccumulation && distM > noiseM) distM else 0.0
        distanceM += countedDist
        if (speedKmh > maxSpeedKmh) maxSpeedKmh = speedKmh

        val heading = when {
            location.hasBearing() -> location.bearing.toDouble()
            countedDist > 0.0 -> GeoUtils.bearingDeg(lastLat, lastLon, location.latitude, location.longitude)
            else -> state.lastPoint?.bearingDeg ?: 0.0
        }

        // --- сохранение точки ---------------------------------------------------
        // На стоянке пишем точку раз в 5 секунд, чтобы не плодить дубликаты.
        if (!haveLast || countedDist > 0.0 || location.time - lastTimeMs >= STANDING_SAVE_INTERVAL_MS) {
            ServiceLocator.trackRepository.insertPoint(
                TrackPointEntity(
                    trackId = trackId,
                    seq = seq++,
                    lat = location.latitude,
                    lon = location.longitude,
                    altitudeM = if (location.hasAltitude()) location.altitude else lastAlt,
                    speedKmh = speedKmh,
                    accuracyM = accuracy,
                    timeMs = location.time,
                    segment = segment
                )
            )
        }

        // --- обновить «последнюю точку» ----------------------------------------
        lastLat = location.latitude
        lastLon = location.longitude
        if (location.hasAltitude()) lastAlt = location.altitude
        lastTimeMs = location.time
        lastAccuracyM = accuracy
        lastSpeedMs = speedMs
        haveLast = true
        skipNextAccumulation = false

        // --- автопауза ----------------------------------------------------------
        if (settings.autoPauseEnabled) {
            if (speedMs <= STANDING_SPEED_MS) {
                if (standingSinceMs < 0) {
                    standingSinceMs = location.time
                } else if (location.time - standingSinceMs >= settings.autoPauseMinutes * 60_000L) {
                    doPause(auto = true)
                    return
                }
            } else {
                standingSinceMs = -1L
            }
        }

        // --- краш-безопасность: периодический сброс статистики в БД -------------
        if (location.time >= statsFlushAt) {
            statsFlushAt = location.time + STATS_FLUSH_MS
            ServiceLocator.trackRepository.updateTrack(currentTrackEntity(endTime = null))
        }

        TrackingBus.set(
            LiveState(
                status = TrackingStatus.RECORDING,
                trackId = trackId,
                autoPaused = false,
                speedKmh = speedKmh,
                distanceM = distanceM,
                totalTimeSec = totalTimeSec,
                movingTimeSec = movingTimeSec,
                maxSpeedKmh = maxSpeedKmh,
                accuracyM = accuracy,
                fixAgeMs = fixAge,
                altitudeM = if (location.hasAltitude()) location.altitude else state.altitudeM,
                lastPoint = LivePoint(
                    lat = location.latitude,
                    lon = location.longitude,
                    speedKmh = speedKmh,
                    timeMs = location.time,
                    segment = segment,
                    bearingDeg = heading
                ),
                pointsCount = seq
            )
        )
    }

    private suspend fun doPause(auto: Boolean) {
        if (TrackingBus.state.value.status != TrackingStatus.RECORDING) return
        ServiceLocator.trackRepository.updateTrack(currentTrackEntity(endTime = null))
        segment++ // после паузы — новый сегмент (разрыв линии и новый trkseg в GPX)
        standingSinceMs = -1L
        TrackingBus.update {
            it.copy(status = TrackingStatus.PAUSED, autoPaused = auto, speedKmh = 0.0)
        }
        Log.i(TAG, "Пауза (auto=$auto)")
    }

    private fun doResume() {
        if (TrackingBus.state.value.status != TrackingStatus.PAUSED) return
        standingSinceMs = -1L
        skipNextAccumulation = true
        TrackingBus.update {
            it.copy(status = TrackingStatus.RECORDING, autoPaused = false)
        }
        Log.i(TAG, "Продолжаем")
    }

    private suspend fun doStop() {
        if (trackId <= 0) return
        val id = trackId
        ServiceLocator.trackRepository.updateTrack(currentTrackEntity(endTime = System.currentTimeMillis()))
        resetState()
        TrackingBus.reset()
        Log.i(TAG, "Поездка завершена: id=$id")
    }

    private fun currentTrackEntity(endTime: Long?): TrackEntity = TrackEntity(
        id = trackId,
        name = trackName,
        startTime = startTimeMs,
        endTime = endTime,
        distanceM = distanceM,
        movingTimeSec = movingTimeSec,
        totalTimeSec = totalTimeSec,
        maxSpeedKmh = maxSpeedKmh
    )

    private fun resetCounters() {
        segment = 0
        seq = 0
        haveLast = false
        skipNextAccumulation = false
        lastSpeedMs = 0.0
        lastAccuracyM = 0.0
        distanceM = 0.0
        totalTimeSec = 0.0
        movingTimeSec = 0.0
        maxSpeedKmh = 0.0
        standingSinceMs = -1L
        statsFlushAt = 0L
    }

    private fun resetState() {
        trackId = -1L
        trackName = ""
        startTimeMs = 0L
        resetCounters()
    }
}
