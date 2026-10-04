package io.visnav.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import org.maplibre.android.MapLibre

class MainActivity : ComponentActivity() {
    private val permissions = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    )
    private val granted = mutableStateOf(false)
    private val request = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        granted.value = result[Manifest.permission.CAMERA] == true &&
            result[Manifest.permission.ACCESS_FINE_LOCATION] == true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        granted.value = listOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION).all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
        if (!granted.value) request.launch(permissions)
        MapLibre.getInstance(applicationContext)
        // The app has no network permissions (removed in the manifest). With a fixed connectivity state
        // MapLibre never calls ConnectivityManager.getActiveNetworkInfo() (MapView init, CONNECTIVITY_CHANGE
        // receiver), which would throw SecurityException without ACCESS_NETWORK_STATE.
        MapLibre.setConnected(false)
        setContent {
            val viewModel: M1ViewModel = viewModel()
            // Обе вкладки работают с одним контроллером (сессия записи не зависит от вкладки).
            var tab by rememberSaveable { mutableIntStateOf(0) }
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize()) {
                        TabRow(selectedTabIndex = tab) {
                            TABS.forEachIndexed { i, title ->
                                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(title) })
                            }
                        }
                        Box(Modifier.weight(1f)) {
                            when (tab) {
                                0 -> NavScreen(viewModel.controller, granted.value)
                                else -> M1Screen(viewModel.controller, granted.value)
                            }
                        }
                    }
                }
            }
        }
    }

    private companion object {
        val TABS = listOf("Навигация", "Отладка")
    }
}
