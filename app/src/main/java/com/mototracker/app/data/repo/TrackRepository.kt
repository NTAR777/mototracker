package com.mototracker.app.data.repo

import com.mototracker.app.data.db.PointDao
import com.mototracker.app.data.db.TrackDao
import com.mototracker.app.data.db.TrackEntity
import com.mototracker.app.data.db.TrackPointEntity
import kotlinx.coroutines.flow.Flow

class TrackRepository(
    private val trackDao: TrackDao,
    private val pointDao: PointDao
) {
    suspend fun createTrack(name: String, startTime: Long): Long =
        trackDao.insert(TrackEntity(name = name, startTime = startTime))

    suspend fun getOpenTrack(): TrackEntity? = trackDao.getOpenTrack()

    suspend fun getTrack(id: Long): TrackEntity? = trackDao.getById(id)

    suspend fun getLastPoint(trackId: Long): TrackPointEntity? = pointDao.getLast(trackId)

    fun observeFinishedTracks(): Flow<List<TrackEntity>> = trackDao.observeFinished()

    fun observePoints(trackId: Long): Flow<List<TrackPointEntity>> = pointDao.observeByTrack(trackId)

    suspend fun getPoints(trackId: Long): List<TrackPointEntity> = pointDao.getByTrack(trackId)

    suspend fun insertPoint(point: TrackPointEntity) {
        pointDao.insert(point)
    }

    suspend fun updateTrack(track: TrackEntity) {
        trackDao.update(track)
    }

    suspend fun deleteTrack(id: Long) {
        trackDao.delete(id)
    }
}
