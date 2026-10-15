package ro.davidmontei.loom.data.sent

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ro.davidmontei.loom.data.auth.CredentialStore
import ro.davidmontei.loom.data.auth.Credentials
import ro.davidmontei.loom.data.download.DownloadNotifications
import ro.davidmontei.loom.data.download.DownloadRepository
import ro.davidmontei.loom.data.download.QueueGame
import ro.davidmontei.loom.data.settings.Settings
import ro.davidmontei.loom.data.settings.SettingsStore
import ro.davidmontei.loom.net.ActiveServer
import ro.davidmontei.loom.net.NotPairedException
import ro.davidmontei.loom.net.ServerInfo
import ro.davidmontei.loom.net.ServerReachability
import ro.davidmontei.loom.romm.ROMM_5_4
import ro.davidmontei.loom.romm.RommException
import ro.davidmontei.loom.romm.RommVersion
import ro.davidmontei.loom.romm.dto.DeviceUpdateRequest
import ro.davidmontei.loom.romm.dto.INSTALL_ALREADY_INSTALLED
import ro.davidmontei.loom.romm.dto.INSTALL_DONE
import ro.davidmontei.loom.romm.dto.INSTALL_FAILED
import ro.davidmontei.loom.romm.dto.InstallReportRequest
import ro.davidmontei.loom.romm.dto.InstallRequestDto
import ro.davidmontei.loom.romm.dto.REMOTE_INSTALL
import ro.davidmontei.loom.romm.dto.installReason
import ro.davidmontei.loom.romm.rommCall
import ro.davidmontei.loom.romm.socket.DeviceSocket

/** Whether games sent from RomM's web UI ("Install on device") can reach Loom, and if not, why. */
sealed interface SendToDeviceState {
    /** Setup isn't finished, or Loom isn't paired. */
    data object NotSetUp : SendToDeviceState

    /** Switched off in Settings › Downloads: RomM doesn't offer this device. */
    data object Off : SendToDeviceState

    /** RomM [version] can't send games to devices: that came with RomM 5.4. Null: not known yet. */
    data class OldServer(val version: String?) : SendToDeviceState

    /**
     * Sending games to devices is switched off on the RomM server, or it has none though its version
     * reads as new enough (a build from source says "development").
     */
    data object OffOnServer : SendToDeviceState

    /**
     * RomM won't take this device's claims: the device was deleted on RomM's Devices page, or Loom's
     * token isn't bound to a device (a pasted token or a pair code). Pairing again by QR code fixes it.
     */
    data object NeedsPairing : SendToDeviceState

    /** Games sent to device [deviceId] land in Loom's queue. */
    data class Ready(val deviceId: String) : SendToDeviceState
}

/**
 * Whether games sent from RomM can come, from Loom's [settings], its [credentials] and the switch and
 * what RomM said ([sent]). Device installs that RomM hasn't said anything of yet (a server Loom paired
 * with since its last heartbeat) count as on: RomM's own default, and a claim refused for them asks.
 */
fun sendToDeviceState(settings: Settings, credentials: Credentials?, sent: SendToDeviceSettings): SendToDeviceState {
    if (!settings.onboardingComplete || settings.server == null || credentials == null) return SendToDeviceState.NotSetUp
    if (!sent.receive) return SendToDeviceState.Off
    if (!sendsToDevices(settings.serverVersion)) return SendToDeviceState.OldServer(settings.serverVersion)
    if (sent.installsEnabled == false) return SendToDeviceState.OffOnServer
    val deviceId = credentials.deviceId
    if (deviceId == null || sent.needsPairing) return SendToDeviceState.NeedsPairing
    return SendToDeviceState.Ready(deviceId)
}

private fun sendsToDevices(version: String?) = version?.let(RommVersion::parse)?.let { it >= ROMM_5_4 } == true

