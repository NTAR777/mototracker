package com.mototracker.app.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Поездка. Статистика периодически обновляется в процессе записи,
 * поэтому после сбоя приложения данные не теряются.
 */
@Entity(tableName = "tracks")
data class TrackEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val startTime: Long,
    val endTime: Long? = null,
    val distanceM: Double = 0.0,
    /** Время в движении, сек. */
    val movingTimeSec: Double = 0.0,
    /** Общее время записи без пауз, сек. */
    val totalTimeSec: Double = 0.0,
    val maxSpeedKmh: Double = 0.0
) {
    val avgMovingKmh: Double
        get() = if (movingTimeSec > 5.0) distanceM / movingTimeSec * 3.6 else 0.0

    val avgTotalKmh: Double
        get() = if (totalTimeSec > 5.0) distanceM / totalTimeSec * 3.6 else 0.0
}

/**
 * Точка маршрута. [segment] растёт на 1 после каждой паузы —
 * используется и для рисования (разрывы линии), и для GPX (trkseg).
 */
@Entity(
    tableName = "points",
    foreignKeys = [
        ForeignKey(
            entity = TrackEntity::class,
            parentColumns = ["id"],
            childColumns = ["trackId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("trackId")]
)
data class TrackPointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val trackId: Long,
    val seq: Int,
    val lat: Double,
    val lon: Double,
    val altitudeM: Double,
    val speedKmh: Double,
    val accuracyM: Double,
    val timeMs: Long,
    val segment: Int
)
