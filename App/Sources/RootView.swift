import BoomDesign
import BoomModels
import BoomPlayer
import FeatureBrowse
import FeaturePlayer
import SwiftUI
import UIKit

/// The whole app on screen: the browsing frame, the mini player above its tab bar, the full player over everything,
/// and the sheets any screen can ask for (equaliser, sign-in, save to playlist).
struct RootView: View {
    let model: AppModel

    /// The sheets any screen can ask for. Only one shows at a time, so one value says which.
    private enum Sheet: Identifiable {
        case equalizer
        /// Google's sign-in, into this browser profile: the active one, or a new one for another account.
        case signIn(profile: String)
        case saveToPlaylist([Song])

        var id: String {
            switch self {
            case .equalizer: "equalizer"
            case .signIn(let profile): "signIn:" + profile
            case .saveToPlaylist(let songs): "save:" + songs.map(\.videoId).joined(separator: ",")
            }
        }
    }

    @State private var isPlayerOpen = false
    @State private var sheet: Sheet?
    /// A page asked for from outside the frame (the full player's "Go to album"): the relay inside the frame opens it.
    @State private var pageToOpen: BrowseEndpoint?
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        ZStack {
            BrowseShell(start: Self.diagnosticStart, willOpen: { isPlayerOpen = false }) {
                MiniPlayerBar(onExpand: { isPlayerOpen = true })
                    .background(PageRelay(page: $pageToOpen))
            }
            NowPlayingOverlay(isExpanded: $isPlayerOpen)
        }
        .background(WindowReader { window in model.start(in: window) })
        .environment(model.services)
        .environment(\.boomNavigator, navigator)
        .task {
            // Diagnostic launches can open a screen without a finger: -BoomShow player | equalizer.
            guard let show = UserDefaults.standard.string(forKey: "BoomShow") else { return }
            try? await Task.sleep(for: .seconds(UserDefaults.standard.double(forKey: "BoomShowAfter").clamped(to: 0...60, or: 6)))
            switch show {
            case "player": isPlayerOpen = true
            case "equalizer": sheet = .equalizer
            case "signin": sheet = .signIn(profile: model.browser.activeProfileID)
            default: break
            }
        }
        .onChange(of: scenePhase) { _, phase in
            BoomTrace.note("app \(phase == .active ? "active" : phase == .background ? "in the background" : "inactive")")
        }
        .sheet(item: $sheet) { sheet in
            switch sheet {
            case .equalizer:
                EqualizerScreen()
                    .environment(model.services)
                    .presentationDragIndicator(.visible)
            case .signIn(let profile):
                SignInSheet(browser: model.browser, session: model.services.session, profile: profile)
            case .saveToPlaylist(let songs):
                SaveToPlaylistSheet(songs: songs)
                    .environment(model.services)
                    .environment(\.boomNavigator, navigator)
            }
        }
    }

    /// Diagnostic launches can open straight onto a screen: -BoomStart explore | library | settings | charts |
    /// moods | search:<words> | suggest:<words> | album:<id> | artist:<id> | playlist:<id>. Without it, Home.
    private static var diagnosticStart: BrowseStart {
        guard let spec = UserDefaults.standard.string(forKey: "BoomStart") else { return .tab(.home) }
        let parts = spec.split(separator: ":", maxSplits: 1).map(String.init)
        let value = parts.count > 1 ? parts[1] : ""
        switch parts[0] {
        case "explore": return .tab(.explore)
        case "library": return .tab(.library)
        case "settings": return .settings
        case "charts": return .charts
        case "moods": return .moods
        case "search": return .search(value.isEmpty ? nil : value)
        case "suggest": return .search(value.isEmpty ? nil : value, submitted: false)
        case "album": return .page(BrowseEndpoint(browseId: value, pageType: .album))
        case "artist": return .page(BrowseEndpoint(browseId: value, pageType: .artist))
        case "playlist": return .page(BrowseEndpoint(browseId: value, pageType: .playlist))
        default: return .tab(.home)
        }
    }

    private var navigator: BoomNavigator {
        BoomNavigator(
            open: { endpoint in
                isPlayerOpen = false
                pageToOpen = endpoint
            },
            openEqualizer: { sheet = .equalizer },
            addToPlaylist: { songs in
                sheet = model.services.session.isSignedIn
                    ? .saveToPlaylist(songs) : .signIn(profile: model.browser.activeProfileID)
            },
            signIn: { sheet = .signIn(profile: model.browser.activeProfileID) },
            addAccount: {
                guard let profile = model.services.session.beginAddingAccount() else { return }
                sheet = .signIn(profile: profile)
            })
    }
}

