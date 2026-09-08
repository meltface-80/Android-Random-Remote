package com.musicd.lite.http

/**
 * The one page a stranger on the network is allowed to see.
 *
 * Self-contained on purpose — no stylesheet, no script file, no icon. Every
 * one of those would be another route that has to be readable before anybody
 * has logged in, and each is another thing to get wrong. It is also why this
 * lives in :core as a string rather than in the web assets: the gate answers
 * before the asset handler is reached, so a page that needed the asset handler
 * would need a hole in the gate to fetch itself through.
 *
 * It says as little as it can. No app name, no version, no zone names — the
 * device reading it has not proved it belongs here yet.
 */
internal object LanLoginPage {

    fun html(message: String = ""): String {
        val note = if (message.isEmpty()) "" else
            """<p class="err">${escape(message)}</p>"""
        return """<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<title>Sign in</title>
<style>
  :root { color-scheme: dark; }
  body { margin:0; min-height:100vh; display:flex; align-items:center;
         justify-content:center; background:#0e1012; color:#e8eaed;
         font:16px/1.5 -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; }
  form { width:min(340px, 86vw); text-align:center; }
  h1 { font-size:1.1rem; font-weight:600; margin:0 0 1.5rem; color:#9aa0a6; }
  input { width:100%; box-sizing:border-box; padding:.9rem 1rem; font-size:1.4rem;
          letter-spacing:.28em; text-align:center; text-transform:uppercase;
          border-radius:12px; border:1px solid #2a2e33; background:#16191d;
          color:#e8eaed; }
  input:focus { outline:2px solid #4a90d9; outline-offset:2px; }
  button { width:100%; margin-top:1rem; padding:.9rem 1rem; font-size:1rem;
           font-weight:600; border:0; border-radius:12px; background:#4a90d9;
           color:#fff; }
  .err { color:#f28b82; margin:0 0 1rem; font-size:.95rem; }
  .hint { color:#5f6368; margin:1.5rem 0 0; font-size:.8rem; }
</style>
</head>
<body>
<form method="POST" action="/api/lan/login">
  <h1>Enter the code</h1>
  $note
  <input name="pin" autocomplete="one-time-code" autocapitalize="characters"
         autocorrect="off" spellcheck="false" autofocus
         inputmode="text" maxlength="8" aria-label="Code">
  <button type="submit">Continue</button>
  <p class="hint">The code is on the phone, in Settings &rsaquo; Network.</p>
</form>
</body>
</html>
"""
    }

    private fun escape(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;")
}
