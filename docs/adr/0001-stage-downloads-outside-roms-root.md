# Stage downloads outside the ROMs root

Cocoon indexes every file in a system folder whose name doesn't start with a dot, and since 3.05 it watches those folders for new files. So a `Game.sfc.part` next to its final location would surface as a game mid-download. Loom therefore writes download parts (still named `*.part`) and assembles the finished install in a staging area at `<volume root>/.loom-staging/`, on the same volume as the ROMs root, and makes it visible with one rename (promote). If that rename ever fails with EXDEV, Loom falls back to copying into a dot-prefixed `.<name>.loomtmp` inside the system folder (which Cocoon ignores) and renaming within that folder.

## Considered Options

- `<name>.part` beside the final file (the original brief): visible to Cocoon's watcher.
- `Android/data/<pkg>/` on the SD card: a separate mount on Android 11+, so moves out of it fail with EXDEV, and it's wiped on uninstall.
