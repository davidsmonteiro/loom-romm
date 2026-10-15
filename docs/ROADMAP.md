# Loom roadmap

Phase 1 (the MVP: pairing, catalog sync, offline browsing, platform mapping, download queue with atomic installs and checksum verification, offload and storage view) is built, and so are these parts of Phase 2: the scheduled catalog refresh, games sent from RomM's web UI, and save sync for 3DS games in Azahar, Switch games in Eden, DS games in melonDS and PSP games in PPSSPP, with RomM 5.4's save sync extras; and of Phase 3, the dual-screen layout, with layouts for any screen size. This file plans the rest of Phase 2 and Phase 3; everything below that isn't marked built is not implemented yet.

## Phase 2: background refresh and save sync

### Scheduled catalog refresh (built)
- `CatalogRefreshWorker` is periodic work every 6, 12 (the default) or 24 h. It needs a network (an unmetered one with "Only on Wi-Fi") and a battery that isn't low. "Only on Wi-Fi" holds covers to an unmetered network too: the prefetch after a sync waits for one, and no cover is fetched or sharpened on a metered one; covers already kept still show.
- It queues the manual sync's incremental `CatalogSyncWorker` (`updated_after` watermark plus the `/api/roms/identifiers` diff for deletions) as the same unique work, so a refresh never runs alongside a manual or headless sync.
- It does nothing until setup is finished, or while Loom is signed out.
- It first checks that RomM answers (the heartbeat). When RomM can't be reached it queues no sync: it retries three times over a few minutes, then waits for the next interval, so a refresh that finds RomM out of reach doesn't leave "Sync failed" in the library. Any other answer, a rejected token for one, queues the sync, which reports it.
- From app start the schedule follows the settings (Settings → Library sync: Off / 6 / 12 / 24 h, Only on Wi-Fi). It is updated in place, so restarting the app never puts the next refresh off.

### Games sent from RomM (built)
RomM 5.4's **Install on device** sends a game from the web UI to a device as an install request, which the device claims and reports on ([ADR 0013](adr/0013-receive-games-sent-from-romm-by-claiming-nudged-by-our-own-socket-io-client.md)). The owner's choices (2026-10-15): on by default, with a switch in Settings → Downloads; `done` is reported once the game is in the queue, which RomM shows as "Added to downloads".

