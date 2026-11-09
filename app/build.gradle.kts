import java.util.Properties
import java.io.File
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Optional Audius API key: put AUDIUS_API_KEY=... in local.properties, or set an
// environment variable / GitHub secret with the same name. The app works without it.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.isFile) f.inputStream().use { load(it) }
}
val audiusKey: String = System.getenv("AUDIUS_API_KEY")
    ?: localProps.getProperty("AUDIUS_API_KEY")
    ?: ""
val youtubeDataApiKey: String = System.getenv("YOUTUBE_DATA_API_KEY")
    ?: localProps.getProperty("YOUTUBE_DATA_API_KEY")
    ?: ""
val youtubePlayerBaseUrl: String = System.getenv("YOUTUBE_PLAYER_BASE_URL")
    ?: localProps.getProperty("YOUTUBE_PLAYER_BASE_URL")
    ?: "https://mawjaplay-bumwjcee.manus.space"
val releaseKeystorePath = System.getenv("MAWJA_KEYSTORE") ?: rootProject.file("../keys/mawja-upload.jks").absolutePath
val releaseKeystorePassword = System.getenv("MAWJA_KEYSTORE_PASSWORD") ?: ""
val releaseKeyAlias = System.getenv("MAWJA_KEY_ALIAS") ?: "mawjaUpload"
val releaseKeyPassword = System.getenv("MAWJA_KEY_PASSWORD") ?: releaseKeystorePassword
val releaseSigningReady = File(releaseKeystorePath).isFile && releaseKeystorePassword.isNotBlank()
android {
    namespace = "com.mawja.player"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.mawja.player"
        minSdk = 26
        targetSdk = 37
        versionCode = 32
        versionName = "1.0.0"
        buildConfigField("String", "AUDIUS_API_KEY", "\"$audiusKey\"")
        buildConfigField("String", "YOUTUBE_DATA_API_KEY", "\"$youtubeDataApiKey\"")
        buildConfigField("String", "YOUTUBE_PLAYER_BASE_URL", "\"$youtubePlayerBaseUrl\"")
    }

    signingConfigs {
        if (releaseSigningReady) {
            create("releaseUpload") {
                storeFile = file(releaseKeystorePath)
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = if (releaseSigningReady) signingConfigs.getByName("releaseUpload")
            else signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    bundle {
        language {
            enableSplit = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // Required by NewPipeExtractor on minSdk < 33
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        jniLibs {
            keepDebugSymbols += setOf(
                "**/libandroidx.graphics.path.so",
                "**/libdatastore_shared_counter.so",
            )
        }
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/INDEX.LIST",
                "META-INF/DEPENDENCIES",
            )
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.addAll(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
            "-opt-in=androidx.compose.foundation.layout.ExperimentalLayoutApi",
        )
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.11.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.11.1")
    implementation("androidx.media3:media3-session:1.11.1")
    implementation("androidx.media3:media3-datasource:1.11.1")
    implementation("androidx.media3:media3-common:1.11.1")
    implementation("androidx.media3:media3-ui:1.11.1")
    implementation("com.google.guava:guava:33.7.2-android")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("io.coil-kt:coil-video:2.7.0")
    implementation("androidx.palette:palette-ktx:1.0.0")

    // YouTube extraction (GPLv3 library: this makes the app GPLv3 too)
    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.26.5")
}
