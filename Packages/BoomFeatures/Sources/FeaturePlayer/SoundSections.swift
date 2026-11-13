import BoomDesign
import BoomPlayer
import SwiftUI

// The sections under the faders. Each takes plain values (not the store's whole settings), so moving one control
// redraws its own section and SwiftUI skips the rest.

/// A section's small capitals title, with an optional note or control on the right.
struct SoundSectionTitle<Trailing: View>: View {
    let title: String
    @ViewBuilder var trailing: Trailing

    var body: some View {
        HStack(alignment: .firstTextBaseline) {
            Text(title.uppercased())
                .font(BoomFont.strapline.weight(.bold))
                .tracking(1.2)
                .foregroundStyle(BoomColor.textSecondary)
                .accessibilityAddTraits(.isHeader)
            Spacer(minLength: 8)
            trailing
        }
        .padding(.horizontal, 4)
    }
}

extension SoundSectionTitle where Trailing == EmptyView {
    init(title: String) {
        self.init(title: title) { EmptyView() }
    }
}

/// The rounded box each section's controls sit in.
struct SoundCard<Content: View>: View {
    @ViewBuilder var content: Content

    var body: some View {
        VStack(spacing: 0) { content }
            .padding(.horizontal, 14)
            .padding(.vertical, 4)
            .background(BoomColor.surface, in: .rect(cornerRadius: BoomMetrics.cardRadius + 6))
            .overlay(RoundedRectangle(cornerRadius: BoomMetrics.cardRadius + 6).strokeBorder(Color.white.opacity(0.05)))
    }
}

private struct CardDivider: View {
    var body: some View {
        Rectangle().fill(BoomColor.separator).frame(height: 0.5)
    }
}

// MARK: Presets

/// The preset chips: the starred ones first, then the rest in the store's order. A star button beside the title
/// stars the selected preset; the context menu stars any. The listener's own presets carry the whole sound and say so.
struct SoundPresets: View {
    let presets: [EqualizerPreset]
    let favoriteIDs: [String]
    let selectedID: String?
    let equalizer: EqualizerStore

    /// The naming alert, for a new preset or a rename. Changed only through `open(_:)` and `finish(saving:)`.
    private enum Naming: Equatable {
        case closed
        case new
        case rename(id: String)
    }

    @State private var naming = Naming.closed
    @State private var name = ""

    private var ordered: [EqualizerPreset] {
        let starred = Set(favoriteIDs)
        return presets.filter { starred.contains($0.id) } + presets.filter { !starred.contains($0.id) }
    }

