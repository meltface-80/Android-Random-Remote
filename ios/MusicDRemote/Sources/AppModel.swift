import Foundation
import SwiftUI

/// What the shell knows: where the Core is, and how far along we are.
///
/// Deliberately thin. The Roon client is being ported piece by piece and each
/// piece is landed with CI compiling and testing it, rather than several
/// thousand lines of Swift written blind and pushed in one go — that is the
/// mistake this repository already has a rule about.
@MainActor
final class AppModel: ObservableObject {

    enum Stage {
        case needsCoreAddress
        case connecting(String)
        case ready
        case failed(String)
    }

    @Published private(set) var stage: Stage = .needsCoreAddress

    private let defaults = UserDefaults.standard
    private let coreKey = "core.address"

    var savedCoreAddress: String? { defaults.string(forKey: coreKey) }

    func start() async {
        guard let address = savedCoreAddress, !address.isEmpty else {
            stage = .needsCoreAddress
            return
        }
        stage = .connecting("Talking to \(address)")
        // The MOO session lands next; until it does, the shell says plainly
        // that it is not connected rather than showing a UI with nothing
        // behind it.
        stage = .failed("The Roon client is not wired up in this build yet.")
    }

    func setCore(_ address: String) {
        let trimmed = address.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        defaults.set(trimmed, forKey: coreKey)
        Task { await start() }
    }

    func forgetCore() {
        defaults.removeObject(forKey: coreKey)
        stage = .needsCoreAddress
    }
}
