import BoomDesign
import BoomModels
import BoomPlayer
import FeatureBrowse
import SwiftUI

/// A demo app for the Library, Settings and History screens and the playlist sheets, on invented data. Launch
/// arguments (read through UserDefaults) pick the screen and the state, so each one can be screenshotted.
@main
struct LibraryGalleryApp: App {
    @State private var services: AppServices
    private let screen: String
    private let startsPlaying: Bool

    init() {
        let defaults = UserDefaults.standard
        let signedIn = defaults.object(forKey: "signedin") as? String != "0"
        let preview = PreviewMusicAPI(
            delays: defaults.string(forKey: "nodelay") != "1",
            signedIn: signedIn,
            latency: defaults.string(forKey: "slow") == "1" ? 6000...7000 : 100...300)
        let mode = GalleryMode(rawValue: defaults.string(forKey: "mode") ?? "") ?? .normal
        let api = ScriptedMusicAPI(base: preview, mode: mode)
        let base = AppServices.preview(api: api, account: signedIn ? PreviewMusicAPI.previewAccount : nil)
        // Downloads run on a scripted backend, so every state (arriving, waiting, failed, Wi-Fi) can be staged.
        let script = GalleryDownloadBackend.Script(rawValue: defaults.string(forKey: "dl") ?? "") ?? .none
        let backend = GalleryDownloadBackend(script: script)
        GalleryDownloadBackend.current = backend
        let downloadDefaults = UserDefaults(suiteName: "gallery.downloads") ?? .standard
        downloadDefaults.removePersistentDomain(forName: "gallery.downloads")
        _services = State(initialValue: AppServices(
            api: api, player: base.player, equalizer: base.equalizer, session: base.session, cache: base.cache,
            downloads: DownloadStore(backend: backend, defaults: downloadDefaults)))
        screen = defaults.string(forKey: "screen") ?? "library"
        startsPlaying = defaults.string(forKey: "playing") == "1"
    }

    var body: some Scene {
        WindowGroup {
            Group {
                switch screen {
                case "settings": BrowseShell(start: .settings) { StandInPlayerBar() }
                case "history": HistoryHost()
                case "selfcheck": SelfCheckView()
                case "newplaylist": SheetHost { NewPlaylistSheet { print("[gallery] created \($0)") } }
                case "editor": EditorHost()
                case "downloads": BrowseShell(start: .downloads) { StandInPlayerBar() }
                // -view songs|collections  -select <n>  -collection <id>
                case "downloadsstaged":
                    DownloadsStagedPreview(
                        view: UserDefaults.standard.string(forKey: "view") ?? "songs",
                        selectCount: UserDefaults.standard.integer(forKey: "select"),
                        collectionID: UserDefaults.standard.string(forKey: "collection"))
                case "album":
                    BrowseShell(start: .page(BrowseEndpoint(browseId: "MPREb_smallhours", pageType: .album))) {
                        StandInPlayerBar()
                    }
                case "playlist":
                    BrowseShell(start: .page(BrowseEndpoint(browseId: "PLbb_latenightdrive", pageType: .playlist))) {
                        StandInPlayerBar()
                    }
                default: BrowseShell(start: .tab(.library)) { StandInPlayerBar() }
                }
            }
            .environment(services)
            .environment(\.boomNavigator, BoomNavigator(
                open: { print("[gallery] open \($0.browseId)") },
                openEqualizer: { print("[gallery] open equaliser") },
                addToPlaylist: { songs in print("[gallery] add \(songs.count) songs to a playlist") },
                signIn: { print("[gallery] sign in") }))
            .task {
                await GalleryDownloadBackend.current?.seed(services)
                guard startsPlaying, services.player.current == nil else { return }
                if let page = try? await services.api.album(id: "MPREb_afterhoursatlas") {
                    services.player.play(songs: page.tracks, startAt: 1, source: nil)
                }
            }
        }
    }
}

/// History pushed on a plain stack, the way the Library tab pushes it.
private struct HistoryHost: View {
    var body: some View {
        NavigationStack { HistoryScreen() }
            .tint(BoomColor.textPrimary)
            .preferredColorScheme(.dark)
    }
}

/// A dark page with a sheet already up.
private struct SheetHost<Sheet: View>: View {
    @ViewBuilder let sheet: () -> Sheet
    @State private var isShown = true

    var body: some View {
        BoomColor.background.ignoresSafeArea()
            .sheet(isPresented: $isShown, content: sheet)
    }
}

/// The editor on one of the preview's playlists, made editable.
private struct EditorHost: View {
    @Environment(AppServices.self) private var services
    @State private var page: PlaylistPage?

    var body: some View {
        BoomColor.background.ignoresSafeArea()
            .task {
                guard var loaded = try? await services.api.playlist(id: "PLbb_latenightdrive") else { return }
                loaded.isEditable = true
                loaded.description = "Windows down, nothing on the map."
                page = loaded
            }
            .sheet(item: Binding(get: { page.map(EditorItem.init) }, set: { if $0 == nil { page = nil } })) { item in
                PlaylistEditorSheet(page: item.page, onChanged: { print("[gallery] changed") }, onDeleted: {
                    print("[gallery] deleted")
                })
            }
            .preferredColorScheme(.dark)
    }
}

private struct EditorItem: Identifiable {
    let page: PlaylistPage
    var id: String { page.playlist.id }
}

/// A plain 64 pt bar in place of the real mini player.
private struct StandInPlayerBar: View {
    @Environment(AppServices.self) private var services

