// RomM API client: DTOs mirrored from the RomM 5.4.0 OpenAPI schema, Retrofit service, pairing flows.
// Pure Kotlin/JVM so every API test runs against MockWebServer without Android.
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
    api(libs.retrofit.core)
    implementation(libs.retrofit.converter.kotlinx.serialization)
    api(libs.kotlinx.serialization.json)
    api(libs.kotlinx.coroutines.core)
    implementation(libs.javax.inject)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}

// Opt-in, read-only smoke test against a real server:
//   ./gradlew :core-romm:liveCheck -Promm.url=https://romm.example.org   (LOOM_TOKEN env var optional)
tasks.register<Test>("liveCheck") {
    description = "Read-only checks against a live RomM server (opt-in)."
    group = "verification"
    val testSourceSet = sourceSets["test"]
    testClassesDirs = testSourceSet.output.classesDirs
    classpath = testSourceSet.runtimeClasspath
    filter { includeTestsMatching("*LiveCheck*") }
    systemProperty("romm.url", providers.gradleProperty("romm.url").getOrElse(""))
    outputs.upToDateWhen { false }
}

tasks.named<Test>("test") {
    filter { excludeTestsMatching("*LiveCheck*") }
}
