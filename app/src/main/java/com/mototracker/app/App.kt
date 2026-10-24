package com.mototracker.app

import android.app.Application
import android.content.pm.PackageManager
import android.util.Log
import com.mototracker.app.di.ServiceLocator
import com.yandex.mapkit.MapKitFactory

class App : Application() {

    override fun onCreate() {
        super.onCreate()

        // Ключ читаем из манифеста (он подставляется из local.properties при сборке).
        // setApiKey обязан вызываться до initialize и до создания первого MapView.
        val key = try {
            packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
                .metaData?.getString(MAPKIT_METADATA_KEY)
        } catch (e: Exception) {
            null
        }
        if (key.isNullOrBlank()) {
            Log.w(TAG, "MapKit API-ключ не задан: добавь MAPKIT_API_KEY=... в local.properties")
        } else {
            MapKitFactory.setApiKey(key)
        }
        MapKitFactory.initialize(this)

        ServiceLocator.init(this)
    }

    companion object {
        private const val TAG = "MotoTracker"
        private const val MAPKIT_METADATA_KEY = "com.yandex.mapkit.API_KEY"
    }
}
