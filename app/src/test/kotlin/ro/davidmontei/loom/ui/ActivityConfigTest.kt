package ro.davidmontei.loom.ui

import android.content.ComponentName
import android.content.Context
import android.content.pm.ActivityInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import ro.davidmontei.loom.MainActivity

@RunWith(AndroidJUnit4::class)
class ActivityConfigTest {
    /**
     * Cocoon and AYN's screen swap move Loom between the Thor's two screens, which differ in size and
     * shape; a phone rotates. Loom handles those changes itself rather than being recreated, so it
     * keeps its screen, focus and open dialog, and lays them out again for the new size. A pad or
     * keyboard connecting already was.
     */
    @Test fun movingBetweenScreensDoesNotRecreateLoom() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val handled = context.packageManager.getActivityInfo(ComponentName(context, MainActivity::class.java), 0).configChanges
        val expected = ActivityInfo.CONFIG_KEYBOARD or ActivityInfo.CONFIG_KEYBOARD_HIDDEN or ActivityInfo.CONFIG_NAVIGATION or
            ActivityInfo.CONFIG_ORIENTATION or ActivityInfo.CONFIG_SCREEN_SIZE or ActivityInfo.CONFIG_SMALLEST_SCREEN_SIZE or
            ActivityInfo.CONFIG_SCREEN_LAYOUT or ActivityInfo.CONFIG_DENSITY
        assertThat(handled and expected).isEqualTo(expected)
    }
}
