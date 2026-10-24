package com.mototracker.app.ui.map

import android.graphics.PointF
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.mototracker.app.R
import com.mototracker.app.data.db.TrackPointEntity
import com.mototracker.app.tracker.LivePoint
import com.mototracker.app.util.SpeedGradient
import com.yandex.mapkit.animation.Animation
import com.yandex.mapkit.geometry.Point
import com.yandex.mapkit.geometry.Polyline
import com.yandex.mapkit.map.CameraPosition
import com.yandex.mapkit.map.CameraUpdateReason
import com.yandex.mapkit.map.IconStyle
import com.yandex.mapkit.map.MapInputListener
import com.yandex.mapkit.map.MapObjectCollection
import com.yandex.mapkit.map.PlacemarkMapObject
import com.yandex.mapkit.map.PolylineMapObject
import com.yandex.mapkit.map.RotationType
import com.yandex.mapkit.mapview.MapView
import com.yandex.runtime.image.ImageProvider
import kotlin.math.ceil

/**
 * Карта Яндекса с маршрутом, окрашенным по скорости (плавный градиент),
 * стрелкой текущей позиции и режимом слежения камеры.
 */
@Composable
fun MotoMap(
    modifier: Modifier,
    points: List<TrackPointEntity>,
    livePoint: LivePoint?,
    followUser: Boolean,
    onUserDragged: () -> Unit,
    showStartEndMarkers: Boolean = false,
    initialCenter: Point? = null,
    initialZoom: Float = 14f
) {
    val context = LocalContext.current
    val mapView = remember { MapView(context) }
    val map = remember { mapView.mapWindow.map }

    DisposableEffect(Unit) {
        mapView.onStart()
        onDispose { mapView.onStop() }
    }

    // Реакция на жесты пользователя: выключаем слежение, чтобы не драться за камеру.
    DisposableEffect(Unit) {
        val listener = object : MapInputListener {
            override fun onCameraPositionChanged(
                map: com.yandex.mapkit.map.Map,
                cameraPosition: CameraPosition,
                cameraUpdateReason: CameraUpdateReason,
                finished: Boolean
            ) {
                if (cameraUpdateReason == CameraUpdateReason.GESTURES) onUserDragged()
            }

            override fun onMapTap(map: com.yandex.mapkit.map.Map, point: Point) = Unit

            override fun onMapLongTap(map: com.yandex.mapkit.map.Map, point: Point) = Unit
        }
        map.addCameraInputListener(listener)
        onDispose { map.removeCameraInputListener(listener) }
    }

    var lineObj by remember { mutableStateOf<PolylineMapObject?>(null) }
    var markerObj by remember { mutableStateOf<PlacemarkMapObject?>(null) }
    var startObj by remember { mutableStateOf<PlacemarkMapObject?>(null) }
    var endObj by remember { mutableStateOf<PlacemarkMapObject?>(null) }

    // Маршрут: пересобираем линию на каждую новую точку (для MVP этого достаточно).
    LaunchedEffect(points) {
        val collection: MapObjectCollection = map.mapObjects
        lineObj?.let { collection.remove(it) }
        lineObj = null

        val display = decimate(points, 4000)
        if (display.size >= 2) {
            val polyline = Polyline(display.map { Point(it.lat, it.lon) })
            val obj = collection.addPolyline(polyline)
            obj.strokeWidth = 6f
            obj.outlineWidth = 8f
            obj.outlineColor = 0xF0101418.toInt()
            val colors = ArrayList<Int>(display.size - 1)
            for (i in 0 until display.size - 1) {
                colors.add(SpeedGradient.colorFor((display[i].speedKmh + display[i + 1].speedKmh) / 2.0))
            }
            obj.strokeColors = colors
            lineObj = obj
        }
    }

    // Маркеры старта/финиша (экран истории).
    LaunchedEffect(showStartEndMarkers, points) {
        startObj?.let { map.mapObjects.remove(it) }
        endObj?.let { map.mapObjects.remove(it) }
        startObj = null
        endObj = null
        if (showStartEndMarkers && points.isNotEmpty()) {
            startObj = map.mapObjects.addPlacemark(Point(points.first().lat, points.first().lon)).apply {
                setIcon(ImageProvider.fromResource(context, R.drawable.ic_point_start))
                zIndex = 20f
            }
            endObj = map.mapObjects.addPlacemark(Point(points.last().lat, points.last().lon)).apply {
                setIcon(ImageProvider.fromResource(context, R.drawable.ic_point_end))
                zIndex = 20f
            }
        }
    }

    // Стрелка текущей позиции.
    LaunchedEffect(livePoint) {
        val lp = livePoint ?: return@LaunchedEffect
        if (markerObj == null) {
            val placemark = map.mapObjects.addPlacemark(Point(lp.lat, lp.lon))
            placemark.setIcon(ImageProvider.fromResource(context, R.drawable.ic_user_arrow))
            placemark.setIconStyle(
                IconStyle().apply {
                    rotationType = RotationType.ROTATE
                    anchor = PointF(0.5f, 0.5f)
                }
            )
            placemark.zIndex = 30f
            markerObj = placemark
        }
        markerObj?.geometry = Point(lp.lat, lp.lon)
        markerObj?.rotationAngle = lp.bearingDeg.toFloat()
    }

    // Первичное центрирование.
    var centered by remember { mutableStateOf(false) }
    LaunchedEffect(initialCenter, livePoint) {
        if (centered) return@LaunchedEffect
        val target = livePoint?.let { Point(it.lat, it.lon) } ?: initialCenter
        if (target != null) {
            map.move(CameraPosition(target, initialZoom, 0f, 0f))
            centered = true
        }
    }

    // Слежение камеры за райдером.
    LaunchedEffect(livePoint, followUser) {
        val lp = livePoint ?: return@LaunchedEffect
        if (followUser) {
            val zoom = map.cameraPosition.zoom.coerceIn(13f, 18f)
            map.move(
                CameraPosition(Point(lp.lat, lp.lon), zoom, 0f, 0f),
                Animation(Animation.Type.SMOOTH, 0.5f),
                null
            )
        }
    }

    AndroidView(modifier = modifier, factory = { mapView })
}

/** Режем длинные треки для отображения, чтобы не грузить карту тысячами точек. */
private fun decimate(points: List<TrackPointEntity>, maxPoints: Int): List<TrackPointEntity> {
    if (points.size <= maxPoints) return points
    val step = ceil(points.size / maxPoints.toDouble()).toInt()
    val out = ArrayList<TrackPointEntity>(maxPoints + 1)
    var i = 0
    while (i < points.size) {
        out.add(points[i])
        i += step
    }
    if (out.lastOrNull() !== points.last()) out.add(points.last())
    return out
}
