# Loom

Loom moves games from a RomM server into the folders the Cocoon frontend scans on an AYN Thor, removes them again when space is needed, and (later) keeps saves in sync. Cocoon stays the place to browse and play; Loom is the store you visit occasionally.

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
The subfolder of the ROMs root holding one system's games, the one Cocoon is set up to read for it, e.g. `snes`, `gc`, `n3ds`.
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
The queued work of turning one ROM into an install.
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
