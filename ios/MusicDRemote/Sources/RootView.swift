import SwiftUI

/// Nothing but a host for the web UI and the "where is your Core?" prompt.
///
/// The front-end is the same 11,600 lines the Android app bundles, unmodified.
/// It talks to `/api/...` with relative URLs, which is what makes it portable:
/// serve those paths and the whole interface works without knowing what is
/// underneath it.
struct RootView: View {
    @StateObject private var model = AppModel()

    var body: some View {
        ZStack {
            Color(red: 0.055, green: 0.063, blue: 0.071).ignoresSafeArea()

            switch model.stage {
            case .needsCoreAddress:
                CoreAddressView(model: model)
            case .connecting(let detail):
                StatusView(title: "Connecting…", detail: detail)
            case .ready:
                WebUIView(model: model).ignoresSafeArea(.container, edges: .bottom)
            case .failed(let reason):
                StatusView(title: "Not connected", detail: reason) {
                    model.forgetCore()
                }
            }
        }
        .preferredColorScheme(.dark)
        .task { await model.start() }
    }
}

private struct StatusView: View {
    let title: String
    let detail: String
    var onReset: (() -> Void)?

    var body: some View {
        VStack(spacing: 14) {
            ProgressView().tint(.white).opacity(onReset == nil ? 1 : 0)
            Text(title).font(.headline).foregroundStyle(.white)
            Text(detail)
                .font(.callout)
                .multilineTextAlignment(.center)
                .foregroundStyle(.white.opacity(0.65))
                .padding(.horizontal, 32)
            if let onReset {
                Button("Change the Core address", action: onReset)
                    .buttonStyle(.bordered)
                    .tint(.white)
                    .padding(.top, 6)
            }
        }
    }
}

/// Where the Core is.
///
/// Android finds it by itself, with SOOD broadcasting to 239.255.90.90:9003.
/// iOS blocks multicast and broadcast without an entitlement Apple grants only
/// to paid developer accounts, so on a sideloaded build discovery is not
/// available and the address is typed once. It is remembered per Core.
private struct CoreAddressView: View {
    @ObservedObject var model: AppModel
    @State private var address: String = ""

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("Your Roon Core").font(.title2.bold()).foregroundStyle(.white)
            Text("""
                 Enter the address of the machine running Roon Server — the same \
                 one Roon's own remotes connect to. iOS does not allow this app \
                 to find it by itself, so it is asked for once and remembered.
                 """)
                .font(.callout)
                .foregroundStyle(.white.opacity(0.65))

            TextField("192.168.1.20", text: $address)
                .textFieldStyle(.roundedBorder)
                .keyboardType(.numbersAndPunctuation)
                .autocorrectionDisabled()
                .textInputAutocapitalization(.never)
                .submitLabel(.go)
                .onSubmit { model.setCore(address) }

            Button("Connect") { model.setCore(address) }
                .buttonStyle(.borderedProminent)
                .disabled(address.trimmingCharacters(in: .whitespaces).isEmpty)

            Text("Then enable the extension in Roon: Settings → Extensions.")
                .font(.footnote)
                .foregroundStyle(.white.opacity(0.5))
        }
        .padding(28)
        .onAppear { address = model.savedCoreAddress ?? "" }
    }
}
