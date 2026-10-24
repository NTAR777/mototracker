package com.mototracker.app.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mototracker.app.data.db.TrackEntity
import com.mototracker.app.data.db.TrackPointEntity
import com.mototracker.app.di.ServiceLocator
import com.mototracker.app.ui.map.MotoMap
import com.mototracker.app.util.Format
import com.mototracker.app.util.GeoUtils
import com.mototracker.app.util.GpxExporter
import com.yandex.mapkit.geometry.Point
import kotlinx.coroutines.launch

data class TrackStats(
    val distanceM: Double,
    val totalTimeSec: Double,
    val movingTimeSec: Double,
    val maxSpeedKmh: Double,
    val maxAltitudeM: Double
) {
    val avgMovingKmh: Double
        get() = if (movingTimeSec > 5.0) distanceM / movingTimeSec * 3.6 else 0.0

    val avgTotalKmh: Double
        get() = if (totalTimeSec > 5.0) distanceM / totalTimeSec * 3.6 else 0.0
}

/** Пересчёт статистики «с нуля» по точкам — самый точный вариант. */
fun computeStats(points: List<TrackPointEntity>): TrackStats {
    var distance = 0.0
    var total = 0.0
    var moving = 0.0
    var maxSpeed = 0.0
    var maxAlt = Double.MIN_VALUE
    if (points.isEmpty()) {
        return TrackStats(0.0, 0.0, 0.0, 0.0, 0.0)
    }
    for (i in points.indices) {
        val p = points[i]
        if (p.speedKmh > maxSpeed) maxSpeed = p.speedKmh
        if (p.altitudeM > maxAlt) maxAlt = p.altitudeM
        if (i == 0) continue
        val prev = points[i - 1]
        if (prev.segment != p.segment) continue // пауза — разрыв
        val dt = (p.timeMs - prev.timeMs) / 1000.0
        if (dt <= 0.0) continue
        val dist = GeoUtils.distanceM(prev.lat, prev.lon, p.lat, p.lon)
        if (dist > 4.0) distance += dist // тот же порог шума, что при записи
        total += minOf(dt, 10.0)
        if (p.speedKmh / 3.6 > 0.7) moving += minOf(dt, 10.0)
    }
    return TrackStats(distance, total, moving, maxSpeed, maxAlt)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackDetailScreen(trackId: Long, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var track by remember { mutableStateOf<TrackEntity?>(null) }
    var points by remember { mutableStateOf<List<TrackPointEntity>>(emptyList()) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var exportMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(trackId) {
        track = ServiceLocator.trackRepository.getTrack(trackId)
        points = ServiceLocator.trackRepository.getPoints(trackId)
    }

    val stats = remember(points) { computeStats(points) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(track?.name ?: "Поездка") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                }
            )
        }
    ) { padding ->
        if (track == null) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator() }
            return@Scaffold
        }

        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Box(Modifier.weight(1f)) {
                MotoMap(
                    modifier = Modifier.fillMaxSize(),
                    points = points,
                    livePoint = null,
                    followUser = false,
                    onUserDragged = {},
                    showStartEndMarkers = true,
                    initialCenter = points.firstOrNull()?.let { Point(it.lat, it.lon) },
                    initialZoom = 13f
                )
            }

            StatsGrid(stats)

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = {
                        scope.launch {
                            val t = track ?: return@launch
                            val result = GpxExporter.export(context, t, points)
                            exportMessage = result.fold(
                                onSuccess = { uri ->
                                    uri?.let { shareGpx(context, it) }
                                    "GPX сохранён в «Загрузки»"
                                },
                                onFailure = { "Ошибка экспорта: ${it.message}" }
                            )
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Filled.Share, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("GPX")
                }
                OutlinedButton(
                    onClick = { showDeleteDialog = true },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Icon(Icons.Filled.Delete, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Удалить")
                }
            }
            exportMessage?.let {
                Text(
                    it,
                    Modifier.padding(horizontal = 16.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(8.dp))
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Удалить поездку?") },
            text = { Text("Маршрут и статистика будут удалены безвозвратно.") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    scope.launch {
                        ServiceLocator.trackRepository.deleteTrack(trackId)
                        onBack()
                    }
                }) { Text("Удалить") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("Отмена") }
            }
        )
    }
}

@Composable
private fun StatsGrid(stats: TrackStats) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            StatCell("Расстояние", Format.distance(stats.distanceM))
            StatCell("Время", Format.duration(stats.totalTimeSec))
            StatCell("В движении", Format.duration(stats.movingTimeSec))
        }
        Spacer(Modifier.height(10.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            StatCell("Ср. движ.", Format.speed(stats.avgMovingKmh))
            StatCell("Ср. общ.", Format.speed(stats.avgTotalKmh))
            StatCell("Макс", Format.speed(stats.maxSpeedKmh))
        }
        Spacer(Modifier.height(10.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            StatCell("Макс. высота", "%.0f м".format(stats.maxAltitudeM))
        }
    }
}

private fun shareGpx(context: Context, uri: Uri) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "application/gpx+xml"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, "Отправить GPX"))
}
