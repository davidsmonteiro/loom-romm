package ro.davidmontei.loom.ui.components

import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicSecureTextField
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldDecorator
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.platform.PlatformTextInputSession
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import ro.davidmontei.loom.ui.layout.rememberLoomLayout
import ro.davidmontei.loom.ui.theme.LoomColors
import ro.davidmontei.loom.ui.theme.tabular

/** Spacing scale: generous, because the Thor's top screen is read at arm's length. */
object Space {
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
}

private val buttonShape = RoundedCornerShape(12.dp)

@Composable
fun LoomButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    destructive: Boolean = false,
) {
    val container = when {
        !enabled -> LoomColors.SurfaceRaised.copy(alpha = 0.5f)
        primary && destructive -> LoomColors.Error
        primary -> LoomColors.Accent
        else -> LoomColors.SurfaceHighest
    }
    val content = when {
        !enabled -> LoomColors.TextFaint
        primary && destructive -> LoomColors.OnError
        primary -> LoomColors.OnAccent
        destructive -> LoomColors.Error
        else -> LoomColors.Text
    }
    Row(
        modifier = modifier
            .heightIn(min = 52.dp)
            .background(container, buttonShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = FocusRingIndication(cornerRadius = 12.dp),
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = Space.xl, vertical = Space.m),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(22.dp))
            Spacer(Modifier.size(Space.s))
        }
        Text(text, color = content, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}

@Composable
fun LoomChip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(50)
    Box(
        modifier = modifier
            .heightIn(min = 40.dp)
            .background(if (selected) LoomColors.AccentMuted else LoomColors.SurfaceRaised, shape)
            .border(1.dp, if (selected) LoomColors.Accent else LoomColors.Outline, shape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = FocusRingIndication(cornerRadius = 20.dp),
                role = Role.Tab,
                onClick = onClick,
            )
            .padding(horizontal = Space.l, vertical = Space.s),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = if (selected) LoomColors.Text else LoomColors.TextMuted)
    }
}

/** A row in a list or settings section. */
@Composable
fun LoomRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    selected: Boolean = false,
    /** Shorter, for lists that must fit the screen (Settings' sections). */
    dense: Boolean = false,
    subtitleLines: Int = 3,
) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = if (dense) 36.dp else 64.dp)
            .background(if (selected) LoomColors.SurfaceHighest else LoomColors.SurfaceRaised, shape)
            .then(
                if (onClick != null) Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = FocusRingIndication(),
                    onClick = onClick,
                ) else Modifier,
            )
            .padding(horizontal = Space.l, vertical = if (dense) Space.s else Space.m),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.m),
    ) {
        leading?.invoke()
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = LoomColors.TextMuted, maxLines = subtitleLines,
                    overflow = TextOverflow.Ellipsis)
            }
        }
        trailing?.invoke(this)
    }
}

@Composable
fun LoomSwitchRow(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit, subtitle: String? = null, subtitleLines: Int = 3) {
    LoomRow(title = title, subtitle = subtitle, subtitleLines = subtitleLines, onClick = { onCheckedChange(!checked) }, trailing = {
        val shape = RoundedCornerShape(50)
        Box(
            Modifier.size(width = 52.dp, height = 30.dp)
                .background(if (checked) LoomColors.Accent else LoomColors.SurfaceHighest, shape)
                .padding(4.dp),
            contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
        ) { Box(Modifier.size(22.dp).background(if (checked) LoomColors.OnAccent else LoomColors.TextMuted, CircleShape)) }
    })
}

