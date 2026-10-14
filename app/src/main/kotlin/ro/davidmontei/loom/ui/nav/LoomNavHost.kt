package ro.davidmontei.loom.ui.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavController
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import kotlinx.serialization.Serializable
import ro.davidmontei.loom.ui.library.LibraryScreen
import ro.davidmontei.loom.ui.onboarding.OnboardingScreen
import ro.davidmontei.loom.ui.queue.QueueScreen
import ro.davidmontei.loom.ui.settings.SettingsScreen

/** First-run setup, or with [pairAgain] just its pairing step, opened on top of the screen that asked for it. */
@Serializable data class OnboardingRoute(val pairAgain: Boolean = false)
@Serializable data object LibraryRoute
/**
 * Opened from a game in the library: [focusJobId] is its download to put focus on, or
 * [focusConflictRomId] the game whose save conflict to put focus on.
 */
@Serializable data class QueueRoute(val focusJobId: Long? = null, val focusConflictRomId: Int? = null)
@Serializable data object SettingsRoute

/** Three screens plus first-run onboarding. B (back) always pops; Library is the root. */
@Composable
fun LoomNavHost(nav: NavHostController, onboarded: Boolean, openQueue: Boolean, onQueueOpened: () -> Unit) {
    val pairAgain = { nav.pairAgain() }
    NavHost(navController = nav, startDestination = if (onboarded) LibraryRoute else OnboardingRoute()) {
        composable<OnboardingRoute> { entry ->
            val route = entry.toRoute<OnboardingRoute>()
            OnboardingScreen(onFinished = { nav.leaveOnboarding(route) })
        }
        composable<LibraryRoute> {
            LibraryScreen(
                openQueue = { jobId, romId -> nav.navigate(QueueRoute(jobId, romId)) { launchSingleTop = true } },
                openSettings = { nav.navigate(SettingsRoute) { launchSingleTop = true } },
                onRepair = pairAgain,
            )
        }
        composable<QueueRoute> { entry ->
            val route = entry.toRoute<QueueRoute>()
            QueueScreen(focusJobId = route.focusJobId, focusConflictRomId = route.focusConflictRomId, onBack = { nav.popBackStack() })
        }
        composable<SettingsRoute> {
            SettingsScreen(onBack = { nav.popBackStack() }, onRepair = pairAgain)
        }
    }
    LaunchedEffect(openQueue, onboarded) {
        if (openQueue && onboarded) {
            nav.navigate(QueueRoute()) { launchSingleTop = true }
            onQueueOpened()
        }
    }
}

/**
 * Settings' "Pair again", and the library's when RomM rejects the token: pairing on top of the
 * current screen. Loom keeps its token until a new one works.
 */
fun NavController.pairAgain() = navigate(OnboardingRoute(pairAgain = true)) { launchSingleTop = true }

/** Setup goes on to the library; pairing again goes back to the screen it was opened from (B, or a new token). */
fun NavController.leaveOnboarding(route: OnboardingRoute) {
    if (route.pairAgain) popBackStack<OnboardingRoute>(inclusive = true)
    else navigate(LibraryRoute) { popUpTo<OnboardingRoute> { inclusive = true } }
}
