package com.musicd.lite

/**
 * The core module is plain Kotlin/JVM so that the protocol, library and API
 * logic can be compiled and unit-tested without an Android SDK. That rules out
 * android.util.Log, so logging goes through a sink the Android module replaces
 * at startup and the tests leave alone.
 */
object Log {

    interface Sink {
        fun write(level: Char, tag: String, message: String, error: Throwable?)
    }

    /** Discards everything. Replaced by the app with a Logcat-backed sink. */
    @Volatile
    var sink: Sink? = null

    /** Verbose tracing of every Roon call. Off unless the app turns it on. */
    @Volatile
    var debug: Boolean = false

    fun d(tag: String, message: String) {
        if (debug) sink?.write('D', tag, message, null)
    }

    fun i(tag: String, message: String) = sink?.write('I', tag, message, null)

    fun w(tag: String, message: String, error: Throwable? = null) =
        sink?.write('W', tag, message, error)

    fun e(tag: String, message: String, error: Throwable? = null) =
        sink?.write('E', tag, message, error)
}
