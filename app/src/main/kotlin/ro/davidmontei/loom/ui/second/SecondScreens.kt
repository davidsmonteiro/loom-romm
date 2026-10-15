package ro.davidmontei.loom.ui.second

import android.content.ContentResolver
import android.provider.Settings
import android.view.Display

/** A display as [pickSecondScreen] weighs it. */
data class DisplayCandidate(val id: Int, val flags: Int, val state: Int, val valid: Boolean)

fun Display.toCandidate() = DisplayCandidate(displayId, flags, state, isValid)

/**
 * The display Loom's panel goes on, or null for none, from the displays a presentation can go on
 * ([candidates], in the system's order: wifi, external, overlay, virtual, then built-in) while Loom
 * is on display [own] and [enabled] ("Use the second screen").
 *
 * The display's type is hidden from apps, so its flags stand in for it. A display Loom itself is on
 * never counts, nor one that is off or private. It must be secure: physical screens are, while an
 * app's virtual display (a screen recording, Discord sharing the screen) can't be without a system
 * permission. Of those left, the last: a built-in second screen, as the Thor's bottom one, before a
 * monitor plugged in.
 */
fun pickSecondScreen(own: Int, candidates: List<DisplayCandidate>, enabled: Boolean = true): Int? = if (!enabled) null else
    candidates.lastOrNull {
        it.id != own && it.valid && it.state != Display.STATE_OFF &&
            it.flags and Display.FLAG_PRESENTATION != 0 && it.flags and Display.FLAG_PRIVATE == 0 && it.flags and Display.FLAG_SECURE != 0
    }?.id

/**
 * AYN's Focus Mode, in the system settings its Dual screen assistant writes: which screen the pad's
 * keys go to. [FOCUS_AUTO_LOCK] the one last touched, [FOCUS_TOP] the top one (the default display),
 * [FOCUS_BOTTOM] the bottom one. Other devices don't have it.
 */
const val AYN_FOCUS_LOCK = "screen_focus_lock"
const val FOCUS_AUTO_LOCK = 0
const val FOCUS_TOP = 1
const val FOCUS_BOTTOM = 2

/** AYN's Focus Mode as it is now: Auto-lock where there is none, or it can't be read. */
fun readFocusLock(resolver: ContentResolver): Int =
    runCatching { Settings.System.getInt(resolver, AYN_FOCUS_LOCK, FOCUS_AUTO_LOCK) }.getOrDefault(FOCUS_AUTO_LOCK)

/** What Loom says in place of its button hints while AYN's Focus Mode keeps the pad on the other screen. */
enum class PadNotice(val text: String) {
    LOCKED_TO_BOTTOM("AYN's Focus Mode has the pad on the bottom screen: set it to Auto-lock or Top screen to use Loom."),
    LOCKED_TO_TOP("AYN's Focus Mode has the pad on the top screen: set it to Auto-lock or Bottom screen to use Loom."),
}

/** The notice for Focus Mode [lock] while Loom is on the default display (the top screen) or not: none while the pad reaches Loom. */
fun padNotice(lock: Int, onDefaultDisplay: Boolean): PadNotice? = when {
    lock == FOCUS_BOTTOM && onDefaultDisplay -> PadNotice.LOCKED_TO_BOTTOM
    lock == FOCUS_TOP && !onDefaultDisplay -> PadNotice.LOCKED_TO_TOP
    else -> null
}
