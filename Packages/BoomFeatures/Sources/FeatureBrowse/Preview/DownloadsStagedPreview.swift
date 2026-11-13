import BoomDesign
import BoomPlayer
import SwiftUI

/// The Downloads screen opened in a given view, with rows already picked, or one downloaded album or playlist: for
/// the gallery's screenshots, which have no finger to tap with.
public struct DownloadsStagedPreview: View {
    private let view: DownloadsView
    private let selectCount: Int
    private let collectionID: String?

    @Environment(AppServices.self) private var services
    @State private var isReady = false

    /// - Parameters:
    ///   - view: "songs" or "collections".
    ///   - selectCount: Opens in select mode with this many rows picked (0: not selecting).
    ///   - collectionID: Opens that downloaded album or playlist instead.
    public init(view: String, selectCount: Int, collectionID: String?) {
        self.view = DownloadsView(rawValue: view) ?? .songs
        self.selectCount = selectCount
        self.collectionID = collectionID
    }

    public var body: some View {
        NavigationStack {
            // Waits for the gallery's downloads, so the picked rows exist.
            if !isReady {
                BoomColor.background.ignoresSafeArea()
                    .task {
                        try? await Task.sleep(for: .seconds(1.5))
                        isReady = true
                    }
            } else if let collectionID {
                DownloadedCollectionScreen(id: collectionID)
            } else {
                DownloadsScreen(view: view, mode: mode)
            }
        }
        .tint(BoomColor.textPrimary)
        .preferredColorScheme(.dark)
    }

    private var mode: DownloadsMode {
        guard selectCount > 0 else { return .browsing }
        let ids = view == .songs
            ? services.downloads.items.filter(\.isDownloaded).map(\.id)
            : services.downloads.collections.map(\.id)
        return .selecting(Set(ids.prefix(selectCount)))
    }
}