@Composable
fun LoomTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String?,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Done,
    onImeAction: () -> Unit = {},
    secret: Boolean = false,
    singleLine: Boolean = true,
    supporting: String? = null,
    isError: Boolean = false,
    tag: String? = null,
    editRequest: Int = 0,
    /** Told when typing starts and stops: the field itself has focus, not just its frame. */
    onEditingChange: (Boolean) -> Unit = {},
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val keyboardMode = LocalInputModeManager.current.inputMode == InputMode.Keyboard
    val shape = RoundedCornerShape(12.dp)

    // With a pad, the D-pad lands on the frame (ring, no keyboard over the screen); A starts
    // editing, which focuses the inner field and brings up the keyboard. Touch works as usual.
    var editing by remember { mutableStateOf(false) }
    val frame = remember { FocusRequester() }
    val inner = remember { FocusRequester() }
    val frameInteraction = remember { MutableInteractionSource() }
    val innerInteraction = remember { MutableInteractionSource() }
    val frameFocused by frameInteraction.collectIsFocusedAsState()
    val innerFocused by innerInteraction.collectIsFocusedAsState()
    LaunchedEffect(editing) { if (editing) runCatching { inner.requestFocus() } }
    // A screen's own shortcut (Y for search) goes straight to typing: each new request starts editing.
    LaunchedEffect(editRequest) { if (editRequest > 0) editing = true }

    // Mirror the caller's value into the field only while nobody is typing in it, so a value
    // echoed back from a ViewModel can never overwrite keystrokes still arriving.
    val state = rememberTextFieldState(value)
    val latestOnChange by rememberUpdatedState(onValueChange)
    LaunchedEffect(state) { snapshotFlow { state.text.toString() }.collect { latestOnChange(it) } }
    LaunchedEffect(value, innerFocused) {
        if (!innerFocused && state.text.toString() != value) state.setTextAndPlaceCursorAtEnd(value)
    }

    var submitted by remember { mutableStateOf(false) }
    val submit = {
        if (!submitted) {
            submitted = true
            editing = false
            keyboard?.hide()
            // "Next" moves on; otherwise focus returns to the frame. Then the screen's own action
            // runs, so one that moves focus (search to its first result) has the last word.
            if (imeAction == ImeAction.Next) focusManager.moveFocus(FocusDirection.Down) else runCatching { frame.requestFocus() }
            onImeAction()
        }
    }
    LaunchedEffect(innerFocused) { if (innerFocused) submitted = false else editing = false }
    val latestOnEditingChange by rememberUpdatedState(onEditingChange)
    LaunchedEffect(innerFocused) { latestOnEditingChange(innerFocused) }

    val textStyle = MaterialTheme.typography.bodyLarge.copy(color = LoomColors.Text)
    val options = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction, autoCorrectEnabled = false)
    val decorator = TextFieldDecorator { field ->
        Box {
            if (state.text.isEmpty() && placeholder != null) {
                Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = LoomColors.TextFaint, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
            }
            field()
        }
    }
    val fieldModifier = Modifier
        .fillMaxWidth()
        .focusRequester(inner)
        .focusProperties { canFocus = editing || !keyboardMode }
        .onPreviewKeyEvent { event ->
            // A hardware/pad Enter submits once (on key-down) and its key-up is swallowed, so it
            // can't also trigger whatever gets focus next.
            if (singleLine && (event.key == Key.Enter || event.key == Key.NumPadEnter)) {
                if (event.type == KeyEventType.KeyDown) submit()
                true
            } else false
        }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        if (label != null) Text(label, style = MaterialTheme.typography.labelMedium, color = LoomColors.TextMuted)
        Box(
            Modifier
                .fillMaxWidth()
                .focusRequester(frame)
                .background(LoomColors.Surface, shape)
                .border(
                    width = if (frameFocused || innerFocused) 3.dp else 1.dp,
                    color = when { frameFocused || innerFocused -> LoomColors.Accent; isError -> LoomColors.Error; else -> LoomColors.Outline },
                    shape = shape,
                )
                .clickable(frameInteraction, indication = null, onClickLabel = label ?: placeholder) { editing = true }
                .padding(horizontal = Space.l, vertical = 12.dp)
                .then(if (tag != null) Modifier.testTag(tag) else Modifier),
        ) {
            InterceptPlatformTextInput(KeepScreenVisible) {
                if (secret) {
                    BasicSecureTextField(
                        state = state, modifier = fieldModifier, textStyle = textStyle, keyboardOptions = options,
                        onKeyboardAction = { submit() }, interactionSource = innerInteraction,
                        cursorBrush = SolidColor(LoomColors.Accent), decorator = decorator,
                    )
                } else {
                    BasicTextField(
                        state = state, modifier = fieldModifier, textStyle = textStyle, keyboardOptions = options,
                        onKeyboardAction = { submit() },
                        lineLimits = if (singleLine) TextFieldLineLimits.SingleLine else TextFieldLineLimits.Default,
                        interactionSource = innerInteraction, cursorBrush = SolidColor(LoomColors.Accent), decorator = decorator,
                    )
                }
            }
        }
        if (supporting != null) {
            Text(supporting, style = MaterialTheme.typography.bodySmall, color = if (isError) LoomColors.Error else LoomColors.TextMuted)
        }
    }
}