    private var selected: EqualizerPreset? { presets.first { $0.id == selectedID } }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            // Title and buttons on one line while they fit; with large text the buttons move under the title.
            ViewThatFits(in: .horizontal) {
                SoundSectionTitle(title: "Presets") { presetButtons }
                VStack(alignment: .leading, spacing: 0) {
                    SoundSectionTitle(title: "Presets")
                    presetButtons.padding(.horizontal, 4)
                }
            }
            ScrollViewReader { proxy in
                ScrollView(.horizontal) {
                    HStack(spacing: 8) {
                        ForEach(ordered) { preset in
                            chip(preset).id(preset.id)
                        }
                    }
                    .padding(.horizontal, BoomMetrics.margin)
                }
                .scrollIndicators(.hidden)
                .padding(.horizontal, -BoomMetrics.margin)
                .onAppear { reveal(selectedID, with: proxy, animated: false) }
                .onChange(of: selectedID) { _, id in reveal(id, with: proxy, animated: true) }
                .onChange(of: favoriteIDs) { _, _ in reveal(selectedID, with: proxy, animated: true) }
            }
            if presets.contains(where: { !$0.isBuiltIn }) {
                Label {
                    Text("Your own presets also set the tone, amplifier, enhance and stereo.")
                } icon: {
                    Image(systemName: "slider.horizontal.3")
                }
                .font(BoomFont.caption)
                .foregroundStyle(BoomColor.textTertiary)
                .padding(.horizontal, 4)
            }
        }
        .alert(naming == .new ? "Save as preset" : "Rename preset", isPresented: isNamingShown) {
            TextField("Name", text: $name)
            Button("Cancel", role: .cancel) { finish(saving: false) }
            Button("Save") { finish(saving: true) }
        } message: {
            Text(naming == .new
                ? "Keeps the whole sound as it is now: the ten bands, tone, amplifier, enhance and stereo."
                : "")
        }
    }

    private var presetButtons: some View {
        HStack(spacing: 14) {
            if let selected { starButton(selected) }
            Button {
                open(.new)
            } label: {
                Label("Save preset", systemImage: "plus.circle.fill")
                    .font(BoomFont.button)
                    .foregroundStyle(BoomColor.accent)
                    .lineLimit(1)
                    .fixedSize()
                    .frame(minHeight: BoomMetrics.minimumTarget)
                    .contentShape(Rectangle())
            }
            .buttonStyle(PressScaleStyle(scale: 0.95))
            .accessibilityLabel("Save as preset")
            .accessibilityIdentifier("sound.preset.save")
        }
    }

    private func starButton(_ preset: EqualizerPreset) -> some View {
        let isStarred = favoriteIDs.contains(preset.id)
        return Button {
            SoundHaptics.tick()
            withAnimation(.snappy) { equalizer.setFavorite(!isStarred, presetID: preset.id) }
        } label: {
            Label(isStarred ? "Starred" : "Star", systemImage: isStarred ? "star.fill" : "star")
                .font(BoomFont.button)
                .lineLimit(1)
                .fixedSize()
                .foregroundStyle(isStarred ? BoomColor.accent : BoomColor.textPrimary)
                .frame(minHeight: BoomMetrics.minimumTarget)
                .contentShape(Rectangle())
        }
        .buttonStyle(PressScaleStyle(scale: 0.95))
        .accessibilityLabel(isStarred ? "Remove \(preset.name) from favourites" : "Add \(preset.name) to favourites")
        .accessibilityIdentifier("sound.preset.star")
    }

    private var isNamingShown: Binding<Bool> {
        Binding(get: { naming != .closed }, set: { shown in if !shown { naming = .closed } })
    }

    private func open(_ next: Naming) {
        switch next {
        case .closed, .new: name = ""
        case .rename(let id): name = presets.first { $0.id == id }?.name ?? ""
        }
        naming = next
    }

    private func finish(saving: Bool) {
        if saving {
            switch naming {
            case .new: withAnimation(.snappy) { _ = equalizer.saveCurrentAsPreset(named: name) }
            case .rename(let id): equalizer.renamePreset(id: id, to: name)
            case .closed: break
            }
        }
        naming = .closed
    }

    private func reveal(_ id: String?, with proxy: ScrollViewProxy, animated: Bool) {
        guard let id else { return }
        if animated {
            withAnimation(.snappy) { proxy.scrollTo(id, anchor: .center) }
        } else {
            proxy.scrollTo(id, anchor: .center)
        }
    }

    private func chip(_ preset: EqualizerPreset) -> some View {
        let isSelected = selectedID == preset.id
        let isStarred = favoriteIDs.contains(preset.id)
        return Button {
            SoundHaptics.tick()
            withAnimation(.snappy(duration: 0.35)) { equalizer.select(preset) }
        } label: {
            HStack(spacing: 5) {
                if isStarred {
                    Image(systemName: "star.fill")
                        .font(.system(size: 10, weight: .bold))
                        .foregroundStyle(isSelected ? BoomColor.onAccent : BoomColor.accent)
                }
                Text(preset.name)
                    .font(BoomFont.button)
                    .lineLimit(1)
                if preset.sound != nil {
                    Image(systemName: "slider.horizontal.3")
                        .font(.system(size: 10, weight: .bold))
                        .opacity(0.75)
                }
            }
            .foregroundStyle(isSelected ? BoomColor.onAccent : BoomColor.textPrimary)
            .padding(.horizontal, 14)
            .frame(height: 34)
            .background(isSelected ? BoomColor.accent : BoomColor.chip, in: Capsule())
            .overlay(Capsule().strokeBorder(isStarred && !isSelected ? BoomColor.accent.opacity(0.35) : .clear))
            .frame(minHeight: BoomMetrics.minimumTarget)
            .contentShape(Rectangle())
        }
        .buttonStyle(PressScaleStyle(scale: 0.95))
        .accessibilityLabel(preset.name)
        .accessibilityValue(isStarred ? "Favourite" : "")
        .accessibilityHint(preset.sound != nil ? "Sets the bands, tone, amplifier, enhance and stereo" : "Sets the ten bands")
        .accessibilityAddTraits(isSelected ? .isSelected : [])
        .accessibilityIdentifier("sound.preset.\(preset.id)")
        .contextMenu {
            Button(
                isStarred ? "Remove from favourites" : "Add to favourites",
                systemImage: isStarred ? "star.slash" : "star"
            ) {
                withAnimation(.snappy) { equalizer.setFavorite(!isStarred, presetID: preset.id) }
            }
            if !preset.isBuiltIn {
                Button("Rename", systemImage: "pencil") { open(.rename(id: preset.id)) }
                Button("Update with the current sound", systemImage: "square.and.arrow.down") {
                    equalizer.overwritePreset(id: preset.id)
                }
                Button("Delete", systemImage: "trash", role: .destructive) {
                    withAnimation(.snappy) { equalizer.deletePreset(id: preset.id) }
                }
            }
        }
    }
}

