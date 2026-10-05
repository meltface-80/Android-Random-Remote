<div align="center">

<img width="260" alt="Rouen Lite" src="docs/rouen-lite-logo.png" />

</div>

# Rouen Lite (for Android) - v0.5.0

**📱 Download & install guide: [meltface-80.github.io/Rouen-Lite](https://meltface-80.github.io/Rouen-Lite/)**

Rouen Lite is [Rouen](https://github.com/meltface-80/Rouen) as a native Android app: a music discovery companion for Roon that registers itself as a Roon extension and talks to your Core directly — no Docker, no server, no other machine.

*Why Lite?* It leaves out the parts of Rouen that need your music files, a streaming account or an always-on screen — see [What's not in Lite](#whats-not-in-lite). Until v0.5.0 it was called MusicD Remote Lite.

**Download: [musicd-remote-lite-0.5.0.apk](https://github.com/meltface-80/Rouen-Lite/raw/main/dist/musicd-remote-lite-0.5.0.apk)** — Android 8.0 or newer.

---

## Features

Every feature below has an **ⓘ** — tap it for how to switch the feature on, set it up and use it.

🏠 Home

A greeting, the Random Album button and Album of the day, then rows you choose: Recently played, Listen later, Smart Picks, Random albums, Artists, Library and Browse by genre.

<details><summary><b>ⓘ</b> How to set it up and use it</summary>

Home is the first screen. Tap the title of Listen later, Smart Picks, Random albums, Artists or Library for its full screen. Choose which rows show, and their order, under **☰ → Settings → Home Screen** — hold a row's handle to drag it. Random albums and Artists show ten each, the same ten all day.

</details>

⸻

🎲 Random Album and Album of the day

Random Album plays a random album from your whole library. Album of the day is the same album on every device, from 00:01 until you play it.

<details><summary><b>ⓘ</b> How to set it up and use it</summary>

Both sit under the greeting on Home. Tap the disc to start a random album in your current zone. The album marked **★ Today** opens like any other; once any of its tracks has played, it is gone until the next 00:01 (your phone's time).

</details>

⸻

🔀 Random albums

A screen of albums in random order, drawn again whenever you like and filtered by genre, tag or decade.

<details><summary><b>ⓘ</b> How to set it up and use it</summary>

Open **☰ → Random albums**. The shuffle button draws a new set; the filter button narrows it by **Genre**, **Tag** or **Decade**. Tip: tag albums in Roon (for example from a Focus on hi-res), then filter by that tag here.

</details>

⸻

📚 Library

Every album in a grid or a list, sorted by album, artist, release date, recently added, most played, last played or random, in either direction.

<details><summary><b>ⓘ</b> How to set it up and use it</summary>

Tap **Library** on Home. **Sort** is at the top, with an arrow to reverse it; **Random** adds a reshuffle button. The grid/list button is in the top bar. Recently added, Most played and Last played use what this app has seen since it was installed — Roon publishes no import date or play counts.

</details>

⸻

🔍 Search

Instant search of your whole library by album and artist as you type — words in any order, typos forgiven. Matching Pitchfork reviews are listed below.

<details><summary><b>ⓘ</b> How to set it up and use it</summary>

Tap the magnifier at the top of Home and type. Tap an album to open it or an artist for their page; **×** closes the search. Pitchfork matches appear once the Pitchfork lists have been fetched.

</details>

⸻

💿 Album pages

Artwork, release date (to the day where MusicBrainz knows it), a Wikipedia description, linked artist credits, Play now and Queue, and a ⋯ menu with Next, Radio and Listen later.

<details><summary><b>ⓘ</b> How to set it up and use it</summary>

Tap any album. Tap a track for **Play now** or **Queue**; long-press tracks to select several. Tap an artist's name for their page.

</details>

⸻

↔️ Previous / next album

Step to the album either side of the one you opened, on the row or wall you opened it from, without closing it.

<details><summary><b>ⓘ</b> How to set it up and use it</summary>

Open an album from any row or wall, then swipe the card sideways, tap **‹ ›** on the edges of the cover, or press **←** / **→** on a keyboard.

</details>

⸻

🎤 Artists

An artist page lists the albums they lead and the ones they appear on, with a biography from Wikipedia.

<details><summary><b>ⓘ</b> How to set it up and use it</summary>

Tap an artist's name in an album, on Now playing or in search, or tap **Artists** on Home for all of them.

</details>

⸻

▶️ Playback

Play or queue albums and tracks, or play an album next. Now playing has seek, shuffle, repeat and volume; group zones, power devices, and pause or mute every zone.

<details><summary><b>ⓘ</b> How to set it up and use it</summary>

Pick your zone under **☰ → Settings → Playback → Zone / output**, or with the speaker button in Now playing. Tap the mini player for Now playing, and drag the bar to seek. The speaker button also holds **Group zones…**, **Device power…** and pause, mute or unmute all zones. Changing zone while music plays offers to move it to the new zone.

</details>

⸻

📜 Queue, and what already played

The Queue tab shows what's coming and what this app saw each zone play earlier; put any of the earlier tracks back.

<details><summary><b>ⓘ</b> How to set it up and use it</summary>

In Now playing, tap **Queue**. Tap a coming track to play from there. Tap **▸ N played earlier** to unfold the history: tap a track to play it next, or **Select** several, then **Play next** or **Add to queue**. The history is kept in memory, so it starts empty whenever the app restarts.

</details>

⸻

☑️ Multi-select

Select albums on any wall, then play them, add them to the end of the queue, add them to a playlist, or put them on Listen later.

<details><summary><b>ⓘ</b> How to set it up and use it</summary>

Long-press an album to start selecting, tap more albums to add them, then use the **⋯** button in the bar. **Clear selection** ends it.

</details>

⸻

🎵 Your own playlists

Roon's extension API cannot write a playlist, so Rouen Lite keeps its own: build them from albums or tracks and play or queue them.

<details><summary><b>ⓘ</b> How to set it up and use it</summary>

Select albums or tracks (long-press), choose **Add to playlist…**, and pick a playlist or name a new one. Find them under **☰ → Playlists**. Up to 50 playlists of 500 tracks each.

</details>

⸻

✨ Smart Picks

A daily set of albums from your own library. Open one, put it on Listen later, or tap Not for me to keep that artist out.

<details><summary><b>ⓘ</b> How to set it up and use it</summary>

On unless you switch it off under **☰ → Settings → Smart Picks**. See the picks on their Home row or under **☰ → Smart Picks**; **Go to Album** opens one. **Send each day's picks to → Listen later** puts the day's first five on Listen later.

</details>

⸻

🕒 Listen later

Put albums aside to play another time. The list is the same on every device, and an album leaves it once every track has started playing since you added it.

<details><summary><b>ⓘ</b> How to set it up and use it</summary>

Choose **Listen later** from an album's **⋯** menu or from a multi-select, or tap **＋ Listen later** on a Smart Pick. Find the list under **☰ → Listen later** or on its Home row; **Go to Album** opens one and **Remove** takes it off. An album the app has never opened has no track list to check, so it stays until you remove it.

</details>

⸻

🧭 Discover

New albums from the last 60 days by the 40 artists you played on the most different days, from Deezer's public catalogue. Albums you own and reissues are left out.

<details><summary><b>ⓘ</b> How to set it up and use it</summary>

Off by default — it is the one feature that sends artist names to an outside service. Switch on **☰ → Settings → Discover** and pick the hour. The day's list is built once, at or after that hour, when the app next checks its library or you open **☰ → Discover**; **Refresh** builds it now. It needs play history to work from. A row you own queues; any other opens your default streaming service (**Settings → Share Card**).

</details>

⸻

📤 Share card

A card of what's playing — artwork, title, artist, release date, a description and any Pitchfork score — sent through Android's share sheet, with listening and review links underneath.

<details><summary><b>ⓘ</b> How to set it up and use it</summary>

Tap the share button on Now playing or on an album, then **Share…**. Under the card are links to Qobuz, TIDAL, Spotify, Apple Music, Amazon Music, Deezer and Bandcamp, and to Wikipedia, Pitchfork and AllMusic. Choose which appear, and your default service, under **☰ → Settings → Share Card**; holding a service button also makes it the default.

</details>

⸻

👂 If you like this

Under the share card, three artists like the one playing, each with their first album. One you own queues; any other opens in your default service.

<details><summary><b>ⓘ</b> How to set it up and use it</summary>

Open the share card; the row appears underneath once Deezer answers. Set the default service under **☰ → Settings → Share Card**.

</details>

⸻

📰 Pitchfork

Pitchfork's Latest Reviews and Best New Music, grouped by genre, with scores. Read a review on pitchfork.com, open the album if you own it, or find it on Qobuz.

<details><summary><b>ⓘ</b> How to set it up and use it</summary>

Open **☰ → Pitchfork** and switch between **Latest Reviews** and **Best New Music**. Tap a record for its score and links. The review text stays on pitchfork.com.

</details>

⸻

📻 Random Album Radio

When a zone's queue runs out, another random album starts. Set per zone; switching it on turns Roon Radio off for that zone.

<details><summary><b>ⓘ</b> How to set it up and use it</summary>

Switch on **☰ → Settings → Playback → Random album radio** for the zone selected there. It can also be set in Roon: **Settings → Extensions →** the gear beside **Rouen Lite (Android)** has one switch per zone.

</details>

⸻

📡 Live screens

Screens refresh themselves when something they show changes — a play, a setting changed on another device, a new day — through one held request, never a timer.

<details><summary><b>ⓘ</b> How to set it up and use it</summary>

Nothing to switch on.

</details>

⸻

🎛️ UI Settings and themes

Text size, grid layout and tile size for each device, and two themes: Graphite and Brass, or Brass light.

<details><summary><b>ⓘ</b> How to set it up and use it</summary>

**☰ → Settings → UI Settings**: album and artist text, grid screen title (Normal to +50%), grid layout (Auto, 3 columns, 2 columns or List) and tile size (−50% to +50%). **☰ → Settings → Appearance**: pick a theme and tap **Apply**. Both are saved on that device only.

</details>

⸻

🔄 Library sync

Checks Roon every 10 minutes while paired and re-reads the library when its album list has changed.

<details><summary><b>ⓘ</b> How to set it up and use it</summary>

Nothing to switch on. To read the library now, tap **Rescan library** at the bottom of the side menu (☰); the line under it shows the album count and when Roon was last checked.

</details>

⸻

🌐 Open on other devices

Serve the app to other phones, tablets and computers on your Wi-Fi, behind an eight-character code — the remote and the dial both.

<details><summary><b>ⓘ</b> How to set it up and use it</summary>

Off by default. Switch on **☰ → Settings → Network → Open on other devices**. It shows an address such as `http://192.168.0.42:3450` and a code; open the address on the other device and enter the code once. The dial is at the same address plus `/dial`. Turning the switch off and on issues a new code and signs every device out. Traffic is not encrypted, so use a network you trust.

</details>

⸻

⬇️ Updates in the app

Checks for a newer APK when you ask, downloads it, verifies its SHA-256 and hands it to Android's installer.

<details><summary><b>ⓘ</b> How to set it up and use it</summary>

**☰ → Settings → System → Check for updates**, then **Update**. Android asks you to confirm the install.

</details>

⸻

🔒 Lock screen and media keys

Artwork and transport on the lock screen and in the notification. Headset, Bluetooth and car buttons control the zone, and the phone's volume keys change the zone's volume.

<details><summary><b>ⓘ</b> How to set it up and use it</summary>

Works once the app is paired, for the zone you last chose in the app. Assistant's "pause" and "next track" reach it too; asking Assistant to play an album in this app does not work — use the dial's microphone for that.

</details>

⸻

🏠 Home-screen widget

Now playing, with previous, play/pause and next. Tap the cover for a random album, or the text to open the app. Redrawn when the zone changes, never on a timer.

<details><summary><b>ⓘ</b> How to set it up and use it</summary>

Long-press an empty spot on your home screen, choose **Widgets**, find **Rouen Lite** and drag out the now-playing widget.

</details>

⸻

⚡ Quick Settings tile

**Random album** in the notification shade: one tap starts a random album in your current zone, without opening the app.

<details><summary><b>ⓘ</b> How to set it up and use it</summary>

Pull the shade down twice, tap the pencil to edit, then drag **Random album** from the inactive tiles into the grid. The tile shows the zone it will play in, and greys out until the app is paired.

</details>

⸻

🎚️ The dial

A second app icon, **Dial for Roon**: sweep the ring for volume, with album art, transport, mute and the zone name. Also a home-screen widget.

<details><summary><b>ⓘ</b> How to set it up and use it</summary>

Open **Dial for Roon** from your launcher. Sweep the ring to change volume; tap the zone name to pick a room; long-press for the full app. For the widget, find **Rouen Lite** in your home screen's widget list and drag out the dial — its two sides lower and raise the volume.

</details>

⸻

🎙️ Speak to the dial

Tap the dial's microphone and ask: play an album or artist from your library, pause, resume, skip, louder, mute, or "surprise me" — in any room.

<details><summary><b>ⓘ</b> How to set it up and use it</summary>

Allow the microphone the first time. Say "play Mezzanine", "pause music", "next track", "turn it up" (5% of the output's range per command), "mute", or "surprise me"; add "in the kitchen" to aim at a room. Android's own recogniser does the listening, and the answer is always a record in your library.

</details>

⸻

🤖 Commands from other apps

Tasker, MacroDroid or any app can send the dial's spoken commands as an intent; Android's play-from-search intent plays a match, or a random album when empty.

<details><summary><b>ⓘ</b> How to set it up and use it</summary>

Send `com.musicd.lite.android.action.VOICE_COMMAND` with a `command` extra, for example:

```
adb shell am start -a com.musicd.lite.android.action.VOICE_COMMAND \
  -e command "play Mezzanine in the kitchen"
```

The app also answers `android.media.action.MEDIA_PLAY_FROM_SEARCH`.

</details>

---

## Install

1. Download the APK above and open it. Allow your browser to install unknown apps if Android asks.
2. Open **Rouen Lite** on the same Wi-Fi as your Roon Core. It finds the Core itself.
3. In Roon, go to **Settings → Extensions** and click **Enable** on **Rouen Lite (Android)**. Builds before 0.5.0 are listed as "MusicD Remote Lite (Android)" — the same extension, renamed.

Approval happens once per Core; until then, the app's notification says what it is waiting for. The app keeps a foreground service and its notification because the app *is* the extension, and it has to keep running with the screen off.

### Updating, and signing

Use **Settings → System → Check for updates**, or download the newest APK and install it over the old one. Every release is signed with one key, and CI refuses to publish an APK signed with any other (pinned in `tools/release-key.sha256`). Builds 0.1.7 and earlier used throwaway keys: uninstall one of those once, then updates work.

```
python3 tools/apk-cert.py dist/musicd-remote-lite-*.apk --expect-file tools/release-key.sha256
```

---

## What's not in Lite

| Not in this build | Why |
|---|---|
| **Record labels** — Label of the week, the label explorer, logos, merges | Built from tags in your music files. The phone cannot see the files, and Roon's extension API exposes no paths. |
| **Waveform** seek bar | Rouen decodes the audio itself. The plain seek bar is used instead. |
| **Qobuz and TIDAL accounts** — browsing, favourites, catalogue search | Unofficial APIs those services' terms forbid. Roon still streams both through its own account. |
| **Wall display** and screensaver | A phone serving a TV around the clock is a poor fit. Use Rouen for that. |
| **Roon's playlists, Dynamic Playlists, Import a playlist** | Their menu items remain and their screens are empty. Your own playlists work — see above. |
| **Sample-rate and source badges** | Read from file tags, like labels. |
| **Queue editing** — remove, reorder, clear | Not a Lite limit: Roon's extension API has no such command. Play from here works. |

---

## How it works

```
┌──────────────────────────────────────────────────┐
│ MainActivity — a WebView                         │
│   assets/web/  =  Rouen's page, merged           │
└───────────────────────┬──────────────────────────┘
                        │  http://127.0.0.1:3450/api/...
┌───────────────────────▼──────────────────────────┐
│ :core  (plain Kotlin/JVM — unit-tested)          │
│   HttpServer → RemoteApi                         │
│   AlbumIndex · Search · LibraryView · Albums     │
│   RoonCore: SOOD → MOO → registry/transport/     │
│             browse/image                         │
└───────────────────────┬──────────────────────────┘
                        │  ws://<core>:<port>/api
                  ┌─────▼─────┐
                  │ Roon Core │
                  └───────────┘
```

The page is Rouen's own `public/` directory, kept current with upstream by a three-way merge (v1.8.77 as of 0.5.0), with this build's differences on top. The Node server it talks to upstream is Kotlin here, in the same process. The server answers only the phone itself unless **Open on other devices** is on.

Release dates come from MusicBrainz, descriptions and biographies from Wikipedia, and Discover and "If you like this" from Deezer. None needs a key or an account.

---

## Build and test

```bash
ANDROID_HOME=/path/to/sdk ./gradlew :app:assembleRelease   # JDK 17+, SDK platform 36
./gradlew :core:test                                       # no Android SDK needed
```

CI builds the APK and runs 505 `:core` tests and 21 `:app` Robolectric tests on every push, plus checks of the wire format against RoonLabs' `node-roon-api`, of the page's API field names in both directions, of the stylesheet and of the share card. Nothing here runs on a device, and the protocol is not checked against a live Core in CI.

---

## Limits

- Home network only: Roon extensions have no remote path, so away from home needs a VPN.
- A control surface, not a Roon output — the phone does not play audio.
- Needs a running Roon Server and a Roon subscription.

---

## License

Rouen Lite (Android) is copyright (c) 2026 Lewis Menzies (Music Duck / MusicD) and released under the MIT License — see [LICENSE](LICENSE). The page in `app/src/main/assets/web/` is [Rouen's](https://github.com/meltface-80/Rouen), same holder, same licence — see the `NOTICE` beside it.

Not affiliated with or endorsed by Roon Labs. "Roon" is their trademark.
