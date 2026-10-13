# Decide save sync from a local baseline

RomM 5.3.1's `POST /api/sync/negotiate` compares a device's save with the slot head through the device's sync row, and that row only exists for the version the device itself last uploaded or downloaded. Whenever another device or the web player uploaded last, there is no row and negotiate falls back to comparing timestamps: it can answer `download` over progress made on the Thor (if the Thor's file is older) or `upload` that RomM then refuses with 409. It also has no tombstones, so a slot deleted on the server comes back as an `upload`. Loom therefore keeps its own baseline per slot (its hash of the save unit, plus the slot head's id, hash and time at the last sync) and decides with a pure rule, `SyncDecider`. An upload happens only when the server is unchanged since the baseline, and a download only when the Thor's save is unchanged or absent and the newer head came from the same emulator. Identical bytes are adopted as the new baseline. Everything else is a save conflict for the user, and so is any answer from negotiate that differs from Loom's own. Negotiate is still called, because it opens the session RomM counts operations in and it sees heads Loom doesn't know about, but it never decides on its own.

## Considered Options

- Follow negotiate (the first roadmap plan): loses a save the first time another device uploads while the Thor holds unsynced progress.
- Newest wins: compares the Thor's clock with the server's, and a wrong clock silently picks the older save.
- Wait for RomM 5.4.0, which keeps hash baselines on the server: it shipped days before this decision and the server runs 5.3.1. When Loom supports it, its answer becomes one more cross-check; Loom's rule stays.
