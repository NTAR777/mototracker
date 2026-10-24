package com.mototracker.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackDao {
    @Insert
    suspend fun insert(track: TrackEntity): Long

    @Update
    suspend fun update(track: TrackEntity)

    @Query("SELECT * FROM tracks WHERE endTime IS NULL ORDER BY startTime DESC LIMIT 1")
    suspend fun getOpenTrack(): TrackEntity?

    @Query("SELECT * FROM tracks WHERE id = :id")
    suspend fun getById(id: Long): TrackEntity?

    @Query("SELECT * FROM tracks WHERE endTime IS NOT NULL ORDER BY startTime DESC")
    fun observeFinished(): Flow<List<TrackEntity>>

    @Query("DELETE FROM tracks WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface PointDao {
    @Insert
    suspend fun insert(point: TrackPointEntity): Long

    @Query("SELECT * FROM points WHERE trackId = :trackId ORDER BY seq ASC")
    suspend fun getByTrack(trackId: Long): List<TrackPointEntity>

    @Query("SELECT * FROM points WHERE trackId = :trackId ORDER BY seq ASC")
    fun observeByTrack(trackId: Long): Flow<List<TrackPointEntity>>

    @Query("SELECT * FROM points WHERE trackId = :trackId ORDER BY seq DESC LIMIT 1")
    suspend fun getLast(trackId: Long): TrackPointEntity?
}