// MARK: Tone

struct SoundToneSection: View {
    let bass: Float
    let treble: Float
    let isEnabled: Bool
    let equalizer: EqualizerStore

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            SoundSectionTitle(title: "Tone")
            SoundCard {
                SoundSlider(
                    control: .bass, value: bass, isEnabled: isEnabled, equalizer: equalizer,
                    detail: "Everything below about 100 Hz.")
                CardDivider()
                SoundSlider(
                    control: .treble, value: treble, isEnabled: isEnabled, equalizer: equalizer,
                    detail: "Everything above about 10 kHz.")
            }
        }
    }
}

// MARK: Amplifier

struct SoundAmplifierSection: View {
    let boost: Float
    let preamp: Float
    let headroom: Float
    let isEnabled: Bool
    let isLimiterOn: Bool
    let isAutoHeadroomOn: Bool
    let isNormalising: Bool
    let equalizer: EqualizerStore

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            SoundSectionTitle(title: "Amplifier")
            SoundCard {
                HStack(alignment: .top, spacing: 12) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Volume boost")
                            .font(BoomFont.itemTitle)
                            .foregroundStyle(BoomColor.textSecondary)
                            .padding(.top, 12)
                        HStack(alignment: .firstTextBaseline, spacing: 8) {
                            Text(SoundFaders.readout(boost))
                                .font(.system(.largeTitle, design: .rounded, weight: .bold).monospacedDigit())
                                .foregroundStyle(boost > 0 && isEnabled ? BoomColor.accent : BoomColor.textPrimary)
                                .contentTransition(.numericText(value: Double(boost)))
                            Text("dB")
                                .font(.system(.title3, design: .rounded, weight: .semibold))
                                .foregroundStyle(BoomColor.textSecondary)
                            Spacer(minLength: 4)
                            Text(SoundControl.percent(boost: boost))
                                .font(.system(.title3, design: .rounded, weight: .semibold).monospacedDigit())
                                .foregroundStyle(BoomColor.textSecondary)
                                .accessibilityHidden(true)
                        }
                        .lineLimit(1)
                        .minimumScaleFactor(0.6)
                        .accessibilityElement(children: .combine)
                        .accessibilityIdentifier("sound.boost.readout")
                        SoundSlider(control: .boost, value: boost, isEnabled: isEnabled, equalizer: equalizer, showsHeader: false)
                    }
                    LimiterMeter(meter: equalizer.meter, isLimiterOn: isLimiterOn, isEnabled: isEnabled)
                        .padding(.vertical, 12)
                }
                .fixedSize(horizontal: false, vertical: true)
                Text("Louder than full volume. Clip protection holds every peak under −0.3 dBFS, so quiet recordings gain nearly the whole boost and ones already mastered loud gain less.")
                    .font(BoomFont.caption)
                    .foregroundStyle(BoomColor.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.bottom, 12)
                CardDivider()
                SoundSlider(
                    control: .preamp, value: preamp, isEnabled: isEnabled, equalizer: equalizer,
                    detail: "The level going into the equaliser. Lower it if a big boost sounds strained.")
                CardDivider()
                SoundSwitch(
                    title: "Clip protection", detail: "A limiter that keeps peaks below full scale, so nothing distorts.",
                    identifier: "sound.limiter", isOn: isLimiterOn) { equalizer.setLimiterEnabled($0) }
                CardDivider()
                SoundSwitch(
                    title: "Auto headroom",
                    detail: "Lowers the level by the curve's highest boost, so the bands alone never push into the limiter.",
                    identifier: "sound.autoHeadroom", isOn: isAutoHeadroomOn,
                    badge: isAutoHeadroomOn && headroom > 0 ? "−" + String(format: "%.1f dB", headroom) : nil
                ) { equalizer.setAutoHeadroomEnabled($0) }
                CardDivider()
                SoundSwitch(
                    title: "Normalise loudness",
                    detail: "Plays every song at a similar level. Works even with the equaliser off.",
                    identifier: "sound.normalise", isOn: isNormalising) { equalizer.setLoudnessNormalizationEnabled($0) }
            }
            ClipWarning(meter: equalizer.meter, isLimiterOn: isLimiterOn || !isEnabled)
        }
    }
}

