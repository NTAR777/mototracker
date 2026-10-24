package com.mototracker.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.mototracker.app.MainActivity
import com.mototracker.app.R
import com.mototracker.app.di.ServiceLocator
import com.mototracker.app.tracker.LiveState
import com.mototracker.app.tracker.TrackRecorder
import com.mototracker.app.tracker.TrackingBus
import com.mototracker.app.tracker.TrackingStatus
import com.mototracker.app.util.Format
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground-сервис записи GPS. Живёт, пока идёт (или стоит на паузе) поездка,
 * держит WakeLock, чтобы процессор не засыпал между фиксами.
 *
 * Если система убила процесс — сервис перезапустится (START_STICKY) и
 * рекордер продолжит писать в тот же трек из БД.
 */
class LocationService : Service() {

    companion object {
        const val ACTION_START = "com.mototracker.app.action.START"

        private const val CHANNEL_ID = "recording"
        private const val NOTIFICATION_ID = 42
        private const val TAG = "LocationService"

        fun start(context: Context) {
            context.startForegroundService(
                Intent(context, LocationService::class.java).setAction(ACTION_START)
            )
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var wakeLock: PowerManager.WakeLock? = null

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            for (location in result.locations) {
                TrackRecorder.onLocationUpdate(location)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startAsForeground()
        acquireWakeLock()

        val client = LocationServices.getFusedLocationProviderClient(this)
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
            .setMinUpdateIntervalMillis(1000L)
            .setMaxUpdateDelayMillis(2000L)
            .build()
        client.requestLocationUpdates(request, locationCallback, mainLooper)

        scope.launch {
            // Восстановление «висящего» трека после сбоя процесса.
            val open = ServiceLocator.trackRepository.getOpenTrack()
            if (open != null && !TrackRecorder.isAttached) {
                TrackRecorder.attachOpenTrack(open.id)
            }
        }

        scope.launch {
            // Обновляем уведомление не чаще, чем раз в 3 секунды.
            while (isActive) {
                updateNotification(TrackingBus.state.value)
                delay(3000)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Все команды пауза/стоп приходят через ServiceActionsReceiver.
        return START_STICKY
    }

    override fun onDestroy() {
        LocationServices.getFusedLocationProviderClient(this)
            .removeLocationUpdates(locationCallback)
        wakeLock?.let { if (it.isHeld) it.release() }
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---------------------------------------------------------------- notify

    private fun startAsForeground() {
        val notification = buildNotification(TrackingBus.state.value)
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Не удалось запустить foreground-сервис", e)
            stopSelf()
        }
    }

    private fun buildNotification(state: LiveState): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = when {
            state.status == TrackingStatus.PAUSED && state.autoPaused -> "Автопауза"
            state.status == TrackingStatus.PAUSED -> "Пауза"
            else -> "Запись поездки"
        }
        val text = "${Format.distance(state.distanceM)} • ${Format.speed(state.speedKmh)}"

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_record)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setContentIntent(openApp)

        builder.addAction(0, "Пауза", actionPending(ServiceActionsReceiver.ACTION_PAUSE))
        builder.addAction(0, "Стоп", actionPending(ServiceActionsReceiver.ACTION_STOP))
        return builder.build()
    }

    private fun updateNotification(state: LiveState) {
        if (state.status == TrackingStatus.IDLE) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.notify(NOTIFICATION_ID, buildNotification(state))
    }

    private fun actionPending(action: String): PendingIntent =
        PendingIntent.getBroadcast(
            this,
            action.hashCode(),
            Intent(this, ServiceActionsReceiver::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun createNotificationChannel() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Запись поездки",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Показывает, что идёт запись маршрута"
        }
        nm.createNotificationChannel(channel)
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MotoTracker::recording")?.apply {
            setReferenceCounted(false)
            acquire(24 * 60 * 60 * 1000L) // максимум сутки
        }
    }
}
