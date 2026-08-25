package com.musicd.lite.android

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
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
 * and an `<a download>`. A WebView has none of the three, so all three buttons
 * were dead — and Copy and Share did not even render, because the page
 * feature-detects before drawing them.
 *
 * Only Share is provided, and that is a decision rather than an omission:
 *
 *  - **Share** works, and on Android it is the button that already leads
 *    everywhere else — save to Files, send to another app, put it in a message.
 *  - **Copy** cannot be made to work honestly. An image reaches the Android
 *    clipboard as a content:// URI, and the app doing the pasting holds no
 *    grant against our FileProvider, so the copy reports success and pastes
 *    nothing.
 *  - **Download** is blocked by scoped storage from Android 10, and the
 *    supported route — a MediaStore insert — is the same two taps as Share.
 *
 * The two that cannot work are removed rather than left to fail: the page
 * feature-detects Copy, so not shimming it takes the button away, and the
 * download anchor is dropped from the DOM.
 *
 * The fix belongs here rather than in the page. The bundled assets are kept
 * byte-identical to MusicD-Remote's so a newer upstream UI stays a file copy,
 * and "this platform is missing a web API" is the shell's problem to solve.
 */
class ShareBridge(private val activity: Activity) {

    companion object {
        private const val TAG = "ShareBridge"
        const val NAME = "MusicDShare"

        /** Cleared on every share so the cache cannot grow without bound. */
        private const val SHARE_DIR = "shared"

        /**
         * Implements Web Share on top of the bridge and removes the two
         * controls that cannot work. Injected after every page load.
         *
         * The share goes through a base64 round-trip because a `blob:` URL is
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

          // Deliberately NOT shimmed: window.ClipboardItem and
          // navigator.clipboard.write. Handing an image to the Android
          // clipboard means handing over a content:// URI, and the app pasting
          // it has no grant to read our FileProvider — so the copy appeared to
          // succeed and pasted nothing. The page feature-detects both before
          // it draws the Copy button, so leaving them absent removes the
          // button rather than leaving one that lies.
          //
          // Text copying is fine and does not go through a URI.
          var clip = navigator.clipboard || {};
          if (typeof clip.writeText !== "function") {
            clip.writeText = function (text) {
              return new Promise(function (resolve, reject) {
                bridge.copyText(String(text)) ? resolve() : reject(new Error("Copy failed"));
              });
            };
            try {
              navigator.clipboard = clip;
            } catch (e) {
              Object.defineProperty(navigator, "clipboard", { value: clip, configurable: true });
            }
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

          // The Download button is removed rather than wired up. Writing to
          // the public Downloads directory is blocked by scoped storage from
          // Android 10, and the honest alternative — a MediaStore insert — is
          // the same two taps as Share, which already works. A button that
          // needs a fallback to another button is one button too many.
          function dropDownloadLinks(root) {
            var links = (root || document).querySelectorAll("a[download]");
            for (var i = 0; i < links.length; i++) links[i].remove();
          }
          dropDownloadLinks(document);
          new MutationObserver(function (records) {
            for (var i = 0; i < records.length; i++) {
              var added = records[i].addedNodes;
              for (var j = 0; j < added.length; j++) {
                var n = added[j];
                if (n.nodeType !== 1) continue;
                if (n.matches && n.matches("a[download]")) n.remove();
                else dropDownloadLinks(n);
              }
            }
          }).observe(document.documentElement, { childList: true, subtree: true });
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
}