/**
 * In landscape, keyboards default to full-screen editing, which covers the whole app with a bare
 * text box. Loom keeps its screen visible above the keyboard, so you see the search results or the
 * form you are filling in.
 */
@OptIn(ExperimentalComposeUiApi::class)
private object KeepScreenVisible : PlatformTextInputInterceptor {
    override suspend fun interceptStartInputMethod(request: PlatformTextInputMethodRequest, nextHandler: PlatformTextInputSession): Nothing =
        nextHandler.startInputMethod(object : PlatformTextInputMethodRequest {
            override fun createInputConnection(outAttributes: EditorInfo): InputConnection =
                request.createInputConnection(outAttributes).also {
                    outAttributes.imeOptions = outAttributes.imeOptions or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
                }
        })
}

/**
 * Long text the pad can read: it takes focus, and Up/Down scroll it a page at a time until it
 * reaches an end, where they move focus on as usual. Use in place of `verticalScroll`.
 */
fun Modifier.padScroll(state: ScrollState): Modifier = composed {
    val scope = rememberCoroutineScope()
    val interaction = remember { MutableInteractionSource() }
    this
        .onPreviewKeyEvent { event ->
            val page = state.viewportSize * 0.8f
            val forward = event.key == Key.DirectionDown && state.canScrollForward
            val backward = event.key == Key.DirectionUp && state.canScrollBackward
            if (!forward && !backward) return@onPreviewKeyEvent false
            if (event.type == KeyEventType.KeyDown) scope.launch { state.animateScrollBy(if (forward) page else -page) }
            true
        }
        .focusable(interactionSource = interaction)
        .indication(interaction, FocusRingIndication(cornerRadius = 8.dp))
        .verticalScroll(state)
}

/** "1 game", "84 games". */
fun gameCount(n: Int): String = if (n == 1) "1 game" else "$n games"

/** A thin determinate bar in the accent colour (sage once complete). */
@Composable
fun LoomProgressBar(fraction: Float?, modifier: Modifier = Modifier, height: Dp = 6.dp, color: Color = LoomColors.Accent) {
    val shape = RoundedCornerShape(50)
    Box(modifier.fillMaxWidth().height(height).background(LoomColors.SurfaceHighest, shape)) {
        if (fraction != null) {
            Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(height).background(color, shape))
        }
    }
}

/** Small status label: "On device" in sage, or an accent / error variant. */
@Composable
fun StatusBadge(text: String, modifier: Modifier = Modifier, color: Color = LoomColors.Installed, onColor: Color = LoomColors.OnInstalled) {
    Box(modifier.background(color, RoundedCornerShape(8.dp)).padding(horizontal = Space.s, vertical = 2.dp)) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = onColor, fontWeight = FontWeight.SemiBold)
    }
}

/** One button hint in the footer: a round glyph and what it does. */
data class ButtonHint(val glyph: String, val label: String)

/**
 * The footer's hints, on one line where they fit: the library's six take about 740 of the 784 dp
 * the Thor's top screen leaves them. On a narrower screen they wrap rather than squeeze the last one.
 */
@Composable
fun ButtonHints(hints: List<ButtonHint>, modifier: Modifier = Modifier) {
    FlowRow(
        modifier.testTag("button-hints"),
        horizontalArrangement = Arrangement.spacedBy(Space.l),
        verticalArrangement = Arrangement.spacedBy(Space.s),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        hints.forEach { hint ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                Box(
                    Modifier.heightIn(min = 26.dp).widthIn(min = 26.dp).background(LoomColors.SurfaceHighest, RoundedCornerShape(13.dp))
                        .padding(horizontal = 7.dp),
                    contentAlignment = Alignment.Center,
                ) { Text(hint.glyph, style = MaterialTheme.typography.labelMedium, color = LoomColors.Text) }
                Text(hint.label, style = MaterialTheme.typography.labelMedium, color = LoomColors.TextMuted)
            }
        }
    }
}

