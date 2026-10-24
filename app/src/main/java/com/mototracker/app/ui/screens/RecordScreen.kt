package com.mototracker.app.ui.screens

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.gms.location.LocationServices
import com.mototracker.app.di.ServiceLocator
import com.mototracker.app.service.LocationService
import com.mototracker.app.tracker.LiveState
import com.mototracker.app.tracker.LiveGpsClient
import com.mototracker.app.tracker.TrackRecorder
import com.mototracker.app.tracker.TrackingBus
import com.mototracker.app.tracker.TrackingStatus
import com.mototracker.app.ui.components.StatCell
import com.mototracker.app.ui.map.MotoMap
import com.mototracker.app.util.Format
import com.mototracker.app.util.SpeedGradient
import com.yandex.mapkit.geometry.Point
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

@Composable
fun RecordScreen(onOpenSpeedometer: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? Activity
    val scope = rememberCoroutineScope()
    val state by TrackingBus.state.collectAsStateWithLifecycle()
    val settings by ServiceLocator.settingsRepository.settings.collectAsStateWithLifecycle()

    var hasPermission by remember { mutableStateOf(hasLocationPermission(context)) }
    // Если пользователь выбрал «Приблизительное местоположение» — точный GPS не заработает.
    val coarseOnly = !hasPermission &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED
    var followUser by remember { mutableStateOf(true) }
    var showStopDialog by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        hasPermission = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
    }

    val requestPermissions: () -> Unit = {
        val wanted = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionLauncher.launch(wanted.toTypedArray())
    }

    // Экран не гаснет во время записи (и если включено в настройках — всегда).
    DisposableEffect(activity, settings.keepScreenOn, state.status) {
        val shouldKeep = settings.keepScreenOn || state.status != TrackingStatus.IDLE
        if (activity != null && shouldKeep) {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // После остановки записи — снова включаем UI-поток GPS (сервис уже отдал обновления).
    LaunchedEffect(state.status) {
        if (state.status == TrackingStatus.IDLE) {
            LiveGpsClient.start(context)
        } else {
            LiveGpsClient.stop()
        }
    }

    // Точки текущего трека из БД — источник для линии на карте.
    val trackId = state.trackId
    val pointsFlow = remember(trackId) {
        if (trackId != null) {
            ServiceLocator.trackRepository.observePoints(trackId)
        } else {
            flowOf(emptyList())
        }
    }
    val points by pointsFlow.collectAsStateWithLifecycle(initialValue = emptyList())

    // Центр карты до первого фикс-а — последняя известная позиция.
    var initialCenter by remember { mutableStateOf<Point?>(null) }
    LaunchedEffect(hasPermission) {
        if (!hasPermission) return@LaunchedEffect
        try {
            LocationServices.getFusedLocationProviderClient(context).lastLocation
                .addOnSuccessListener { loc ->
                    if (loc != null) initialCenter = Point(loc.latitude, loc.longitude)
                }
        } catch (_: Exception) {
            // нет сервисов Google — просто не центрируем заранее
        }
    }

    Box(Modifier.fillMaxSize()) {
        MotoMap(
            modifier = Modifier.fillMaxSize(),
            points = points,
            livePoint = state.lastPoint,
            followUser = followUser,
            onUserDragged = { followUser = false },
            initialCenter = initialCenter,
            initialZoom = 16f
        )

        if (!followUser && state.status != TrackingStatus.IDLE) {
            FloatingActionButton(
                onClick = { followUser = true },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(bottom = 148.dp, end = 16.dp)
            ) {
                Icon(Icons.Filled.CenterFocusStrong, contentDescription = "Центрировать")
            }
        }

        Column(Modifier.fillMaxSize()) {
            TopStats(state, settings.accuracyThresholdM, onOpenSpeedometer)
            Spacer(Modifier.weight(1f))
            Controls(
                status = state.status,
                autoPaused = state.autoPaused,
                enabled = hasPermission,
                onStart = {
                    LocationService.start(context)
                    scope.launch { TrackRecorder.startNew() }
                },
                onPause = { TrackRecorder.pause() },
                onResume = { TrackRecorder.resume() },
                onStop = { showStopDialog = true }
            )
        }

        if (!hasPermission) {
            Card(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp),
                shape = RoundedCornerShape(20.dp)
            ) {
                Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        if (coarseOnly) "Нужно точное местоположение" else "Нужен доступ к геолокации",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (coarseOnly) {
                            "Сейчас выдано только «Приблизительное местоположение» — GPS-спидометр и запись с ним не работают. Открой настройки приложения → Разрешения → Геоданные и выбери «Точное местоположение»."
                        } else {
                            "Без него приложение не сможет записывать маршрут и показывать спидометр. В диалоге выбирай «Точное местоположение»."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    if (coarseOnly) {
                        Button(onClick = {
                            try {
                                context.startActivity(
                                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                        .setData(Uri.parse("package:${context.packageName}"))
                                )
                            } catch (_: Exception) {
                            }
                        }) { Text("Открыть настройки") }
                    } else {
                        Button(onClick = requestPermissions) { Text("Разрешить") }
                    }
                }
            }
        }
    }

    if (showStopDialog) {
        AlertDialog(
            onDismissRequest = { showStopDialog = false },
            title = { Text("Завершить поездку?") },
            text = { Text("Маршрут будет сохранён в историю. Остановки (паузы) в статистику не войдут.") },
            confirmButton = {
                TextButton(onClick = {
                    showStopDialog = false
                    TrackRecorder.stop()
                    context.stopService(Intent(context, LocationService::class.java))
                }) { Text("Сохранить и стоп") }
            },
            dismissButton = {
                TextButton(onClick = { showStopDialog = false }) { Text("Отмена") }
            }
        )
    }
}

