package com.mototracker.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.mototracker.app.tracker.TrackRecorder

/**
 * Кнопки в уведомлении. Отдельный BroadcastReceiver надёжнее, чем
 * startService из фона: работает даже когда приложение в фоне.
 */
class ServiceActionsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_PAUSE -> TrackRecorder.pause()
            ACTION_RESUME -> TrackRecorder.resume()
            ACTION_STOP -> {
                TrackRecorder.stop()
                context.stopService(Intent(context, LocationService::class.java))
            }
        }
    }

    companion object {
        const val ACTION_PAUSE = "com.mototracker.app.action.PAUSE"
        const val ACTION_RESUME = "com.mototracker.app.action.RESUME"
        const val ACTION_STOP = "com.mototracker.app.action.STOP"
    }
}
