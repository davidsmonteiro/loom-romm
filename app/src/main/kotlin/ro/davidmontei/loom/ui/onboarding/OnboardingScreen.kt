package ro.davidmontei.loom.ui.onboarding

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import ro.davidmontei.loom.R
import ro.davidmontei.loom.ui.components.ButtonHint
import ro.davidmontei.loom.ui.components.FolderPicker
import ro.davidmontei.loom.ui.components.InitialFocus
import ro.davidmontei.loom.ui.components.LoomButton
import ro.davidmontei.loom.ui.components.LoomChip
import ro.davidmontei.loom.ui.components.LoomProgressBar
import ro.davidmontei.loom.ui.components.LoomTextField
import ro.davidmontei.loom.ui.components.QrCode
import ro.davidmontei.loom.ui.components.ScreenScaffold
import ro.davidmontei.loom.ui.components.Space
import ro.davidmontei.loom.ui.theme.LoomColors
import ro.davidmontei.loom.ui.theme.tabular
import java.io.File

/** Everything the onboarding steps can do, so they can be driven in tests without Hilt. */
interface OnboardingActions {
    val knownSystemFolders: Set<String>
    fun go(step: OnboardingStep)
    fun setAddress(text: String)
    fun connect()
    fun setPairMode(mode: PairMode)
    fun setPairInput(text: String)
    fun submitPairInput()
    fun startDeviceAuth()
    fun pairAgain()
    fun refreshStorage()
    fun pickRoot(dir: File)
    fun createSuggestedRoot()
    fun finish()
}

private val hints = listOf(ButtonHint("A", "Select"), ButtonHint("B", "Back"))

@Composable
fun OnboardingScreen(onFinished: () -> Unit, vm: OnboardingViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val finished by rememberUpdatedState(onFinished)
    val actions = remember(vm) {
        object : OnboardingActions {
            override val knownSystemFolders get() = vm.knownSystemFolders
            override fun go(step: OnboardingStep) = vm.go(step)
            override fun setAddress(text: String) = vm.setAddress(text)
            override fun connect() = vm.connect()
            override fun setPairMode(mode: PairMode) = vm.setPairMode(mode)
            override fun setPairInput(text: String) = vm.setPairInput(text)
            override fun submitPairInput() = vm.submitPairInput()
            override fun startDeviceAuth() = vm.startDeviceAuth()
            override fun pairAgain() = vm.pairAgain()
            override fun refreshStorage() = vm.refreshStorage()
            override fun pickRoot(dir: File) = vm.pickRoot(dir)
            override fun createSuggestedRoot() = vm.createSuggestedRoot()
            override fun finish() = vm.finish { finished() }
        }
    }
    OnboardingContent(state, actions)
}

@Composable
fun OnboardingContent(state: OnboardingState, actions: OnboardingActions) {
    val previous = when (state.step) {
        OnboardingStep.WELCOME -> null
        OnboardingStep.SERVER -> OnboardingStep.WELCOME
        OnboardingStep.PAIR -> OnboardingStep.SERVER
        OnboardingStep.STORAGE -> OnboardingStep.PAIR
        OnboardingStep.ROMS_ROOT -> OnboardingStep.STORAGE
        OnboardingStep.BACKGROUND -> OnboardingStep.ROMS_ROOT
        OnboardingStep.SYNC -> OnboardingStep.BACKGROUND
    }
    // B steps back; on the first step it does nothing rather than close Loom.
    BackHandler { previous?.let(actions::go) }

    val stepNumber = state.step.ordinal
    ScreenScaffold(title = "Set up Loom", hints = hints, actions = {
        if (stepNumber > 0) Text("Step $stepNumber of ${OnboardingStep.entries.size - 1}", style = MaterialTheme.typography.labelLarge.tabular(), color = LoomColors.TextMuted)
    }) {
        val first = remember { FocusRequester() }
        InitialFocus(first, state.step, state.pairMode, state.pairedAs)
        // Each step is laid out to fit the Thor's screen. The scroll is only a fallback (large font
        // sizes), except on the ROMs step, whose folder list scrolls inside its own pane.
        val ownScroll = state.step == OnboardingStep.ROMS_ROOT
        Column(
            // Padding keeps focus rings inside the scroll container's clip.
            Modifier.fillMaxWidth().widthIn(max = 900.dp)
                .then(if (ownScroll) Modifier.fillMaxHeight() else Modifier.verticalScroll(rememberScrollState()))
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(Space.l),
        ) {
            when (state.step) {
                OnboardingStep.WELCOME -> Welcome(first) { actions.go(OnboardingStep.SERVER) }
                OnboardingStep.SERVER -> Server(state, actions, first)
                OnboardingStep.PAIR -> Pair(state, actions, first)
                OnboardingStep.STORAGE -> Storage(state, actions, first)
                OnboardingStep.ROMS_ROOT -> RomsRoot(state, actions, first)
                OnboardingStep.BACKGROUND -> Background(actions, first)
                OnboardingStep.SYNC -> Sync(state, first, actions::finish)
            }
        }
    }
}

