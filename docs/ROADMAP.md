# Loom roadmap

Phase 1 (the MVP: pairing, catalog sync, offline browsing, platform mapping, download queue with atomic installs and checksum verification, offload and storage view) is what's built. This file plans the next two phases; nothing below is implemented yet.

## Phase 2: background refresh and save sync

### Scheduled catalog refresh
- A periodic `CatalogSyncWorker` (every 6–12 h, network required, battery not low) running the same incremental sync as the manual one: `updated_after` watermark plus the `/api/roms/identifiers` diff for deletions.
- Settings: interval, Wi-Fi only.
- Optional: RomM 5.4's install queue ("send to device" from the web UI), via `POST /api/devices/{id}/installs/claim` and the Socket.IO `/devices` namespace. Loom's token is already device-bound, which that feature requires.

### Save sync
RomM ≥ 4.9 negotiates sync server-side, so Loom does not invent its own conflict logic:
1. Loom lists local saves for installed ROMs: `{rom_id, file_name, slot, emulator, content_hash, updated_at, file_size_bytes}`. `content_hash` is the MD5 of the file. For a zipped folder save it is the MD5 over the sorted `name:md5` lines, matching RomM's `_compute_zip_hash`.
2. `POST /api/sync/negotiate`, scoped with `rom_ids` to what's installed, returns operations: `upload`, `download`, `conflict`, `delete`, `no_op`.
3. Loom carries out the operations:
   - Upload: `POST /api/saves?rom_id&emulator&slot&device_id&content_hash`, multipart field `saveFile`.
   - Download: `GET /api/saves/{id}/content?device_id`, then `POST /api/saves/{id}/downloaded`.
4. `POST /api/sync/sessions/{id}/complete`.

Per-emulator save-folder mapping:
- Fills the `saveDir` field the platform mapping JSON already reserves.
- Uses Argosy's `SavePathRegistry` as the reference for default paths (RetroArch per-core folders, DuckStation memory cards, Dolphin GCI folders, …).
- A save can be a file or a folder; folders are zipped for upload.

Stages:
1. **Manual push/pull.** The `PUSH_SAVES` and `PULL_SAVES` headless actions, wired in Phase 1, start working. A per-game "Sync saves" action is added.
2. **Watcher.** A `FileObserver` on each mapped save folder. After writes settle (a quiet period of roughly 30 s, and no write lock held by the emulator), the changed save is uploaded. Downloads stay manual or happen at sync time, to avoid overwriting a save mid-session.
- Conflicts surface in the queue screen with both timestamps; nothing is auto-resolved.

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
