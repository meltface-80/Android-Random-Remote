import SwiftUI

/// The iOS shell.
///
/// What this is, and what it deliberately is not. The Android build is a Roon
/// *extension* that lives on the phone: a foreground service holds the pairing
/// and the MOO socket open, which is what lets Random Album Radio put the next
/// record on while the phone is in a pocket.
///
/// iOS has no equivalent and will not be talked into one. A backgrounded app is
/// suspended and its sockets go with it, and the usual dodge — declaring the
/// `audio` background mode — would be claiming to produce sound this app does
/// not produce, which is the same lie the Android service refuses to tell the
/// platform, and it would not hold the socket reliably in any case.
///
/// So this is a remote you open. It pairs when it comes to the foreground and
/// lets go when it leaves. Everything that needs the extension to be awake and
/// unattended stays on Android.
@main
struct MusicDRemoteApp: App {
    var body: some Scene {
        WindowGroup {
            RootView()
        }
    }
}
