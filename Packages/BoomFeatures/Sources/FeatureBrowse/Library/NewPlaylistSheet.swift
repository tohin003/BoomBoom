import BoomDesign
import BoomModels
import BoomPlayer
import SwiftUI

extension PlaylistPrivacy {
    var title: String {
        switch self {
        case .private: "Private"
        case .unlisted: "Unlisted"
        case .public: "Public"
        }
    }

    var explanation: String {
        switch self {
        case .private: "Only you can see this playlist."
        case .unlisted: "Anyone with the link can see it. It does not show up in search."
        case .public: "Anyone can find and play this playlist."
        }
    }
}

/// The form for a new playlist: a name, a description, who can see it.
public struct NewPlaylistSheet: View {
    private enum Status: Equatable {
        case idle
        case creating
        case failed(String)
    }

    private let onCreated: (String) -> Void

    @Environment(AppServices.self) private var services
    @Environment(\.dismiss) private var dismiss
    @State private var title = ""
    @State private var details = ""
    @State private var privacy = PlaylistPrivacy.private
    @State private var status = Status.idle
    @FocusState private var isTitleFocused: Bool

    /// - Parameter onCreated: Called with the new playlist's id once it exists; the sheet closes itself.
    public init(onCreated: @escaping (String) -> Void) {
        self.onCreated = onCreated
    }

    private var trimmedTitle: String { title.trimmingCharacters(in: .whitespacesAndNewlines) }
    private var isCreating: Bool { status == .creating }

    public var body: some View {
        NavigationStack {
            List {
                Section {
                    TextField("Name", text: $title)
                        .focused($isTitleFocused)
                        .submitLabel(.next)
                    TextField("Description (optional)", text: $details, axis: .vertical)
                        .lineLimit(1...4)
                }
                .listRowBackground(BoomColor.surfaceRaised)
                Section {
                    Picker("Privacy", selection: $privacy) {
                        ForEach(PlaylistPrivacy.allCases, id: \.self) { Text($0.title).tag($0) }
                    }
                    .pickerStyle(.menu)
                } footer: {
                    Text(privacy.explanation)
                }
                .listRowBackground(BoomColor.surfaceRaised)
                if case .failed(let message) = status {
                    Section {
                        Label(message, systemImage: "exclamationmark.triangle")
                            .font(BoomFont.itemSubtitle)
                            .foregroundStyle(BoomColor.destructive)
                    }
                    .listRowBackground(BoomColor.surfaceRaised)
                }
            }
            .scrollContentBackground(.hidden)
            .background(BoomColor.surface)
            .navigationTitle("New playlist")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                        .disabled(isCreating)
                }
                ToolbarItem(placement: .confirmationAction) {
                    if isCreating {
                        ProgressView()
                    } else {
                        Button("Create", action: create)
                            .fontWeight(.semibold)
                            .disabled(trimmedTitle.isEmpty)
                    }
                }
            }
        }
        .tint(BoomColor.accent)
        .interactiveDismissDisabled(isCreating)
        .presentationDetents([.medium, .large])
        .presentationBackground(BoomColor.surface)
        .preferredColorScheme(.dark)
        .onAppear { isTitleFocused = true }
    }

    private func create() {
        guard !trimmedTitle.isEmpty, !isCreating else { return }
        status = .creating
        let name = trimmedTitle
        let description = details.trimmingCharacters(in: .whitespacesAndNewlines)
        let privacy = privacy
        let api = services.api
        Task {
            do {
                let id = try await api.createPlaylist(
                    title: name, description: description, privacy: privacy, videoIds: [])
                dismiss()
                onCreated(id)
            } catch {
                status = .failed("Couldn't create the playlist. " + userMessage(for: error))
            }
        }
    }
}
