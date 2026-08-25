package com.musicd.lite.android

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Environment
import android.util.Base64
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.core.content.FileProvider
import java.io.File

/**
 * The web APIs the share card needs and Android's WebView does not have.
 *
 * The card is drawn into a canvas by the bundled front-end, which then offers
 * Copy, Share and Download using `navigator.clipboard.write`, `navigator.share`
 * and an `<a download>`. A WebView provides none of the three: the async
 * Clipboard API refuses image writes, the Web Share API is simply absent, and
 * `download` is inert unless the app implements a DownloadListener. All three
 * buttons were therefore dead — the Copy and Share ones did not even render,
 * because the page feature-detects before drawing them.
 *
 * The fix belongs here rather than in the page. The bundled assets are kept
 * byte-identical to MusicD-Remote's so a newer upstream UI stays a file copy,
 * and "this platform is missing a web API" is the shell's problem to solve.
 * [JS_SHIM] implements the three APIs against this bridge, so the page's own
 * feature detection passes and its existing buttons work unmodified.
 */
class ShareBridge(private val activity: Activity) {

    companion object {
        private const val TAG = "ShareBridge"
        const val NAME = "MusicDShare"

        /** Cleared on every share so the cache cannot grow without bound. */
        private const val SHARE_DIR = "shared"

        /**
         * Implements Clipboard, Web Share and blob downloads on top of the
         * bridge. Injected after every page load.
         *
         * Everything goes through a base64 round-trip because a `blob:` URL is
         * meaningless outside the renderer — the app cannot open one, so the
         * bytes have to be read in JavaScript and handed over.
         */
        val JS_SHIM = """
        (function () {
          if (window.__musicdShareInstalled) return;
          window.__musicdShareInstalled = true;
          var bridge = window.$NAME;
          if (!bridge) return;

          function toBase64(blob) {
            return new Promise(function (resolve, reject) {
              var r = new FileReader();
              r.onload = function () {
                var s = String(r.result || "");
                var comma = s.indexOf(",");
                resolve(comma >= 0 ? s.slice(comma + 1) : s);
              };
              r.onerror = function () { reject(r.error || new Error("read failed")); };
              r.readAsDataURL(blob);
            });
          }

          // The page constructs one of these and hands it to clipboard.write.
          if (typeof window.ClipboardItem === "undefined") {
            window.ClipboardItem = function (items) { this.__items = items || {}; };
          }

          var clip = navigator.clipboard || {};
          clip.write = function (items) {
            var item = (items && items[0]) || {};
            var map = item.__items || {};
            var blob = map["image/png"] || map["image/jpeg"];
            if (!blob) return Promise.reject(new Error("Nothing to copy"));
            return Promise.resolve(blob).then(toBase64).then(function (b64) {
              if (!bridge.copyImage(b64)) throw new Error("The clipboard refused the image");
            });
          };
          if (typeof clip.writeText !== "function") {
            clip.writeText = function (text) {
              return new Promise(function (resolve, reject) {
                bridge.copyText(String(text)) ? resolve() : reject(new Error("Copy failed"));
              });
            };
          }
          try {
            navigator.clipboard = clip;
          } catch (e) {
            Object.defineProperty(navigator, "clipboard", { value: clip, configurable: true });
          }

          function shareFiles(data) {
            var file = data && data.files && data.files[0];
            if (!file) return Promise.reject(new Error("Nothing to share"));
            return toBase64(file).then(function (b64) {
              bridge.shareImage(b64, file.name || "card.png", file.type || "image/png");
            });
          }
          if (typeof navigator.share !== "function") {
            navigator.share = shareFiles;
            navigator.canShare = function (data) {
              return !!(data && data.files && data.files.length);
            };
          }

          // `<a download href="blob:...">` never fires the WebView's download
          // listener with anything the app can open, so it is handled here.
          document.addEventListener("click", function (e) {
            var a = e.target && e.target.closest && e.target.closest("a[download]");
            if (!a || !a.href) return;
            if (a.href.indexOf("blob:") !== 0 && a.href.indexOf("data:") !== 0) return;
            e.preventDefault();
            fetch(a.href)
              .then(function (r) { return r.blob(); })
              .then(toBase64)
              .then(function (b64) {
                bridge.saveImage(b64, a.getAttribute("download") || "card.png", "image/png");
              })
              .catch(function (err) { console.warn("save failed", err); });
          }, true);
        })();
        """.trimIndent()

        /** Runs the shim in [web]. Safe to call on every page load. */
        fun install(web: WebView) {
            web.evaluateJavascript(JS_SHIM, null)
        }
    }

