# MusicD Remote for iOS

A sideloadable iOS build, sharing this repository's front-end and its wire
format with the Android app.

## What it is, and what it cannot be

The Android build is a Roon **extension** that lives on the phone. A foreground
service holds the pairing and the MOO socket open, which is what lets Random
Album Radio put the next record on while the phone is in a pocket.

iOS has no equivalent and will not be talked into one. A backgrounded app is
suspended and its sockets go with it. The usual dodge — declaring the `audio`
background mode — would mean claiming to produce sound this app does not
produce, and it would not hold the socket reliably anyway.

**So this is a remote you open.** It pairs when it comes to the foreground and
lets go when it leaves. Everything that needs the extension awake and
unattended stays on Android.

Two further differences, both from iOS rather than from choice:

- **No Core discovery.** Android finds the Core with SOOD, which broadcasts to
  `239.255.90.90:9003` and `255.255.255.255`. iOS blocks multicast and
  broadcast without the `com.apple.developer.networking.multicast` entitlement,
  which Apple grants only to paid developer accounts on application. The Core's
  address is typed once and remembered.
- **iOS will ask for local network permission** the first time. It has to: the
  Core is on your own network and that is the prompt that allows talking to it.

## Installing

The published `.ipa` is **unsigned**, because AltStore, SideStore and Sideloadly
all strip whatever signature is there and re-sign with your own Apple ID.

With a free Apple ID: the app expires after **7 days** and must be refreshed,
and you may have three sideloaded apps at once. A paid Apple Developer
membership signs for a year.

Being in the EU does not remove the signing requirement. The DMA opened up
alternative marketplaces and web distribution, but both still need an Apple
Developer Program membership and notarisation — neither enables *unsigned*
sideloading.

## Building

Apple's toolchain is macOS-only, so this cannot be built on the machine the
Android app is developed on. CI does it on a `macos-15` runner and uploads the
`.ipa` as an artefact.

Locally, on a Mac:

```bash
brew install xcodegen
cd ios && xcodegen generate
open MusicDRemote.xcodeproj
```

`MusicDRemote.xcodeproj` is **generated and not committed**. A `pbxproj` is
UUIDs all the way down: it does not review and it does not merge.
`ios/project.yml` is the real project file.

## How it shares code with Android

- **The front-end is not copied.** `ios/project.yml` references
  `app/src/main/assets/web` as a folder, so both platforms bundle the same
  11,600 lines and neither can drift.
- **The page is served without a server.** It asks for `/api/…` with relative
  URLs, so a `WKURLSchemeHandler` under a `musicd://` scheme answers them
  in-process — no socket, no port, nothing for App Transport Security to
  object to.
- **The wire format is a shared contract.** `tools/wire-fixtures` holds
  byte-exact MOO frames. The Kotlin client asserts against them,
  `tools/verify-wire.js` feeds them to RoonLabs' own `moo.js`, and the Swift
  client asserts against them too. A second implementation of a protocol is
  normally a slow drift into disagreement; three implementations held to one
  set of committed bytes is what makes it safe to have one.

## State of it

| | |
|---|---|
| MOO framing | done, fixture-verified against the Kotlin client and node-roon-api |
| App shell, bundled front-end, Core address | done |
| MOO session, pairing, zones, transport | next |
| Browse, library index, search | after that |
| The dial | after that |

The app is not yet useful: it shows the front-end and says plainly that the
Roon client is not wired up. It is landed this way on purpose — nothing here
can be compiled or run on the machine it is written on, so it goes in pieces
that CI actually builds and tests, rather than several thousand lines of Swift
written blind and pushed in one go.
