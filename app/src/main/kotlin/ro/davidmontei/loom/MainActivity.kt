package ro.davidmontei.loom

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import ro.davidmontei.loom.data.settings.SettingsStore
import ro.davidmontei.loom.ui.components.FocusRingIndication
import ro.davidmontei.loom.ui.input.GamepadKeys
import ro.davidmontei.loom.ui.nav.LoomNavHost
import ro.davidmontei.loom.ui.theme.LoomColors
import ro.davidmontei.loom.ui.theme.LoomTheme
import javax.inject.Inject

data class AppState(val onboarded: Boolean, val swapConfirmBack: Boolean)

@HiltViewModel
class AppViewModel @Inject constructor(settings: SettingsStore) : ViewModel() {
    /** Null until settings have loaded, so the first frame doesn't flash onboarding. */
    val state: StateFlow<AppState?> = settings.settings
        .map { AppState(it.onboardingComplete && it.server != null, it.swapConfirmBack) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
}

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    companion object {
        /** Opens straight to the download queue (notification taps, the "Downloads" shortcut). */
        const val ACTION_OPEN_QUEUE = "ro.davidmontei.loom.action.OPEN_QUEUE"
    }

    private val app: AppViewModel by viewModels()
    private var openQueue by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        openQueue = intent?.action == ACTION_OPEN_QUEUE
        setContent {
            LoomTheme {
                CompositionLocalProvider(LocalIndication provides FocusRingIndication()) {
                    Box(Modifier.fillMaxSize().background(LoomColors.Background)) {
                        val state = app.state.collectAsStateWithLifecycle().value ?: return@Box
                        val nav = rememberNavController()
                        LoomNavHost(nav, state.onboarded, openQueue, onQueueOpened = { openQueue = false })
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == ACTION_OPEN_QUEUE) openQueue = true
    }

    /** Pad A/B become confirm/back before Compose sees them (see [GamepadKeys]). */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        super.dispatchKeyEvent(GamepadKeys.remapEvent(event, app.state.value?.swapConfirmBack ?: false))
}
