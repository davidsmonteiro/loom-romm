# Draw dialogs in the activity's window, not Compose's Dialog

Compose's `Dialog` (and `Popup`) open a separate window, whose key events never pass through `MainActivity.dispatchKeyEvent`, so the pad's A button (remapped there to DPAD_CENTER, because Compose's `clickable` doesn't handle BUTTON_A) would do nothing inside them. Loom's `LoomDialog` is instead an overlay drawn by a root `DialogHost` in the activity's own window, with focus trapped inside and B wired to dismiss.
