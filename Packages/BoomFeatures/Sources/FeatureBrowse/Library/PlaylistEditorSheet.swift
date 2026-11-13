import BoomDesign
import BoomModels
import BoomPlayer
import SwiftUI

/// Edit one of the listener's playlists: its name, description and privacy, which songs it holds, or delete it.
///
/// Removing a song takes effect at once on screen and is put back, with a message, if YouTube Music refuses. Name,
/// description and privacy are sent with "Save".
public struct PlaylistEditorSheet: View {
    private enum Status: Equatable {
        case idle
        case saving
        case deleting
        case failed(String)
    }

    private let page: PlaylistPage
    private let onChanged: () -> Void
    private let onDeleted: () -> Void

    @Environment(AppServices.self) private var services
    @Environment(\.dismiss) private var dismiss
    @State private var title: String
    @State private var details: String
    /// Nil keeps the privacy as it is: a playlist page does not say what it currently is.
    @State private var privacy: PlaylistPrivacy?
    @State private var songs: [Song]
    @State private var continuation: String?
    @State private var status = Status.idle
    @State private var removalNotice: String?
    @State private var isConfirmingDelete = false

    /// - Parameters:
    ///   - onChanged: Called after anything was saved or a song was removed, so the page behind can reload.
    ///   - onDeleted: Called after the playlist was deleted (the sheet has closed): the page behind has nothing
    ///     left to show and should go back.
    public init(page: PlaylistPage, onChanged: @escaping () -> Void, onDeleted: @escaping () -> Void = {}) {
        self.page = page
        self.onChanged = onChanged
        self.onDeleted = onDeleted
        _title = State(initialValue: page.playlist.title)
        _details = State(initialValue: page.description ?? "")
        _privacy = State(initialValue: nil)
        _songs = State(initialValue: page.tracks)
        _continuation = State(initialValue: page.continuation)
    }

    private var isBusy: Bool { status == .saving || status == .deleting }
    private var trimmedTitle: String { title.trimmingCharacters(in: .whitespacesAndNewlines) }
    private var trimmedDetails: String { details.trimmingCharacters(in: .whitespacesAndNewlines) }

    private var hasChanges: Bool {
        trimmedTitle != page.playlist.title || trimmedDetails != (page.description ?? "") || privacy != nil
    }

