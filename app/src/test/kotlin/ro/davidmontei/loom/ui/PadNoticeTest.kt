package ro.davidmontei.loom.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import ro.davidmontei.loom.ui.second.FOCUS_AUTO_LOCK
import ro.davidmontei.loom.ui.second.FOCUS_BOTTOM
import ro.davidmontei.loom.ui.second.FOCUS_TOP
import ro.davidmontei.loom.ui.second.PadNotice
import ro.davidmontei.loom.ui.second.padNotice

/** What Loom says about AYN's Focus Mode, with Loom on the top screen (the default display) or the bottom one. */
class PadNoticeTest {
    /** Auto-lock gives the pad to the screen last touched: Loom's, once it's touched or opened. */
    @Test fun autoLockSaysNothingOnEitherScreen() {
        assertThat(padNotice(FOCUS_AUTO_LOCK, onDefaultDisplay = true)).isNull()
        assertThat(padNotice(FOCUS_AUTO_LOCK, onDefaultDisplay = false)).isNull()
    }

    @Test fun topScreenSaysNothingWithLoomOnTop() {
        assertThat(padNotice(FOCUS_TOP, onDefaultDisplay = true)).isNull()
    }

    @Test fun topScreenSaysSoWithLoomOnTheBottom() {
        assertThat(padNotice(FOCUS_TOP, onDefaultDisplay = false)).isEqualTo(PadNotice.LOCKED_TO_TOP)
    }

    @Test fun bottomScreenSaysSoWithLoomOnTop() {
        assertThat(padNotice(FOCUS_BOTTOM, onDefaultDisplay = true)).isEqualTo(PadNotice.LOCKED_TO_BOTTOM)
    }

    @Test fun bottomScreenSaysNothingWithLoomOnTheBottom() {
        assertThat(padNotice(FOCUS_BOTTOM, onDefaultDisplay = false)).isNull()
    }

    /** A value AYN adds later says nothing rather than something wrong. */
    @Test fun anUnknownModeSaysNothing() {
        assertThat(padNotice(3, onDefaultDisplay = true)).isNull()
        assertThat(padNotice(3, onDefaultDisplay = false)).isNull()
    }
}