@Composable
private fun Title(text: String, body: String? = null) {
    Text(text, style = MaterialTheme.typography.headlineMedium)
    if (body != null) Text(body, style = MaterialTheme.typography.bodyLarge, color = LoomColors.TextMuted)
}

@Composable
private fun Welcome(first: FocusRequester, next: () -> Unit) {
    Image(painterResource(R.drawable.ic_loom_mark), contentDescription = "Loom", modifier = Modifier.size(72.dp))
    Title(
        "Your RomM library, in Cocoon",
        "Loom copies games from your RomM server into the folders Cocoon already scans, removes them when you need " +
            "the space, and checks every file so Cocoon never sees a half-finished download. You keep browsing and " +
            "playing in Cocoon.",
    )
    LoomButton("Get started", onClick = next, primary = true, modifier = Modifier.focusRequester(first))
}

@Composable
private fun Server(state: OnboardingState, actions: OnboardingActions, first: FocusRequester) {
    Title(
        "Where is your RomM server?",
        "Use whichever address reaches it from the Thor: a local one (192.168.1.10:8080), Tailscale " +
            "(100.x.y.z:8080 or a MagicDNS name) or your domain (romm.example.org). You can change it later in Settings.",
    )
    LoomTextField(
        value = state.addressInput, onValueChange = actions::setAddress, label = "Server address",
        placeholder = "romm.example.org or 100.x.y.z:8080", keyboardType = KeyboardType.Uri, onImeAction = actions::connect,
        modifier = Modifier.focusRequester(first), isError = state.serverError != null,
    )
    state.serverError?.let { Text(it, color = LoomColors.Error, style = MaterialTheme.typography.bodyMedium) }
    state.serverSummary?.let { Text(it, color = LoomColors.Installed, style = MaterialTheme.typography.bodyMedium) }
    state.insecureWarning?.let { Text(it, color = LoomColors.Accent, style = MaterialTheme.typography.bodyMedium) }
    val continueFocus = remember { FocusRequester() }
    val checkFocus = remember { FocusRequester() }
    // After a check, focus goes where the next press should: Continue on success, Check on failure.
    LaunchedEffect(state.serverSummary, state.serverError) {
        if (state.serverSummary != null) runCatching { continueFocus.requestFocus() }
        else if (state.serverError != null) runCatching { checkFocus.requestFocus() }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(Space.m)) {
        LoomButton(if (state.probing) "Checking…" else "Check address", onClick = actions::connect, enabled = !state.probing,
            modifier = Modifier.focusRequester(checkFocus))
        LoomButton("Continue", onClick = { actions.go(OnboardingStep.PAIR) }, primary = true, enabled = state.serverSummary != null,
            modifier = Modifier.focusRequester(continueFocus))
    }
}

