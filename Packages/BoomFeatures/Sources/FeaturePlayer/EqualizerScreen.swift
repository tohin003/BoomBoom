import BoomDesign
import BoomPlayer
import SwiftUI

/// The equaliser and amplifier. The app shows it as a sheet. Every change applies to the sound at once.
///
/// The header (Done, Reset, the master switch, "Original") stays put; the graph, faders and sections scroll under it.
/// Only the meter views read the store's live levels, so the rest redraws only when a setting changes.
public struct EqualizerScreen: View {
    @Environment(AppServices.self) private var services
    @Environment(\.dismiss) private var dismiss
    /// The band a finger is on, if any: lights its point on the graph and stops the sheet from being pulled down.
    @State private var activeBand: Int?
    @State private var isConfirmingReset = false

    public init() {}

    public var body: some View {
        let equalizer = services.equalizer
        let settings = equalizer.settings
        VStack(spacing: 0) {
            SoundHeader(
                isEnabled: settings.isEnabled, isComparing: equalizer.isComparing, equalizer: equalizer,
                onDone: { dismiss() },
                onReset: {
                    if Self.hasChanges(settings) {
                        isConfirmingReset = true
                    } else {
                        equalizer.reset()
                    }
                })
            ScrollView {
                VStack(alignment: .leading, spacing: 28) {
                    VStack(spacing: 14) {
                        graph(equalizer, settings: settings)
                        SoundFaders(gains: settings.gains, isEnabled: settings.isEnabled, equalizer: equalizer, activeBand: $activeBand)
                    }
                    .padding(.top, 6)
                    SoundPresets(
                        presets: equalizer.presets, favoriteIDs: equalizer.favoritePresetIDs,
                        selectedID: equalizer.selectedPresetID, equalizer: equalizer)
                    SoundToneSection(bass: settings.bass, treble: settings.treble, isEnabled: settings.isEnabled, equalizer: equalizer)
                    SoundAmplifierSection(
                        boost: settings.boost, preamp: settings.preamp, headroom: settings.headroom,
                        isEnabled: settings.isEnabled, isLimiterOn: settings.isLimiterEnabled,
                        isAutoHeadroomOn: settings.isAutoHeadroomEnabled,
                        isNormalising: settings.isLoudnessNormalizationEnabled, equalizer: equalizer)
                    SoundEnhanceSection(deepBass: settings.deepBass, air: settings.air, isEnabled: settings.isEnabled, equalizer: equalizer)
                    SoundStereoSection(
                        width: settings.width, balance: settings.balance, crossfeed: settings.crossfeed,
                        isSpatial: settings.isSpatialEnabled, isHeadTracking: settings.isHeadTrackingEnabled,
                        isHeadTrackingAvailable: equalizer.isHeadTrackingAvailable,
                        isEnabled: settings.isEnabled, equalizer: equalizer)
                    SoundOutputSection(meter: equalizer.meter)
                }
                .padding(.horizontal, BoomMetrics.margin)
                .padding(.bottom, 40)
            }
            .scrollIndicators(.hidden)
            .accessibilityIdentifier("sound.scroll")
        }
        .background(BoomColor.background.ignoresSafeArea())
        .environment(\.colorScheme, .dark)
        .interactiveDismissDisabled(activeBand != nil)
        .confirmationDialog("Reset the sound?", isPresented: $isConfirmingReset, titleVisibility: .visible) {
            Button("Reset everything", role: .destructive) {
                withAnimation(.snappy(duration: 0.35)) { equalizer.reset() }
            }
            .accessibilityIdentifier("sound.reset.confirm")
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("Bands, tone, amplifier, enhance, stereo and Spatial go back to their defaults. The master switch and loudness normalising stay as they are.")
        }
        .onAppear { equalizer.startMetering() }
        .onDisappear {
            equalizer.setComparing(false)
            equalizer.stopMetering()
        }
    }

    private func graph(_ equalizer: EqualizerStore, settings: SoundSettings) -> some View {
        // Flat while off: the sound then passes untouched, whatever the faders say.
        let isLive = settings.isEnabled
        let curve = isLive ? settings.response(at: GraphScale.frequencies) : Self.flatCurve
        let points = isLive ? settings.response(at: EqualizerStore.bands.map(\.frequency)) : Self.flatPoints
        return SoundGraph(
            curve: curve, points: points, isEnabled: isLive, isComparing: equalizer.isComparing,
            meter: equalizer.meter, activeBand: activeBand)
    }

    private static let flatCurve = [Double](repeating: 0, count: GraphScale.frequencies.count)
    private static let flatPoints = [Double](repeating: 0, count: EqualizerStore.bands.count)

    /// True when Reset would undo something worth asking about.
    static func hasChanges(_ settings: SoundSettings) -> Bool {
        var plain = SoundSettings()
        plain.isEnabled = settings.isEnabled
        plain.isLoudnessNormalizationEnabled = settings.isLoudnessNormalizationEnabled
        return settings != plain
    }
}

/// Done, the title and Reset; under them the master switch and the press-and-hold "Original" button.
private struct SoundHeader: View {
    let isEnabled: Bool
    let isComparing: Bool
    let equalizer: EqualizerStore
    let onDone: () -> Void
    let onReset: () -> Void

