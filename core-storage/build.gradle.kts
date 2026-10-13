// Everything about putting games on disk: platform mapping, filename sanitising, layout planning,
// .m3u generation, the download state machine and the transfer/verify/promote engine.
// Pure Kotlin/JVM; Android specifics (StatFs, StorageManager, WorkManager) sit behind ports in :app.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(platform(libs.okhttp.bom))
    api(libs.okhttp.core)
    api(libs.kotlinx.serialization.json)
    api(libs.kotlinx.coroutines.core)
    implementation(libs.commons.compress)
    implementation(libs.xz)
    implementation(libs.javax.inject)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}