/// A switch row with a line saying what it does. `onChange` is called only from the switch itself.
struct SoundSwitch: View {
    let title: String
    let detail: String
    let identifier: String
    let isOn: Bool
    var badge: String?
    /// False: shown greyed out and untouchable, with `detail` saying why.
    var isAvailable = true
    let onChange: (Bool) -> Void

    var body: some View {
        Toggle(isOn: Binding(get: { isOn }, set: { onChange($0) })) {
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 6) {
                    Text(title)
                        .font(BoomFont.itemTitle)
                        .foregroundStyle(BoomColor.textPrimary)
                    if let badge {
                        Text(badge)
                            .font(BoomFont.time.weight(.bold))
                            .foregroundStyle(BoomColor.accent)
                            .padding(.horizontal, 5)
                            .padding(.vertical, 1)
                            .background(BoomColor.accent.opacity(0.14), in: .rect(cornerRadius: 4))
                            .fixedSize()
                    }
                }
                Text(detail)
                    .font(BoomFont.caption)
                    .foregroundStyle(BoomColor.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .toggleStyle(SoundToggleStyle())
        .disabled(!isAvailable)
        .opacity(isAvailable ? 1 : 0.55)
        .padding(.vertical, 12)
        .accessibilityIdentifier(identifier)
        .sensoryFeedback(.selection, trigger: isOn)
    }
}

extension SoundSwitch: @MainActor Equatable {
    static func == (lhs: SoundSwitch, rhs: SoundSwitch) -> Bool {
        lhs.title == rhs.title && lhs.detail == rhs.detail && lhs.isOn == rhs.isOn && lhs.badge == rhs.badge
            && lhs.isAvailable == rhs.isAvailable
    }
}

/// The sound screen's switch: the whole row is the target, drawn as a switch on the right. A plain button inside,
/// because the system switch dropped taps in this sheet (the first tap after touching anything else, in the finger
/// tests); SwiftUI keeps the Toggle's meaning for VoiceOver.
struct SoundToggleStyle: ToggleStyle {
    @Environment(\.isEnabled) private var isEnabled