    public var body: some View {
        NavigationStack {
            List {
                detailsSection
                songsSection
                deleteSection
            }
            .scrollContentBackground(.hidden)
            .background(BoomColor.surface)
            .navigationTitle("Edit playlist")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(hasChanges ? "Cancel" : "Done") { dismiss() }
                        .disabled(isBusy)
                }
                ToolbarItem(placement: .confirmationAction) {
                    if status == .saving {
                        ProgressView()
                    } else {
                        Button("Save", action: save)
                            .fontWeight(.semibold)
                            .disabled(!hasChanges || trimmedTitle.isEmpty || isBusy)
                    }
                }
            }
        }
        .tint(BoomColor.accent)
        .interactiveDismissDisabled(isBusy)
        .presentationBackground(BoomColor.surface)
        .preferredColorScheme(.dark)
        .confirmationDialog(
            "Delete “\(page.playlist.title)”?", isPresented: $isConfirmingDelete, titleVisibility: .visible
        ) {
            Button("Delete playlist", role: .destructive, action: delete)
        } message: {
            Text("The playlist and the order of its songs will be gone. The songs themselves stay on BoomBoom.")
        }
        .alert(
            "Couldn't remove the song", isPresented: Binding(get: { removalNotice != nil }, set: { if !$0 { removalNotice = nil } }),
            presenting: removalNotice
        ) { _ in
            Button("OK") {}
        } message: { message in
            Text(message)
        }
        .task { await loadRemainingSongs() }
    }

    // MARK: Sections

    private var detailsSection: some View {
        Group {
            Section {
                TextField("Name", text: $title)
                TextField("Description (optional)", text: $details, axis: .vertical)
                    .lineLimit(1...4)
                Picker("Privacy", selection: $privacy) {
                    Text("Keep as is").tag(PlaylistPrivacy?.none)
                    ForEach(PlaylistPrivacy.allCases, id: \.self) { Text($0.title).tag(PlaylistPrivacy?.some($0)) }
                }
                .pickerStyle(.menu)
            } footer: {
                VStack(alignment: .leading, spacing: 6) {
                    if let privacy { Text(privacy.explanation) }
                    if case .failed(let message) = status {
                        Label(message, systemImage: "exclamationmark.triangle")
                            .foregroundStyle(BoomColor.destructive)
                    }
                }
            }
            .listRowBackground(BoomColor.surfaceRaised)
            .disabled(isBusy)
        }
    }

    private var songsSection: some View {
        Section {
            ForEach(songs, id: \.entryKey) { song in
                songRow(song)
            }
            if continuation != nil {
                HStack {
                    Spacer()
                    ProgressView()
                    Spacer()
                }
                .listRowBackground(BoomColor.surfaceRaised)
            } else if songs.isEmpty {
                Text("This playlist is empty")
                    .font(BoomFont.itemSubtitle)
                    .foregroundStyle(BoomColor.textSecondary)
                    .listRowBackground(BoomColor.surfaceRaised)
            }
        } header: {
            Text(songs.count == 1 ? "1 song" : "\(songs.count) songs")
                .font(BoomFont.button)
                .foregroundStyle(BoomColor.textSecondary)
                .textCase(nil)
        } footer: {
            if songs.contains(where: { $0.setVideoId != nil }) {
                Text("Swipe a song to remove it.")
            }
        }
    }

    private func songRow(_ song: Song) -> some View {
        HStack(spacing: BoomMetrics.rowSpacing) {
            ArtworkView(song.artwork, width: 40, height: 40)
            VStack(alignment: .leading, spacing: 2) {
                Text(song.title)
                    .font(BoomFont.itemTitle)
                    .foregroundStyle(BoomColor.textPrimary)
                    .lineLimit(1)
                Text(song.artistLine)
                    .font(BoomFont.itemSubtitle)
                    .foregroundStyle(BoomColor.textSecondary)
                    .lineLimit(1)
            }
            Spacer(minLength: 0)
        }
        .opacity(song.isAvailable ? 1 : 0.4)
        .listRowBackground(BoomColor.surfaceRaised)
        .swipeActions(edge: .trailing, allowsFullSwipe: true) {
            if song.setVideoId != nil {
                Button("Remove", systemImage: "trash", role: .destructive) { remove(song) }
            }
        }
    }

    private var deleteSection: some View {
        Section {
            Button(role: .destructive) {
                isConfirmingDelete = true
            } label: {
                HStack {
                    if status == .deleting { ProgressView() }
                    Text("Delete playlist")
                        .frame(maxWidth: .infinity)
                }
            }
            .disabled(isBusy)
        }
        .listRowBackground(BoomColor.surfaceRaised)
    }

    // MARK: Actions

    private func save() {
        guard hasChanges, !trimmedTitle.isEmpty, !isBusy else { return }
        status = .saving
        let newTitle = trimmedTitle != page.playlist.title ? trimmedTitle : nil
        let newDetails = trimmedDetails != (page.description ?? "") ? trimmedDetails : nil
        let newPrivacy = privacy
        let id = page.playlist.id
        let api = services.api
        Task {
            do {
                try await api.editPlaylist(id: id, title: newTitle, description: newDetails, privacy: newPrivacy)
                onChanged()
                dismiss()
            } catch {
                status = .failed("Couldn't save your changes. " + userMessage(for: error))
            }
        }
    }

    private func delete() {
        guard !isBusy else { return }
        status = .deleting
        let id = page.playlist.id
        let api = services.api
        Task {
            do {
                try await api.deletePlaylist(id: id)
                onChanged()
                dismiss()
                onDeleted()
            } catch {
                status = .failed("Couldn't delete the playlist. " + userMessage(for: error))
            }
        }
    }

    private func remove(_ song: Song) {
        guard let index = songs.firstIndex(where: { $0.entryKey == song.entryKey }) else { return }
        songs.remove(at: index)
        let id = page.playlist.id
        let api = services.api
        Task {
            do {
                try await api.removeFromPlaylist(id: id, songs: [song])
                onChanged()
            } catch {
                if !songs.contains(where: { $0.entryKey == song.entryKey }) {
                    songs.insert(song, at: min(index, songs.count))
                }
                removalNotice = "“\(song.title)” is still in the playlist. " + userMessage(for: error)
            }
        }
    }

    /// A long playlist arrives in parts; the rest is fetched so that every song can be removed.
    private func loadRemainingSongs() async {
        while let token = continuation {
            guard let more = try? await services.api.playlistContinuation(token), !Task.isCancelled else {
                continuation = nil
                return
            }
            let known = Set(songs.map(\.entryKey))
            songs += more.songs.filter { !known.contains($0.entryKey) }
            continuation = more.continuation
        }
    }
}

private extension Song {
    /// A song can sit in a playlist twice; the entry's own id tells the two apart.
    var entryKey: String { videoId + "|" + (setVideoId ?? "") }
}

/// Loads a playlist's page for the editor: the Library lists playlists, and the editor needs their songs.
struct PlaylistEditorLoader: View {
    private enum Load {
        case loading
        case failed(String)
        case loaded(PlaylistPage)
    }

    let playlist: PlaylistItem
    let onChanged: () -> Void

    @Environment(AppServices.self) private var services
    @Environment(\.dismiss) private var dismiss
    @State private var load = Load.loading
    @State private var retryCount = 0

    var body: some View {
        switch load {
        case .loaded(let page) where page.isEditable:
            PlaylistEditorSheet(page: page, onChanged: onChanged)
        case .loaded:
            placeholder {
                MessageView(
                    "You can't edit this playlist", message: "It belongs to someone else.", systemImage: "lock")
            }
        case .failed(let message):
            placeholder { ErrorView(message) { retryCount += 1 } }
        case .loading:
            placeholder { LoadingView(.list).padding(.top, 8) }
        }
    }

    private func placeholder<Content: View>(@ViewBuilder _ content: () -> Content) -> some View {
        NavigationStack {
            ScrollView { content() }
                .background(BoomColor.surface)
                .navigationTitle("Edit playlist")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button("Done") { dismiss() }
                    }
                }
        }
        .tint(BoomColor.accent)
        .presentationBackground(BoomColor.surface)
        .preferredColorScheme(.dark)
        .task(id: retryCount) { await fetch() }
    }

    private func fetch() async {
        if case .failed = load { load = .loading }
        do {
            let page = try await services.api.playlist(id: playlist.id)
            guard !Task.isCancelled else { return }
            load = .loaded(page)
        } catch {
            guard !Task.isCancelled else { return }
            load = .failed(userMessage(for: error))
        }
    }
}
