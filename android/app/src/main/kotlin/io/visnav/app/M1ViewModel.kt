package io.visnav.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel

/** Держит M1Controller живым через пересоздание Activity (поворот, смена конфигурации). */
class M1ViewModel(application: Application) : AndroidViewModel(application) {
    val controller = M1Controller(application)

    override fun onCleared() {
        controller.close()
    }
}