/**
 * Screen chrome: title bar, content, and a footer of button hints. [titleContent], when given,
 * takes the title's place (the library's platform button on a narrow screen), filling what
 * [actions] leave. A narrow screen has narrower margins, so the hints wrap to two lines at most.
 */
@Composable
fun ScreenScaffold(
    title: String,
    hints: List<ButtonHint>,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
    titleContent: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxSize()
            .background(LoomColors.Background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = if (rememberLoomLayout().compact) Space.l else Space.xl, vertical = Space.m),
        verticalArrangement = Arrangement.spacedBy(Space.m),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (titleContent != null) titleContent()
            else Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f), maxLines = 1,
                overflow = TextOverflow.Ellipsis)
            actions()
        }
        Column(Modifier.weight(1f).fillMaxWidth()) { content() }
        ButtonHints(hints)
    }
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelMedium, color = LoomColors.TextMuted, modifier = modifier.padding(top = Space.s))
}

/** Where [LoomDialog]s are drawn: one overlay above the whole screen, in the activity's window. */
class DialogHostState {
    internal var content by mutableStateOf<(@Composable () -> Unit)?>(null)
}

val LocalDialogHost = staticCompositionLocalOf<DialogHostState?> { null }

/** Wrap the app (or a test) in this so dialogs declared deep inside a screen cover the whole screen. */
@Composable
fun DialogHost(content: @Composable () -> Unit) {
    val state = remember { DialogHostState() }
    CompositionLocalProvider(LocalDialogHost provides state) {
        Box(Modifier.fillMaxSize()) {
            content()
            // Keyed by dialog, so one that replaces another in the same frame (details, then
            // "Remove from device?") is drawn afresh and puts focus on its own first button.
            state.content?.let { dialog -> key(dialog) { dialog() } }
        }
    }
}

/**
 * A dialog drawn in the activity's own window (Compose's Dialog opens a separate window, which
 * would bypass the activity's gamepad remapping). Rendered by the nearest [DialogHost] above
 * everything else; focus is trapped inside; B closes it.
 *
 * [maxWidth] can be raised (to [Dp.Unspecified]: the screen's width) for a form that would
 * otherwise not fit the screen's height.
 */
@Composable
fun LoomDialog(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    maxWidth: Dp = 680.dp,
    body: @Composable ColumnScope.() -> Unit = {},
    buttons: @Composable RowScope.(firstFocus: FocusRequester) -> Unit,
) {
    val host = LocalDialogHost.current
    val surface: @Composable () -> Unit = { DialogSurface(title, onDismiss, modifier, maxWidth, body, buttons) }
    if (host == null) {
        surface()
        return
    }
    val latest = rememberUpdatedState(surface)
    DisposableEffect(host) {
        val entry: @Composable () -> Unit = { latest.value() }
        host.content = entry
        onDispose { if (host.content === entry) host.content = null }
    }
}

@Composable
private fun DialogSurface(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier,
    maxWidth: Dp,
    body: @Composable ColumnScope.() -> Unit,
    buttons: @Composable RowScope.(firstFocus: FocusRequester) -> Unit,
) {
    val first = remember { FocusRequester() }
    BackHandler(onBack = onDismiss)
    InitialFocus(first, delayMs = 0)
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier
                .widthIn(max = maxWidth)
                .padding(Space.xl)
                .background(LoomColors.Surface, RoundedCornerShape(16.dp))
                .border(1.dp, LoomColors.Outline, RoundedCornerShape(16.dp))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
                .focusProperties { onExit = { cancelFocusChange() } }
                .focusGroup()
                .verticalScroll(rememberScrollState())
                .padding(Space.xl),
            verticalArrangement = Arrangement.spacedBy(Space.l),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            body()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.m, Alignment.End)) { buttons(first) }
        }
    }
}

/** Tabular, readable byte counts for the UI. */
@Composable
fun BytesText(bytes: Long, modifier: Modifier = Modifier, color: Color = LoomColors.TextMuted) {
    Text(ro.davidmontei.loom.storage.engine.formatBytes(bytes), modifier = modifier, color = color,
        style = MaterialTheme.typography.bodySmall.tabular())
}

val NoPadding = PaddingValues(0.dp)

/** Shorthand used by screens that ask for initial focus. */
fun Modifier.initialFocus(requester: FocusRequester) = focusRequester(requester)
