package com.mototracker.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.mototracker.app.ui.AppRoot
import com.mototracker.app.ui.theme.MotoTrackerTheme
import com.mototracker.app.tracker.LiveGpsClient
import com.mototracker.app.tracker.TrackingBus
import com.mototracker.app.tracker.TrackingStatus
import com.yandex.mapkit.MapKitFactory

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            MotoTrackerTheme {
                AppRoot()
            }
        }
    }

    // Требование MapKit SDK: жизненный цикл движка карты привязан к активности.
    override fun onStart() {
        super.onStart()
        MapKitFactory.getInstance().onStart()
        // GPS для UI (спидометр, стрелка на карте) — сразу, без записи.
        if (TrackingBus.state.value.status == TrackingStatus.IDLE) {
            LiveGpsClient.start(applicationContext)
        }
    }

    override fun onStop() {
        // Пока идёт запись, поток держит LocationService; UI-поток глушим.
        LiveGpsClient.stop()
        MapKitFactory.getInstance().onStop()
        super.onStop()
    }
}
