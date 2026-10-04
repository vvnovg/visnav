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
 * [onMapReady] is called once the first style has loaded.
 */
@Composable
fun MapLibreMap(
    modifier: Modifier,
    styleJson: String,
    onMapReady: (MapLibreMap) -> Unit,
    onUserGesture: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mapView = remember { MapView(context) }
    val currentOnMapReady = rememberUpdatedState(onMapReady)
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
    val firstStyle = remember { booleanArrayOf(true) }
    LaunchedEffect(mapView, styleJson) {
        mapView.getMapAsync { map ->
            map.setStyle(Style.Builder().fromJson(styleJson)) {
                if (firstStyle[0]) {
                    firstStyle[0] = false
                    currentOnMapReady.value(map)
                }
            }
        }
    }

    AndroidView(factory = { mapView }, modifier = modifier)
}
