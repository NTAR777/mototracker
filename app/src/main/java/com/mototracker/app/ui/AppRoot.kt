package com.mototracker.app.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.mototracker.app.ui.screens.HistoryScreen
import com.mototracker.app.ui.screens.RecordScreen
import com.mototracker.app.ui.screens.SettingsScreen
import com.mototracker.app.ui.screens.SpeedometerScreen
import com.mototracker.app.ui.screens.TrackDetailScreen

object Routes {
    const val RECORD = "record"
    const val HISTORY = "history"
    const val SETTINGS = "settings"
    const val SPEEDO = "speedo"
    const val TRACK = "track/{trackId}"

    fun track(id: Long) = "track/$id"
}

@Composable
fun AppRoot() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = currentRoute in listOf(Routes.RECORD, Routes.HISTORY, Routes.SETTINGS)

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    NavigationBarItem(
                        selected = currentRoute == Routes.RECORD,
                        onClick = { navigateTo(navController, Routes.RECORD) },
                        icon = { Icon(Icons.Filled.Speed, contentDescription = null) },
                        label = { Text("Запись") }
                    )
                    NavigationBarItem(
                        selected = currentRoute == Routes.HISTORY,
                        onClick = { navigateTo(navController, Routes.HISTORY) },
                        icon = { Icon(Icons.Filled.History, contentDescription = null) },
                        label = { Text("История") }
                    )
                    NavigationBarItem(
                        selected = currentRoute == Routes.SETTINGS,
                        onClick = { navigateTo(navController, Routes.SETTINGS) },
                        icon = { Icon(Icons.Filled.Tune, contentDescription = null) },
                        label = { Text("Настройки") }
                    )
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Routes.RECORD,
            modifier = Modifier.padding(padding)
        ) {
            composable(Routes.RECORD) {
                RecordScreen(onOpenSpeedometer = { navController.navigate(Routes.SPEEDO) })
            }
            composable(Routes.HISTORY) {
                HistoryScreen(onOpenTrack = { navController.navigate(Routes.track(it)) })
            }
            composable(Routes.SETTINGS) {
                SettingsScreen()
            }
            composable(Routes.SPEEDO) {
                SpeedometerScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.TRACK) { entry ->
                val id = entry.arguments?.getString("trackId")?.toLongOrNull()
                if (id != null) {
                    TrackDetailScreen(trackId = id, onBack = { navController.popBackStack() })
                }
            }
        }
    }
}

private fun navigateTo(
    navController: androidx.navigation.NavController,
    route: String
) {
    navController.navigate(route) {
        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
