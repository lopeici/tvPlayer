import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Optional release signing: only wired up if keystore.properties exists (git-ignored).
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        FileInputStream(keystorePropertiesFile).use { load(it) }
    }
}

// "personal" flavor seed: a pre-loaded playlist baked into the personal build only. Credentials
// live in personal.properties (git-ignored), so they never reach the repo. Other builds (generic)
// ship with no seed, so a fresh clone without this file still builds normally.
val personalPropertiesFile = rootProject.file("personal.properties")
val personalProperties = Properties().apply {
    if (personalPropertiesFile.exists()) {
        FileInputStream(personalPropertiesFile).use { load(it) }
    }
}
// versionCode = number of commits on HEAD, so every build from a newer commit upgrades in place.
// Falls back to 1 when git isn't installed (exec throws) or this isn't a repository (non-zero exit,
// e.g. building from a source archive).
val gitCommitCount: Int = runCatching {
    providers.exec {
        commandLine("git", "rev-list", "--count", "HEAD")
        isIgnoreExitValue = true
    }.standardOutput.asText.get().trim().toIntOrNull()
}.getOrNull() ?: 1

// Quote + escape a value for use as a BuildConfig String literal.
fun buildConfigString(value: String): String =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "com.lopeici.tvplayer"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.lopeici.tvplayer"
        minSdk = 26
        targetSdk = 36
        versionCode = gitCommitCount
        versionName = "1.0"

        // Default: no pre-loaded playlist. The `personal` flavor overrides these below.
        buildConfigField("String", "SEED_PLAYLIST_URL", "\"\"")
        buildConfigField("String", "SEED_PLAYLIST_NAME", "\"\"")
    }

    flavorDimensions += "distribution"
    productFlavors {
        // Standard build for anyone — starts empty, add playlists in-app.
        create("generic") {
            dimension = "distribution"
        }
        // Private build with a pre-loaded playlist (from personal.properties). Same applicationId,
        // so installing it upgrades an existing install in place.
        create("personal") {
            dimension = "distribution"
            buildConfigField(
                "String",
                "SEED_PLAYLIST_URL",
                buildConfigString(personalProperties.getProperty("url", "")),
            )
            buildConfigField(
                "String",
                "SEED_PLAYLIST_NAME",
                buildConfigString(personalProperties.getProperty("name", "Personal")),
            )
        }
    }

    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        // Media3's @UnstableApi is an androidx (lint-enforced) opt-in, not a Kotlin compiler one,
        // so the module-wide opt-in for it has to live here.
        disable += "UnsafeOptInUsageError"
    }
}

kotlin {
    compilerOptions {
        optIn.add("androidx.compose.material3.ExperimentalMaterial3Api")
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)

    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.datasource.okhttp)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.cast)
    implementation(libs.play.services.cast.framework)
    implementation(libs.androidx.mediarouter)

    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.okhttp)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
}
