plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    // Pure-JVM, and equally happy inside an APK — this is the WebSocket client
    // for the MOO session and the HTTP client for the metadata lookups.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Android ships org.json in the platform, so it must NOT be packaged into
    // the APK. It is a compile-time dependency here and a real one only under
    // test, where there is no android.jar to provide it.
    compileOnly("org.json:json:20240303")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}

tasks.test {
    // The wire-format samples MooTest and SoodTest write for
    // tools/verify-wire.js are an OUTPUT of this task, and declaring them is
    // what makes a cache hit restore them. Without this line the task can be
    // satisfied FROM-CACHE, the .bin files never appear, and the cross-check
    // against node-roon-api fails insisting the tests were never run — which
    // is exactly what happened on CI.
    outputs.dir(layout.buildDirectory.dir("moo-samples"))

    testLogging { events("passed", "failed", "skipped") }
}
