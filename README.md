# Loom

A small companion app for the [Cocoon](https://github.com/inssekt/CocoonFE) frontend on the AYN Thor. Loom puts games from your self-hosted [RomM](https://github.com/rommapp/romm) server into the folders Cocoon already scans, removes them when you need space, and (in a later phase) keeps saves in sync. You still browse and play in Cocoon.

> Work in progress: Phase 1 (MVP) is being built. See [docs/ROADMAP.md](docs/ROADMAP.md).

## Build

Requirements: JDK 17 and the Android SDK with `platforms;android-37.0` and `build-tools;37.0.0`.

```sh
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew test                 # JVM unit tests for all modules
```

## License and credits

Loom is free software under the [GNU GPL v3.0](LICENSE).

- **[RomM](https://github.com/rommapp/romm)** (AGPL-3.0) is the server Loom talks to. The API models follow RomM's OpenAPI schema.
- **[Argosy Launcher](https://github.com/rommapp/argosy-launcher)** (GPL-3.0), the official RomM Android client, was the reference for the RomM pairing flow and API usage.
- **[Inter](https://github.com/rsms/inter)** by Rasmus Andersson, under the SIL Open Font License 1.1.
