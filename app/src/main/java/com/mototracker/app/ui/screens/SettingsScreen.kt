package com.mototracker.app.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mototracker.app.di.ServiceLocator

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val settings by ServiceLocator.settingsRepository.settings.collectAsStateWithLifecycle()
    val repo = ServiceLocator.settingsRepository

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text("Настройки", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))

        SectionTitle("Запись")
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("Автопауза на остановках", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Стоишь дольше заданного времени — запись встаёт на паузу сама",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = settings.autoPauseEnabled,
                onCheckedChange = { v -> repo.update { it.copy(autoPauseEnabled = v) } }
            )
        }
        if (settings.autoPauseEnabled) {
            Text(
                "Порог автопаузы: ${settings.autoPauseMinutes} мин",
                style = MaterialTheme.typography.bodyMedium
            )
            Slider(
                value = settings.autoPauseMinutes.toFloat(),
                onValueChange = { v ->
                    repo.update { it.copy(autoPauseMinutes = v.toInt().coerceIn(1, 10)) }
                },
                valueRange = 1f..10f,
                steps = 8
            )
        }

        HorizontalDivider(Modifier.padding(vertical = 12.dp))

        SectionTitle("Фильтр GPS")
        Text(
            "Настроено под регион с плохим приёмом: мусорные точки отбрасываются",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Макс. погрешность точки: ${settings.accuracyThresholdM.toInt()} м",
            style = MaterialTheme.typography.bodyMedium
        )
        Slider(
            value = settings.accuracyThresholdM.toFloat(),
            onValueChange = { v ->
                val snapped = (v / 5).toInt() * 5
                repo.update { it.copy(accuracyThresholdM = snapped.toDouble().coerceIn(10.0, 50.0)) }
            },
            valueRange = 10f..50f,
            steps = 7
        )
        Text(
            "Макс. правдоподобная скорость: ${settings.maxPlausibleSpeedKmh.toInt()} км/ч",
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            "Скачки «быстрее этой скорости» считаются ошибкой GPS",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Slider(
            value = settings.maxPlausibleSpeedKmh.toFloat(),
            onValueChange = { v ->
                val snapped = (v / 10).toInt() * 10
                repo.update { it.copy(maxPlausibleSpeedKmh = snapped.toDouble().coerceIn(150.0, 320.0)) }
            },
            valueRange = 150f..320f,
            steps = 16
        )

        HorizontalDivider(Modifier.padding(vertical = 12.dp))

        SectionTitle("Экран")
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("Держать экран включённым", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Иначе экран гаснет только во время записи",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = settings.keepScreenOn,
                onCheckedChange = { v -> repo.update { it.copy(keepScreenOn = v) } }
            )
        }

        HorizontalDivider(Modifier.padding(vertical = 12.dp))

        SectionTitle("Батарея (важно для HyperOS/MIUI)")
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val ignoring = pm?.isIgnoringBatteryOptimizations(context.packageName) ?: false
        if (ignoring) {
            Text(
                "✓ Оптимизация батареи отключена для приложения",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodyMedium
            )
        } else {
            Button(onClick = {
                try {
                    context.startActivity(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                            .setData(Uri.parse("package:${context.packageName}"))
                    )
                } catch (_: Exception) {
                }
            }) { Text("Разрешить работу в фоне") }
        }
        Spacer(Modifier.height(8.dp))
        Card(shape = RoundedCornerShape(12.dp)) {
            Column(Modifier.padding(12.dp)) {
                Text(
                    "Чтобы Xiaomi не убивала запись на длинном рейде:",
                    style = MaterialTheme.typography.titleSmall
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "1. Настройки → Приложения → Moto Tracker → Автозапуск: ВКЛ\n" +
                        "2. Экономия батареи приложения → «Нет ограничений»\n" +
                        "3. В недавних приложениях зафиксируйте Moto Tracker (долгий тап по карточке → замок)",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
    Spacer(Modifier.height(8.dp))
}
