# Loom

Loom moves games from a RomM server into the folders the Cocoon frontend scans on an AYN Thor, removes them again when space is needed, and keeps 3DS and Switch saves in sync with RomM. Cocoon stays the place to browse and play; Loom is the store you visit occasionally.

## Language

### The server side

**RomM server**:
The user's self-hosted RomM instance, the source of truth for which games exist.
_Avoid_: backend, API (when you mean the server as a whole)

**Server address**:
The one base URL Loom uses to reach the RomM server: a LAN address, a Tailscale address or name, or a public domain, whichever suits the user's setup.
_Avoid_: host, endpoint

**Pairing**:
Getting a client token for this device without typing it, by approving a code shown on the Thor from RomM's web UI.
_Avoid_: login, sign-in

**Platform**:
A RomM system (SNES, PlayStation, …) identified by its RomM slug.
_Avoid_: console, system (when you mean the RomM record)

**ROM**:
One game entry in RomM. It may be backed by one file or by several ROM files.
_Avoid_: game file, title

**ROM file**:
One file belonging to a ROM, with its own size, hashes and category (game, update, dlc, manual, …).
_Avoid_: part (that word is taken, see Download part), asset

### The device side

**Catalog**:
Loom's offline copy of the server's platforms, ROMs and ROM files.
_Avoid_: library (that's Cocoon's word for what it shows), cache, database

**ROMs root**:
The single directory on the Thor whose subfolders Cocoon scans, e.g. `/storage/9C33-6BBD/ROMs`.
_Avoid_: library path, base dir

**System folder**:
The subfolder of the ROMs root holding one system's games, the one Cocoon is set up to read for it, e.g. `snes`, `gc`, `n3ds`. The ROMs root's `saves` folder, where emulators' saves can be kept (`ROMs/saves/Eden`), is never one.
_Avoid_: platform folder, rom dir

**Candidate folder**:
A folder name a platform's system folder often has (Cocoon's, ES-DE's, other frontends'), e.g. `arcade`, `mame`, `fbneo` and `fba` for arcade. Loom picks among the candidates on the card, and asks when it can't tell.

**Platform mapping**:
The rule that decides, per platform, which system folder its games land in and how they are laid out (archive handling, multi-disc layout, which ROM file categories to include).
_Avoid_: folder config, path map

**Install**:
A ROM that is present in its system folder as a single complete entry Cocoon can see.
_Avoid_: download (the process, not the result), local copy

**Adopted install**:
An install Loom found already present at the expected path rather than downloading itself. Matched by name and size, or by name alone when nothing says what size it should be (a folder, or an archive RomM lists no contents for).

**Offload**:
Removing an install's files from the device while keeping its catalog entry, so it can be downloaded again with one press.
_Avoid_: uninstall, delete

**Multi-disc set**:
A ROM made of two or more launchable disc images, installed as a `Name.m3u/` folder that Cocoon shows as one game.

### Downloading

**Download job**:
The queued work of turning one ROM into an install. A job can wait for its platform's folder: when the card doesn't settle where the platform's games go and nobody was there to ask (a sent game), until the owner answers in the queue.
_Avoid_: task, transfer

**Download part**:
One ROM file being fetched as part of a download job.

**Staging area**:
A hidden directory at the root of the same storage volume, outside the ROMs root, where download parts and the not-yet-visible install are built.
_Avoid_: temp dir, cache

**Promote**:
The single rename that moves a finished install from the staging area into its system folder, at which point Cocoon can see it.
_Avoid_: publish, commit, move

**Install state**:
What the catalog shows per ROM: not installed, queued, downloading, installed or failed.

### Games sent from RomM

**Install request**:
RomM's record (RomM 5.4) of a game its owner sent to this device with **Install on device** in the web UI. Loom claims it, queues the game and reports how that went (done once it is in the queue, already installed, or failed with a reason), which ends it on RomM. A passing problem isn't reported: the next claim hands the request back.
_Avoid_: remote install, push install

**Sent game**:
A game in the queue because of an install request rather than X in the library. It downloads like any other, by the platform's own file rules, and the queue marks it "From RomM".
_Avoid_: remote install, pushed game

