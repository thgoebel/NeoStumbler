package xyz.malkki.neostumbler.ui.screens

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.gestures.MoveGestureDetector
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.plugins.annotation.FillManager
import org.maplibre.android.plugins.annotation.FillOptions
import xyz.malkki.neostumbler.R
import xyz.malkki.neostumbler.extensions.checkMissingPermissions
import xyz.malkki.neostumbler.ui.composables.KeepScreenOn
import xyz.malkki.neostumbler.ui.composables.PermissionsDialog
import xyz.malkki.neostumbler.ui.viewmodel.MapViewModel

private val HEAT_LOW = ColorUtils.setAlphaComponent(0xd278ff, 120)
private val HEAT_HIGH = ColorUtils.setAlphaComponent(0xaa00ff, 120)

@Composable
fun MapScreen(mapViewModel: MapViewModel = viewModel()) {
    val context = LocalContext.current

    val lifecycle = LocalLifecycleOwner.current.lifecycle

    val showPermissionDialog = rememberSaveable {
        mutableStateOf(false)
    }

    val trackMyLocation = rememberSaveable {
        mutableStateOf(false)
    }

    val fillManager = remember {
        mutableStateOf<FillManager?>(null)
    }

    val latestReportPosition = mapViewModel.latestReportPosition.collectAsState(initial = null)

    val heatMapTiles = mapViewModel.heatMapTiles.collectAsState(initial = emptyList())

    val myLocation = mapViewModel.myLocation.collectAsStateWithLifecycle(initialValue = null, minActiveState = Lifecycle.State.RESUMED)

    if (showPermissionDialog.value) {
        PermissionsDialog(
            missingPermissions = listOf(Manifest.permission.ACCESS_FINE_LOCATION),
            permissionRationales = mapOf(
                Manifest.permission.ACCESS_FINE_LOCATION to stringResource(R.string.permission_rationale_location_map)
            ),
            onPermissionsGranted = {
                showPermissionDialog.value = false

                val hasPermission = context.checkMissingPermissions(Manifest.permission.ACCESS_COARSE_LOCATION).isEmpty()

                mapViewModel.setShowMyLocation(hasPermission)
                trackMyLocation.value = hasPermission
            }
        )
    }

    KeepScreenOn()

    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                MapLibre.getInstance(context)

                val mapView = LifecycleAwareMap(context)
                mapView.getMapAsync { map ->
                    val styleBuilder = Style.Builder()
                        .fromJson(context.assets.open("style.json").use { it.readBytes().decodeToString() })

                    map.setStyle(styleBuilder)

                    map.addOnMoveListener(object : MapLibreMap.OnMoveListener {
                        override fun onMoveBegin(p0: MoveGestureDetector) {
                            trackMyLocation.value = false
                        }

                        override fun onMove(p0: MoveGestureDetector) {}

                        override fun onMoveEnd(p0: MoveGestureDetector) {}
                    })

                    map.setMinZoomPreference(3.0)
                    map.setMaxZoomPreference(16.0)

                    map.uiSettings.isLogoEnabled = false
                    map.uiSettings.isAttributionEnabled = false

                    map.addOnCameraMoveListener(object : MapLibreMap.OnCameraMoveListener {
                        override fun onCameraMove() {
                            val mapCenter = xyz.malkki.neostumbler.common.LatLng(map.cameraPosition.target!!.latitude, map.cameraPosition.target!!.longitude)

                            mapViewModel.setMapCenter(mapCenter)
                            mapViewModel.setZoom(map.cameraPosition.zoom)

                            mapViewModel.setMapBounds(
                                minLatitude = map.projection.visibleRegion.latLngBounds.latitudeSouth,
                                maxLatitude = map.projection.visibleRegion.latLngBounds.latitudeNorth,
                                minLongitude = map.projection.visibleRegion.latLngBounds.longitudeWest,
                                maxLongitude = map.projection.visibleRegion.latLngBounds.longitudeEast
                            )
                        }
                    })

                    map.locationComponent.activateLocationComponent(
                        LocationComponentActivationOptions.builder(context, map.style!!)
                            //Set location engine to null, because we provide locations by ourself
                            .locationEngine(null)
                            .useDefaultLocationEngine(false)
                            .build()
                    )
                    @SuppressLint("MissingPermission")
                    map.locationComponent.isLocationComponentEnabled = true

                    fillManager.value = FillManager(mapView, map, map.style!!)
                }
                //TODO: set HTTP client for the map with HttpRequestUtil

                mapView
            },
            update = { mapView ->
                mapView.lifecycle = lifecycle

                mapView.getMapAsync { map ->
                    if (map.cameraPosition.target == null || (map.cameraPosition.target?.latitude == 0.0 && map.cameraPosition.target?.longitude == 0.0)) {
                        if (latestReportPosition.value != null) {
                            map.cameraPosition = CameraPosition.Builder()
                                .target(latestReportPosition.value?.let { LatLng(it.latitude, it.longitude) })
                                .zoom(10.0)
                                .build()
                        } else {
                            map.cameraPosition = CameraPosition.Builder()
                                .target(LatLng(mapViewModel.mapCenter.value.latitude, mapViewModel.mapCenter.value.longitude))
                                .zoom(mapViewModel.zoom.value)
                                .build()
                        }
                    }

                    if (myLocation.value != null) {
                        map.locationComponent.forceLocationUpdate(myLocation.value!!.location)

                        if (trackMyLocation.value) {
                            map.cameraPosition = CameraPosition.Builder().target(LatLng(myLocation.value!!.location.latitude, myLocation.value!!.location.longitude)).build()
                        }
                    }
                }

                fillManager.value?.let {
                    it.deleteAll()

                    it.create(createHeatMapFill(heatMapTiles.value))
                }
            },
            onRelease = { view ->
                view.lifecycle = null
            }
        )

        Box(
            modifier = Modifier.fillMaxSize().padding(16.dp)
        ) {
            Text(
                modifier = Modifier.align(Alignment.BottomStart),
                text = stringResource(R.string.openstreetmap_attribution),
                fontSize = 10.sp
            )

            FilledIconButton(
                modifier = Modifier
                    .size(48.dp)
                    .align(Alignment.BottomEnd),
                onClick = {
                    if (context.checkMissingPermissions(Manifest.permission.ACCESS_FINE_LOCATION).isEmpty()) {
                        mapViewModel.setShowMyLocation(true)
                        trackMyLocation.value = true
                    } else {
                        showPermissionDialog.value = true
                    }
                }
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.my_location_24),
                    contentDescription = stringResource(id = R.string.show_my_location)
                )
            }
        }
    }
}

