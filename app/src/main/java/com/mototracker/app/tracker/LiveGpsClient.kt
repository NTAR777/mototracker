package com.mototracker.app.tracker

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

/**
 * Лёгкий GPS-поток для UI: работает, пока приложение на экране и записи нет.
 * Даёт спидометр, стрелку на карте и бейдж точности сразу после запуска.
 * Любая причина «почему GPS не работает» публикуется в [LiveState.gpsError],
 * чтобы не было молчаливых отказов.
 */
object LiveGpsClient {

    private const val TAG = "LiveGpsClient"

    private var fusedClient: com.google.android.gms.location.FusedLocationProviderClient? = null
    private var running = false

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val location = result.lastLocation ?: return
            // Пока идёт запись (или пауза), позицию отдаёт LocationService — не мешаем.
            if (TrackingBus.state.value.status != TrackingStatus.IDLE) return

            // Возраст фикса по elapsedRealtime — не зависит от «плывущего» времени GPS.
            val fixAgeMs = (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000L
            val speedMs = if (location.hasSpeed()) location.speed.toDouble() else 0.0
            val speedKmh = speedMs * 3.6

            TrackingBus.set(
                LiveState(
                    status = TrackingStatus.STANDBY,
                    speedKmh = speedKmh,
                    accuracyM = location.accuracy.toDouble(),
                    fixAgeMs = fixAgeMs,
                    altitudeM = if (location.hasAltitude()) location.altitude else null,
                    gpsError = null,
                    lastPoint = LivePoint(
                        lat = location.latitude,
                        lon = location.longitude,
                        speedKmh = speedKmh,
                        timeMs = System.currentTimeMillis(),
                        segment = 0,
                        bearingDeg = if (location.hasBearing()) location.bearing.toDouble() else 0.0
                    )
                )
            )
        }
    }

    fun start(context: Context) {
        if (running) return

        // Для HIGH_ACCURACY нужна именно точная геолокация; с «приблизительной»
        // FusedLocationProvider кидает SecurityException — выявляем это заранее.
        val fineGranted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!fineGranted) {
            publishError("Нужно разрешение «Точное местоположение» (Настройки → Приложения → Moto Tracker → Разрешения)")
            return
        }

        // Системная геолокация может быть выключена целиком — тогда фикс-ов не будет вовсе.
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        val anyProviderOn = lm?.run {
            isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        } ?: false
        if (!anyProviderOn) {
            publishError("Геолокация выключена в настройках телефона")
            return
        }

        val fused = try {
            LocationServices.getFusedLocationProviderClient(context)
        } catch (e: Exception) {
            Log.w(TAG, "Google Play Services недоступны", e)
            publishError("Сервисы Google Play недоступны: ${e.message}")
            return
        }

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
            .setMinUpdateIntervalMillis(500L)
            .build()
        try {
            fused.requestLocationUpdates(request, callback, context.mainLooper)
        } catch (e: SecurityException) {
            Log.w(TAG, "Нет разрешения на точную геолокацию", e)
            publishError("Разрешение «Точное местоположение» не выдано")
            return
        } catch (e: Exception) {
            Log.w(TAG, "Не удалось подписаться на обновления локации", e)
            publishError("Не удалось включить GPS: ${e.message}")
            return
        }
        fusedClient = fused
        running = true
        publishError(null)
    }

    fun stop() {
        if (!running) return
        try {
            fusedClient?.removeLocationUpdates(callback)
        } catch (_: Exception) {
        }
        fusedClient = null
        running = false
    }

    private fun publishError(message: String?) {
        if (TrackingBus.state.value.status != TrackingStatus.IDLE) return
        TrackingBus.set(TrackingBus.state.value.copy(status = TrackingStatus.IDLE, gpsError = message))
    }
}