@Composable
private fun Pair(state: OnboardingState, actions: OnboardingActions, first: FocusRequester) {
    if (state.pairedAs != null) {
        Title("Paired with RomM", "Loom is signed in as ${state.pairedAs}.")
        Row(horizontalArrangement = Arrangement.spacedBy(Space.m)) {
            LoomButton("Continue", onClick = { actions.go(OnboardingStep.STORAGE) }, primary = true, modifier = Modifier.focusRequester(first))
            LoomButton("Pair again", onClick = actions::pairAgain)
        }
        return
    }
    val session = state.session.takeIf { state.pairMode == PairMode.DEVICE_CODE }
    // The QR code sits beside the whole step rather than under it: nothing below the chips takes focus, so on a
    // gamepad anything pushed off the bottom of the screen could never be scrolled to.
    Row(horizontalArrangement = Arrangement.spacedBy(Space.xxl)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.m)) {
            Title("Pair with RomM")
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                LoomChip("Scan a code", state.pairMode == PairMode.DEVICE_CODE, { actions.setPairMode(PairMode.DEVICE_CODE) },
                    Modifier.focusRequester(first))
                LoomChip("Enter a pair code", state.pairMode == PairMode.PAIR_CODE, { actions.setPairMode(PairMode.PAIR_CODE) })
                LoomChip("Paste a token", state.pairMode == PairMode.PASTE_TOKEN, { actions.setPairMode(PairMode.PASTE_TOKEN) })
            }
            when (state.pairMode) {
                PairMode.DEVICE_CODE -> if (session == null) {
                    if (state.pairing) Text("Asking RomM for a code…", color = LoomColors.TextMuted)
                    LoomButton("Get a new code", onClick = actions::startDeviceAuth, primary = true)
                } else {
                    Text("Scan with your phone, sign in to RomM if asked, and approve Loom.", style = MaterialTheme.typography.bodyLarge)
                    Text(session.displayCode, style = MaterialTheme.typography.displaySmall.copy(fontFamily = FontFamily.Monospace, letterSpacing = 4.sp), color = LoomColors.Accent)
                    Text("Or open ${session.verificationUrl} and enter the code.", style = MaterialTheme.typography.bodyMedium, color = LoomColors.TextMuted)
                    Countdown(state.sessionStartedAt, session.expiresInSeconds)
                }
                PairMode.PAIR_CODE -> {
                    Text("In RomM's web UI, open your profile → API tokens, create a token for Loom and choose “Pair device”. Type the 8-character code it shows (it lasts 60 seconds).",
                        style = MaterialTheme.typography.bodyMedium, color = LoomColors.TextMuted)
                    LoomTextField(state.pairInput, actions::setPairInput, "Pair code", placeholder = "ABCD-2345", onImeAction = actions::submitPairInput)
                    LoomButton(if (state.pairing) "Pairing…" else "Pair", onClick = actions::submitPairInput, primary = true, enabled = !state.pairing)
                }
                PairMode.PASTE_TOKEN -> {
                    Text("Paste a RomM client API token (starts with rmm_), e.g. sent over adb or KDE Connect.",
                        style = MaterialTheme.typography.bodyMedium, color = LoomColors.TextMuted)
                    LoomTextField(state.pairInput, actions::setPairInput, "API token", placeholder = "rmm_…", secret = true, onImeAction = actions::submitPairInput)
                    LoomButton(if (state.pairing) "Checking…" else "Use token", onClick = actions::submitPairInput, primary = true, enabled = !state.pairing)
                }
            }
            state.pairError?.let { Text(it, color = LoomColors.Error, style = MaterialTheme.typography.bodyMedium) }
        }
        if (session != null) QrCode(session.verificationUrlComplete.toString(), size = 232.dp)
    }
}

@Composable
private fun Countdown(startedAt: Long, seconds: Int) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(startedAt) { while (true) { now = System.currentTimeMillis(); delay(1000) } }
    val left = (seconds - (now - startedAt) / 1000).coerceAtLeast(0)
    Text("Waiting for approval · code expires in %d:%02d".format(left / 60, left % 60),
        style = MaterialTheme.typography.bodyMedium.tabular(), color = LoomColors.TextMuted)
}

@Composable
private fun Storage(state: OnboardingState, actions: OnboardingActions, first: FocusRequester) {
    val context = LocalContext.current
    LifecycleResumeEffect(Unit) { actions.refreshStorage(); onPauseOrDispose { } }
    Title(
        "Let Loom write to your ROM folders",
        "Cocoon reads games from folders like ROMs/snes on your SD card. To put games there, and to delete them " +
            "when you offload, Loom needs Android's “All files access”. Loom only touches your ROMs folder and its " +
            "own hidden .loom-staging folder.",
    )
    if (state.hasAllFilesAccess) {
        Text("All files access is on.", color = LoomColors.Installed, style = MaterialTheme.typography.bodyLarge)
        LoomButton("Continue", onClick = { actions.go(OnboardingStep.ROMS_ROOT) }, primary = true, modifier = Modifier.focusRequester(first))
    } else {
        LoomButton("Open Android settings", primary = true, modifier = Modifier.focusRequester(first), onClick = {
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, "package:${context.packageName}".toUri())
            runCatching { context.startActivity(intent) }.onFailure {
                context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        })
        Text("Turn on “Allow access to manage all files” for Loom, then press B to come back.", color = LoomColors.TextMuted)
    }
}

