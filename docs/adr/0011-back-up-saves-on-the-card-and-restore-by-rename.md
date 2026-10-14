# Back up saves on the card, and restore by rename

Before Loom replaces a save on the Thor (a download, Keep server, Keep both), it zips the save unit into `<volume root>/.loom/save-backups/<rom id>/`, on the card that holds the save and outside the ROMs root, and reads the copy back before going on; the newest ten per game are kept. A dot folder at the volume root is invisible to Cocoon, survives uninstalling Loom (unlike `Android/data`), and is on the same card as the save, so a backup never depends on internal storage having room. The new save is then unpacked into a work folder beside the save (`.loom-restore-<id>`, same card, so every move is a rename) and checked against the archive; a journal lists the new files and the old ones, the old files are moved aside and the new ones renamed into place one by one, and the result is hashed again. Any failure moves the old files back, and if Loom dies midway, or putting the old files back fails as well, the next run finishes undoing it from the journal before reading the save; the journal's list of old files tells an old file already back from a new one still in place. No move ever replaces a file. So the save on disk is always the old one or the new, checked one, and the backup is a second line of defence, not the only one.

## Considered Options

- Backups in Loom's private storage: lost on uninstall, and internal storage may be the fuller of the two.
- Backups inside the ROMs root: Cocoon would list them unless every name starts with a dot, and a user tidying a system folder could delete them.
- Rename the whole save folder in one step: atomic, but a crash between taking the old folder away and putting the new one in leaves the game with no save folder at all, and it doesn't extend to a unit made of several folders.
