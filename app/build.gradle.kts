import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.room)
}

// Release signing material lives in a gitignored keystore.properties (CI writes one from repository
// secrets). Without it the release build is signed with the debug key; debug builds always are.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) FileInputStream(keystorePropsFile).use { load(it) }
}
val hasReleaseSigning = keystorePropsFile.exists()

// versionCode follows the git commit count so sideloaded upgrades are always monotonic.
val gitVersionCode: Int = providers.exec {
    commandLine("git", "rev-list", "--count", "HEAD")
    isIgnoreExitValue = true // no commits yet / no git → fall back to 1
}.standardOutput.asText.get().trim().toIntOrNull()?.coerceAtLeast(1) ?: 1

// versionName comes from the latest v* tag: "0.2.0" on the tagged commit, "0.2.0-3-gabc1234" after
// it, plus "-dirty" with uncommitted changes. Obtainium compares it with the release tag.
val gitVersionName: String = providers.exec {
    commandLine("git", "describe", "--tags", "--match", "v[0-9]*", "--dirty")
    isIgnoreExitValue = true // no tag yet / no git → fall back to 0.0.0
}.standardOutput.asText.get().trim().removePrefix("v").ifEmpty { "0.0.0" }

android {
    namespace = "ro.davidmontei.loom"
    compileSdk = 37
    buildToolsVersion = "37.0.0" // also what the release workflow verifies APKs with

    defaultConfig {
        applicationId = "ro.davidmontei.loom"
        minSdk = 30
        targetSdk = 37
        versionCode = gitVersionCode
        versionName = gitVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        // Debug builds keep the debug key: a debuggable build signed with the release key would install
        // over a release and open its data to run-as.
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Without keystore.properties the minified build is signed with the debug key, so it installs
            // over a debug build in place (keeping pairing and data) for testing R8 on the device.
            signingConfig = signingConfigs.getByName(if (hasReleaseSigning) "release" else "debug")
        }
    }

    lint {
        // Warnings fail the build, so lint stays at zero. Version hints are only informational: they'd
        // otherwise turn the build red by themselves whenever a dependency or Android ships a release.
        warningsAsErrors = true
        informational += setOf("GradleDependency", "AndroidGradlePluginVersion", "OldTargetApi")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    implementation(project(":core-romm"))
    implementation(project(":core-storage"))

    // --- AndroidX core / lifecycle / activity ---
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime)

    // --- Compose (BOM-managed) ---
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    testImplementation(composeBom)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    // --- Hilt ---
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    // --- Room ---
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // --- Networking / images ---
    implementation(platform(libs.okhttp.bom))
    implementation(libs.okhttp.core)
    implementation(libs.okhttp.logging.interceptor)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.coil.compose)
    implementation(libs.zxing.core)

    // --- Test (JVM, Robolectric) ---
    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.turbine)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.androidx.work.testing)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.okhttp.mockwebserver)
}
