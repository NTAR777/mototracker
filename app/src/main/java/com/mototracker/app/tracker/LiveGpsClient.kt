package com.mototracker.app.tracker

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
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
 * Даёт спидометр, стрелку на карте и бейдж точности сразу после запуска,
 * без нажатия СТАРТ. В БД ничего не пишет — это делает только сервис записи.
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

            // Возраст фикса по elapsedRealtime — не зависит от часовых поясов
            // и «плывущего» времени GPS на некоторых прошивках.
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
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) return

        val fused = try {
            LocationServices.getFusedLocationProviderClient(context)
        } catch (e: Exception) {
            Log.w(TAG, "Google Play Services недоступны", e)
            return
        }

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
            .setMinUpdateIntervalMillis(500L)
            .build()
        try {
            fused.requestLocationUpdates(request, callback, context.mainLooper)
        } catch (e: Exception) {
            Log.w(TAG, "Не удалось подписаться на обновления локации", e)
            return
        }
        fusedClient = fused
        running = true
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
}
