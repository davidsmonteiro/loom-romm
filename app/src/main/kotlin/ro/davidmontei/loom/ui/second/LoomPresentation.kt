package ro.davidmontei.loom.ui.second

import android.app.Presentation
import android.os.Bundle
import android.view.Display
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.view.WindowCompat
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import ro.davidmontei.loom.R
import ro.davidmontei.loom.ui.theme.LoomTheme

/**
 * Loom's window on a second screen: [content], drawn edge to edge on black, which can never take
 * the pad's focus. A tap on it doesn't move focus either, so the pad stays with Loom; it stays
 * touchable, so a tap doesn't fall through to whatever is under it (Cocoon's bottom screen, which
 * would take the pad).
 *
 * Its Compose content is laid out for its own screen: the presentation's context, not the
 * activity's, so sizes are the second screen's. It shares the activity's lifecycle and saved state
 * but deliberately not its ViewModels: a ViewModel asked for here would be a second copy of the
 * activity's, so the panel is only ever handed state.
 */
class LoomPresentation(private val activity: ComponentActivity, display: Display, private val content: @Composable () -> Unit) :
    Presentation(activity, display, R.style.Theme_Loom_SecondScreen) {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window?.run {
            addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            WindowCompat.setDecorFitsSystemWindows(this, false)
        }
        setCancelable(false)
        setContentView(
            ComposeView(context).apply {
                // Its own id: saved state is kept by id, and the activity's own content has one too.
                id = R.id.loom_second_screen
                setViewTreeLifecycleOwner(activity)
                setViewTreeSavedStateRegistryOwner(activity)
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
                setContent { LoomTheme { this@LoomPresentation.content() } }
            },
        )
    }
}

/** Shows [content] in a [LoomPresentation]. */
class PresentationWindow(private val activity: ComponentActivity, private val content: @Composable () -> Unit) : SecondScreenWindow {
    private var shown: LoomPresentation? = null

    override fun show(display: Display, onGone: () -> Unit): Boolean {
        val presentation = LoomPresentation(activity, display, content)
        // Only the system's taking it away is news: dismiss() lets go of it first.
        presentation.setOnDismissListener { if (shown === presentation) { shown = null; onGone() } }
        shown = presentation
        return try {
            presentation.show()
            true
        } catch (e: WindowManager.InvalidDisplayException) {
            shown = null
            false
        } catch (e: WindowManager.BadTokenException) {
            shown = null
            false
        }
    }

    override fun dismiss() {
        val presentation = shown ?: return
        shown = null
        presentation.dismiss()
    }
}
