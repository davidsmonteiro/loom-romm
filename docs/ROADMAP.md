# Loom roadmap

Phase 1 (the MVP: pairing, catalog sync, offline browsing, platform mapping, download queue with atomic installs and checksum verification, offload and storage view) is built, and so are three parts of Phase 2: the scheduled catalog refresh, save sync for 3DS games in Azahar, and save sync for Switch games in Eden. This file plans the rest of Phase 2 and Phase 3; everything below that isn't marked built is not implemented yet.

## Phase 2: background refresh and save sync

### Scheduled catalog refresh (built)
- `CatalogRefreshWorker` is periodic work every 6, 12 (the default) or 24 h. It needs a network (an unmetered one with "Only on Wi-Fi") and a battery that isn't low. "Only on Wi-Fi" holds covers to an unmetered network too: the prefetch after a sync waits for one, and no cover is fetched or sharpened on a metered one; covers already kept still show.
- It queues the manual sync's incremental `CatalogSyncWorker` (`updated_after` watermark plus the `/api/roms/identifiers` diff for deletions) as the same unique work, so a refresh never runs alongside a manual or headless sync.
- It does nothing until setup is finished, or while Loom is signed out.
- It first checks that RomM answers (the heartbeat). When RomM can't be reached it queues no sync: it retries three times over a few minutes, then waits for the next interval, so a refresh that finds RomM out of reach doesn't leave "Sync failed" in the library. Any other answer, a rejected token for one, queues the sync, which reports it.
- From app start the schedule follows the settings (Settings → Library sync: Off / 6 / 12 / 24 h, Only on Wi-Fi). It is updated in place, so restarting the app never puts the next refresh off.

Not built, as it needs RomM 5.4 and Loom's server runs 5.3.1: the install queue ("send to device" from the web UI), via `POST /api/devices/{id}/installs/claim` and the Socket.IO `/devices` namespace. Loom's token is already device-bound, which that feature requires.

### Save sync: 3DS games in Azahar (built)
RomM ≥ 4.9 plans sync server-side with negotiate. On 5.3.1, though, negotiate compares timestamps whenever another device uploaded last, so Loom decides from its own baseline and treats any disagreement as a save conflict ([ADR 0010](adr/0010-decide-save-sync-from-a-local-baseline.md)). Before replacing a save it backs it up on the card and restores by rename ([ADR 0011](adr/0011-back-up-saves-on-the-card-and-restore-by-rename.md)).

The owner's choices (2026-10-13): Azahar only for now; emulators that keep saves in `Android/data` aren't synced; conflicts always ask; automatic upload on; downloads only when asked; the `autosave` slot; saves only (no states, no 3DS extdata); built on RomM 5.3.1.

What's built:
- **Finding the save.** Each installed 3DS game's title ID comes from RomM (`save_target`, `title_id`) or the cartridge image's header. The save unit is Azahar's `sdmc/Nintendo 3DS/<id0>/<id1>/title/00040000/<low>/data/`, zipped under `data/` as Argosy does. On the Thor, Azahar's folders sit inside `ROMs/n3ds`; folder scans, adoption, downloads and offload never take them for games.
- **One run at a time.** A process-wide lock and one unique WorkManager job, because negotiate cancels the device's other sessions. A run hashes each save as RomM does (reusing the hash while names, sizes and times are unchanged), negotiates in chunks of at most 500 ROM ids (each session completed before the next), and decides each game with `SyncDecider`.
- **Uploads.** In the background every 15 minutes, when Loom opens and when the card mounts (Settings → Saves → Upload saves automatically), with `PUSH_SAVES`, and from a game's details. Only once the save has been left alone for a minute and two hashes about 5 s apart agree. A 409 is a save conflict. Bytes RomM matches to an older version are uploaded again with `overwrite=true`.
- **Downloads.** Only when asked: Restore server save (a game's details or the queue), Keep server or Keep both, each after the user confirms the emulator is closed; and `PULL_SAVES`, which only replaces saves unchanged on the Thor or missing, and only within 30 s of the command (a later or repeated run only counts newer saves, since the emulator may be running by then). Each one re-hashes and backs up the Thor's save, downloads with `optimistic=false` (at most 256 MiB), checks RomM's hash, restores atomically, calls `/downloaded`, and only then writes the baseline. A newer save from another emulator is never applied without asking.
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

Next:
- **melonDS (DS) and PPSSPP (PSP)**, once set up with saves on the SD card (melonDS beside the ROMs, PPSSPP's memory stick on the card): one resolver each, plus fixture tests.
- DuckStation, Dolphin and Cemu stay out of reach in `Android/data` unless they can move their saves to shared storage, as Eden did.
- **Eden, later:** Switch title IDs from RomM once it reads them (5.4 still can't without keys), as a cross-check of the one read on the Thor; the owner's pick of a save folder offered from the queue too, not only from the game's details.
- **RomM 5.4 extras**, behind a version check: `content_hash` on `/downloaded` (server-side baselines), negotiate's `emulators` filter, and `delete` as a question about deleting the local save. Loom's own rule stays as a cross-check.
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

### Dual-screen layout
- Use the `Presentation` API on the Thor's second display (`DisplayManager.getDisplays(DISPLAY_CATEGORY_PRESENTATION)`).
- The top screen shows cover art and details for the focused game. The bottom screen shows the list and the queue.
- With a single display, Loom falls back to today's layout.

## Later: design refresh
- Rework the logo and the overall look (palette, type, layout) so Loom sits naturally next to Cocoon. Phase 1's warp-and-weft logo and dark palette are placeholders until then.