    private fun decode(base64: String): ByteArray? = try {
        Base64.decode(base64, Base64.DEFAULT)
    } catch (e: Exception) {
        Log.w(TAG, "the page sent something that is not base64", e)
        null
    }

    /**
     * Writes the image somewhere another app can read it and puts that URI on
     * the clipboard. An image cannot go on the clipboard as bytes — it has to
     * be a content:// URI backed by a FileProvider.
     */
    @JavascriptInterface
    fun copyImage(base64: String): Boolean {
        val bytes = decode(base64) ?: return false
        return try {
            val uri = FileProvider.getUriForFile(
                activity, "${activity.packageName}.shares", write(bytes, "card.png")
            )
            val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newUri(activity.contentResolver, "Share card", uri)
            clipboard.setPrimaryClip(clip)
            // Without this the pasting app cannot read the file we just handed it.
            activity.grantUriPermission(
                activity.packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            true
        } catch (e: Exception) {
            Log.w(TAG, "copy failed", e)
            false
        }
    }

    @JavascriptInterface
    fun copyText(text: String): Boolean = try {
        val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("MusicD", text))
        true
    } catch (e: Exception) {
        Log.w(TAG, "copy text failed", e)
        false
    }

    @JavascriptInterface
    fun shareImage(base64: String, fileName: String, mime: String) {
        val bytes = decode(base64) ?: return
        activity.runOnUiThread {
            try {
                val uri = FileProvider.getUriForFile(
                    activity, "${activity.packageName}.shares", write(bytes, fileName)
                )
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = mime.ifEmpty { "image/png" }
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                activity.startActivity(Intent.createChooser(send, "Share card"))
            } catch (e: Exception) {
                Log.w(TAG, "share failed", e)
            }
        }
    }

    /** The Download button: put the card where a gallery app will find it. */
    @JavascriptInterface
    fun saveImage(base64: String, fileName: String, mime: String) {
        val bytes = decode(base64) ?: return
        try {
            val downloads =
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val target = uniqueIn(downloads, safeName(fileName))
            target.parentFile?.mkdirs()
            target.writeBytes(bytes)
            activity.runOnUiThread { toast("Saved to Downloads/${target.name}") }
        } catch (e: Exception) {
            // Scoped storage, or no permission. Fall back to the share sheet,
            // which lets the user put it wherever they like.
            Log.d(TAG, "direct save failed (${e.message}) — offering the share sheet")
            shareImage(base64, fileName, mime)
        }
    }

    private fun toast(message: String) {
        android.widget.Toast.makeText(activity, message, android.widget.Toast.LENGTH_SHORT).show()
    }

    /** A share cache holding exactly the file being shared right now. */
    private fun write(bytes: ByteArray, fileName: String): File {
        val dir = File(activity.cacheDir, SHARE_DIR)
        dir.listFiles()?.forEach { it.delete() }
        dir.mkdirs()
        return File(dir, safeName(fileName)).apply { writeBytes(bytes) }
    }

    /** The page names the file; it must not be able to choose the path. */
    private fun safeName(raw: String): String {
        val name = raw.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .take(96)
        return name.ifEmpty { "card.png" }
    }

    private fun uniqueIn(dir: File, name: String): File {
        var candidate = File(dir, name)
        if (!candidate.exists()) return candidate
        val stem = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "")
        var n = 2
        while (candidate.exists() && n < 1000) {
            candidate = File(dir, stem + "-" + n + if (ext.isEmpty()) "" else ".$ext")
            n++
        }
        return candidate
    }
}
