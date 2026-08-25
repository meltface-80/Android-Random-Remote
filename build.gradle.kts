// Plugin versions are declared in the modules that apply them (:core is a plain
// Kotlin/JVM library, :app is the Android module) so the pure-JVM core can be
// configured and tested without the Android Gradle Plugin on the classpath.
tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
