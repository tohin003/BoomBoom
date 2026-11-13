import BoomDesign
import BoomModels
import BoomPlayer
import SwiftUI

/// What the listener is asked to confirm.
private enum SettingsConfirmation: Identifiable {
    case clearSongs

    var id: Self { self }
}

/// Settings, reached from the account button: the accounts (`AccountsSection`), playback, the songs kept on the
/// phone, and the app.
struct SettingsScreen: View {
    @Environment(AppServices.self) private var services
    @Environment(\.boomNavigator) private var navigator
    @State private var confirmation: SettingsConfirmation?
    @State private var expiry: Date?
    @State private var hasLog = false

    var body: some View {
        List {
            AccountsSection()
            playbackSection
            downloadsSection
            storageSection
            aboutSection
        }
        .listStyle(.insetGrouped)
        .scrollContentBackground(.hidden)
        .background(BoomColor.background.ignoresSafeArea())
        .navigationTitle("Settings")
        .navigationBarTitleDisplayMode(.large)
        .toolbarBackground(BoomColor.background, for: .navigationBar)
        .task {
            expiry = BuildInfo.expirationDate()
            hasLog = FileManager.default.fileExists(atPath: BoomTrace.fileURL.path)
            await services.cache.refresh()
        }
    }

    /// Whether the listener is being asked about `kind`. Each question is attached to the button that raises it,
    /// so it opens from that button: attached to the whole list, it opened wherever the list happened to put it.
    private func isAsking(_ kind: SettingsConfirmation) -> Binding<Bool> {
        Binding(get: { confirmation == kind }, set: { if !$0, confirmation == kind { confirmation = nil } })
    }

    // MARK: Playback

