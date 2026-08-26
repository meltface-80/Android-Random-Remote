# Working on this repository

## The rule

**Do not ship a change you have not tested. If you cannot test it, say so in
the same breath as you hand it over.**

This is not boilerplate. Three consecutive releases shipped with bugs that
would have been caught by running the thing once:

- **0.1.13** — Settings was completely broken. A bad cut removed `showView`,
  `atHome` and the nav handler. `node --check` passed, and that was reported as
  validation. It is not: it parses, it does not resolve names.
- **0.2.1** — the media session threw on construction on every launch, because
  `setCallback(callback)` with no handler needs a Looper the startup thread does
  not have. The `catch` around it logged a warning and carried on, so the app
  had *never* had a media session. Because the zone watcher was gated on that
  session, the widget silently froze too, and it looked like three bugs.
- **0.2.2** — the widget's play/pause icon was never set at all. The layout
  ships a play triangle; nothing ever changed it. One missing line, and no test
  anywhere could have caught it because the widget is not testable here.

The pattern is the same each time: **the code compiled, so it was shipped.**
Compiling is not evidence.

## What "tested" means here, concretely

Run all of these before pushing. None is optional.

```bash
./gradlew :core:test          # 175+ tests; see the workaround below
node --check app/src/main/assets/web/app.js
npx eslint -c tools/eslint.config.mjs app/src/main/assets/web/app.js
node tools/verify-wire.js     # needs tools/node_modules; CI has it
python3 tools/check-api-contract.py
```

**A new test must fail before the fix and pass after it.** Prove it: break the
fix, run the test, show it failing, restore. A test that passes both ways is
decoration. Every bug fix in this repo has one, and where it does not, the
commit message says why.

### The local Gradle workaround

The proxy blocks the Android Gradle Plugin, so `:app` cannot be built here —
only in CI. To run `:core:test` locally, temporarily strip the Android plugins:

```bash
# save build.gradle.kts and settings.gradle.kts first
sed -i '/id("com.android/d;/id("org.jetbrains.kotlin.android")/d' build.gradle.kts
sed -i 's/include(":app")//' settings.gradle.kts
./gradlew --offline -q :core:test
# then RESTORE BOTH FILES — never commit the stripped versions
```

## The dial is not this project's code

`app/src/main/java/com/musicd/lite/android/dial/` and the dial's resources are
ported from meltface-80/dial-for-Roon by `tools/sync-dial.py`, from the commit
pinned in `tools/dial-upstream.json`. The difference is a declared list of
string substitutions and nothing else.

**Never edit a synced file here.** The next sync would undo it silently, so CI
runs `--check` and fails if one has been touched. Fix it upstream and re-pin,
or take the file out of the manifest and adapt it by hand — either is fine, but
a file cannot be both synced and locally patched.

`DialActivity.kt` and `DialWidget.kt` are ours, and are where the dial meets
this app's Roon client. When upstream grows a new callback or expects a new
resource, that is where to answer it.

```bash
tools/sync-dial.py --latest                  # has upstream moved?
tools/sync-dial.py --write --commit <sha>    # bring it in
tools/sync-dial.py --check                   # what CI runs
```

## The honesty rule about Android code

CI compiles `:app` and runs `:app:testDebugUnitTest`. Robolectric with native
graphics runs the real Skia pipeline on the JVM, so a custom View can be
measured, drawn and driven with real MotionEvents with no device — that is how
the dial is tested, and new view code should be tested the same way.

Everything else in `app/src/main/java/` still has nothing but the compiler.
There are no instrumentation tests and no device here.

So for anything in `app/src/main/java/`, state plainly what was verified and
what was not. "Compiles and the core tests pass" is an honest claim. "Fixed" is
not, unless someone has run it on a phone.

Push logic down into `:core` wherever it can go — that is the only place with
tests. `MusicdLite.activeZone()` and `playRandomAlbum()` exist in core rather
than in the widget and the tile for exactly this reason: the shared decision is
testable, the four Android surfaces that call it are thin.

## Verifying what actually shipped

The APK is evidence and can be inspected without a device. Do it when a change
touches the manifest, resources or signing:

```bash
python3 tools/apk-cert.py dist/<apk> --expect-file tools/release-key.sha256
```

The manifest inside the APK can be decoded to confirm a component really was
declared as intended — not merely that its name appears somewhere. "The string
is in the file" and "the component is declared with the right intent-filter"
are different claims.

## Things about this codebase that are easy to get wrong

- **The bundled page is the authority on every API field name**, not the
  original server. Eleven wire-contract bugs came from porting the server's
  names. `tools/check-api-contract.py` catches the class; run it.
- **Roon's transport API has exactly 22 verbs**, and `play_from_here` is the
  only queue mutation. There is no remove, reorder or clear.
  `tools/verify-wire.js` checks request bodies against RoonLabs' own code.
- **Zone ids are not stable.** They do not survive a Core restart, a regroup or
  a rename, and Roon answers a command for an unknown zone by doing nothing at
  all. Resolve zones through `MusicdLite.activeZone()`, never from a remembered
  id directly.
- **Never poll.** The page long-polls `/api/zone-state?wait_for=`, and the
  service rides `awaitZoneChange`. A timer added anywhere — including a widget's
  `updatePeriodMillis` — undoes that.
- **Never call Roon from the main thread.** `roon.control` blocks until the Core
  answers. `onStartCommand`, `onUpdate` and media-session callbacks are all main
  thread.
- **The HTTP server binds to `127.0.0.1` by construction.** There is no
  authentication because nothing off-device can reach it. Do not open it to the
  network without building auth first.
- **The signing keystore is private key material.** It lives in CI secrets. Do
  not commit it, and do not change the key: an APK signed with a different one
  cannot install over the existing app.

## Errors must be loud enough to notice

`runCatching { … }.onFailure { Log.d(...) }` around something the app depends on
is how the media session failed invisibly for three releases. When guarding
something optional, make sure the app really does work without it — and do not
let unrelated features depend on the thing being guarded.

## Scope and process

- Develop on the branch named in the task. Never push to another branch.
- Do not open a pull request unless asked.
- Bump `versionName` **and** `versionCode` in `app/build.gradle.kts` for any
  build meant to be installed; Android refuses to install over an equal or
  higher `versionCode`. The workflow publishes `dist/` and rewrites the README
  and `docs/index.html` from `versionName`.
- Ask before guessing when a choice is the user's to make. A corner, a layout, a
  destructive default — ask, do not assume and apologise later.