    func makeBody(configuration: Configuration) -> some View {
        Button {
            configuration.isOn.toggle()
        } label: {
            HStack(spacing: 12) {
                configuration.label
                    .frame(maxWidth: .infinity, alignment: .leading)
                SoundSwitchKnob(isOn: configuration.isOn, isEnabled: isEnabled)
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

/// The drawn switch: yellow track and white knob when on.
struct SoundSwitchKnob: View {
    let isOn: Bool
    var isEnabled = true

    var body: some View {
        Capsule()
            .fill(isOn ? BoomColor.accent : Color.white.opacity(0.18))
            .frame(width: 51, height: 31)
            .overlay(alignment: isOn ? .trailing : .leading) {
                Circle()
                    .fill(isEnabled ? Color.white : Color(white: 0.75))
                    .shadow(color: .black.opacity(0.3), radius: 2, y: 1)
                    .padding(2)
            }
            .animation(.snappy(duration: 0.22), value: isOn)
            .accessibilityHidden(true)
    }
}

// MARK: Enhance

struct SoundEnhanceSection: View {
    let deepBass: Float
    let air: Float
    let isEnabled: Bool
    let equalizer: EqualizerStore

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            SoundSectionTitle(title: "Enhance")
            SoundCard {
                SoundSlider(
                    control: .deepBass, value: deepBass, isEnabled: isEnabled, equalizer: equalizer,
                    detail: "Adds harmonics of the lowest notes, so small speakers and earphones carry them.")
                CardDivider()
                SoundSlider(
                    control: .air, value: air, isEnabled: isEnabled, equalizer: equalizer,
                    detail: "Adds a little harmonic sparkle to the top octave.")
            }
        }
    }
}

// MARK: Stereo

struct SoundStereoSection: View {
    let width: Float
    let balance: Float
    let crossfeed: Float
    let isSpatial: Bool
    let isHeadTracking: Bool
    let isHeadTrackingAvailable: Bool
    let isEnabled: Bool
    let equalizer: EqualizerStore

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            SoundSectionTitle(title: "Stereo")
            SoundCard {
                SoundSlider(
                    control: .width, value: width, isEnabled: isEnabled, equalizer: equalizer,
                    detail: "Narrower toward mono, or wider than the recording.")
                CardDivider()
                SoundSlider(control: .balance, value: balance, isEnabled: isEnabled, equalizer: equalizer)
                CardDivider()
                SoundSwitch(
                    title: "Spatial",
                    detail: "For headphones: the two sides sound like a pair of loudspeakers in front of you, each reaching both ears as in a room. On the phone's own speaker it only changes the tone.",
                    identifier: "sound.spatial", isOn: isSpatial
                ) { isOn in
                    // Like the sliders: turning it on means wanting to hear it.
                    if isOn, !equalizer.settings.isEnabled { equalizer.setEnabled(true) }
                    withAnimation(.snappy(duration: 0.3)) { equalizer.setSpatialEnabled(isOn) }
                }
                if isSpatial {
                    SoundSwitch(
                        title: "Follow head movement",
                        detail: isHeadTrackingAvailable
                            ? "The loudspeakers stay where they are while you turn your head, and come round to face you again after a few seconds."
                            : "Needs AirPods that report head movement (AirPods Pro, AirPods Max, AirPods 3 or later).",
                        identifier: "sound.headTracking", isOn: isHeadTracking && isHeadTrackingAvailable,
                        isAvailable: isHeadTrackingAvailable
                    ) { equalizer.setHeadTrackingEnabled($0) }
                    .padding(.leading, 18)
                    .overlay(alignment: .leading) {
                        Rectangle().fill(BoomColor.accent.opacity(0.5)).frame(width: 2).padding(.vertical, 12)
                    }
                    .transition(.opacity.combined(with: .move(edge: .top)))
                }
                CardDivider()
                SoundSlider(
                    control: .crossfeed, value: crossfeed, isEnabled: isEnabled && !isSpatial, equalizer: equalizer,
                    detail: isSpatial
                        ? "Not needed while Spatial is on: Spatial already lets each side reach both ears."
                        : "For headphones: a little of each side reaches the other ear, later and softer, as in front of loudspeakers.")
                    .opacity(isSpatial ? 0.5 : 1)
            }
        }
    }
}

// MARK: Output

struct SoundOutputSection: View {
    let meter: LevelMeter

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            SoundSectionTitle(title: "Output") {
                Text("RMS · peak · dBFS")
                    .font(BoomFont.caption)
                    .foregroundStyle(BoomColor.textTertiary)
            }
            SoundOutputMeters(meter: meter)
                .padding(14)
                .background(BoomColor.surface, in: .rect(cornerRadius: BoomMetrics.cardRadius + 6))
        }
    }
}