/// Sits inside the frame, where the navigator pushes onto the current tab, and opens pages asked for from outside.
private struct PageRelay: View {
    @Binding var page: BrowseEndpoint?
    @Environment(\.boomNavigator) private var navigator

    var body: some View {
        Color.clear
            .onChange(of: page) { _, wanted in
                guard let wanted else { return }
                page = nil
                navigator.open(wanted)
            }
    }
}

/// Finds the window the app is drawn in.
private struct WindowReader: UIViewRepresentable {
    let onWindow: (UIWindow) -> Void

    func makeUIView(context: Context) -> UIView {
        let view = ProbeView()
        view.onWindow = onWindow
        view.isUserInteractionEnabled = false
        return view
    }

    func updateUIView(_ view: UIView, context: Context) {}

    private final class ProbeView: UIView {
        var onWindow: ((UIWindow) -> Void)?

        override func didMoveToWindow() {
            super.didMoveToWindow()
            if let window { onWindow?(window) }
        }
    }
}

/// "Save to playlist": the listener's playlists, and a way to make a new one.
struct SaveToPlaylistSheet: View {
    let songs: [Song]

    @Environment(AppServices.self) private var services
    @Environment(\.dismiss) private var dismiss
    @State private var playlists: [PlaylistItem] = []
    @State private var isLoading = true
    @State private var message: String?
    @State private var newTitle = ""
    @State private var isNaming = false

    var body: some View {
        NavigationStack {
            List {
                Button {
                    isNaming = true
                } label: {
                    Label("New playlist", systemImage: "plus")
                        .foregroundStyle(BoomColor.accent)
                }
                .listRowBackground(BoomColor.surface)
                if isLoading {
                    ProgressView().frame(maxWidth: .infinity).listRowBackground(BoomColor.surface)
                }
                if let message {
                    Text(message).font(BoomFont.itemSubtitle).foregroundStyle(BoomColor.textSecondary)
                        .listRowBackground(BoomColor.surface)
                }
                ForEach(playlists) { playlist in
                    Button {
                        save(to: playlist)
                    } label: {
                        HStack(spacing: BoomMetrics.rowSpacing) {
                            ArtworkView(playlist.artwork, width: BoomMetrics.rowCover)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(playlist.title).font(BoomFont.itemTitle).foregroundStyle(BoomColor.textPrimary)
                                if let detail = playlist.detailText {
                                    Text(detail).font(BoomFont.itemSubtitle).foregroundStyle(BoomColor.textSecondary)
                                }
                            }
                        }
                    }
                    .listRowBackground(BoomColor.surface)
                }
            }
            .scrollContentBackground(.hidden)
            .background(BoomColor.background)
            .navigationTitle("Save to playlist")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
            }
            .alert("New playlist", isPresented: $isNaming) {
                TextField("Name", text: $newTitle)
                Button("Create") { createAndSave() }
                Button("Cancel", role: .cancel) {}
            }
            .task { await load() }
        }
        .preferredColorScheme(.dark)
        .presentationDetents([.medium, .large])
    }

    private func load() async {
        defer { isLoading = false }
        do {
            let page = try await services.api.library(.playlists)
            playlists = page.shelves.flatMap(\.items).compactMap { item in
                // Only playlists the listener can add to: not the built-in "Liked music" and the like.
                if case .playlist(let playlist) = item, playlist.id.hasPrefix("PL") { return playlist }
                return nil
            }
            if playlists.isEmpty { message = "No playlists yet. Make one above." }
        } catch {
            message = "Couldn't load your playlists."
        }
    }

    private func save(to playlist: PlaylistItem) {
        let ids = songs.map(\.videoId)
        Task {
            do {
                try await services.api.addToPlaylist(id: playlist.id, videoIds: ids)
                dismiss()
            } catch {
                message = "Couldn't save to \(playlist.title)."
            }
        }
    }

    private func createAndSave() {
        let title = newTitle.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !title.isEmpty else { return }
        let ids = songs.map(\.videoId)
        Task {
            do {
                _ = try await services.api.createPlaylist(title: title, description: "", privacy: .private, videoIds: ids)
                dismiss()
            } catch {
                message = "Couldn't create the playlist."
            }
        }
    }
}

private extension Double {
    /// The value kept inside `range`, or `fallback` when it is zero (not given).
    func clamped(to range: ClosedRange<Double>, or fallback: Double) -> Double {
        self == 0 ? fallback : min(max(self, range.lowerBound), range.upperBound)
    }
}
