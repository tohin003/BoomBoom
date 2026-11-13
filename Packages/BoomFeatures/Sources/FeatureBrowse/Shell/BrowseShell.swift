import BoomDesign
import BoomModels
import BoomPlayer
import Combine
import SwiftUI

/// The app's three tabs.
public enum BrowseTab: String, CaseIterable, Hashable, Sendable {
    case home, explore, library

    var title: String {
        switch self {
        case .home: "Home"
        case .explore: "Explore"
        case .library: "Library"
        }
    }

    var symbol: String {
        switch self {
        case .home: "house"
        case .explore: "safari"
        case .library: "books.vertical"
        }
    }
}

/// Where the frame opens: the Home tab, or straight onto a screen (for deep links and demo apps).
public enum BrowseStart: Hashable, Sendable {
    case tab(BrowseTab)
    /// Search, optionally with words in the field: submitted (results show) or not (suggestions show).
    case search(String?, submitted: Bool = true)
    /// An album, an artist, a playlist or a page of shelves, pushed onto Home.
    case page(BrowseEndpoint)
    case charts
    case moods
    case settings
    /// The Downloads screen, pushed onto Library.
    case downloads
}

/// The app's frame: Home, Explore and Library, each with its own navigation stack, a flat tab bar, and the
/// player bar above it while a song is loaded.
///
/// It reads the `boomNavigator` the app set and hands its screens a copy whose `open` pushes onto the current tab;
/// the other actions (equaliser, save to playlist, sign-in) pass through untouched.
public struct BrowseShell<PlayerBar: View>: View {
    private let playerBar: () -> PlayerBar
    private let willOpen: (@MainActor () -> Void)?

    @Environment(AppServices.self) private var services
    @Environment(\.boomNavigator) private var incoming
    @State private var router: ShellRouter
    @State private var isKeyboardUp = false
    /// Says when an album or playlist could not be collected for downloading, or was cut at the song limit: the
    /// download may have been started from a menu whose screen is gone.
    private var collector: DownloadCollector { DownloadCollector.shared }

    /// - Parameters:
    ///   - start: The tab or screen shown first.
    ///   - willOpen: Called before `navigator.open` pushes a page, so the app can close the full player first.
    ///   - playerBar: The mini player, drawn `BoomMetrics.miniPlayerHeight` tall above the tab bar.
    public init(
        start: BrowseStart = .tab(.home), willOpen: (@MainActor () -> Void)? = nil,
        @ViewBuilder playerBar: @escaping () -> PlayerBar
    ) {
        self.playerBar = playerBar
        self.willOpen = willOpen
        _router = State(initialValue: ShellRouter(start: start))
    }

    public var body: some View {
        ZStack {
            ForEach(BrowseTab.allCases, id: \.self) { tab in
                if router.visited.contains(tab) {
                    let isSelected = router.selected == tab
                    TabStack(tab: tab, router: router)
                        .opacity(isSelected ? 1 : 0)
                        .allowsHitTesting(isSelected)
                        .accessibilityHidden(!isSelected)
                        // Hiding the stack alone does not reach the pages inside it; each page hides itself too.
                        .environment(\.isShellTabActive, isSelected)
                        .zIndex(isSelected ? 1 : 0)
                }
            }
        }
        // A safe-area inset here would not reach the pages inside the navigation stacks, so the bars are drawn on
        // top and each page reserves their height itself (see `reservesShellBars()`).
        .environment(\.shellBarsHeight, barsHeight)
        .overlay(alignment: .bottom) {
            if !isKeyboardUp {
                bottomBars
            }
        }
        .environment(\.boomNavigator, navigator)
        .background(BoomColor.background.ignoresSafeArea())
        .tint(BoomColor.textPrimary)
        .preferredColorScheme(.dark)
        .alert(
            collector.notice?.title ?? "", isPresented: Binding(
                get: { collector.notice != nil }, set: { if !$0 { collector.notice = nil } }),
            presenting: collector.notice
        ) { _ in
            Button("OK") {}
        } message: { notice in
            Text(notice.message)
        }
        .onReceive(NotificationCenter.default.publisher(for: UIResponder.keyboardWillShowNotification)) { _ in
            isKeyboardUp = true
        }
        .onReceive(NotificationCenter.default.publisher(for: UIResponder.keyboardWillHideNotification)) { _ in
            isKeyboardUp = false
        }
    }

    private var navigator: BoomNavigator {
        var copy = incoming
        let router = router
        let willOpen = willOpen
        copy.open = { endpoint in
            willOpen?()
            router.push(.page(endpoint))
        }
        return copy
    }

    private var barsHeight: CGFloat {
        guard !isKeyboardUp else { return 0 }
        return BoomMetrics.tabBarHeight + (services.player.current != nil ? BoomMetrics.miniPlayerHeight : 0)
    }

    private var bottomBars: some View {
        VStack(spacing: 0) {
            if services.player.current != nil {
                playerBar()
                    .frame(height: BoomMetrics.miniPlayerHeight)
                    .transition(.move(edge: .bottom).combined(with: .opacity))
            }
            BoomTabBar(selected: router.selected) { router.select($0) }
        }
        .background(BoomColor.surface.ignoresSafeArea(edges: .bottom))
        .animation(.easeOut(duration: 0.25), value: services.player.current != nil)
    }
}

/// The tabs' navigation state. One per frame.
@MainActor
@Observable
final class ShellRouter {
    var selected: BrowseTab
    var paths: [BrowseTab: [BrowseRoute]] = [:]
    /// Tabs shown at least once; their stacks stay alive so each keeps its place.
    var visited: Set<BrowseTab>
    var reselectTicks: [BrowseTab: Int] = [:]

