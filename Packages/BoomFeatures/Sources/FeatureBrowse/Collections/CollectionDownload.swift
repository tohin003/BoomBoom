import BoomDesign
import BoomModels
import BoomPlayer
import SwiftUI

/// What an album or playlist page hands its download button: which collection, and how to gather its songs from
/// what the page already holds.
struct CollectionDownloadSource {
    let kind: DownloadCollection.Kind
    let entry: DownloadCollection
    /// The songs on the page so far.
    let songs: [Song]
    /// The token of the playlist's next part, when more songs exist than the page has loaded.
    let continuation: String?

    var id: String { entry.id }
}

/// The round download button of an album's or playlist's header, in four looks (see `CollectionDownloadLook`):
/// an arrow; a ring while songs arrive; the accent when all are in; a warning when some could not be fetched.
/// Every tap but the first asks what to do, in a dialog attached to this button.
struct CollectionDownloadButton: View {
    let source: CollectionDownloadSource

    @Environment(AppServices.self) private var services
    /// The look the listener tapped: the dialog's words follow it, even if the look moves on while it is open.
    @State private var asked: CollectionDownloadLook?

    private var look: CollectionDownloadLook {
        CollectionDownloadLook.of(source.id, store: services.downloads, collector: DownloadCollector.shared)
    }

