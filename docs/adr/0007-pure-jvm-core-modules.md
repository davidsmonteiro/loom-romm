# Split the Android-free logic into two JVM modules

The brief allowed a single `app` module. Loom splits out `:core-romm` (API client, DTOs, pairing) and `:core-storage` (mapping, sanitising, layout planning, `.m3u`, the download state machine and the transfer/verify/promote engine) as plain Kotlin/JVM modules. The logic that most needs tests then runs on the JVM in seconds without Robolectric, and the compiler stops it from depending on Android; StatFs, StorageManager, Room and WorkManager stay in `:app` behind small interfaces.
