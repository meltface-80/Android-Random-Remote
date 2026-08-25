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
        versionCode = 5
        versionName = "0.1.4"
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Signed with the debug key so the artifact is directly installable
            // without a keystore. It will not update in place over a build
            // signed with a different key.
            signingConfig = signingConfigs.getByName("debug")
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