**Claim**:
Asking RomM for the install requests waiting for this device, which RomM then holds as taken until reported. Loom claims at start, when it comes to the front, after pairing again, when RomM answers again after being out of reach, every 15 minutes in the background, and while in front whenever RomM's device socket says a game was sent (every minute when the socket can't connect).
_Avoid_: poll, fetch

### Saves

**Save**:
A game's own saved progress as its emulator keeps it (cartridge save, memory card, save data). Not a save state.
_Avoid_: save state, save file (a save can be a folder)

**Save unit**:
Everything Loom syncs as one game's save, travelling as one file: a single save file, or folders zipped under fixed root names (`data/` for a 3DS title, the title ID folder itself for a Switch game, as Argosy does). Extdata and states are not part of it.
_Avoid_: save folder, save dir

**Slot**:
RomM's name for one line of save versions per ROM; device and server saves pair on (ROM, slot). Loom syncs one slot, `autosave`, and its newest version is the **slot head**.
_Avoid_: channel

**Baseline**:
What Loom recorded the last time a game's slot was in sync on the Thor: its own hash of the save unit, the slot head's id, hash and time, and the save's folder. Comparing each side with it tells which one changed. A save found in another folder (Azahar's folder changed, the game moved to another card) is compared as if it had never synced.
_Avoid_: last sync, sync row (that is RomM's record of the device's last download or upload)

**Save backup**:
A zip of a save unit Loom takes before it replaces that save, kept on the same card in `.loom/save-backups/<rom id>/` (outside the ROMs root), newest ten per game.
_Avoid_: snapshot, copy

**Save conflict**:
A slot where Loom won't pick a side on its own: both sides changed since the baseline, the server's save went back or came from another emulator, or RomM disagrees with Loom. The user chooses Keep Thor, Keep server, Keep both or Skip.

**Save sync run**:
One pass over some or all installed games' saves: read and hash each, negotiate with RomM, decide each game from its baseline, then upload, restore or record a conflict. Never two at once.
_Avoid_: sync session (that is RomM's record of one negotiate)

**Automatic upload**:
Uploading saves that changed on the Thor without being asked, in a save sync run every 15 minutes, when Loom opens and when the card mounts. Only once a save has been left alone for a minute (the quiet period) and reads the same twice a few seconds apart. On by default, with a switch in Settings; off, no run starts by itself, so Loom also stops noticing newer saves on RomM and new conflicts until asked.
_Avoid_: auto sync (nothing comes down from RomM without the user)

**Restore**:
Putting RomM's save in place of the Thor's, only when the user asks, once they have closed the emulator: after a save backup, and in one piece (the save on the card is always the old one or the checked new one). Keep server and Keep both restore too; `PULL_SAVES` restores only saves unchanged on the Thor or missing, and only within 30 seconds of the command.
_Avoid_: pull, download (for what the user does)

**Emulator id**:
The name a save is synced under in RomM's `emulator` field: `azahar` for Azahar, `eden` for Eden. A newer save on RomM under another emulator id is never restored without asking.

**Title ID**:
The 16-hex-digit id of a game's own title (the application, not an update or DLC), which names its save folder: in Azahar's `title/00040000/<low>/`, in Eden's `<profile>/<title ID>/`. For 3DS games RomM or the cartridge header gives it; for Switch games Loom reads it from the image with the header key, or the owner picks the save folder.
_Avoid_: game id, program id (an NCA's program id is a title ID only for the application's program)

**Header key**:
The `header_key` in the owner's `prod.keys`, dumped from their Switch: it decrypts the NCA headers holding a Switch game's title ID. Read from the card only when a title ID is needed, held in memory for that read; never stored, logged or uploaded.
_Avoid_: prod key, keys (prod.keys holds many keys; Loom only ever uses this one)

**Eden profile**:
A user folder (32 hex digits) in Eden's `user/save/0000000000000000/`, holding that user's saves. Loom syncs one profile's saves: the only one, or the one chosen in Settings. The all-zeros folder holds per-console saves and is no profile. When Eden holds a game's save in both (it saved per console, then per user), the owner picks which one Loom syncs; the other is left alone.

**Archival copy**:
A save version uploaded outside every slot, which negotiate never pairs. Keep both puts the Thor's save there, as "<game> (Thor <UTC date and time>).zip".
_Avoid_: backup (backups stay on the card)
