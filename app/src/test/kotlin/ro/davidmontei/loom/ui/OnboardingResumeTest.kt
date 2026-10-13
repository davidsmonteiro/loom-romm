package ro.davidmontei.loom.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import ro.davidmontei.loom.ui.onboarding.OnboardingStep
import ro.davidmontei.loom.ui.onboarding.resumeStep

/** Found on the Thor: a restart after pairing began setup again at Welcome, heading for a second pairing. */
class OnboardingResumeTest {
    @Test fun freshInstallStartsAtWelcome() {
        assertThat(resumeStep(hasServer = false, paired = false, hasAllFilesAccess = false, hasRomsRoot = false)).isEqualTo(OnboardingStep.WELCOME)
    }

    @Test fun knownServerWithoutTokenGoesToTheCode() {
        assertThat(resumeStep(hasServer = true, paired = false, hasAllFilesAccess = true, hasRomsRoot = true)).isEqualTo(OnboardingStep.PAIR)
    }

    @Test fun pairedSetupPicksUpAtTheFirstMissingPermissionOrFolder() {
        assertThat(resumeStep(hasServer = true, paired = true, hasAllFilesAccess = false, hasRomsRoot = false)).isEqualTo(OnboardingStep.STORAGE)
        assertThat(resumeStep(hasServer = true, paired = true, hasAllFilesAccess = true, hasRomsRoot = false)).isEqualTo(OnboardingStep.ROMS_ROOT)
    }

    @Test fun rePairingWithEverythingInPlaceSkipsToTheLastSteps() {
        assertThat(resumeStep(hasServer = true, paired = true, hasAllFilesAccess = true, hasRomsRoot = true)).isEqualTo(OnboardingStep.BACKGROUND)
    }
}
