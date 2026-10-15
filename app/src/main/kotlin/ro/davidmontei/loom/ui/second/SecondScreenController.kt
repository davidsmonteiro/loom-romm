package ro.davidmontei.loom.ui.second

import android.app.Activity
import android.content.ContentResolver
import android.database.ContentObserver
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Display
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Where the second screen's panel is drawn: a window on another display. */
interface SecondScreenWindow {
    /** Shows the panel on [display]; false when it couldn't be (the display went meanwhile). [onGone]: the system took it away. */
    fun show(display: Display, onGone: () -> Unit): Boolean

    fun dismiss()
}

/**
 * Shows the panel on a second screen while Loom is in front, and only then. A presentation isn't
 * tied to the activity: left alone, it would stay over Cocoon's bottom screen, or a game's, once
 * Loom has gone. So it goes when Loom stops (Home, a game launched), when another activity takes
 * the top, even on the other screen, and when its screen turns off or goes, and comes back as
 * these undo. It is never on the screen Loom itself is on, and not while [enabled] is off.
 *
 * It also follows AYN's Focus Mode. While it gives the pad to the screen Loom isn't on, Loom says
 * so in place of its hints; and with the pad on the bottom screen, the panel goes, so whatever the
 * pad works there is in view.
 *
 * Observe it from the activity's lifecycle; the activity passes on [onTopResumedChanged] and
 * [onConfigurationChanged] (a move to the other screen).
 */
class SecondScreenController(
    activity: Activity,
    private val holder: CompanionHolder,
    private val enabled: StateFlow<Boolean>,
    private val window: SecondScreenWindow,
    /** The display Loom is on. */
    private val ownDisplay: () -> Int = { runCatching { activity.display?.displayId }.getOrNull() ?: Display.DEFAULT_DISPLAY },
    private val displays: DisplayManager = activity.getSystemService(DisplayManager::class.java),
    /** The displays a presentation can go on, in the system's order. */
    private val presentationDisplays: () -> List<Display> = { displays.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).toList() },
    /** Where AYN's Focus Mode is read from. */
    private val resolver: ContentResolver = activity.contentResolver,
) : DefaultLifecycleObserver {
    private val listener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = update()
        override fun onDisplayRemoved(displayId: Int) = update()
        override fun onDisplayChanged(displayId: Int) = update()
    }
    private val focusMode = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) = update()
    }
    private var started = false
    private var topResumed = false
    private var watching: Job? = null
    private var shownOn: Int? = null

    override fun onStart(owner: LifecycleOwner) {
        started = true
        displays.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
        resolver.registerContentObserver(Settings.System.getUriFor(AYN_FOCUS_LOCK), false, focusMode)
        watching = owner.lifecycleScope.launch { enabled.collect { update() } }
        update()
    }

    override fun onStop(owner: LifecycleOwner) {
        started = false
        displays.unregisterDisplayListener(listener)
        resolver.unregisterContentObserver(focusMode)
        watching?.cancel()
        watching = null
        update()
    }

    /** Whether Loom is the top resumed activity: with a screen each, another app's can be resumed beside it. */
    fun onTopResumedChanged(isTop: Boolean) {
        topResumed = isTop
        update()
    }

    fun onConfigurationChanged() = update()

    private fun update() {
        val own = ownDisplay()
        val notice = padNotice(readFocusLock(resolver), onDefaultDisplay = own == Display.DEFAULT_DISPLAY)
        holder.setPadNotice(notice)
        // With the pad on the bottom screen, what it works there (Cocoon, a game) must be in view, not under the panel.
        val target = if (!started || !topResumed || notice == PadNotice.LOCKED_TO_BOTTOM) null
            else pickSecondScreen(own, presentationDisplays().map { it.toCandidate() }, enabled.value)
        if (target == shownOn) return
        if (shownOn != null) {
            shownOn = null
            window.dismiss()
        }
        val display = target?.let(displays::getDisplay)
        if (display != null && window.show(display) { if (shownOn == display.displayId) { shownOn = null; holder.setPresenting(false) } }) {
            shownOn = display.displayId
        }
        holder.setPresenting(shownOn != null)
    }
}
