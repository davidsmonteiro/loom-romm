# Loom roadmap

Phase 1 (the MVP: pairing, catalog sync, offline browsing, platform mapping, download queue with atomic installs and checksum verification, offload and storage view) is built, and so is Phase 2's save sync for 3DS games in Azahar. This file plans the rest of Phase 2 and Phase 3; everything below that isn't marked built is not implemented yet.

## Phase 2: background refresh and save sync

### Scheduled catalog refresh
- A periodic `CatalogSyncWorker` (every 6–12 h, network required, battery not low) running the same incremental sync as the manual one: `updated_after` watermark plus the `/api/roms/identifiers` diff for deletions.
- Settings: interval, Wi-Fi only.
- Optional: RomM 5.4's install queue ("send to device" from the web UI), via `POST /api/devices/{id}/installs/claim` and the Socket.IO `/devices` namespace. Loom's token is already device-bound, which that feature requires.

### Save sync: 3DS games in Azahar (built)
RomM ≥ 4.9 plans sync server-side with negotiate. On 5.3.1, though, negotiate compares timestamps whenever another device uploaded last, so Loom decides from its own baseline and treats any disagreement as a save conflict ([ADR 0010](adr/0010-decide-save-sync-from-a-local-baseline.md)). Before replacing a save it backs it up on the card and restores by rename ([ADR 0011](adr/0011-back-up-saves-on-the-card-and-restore-by-rename.md)).

The owner's choices (2026-10-13): Azahar only for now; emulators that keep saves in `Android/data` aren't synced; conflicts always ask; automatic upload on; downloads only when asked; the `autosave` slot; saves only (no states, no 3DS extdata); built on RomM 5.3.1.

What's built:
- **Finding the save.** Each installed 3DS game's title ID comes from RomM (`save_target`, `title_id`) or the cartridge image's header. The save unit is Azahar's `sdmc/Nintendo 3DS/<id0>/<id1>/title/00040000/<low>/data/`, zipped under `data/` as Argosy does. On the Thor, Azahar's folders sit inside `ROMs/n3ds`; folder scans, adoption, downloads and offload never take them for games.
- **One run at a time.** A process-wide lock and one unique WorkManager job, because negotiate cancels the device's other sessions. A run hashes each save as RomM does (reusing the hash while names, sizes and times are unchanged), negotiates in chunks of at most 500 ROM ids (each session completed before the next), and decides each game with `SyncDecider`.
- **Uploads.** In the background every 15 minutes, when Loom opens and when the card mounts (Settings → Downloads & saves → Upload saves automatically), with `PUSH_SAVES`, and from a game's details. Only once the save has been left alone for a minute and two hashes about 5 s apart agree. A 409 is a save conflict. Bytes RomM matches to an older version are uploaded again with `overwrite=true`.
- **Downloads.** Only when asked: Restore server save (a game's details or the queue), Keep server or Keep both, each after the user confirms the emulator is closed; and `PULL_SAVES`, which only replaces saves unchanged on the Thor or missing, and only within 30 s of the command (a later or repeated run only counts newer saves, since the emulator may be running by then). Each one re-hashes and backs up the Thor's save, downloads with `optimistic=false` (at most 256 MiB), checks RomM's hash, restores atomically, calls `/downloaded`, and only then writes the baseline. A newer save from another emulator is never applied without asking.
- **Conflicts.** Listed first in the queue with both sides; the user picks Keep Thor, Keep server, Keep both or Skip (the default, and B). Keep both uploads the Thor's save outside the slot under a unique name.
- **Telling the user.** A game's details say where its save stands; the queue lists conflicts, saves to restore and the games synced lately; notifications for conflicts and newer saves on RomM open the queue; Settings shows how the last background sync went.
- **Reinstalls.** New installs derive RomM's `client_device_identifier` from `ANDROID_ID`, so pairing again reuses the same RomM device; backups live on the card in `.loom/save-backups`.

Corrections to the first plan (design review, 2026-10-13):
1. There is no `delete` action before RomM 5.4.0.
2. 5.3.1 ignores the upload's `content_hash`. Loom sends it anyway, for 5.4.0.
3. Downloads need `optimistic=false`: the default records the device as synced before Loom has stored anything.
4. "Loom does not invent its own conflict logic" can't hold on 5.3.1: negotiate falls back to timestamps when another device uploaded last and can ask for a download over unsynced progress. Loom's baseline rule wins (ADR 0010).
5. The platform mapping's `saveDir: String?` can't express saves found by title ID or made of folders: each emulator gets a resolver instead.
6. No `FileObserver`: Azahar writes through SAF, which may bypass the path Loom would watch. Scanning a few dozen saves' sizes and times is cheap and reliable.

Next:
- **melonDS (DS) and PPSSPP (PSP)**, once set up with saves on the SD card (melonDS beside the ROMs, PPSSPP's memory stick on the card): one resolver each, plus fixture tests.
- **Eden (Switch)**, the only `Android/data` emulator with real saves on the Thor: a spike to move its `nand` to shared storage (`nand_directory` in `config.ini`, all-files access granted to Eden). DuckStation, Dolphin and Cemu stay out of reach unless they can do the same.
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
