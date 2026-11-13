import BoomDesign
import BoomModels
import BoomPlayer
import SwiftUI

/// How a row of the Downloads screen answers a tap.
enum DownloadRowMode {
    case normal(onTap: () -> Void)
    /// Select mode: the row shows a checkmark circle and a tap picks it.
    case selecting(isSelected: Bool, onToggle: () -> Void)
}

/// One downloaded or pending song: cover, title, artist, and its size and quality (or its state while pending).
struct DownloadSongRow: View {
    let item: DownloadItem
    let mode: DownloadRowMode

    @Environment(AppServices.self) private var services

    var body: some View {
        HStack(spacing: BoomMetrics.rowSpacing) {
            Button(action: tap) {
                HStack(spacing: BoomMetrics.rowSpacing) {
                    if case .selecting(let isSelected, _) = mode {
                        SelectionMark(isSelected: isSelected)
                    }
                    ArtworkView(item.song.artwork, width: BoomMetrics.rowCover)
                        .overlay { if isCurrent { nowPlaying } }
                    VStack(alignment: .leading, spacing: 2) {
                        Text(item.song.title)
                            .font(BoomFont.itemTitle)
                            .foregroundStyle(isCurrent ? BoomColor.accent : BoomColor.textPrimary)
                            .lineLimit(1)
                        Text(item.song.artistLine)
                            .font(BoomFont.itemSubtitle)
                            .foregroundStyle(BoomColor.textSecondary)
                            .lineLimit(1)
                        HStack(spacing: 5) {
                            DownloadBadge(item.state, size: 12)
                            Text(DownloadsMath.detail(of: item, waitingForWiFi: services.downloads.isWaitingForWiFi))
                                .font(BoomFont.caption.monospacedDigit())
                                .foregroundStyle(isFailed ? BoomColor.destructive : BoomColor.textTertiary)
                                .lineLimit(1)
                        }
                    }
                    Spacer(minLength: 0)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(LibraryRowStyle())
            .accessibilityElement(children: .combine)
            .accessibilityAddTraits(isSelected ? [.isSelected] : [])
            .accessibilityHint(isSelecting ? "Selects it" : (item.isDownloaded ? "Plays" : ""))
            .accessibilityIdentifier("downloads.row")

            if !isSelecting {
                ItemMoreButton(.song(item.song))
                    .padding(.trailing, -10)
            }
        }
        .padding(.vertical, 4)
    }

    private var isCurrent: Bool { services.player.current?.videoId == item.id }

    private var isFailed: Bool {
        if case .failed = item.state { return true }
        return false
    }

    private var isSelecting: Bool {
        if case .selecting = mode { return true }
        return false
    }

    private var isSelected: Bool {
        if case .selecting(let isSelected, _) = mode { return isSelected }
        return false
    }

    private func tap() {
        switch mode {
        case .normal(let onTap): onTap()
        case .selecting(_, let onToggle): onToggle()
        }
    }

    private var nowPlaying: some View {
        ZStack {
            Color.black.opacity(0.45)
            NowPlayingBars(isAnimating: services.player.isPlayingOrAboutTo)
                .frame(width: 18, height: 16)
        }
        .clipShape(RoundedRectangle(cornerRadius: BoomMetrics.coverRadius))
    }
}

/// One downloaded album or playlist: cover, title, what it is, and "40 songs · 212 MB" or how far it has come.
struct DownloadCollectionRow: View {
    let collection: DownloadCollection
    let mode: DownloadRowMode

    @Environment(AppServices.self) private var services

    var body: some View {
        Button(action: tap) {
            HStack(spacing: BoomMetrics.rowSpacing) {
                if case .selecting(let isSelected, _) = mode {
                    SelectionMark(isSelected: isSelected)
                }
                ArtworkView(collection.artwork, width: BoomMetrics.rowCover)
                VStack(alignment: .leading, spacing: 2) {
                    Text(collection.title)
                        .font(BoomFont.itemTitle)
                        .foregroundStyle(BoomColor.textPrimary)
                        .lineLimit(1)
                    Text(kindLine)
                        .font(BoomFont.itemSubtitle)
                        .foregroundStyle(BoomColor.textSecondary)
                        .lineLimit(1)
                    HStack(spacing: 5) {
                        DownloadBadge(services.downloads.collectionState(collection.id), size: 12)
                        Text(detail)
                            .font(BoomFont.caption.monospacedDigit())
                            .foregroundStyle(BoomColor.textTertiary)
                            .lineLimit(1)
                    }
                }
                Spacer(minLength: 0)
                if case .normal = mode {
                    Image(systemName: "chevron.right")
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(BoomColor.textTertiary)
                }
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(LibraryRowStyle())
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(isSelected ? [.isSelected] : [])
        .accessibilityHint(isSelected || !isNormal ? "Selects it" : "Opens")
        .accessibilityIdentifier("downloads.collection")
        .padding(.vertical, 4)
    }

    private var kindLine: String {
        let kind = collection.kind == .album ? "Album" : "Playlist"
        guard let subtitle = collection.subtitle, !subtitle.isEmpty else { return kind }
        return "\(kind) · \(subtitle)"
    }

    private var detail: String {
        let store = services.downloads
        switch CollectionDownloadLook.of(collection.id, store: store, collector: DownloadCollector.shared) {
        case .arriving(let done, let total):
            return store.isWaitingForWiFi ? "\(done) of \(total) · waiting for Wi-Fi" : "\(done) of \(total) downloaded"
        case .partlyFailed(let done, let total):
            return "\(done) of \(total) · \(total - done) couldn't be downloaded"
        case .downloaded, .notDownloaded, .collecting:
            let total = store.songs(of: collection.id).count
            return "\(DownloadsMath.songs(total)) · \(DownloadSize.text(store.bytes(of: collection.id)))"
        }
    }

    private var isNormal: Bool {
        if case .normal = mode { return true }
        return false
    }

    private var isSelected: Bool {
        if case .selecting(let isSelected, _) = mode { return isSelected }
        return false
    }

    private func tap() {
        switch mode {
        case .normal(let onTap): onTap()
        case .selecting(_, let onToggle): onToggle()
        }
    }
}

/// The round checkmark at the front of a row in select mode.
struct SelectionMark: View {
    let isSelected: Bool

    var body: some View {
        Image(systemName: isSelected ? "checkmark.circle.fill" : "circle")
            .font(.system(size: 22, weight: .regular))
            .foregroundStyle(isSelected ? BoomColor.accent : BoomColor.textTertiary)
            .frame(width: 28)
            .accessibilityHidden(true)
    }
}

/// The row at the top of the Library tab that opens Downloads. Shown signed in or not, and with no connection.
struct DownloadsEntryRow: View {
    @Environment(AppServices.self) private var services
    @Environment(\.boomRowInset) private var inset

    var body: some View {
        NavigationLink(value: BrowseRoute.downloads) {
            HStack(spacing: BoomMetrics.rowSpacing) {
                Image(systemName: "arrow.down.circle.fill")
                    .font(.system(size: 24, weight: .semibold))
                    .foregroundStyle(BoomColor.accent)
                    .frame(width: BoomMetrics.rowCover, height: BoomMetrics.rowCover)
                    .background(BoomColor.surfaceRaised, in: RoundedRectangle(cornerRadius: BoomMetrics.coverRadius))
                VStack(alignment: .leading, spacing: 2) {
                    Text("Downloads")
                        .font(BoomFont.itemTitle)
                        .foregroundStyle(BoomColor.textPrimary)
                    Text(subtitle)
                        .font(BoomFont.itemSubtitle)
                        .foregroundStyle(BoomColor.textSecondary)
                        .lineLimit(1)
                }
                Spacer(minLength: 0)
                Image(systemName: "chevron.right")
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(BoomColor.textTertiary)
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(LibraryRowStyle())
        .accessibilityElement(children: .combine)
        .accessibilityHint("Opens")
        .accessibilityIdentifier("library.downloads")
        .padding(.horizontal, inset)
        .frame(minHeight: BoomMetrics.rowCover + 8)
    }

    private var subtitle: String {
        let store = services.downloads
        let count = store.downloaded.count
        if count == 0 && store.pendingCount == 0 { return "Songs that play without a connection" }
        var parts = ["\(DownloadsMath.songs(count)) · \(DownloadSize.text(store.totalBytes))"]
        if store.pendingCount > 0 { parts.append("\(store.pendingCount) to come") }
        return parts.joined(separator: " · ")
    }
}

/// A page that could not load: the message and Retry, and, when there are downloads, a way to them (they play
/// without a connection).
struct PageErrorView: View {
    let message: String
    let retry: (() -> Void)?

    @Environment(AppServices.self) private var services

    init(_ message: String, retry: (() -> Void)? = nil) {
        self.message = message
        self.retry = retry
    }

    var body: some View {
        VStack(spacing: 0) {
            ErrorView(message, retry: retry)
            if !services.downloads.downloaded.isEmpty {
                NavigationLink(value: BrowseRoute.downloads) {
                    Label("Play your downloads", systemImage: "arrow.down.circle.fill")
                }
                .buttonStyle(FilledPillButtonStyle())
                .accessibilityIdentifier("error.downloads")
                .padding(.top, -16)
            }
        }
    }
}