private fun createHeatMapFill(tiles: Collection<MapViewModel.HeatMapTile>): List<FillOptions> {
    return tiles.map { tile ->
        val color = ColorUtils.blendARGB(HEAT_LOW, HEAT_HIGH, tile.heatPct)

        FillOptions()
            .withFillColor(org.maplibre.android.utils.ColorUtils.colorToRgbaString(color))
            .withFillOutlineColor(org.maplibre.android.utils.ColorUtils.colorToRgbaString(ColorUtils.setAlphaComponent(0, 0)))
            .withLatLngs(listOf(tile.outline.map {
                LatLng(it.latitude, it.longitude)
            }))
    }
}

private class LifecycleAwareMap(context: Context) : MapView(context) {
    var lifecycle: Lifecycle? = null
        set(value) {
            field?.removeObserver(observer)
            value?.addObserver(observer)
            field = value
        }

    private val observer = LifecycleEventObserver { source, event ->
        when (event) {
            Lifecycle.Event.ON_RESUME -> {
                onResume()
            }
            Lifecycle.Event.ON_PAUSE -> {
                onPause()
            }
            Lifecycle.Event.ON_START -> {
                onStart()
            }
            Lifecycle.Event.ON_STOP -> {
                onStop()
            }
            Lifecycle.Event.ON_DESTROY -> {
                onDestroy()
            }
            else -> {}
        }
    }
}
