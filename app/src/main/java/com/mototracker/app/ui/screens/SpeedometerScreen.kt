package com.mototracker.app.ui.screens

import android.app.Activity
import android.view.WindowManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mototracker.app.tracker.TrackingBus
import com.mototracker.app.tracker.TrackingStatus
import com.mototracker.app.util.Format
import com.mototracker.app.util.SpeedGradient

/** Полноэкранный GPS-спидометр. */
@Composable
fun SpeedometerScreen(onBack: () -> Unit) {
    val state by TrackingBus.state.collectAsStateWithLifecycle()
    val activity = LocalContext.current as? Activity

    DisposableEffect(activity) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    val hasFix = state.fixAgeMs in 0..4000

    Column(
        Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
            }
            Spacer(Modifier.weight(1f))
            Text(
                when (state.status) {
                    TrackingStatus.RECORDING -> "Запись"
                    TrackingStatus.PAUSED -> if (state.autoPaused) "Автопауза" else "Пауза"
                    TrackingStatus.STANDBY -> "Готов"
                    TrackingStatus.IDLE -> "Без записи"
                },
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.width(48.dp))
        }

        Spacer(Modifier.weight(1f))

        Text(
            text = if (hasFix) Format.speedRaw(state.speedKmh) else "—",
            fontSize = 140.sp,
            fontWeight = FontWeight.Black,
            color = Color(SpeedGradient.colorFor(state.speedKmh))
        )
        Text(
            "км/ч",
            fontSize = 28.sp,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
        )

        Spacer(Modifier.height(16.dp))
        AssistChip(
            onClick = {},
            label = {
                Text(
                    if (hasFix) "GPS ±${state.accuracyM.coerceAtMost(99.0).toInt()} м"
                    else "Нет сигнала GPS"
                )
            }
        )

        Spacer(Modifier.weight(1f))

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(Format.speed(state.maxSpeedKmh), style = MaterialTheme.typography.titleLarge)
                Text("макс", style = MaterialTheme.typography.labelMedium)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(Format.distance(state.distanceM), style = MaterialTheme.typography.titleLarge)
                Text("расстояние", style = MaterialTheme.typography.labelMedium)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(Format.speed(state.avgMovingKmh), style = MaterialTheme.typography.titleLarge)
                Text("средняя", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}
