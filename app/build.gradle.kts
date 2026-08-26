import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.musicd.lite.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.musicd.lite"
        minSdk = 26
        targetSdk = 36
        // Bumping versionName is what publishes a new APK into dist/ and
        // repoints the README at it — see the workflow. versionCode must rise
        // with it or Android refuses to install over the previous build.
        versionCode = 14
        versionName = "0.1.13"
    }

    buildFeatures {
        buildConfig = true
    }

    /**
     * The release key, and why it cannot be the debug one.
     *
     * Android refuses to install an APK over one signed with a different key,
     * and the debug keystore is generated per machine — a CI runner is fresh
     * every time, so every published build carried a NEW certificate. Three
     * consecutive releases had three different ones, which is exactly why
     * installing an update over the existing app stopped working.
     *
     * So the key comes from the environment (a CI secret), and there is no
     * fallback that silently signs with something else: a release build with
     * no key configured is unsigned, which fails loudly at install time rather
     * than producing an APK that looks fine and cannot be an update.
     */
    val keystorePath = System.getenv("MUSICD_KEYSTORE")
    if (!keystorePath.isNullOrBlank()) {
        signingConfigs.create("release") {
            storeFile = file(keystorePath)
            storePassword = System.getenv("MUSICD_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("MUSICD_KEY_ALIAS") ?: "musicd"
            keyPassword = System.getenv("MUSICD_KEY_PASSWORD")
                ?: System.getenv("MUSICD_KEYSTORE_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += setOf(
            "META-INF/*.kotlin_module",
            "META-INF/DEPENDENCIES",
            "META-INF/LICENSE*",
            "META-INF/NOTICE*"
        )
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // Everything that is not Android lives in :core, where it is unit-tested on
    // a plain JVM. This module is the shell: a WebView, a foreground service,
    // and SQLite.
    implementation(project(":core"))
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // FileProvider only. Handing an image to the clipboard or a share sheet
    // means handing over a content:// URI, and that needs a provider.
    implementation("androidx.core:core:1.13.1")
}