    init(start: BrowseStart) {
        var tab = BrowseTab.home
        var startPath: [BrowseRoute] = []
        switch start {
        case .tab(let chosen): tab = chosen
        case .search(let query, let submitted): startPath = [.search(query: query, submitted: submitted)]
        case .page(let endpoint): startPath = [.page(endpoint)]
        case .charts: tab = .explore; startPath = [.charts]
        case .moods: tab = .explore; startPath = [.moods]
        case .settings: startPath = [.settings]
        case .downloads: tab = .library; startPath = [.downloads]
        }
        selected = tab
        visited = [tab]
        paths = [tab: startPath]
    }

    /// The stack's path. A link tapped twice before its page slides in would push that page twice: the second is
    /// dropped, as in `push(_:)`.
    func path(_ tab: BrowseTab) -> Binding<[BrowseRoute]> {
        Binding(get: { self.paths[tab] ?? [] }, set: { new in
            let old = self.paths[tab] ?? []
            if new.count == old.count + 1, new.dropLast().elementsEqual(old), new.last == old.last { return }
            self.paths[tab] = new
        })
    }

    /// A tab bar tap: switch tabs; on the selected tab, back to its root, then to the top.
    func select(_ tab: BrowseTab) {
        guard tab == selected else {
            visited.insert(tab)
            selected = tab
            return
        }
        if paths[tab]?.isEmpty == false {
            paths[tab] = []
        } else {
            reselectTicks[tab, default: 0] += 1
        }
    }

    /// Pushes onto the current tab, ignoring a second tap that would push the same page twice.
    func push(_ route: BrowseRoute) {
        var path = paths[selected] ?? []
        guard path.last != route else { return }
        path.append(route)
        paths[selected] = path
    }
}

/// One tab's navigation stack.
private struct TabStack: View {
    let tab: BrowseTab
    let router: ShellRouter

    var body: some View {
        NavigationStack(path: router.path(tab)) {
            root
                .reservesShellBars()
                .navigationDestination(for: BrowseRoute.self) { route in
                    RouteScreen(route: route)
                        .reservesShellBars()
                }
        }
        .environment(\.tabReselect, router.reselectTicks[tab] ?? 0)
    }

    @ViewBuilder
    private var root: some View {
        switch tab {
        case .home: HomeScreen()
        case .explore: ExploreScreen()
        case .library: LibraryScreen()
        }
    }
}

/// The screen for a pushed route.
struct RouteScreen: View {
    let route: BrowseRoute

    var body: some View {
        Group {
            switch route {
            case .page(let endpoint):
                switch endpoint.pageType {
                case .album: AlbumScreen(id: endpoint.browseId)
                case .artist, .userChannel: ArtistScreen(id: endpoint.browseId)
                case .playlist: PlaylistScreen(id: endpoint.browseId)
                case .podcast, .generic: BrowseScreen(source: .endpoint(endpoint))
                }
            case .search(let query, let submitted):
                SearchScreen(initialQuery: query, submitsInitialQuery: submitted)
            case .charts:
                BrowseScreen(source: .charts(country: nil))
            case .moods:
                MoodsScreen()
            case .settings:
                SettingsScreen()
            case .downloads:
                DownloadsScreen()
            case .downloadedCollection(let id):
                DownloadedCollectionScreen(id: id)
            }
        }
        .background(BoomColor.background.ignoresSafeArea())
    }
}

/// The flat tab bar: three buttons, the selected one in the accent.
struct BoomTabBar: View {
    let selected: BrowseTab
    let onSelect: (BrowseTab) -> Void

    var body: some View {
        HStack(spacing: 0) {
            ForEach(BrowseTab.allCases, id: \.self) { tab in
                let isSelected = tab == selected
                Button {
                    onSelect(tab)
                } label: {
                    VStack(spacing: 3) {
                        Image(systemName: isSelected ? tab.symbol + ".fill" : tab.symbol)
                            .font(.system(size: 21, weight: isSelected ? .semibold : .regular))
                            .frame(height: 26)
                        Text(tab.title)
                            .font(.system(size: 11, weight: isSelected ? .semibold : .medium))
                            .lineLimit(1)
                            .dynamicTypeSize(...DynamicTypeSize.xLarge)
                    }
                    .foregroundStyle(isSelected ? BoomColor.accent : BoomColor.textSecondary)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(tab.title)
                .accessibilityAddTraits(isSelected ? [.isSelected] : [])
            }
        }
        .frame(height: BoomMetrics.tabBarHeight)
        .overlay(alignment: .top) {
            BoomColor.separator.frame(height: 0.5)
        }
    }
}

extension EnvironmentValues {
    /// The height of the tab bar and the player bar over the bottom of every page.
    @Entry var shellBarsHeight: CGFloat = 0
    /// False on the pages of a tab that is not shown: they stay alive (to keep their place) but out of reach.
    @Entry var isShellTabActive = true
}

extension View {
    /// Keeps a page's content clear of the tab bar and player bar (scroll views end above them), and hides it from
    /// VoiceOver while its tab is not the one shown.
    func reservesShellBars() -> some View {
        modifier(ShellBarsInset())
    }
}

private struct ShellBarsInset: ViewModifier {
    @Environment(\.shellBarsHeight) private var height
    @Environment(\.isShellTabActive) private var isActive

    func body(content: Content) -> some View {
        content.safeAreaInset(edge: .bottom, spacing: 0) {
            Color.clear.frame(height: height).allowsHitTesting(false)
        }
        .accessibilityHidden(!isActive)
    }
}
