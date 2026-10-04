package io.visnav.app

import android.content.ComponentCallbacks2
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style

/**
 * MapLibre [MapView] inside Compose. Forwards lifecycle events and low-memory callbacks,
 * reloads the style whenever [styleJson] changes, disables the logo, the attribution button
 * (both lead to the internet) and tilt, and reports user gestures to [onUserGesture].
 *
 * [onMapReady] is called once, after the first style has loaded (camera setup).
 * [onStyleLoaded] is called after every style load, including the reload on a day/night switch.
 * A reload replaces all sources and layers, so callers must look up GeoJSON sources from the
 * [Style] passed here (or `map.style`) each time and must not cache source objects across loads.
 *
 * `onSaveInstanceState` is deliberately not forwarded: after recreation the follow camera
 * re-centres the map, so there is no camera state worth restoring.
 */
@Composable
fun MapLibreMap(
    modifier: Modifier,
    styleJson: String,
    onMapReady: (MapLibreMap) -> Unit,
    onStyleLoaded: (MapLibreMap, Style) -> Unit,
    onUserGesture: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    // One MapView per lifecycle owner: a destroyed view is never handed to a new owner's onCreate.
    val mapView = remember(lifecycleOwner) { MapView(context) }
    val currentOnMapReady = rememberUpdatedState(onMapReady)
    val currentOnStyleLoaded = rememberUpdatedState(onStyleLoaded)
    val currentOnUserGesture = rememberUpdatedState(onUserGesture)

    DisposableEffect(lifecycleOwner, mapView) {
        // A new observer is replayed the events up to the current state (ON_CREATE, ON_START, ...).
        var forwarded = Lifecycle.State.INITIALIZED
        val observer = LifecycleEventObserver { _, event ->
            if (forwarded == Lifecycle.State.DESTROYED) return@LifecycleEventObserver
            when (event) {
                Lifecycle.Event.ON_CREATE -> mapView.onCreate(null)
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                Lifecycle.Event.ON_DESTROY -> mapView.onDestroy()
                Lifecycle.Event.ON_ANY -> Unit
            }
            forwarded = event.targetState
        }
        val memory = object : ComponentCallbacks2 {
            override fun onConfigurationChanged(newConfig: Configuration) = Unit
            override fun onLowMemory() = mapView.onLowMemory()
            override fun onTrimMemory(level: Int) = Unit
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        context.registerComponentCallbacks(memory)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            context.unregisterComponentCallbacks(memory)
            // Left the composition while the owner is still alive: finish the MapView lifecycle.
            if (forwarded.isAtLeast(Lifecycle.State.RESUMED)) mapView.onPause()
            if (forwarded.isAtLeast(Lifecycle.State.STARTED)) mapView.onStop()
            if (forwarded.isAtLeast(Lifecycle.State.CREATED)) mapView.onDestroy()
        }
    }

    LaunchedEffect(mapView) {
        mapView.getMapAsync { map ->
            map.uiSettings.isLogoEnabled = false
            map.uiSettings.isAttributionEnabled = false
            map.uiSettings.isTiltGesturesEnabled = false
            map.addOnCameraMoveStartedListener { reason ->
                if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) {
                    currentOnUserGesture.value()
                }
            }
        }
    }

    // Style reload on every styleJson change (day <-> night); onMapReady after the first load only.
    val firstStyle = remember(mapView) { booleanArrayOf(true) }
    LaunchedEffect(mapView, styleJson) {
        mapView.getMapAsync { map ->
            map.setStyle(Style.Builder().fromJson(styleJson)) { style ->
                if (firstStyle[0]) {
                    firstStyle[0] = false
                    currentOnMapReady.value(map)
                }
                currentOnStyleLoaded.value(map, style)
            }
        }
    }

    AndroidView(factory = { mapView }, modifier = modifier)
}