    private var playbackSection: some View {
        Section {
            Toggle(isOn: Binding(
                get: { services.player.isAutoplayEnabled }, set: { services.player.isAutoplayEnabled = $0 })
            ) {
                titleWithNote("Autoplay similar songs", "When the queue ends, keep playing songs like the last one.")
            }
            .tint(BoomColor.accent)
            Button {
                navigator.openEqualizer()
            } label: {
                HStack {
                    Text("Equaliser and amplifier")
                        .font(BoomFont.itemTitle)
                        .foregroundStyle(BoomColor.textPrimary)
                    Spacer()
                    Text(services.equalizer.settings.isEnabled ? "On" : "Off")
                        .font(BoomFont.itemSubtitle)
                        .foregroundStyle(
                            services.equalizer.settings.isEnabled ? BoomColor.accent : BoomColor.textSecondary)
                    Image(systemName: "chevron.right")
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(BoomColor.textTertiary)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            presetMenu
            VStack(alignment: .leading, spacing: 4) {
                titleWithNote("Audio quality", "The best stream on offer for your account is always used.")
                if let format = services.player.audioFormat {
                    Text(format)
                        .font(BoomFont.itemSubtitle)
                        .foregroundStyle(BoomColor.accent)
                }
                // Where it is going, and whether anything had to be converted on the way.
                if let output = services.player.audioOutput {
                    Text("To \(output)")
                        .font(BoomFont.itemSubtitle)
                        .foregroundStyle(BoomColor.textSecondary)
                }
            }
            .padding(.vertical, 2)
        } header: {
            header("Playback")
        }
        .listRowBackground(BoomColor.surface)
    }

    /// The sound presets without opening the equaliser: favourites first, then the listener's own, then the rest.
    private var presetMenu: some View {
        let equalizer = services.equalizer
        let isOn = equalizer.settings.isEnabled
        let selected = equalizer.presets.first { $0.id == equalizer.selectedPresetID }
        let favourites = equalizer.favoritePresets
        let starred = Set(favourites.map(\.id))
        let own = equalizer.presets.filter { !$0.isBuiltIn && !starred.contains($0.id) }
        let builtIn = equalizer.presets.filter { $0.isBuiltIn && !starred.contains($0.id) }
        func choice(_ preset: EqualizerPreset) -> some View {
            Button {
                equalizer.select(preset)
            } label: {
                if isOn, preset.id == selected?.id {
                    Label(preset.name, systemImage: "checkmark")
                } else if starred.contains(preset.id) {
                    Label(preset.name, systemImage: "star.fill")
                } else {
                    Text(preset.name)
                }
            }
        }
        return Menu {
            Button {
                equalizer.setEnabled(false)
            } label: {
                if isOn { Text("Off") } else { Label("Off", systemImage: "checkmark") }
            }
            if !favourites.isEmpty {
                Section("Favourites") { ForEach(favourites) { choice($0) } }
            }
            if !own.isEmpty {
                Section("Your presets") { ForEach(own) { choice($0) } }
            }
            Section("Presets") { ForEach(builtIn) { choice($0) } }
            Section {
                Button("Open the equaliser", systemImage: "slider.vertical.3") { navigator.openEqualizer() }
            }
        } label: {
            HStack {
                Text("Sound preset")
                    .font(BoomFont.itemTitle)
                    .foregroundStyle(BoomColor.textPrimary)
                Spacer()
                Text(isOn ? (selected?.name ?? "Custom") : "Off")
                    .font(BoomFont.itemSubtitle)
                    .foregroundStyle(isOn ? BoomColor.accent : BoomColor.textSecondary)
                    .lineLimit(1)
                Image(systemName: "chevron.up.chevron.down")
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundStyle(BoomColor.textTertiary)
            }
            .contentShape(Rectangle())
        }
        .accessibilityIdentifier("settings.preset")
        .accessibilityLabel("Sound preset")
        .accessibilityValue(isOn ? (selected?.name ?? "Custom") : "Off")
    }

    // MARK: Downloads

    private var downloadsSection: some View {
        let downloads = services.downloads
        return Section {
            NavigationLink(value: BrowseRoute.downloads) {
                HStack {
                    Text("Downloads")
                        .font(BoomFont.itemTitle)
                        .foregroundStyle(BoomColor.textPrimary)
                    Spacer()
                    Text("\(DownloadsMath.songs(downloads.downloaded.count)) · \(Self.bytes(downloads.totalBytes))")
                        .font(BoomFont.itemSubtitle.monospacedDigit())
                        .foregroundStyle(BoomColor.textSecondary)
                }
            }
            .accessibilityIdentifier("settings.downloads")
            Toggle(isOn: Binding(
                get: { downloads.isMobileDataAllowed }, set: { downloads.isMobileDataAllowed = $0 })
            ) {
                titleWithNote("Download over mobile data", "Off: downloads wait for Wi-Fi.")
            }
            .tint(BoomColor.accent)
            .accessibilityIdentifier("settings.downloadsMobileData")
        } header: {
            header("Downloads")
        } footer: {
            Text("Downloads are yours: they stay until you remove them. The cache below is kept by the app and trims "
                + "itself.")
        }
        .listRowBackground(BoomColor.surface)
    }

    // MARK: Storage

    private var storageSection: some View {
        let cache = services.cache
        return Section {
            valueRow("Songs in the cache", "\(cache.songCount)")
            valueRow("Space used", Self.bytes(cache.usedBytes))
            Picker("Use up to", selection: Binding(get: { cache.limitBytes }, set: { cache.limitBytes = $0 })) {
                ForEach(limitChoices(including: cache.limitBytes), id: \.self) { Text(Self.bytes($0)).tag($0) }
            }
            .font(BoomFont.itemTitle)
            .pickerStyle(.menu)
            Button("Empty the cache", role: .destructive) { confirmation = .clearSongs }
                .font(BoomFont.itemTitle)
                .disabled(cache.songCount == 0 && cache.usedBytes == 0)
                .confirmationDialog("Empty the song cache?", isPresented: isAsking(.clearSongs), titleVisibility: .visible) {
                    Button("Empty the cache", role: .destructive) {
                        Task { await services.cache.clear() }
                    }
                } message: {
                    Text("Songs start a little slower and need a connection until they are played again.")
                }
        } header: {
            header("Automatic song cache")
        } footer: {
            Text("BoomBoom keeps every song it fetches by itself: the ones you played and the next few in your "
                + "queue. They then start at once, use no data the next time and play without a connection. "
                + "You did not have to save them, and they are not downloads in your library. "
                + "When the limit is reached, the ones played longest ago are removed.")
        }
        .listRowBackground(BoomColor.surface)
    }

    private func limitChoices(including current: Int64) -> [Int64] {
        let choices = CacheStore.limitChoices
        return choices.contains(current) ? choices : (choices + [current]).sorted()
    }

    // MARK: About

    private var aboutSection: some View {
        Section {
            valueRow("Version", "\(BuildInfo.version) (\(BuildInfo.build))")
            if let expiry {
                VStack(alignment: .leading, spacing: 4) {
                    HStack {
                        Text("This build expires")
                            .font(BoomFont.itemTitle)
                            .foregroundStyle(BoomColor.textPrimary)
                        Spacer()
                        Text(expiry.formatted(date: .abbreviated, time: .omitted))
                            .font(BoomFont.itemSubtitle)
                            .foregroundStyle(expiry < .now ? BoomColor.destructive : BoomColor.textSecondary)
                    }
                    Text("Your Mac renews it automatically while it is nearby.")
                        .font(BoomFont.itemSubtitle)
                        .foregroundStyle(BoomColor.textSecondary)
                }
                .padding(.vertical, 2)
            }
            if hasLog {
                ShareLink(item: BoomTrace.fileURL) {
                    Label("Share diagnostic log", systemImage: "square.and.arrow.up")
                        .font(BoomFont.itemTitle)
                        .foregroundStyle(BoomColor.textPrimary)
                }
            } else {
                Label("Share diagnostic log", systemImage: "square.and.arrow.up")
                    .font(BoomFont.itemTitle)
                    .foregroundStyle(BoomColor.textTertiary)
            }
        } header: {
            header("About")
        } footer: {
            Text(hasLog
                ? "A short record of what the app did lately. It holds no account details."
                : "Nothing has been recorded yet.")
        }
        .listRowBackground(BoomColor.surface)
    }

    // MARK: Pieces

    private func header(_ title: String) -> some View {
        Text(title)
            .font(BoomFont.button)
            .foregroundStyle(BoomColor.textSecondary)
            .textCase(nil)
    }

    private func titleWithNote(_ title: String, _ note: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title)
                .font(BoomFont.itemTitle)
                .foregroundStyle(BoomColor.textPrimary)
            Text(note)
                .font(BoomFont.itemSubtitle)
                .foregroundStyle(BoomColor.textSecondary)
        }
    }

    private func valueRow(_ title: String, _ value: String) -> some View {
        HStack {
            Text(title)
                .font(BoomFont.itemTitle)
                .foregroundStyle(BoomColor.textPrimary)
            Spacer()
            Text(value)
                .font(BoomFont.itemSubtitle.monospacedDigit())
                .foregroundStyle(BoomColor.textSecondary)
        }
        .accessibilityElement(children: .combine)
    }

    private static func bytes(_ count: Int64) -> String {
        let formatter = ByteCountFormatter()
        formatter.countStyle = .file
        formatter.allowsNonnumericFormatting = false
        return formatter.string(fromByteCount: count)
    }
}