    var body: some View {
        let look = look
        Button {
            if look == .notDownloaded {
                start()
            } else {
                asked = look
            }
        } label: {
            glyph(look)
                .frame(width: 44, height: 44)
                .background(look.isComplete ? BoomColor.accent : Color.white.opacity(0.1), in: Circle())
                .contentShape(Circle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(look.label)
        .accessibilityValue(look.value)
        .accessibilityIdentifier("collection.download")
        .confirmationDialog(
            asked.map { $0.question(source.entry.title) } ?? "", isPresented: Binding(
                get: { asked != nil }, set: { if !$0 { asked = nil } }),
            titleVisibility: .visible, presenting: asked
        ) { asked in
            switch asked {
            case .partlyFailed:
                Button("Try the missing songs again") { retry() }
                Button("Remove download", role: .destructive) { remove() }
            case .downloaded:
                Button("Remove download", role: .destructive) { remove() }
                // A playlist grows: downloading it again adds the songs that joined it since.
                if source.kind == .playlist {
                    Button("Add new songs") { start() }
                }
            case .collecting, .arriving:
                Button("Stop and remove", role: .destructive) { remove() }
            case .notDownloaded:
                EmptyView()
            }
        } message: { asked in
            Text(asked.explanation(freed: DownloadsMath.freedBytes(collections: [source.id], in: services.downloads)))
        }
    }

    @ViewBuilder
    private func glyph(_ look: CollectionDownloadLook) -> some View {
        switch look {
        case .notDownloaded:
            Image(systemName: "arrow.down")
                .font(.system(size: 18, weight: .medium))
                .foregroundStyle(BoomColor.textPrimary)
        case .collecting:
            ProgressView()
                .controlSize(.small)
                .tint(BoomColor.textPrimary)
        case .arriving(let done, let total):
            ZStack {
                DownloadRing(fraction: total > 0 ? Double(done) / Double(total) : 0, lineWidth: 2.5)
                    .frame(width: 26, height: 26)
                Image(systemName: "square.fill")
                    .font(.system(size: 8))
                    .foregroundStyle(BoomColor.accent)
            }
        case .downloaded:
            Image(systemName: "arrow.down")
                .font(.system(size: 18, weight: .bold))
                .foregroundStyle(BoomColor.onAccent)
        case .partlyFailed:
            Image(systemName: "exclamationmark")
                .font(.system(size: 18, weight: .bold))
                .foregroundStyle(BoomColor.destructive)
        }
    }

    private func start() {
        DownloadCollector.shared.download(
            source.kind, id: source.id, seed: (source.entry, source.songs, source.continuation),
            api: services.api, store: services.downloads)
    }

    private func retry() {
        let store = services.downloads
        guard let entry = store.collections.first(where: { $0.id == source.id }) else { return }
        // Asking for the collection again with its own songs sets its failed ones going; other failures stay put.
        store.download(store.songs(of: source.id).map(\.song), as: entry)
    }

    private func remove() {
        DownloadCollector.shared.cancel(source.id)
        services.downloads.removeCollection(source.id)
    }
}

/// The line under an album's or playlist's buttons that says where its download stands: "Collecting the list…
/// 300 songs", "Downloading 12 of 40", "Downloaded · 40 songs · 212 MB", "2 couldn't be downloaded · Retry".
struct CollectionDownloadStatus: View {
    let source: CollectionDownloadSource

    @Environment(AppServices.self) private var services

    var body: some View {
        let store = services.downloads
        let collector = DownloadCollector.shared
        let look = CollectionDownloadLook.of(source.id, store: store, collector: collector)
        let isCapped = collector.capped.contains(source.id)
        if look != .notDownloaded {
            VStack(spacing: 4) {
                HStack(spacing: 6) {
                    DownloadBadge(badge(look), size: 12)
                    Text(text(look, waitingForWiFi: store.isWaitingForWiFi))
                        .font(BoomFont.caption.monospacedDigit())
                        .foregroundStyle(BoomColor.textSecondary)
                    if case .partlyFailed = look {
                        Button("Retry") {
                            guard let entry = store.collections.first(where: { $0.id == source.id }) else { return }
                            store.download(store.songs(of: source.id).map(\.song), as: entry)
                        }
                        .font(BoomFont.caption.weight(.semibold))
                        .foregroundStyle(BoomColor.accent)
                        .accessibilityIdentifier("collection.download.retry")
                    }
                }
                .accessibilityElement(children: .combine)
                .accessibilityIdentifier("collection.download.status")
                if isCapped {
                    Text("Only the first \(DownloadCollector.songLimit) songs are downloaded.")
                        .font(BoomFont.caption)
                        .foregroundStyle(BoomColor.textTertiary)
                }
            }
            .padding(.top, 10)
            .transition(.opacity)
        }
    }

    private func badge(_ look: CollectionDownloadLook) -> DownloadItem.State? {
        switch look {
        case .notDownloaded, .collecting: nil
        case .arriving(let done, let total): .downloading(total > 0 ? Double(done) / Double(total) : 0)
        case .downloaded: .downloaded
        case .partlyFailed: .failed("")
        }
    }

    private func text(_ look: CollectionDownloadLook, waitingForWiFi: Bool) -> String {
        switch look {
        case .notDownloaded: ""
        case .collecting(let found):
            found > 0 ? "Collecting the list… \(DownloadsMath.songs(found))" : "Collecting the list…"
        case .arriving(let done, let total):
            waitingForWiFi ? "\(done) of \(total) · waiting for Wi-Fi" : "Downloading \(done) of \(total)"
        case .downloaded(let total, let bytes):
            "Downloaded · \(DownloadsMath.songs(total)) · \(DownloadSize.text(bytes))"
        case .partlyFailed(let done, let total):
            "\(done) of \(total) downloaded · \(total - done) couldn't be"
        }
    }
}

extension CollectionDownloadLook {
    /// Drawn in the accent: everything is on the phone.
    var isComplete: Bool {
        if case .downloaded = self { return true }
        return false
    }

    var label: String {
        switch self {
        case .notDownloaded: "Download"
        case .collecting, .arriving: "Downloading"
        case .downloaded: "Downloaded"
        case .partlyFailed: "Partly downloaded"
        }
    }

    /// "12 of 40" and the like, for VoiceOver and for tests.
    var value: String {
        switch self {
        case .notDownloaded: ""
        case .collecting(let found): "Collecting, \(found) found"
        case .arriving(let done, let total): "\(done) of \(total)"
        case .downloaded(let total, _): "\(total) songs"
        case .partlyFailed(let done, let total): "\(done) of \(total), some failed"
        }
    }

    func question(_ title: String) -> String {
        switch self {
        case .notDownloaded: ""
        case .collecting, .arriving: "Stop downloading “\(title)”?"
        case .downloaded: "Remove “\(title)” from downloads?"
        case .partlyFailed: "Some songs of “\(title)” couldn't be downloaded"
        }
    }

    func explanation(freed: Int64) -> String {
        switch self {
        case .notDownloaded: ""
        case .collecting, .arriving:
            "The songs downloaded so far are removed too. Songs you also downloaded on their own stay."
        case .downloaded:
            "This frees \(DownloadSize.text(freed)). Songs you also downloaded on their own, or that another "
                + "download holds, stay."
        case .partlyFailed(let done, let total):
            "\(done) of \(total) are on the phone. The others can be tried again."
        }
    }
}
