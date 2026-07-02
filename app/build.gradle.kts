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
// Quote + escape a value for use as a BuildConfig String literal.
fun buildConfigString(value: String): String =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "com.lopeici.tvplayer"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.lopeici.tvplayer"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
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
            isMinifyEnabled = false
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
}

kotlin {
    compilerOptions {
        // Many Media3 APIs (ExoPlayer.Builder, CastPlayer, PlayerView, ...) are @UnstableApi.
        optIn.add("androidx.media3.common.util.UnstableApi")
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
