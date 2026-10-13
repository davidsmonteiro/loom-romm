package ro.davidmontei.loom

import android.annotation.SuppressLint
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
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
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
import ro.davidmontei.loom.ui.components.DialogHost
import ro.davidmontei.loom.ui.components.FocusRingIndication
import ro.davidmontei.loom.ui.input.GamepadKeys
import ro.davidmontei.loom.ui.input.TouchModePress
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

        /** Long enough for [ro.davidmontei.loom.ui.components.InitialFocus] to place focus after touch mode ends. */
        private const val FOCUS_SETTLE_MS = 120L
    }

    private val app: AppViewModel by viewModels()
    private var openQueue by mutableStateOf(false)
    private val touchModePress = TouchModePress(
        inTouchMode = { window.decorView.isInTouchMode },
        leaveTouchMode = ::leaveTouchMode,
        later = { window.decorView.postDelayed(it, FOCUS_SETTLE_MS) },
        deliver = ::deliver,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        hideSystemBars()
        openQueue = intent?.action == ACTION_OPEN_QUEUE
        setContent {
            LoomTheme {
                CompositionLocalProvider(LocalIndication provides FocusRingIndication()) {
                    DialogHost {
                        Box(Modifier.fillMaxSize().background(LoomColors.Background)) {
                            val state = app.state.collectAsStateWithLifecycle().value ?: return@Box
                            val nav = rememberNavController()
                            LoomNavHost(nav, state.onboarded, openQueue, onQueueOpened = { openQueue = false })
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == ACTION_OPEN_QUEUE) openQueue = true
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemBars() // permission dialogs and other apps can bring them back
            leaveTouchMode()
        }
    }

    /** Full screen, like the games it installs. A swipe from the edge shows the bars for a moment. */
    private fun hideSystemBars() = WindowCompat.getInsetsController(window, window.decorView).run {
        systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        hide(WindowInsetsCompat.Type.systemBars())
    }

    /**
     * In touch mode Compose focuses nothing, so a pad press would only switch modes and otherwise
     * do nothing. Loom is pad-first, so it leaves touch mode as soon as it can; the screen's
     * InitialFocus then puts focus on its primary element.
     */
    private fun leaveTouchMode() {
        if (window.decorView.isInTouchMode) window.decorView.requestFocusFromTouch()
    }

    /**
     * Pad A/B become confirm/back before Compose sees them (see [GamepadKeys]), and a press made
     * in touch mode waits for focus (see [TouchModePress]).
     *
     * Lint flags these because androidx.core re-declares the platform method as
     * library-restricted; it is the public Activity.dispatchKeyEvent.
     */
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        touchModePress.dispatch(GamepadKeys.remapEvent(event, app.state.value?.swapConfirmBack ?: false))

    @SuppressLint("RestrictedApi")
    private fun deliver(key: KeyEvent): Boolean = super.dispatchKeyEvent(key)
}
