import BoomDesign
import BoomModels
import BoomPlayer
import SwiftUI

/// The full player's page: header, cover, title, scrubber, controls, and the tabs to "Up next", "Lyrics",
/// "Related".
struct PlayerScreen: View {
    @Environment(AppServices.self) private var services
    @Environment(OverlayModel.self) private var model
    @Environment(\.boomNavigator) private var navigator
    @State private var backdrop: Color = BoomColor.surface
    @State private var coverBox: CGSize = .zero
    @State private var extras = PlayerExtras()
    @GestureState private var isDraggingDown = false

    var body: some View {
        let player = services.player
        VStack(spacing: 0) {
            PlayerTopBar()
                .padding(.horizontal, 4)
            Color.clear
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .onGeometryChange(for: CGSize.self, of: \.size) { coverBox = $0 }
                .overlay {
                    let side = max(80, min(coverBox.width - 2 * 24, coverBox.height - 24))
                    CoverCarousel(side: side)
                }
            VStack(spacing: 0) {
                TitleBlock()
                    .padding(.horizontal, BoomMetrics.margin - 4)
                Scrubber(player: player)
                    .padding(.horizontal, BoomMetrics.margin + 8)
                    .padding(.top, 14)
                TransportRow()
                    .padding(.horizontal, BoomMetrics.margin)
                    .padding(.top, 6)
                FailureNotice()
                SecondaryRow()
                    .padding(.horizontal, BoomMetrics.margin + 16)
                    .padding(.top, 6)
                PanelTabsBar()
                    .padding(.top, 4)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background { PlayerBackdrop(color: backdrop) }
        .contentShape(Rectangle())
        .gesture(collapseDrag)
        .onDragCancel(isTouching: isDraggingDown) { model.dragCancelled() }
        .overlay { PanelHost() }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("player")
        .environment(extras)
        .task(id: player.current?.artwork) { await loadBackdrop(for: player.current) }
        .environment(\.colorScheme, .dark)
    }

    private var collapseDrag: some Gesture {
        DragGesture(minimumDistance: 12, coordinateSpace: .global)
            .updating($isDraggingDown) { _, touching, _ in touching = true }
            .onChanged { model.dragChanged($0.translation) }
            .onEnded { value in
                model.dragEnded(
                    translation: value.translation, predicted: value.predictedEndTranslation,
                    velocity: value.velocity.height)
            }
    }

    private func loadBackdrop(for song: Song?) async {
        guard let song, let url = song.artwork.url(pixelWidth: 96) else {
            withAnimation(.easeInOut(duration: 0.6)) { backdrop = BoomColor.surface }
            return
        }
        let image = try? await ImagePipeline.shared.image(for: url)
        guard !Task.isCancelled else { return }
        // A cover that fails to load must not leave the previous song's colour behind.
        let color = image.map { ImagePipeline.shared.backdropColor(of: $0) } ?? BoomColor.surface
        withAnimation(.easeInOut(duration: 0.6)) { backdrop = color }
    }
}

/// The page's gradient, rounded at the top while it is being dragged down. Reads the drag offset itself so the
/// rest of the player does not redraw as it moves.
private struct PlayerBackdrop: View {
    let color: Color
    @Environment(OverlayModel.self) private var model

    var body: some View {
        UnevenRoundedRectangle(
            topLeadingRadius: radius, bottomLeadingRadius: 0, bottomTrailingRadius: 0, topTrailingRadius: radius,
            style: .continuous
        )
        .fill(LinearGradient(
            stops: [
                .init(color: color, location: 0),
                .init(color: color.mix(with: BoomColor.background, by: 0.45), location: 0.55),
                .init(color: BoomColor.background, location: 1),
            ], startPoint: .top, endPoint: .bottom))
        .ignoresSafeArea()
    }

    private var radius: CGFloat { min(max(model.offset, 0) / 4, 40) }
}

// MARK: Header

private struct PlayerTopBar: View {
    @Environment(AppServices.self) private var services
    @Environment(OverlayModel.self) private var model
    @Environment(\.boomNavigator) private var navigator

    var body: some View {
        let player = services.player
        HStack(spacing: 0) {
            IconButton(systemName: "chevron.down", label: "Close player", size: 20, weight: .semibold) {
                model.dismiss()
            }
            Spacer(minLength: 8)
            sourceLabel(player.source)
            Spacer(minLength: 8)
            PlayerMoreMenu()
        }
        .frame(height: 52)
    }

    @ViewBuilder
    private func sourceLabel(_ source: QueueSource?) -> some View {
        let content = VStack(spacing: 2) {
            Text("PLAYING FROM \(source?.label.uppercased() ?? "")")
                .font(.system(.caption2, weight: .medium))
                .kerning(0.8)
                .foregroundStyle(BoomColor.textSecondary)
                .lineLimit(1)
            Text(source?.title ?? "Your queue")
                .font(.system(.subheadline, weight: .semibold))
                .foregroundStyle(BoomColor.textPrimary)
                .lineLimit(1)
        }
        if let endpoint = source?.endpoint {
            Button {
                model.dismiss()
                navigator.open(endpoint)
            } label: { content.contentShape(Rectangle()) }
                .buttonStyle(.plain)
                .accessibilityHint("Opens it")
        } else {
            content.accessibilityElement(children: .combine)
        }
    }
}

private struct PlayerMoreMenu: View {
    @Environment(AppServices.self) private var services
    @Environment(OverlayModel.self) private var model
    @Environment(\.boomNavigator) private var navigator

    var body: some View {
        let player = services.player
        Menu {
            if let song = player.current {
                Button("Start radio", systemImage: "dot.radiowaves.left.and.right") { player.startRadio(from: song) }
                Button("Save to playlist", systemImage: "text.badge.plus") { navigator.addToPlaylist([song]) }
                downloadButton(song)
                if let albumID = song.album?.id {
                    Button("Go to album", systemImage: "square.stack") {
                        leave(to: BrowseEndpoint(browseId: albumID, pageType: .album))
                    }
                }
                if let artistID = song.firstLinkedArtist?.id {
                    Button("Go to artist", systemImage: "person") {
                        leave(to: BrowseEndpoint(browseId: artistID, pageType: .artist))
                    }
                }
                if let url = song.shareURL {
                    ShareLink(item: url) { Label("Share", systemImage: "square.and.arrow.up") }
                }
            }
            Menu {
                SleepTimerMenuItems(player: player)
            } label: {
                Label("Sleep timer", systemImage: "moon.zzz")
            }
            Button("Equaliser", systemImage: "slider.vertical.3") { navigator.openEqualizer() }
        } label: {
            Image(systemName: "ellipsis")
                .font(.system(size: 20, weight: .semibold))
                .foregroundStyle(BoomColor.textPrimary)
                .rotationEffect(.degrees(90))
                .frame(width: BoomMetrics.minimumTarget, height: BoomMetrics.minimumTarget)
                .contentShape(Rectangle())
        }
        .accessibilityLabel("More")
    }

    /// "Download", or "Remove download" once the song is kept or on its way.
    @ViewBuilder
    private func downloadButton(_ song: Song) -> some View {
        let downloads = services.downloads
        switch downloads.state(of: song.videoId) {
        case nil, .failed:
            Button("Download", systemImage: "arrow.down.circle") { downloads.download(song) }
        case .waiting, .downloading, .downloaded:
            Button("Remove download", systemImage: "trash", role: .destructive) { downloads.remove([song.videoId]) }
        }
    }

    private func leave(to endpoint: BrowseEndpoint) {
        model.dismiss()
        navigator.open(endpoint)
    }
}

// MARK: Title and rating

private struct TitleBlock: View {
    @Environment(AppServices.self) private var services
    @Environment(OverlayModel.self) private var model
    @Environment(\.boomNavigator) private var navigator

    var body: some View {
        let player = services.player
        let song = player.current
        HStack(spacing: 4) {
            rateButton(player, .dislike)
            VStack(spacing: 4) {
                MarqueeText(
                    text: song?.title ?? "", font: .system(.title2, weight: .bold), alignment: .center)
                artistLine(song)
            }
            .frame(maxWidth: .infinity)
            rateButton(player, .like)
        }
        .frame(minHeight: 64)
    }

    @ViewBuilder
    private func artistLine(_ song: Song?) -> some View {
        let text = Text(song?.artistLine ?? "")
            .font(.system(.body))
            .foregroundStyle(BoomColor.textSecondary)
            .lineLimit(1)
        if let song, let artistID = song.firstLinkedArtist?.id {
            Button {
                model.dismiss()
                navigator.open(BrowseEndpoint(browseId: artistID, pageType: .artist))
            } label: { text }
                .buttonStyle(.plain)
                .accessibilityHint("Opens the artist")
        } else {
            text
        }
    }

    private func rateButton(_ player: PlayerStore, _ status: LikeStatus) -> some View {
        let isOn = player.likeStatus == status
        let isLike = status == .like
        let glyph = (isLike ? "hand.thumbsup" : "hand.thumbsdown") + (isOn ? ".fill" : "")
        return Button {
            // A rating needs an account: signed out, it would only flip and silently flip back.
            guard services.session.isSignedIn else { return navigator.signIn() }
            player.setLike(isOn ? .indifferent : status)
        } label: {
            Image(systemName: glyph)
                .font(.system(size: 22))
                .foregroundStyle(isOn ? BoomColor.accent : BoomColor.textPrimary)
                .contentTransition(.symbolEffect(.replace))
                .frame(width: 48, height: 48)
                .contentShape(Rectangle())
        }
        .buttonStyle(PressScaleStyle(scale: 0.8))
        .sensoryFeedback(.impact(weight: .light), trigger: isOn) { _, new in new }
        .disabled(player.current == nil)
        .accessibilityLabel(isLike ? "Like" : "Dislike")
        .accessibilityAddTraits(isOn ? .isSelected : [])
    }
}

// MARK: Controls

private struct TransportRow: View {
    @Environment(AppServices.self) private var services

    var body: some View {
        let player = services.player
        HStack(spacing: 0) {
            IconButton(
                systemName: "shuffle", label: player.isShuffled ? "Shuffle on" : "Shuffle off", size: 21,
                tint: player.isShuffled ? BoomColor.accent : BoomColor.textPrimary
            ) { player.toggleShuffle() }
            Spacer(minLength: 0)
            IconButton(systemName: "backward.end.fill", label: "Previous", size: 30) { player.previous() }
                .disabled(player.current == nil)
            Spacer(minLength: 0)
            BigPlayButton(player: player)
            Spacer(minLength: 0)
            IconButton(systemName: "forward.end.fill", label: "Next", size: 30) { player.next() }
                .disabled(!player.hasNext)
                .opacity(player.hasNext ? 1 : 0.35)
            Spacer(minLength: 0)
            IconButton(
                systemName: player.repeatMode == .one ? "repeat.1" : "repeat", label: repeatLabel(player.repeatMode),
                size: 21, tint: player.repeatMode == .off ? BoomColor.textPrimary : BoomColor.accent
            ) { player.cycleRepeatMode() }
                .contentTransition(.symbolEffect(.replace))
        }
    }

    private func repeatLabel(_ mode: RepeatMode) -> String {
        switch mode {
        case .off: "Repeat off"
        case .all: "Repeat all"
        case .one: "Repeat one"
        }
    }
}

private struct BigPlayButton: View {
    let player: PlayerStore

    var body: some View {
        Button { player.togglePlayPause() } label: {
            ZStack {
                Circle().fill(BoomColor.textPrimary)
                Image(systemName: glyph)
                    .font(.system(size: 30, weight: .bold))
                    .foregroundStyle(BoomColor.background)
                    .contentTransition(.symbolEffect(.replace))
                    .offset(x: glyph == "play.fill" ? 2 : 0)
                if player.status.isWaiting {
                    SpinnerRing(color: BoomColor.accent, lineWidth: 3.5)
                        .padding(-7)
                }
            }
            .frame(width: 72, height: 72)
            .contentShape(Circle())
        }
        .buttonStyle(PressScaleStyle(scale: 0.92))
        .accessibilityLabel(accessibilityText)
        .accessibilityValue(player.status.isWaiting ? "Loading" : "")
    }

    private var glyph: String {
        if case .failed = player.status { return "arrow.clockwise" }
        return player.isPlayingOrAboutTo ? "pause.fill" : "play.fill"
    }

    private var accessibilityText: String {
        if case .failed = player.status { return "Retry" }
        return player.isPlayingOrAboutTo ? "Pause" : "Play"
    }
}

private struct FailureNotice: View {
    @Environment(AppServices.self) private var services

    var body: some View {
        let player = services.player
        if let message = player.status.failureMessage {
            HStack(spacing: 10) {
                Image(systemName: "exclamationmark.triangle.fill")
                    .foregroundStyle(BoomColor.accent)
                Text(message)
                    .font(BoomFont.itemSubtitle)
                    .foregroundStyle(BoomColor.textSecondary)
                    .lineLimit(2)
                    .frame(maxWidth: .infinity, alignment: .leading)
                Button("Retry") { player.retry() }
                    .font(BoomFont.button)
                    .foregroundStyle(BoomColor.onAccent)
                    .padding(.horizontal, 14)
                    .frame(minHeight: 32)
                    .background(BoomColor.accent, in: Capsule())
                    .frame(minHeight: BoomMetrics.minimumTarget)
            }
            .padding(.horizontal, BoomMetrics.margin + 8)
            .transition(.opacity.combined(with: .move(edge: .top)))
        }
    }
}

private struct SecondaryRow: View {
    @Environment(AppServices.self) private var services
    @Environment(\.boomNavigator) private var navigator

    var body: some View {
        let player = services.player
        let eqOn = services.equalizer.settings.isEnabled
        HStack {
            IconButton(
                systemName: "slider.vertical.3", label: eqOn ? "Sound, equaliser on" : "Sound, equaliser off",
                size: 19, tint: eqOn ? BoomColor.accent : BoomColor.textSecondary
            ) { navigator.openEqualizer() }
            Spacer()
            RoutePickerButton()
                .frame(width: BoomMetrics.minimumTarget, height: BoomMetrics.minimumTarget)
                .accessibilityLabel("AirPlay and Bluetooth")
            Spacer()
            SleepTimerButton(player: player)
        }
    }
}

private struct SleepTimerButton: View {
    let player: PlayerStore

    var body: some View {
        let isSet = player.sleepTimer != nil
        Menu {
            SleepTimerMenuItems(player: player)
        } label: {
            HStack(spacing: 4) {
                Image(systemName: isSet ? "moon.zzz.fill" : "moon.zzz")
                    .font(.system(size: 19))
                switch player.sleepTimer {
                case .at(let date):
                    Text(timerInterval: Date.now...max(date, Date.now), countsDown: true, showsHours: false)
                        .font(BoomFont.time)
                        .frame(minWidth: 34)
                case .endOfSong:
                    Text("End").font(BoomFont.time)
                case nil:
                    EmptyView()
                }
            }
            .foregroundStyle(isSet ? BoomColor.accent : BoomColor.textSecondary)
            .frame(minWidth: BoomMetrics.minimumTarget, minHeight: BoomMetrics.minimumTarget)
            .contentShape(Rectangle())
        }
        .accessibilityLabel(isSet ? "Sleep timer on" : "Sleep timer")
    }
}

// MARK: Tabs at the foot

private struct PanelTabsBar: View {
    @Environment(OverlayModel.self) private var model

    var body: some View {
        HStack(spacing: 0) {
            ForEach(NowPlayingPanel.allCases, id: \.self) { tab in
                Button {
                    model.setPanel(tab)
                } label: {
                    Text(tab.title)
                        .font(.system(.footnote, weight: .bold))
                        .kerning(0.6)
                        .foregroundStyle(BoomColor.textSecondary)
                        .frame(maxWidth: .infinity, minHeight: 48)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }
        }
        .padding(.horizontal, BoomMetrics.margin)
        .highPriorityGesture(
            DragGesture(minimumDistance: 16).onEnded { value in
                if value.translation.height < -24 { model.setPanel(.upNext) }
            })
    }
}