    var body: some View {
        VStack(spacing: 10) {
            ZStack {
                Text("Sound")
                    .font(.system(.headline, weight: .bold))
                    .foregroundStyle(BoomColor.textPrimary)
                    .accessibilityAddTraits(.isHeader)
                HStack {
                    Button("Done", action: onDone)
                        .font(.system(.body, weight: .semibold))
                        .foregroundStyle(BoomColor.accent)
                        .frame(minWidth: BoomMetrics.minimumTarget, minHeight: BoomMetrics.minimumTarget)
                        .accessibilityIdentifier("sound.done")
                    Spacer()
                    Button(action: onReset) {
                        Label("Reset", systemImage: "arrow.counterclockwise")
                            .labelStyle(.titleOnly)
                    }
                    .font(.system(.body))
                    .foregroundStyle(BoomColor.textPrimary)
                    .frame(minWidth: BoomMetrics.minimumTarget, minHeight: BoomMetrics.minimumTarget)
                    .accessibilityHint("Sets every band, the tone, the amplifier and stereo back to flat")
                    .accessibilityIdentifier("sound.reset")
                }
            }
            HStack(spacing: 10) {
                MasterSwitch(isOn: isEnabled, equalizer: equalizer)
                OriginalButton(isComparing: isComparing, isEnabled: isEnabled, equalizer: equalizer)
            }
            .fixedSize(horizontal: false, vertical: true)
        }
        .padding(.horizontal, BoomMetrics.margin)
        .padding(.top, 10)
        .padding(.bottom, 10)
        .background(BoomColor.background)
        .overlay(alignment: .bottom) { Rectangle().fill(BoomColor.separator).frame(height: 0.5) }
    }
}

private struct MasterSwitch: View {
    let isOn: Bool
    let equalizer: EqualizerStore

    var body: some View {
        Toggle(isOn: Binding(get: { isOn }, set: { equalizer.setEnabled($0) })) {
            HStack(spacing: 10) {
                Image(systemName: "power")
                    .font(.system(size: 15, weight: .heavy))
                    .foregroundStyle(isOn ? BoomColor.onAccent : BoomColor.textSecondary)
                    .frame(width: 30, height: 30)
                    .background(isOn ? BoomColor.accent : BoomColor.surfaceRaised, in: Circle())
                    .shadow(color: isOn ? BoomColor.accent.opacity(0.55) : .clear, radius: 6)
                VStack(alignment: .leading, spacing: 0) {
                    Text("Equaliser")
                        .font(BoomFont.button)
                        .foregroundStyle(BoomColor.textPrimary)
                    Text(isOn ? "On" : "Off: untouched")
                        .font(BoomFont.caption.weight(.semibold))
                        .foregroundStyle(isOn ? BoomColor.accent : BoomColor.textTertiary)
                }
                .lineLimit(1)
                .minimumScaleFactor(0.8)
            }
        }
        .toggleStyle(SoundToggleStyle())
        .padding(.leading, 8)
        .padding(.trailing, 12)
        .padding(.vertical, 7)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(BoomColor.surface, in: .rect(cornerRadius: 14))
        .overlay(RoundedRectangle(cornerRadius: 14).strokeBorder(isOn ? BoomColor.accent.opacity(0.4) : Color.white.opacity(0.06)))
        .sensoryFeedback(.impact(weight: .medium), trigger: isOn)
        .accessibilityLabel("Equaliser")
        .accessibilityIdentifier("sound.master")
        .animation(.snappy(duration: 0.25), value: isOn)
    }
}

/// Press and hold to hear the song as it was made; let go to hear the settings again.
private struct OriginalButton: View {
    let isComparing: Bool
    let isEnabled: Bool
    let equalizer: EqualizerStore

    var body: some View {
        VStack(spacing: 1) {
            HStack(spacing: 5) {
                Image(systemName: isComparing ? "ear.fill" : "ear")
                    .font(.system(size: 13, weight: .bold))
                Text("Original")
                    .font(BoomFont.button)
            }
            Text(isComparing ? "Hearing original" : "Hold to compare")
                .font(.caption2.weight(.semibold))
                .opacity(0.8)
                .lineLimit(2)
        }
        .lineLimit(1)
        .minimumScaleFactor(0.75)
        .multilineTextAlignment(.center)
        .foregroundStyle(isComparing ? BoomColor.onAccent : BoomColor.textPrimary)
        .padding(.horizontal, 12)
        .frame(width: 132)
        .frame(maxHeight: .infinity)
        .background(isComparing ? BoomColor.accent : BoomColor.surface, in: .rect(cornerRadius: 14))
        .overlay(RoundedRectangle(cornerRadius: 14).strokeBorder(Color.white.opacity(isComparing ? 0 : 0.12)))
        .scaleEffect(isComparing ? 0.96 : 1)
        .opacity(isEnabled ? 1 : 0.5)
        .animation(.snappy(duration: 0.15), value: isComparing)
        .overlay { TouchSurface(mode: .immediate) { handle($0) } }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Original")
        .accessibilityValue(isComparing ? "Hearing the original" : "Off")
        .accessibilityHint("Hold to hear the song as it was made, without these settings")
        .accessibilityAddTraits(.isButton)
        .accessibilityAction { equalizer.setComparing(!equalizer.isComparing) }
        .accessibilityIdentifier("sound.original")
    }

    private func handle(_ event: FingerEvent) {
        switch event {
        case .began:
            SoundHaptics.tick()
            equalizer.setComparing(true)
        case .ended, .cancelled:
            equalizer.setComparing(false)
        case .moved, .tapped:
            break
        }
    }
}
