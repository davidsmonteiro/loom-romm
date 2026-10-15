package ro.davidmontei.loom.ui.second

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
