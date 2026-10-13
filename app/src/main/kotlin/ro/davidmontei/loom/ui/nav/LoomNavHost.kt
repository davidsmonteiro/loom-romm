package ro.davidmontei.loom.ui.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
/** [focusJobId]: the job to put focus on, when opened from that game in the library. */
@Serializable data class QueueRoute(val focusJobId: Long? = null)
@Serializable data object SettingsRoute

/** Three screens plus first-run onboarding. B (back) always pops; Library is the root. */
@Composable
fun LoomNavHost(nav: NavHostController, onboarded: Boolean, openQueue: Boolean, onQueueOpened: () -> Unit) {
    // Settings' "Pair again", and the library's when RomM rejects the token. Pairing again keeps the
    // current token until a new one works; B, or that new token, comes back to the screen it was opened from.
    val pairAgain = { nav.navigate(OnboardingRoute(pairAgain = true)) { launchSingleTop = true } }
    NavHost(navController = nav, startDestination = if (onboarded) LibraryRoute else OnboardingRoute()) {
        composable<OnboardingRoute> { entry ->
            val route = entry.toRoute<OnboardingRoute>()
            OnboardingScreen(onFinished = {
                if (route.pairAgain) nav.popBackStack<OnboardingRoute>(inclusive = true)
                else nav.navigate(LibraryRoute) { popUpTo<OnboardingRoute> { inclusive = true } }
            })
        }
        composable<LibraryRoute> {
            LibraryScreen(
                openQueue = { jobId -> nav.navigate(QueueRoute(jobId)) { launchSingleTop = true } },
                openSettings = { nav.navigate(SettingsRoute) { launchSingleTop = true } },
                onRepair = pairAgain,
            )
        }
        composable<QueueRoute> { entry ->
            QueueScreen(focusJobId = entry.toRoute<QueueRoute>().focusJobId, onBack = { nav.popBackStack() })
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
