import BoomDesign
import BoomModels
import BoomPlayer
import FeaturePlayer
import SwiftUI

@main
struct PlayerGalleryApp: App {
    @State private var services: AppServices = {
        let services = AppServices.preview(api: FakeMusicAPI())
        services.equalizer.attach(FakeOutput.shared)
        return services
    }()

    var body: some Scene {
        WindowGroup {
            GalleryRoot()
                .environment(services)
                .preferredColorScheme(.dark)
        }
    }
}

/// A plain dark page with the mini player at the foot (above a stand-in tab bar) and the full player over it all.
struct GalleryRoot: View {
    @Environment(AppServices.self) private var services
    @State private var isExpanded = false
    @State private var panel: NowPlayingPanel?
    @State private var showsEqualizer = false
    /// Shows the equaliser further down for a screenshot (`-screen equalizer-bottom -offset 900`); a simulator cannot scroll.
    @State private var showsEqualizerBottom = false
    @State private var notice: String?

    private let arguments = UserDefaults.standard

    var body: some View {
        ZStack(alignment: .bottom) {
            BoomColor.background.ignoresSafeArea()
            page
            VStack(spacing: 0) {
                if services.player.current != nil {
                    MiniPlayerBar { isExpanded = true }
                        .transition(.move(edge: .bottom).combined(with: .opacity))
                }
                tabBar
            }
            NowPlayingOverlay(isExpanded: $isExpanded, panel: $panel)
        }
        .environment(\.boomNavigator, BoomNavigator(
            open: { endpoint in notice = "Would open \(endpoint.pageType.rawValue) \(endpoint.browseId)" },
            openEqualizer: { showsEqualizer = true },
            addToPlaylist: { songs in notice = "Would save \(songs.count) song to a playlist" }))
        .sheet(isPresented: $showsEqualizer) {
            EqualizerScreen()
                .environment(services)
                .presentationDragIndicator(.visible)
        }
        .fullScreenCover(isPresented: $showsEqualizerBottom) {
            // A very tall screen shifted up: shows the part `-offset` points down without a finger to scroll.
            EqualizerScreen()
                .environment(services)
                .frame(height: 3000, alignment: .top)
                .offset(y: -CGFloat(arguments.double(forKey: "offset")))
        }
        .task { await start() }
    }

    private var page: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("Player gallery")
                .font(BoomFont.pageTitle)
                .foregroundStyle(BoomColor.textPrimary)
            Text("Canned songs, a silent player that keeps time. Tap the bar below to open the player.")
                .font(BoomFont.itemSubtitle)
                .foregroundStyle(BoomColor.textSecondary)
            HStack {
                Button("Open player") { isExpanded = true }
                Button("Equaliser") { showsEqualizer = true }
                Button("Stop") { services.player.stop() }
            }
            .buttonStyle(.bordered)
            .tint(BoomColor.accent)
            if let notice {
                Text(notice)
                    .font(BoomFont.caption)
                    .foregroundStyle(BoomColor.accent)
            }
            Spacer()
        }
        .padding(BoomMetrics.margin)
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private var tabBar: some View {
        HStack {
            ForEach(["house.fill", "safari", "magnifyingglass", "books.vertical"], id: \.self) { glyph in
                Image(systemName: glyph)
                    .font(.system(size: 20))
                    .foregroundStyle(glyph == "house.fill" ? BoomColor.textPrimary : BoomColor.textTertiary)
                    .frame(maxWidth: .infinity)
            }
        }
        .frame(height: BoomMetrics.tabBarHeight)
        .background(BoomColor.surface.ignoresSafeArea(edges: .bottom))
        .overlay(alignment: .top) { Rectangle().fill(BoomColor.separator).frame(height: 0.5) }
    }

    /// Starts the fake queue, then opens the state the launch arguments ask for.
    private func start() async {
        guard services.player.current == nil else { return }
        let songs = FakeMusicAPI.songs
        let index = min(max(arguments.integer(forKey: "start"), 0), songs.count - 1)
        services.player.play(
            songs: songs, startAt: index,
            source: QueueSource(
                kind: .playlist, label: "Playlist", title: "Late Night Harbour Mix",
                endpoint: BrowseEndpoint(browseId: "VLPLharbour", pageType: .playlist)))
        if let preset = arguments.string(forKey: "eqpreset"),
           let match = services.equalizer.presets.first(where: { $0.id == preset }) {
            services.equalizer.select(match)
        }
        try? await Task.sleep(for: .milliseconds(400))
        if let seek = arguments.object(forKey: "seek") as? String, let seconds = Double(seek) {
            services.player.seek(to: seconds)
        }
        switch arguments.string(forKey: "screen") {
        case "player": isExpanded = true
        case "queue": isExpanded = true; panel = .upNext
        case "lyrics": isExpanded = true; panel = .lyrics
        case "related": isExpanded = true; panel = .related
        case "equalizer": showsEqualizer = true
        case "equalizer-bottom": showsEqualizerBottom = true
        default: break
        }
    }
}

/// Pretends to be the audio engine's output for the meters: a lively signal whose level follows the boost.
@MainActor
final class FakeOutput: SoundProcessing {
    /// The equaliser holds its output weakly, so the gallery keeps this one alive.
    static let shared = FakeOutput()

    private var settings = SoundSettings()
    private let start = Date()

    func apply(_ settings: SoundSettings) {
        self.settings = settings
    }

    /// `-headtracking 1` pretends AirPods that report head movement are connected.
    var isHeadTrackingAvailable: Bool { UserDefaults.standard.bool(forKey: "headtracking") }

    func takeLevels() -> SoundLevels {
        let time = Date().timeIntervalSince(start)
        let gain = settings.isEnabled ? pow(10, Double(settings.boost + settings.preamp) / 20) : 1
        func channel(_ phase: Double) -> Float {
            let beat = 0.5 + 0.5 * abs(sin(time * 2.1 + phase))
            let wobble = 0.15 * sin(time * 13 + phase * 3)
            return Float(max(0.02, (0.35 + 0.4 * beat + wobble) * gain))
        }
        var levels = SoundLevels()
        let limited = settings.isEnabled && settings.isLimiterEnabled
        levels.peakLeft = limited ? min(channel(0), 0.966) : channel(0)
        levels.peakRight = limited ? min(channel(0.7), 0.966) : channel(0.7)
        levels.rmsLeft = levels.peakLeft * 0.5
        levels.rmsRight = levels.peakRight * 0.5
        let over = max(channel(0), channel(0.7))
        levels.gainReduction = limited && over > 0.966 ? 20 * log10(over / 0.966) : 0
        return levels
    }

    func spectrum(bands: Int) -> [Float] {
        let time = Date().timeIntervalSince(start)
        let curve = settings.response(at: (0..<bands).map { 20 * pow(1000, (Double($0) + 0.5) / Double(bands)) })
        return (0..<bands).map { index in
            let place = Double(index) / Double(max(bands - 1, 1))
            let tilt = -18 - 30 * place
            let motion = 10 * abs(sin(time * (1.5 + 3 * place) + Double(index)))
            return Float(tilt + motion + curve[index])
        }
    }
}
