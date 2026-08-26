<div align="center">

<img width="1536" height="1024" alt="MusicD" src="https://github.com/user-attachments/assets/fc1dd26e-db7f-4e27-8f66-b0ab74db89e3" />

</div>

# MusicD Remote Lite (Android)

**📱 Download & install guide: [meltface-80.github.io/Android-Random-Remote](https://meltface-80.github.io/Android-Random-Remote/)**

A native Android APK of [MusicD Remote for Roon](https://github.com/meltface-80/MusicD-Remote),
with the same interface and no server behind it. It registers itself as a Roon
extension and talks to your Core directly — no Docker, no Node host, no
companion service on another machine.

The interface is not a lookalike: it is MusicD-Remote's own `public/` directory,
copied unmodified into the APK. What changed is everything underneath. The
13,000-line Node server it used to talk to is now Kotlin running in the same
process, speaking Roon's protocols natively — the approach
[Display-extension-apk](https://github.com/meltface-80/Display-extension-apk)
established, applied to a much larger app.

**"Lite" means one thing above all: no record labels.** That feature is most of
the original's weight and it cannot work on a phone at all — details in
[What's not here](#whats-not-here).

## How it fits together

```
┌──────────────────────────────────────────────────┐
│ MainActivity — a WebView                         │
│   assets/web/  =  MusicD-Remote's public/, as-is │
└───────────────────────┬──────────────────────────┘
                        │  http://127.0.0.1:<port>/api/...
┌───────────────────────▼──────────────────────────┐
│ :core  (plain Kotlin/JVM — unit-tested)          │
│   HttpServer  →  RemoteApi                       │
│   AlbumIndex · Search · LibraryView · Albums     │
│   RoonCore:  SOOD → MOO → registry/transport/    │
│              browse/image                        │
└───────────────────────┬──────────────────────────┘
                        │  ws://<core>:<port>/api
                  ┌─────▼─────┐
                  │ Roon Core │
                  └───────────┘
```

Two decisions carry the whole design:

**The front-end is served over real HTTP, from a loopback socket.** Intercepting
requests inside the WebView would have avoided the socket, but
`shouldInterceptRequest` is never handed the *body* of a POST — and the UI POSTs
for every play, queue, volume change and setting. Serving it properly means the
page runs byte-identical to the browser version, and a newer upstream UI is a
file copy rather than a merge.

**Everything that is not Android lives in `:core`,** a plain Kotlin/JVM module.
That is what makes the protocol layer, the stale-offset defence and the whole
API testable on a JVM with no emulator — see [Verification](#verification).

## Install

**Download: [musicd-remote-lite-0.1.12.apk](https://github.com/meltface-80/Android-Random-Remote/raw/main/dist/musicd-remote-lite-0.1.12.apk)**

Sideload it on Android 8.0 (API 26) or newer. The file in
[`dist/`](dist/) is published by CI from the source in this repository, so it is
never a hand-built binary of unknown provenance — the commit that added it names
the commit it was built from. Every push also produces the same APK as a
[CI artifact](../../actions/workflows/build.yml), and a `v*` tag attaches it to
a release.

### Signing, and why updates depend on it

Android refuses to install an APK over one signed with a different key. This
build was signed with the Android **debug** key, which is generated per
machine — so every CI runner produced a new certificate and every published
release was un-installable as an update. Three consecutive releases had three
different certificates.

Releases are now signed with a fixed key held in the
`MUSICD_KEYSTORE_BASE64` and `MUSICD_KEYSTORE_PASSWORD` repository secrets,
and CI refuses to publish an APK whose certificate does not match the
fingerprint pinned in [`tools/release-key.sha256`](tools/release-key.sha256) —
a wrong key now fails the build instead of failing on someone's phone months
later.

Without those secrets the build still compiles and every check still runs;
it just produces an APK marked `-UNSIGNED` and publishes nothing. A missing
secret is not a broken build and CI does not report it as one.

`tools/apk-cert.py` prints the certificate of any APK, which is how the
original fault was diagnosed:

```
python3 tools/apk-cert.py dist/musicd-remote-lite-*.apk
```

**One-time migration.** Builds from 0.1.8 onward update over each other, but
none of them can install over 0.1.7 or earlier, which carry the old
throwaway keys. Uninstall the app once, install 0.1.8, and updates work from
then on.

Then, once:

1. Open the app on the same Wi-Fi as your Roon Core.
2. In Roon: **Settings → Extensions → Enable "MusicD Remote Lite (Android)"**.

The pairing token is stored per Core, so approval only happens the first time.
Until it does, the app's notification tells you exactly what it is waiting for.

## What's here

Everything below works against your library through Roon's browse API, with the
original's screens unchanged:

- **Discovery** — a wall of random albums, filtered by genre, tag or decade;
  Album of the Day; a "not played in N months" row; recently played.
- **The whole library** — a paged, sortable grid (title, artist, year, added,
  play count, last played, or a stable shuffle) with focus facets.
- **Instant search** over the whole library, matched locally on every keystroke:
  prefix-aware, out-of-order (`dark moon` → *Dark Side of the Moon*) and
  typo-tolerant.
- **Album pages** — track list, release year, album and artist write-ups,
  linkable artist credits, play / queue / play-next / start-radio.
- **Playback** — zones and grouping, transport, per-output volume and mute,
  shuffle / repeat / Roon Radio, the queue, play-from-here, zone transfer,
  standby and convenience-switch on source-controlled devices.
- **Multi-select** — queue many albums in one go.
- **Random Album Radio** — when a zone's queue runs dry, another album goes on.
- **Play history** — kept locally, and what "unheard" and "rediscover" are built
  from. This is the feature that gets better the longer the app is installed.
- **The wall display** — the `/display` page, for a tablet in a listening room.
- **Pitchfork** — the Latest and Best New Music listings, with scores, covers
  and links out to pitchfork.com. No review text is served, here or upstream:
  the writing is Pitchfork's and the app links to it.

Release years come from MusicBrainz and the write-ups from Wikipedia. Both are
free and need no key.

## What's not here

The interface still has one entry for each of these; the app answers "this
feature is off" in the shape the front-end already understands, so those screens
show their own empty state instead of an error.

| Not in this build | Why |
|---|---|
| **Record labels** — Label of the Week, the label explorer, label logos, merges | The label index starts from tags read off a **mounted music directory**, then queries iTunes, MusicBrainz, TheAudioDB, Discogs and FanArt.tv. The phone is not the machine holding the files and Roon's extension API exposes no paths, so that first step has no input here yet — see [Reading your music folder](#reading-your-music-folder). This is the "lite" in the name. |
| **Qobuz and TIDAL** browsing, favourites, external search | Both need an account login, and TIDAL needs an OAuth device flow. Deferred, not ruled out. |
| Quality badges (sample rate / bit depth) and source badges | Read from file tags on the mounted music directory. Same missing input as labels. |
| Playlists, smart playlists, share cards, import | Self-contained features, not yet ported. Their screens list nothing rather than failing. |
| In-app self-update | A Docker-era feature. An APK updates by being installed. |

Nothing on that list is a protocol limitation. Labels aside, they are scope.

### Reading your music folder

Labels, and the quality badges with them, are the one feature that needs
something other than Roon: the tags in your actual files. MusicD-Remote gets
them by mounting your library read-only into its container, which works because
it runs on a machine that can see the files.

A phone usually cannot, and Roon does not help — the extension API returns
titles and image keys, never a path. So the mount is not "impossible on
Android" so much as **not implemented**, and there are two honest routes:

- **The library is on a NAS or share.** The app could speak SMB directly and
  read tags over the network. This is the closest match to what the Docker
  build does and would restore labels in full.
- **The library is on the phone or an SD card.** Android's Storage Access
  Framework can hand the app a folder you pick, with no broad storage
  permission and no root.

Either is real work rather than a setting, and neither is written yet. If your
music lives somewhere one of them would reach, say which and it can be built —
the label chain above it is already specified by the original.

## Build

```bash
ANDROID_HOME=/path/to/sdk ./gradlew :app:assembleRelease
```

Needs JDK 17+, Android SDK platform 36 and build-tools 36.0.0. Output lands in
`app/build/outputs/apk/release/`.

`:core` needs no Android SDK at all:

```bash
./gradlew :core:test
```

## Verification

**136 unit tests, all passing.** They run on a plain JVM, which is the point of
splitting `:core` out: the parts most worth testing are tested without an
emulator in the loop.

- **Wire format, checked against Roon's own code.** `tools/verify-wire.js` feeds
  the frames the Kotlin encoder produces to `node-roon-api`'s own `moo.js`, and
  decodes the SOOD query with `sood.js`'s layout — so the framing is verified
  against RoonLabs' implementation rather than against one reading of the
  protocol. CI runs it on every push.

  ```bash
  git clone --depth 1 https://github.com/RoonLabs/node-roon-api /tmp/node-roon-api
  ./gradlew :core:test && node tools/verify-wire.js /tmp/node-roon-api
  ```

- **Zone state.** `subscribe_zones` payloads: the initial set, added / removed /
  changed deltas, and the separate seek-position delta that must not clobber
  anything else.

- **The browse walkers.** Paging, navigating to a genre's or tag's album list,
  the Play menu drill, and the session pooling Roon's server-side state depends
  on — driven against a scripted Core that answers `browse` and `load` with real
  shapes.

- **The stale-offset defence.** A tile carries the offset its album had when the
  index was built, and a library edit shifts those positions. The tests insert
  an album at the front of the library without rebuilding the snapshot and
  assert that the right record still plays; that an album which has left the
  library refuses rather than playing whatever now sits at its offset; and that
  nothing is invoked on Roon in the failure cases.

- **Random Album Radio deciding not to act.** Roon reports a stopped zone at
  several moments that are not the end of a queue — a track loading, the gap
  between albums, the user pressing stop — and the tests drive each of those
  past the settle window. One pins the regression that prompted them: a zone
  already having an album queued must not be picked again by the next tick of
  the zone feed, which arrives several times a second.

- **Album art caching**, counted by how often the Core is actually asked: the
  memory tier absorbing a re-read, the disk tier surviving a restart, a
  truncated file being ignored rather than served as a blank image, and the
  memory budget still holding after two hundred rounds of eviction.

- **The API, end to end over a real socket.** 29 tests drive the shipping HTTP
  server and router — the JSON the unmodified front-end reads, `409` on a moved
  album, `503` when unpaired, and the "feature is off" shapes.

**Not yet verified against a live Roon Core.** There is no Core in the build
environment. The protocol layer is checked against Roon's own code and the API
end to end against a scripted one, but the first real pairing is untested.

**The APK compiles and packages** — CI assembles it on every push, and the file
in `dist/` is that output. What CI cannot do is *run* it: nothing here has
launched the app on a device.

## Limits

- **LAN only.** Roon extensions have no remote or ARC path; away from home needs
  a VPN.
- The phone cannot become a Roon *output* — that needs RAAT, licensed only
  through the Roon Ready partner programme. This is a control surface.
- The app runs a foreground service, and shows a notification, because it *is*
  the extension: its pairing, library snapshot and history live in the app
  process, so it has to survive the screen going off.
- Requires a running Roon Server and a Roon subscription.

## Licence

MIT, see [LICENSE](LICENSE).

The front-end in `app/src/main/assets/web/` is MusicD Remote's, copyright (c)
2026 Lewis Menzies (Music Duck / MusicD), MIT — see the `NOTICE` beside it.

Not affiliated with or endorsed by Roon Labs. "Roon" is their trademark.
