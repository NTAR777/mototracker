package com.mototracker.app.di

import android.content.Context
import com.mototracker.app.data.db.AppDatabase
import com.mototracker.app.data.repo.SettingsRepository
import com.mototracker.app.data.repo.TrackRepository

/**
 * Простейший ручной DI: для личного приложения Hilt избыточен.
 */
object ServiceLocator {

    private lateinit var appContext: Context

    val database: AppDatabase by lazy { AppDatabase.build(appContext) }

    val trackRepository: TrackRepository by lazy {
        TrackRepository(database.trackDao(), database.pointDao())
    }

    val settingsRepository: SettingsRepository by lazy { SettingsRepository(appContext) }

    fun init(context: Context) {
        appContext = context.applicationContext
    }
}
