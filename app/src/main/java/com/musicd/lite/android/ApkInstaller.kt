package com.musicd.lite.android

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File

/**
 * Hands a downloaded APK to Android's package installer.
 *
 * An app cannot replace itself. Only the system installer may, and it always
 * shows the user what it is about to do — so this is the whole of the Android
 * half of an update: point the installer at a file and get out of the way.
 * Everything before it (noticing a new version, downloading, verifying) is
 * plain Kotlin in :core and is tested there.
 *
 * Two things make this fail in ways worth naming, because both look like a bug
 * in the app rather than what they are:
 *
 *  - **A different signing key.** Android refuses to install over an app signed
 *    with another certificate, and says only "App not installed". Every build
 *    before 0.1.8 carried a throwaway key, so those must be uninstalled once.
 *  - **"Install unknown apps" not granted.** The installer asks for it the
 *    first time and returns here if the user declines.
 *
 * The file goes through a content:// URI because a file:// path pointing into
 * this app's storage is refused outright — the installer is a different process
 * and needs a grant it can act on.
 */
object ApkInstaller {

    private const val TAG = "ApkInstaller"

    /** The one directory an update is downloaded into; see file_paths.xml. */
    fun downloadDir(context: Context): File = File(context.cacheDir, "updates")

    fun install(context: Context, apk: File) {
        try {
            val uri = FileProvider.getUriForFile(
                context, "${context.packageName}.shares", apk
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                // Started from a Service, so it needs its own task.
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            // Reported through the notification rather than swallowed: the
            // update banner is polling and would otherwise sit on "Restarting…"
            // for three minutes before timing out with nothing to explain it.
            Log.e(TAG, "could not open the installer", e)
            throw e
        }
    }
}
