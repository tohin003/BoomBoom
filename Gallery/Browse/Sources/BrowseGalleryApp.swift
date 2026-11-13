import BoomDesign
import BoomModels
import BoomPlayer
import FeatureBrowse
import SwiftUI

/// A demo app for the browse screens on invented data. Launch arguments (read through UserDefaults) pick the
/// first screen and the state, so each one can be screenshotted.
@main
struct BrowseGalleryApp: App {
    @State private var services: AppServices
    private let start: BrowseStart
    private let runsSelfCheck: Bool
    private let startsPlaying: Bool

    init() {
        let defaults = UserDefaults.standard
        let signedIn = defaults.object(forKey: "signedin") as? String != "0"
        let api: any MusicAPI = PreviewMusicAPI(
            delays: defaults.string(forKey: "nodelay") != "1",
            signedIn: signedIn,
            failsBrowsing: defaults.string(forKey: "fail") == "1",
            latency: defaults.string(forKey: "slow") == "1" ? 6000...7000 : 100...300)
        _services = State(initialValue: AppServices.preview(
            api: api, account: signedIn ? PreviewMusicAPI.previewAccount : nil))
        start = Self.start(from: defaults)
        runsSelfCheck = defaults.string(forKey: "selfcheck") == "1"
        startsPlaying = defaults.string(forKey: "playing") == "1"
    }

    var body: some Scene {
        WindowGroup {
            Group {
                if runsSelfCheck {
                    SelfCheckView()
                } else {
                    BrowseShell(start: start) {
                        StandInPlayerBar()
                    }
                }
            }
            .environment(services)
            .environment(\.boomNavigator, BoomNavigator(
                openEqualizer: { print("[gallery] open equaliser") },
                addToPlaylist: { songs in print("[gallery] add \(songs.count) songs to a playlist") },
                signIn: { print("[gallery] sign in") }))
            .task {
                let scroll = UserDefaults.standard.double(forKey: "scroll")
                guard scroll > 0 else { return }
                try? await Task.sleep(for: .seconds(UserDefaults.standard.object(forKey: "scrolldelay") as? Double ?? 1.5))
                // Lazy stacks grow as they draw, so jump a few times until the end stops moving.
                for _ in 0..<5 {
                    GalleryScroller.scroll(to: scroll)
                    try? await Task.sleep(for: .milliseconds(400))
                }
            }
            .task {
                guard startsPlaying, services.player.current == nil else { return }
                if let page = try? await services.api.album(id: "MPREb_afterhoursatlas") {
                    services.player.play(songs: page.tracks, startAt: 1, source: nil)
                }
            }
        }
    }

    private static func start(from defaults: UserDefaults) -> BrowseStart {
        let id = defaults.string(forKey: "id")
        switch defaults.string(forKey: "screen") ?? "home" {
        case "explore": return .tab(.explore)
        case "library": return .tab(.library)
        case "search": return .search(defaults.string(forKey: "query"))
        case "suggest": return .search(defaults.string(forKey: "query"), submitted: false)
        case "album":
            return .page(BrowseEndpoint(browseId: id ?? "MPREb_afterhoursatlas", pageType: .album))
        case "artist":
            return .page(BrowseEndpoint(browseId: id ?? "UCbbjunipervale", pageType: .artist))
        case "playlist":
            return .page(BrowseEndpoint(browseId: id ?? "PLbb_latenightdrive", pageType: .playlist))
        case "browse": return .page(BrowseEndpoint(browseId: id ?? "FEmusic_mixed_for_you"))
        case "newreleases": return .page(BrowseEndpoint(browseId: "FEmusic_new_releases"))
        case "charts": return .charts
        case "moods": return .moods
        case "settings": return .settings
        default: return .tab(.home)
        }
    }
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

/// Runs the search logic checks and lists the outcome (also printed to the console).
private struct SelfCheckView: View {
    @State private var lines: [String] = []
    @State private var isRunning = true

    var body: some View {
        List {
            Section(isRunning ? "Running checks…" : "Search logic checks") {
                ForEach(lines, id: \.self) { line in
                    Text(line)
                        .font(.system(.footnote, design: .monospaced))
                        .foregroundStyle(line.hasPrefix("PASS") ? .green : .red)
                }
            }
        }
        .task {
            let report = await BrowseSelfCheck.run()
            lines = report
            isRunning = false
            let failed = report.filter { !$0.hasPrefix("PASS") }.count
            print("[selfcheck] " + report.joined(separator: "\n[selfcheck] "))
            let summary = "DONE \(report.count - failed) passed, \(failed) failed"
            print("[selfcheck] \(summary)")
            // Also kept as a file, for reading from the Mac: Documents/selfcheck.txt in the app's container.
            if let folder = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first {
                try? (report + [summary]).joined(separator: "\n")
                    .write(to: folder.appending(path: "selfcheck.txt"), atomically: true, encoding: .utf8)
            }
        }
    }
}

/// For screenshots below the fold: scrolls the biggest visible vertical scroll view of the window (SwiftUI's
/// ScrollView is a UIScrollView underneath) to `-scroll <points>`.
@MainActor
private enum GalleryScroller {
    static func scroll(to offset: Double) {
        let windows = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.flatMap(\.windows)
        guard let window = windows.first(where: \.isKeyWindow) ?? windows.first else { return }
        var best: (UIScrollView, CGFloat)?
        func visit(_ view: UIView) {
            guard !view.isHidden, view.alpha > 0.01 else { return }
            if let scroll = view as? UIScrollView, scroll.contentSize.height > scroll.bounds.height + 1 {
                let frame = scroll.convert(scroll.bounds, to: window).intersection(window.bounds)
                let area = frame.isNull ? 0 : frame.width * frame.height
                if area > (best?.1 ?? 0) { best = (scroll, area) }
            }
            view.subviews.forEach(visit)
        }
        visit(window)
        guard let scroll = best?.0 else { return }
        let top = -scroll.adjustedContentInset.top
        let maximum = scroll.contentSize.height - scroll.bounds.height + scroll.adjustedContentInset.bottom
        scroll.setContentOffset(CGPoint(x: 0, y: min(top + offset, max(top, maximum))), animated: false)
    }
}