@Composable
private fun RomsRoot(state: OnboardingState, actions: OnboardingActions, first: FocusRequester) {
    val suggested = state.suggestedRoot
    Row(Modifier.fillMaxHeight(), horizontalArrangement = Arrangement.spacedBy(Space.xxl)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.l)) {
            Title("Which folder does Cocoon scan?", "Pick the folder that holds your system folders (snes, psx, gba…). Loom adds games inside it.")
            if (suggested != null) {
                if (suggested.isDirectory) {
                    LoomButton("Use ${suggested.path}", onClick = { actions.pickRoot(suggested) }, primary = true, modifier = Modifier.focusRequester(first))
                } else {
                    LoomButton("Create ${suggested.path}", onClick = actions::createSuggestedRoot, primary = true, modifier = Modifier.focusRequester(first))
                }
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.s)) {
            Text("Or browse", style = MaterialTheme.typography.titleSmall, color = LoomColors.TextMuted)
            FolderPicker(
                volumes = state.volumes,
                initial = suggested?.parentFile,
                knownSystemFolders = actions.knownSystemFolders,
                onPick = actions::pickRoot,
                modifier = Modifier.fillMaxWidth().weight(1f),
                focusOnOpen = suggested == null,
            )
        }
    }
}

@SuppressLint("BatteryLife")
@Composable
private fun Background(actions: OnboardingActions, first: FocusRequester) {
    val context = LocalContext.current
    val power = context.getSystemService(PowerManager::class.java)
    fun notificationsOn() = NotificationManagerCompat.from(context).areNotificationsEnabled()
    fun unrestricted() = power.isIgnoringBatteryOptimizations(context.packageName)
    var notifications by remember { mutableStateOf(notificationsOn()) }
    var background by remember { mutableStateOf(unrestricted()) }
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { notifications = notificationsOn() }
    LifecycleResumeEffect(Unit) {
        notifications = notificationsOn()
        background = unrestricted()
        onPauseOrDispose { }
    }
    // A keeps moving forward: focus waits on the first thing still to allow, then on Continue.
    InitialFocus(first, notifications, background)
    Title(
        "Downloads in the background",
        "Downloads keep going with the screen off. Android shows a notification while they run, and may stop " +
            "them if Loom is battery-optimised. Allowing both makes big downloads reliable.",
    )
    Allowance(notifications, "Notifications are on.", "Allow notifications", if (!notifications) Modifier.focusRequester(first) else Modifier) {
        // Before Android 13 notifications need no runtime permission.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    Allowance(background, "Background downloads are allowed.", "Allow background downloads",
        if (notifications && !background) Modifier.focusRequester(first) else Modifier) {
        runCatching {
            context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:${context.packageName}".toUri()))
        }
    }
    LoomButton("Continue", onClick = { actions.go(OnboardingStep.SYNC) }, primary = true,
        modifier = if (notifications && background) Modifier.focusRequester(first) else Modifier)
}

/** One thing to allow: a button until it's allowed, then a line saying so. */
@Composable
private fun Allowance(done: Boolean, doneText: String, action: String, modifier: Modifier, onClick: () -> Unit) {
    if (done) {
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = LoomColors.Installed)
            Text(doneText, color = LoomColors.Installed, style = MaterialTheme.typography.bodyLarge)
        }
    } else {
        LoomButton(action, onClick = onClick, modifier = modifier)
    }
}

@Composable
private fun Sync(state: OnboardingState, first: FocusRequester, done: () -> Unit) {
    val sync = state.sync
    Title("Fetching your library", "Loom keeps a copy of your RomM catalog, so you can browse it even offline.")
    when {
        sync == null || sync.running -> {
            val total = sync?.total
            val fetched = sync?.fetched ?: 0
            LoomProgressBar(total?.takeIf { it > 0 }?.let { fetched.toFloat() / it })
            Text(if (total != null) "$fetched of $total games" else "Starting…", style = MaterialTheme.typography.bodyLarge.tabular(), color = LoomColors.TextMuted)
            LoomButton("Continue in the background", onClick = done, modifier = Modifier.focusRequester(first))
        }
        sync.error != null -> {
            Text(sync.error, color = LoomColors.Error)
            LoomButton("Open the library anyway", onClick = done, primary = true, modifier = Modifier.focusRequester(first))
        }
        else -> {
            Text("Your library is ready.", color = LoomColors.Installed, style = MaterialTheme.typography.bodyLarge)
            LoomButton("Open the library", onClick = done, primary = true, modifier = Modifier.focusRequester(first))
        }
    }
}