- **Declaring.** RomM lists a device only once it declares `remote_install`. Loom does at every start and whenever the switch changes (`PUT /api/devices/{id}` with the whole capability dict), and again before the next claim if RomM was out of reach. Signing out declares it off first, if RomM answers within 3 s. Signed out while RomM was out of reach, uninstalled, or with a token RomM no longer takes, Loom can't tell it, and RomM lists the Thor until it's deleted on RomM's Devices page.
- **Claiming.** `POST /api/devices/{id}/installs/claim` hands back every request the device holds and hasn't reported. Loom claims at start, each time it comes to the front, after pairing again, when RomM answers again after being out of reach, and every 15 minutes in the background (periodic work, only while games can come). Only the device the token is bound to can claim: a token from a pair code or pasted, or a device deleted on RomM's Devices page, gets "Pair again" in Settings → Downloads and a note in the queue, and no more claims until Loom pairs again.
- **The socket.** While Loom is in front it holds RomM's `/devices` Socket.IO namespace open (Engine.IO 4 over a WebSocket, a small client on Loom's own OkHttp, with its own HTTP client) and claims as soon as RomM says a game was sent; a request RomM says was cancelled takes the game out of the queue if it hasn't started being moved into place. The socket connects again after a drop (1 s doubling to a minute, jittered), RomM not answering its join within 30 s counting as one, and moves when the address or the token changes. While it can't connect, Loom claims every minute; a front that answers the upgrade itself three times in a row isn't tried again until Loom next comes to the front, though not for a proxy saying it can't reach RomM (502, 503, 504, Cloudflare's 52x and 530, as while RomM restarts), which is tried again as after a drop.
- **Queueing.** Each request is queued as X in the library does, by Loom's own per-platform file rules (the request's `file_ids` are ignored). A game added on RomM since the last sync is placed by the platform RomM gives, and the library syncs. A game whose platform's folder Loom would ask about (ADR 0009) waits in the queue in WAITING(FOLDER): "Choose its folder", the queue opening on it ahead of the downloads and the saves, A asks, a notification says so, and "Pause all" can't release it into the default folder. A folder chosen while the game was being queued still releases it, and a game not in the library yet names its platform as the library does. The queue marks sent games "From RomM".
- **Reporting.** `done` once the game is in the queue (or already was), `already_installed` when it's on the card, `failed` with Loom's reason (cut to 500 characters) only for a lasting problem, such as a game sent again whose download failed in a way retrying can't help. A passing one (RomM out of reach, busy or failing for now, or the game's cancelled job still leaving the queue) isn't reported: the next claim returns the request. A report RomM answers with 404 means the request is gone, cancelled by the owner as Loom claimed it, say: the game queued for it leaves the queue again (one already queued from the library stays), and a cancel the socket brings meanwhile waits for the claim being handled.
- **Server switches.** Device installs switched off on the server (the heartbeat's `DEVICE_INSTALL.ENABLED`, a claim's 404, or the socket's refusal) stop the claims until a heartbeat says they're back. A server before 5.4 is never asked, and a heartbeat that says nothing of device installs counts as them off: a RomM built from source says "development" for its version, which reads as the newest.

Not built: a way to see in Loom which requests RomM still holds, and a faster background path than the 15-minute periodic claim (a persistent connection was turned down, ADR 0013).

### Save sync: 3DS games in Azahar (built)
RomM ≥ 4.9 plans sync server-side with negotiate. On 5.3.1, though, negotiate compares timestamps whenever another device uploaded last, so Loom decides from its own baseline and treats any disagreement as a save conflict ([ADR 0010](adr/0010-decide-save-sync-from-a-local-baseline.md)). Before replacing a save it backs it up on the card and restores by rename ([ADR 0011](adr/0011-back-up-saves-on-the-card-and-restore-by-rename.md)).

The owner's choices (2026-10-13): Azahar only for now; emulators that keep saves in `Android/data` aren't synced; conflicts always ask; automatic upload on; downloads only when asked; the `autosave` slot; saves only (no states, no 3DS extdata); built on RomM 5.3.1.

What's built:
- **Finding the save.** Each installed 3DS game's title ID comes from RomM (`save_target`, `title_id`) or the cartridge image's header. The save unit is Azahar's `sdmc/Nintendo 3DS/<id0>/<id1>/title/00040000/<low>/data/`, zipped under `data/` as Argosy does. On the Thor, Azahar's folders sit inside `ROMs/n3ds`; folder scans, adoption, downloads and offload never take them for games.
- **One run at a time.** A process-wide lock and one unique WorkManager job, because two runs could act on one save (and RomM before 5.4.0 cancels the device's other sessions when it negotiates). A run hashes each save as RomM does (reusing the hash while names, sizes and times are unchanged), negotiates in chunks of at most 500 ROM ids (each session completed before the next), and decides each game with `SyncDecider`.
- **Uploads.** In the background every 15 minutes, when Loom opens and when the card mounts (Settings → Saves → Upload saves automatically), with `PUSH_SAVES`, and from a game's details. Only once the save has been left alone for a minute and two hashes about 5 s apart agree. A 409 is a save conflict. Bytes RomM matches to an older version become the newest: RomM 5.4.0 promotes that version itself, and older RomMs get them again with `overwrite=true`.
- **Downloads.** Only when asked: Restore server save (a game's details or the queue), Keep server or Keep both, each after the user confirms the emulator is closed; and `PULL_SAVES`, which only replaces saves unchanged on the Thor or missing, and only within 30 s of the command (a later or repeated run only counts newer saves, since the emulator may be running by then). Each one re-hashes the Thor's save, downloads with `optimistic=false` (at most 256 MiB), checks RomM's hash and the save's layout, backs up the Thor's save, restores atomically, calls `/downloaded`, and only then writes the baseline. A newer save from another emulator is never applied without asking.
- **Conflicts.** Listed first in the queue with both sides; the user picks Keep Thor, Keep server, Keep both or Skip (the default, and B). Keep both uploads the Thor's save outside the slot under a unique name.
- **Telling the user.** A game's details say where its save stands; the queue lists conflicts, saves to restore and the games synced lately; notifications for conflicts and newer saves on RomM open the queue; Settings shows how the last background sync went.
- **Reinstalls.** New installs derive RomM's `client_device_identifier` from `ANDROID_ID`, so pairing again reuses the same RomM device; backups live on the card in `.loom/save-backups`. An install that first paired with 0.1 keeps its stored random identifier (`allowBackup` is off, so a reinstall loses it): its first reinstall pairs as a new RomM device, and later ones as that one.

Corrections to the first plan (design review, 2026-10-13):
1. There is no `delete` action before RomM 5.4.0.
2. 5.3.1 ignores the upload's `content_hash`. Loom sends it anyway, for 5.4.0.
3. Downloads need `optimistic=false`: the default records the device as synced before Loom has stored anything.
4. "Loom does not invent its own conflict logic" can't hold on 5.3.1: negotiate falls back to timestamps when another device uploaded last and can ask for a download over unsynced progress. Loom's baseline rule wins (ADR 0010).
5. The platform mapping's `saveDir: String?` can't express saves found by title ID or made of folders: each emulator gets a resolver instead.
6. No `FileObserver`: Azahar writes through SAF, which may bypass the path Loom would watch. Scanning a few dozen saves' sizes and times is cheap and reliable.

### Save sync: Switch games in Eden (built)
The owner moved Eden's save folder out of `Android/data` to the card, `ROMs/saves/Eden` (2026-10-14), so Switch games join the same save sync: the same baseline, uploads, restores, backups and conflicts as Azahar's, as emulator `eden` in the `autosave` slot.

- **Finding the save.** A game's save is `user/save/0000000000000000/<profile>/<title ID>/` in Eden's save folder (`ROMs/saves/Eden`, or the one chosen in Settings → Saves). One profile is used as is; with several, Settings → Saves asks whose saves to sync, and until then the games say why they wait. The all-zeros profile holds the saves of games that save per console (Argosy's list of them is reused), never a user's. The title folder is zipped under its own name, as Argosy's Switch handler does on RomM 5.3.1, so archives restore either way.
- **Title IDs.** RomM 5.3.1 and 5.4.0 never give one for Switch games (they read them without keys). Loom reads it from the game's XCI or NSP (XCZ, NSZ) with the `header_key` in the owner's `prod.keys` (the ROMs folder, or its `switch` folder): the partition tables and each NCA's first 0x400 bytes, AES-XTS-decrypted (ADR 0012). The key is read only when a game's title ID isn't known, held for that read and dropped; it is never logged, stored or uploaded. The ID is cached per game with the image's path, size and time. Without a usable key, the game's details ask the owner which of Eden's save folders is the game's (newest first; the other games' folders aren't offered), and the answer is remembered. An update or DLC alone keeps no save; two games with one title ID sync neither, as each would restore over the other's save.
- **The saves folder.** `ROMs/saves` is never a system folder: not detected, offered, adopted from, accepted as a platform's folder, or offloaded from.

### Save sync: RomM 5.4 extras (built)
The owner's server runs RomM 5.4.0 (2026-10-15). Every extra Loom uses does nothing on 5.0 to 5.3, or only shows up when 5.4 answers with it, so none needs a version check, and Loom's own rule still decides (ADR 0010).

- **Baselines on RomM.** `/downloaded` (after a restore, or an adopted save) sends the hash Loom checked, so 5.4.0 keeps a baseline for the Thor: RomM's own hash, even for a DS save whose Argosy trailer came off. Uploads already sent theirs.
- **Promoted versions.** Bytes an older version holds are made the newest by 5.4.0 itself, and aren't uploaded a second time.
- **Deleted and rolled-back saves ask.** `delete` (the save the Thor holds was deleted on RomM, and the slot is empty) is a conflict offering **Upload again** and Skip; a `download` of an older version Loom's rule calls gone back says RomM's newest save was deleted. Loom never deletes a save on the Thor.
- **The Thor's device on RomM.** Sync switched off on RomM's Devices page says so, without Pair again, which wouldn't help; a deleted device asks to pair again, for Restore and the conflict answers too.
- **Not used:** negotiate's `emulators` filter. With it, RomM takes the slot head from Loom's emulators only, so a newer save from another emulator would never reach Loom's check (ADR 0010).
- **Settings → Saves** has a card per emulator saying where its saves are; A opens its folder in full, how to set the emulator up, and a folder browser to choose another (Eden's also choose the profile).
- A RomM save Loom can't put in place of the Thor's, or larger than it downloads, is one conflict saying why, with Keep Thor and Skip only, not a failure every sync; a save too large to sync says so once, as unsupported.

### Save sync: DS games in melonDS, PSP games in PPSSPP (built)
The owner's choices (2026-10-15): standalone melonDS and PPSSPP, with saves in `ROMs/saves/melonDS` and `ROMs/saves/PPSSPP`, beside Eden's; emulator ids `melonds` and `ppsspp`, as Argosy and RomM's web player use, with `ppsspp_gold` counted as `ppsspp`. The design is in [ADR 0014](adr/0014-single-file-and-prefix-save-units-and-psp-disc-ids.md).

- **DS.** One raw `.sav` per game, named after the installed ROM file as melonDS names it, uploaded as itself (`<ROM>.sav`). The empty `.sav` melonDS makes on a first run is no save. Argosy's hardcore trailer comes off before a restore; an archive, a DeSmuME save or an empty file on RomM is a conflict Loom can't take. DSiWare isn't synced (its save is in melonDS's DSi NAND); two installs with one save name sync neither.
- **PSP.** Every folder in the memory stick's `PSP/SAVEDATA` starting with the game's disc ID, zipped each under its own name, installed game data left out by Argosy's rule. A restore takes the archive's folders of the game, removes the ones it lacks, and leaves installed data and other games' folders alone.
- **Disc IDs.** RomM's `save_target` (a folder prefix) or `title_id`, else read on the Thor from an ISO, CSO v1 or PBP, kept in `save_keys` with what was looked at. CHDs are left to RomM. Disagreement, or no ID, asks the owner, who picks from the memory stick's disc IDs; the pick stands until RomM or the image names another disc.
- **Settings.** melonDS's and PPSSPP's cards in Settings → Saves, each found in `ROMs/saves` or chosen with the folder browser.

Next:
- DuckStation, Dolphin and Cemu stay out of reach in `Android/data` unless they can move their saves to shared storage, as Eden did.
- **Eden, later:** Switch title IDs from RomM once it reads them (5.4 still can't without keys), as a cross-check of the one read on the Thor; the owner's pick of a save folder offered from the queue too, not only from the game's details.
- **melonDS and PPSSPP, later:** DeSmuME saves converted for melonDS rather than refused; RomM's web player's PSP saves, whose shape isn't known yet (each is a conflict saying so, for now); a PSP game that saves under another disc's ID, which the owner's single pick can't cover.
- A result for `PULL_SAVES` (a broadcast when the run ends) so a frontend can start the emulator as soon as the restores are done, not after a fixed wait.
- Smaller: a per-game "don't sync on this device" switch (kept by Loom: RomM's untrack covers one save version), Wi-Fi only, and a `FileObserver` speed-up if a spike shows it sees Azahar's writes.

## Phase 3: launch shim and dual screen

### Launch shim
Loom registers as a "player" in Cocoon. For each launch:
1. Pull the latest save.
2. Launch the real emulator.
3. Detect the return: the emulator's task leaves the foreground.
4. Push the save.

Ways to register:
- **Contribute a player definition** to CocoonFE's `platforms/*.json` (`amStartArguments` with `{file.path}`). The repo accepts PRs.
- **Tag files** (`.localgameid`) or pinned shortcuts (`ShortcutManager.requestPinShortcut`). Cocoon picks these up when it is the default launcher.

Risks:
- Cocoon's playtime tracking would measure Loom's activity rather than the emulator's, or stop when Loom hands off. Playtime could be pushed to RomM's `/api/play-sessions` instead, but that doesn't fix Cocoon's own counter.
- Emulators launched from another app's task may lose Cocoon's per-game overrides.
- Detecting the return reliably needs either `UsageStatsManager` (a special permission) or the emulator finishing back into Loom's task.

### Dual-screen layout (built)
This section's first draft had the focused game's cover and details on the top screen and the list and queue on the bottom one. The owner reversed it (2026-10-15): the list and all input stay on the top screen, where Cocoon opens Loom, and the bottom screen shows a passive panel of what the pad is on ([ADR 0015](adr/0015-second-screen-presentation-and-adaptive-layout.md)).

- **The panel.** An Android `Presentation` on the second display, picked from `DISPLAY_CATEGORY_PRESENTATION` by its flags (secure, not private, not off, not Loom's own), the built-in screen ahead of a monitor. It never takes the pad's focus; it stays touchable, so a tap doesn't fall through to Cocoon's bottom screen and take the pad, and a tap does nothing. Its Compose content is laid out for its own screen, and is only handed state: the screens on top publish what it shows.
- **What it shows.** The library's focused game (cover, title, state, save, and what RomM says of it), with a strip of what the queue holds; the queue's focused download, or a save conflict's two sides; the pairing QR code during setup; the Loom mark otherwise.
- **When it goes.** On Home or a game launch, when another app is resumed on the other screen, when the bottom screen turns off or goes, while Loom is on the bottom screen itself, with **Use the second screen** off (Settings → Controls & screens), and in AYN's Focus Mode "Bottom screen", where the top screen says why. It comes back as each of these undoes. It goes dark after 3 minutes without a button press or a touch.
- **R3** hides it, so an app open on the bottom screen can be used while Loom stays in front, and brings it back; hidden, it stays hidden until R3 again or Loom is opened again (the owner's choice, after the plan).
- **Any screen size.** Each screen reads its window's size and follows Material's breakpoints (600 and 840 dp): the Thor's top screen is the layout it was designed for; narrower windows (the bottom screen, phones in portrait) pick the platform from the header, show one Settings section at a time (below 800 dp) and give dialogs the full width; wider ones show the focused game beside the games. Content fits without scrolling on the Thor's screens, phones in portrait and tablets; a phone on its side may scroll a dialog or a Settings section.
- **RomM's details.** The catalog (database v8) keeps each game's summary, cut to about 1,000 characters, release year, three genres and two companies in a table of its own, read only for the focused game. The first sync after the upgrade is a full one, queued on the first open.
- **Hero covers.** The panel's cover is made from RomM's large art once the focus has rested on a game for half a second, under "Only on Wi-Fi"; the same fetch sharpens the game's thumbnail. The newest 48 are kept.

Not built: anything on the panel for Settings (help for the focused section, say, or the card's storage), RomM's screenshots of the game, and a way to act from the bottom screen.

## Later: design refresh
- Rework the logo and the overall look (palette, type, layout) so Loom sits naturally next to Cocoon. Phase 1's warp-and-weft logo and dark palette are placeholders until then.
