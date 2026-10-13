package ro.davidmontei.loom.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ro.davidmontei.loom.ui.nav.LibraryRoute
import ro.davidmontei.loom.ui.nav.OnboardingRoute
import ro.davidmontei.loom.ui.nav.QueueRoute
import ro.davidmontei.loom.ui.nav.SettingsRoute
import ro.davidmontei.loom.ui.nav.leaveOnboarding
import ro.davidmontei.loom.ui.nav.pairAgain

/** Loom's routes with empty screens: where pairing again and setup lead. */
@RunWith(AndroidJUnit4::class)
class NavigationTest {
    @get:Rule val rule = createComposeRule()
    private lateinit var nav: NavHostController

    private fun show(start: Any) = rule.setContent {
        nav = rememberNavController()
        NavHost(nav, startDestination = start) {
            composable<OnboardingRoute> {}
            composable<LibraryRoute> {}
            composable<QueueRoute> {}
            composable<SettingsRoute> {}
        }
    }

    /** Found by review: nothing checked that pairing again comes back to the screen that opened it. */
    @Test fun pairingAgainGoesBackToTheScreenItWasOpenedFrom() {
        show(LibraryRoute)
        rule.runOnIdle { nav.navigate(SettingsRoute); nav.pairAgain() }
        rule.runOnIdle {
            assertThat(nav.currentDestination!!.hasRoute<OnboardingRoute>()).isTrue()
            nav.leaveOnboarding(OnboardingRoute(pairAgain = true))
        }
        rule.runOnIdle {
            assertThat(nav.currentDestination!!.hasRoute<SettingsRoute>()).isTrue()
            assertThat(nav.previousBackStackEntry!!.destination.hasRoute<LibraryRoute>()).isTrue()
        }
    }

    @Test fun setupGoesOnToTheLibraryAndIsGone() {
        show(OnboardingRoute())
        rule.runOnIdle { nav.leaveOnboarding(OnboardingRoute()) }
        rule.runOnIdle {
            assertThat(nav.currentDestination!!.hasRoute<LibraryRoute>()).isTrue()
            assertThat(nav.previousBackStackEntry).isNull()
        }
    }
}