    var body: some View {
        let player = services.player
        HStack(spacing: 12) {
            if let song = player.current {
                ArtworkView(song.artwork, width: 44)
                VStack(alignment: .leading, spacing: 2) {
                    Text(song.title).font(BoomFont.itemTitle).lineLimit(1)
                    Text(song.artistLine).font(BoomFont.caption).foregroundStyle(BoomColor.textSecondary).lineLimit(1)
                }
                Spacer()
                Button {
                    player.togglePlayPause()
                } label: {
                    Image(systemName: player.isPlayingOrAboutTo ? "pause.fill" : "play.fill")
                        .font(.system(size: 22))
                        .frame(width: 44, height: 44)
                }
                .accessibilityLabel(player.isPlayingOrAboutTo ? "Pause" : "Play")
            }
        }
        .foregroundStyle(BoomColor.textPrimary)
        .padding(.horizontal, 12)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(BoomColor.surfaceRaised)
    }
}

/// Runs the library checks and lists the outcome (also printed to the console).
private struct SelfCheckView: View {
    @State private var lines: [String] = []
    @State private var isRunning = true

    var body: some View {
        List {
            Section(isRunning ? "Running checks…" : "Library checks") {
                ForEach(lines, id: \.self) { line in
                    Text(line)
                        .font(.system(.footnote, design: .monospaced))
                        .foregroundStyle(line.hasPrefix("PASS") ? .green : .red)
                }
            }
        }
        .task {
            let report = await LibrarySelfCheck.run()
            lines = report
            isRunning = false
            print("[selfcheck] " + report.joined(separator: "\n[selfcheck] "))
        }
    }
}

/// Stands in for the audio engine's downloads: songs "arrive" by script, so the screens' states can be shown.
///   -dl none      nothing downloaded; songs asked for arrive one at a time (about a second each)
///   -dl full      an album and a playlist (part arriving, part waiting), loose songs, one failed
///   -dl wifi      as full, held for Wi-Fi
///   -dl album     the album page's album arriving (12 songs, 5 in), and one of its songs failed
///   -dl albumdone the album page's album downloaded; -dl albumfailed: all in but two
@MainActor
final class GalleryDownloadBackend: DownloadBackend {
    enum Script: String { case none, full, wifi, album, albumdone, albumfailed }

    static var current: GalleryDownloadBackend?

    enum Fate { case finish, waiting, arriving(Double), fail(String) }

    let script: Script
    var fates: [String: Fate] = [:]
    var onDownloadEvent: (@MainActor (DownloadEvent) -> Void)?
    var onDownloadHoldChange: (@MainActor () -> Void)?
    var allowsDownloadsOnMobileData = false {
        didSet { onDownloadHoldChange?() }
    }
    var isHoldingDownloadsForWiFi: Bool { script == .wifi && !allowsDownloadsOnMobileData }
    private var queue: [String] = []
    private var isRunning = false

    init(script: Script) {
        self.script = script
    }

    func startDownloads(_ videoIds: [String]) {
        queue += videoIds
        guard !isRunning else { return }
        isRunning = true
        Task { await run() }
    }

    func removeDownloads(_ videoIds: [String]) {
        queue.removeAll { videoIds.contains($0) }
    }

    private func run() async {
        while !queue.isEmpty {
            let id = queue.removeFirst()
            let fate = fates[id] ?? .finish
            if case .waiting = fate { continue }
            if script == .none {
                for step in 1...4 {
                    try? await Task.sleep(for: .milliseconds(220))
                    onDownloadEvent?(.progress(videoId: id, fraction: Double(step) / 5))
                }
            }
            switch fate {
            case .finish:
                // A size of its own for each song, the same on every launch.
                let mixed = id.unicodeScalars.reduce(UInt64(1469598103934665603)) { ($0 ^ UInt64($1.value)) &* 1099511628211 }
                let size = 3_000_000 + Int64(mixed % 8_000_000)
                onDownloadEvent?(.finished(videoId: id, bytes: size, quality: "Opus 256 kbps"))
            case .arriving(let fraction):
                onDownloadEvent?(.progress(videoId: id, fraction: fraction))
            case .fail(let message):
                onDownloadEvent?(.failed(videoId: id, message: message))
            case .waiting:
                break
            }
        }
        isRunning = false
    }

    /// Puts the script's downloads in place.
    func seed(_ services: AppServices) async {
        let api = services.api
        let store = services.downloads
        switch script {
        case .none:
            return
        case .album, .albumdone, .albumfailed:
            guard let page = try? await api.album(id: "MPREb_smallhours") else { return }
            for (index, song) in page.tracks.enumerated() {
                switch script {
                case .album: fates[song.videoId] = index < 5 ? .finish : index == 5 ? .arriving(0.6) : .waiting
                case .albumfailed: fates[song.videoId] = index < 2 ? .fail("The song is not available") : .finish
                default: break
                }
            }
            store.download(page.tracks, as: DownloadCollector.entry(for: page))
        case .full, .wifi:
            if let page = try? await api.album(id: "MPREb_afterhoursatlas") {
                store.download(page.tracks, as: DownloadCollector.entry(for: page))
            }
            if let page = try? await api.album(id: "MPREb_kodachromeweather") {
                for (index, song) in page.tracks.prefix(6).enumerated() {
                    if index == 2 { fates[song.videoId] = .fail("The connection was lost") }
                    store.download(song)
                }
            }
            if let page = try? await api.playlist(id: "PLbb_latenightdrive") {
                for (index, song) in page.tracks.enumerated() where index >= 4 {
                    fates[song.videoId] = index == 4 && script == .full ? .arriving(0.42) : .waiting
                }
                store.download(page.tracks, as: DownloadCollector.entry(for: page))
            }
        }
    }
}