/**
 * Games sent from RomM's web UI land in Loom's queue (RomM 5.4's device installs, ADR 0013). RomM keeps
 * each game sent as an install request until the device claims it and reports how it went; Loom claims
 * at start, each time it comes to the front, when RomM answers again after being out of reach, after
 * pairing again, and every 15 minutes in the background ([ClaimWorker]). Each game claimed is queued
 * like one picked in the library, waiting in the queue to be asked about when its platform's folder
 * isn't clear, and reported [INSTALL_DONE] once it's in the queue, which RomM shows as "Added to
 * downloads". Only a lasting problem is reported [INSTALL_FAILED]; a passing one leaves the request
 * for the next claim, which returns it again.
 *
 * RomM lists the device under "Install on device" only once it declares [REMOTE_INSTALL], which Loom
 * does at every start and whenever the switch in Settings changes. While Loom is in front, RomM's
 * device socket nudges it to claim the moment a game is sent ([LiveNudges]).
 */
class SendToDevice(
    private val settings: SettingsStore,
    private val credentials: CredentialStore,
    private val store: SendToDeviceStore,
    private val server: ActiveServer,
    private val serverInfo: ServerInfo,
    private val reachability: ServerReachability,
    private val queue: QueueGame,
    private val downloads: DownloadRepository,
    private val notifications: DownloadNotifications,
    private val claims: ClaimScheduler,
    private val live: LiveNudges,
    scope: CoroutineScope,
    private val restartAfterMs: Long = RESTART_AFTER_MS,
) {
    /** What decides the state, read together. */
    private data class Inputs(val settings: Settings, val credentials: Credentials?, val store: SendToDeviceSettings)

    /**
     * What RomM is told the device can do: [receive] as [REMOTE_INSTALL], for device [deviceId] as
     * the token [token] knows it (a new pairing may have found a device without it).
     */
    private data class Declaration(val deviceId: String, val receive: Boolean, val token: String) {
        override fun toString() = "Declaration(deviceId=$deviceId, receive=$receive)"
    }

    /** Once the credentials are read: before, Loom would look unpaired for a moment. */
    private val inputs: Flow<Inputs> = flow {
        credentials.awaitLoaded()
        emitAll(combine(settings.settings, credentials.current, store.settings, ::Inputs))
    }

    val state: StateFlow<SendToDeviceState> =
        inputs.map { it.state() }.stateIn(scope, SharingStarted.Eagerly, SendToDeviceState.NotSetUp)

    private val lock = Mutex()
    private val nudges = Channel<Unit>(Channel.CONFLATED)

    /** What RomM was last told, this process: told again at each start, and whenever it changes. */
    @Volatile private var declared: Declaration? = null

    /**
     * Keeps everything in step while Loom runs: the periodic claim exists exactly while games can come,
     * RomM hears of the switch, the socket is open while Loom is in front, and Loom claims when asked
     * to ([nudge]), after pairing again and when RomM can be reached again. A part that fails starts
     * again on its own ([keepRunning]), so the rest never stops with it.
     */
    suspend fun run(): Unit = coroutineScope {
        launch { keepRunning { live.run(inputs.map { it.liveTarget() }, socketListener) } }
        launch { keepRunning { inputs.map { it.state() is SendToDeviceState.Ready }.distinctUntilChanged().collect(claims::schedule) } }
        launch {
            keepRunning {
                inputs.map { it.declaration() }.distinctUntilChanged().collect { d ->
                    if (d != null) lock.withLock { if (d != declared) declare(d) }
                }
            }
        }
        launch {
            // Paired again, maybe after RomM refused the old token or device: that refusal no longer holds.
            // Not for the token Loom started with: a refusal RomM gave it then still does.
            credentials.awaitLoaded()
            var token = credentials.current.value?.token
            keepRunning {
                credentials.current.map { it?.token }.distinctUntilChanged().collect { now ->
                    if (now == token) return@collect
                    if (now != null) store.setNeedsPairing(false)
                    token = now // only once that's written: a failed write is tried again
                    if (now != null) nudge()
                }
            }
        }
        launch {
            // A game sent while RomM was out of reach is waiting there.
            var away = false
            reachability.current.collect {
                if (it == ServerReachability.State.UNREACHABLE) away = true
                else if (it == ServerReachability.State.REACHABLE && away) { away = false; nudge() }
            }
        }
        // Nudges that come together, as when Loom comes to the front and RomM answers again, claim once.
        for (nudge in nudges) {
            delay(NUDGE_GATHER_MS)
            nudges.tryReceive()
            try {
                drain()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // What went wrong is left for the next claim, which returns every request not reported.
            }
        }
    }

    /**
     * Runs [block] for as long as Loom does: one that fails (a write to a full disk, say) starts again a
     * little later, rather than leaving that part of [run] stopped until Loom restarts.
     */
    private suspend fun keepRunning(block: suspend () -> Unit) {
        while (true) {
            try {
                return block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                delay(restartAfterMs)
            }
        }
    }

    /** Claims soon (see [run]): Loom came to the front, or RomM said a game was sent. */
    fun nudge() {
        nudges.trySend(Unit)
    }

    /**
     * Claims each time Loom comes to the front, the first time being when it starts, and keeps the
     * socket open while it's there.
     */
    fun follow(lifecycle: Lifecycle) = lifecycle.addObserver(object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            live.inFront(true)
            nudge()
        }

        override fun onStop(owner: LifecycleOwner) = live.inFront(false)
    })

    /** What the socket says: RomM refusing it stops the claims as RomM refusing a claim does. */
    private val socketListener = object : LiveNudges.Listener {
        override fun nudge() = this@SendToDevice.nudge()

        override suspend fun cancelled(requestId: String) = this@SendToDevice.cancelled(requestId)

        override suspend fun refused(message: String, token: String) {
            when (message) {
                DeviceSocket.UNAUTHORIZED -> needsPairing(token)
                DeviceSocket.DISABLED -> store.setInstallsEnabled(false) // until a heartbeat says they're back
            }
        }
    }

    /**
     * Claims the games sent to this device and queues each one, reporting how that went. One drain at a
     * time; it does nothing unless games can come ([SendToDeviceState.Ready]).
     */
    suspend fun drain() = lock.withLock {
        inputs().declaration()?.let { if (it != declared) declare(it) }
        val inputs = inputs() // as RomM left them: it may have refused the device just now
        val ready = inputs.state() as? SendToDeviceState.Ready ?: return@withLock
        val token = inputs.credentials?.token ?: return@withLock
        val requests = try {
            server.call { api -> rommCall { api.claimInstalls(ready.deviceId) } }
        } catch (e: NotPairedException) {
            return@withLock
        } catch (e: RommException.Unauthorized) {
            return@withLock needsPairing(token)
        } catch (e: RommException.Forbidden) {
            return@withLock needsPairing(token) // the token isn't this device's
        } catch (e: RommException.NotFound) {
            return@withLock claimRefused(token)
        } catch (e: RommException) {
            return@withLock // out of reach, busy (409) or failing for now: the next claim
        }
        for (request in requests) if (!handle(ready.deviceId, token, request)) break
    }

    /**
     * The owner cancelled request [requestId] on RomM: its game leaves the queue, unless it's already
     * being moved into place or installed. Heard while a claim is being handled, it waits for that to
     * end, as the request's game may not be in the queue yet.
     */
    suspend fun cancelled(requestId: String) {
        lock.withLock { downloads.jobForInstallRequest(requestId)?.let { downloads.cancel(it.id) } }
    }

    private suspend fun inputs(): Inputs {
        credentials.awaitLoaded()
        return Inputs(settings.snapshot(), credentials.current.value, store.snapshot())
    }

    /** Queues [request]'s game and reports how that went. False when there's no use going on now. */
    private suspend fun handle(deviceId: String, token: String, request: InstallRequestDto): Boolean {
        val outcome = queue.queue(request.romId, QueueGame.WhenUnsure.WAIT_IN_QUEUE, request.id)
        val report = when (outcome) {
            // A game waiting for its folder is in the queue all the same: RomM says "Added to downloads".
            is QueueGame.Outcome.Queued, QueueGame.Outcome.AlreadyQueued, is QueueGame.Outcome.WaitingForFolder ->
                InstallReportRequest(INSTALL_DONE)
            QueueGame.Outcome.AlreadyInstalled -> InstallReportRequest(INSTALL_ALREADY_INSTALLED)
            is QueueGame.Outcome.Failed ->
                if (outcome.transient) return true // RomM out of reach or busy for now: the next claim returns it
                else InstallReportRequest(INSTALL_FAILED, installReason(outcome.message))
            is QueueGame.Outcome.AskFolder -> return true // never, when it can wait in the queue
        }
        val goOn = try {
            server.call { api -> rommCall { api.reportInstall(deviceId, request.id, report) } }
            true
        } catch (e: RommException.NotFound) {
            // RomM no longer has the request it just handed back: the owner cancelled it meanwhile (or deleted
            // the device, or switched device installs off). Without the socket, this is the only sign of it.
            // The game queued for it leaves the queue again; one already queued from the library stays.
            downloads.jobForInstallRequest(request.id)?.let { downloads.cancel(it.id) }
            return true
        } catch (e: RommException.Conflict) {
            true // not taken (any more): nothing to report on
        } catch (e: RommException.Unauthorized) {
            needsPairing(token)
            false
        } catch (e: RommException.Forbidden) {
            needsPairing(token)
            false
        } catch (e: RommException.Unreachable) {
            false // the next claim returns it, and finds the game queued
        } catch (e: RommException) {
            true // RomM didn't take the report (422, say): the request expires on its own
        }
        if (outcome is QueueGame.Outcome.WaitingForFolder) {
            // The owner chooses its folder in the queue.
            val title = downloads.job(outcome.jobId)?.title ?: outcome.ask.platformName
            notifications.chooseFolder(title, outcome.ask.platformName)
        }
        return goOn
    }

    /** Tells RomM what the device can do. A refused device needs pairing again; a passing problem, the next claim. */
    private suspend fun declare(declaration: Declaration) {
        try {
            server.call { api ->
                rommCall { api.updateDevice(declaration.deviceId, DeviceUpdateRequest(mapOf(REMOTE_INSTALL to declaration.receive))) }
            }
            declared = declaration
        } catch (e: NotPairedException) {
            // Signed out meanwhile.
        } catch (e: RommException.Unauthorized) {
            needsPairing(declaration.token)
        } catch (e: RommException.Forbidden) {
            needsPairing(declaration.token)
        } catch (e: RommException.NotFound) {
            needsPairing(declaration.token) // the device was deleted on RomM
        } catch (e: RommException) {
            // Out of reach or failing for now: declared again before the next claim.
        }
    }

    /**
     * RomM refused the claim with 404: its device installs are off (the heartbeat says so, and recording
     * that stops the claims), or it no longer has the device.
     */
    private suspend fun claimRefused(token: String) {
        val heartbeat = try {
            server.call { api -> serverInfo.heartbeat(api) }
        } catch (e: NotPairedException) {
            return
        } catch (e: RommException) {
            return
        }
        if (heartbeat.deviceInstall?.enabled == true) needsPairing(token)
    }

    /** Only for the token RomM refused: a pairing made meanwhile isn't undone. */
    private suspend fun needsPairing(token: String) {
        if (credentials.current.value?.token == token) store.setNeedsPairing(true)
    }

    private fun Inputs.state() = sendToDeviceState(settings, credentials, store)

    /** Where the socket connects while games can come. */
    private fun Inputs.liveTarget(): LiveNudges.Target? {
        val url = settings.server?.url ?: return null
        val token = credentials?.token ?: return null
        return if (state() is SendToDeviceState.Ready) LiveNudges.Target(url, token) else null
    }

    /** What to tell RomM: nothing until paired as a device with a RomM that has device installs. */
    private fun Inputs.declaration(): Declaration? {
        val creds = credentials ?: return null
        val deviceId = creds.deviceId ?: return null
        if (!settings.onboardingComplete || store.needsPairing || !sendsToDevices(settings.serverVersion)) return null
        return Declaration(deviceId, store.receive, creds.token)
    }

    companion object {
        /** How long nudges are gathered before claiming once for all of them. */
        const val NUDGE_GATHER_MS = 500L

        /** How long a part of [run] that failed waits before it starts again. */
        const val RESTART_AFTER_MS = 10_000L
    }
}