@Composable
private fun TopStats(
    state: LiveState,
    accuracyThreshold: Double,
    onOpenSpeedometer: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        tonalElevation = 4.dp
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    val hasFix = state.fixAgeMs in 0..6000
                    Text(
                        text = if (hasFix) Format.speedRaw(state.speedKmh) else "—",
                        fontSize = 44.sp,
                        fontWeight = FontWeight.Black,
                        color = Color(SpeedGradient.colorFor(state.speedKmh))
                    )
                    Text(
                        "км/ч",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                GpsBadge(state, accuracyThreshold)
                Spacer(Modifier.width(4.dp))
                IconButton(onClick = onOpenSpeedometer) {
                    Icon(Icons.Filled.Speed, contentDescription = "Полноэкранный спидометр")
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                StatCell("Расстояние", Format.distance(state.distanceM))
                StatCell("Ср. движ.", Format.avg(state.avgMovingKmh))
                StatCell("Ср. общ.", Format.avg(state.avgTotalKmh))
                StatCell("Макс", Format.avg(state.maxSpeedKmh))
            }
            state.gpsError?.let {
                Spacer(Modifier.height(6.dp))
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}

@Composable
private fun GpsBadge(state: LiveState, threshold: Double) {
    val (text, color) = when {
        state.fixAgeMs > 4000L || state.accuracyM > threshold ->
            "Слабый GPS" to MaterialTheme.colorScheme.error
        else -> "±${state.accuracyM.toInt()} м" to MaterialTheme.colorScheme.primary
    }
    Surface(
        shape = RoundedCornerShape(50),
        color = color.copy(alpha = 0.15f)
    ) {
        Text(
            text,
            Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            color = color,
            style = MaterialTheme.typography.labelMedium
        )
    }
}

@Composable
private fun Controls(
    status: TrackingStatus,
    autoPaused: Boolean,
    enabled: Boolean,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        tonalElevation = 6.dp
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            when (status) {
                TrackingStatus.IDLE, TrackingStatus.STANDBY -> {
                    Button(
                        onClick = onStart,
                        enabled = enabled,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(64.dp),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("СТАРТ", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    }
                }

                TrackingStatus.RECORDING -> {
                    Row {
                        Button(
                            onClick = onPause,
                            modifier = Modifier
                                .weight(1f)
                                .height(64.dp),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Icon(Icons.Filled.Pause, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("ПАУЗА", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.width(12.dp))
                        Button(
                            onClick = onStop,
                            modifier = Modifier
                                .weight(1f)
                                .height(64.dp),
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            )
                        ) {
                            Icon(Icons.Filled.Stop, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("СТОП", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                TrackingStatus.PAUSED -> {
                    Row {
                        Button(
                            onClick = onResume,
                            modifier = Modifier
                                .weight(1f)
                                .height(64.dp),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("ПРОДОЛЖИТЬ", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.width(12.dp))
                        OutlinedButton(
                            onClick = onStop,
                            modifier = Modifier
                                .weight(1f)
                                .height(64.dp),
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Icon(Icons.Filled.Stop, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("СТОП", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    if (autoPaused) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Автопауза: поехали — продолжим автоматически",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

private fun hasLocationPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED
