# Loom

Loom is a small companion app for the [Cocoon](https://github.com/inssekt/CocoonFE) frontend on the AYN Thor. It copies games from your self-hosted [RomM](https://github.com/rommapp/romm) server into the folders Cocoon already scans, and removes them again when you need space. You still browse and play in Cocoon; Loom is the store you visit now and then.

![Library](docs/screenshots/library-grid.png)

Loom does not launch games, replace the home screen, or emulate anything. Save sync is planned for Phase 2 ([docs/ROADMAP.md](docs/ROADMAP.md)).

## What it does

- **Pairs without typing a token.** Loom shows a QR code and an 8-character code. You approve it on your phone in RomM's web UI. RomM's own pair code and a pasted token also work.
- **Keeps an offline copy of the catalog.** After the first sync you can browse every platform, with covers, with no network.
  - Later syncs only fetch what changed. Deleted games are detected by comparing ID lists.
  - Covers are fetched in the background after a sync and kept at the size the library shows them, about 20 KB each (roughly 400 MB for 20,000 covers). They live in Loom's cache, so Android may trim them when storage runs low; the next sync fetches them again.
- **Puts each game in the right system folder.** For example `ROMs/snes/`, `ROMs/gc/` or `ROMs/n3ds/`, from a mapping you can edit. It defaults to Cocoon's folder names.
- **Never shows Cocoon a half-written file.**
  - Downloads go to a hidden staging folder on the same SD card.
  - Every file is checked against RomM's checksums.
  - The finished game appears with a single rename.
- **Survives interruptions.** Pausing, the screen turning off, the app being killed, the SD card being pulled, or changing the server address in Settings: downloads resume where they stopped.
- **Offloads.** Removing a game frees the space and keeps it in the library, so it can come back with one press.

## Requirements

| | |
|---|---|
| RomM | 5.0 or newer. Built and tested against **5.3.1**; 5.4 is compatible. |
| Device | Android 11+ (minSdk 30). Designed for the AYN Thor's top screen, which runs Android 13. |
| Permissions | **All files access**, to write into the folders Cocoon scans. Loom is sideloaded, so Play policy doesn't apply. Also notifications, and an optional battery-optimisation exemption so downloads restart in the background. |

## Build and install

You need JDK 17 and the Android SDK with `platforms;android-37.0` and `build-tools;37.0.0`:

```sh
sdkmanager "platforms;android-37.0" "build-tools;37.0.0"
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Release builds are minified with R8. To sign them, create a `keystore.properties` file in the repo root; it's gitignored. Without it, the release APK is built unsigned.

```properties
storeFile=/path/to/loom.jks
storePassword=…
keyAlias=loom
keyPassword=…
```

Then run `./gradlew assembleRelease`. To open Loom from Cocoon, add it from Cocoon's app drawer.

## First run

1. **Server address.** One address, whichever reaches RomM from the Thor:
   - a local one, e.g. `192.168.1.10:8080`;
   - a Tailscale IP or MagicDNS name, e.g. `100.64.0.5:8080`;
   - or your domain, e.g. `romm.example.org`.

   Without a scheme, LAN and Tailscale hosts try `http://` first and everything else tries `https://` first. Change it any time in Settings → Server & pairing; queued downloads continue from the new address.
2. **Pair.** Scan the QR code with your phone and approve "Loom" in RomM.
   - Alternatively, create an API token in RomM's web UI and use **Pair device** to get an 8-character code, or paste a token.
   - Loom asks for these scopes: `me.read platforms.read roms.read assets.read assets.write devices.read devices.write`. The asset and device scopes are for the planned save sync, so you won't have to pair again.
3. **All files access.** Grant it in Android settings, then press B to come back.
4. **ROMs folder.** Pick the folder that holds your system folders.
   - Loom suggests `<SD card>/ROMs`.
   - The folder browser highlights folders whose names match Cocoon's systems.
5. **Background downloads.** Allow notifications and exempt Loom from battery optimisation.
6. **First sync.**

## How games land on disk

Each game becomes exactly **one entry** in its system folder, so it can appear in a single step and Cocoon shows it once:

| On RomM | On the SD card |
|---|---|
| One file (`.sfc`, `.iso`, `.chd`, `.nsp`…) | `ROMs/snes/Chrono Trigger (USA).sfc` |
| A folder with one game file | The file, taken out of the folder |
| Several discs, or a disc split into tracks (cue/bin, gdi) | `ROMs/psx/Final Fantasy VII (USA).m3u/`, containing the discs and a generated `Final Fantasy VII (USA).m3u` (the ES-DE "directory as file" layout Cocoon supports) |
| A zip/7z on a platform set to **unpack** (disc systems, DS, 3DS, Switch…) | Unpacked, then laid out as above. A single file inside takes the game's name: `ROMs/ps2/Super Bust-A-Move (USA).bin`, not `SLUS-20115 (1.00).bin` |
| A zip on a platform set to **keep** (arcade, most cartridge systems) | The archive as it is |
| Anything else with several files (PS3, Wii U…) | `ROMs/ps3/Game (USA)/`, mirroring RomM. Cocoon needs **Scan Subfolders** on to see these. |

Behind the scenes:

- **Staging.** Downloads are written to `<SD card>/.loom-staging/` as `*.part` files, outside the ROMs folder, so Cocoon's folder watcher never sees them. They resume with HTTP Range after any interruption.
- **Verification.**
  - Plain files are checked against RomM's SHA-1, MD5 or CRC32.
  - Zips and 7z files are checked member by member, because RomM hashes their unpacked contents ([ADR 0003](docs/adr/0003-verify-archives-by-member-hashes.md)).
  - Platforms RomM never hashes (Switch, Wii U, PS3…) are checked by size.
- **Name clashes.**
  - A file that's already on the card under the same name is never overwritten: the download stops with an explanation.
  - Two RomM games that would get the same name are told apart with a ` (romm-<id>)` suffix.
- **Games you already had.** Games already on your card in the expected place, with a matching size, are picked up as "On device" without downloading.

## Platform folders

Defaults map RomM's platform slugs to Cocoon's folder names. For example, `ngc → gc`, `3ds → n3ds`, `sms → mastersystem`, `dc → dreamcast`. RomM's single `arcade` slug is split by its server folder into `mame`, `fbneo`, `naomi` and `atomiswave`. The defaults are in [`core-storage/src/main/resources/platform-mapping.default.json`](core-storage/src/main/resources/platform-mapping.default.json); tests check them against RomM 5.3.1's slug list and Cocoon's `platforms/index.json`.

In **Settings → Platform folders** you can change each platform's:
- folder
- archive handling (keep or unpack)
- multi-disc layout (`Game.m3u` folder or plain folder)
- which RomM file categories to download (game only by default; add `update` and `dlc` for Switch, for example)

**Export** writes the full mapping to `Download/loom-mapping.json`; **Import** reads it back. Platforms Loom has no default for use the server's folder name and are marked **Check**.

## Controls

| Button | Library | Queue | Everywhere |
|---|---|---|---|
| D-pad | Move | Move | Move |
| A | Game details | Pause / resume / retry | Select |
| B | | | Back (closes dialogs) |
| X | Download, or offload (asks first) | Cancel (asks first) | |
| Y | Search | | |
| L1 / R1 | Previous / next platform | | |
| Start | Open the queue | | |
| Select | Settings | | |

For Nintendo-layout pads, use Settings → Controls → **Swap A and B**. Touch works too, and the first button press after a tap still acts (it doesn't just bring the focus ring back). Loom runs full screen; swipe from an edge to see the system bars.

## Scripting (adb, Termux)

```sh
# Incremental or full catalog sync
adb shell am broadcast -n ro.davidmontei.loom/.headless.CommandReceiver -a ro.davidmontei.loom.action.SYNC_CATALOG
adb shell am broadcast -n ro.davidmontei.loom/.headless.CommandReceiver -a ro.davidmontei.loom.action.SYNC_CATALOG --ez full true

# Reserved for Phase 2 (answers "not available yet" for now)
adb shell am broadcast -n ro.davidmontei.loom/.headless.CommandReceiver -a ro.davidmontei.loom.action.PUSH_SAVES
adb shell am broadcast -n ro.davidmontei.loom/.headless.CommandReceiver -a ro.davidmontei.loom.action.PULL_SAVES
```

From Termux, run the same commands without `adb shell`. They work even when Loom is in the background.

`am broadcast` prints the result. The result code is:
- `0`: queued
- `1`: not paired
- `2`: not available yet
- `3`: unknown command

The receiver is exported without a permission because its commands only queue work.

Long-press Loom's icon for the **Sync library** and **Downloads** shortcuts.

## Troubleshooting

| You see | Do this |
|---|---|
| "A proxy or login page is in the way" | Something like Cloudflare Access answered instead of RomM. Use an address that reaches RomM directly (Tailnet/LAN). |
| "already exists on the SD card and wasn't installed by Loom" | Remove the old copy, or resync so Loom adopts it if it's the same game. |
| "This card can't hold files over 4 GB (FAT32)" | Reformat the SD card as exFAT. |
| "Needs X free on the SD card" | Offload something (X on an installed game) and retry from the queue. |
| Downloads stop in the background | Allow background downloads in Settings → Downloads. Opening Loom resumes them either way. |
| "RomM rejected Loom's token" | The token expired or was revoked. Use Settings → Server & pairing → Pair again. Your library and games stay. |
| A multi-disc game shows up as one game per disc | RomM lists loose `(Disc N)` files as separate games; keep each multi-disc game in its own folder in RomM so it arrives as one `Name.m3u/` entry. See [docs/ROADMAP.md](docs/ROADMAP.md#needs-a-decision). |
| Multi-disc PS1 games don't start in DuckStation via Cocoon | A known Cocoon issue with relative `.m3u` paths for DuckStation's default player (CocoonFE discussion #414). |

## Development

```sh
./gradlew test             # all JVM tests: :core-romm, :core-storage, :app (Robolectric, SDK 33)
./gradlew lintDebug        # 0 errors, 0 warnings
./gradlew :core-romm:liveCheck -Promm.url=https://romm.example.org            # read-only probe of a real server
LOOM_TOKEN=rmm_… ./gradlew :core-romm:liveCheck -Promm.url=https://romm.example.org   # …plus platforms and a page of ROMs
```

`ScreenshotTest` renders each screen at the Thor's top-screen size into `app/build/screenshots/`.

Modules:
- **`:core-romm`** (Kotlin/JVM): the RomM API client and pairing. DTOs are checked field by field against RomM 5.3.1's committed OpenAPI schema.
- **`:core-storage`** (Kotlin/JVM): platform mapping, filename sanitiser, layout planner, `.m3u` builder, the download state machine and the transfer/verify/promote engine. Its tests use a Range-honouring fake RomM server and crash the engine at every phase.
- **`:app`:** Room, WorkManager, Hilt, Compose UI.

Further reading:
- [`CONTEXT.md`](CONTEXT.md): the project's vocabulary.
- [`docs/adr/`](docs/adr/): the decisions behind staging, per-file downloads, archive checks, pairing, the Keystore, Retrofit and the module split.
- [`docs/ROADMAP.md`](docs/ROADMAP.md): Phases 2 and 3.

## License and credits

Loom is free software under the [GNU General Public License v3.0](LICENSE).

- **[RomM](https://github.com/rommapp/romm)** (AGPL-3.0) is the server Loom talks to. Loom's API models follow RomM's OpenAPI schema.
- **[Argosy Launcher](https://github.com/rommapp/argosy-launcher)** (GPL-3.0), the official RomM Android client, was the reference for the pairing flow and API usage.
- **[Cocoon](https://github.com/inssekt/CocoonFE)**'s public platform list is the source of the default folder names. Loom is not affiliated with Cocoon.
- **[Inter](https://github.com/rsms/inter)** by Rasmus Andersson, SIL Open Font License 1.1.
- AndroidX, Jetpack Compose, Kotlin, OkHttp, Retrofit, Coil, Dagger/Hilt, Apache Commons Compress, XZ for Java and ZXing, under the Apache License 2.0.
