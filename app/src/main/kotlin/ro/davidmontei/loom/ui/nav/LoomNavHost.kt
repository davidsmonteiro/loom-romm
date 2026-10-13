package ro.davidmontei.loom.ui.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import kotlinx.serialization.Serializable
import ro.davidmontei.loom.ui.library.LibraryScreen
import ro.davidmontei.loom.ui.onboarding.OnboardingScreen
import ro.davidmontei.loom.ui.queue.QueueScreen
import ro.davidmontei.loom.ui.settings.SettingsScreen

@Serializable data object OnboardingRoute
@Serializable data object LibraryRoute
@Serializable data object QueueRoute
@Serializable data object SettingsRoute

/** Three screens plus first-run onboarding. B (back) always pops; Library is the root. */
@Composable
fun LoomNavHost(nav: NavHostController, onboarded: Boolean, openQueue: Boolean, onQueueOpened: () -> Unit) {
    NavHost(navController = nav, startDestination = if (onboarded) LibraryRoute else OnboardingRoute) {
        composable<OnboardingRoute> {
            OnboardingScreen(onFinished = {
                nav.navigate(LibraryRoute) { popUpTo(OnboardingRoute) { inclusive = true } }
            })
        }
        composable<LibraryRoute> {
            LibraryScreen(
                openQueue = { nav.navigate(QueueRoute) { launchSingleTop = true } },
                openSettings = { nav.navigate(SettingsRoute) { launchSingleTop = true } },
            )
        }
        composable<QueueRoute> { QueueScreen(onBack = { nav.popBackStack() }) }
        composable<SettingsRoute> {
            SettingsScreen(
                onBack = { nav.popBackStack() },
                onRepair = { nav.navigate(OnboardingRoute) { popUpTo(LibraryRoute) { inclusive = true } } },
            )
        }
    }
    LaunchedEffect(openQueue, onboarded) {
        if (openQueue && onboarded) {
            nav.navigate(QueueRoute) { launchSingleTop = true }
            onQueueOpened()
        }
    }
}
