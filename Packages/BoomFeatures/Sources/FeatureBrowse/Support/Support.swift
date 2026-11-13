import BoomDesign
import BoomModels
import SwiftUI

/// A screen that can be pushed onto a tab's stack.
enum BrowseRoute: Hashable {
    /// An album, an artist, a playlist, or any other page of shelves.
    case page(BrowseEndpoint)
    case search(query: String?, submitted: Bool = true)
    case charts
    case moods
    case settings
    /// The songs, albums and playlists kept on the phone.
    case downloads
    /// One downloaded album or playlist, by its browse id.
    case downloadedCollection(String)
}

/// Shows skeletons, an error with Retry, or the content, from a `LoadState`.
struct LoadStateView<Value, Content: View>: View {
    let state: LoadState<Value>
    var style: LoadingView.Style = .shelves
    let retry: () -> Void
    @ViewBuilder let content: (Value) -> Content

    var body: some View {
        switch state {
        case .loading:
            ScrollView { LoadingView(style).padding(.top, 8) }
                .scrollDisabled(true)
        case .failed(let message):
            ScrollView { PageErrorView(message, retry: retry) }
        case .loaded(let value):
            content(value)
        }
    }
}

extension EnvironmentValues {
    /// Goes up when the listener taps the tab that is already selected and at its root: scroll to the top.
    @Entry var tabReselect = 0
}

/// Shows the page's name in the navigation bar once its own big title has scrolled away, and keeps the bar clear
/// above the header before that.
struct CollapsingTitle: ViewModifier {
    let title: String
    let isShown: Bool

    func body(content: Content) -> some View {
        content
            .navigationTitle(isShown ? title : "")
            .navigationBarTitleDisplayMode(.inline)
            .toolbarBackground(BoomColor.background, for: .navigationBar)
            .toolbarBackgroundVisibility(isShown ? .visible : .hidden, for: .navigationBar)
            .animation(.easeInOut(duration: 0.2), value: isShown)
    }
}

extension View {
    func collapsingTitle(_ title: String, isShown: Bool) -> some View {
        modifier(CollapsingTitle(title: title, isShown: isShown))
    }

    /// Calls `action` with whether the scroll view has moved past `threshold` points.
    func onScrolledPast(_ threshold: CGFloat, _ action: @escaping (Bool) -> Void) -> some View {
        onScrollGeometryChange(for: Bool.self) { geometry in
            geometry.contentOffset.y + geometry.contentInsets.top > threshold
        } action: { _, isPast in
            action(isPast)
        }
    }

    /// Calls `action` with how far the content has scrolled (negative while pulled down).
    func onScrollOffset(_ action: @escaping (CGFloat) -> Void) -> some View {
        onScrollGeometryChange(for: CGFloat.self) { geometry in
            geometry.contentOffset.y + geometry.contentInsets.top
        } action: { _, offset in
            action(offset)
        }
    }
}

/// The row at the end of a list that loads more when it comes near the screen: a spinner while the next part is on
/// its way, Retry when asking for it failed.
struct LoadMoreRow: View {
    let more: MoreState
    let load: () -> Void

    init(more: MoreState, load: @escaping () -> Void) {
        self.more = more
        self.load = load
    }

    /// For lists that track their next part with a flag.
    init(token: String?, isLoading: Bool, load: @escaping () -> Void) {
        more = token.map { isLoading ? .loading($0) : .ready($0) } ?? .end
        self.load = load
    }

    var body: some View {
        HStack {
            switch more {
            case .loading:
                ProgressView().tint(BoomColor.textSecondary)
            case .failed:
                Button("Retry", action: load)
                    .buttonStyle(OutlinePillButtonStyle())
                    .accessibilityHint("Loads more")
            case .ready, .end:
                EmptyView()
            }
        }
        .frame(maxWidth: .infinity, minHeight: 48)
        // On appearing, and for each new token while on screen. A failed part is asked again when the row comes back.
        .task(id: more.token) { load() }
    }
}
