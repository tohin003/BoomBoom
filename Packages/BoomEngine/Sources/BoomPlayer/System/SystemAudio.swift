import AVFoundation
import Foundation

/// The app's standing with the system as a music player: it may play in the background and with the ring switch
/// off, it steps aside for calls and alarms, and it stops when headphones are pulled out.
@MainActor
final class SystemAudio {
    /// A call, alarm or another app took the audio (`began`), or gave it back (`shouldResume` says whether to
    /// carry on playing).
    var onInterruption: ((_ began: Bool, _ shouldResume: Bool) -> Void)?
    /// The headphones or speaker that were playing went away.
    var onOutputLost: (() -> Void)?

    private var observers: [NSObjectProtocol] = []
    private var isConfigured = false

    init() {
        #if os(iOS)
        let center = NotificationCenter.default
        let session = AVAudioSession.sharedInstance()
        observers.append(center.addObserver(
            forName: AVAudioSession.interruptionNotification, object: session, queue: .main
        ) { [weak self] note in
            let type = (note.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt)
                .flatMap(AVAudioSession.InterruptionType.init(rawValue:))
            let options = (note.userInfo?[AVAudioSessionInterruptionOptionKey] as? UInt)
                .map(AVAudioSession.InterruptionOptions.init(rawValue:)) ?? []
            // 0: another player took the audio; 1: the app was suspended; 2: the microphone was muted; 4: the route went.
            let reason = note.userInfo?[AVAudioSessionInterruptionReasonKey] as? UInt
            MainActor.assumeIsolated {
                guard let self, let type else { return }
                switch type {
                case .began:
                    BoomTrace.note("audio interrupted, reason \(reason.map { String($0) } ?? "-")")
                    self.onInterruption?(true, false)
                case .ended:
                    BoomTrace.note("audio interruption over, resume=\(options.contains(.shouldResume))")
                    self.onInterruption?(false, options.contains(.shouldResume))
                @unknown default: break
                }
            }
        })
        observers.append(center.addObserver(
            forName: AVAudioSession.routeChangeNotification, object: session, queue: .main
        ) { [weak self] note in
            let reason = (note.userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt)
                .flatMap(AVAudioSession.RouteChangeReason.init(rawValue:))
            MainActor.assumeIsolated {
                BoomTrace.note("audio route changed, reason \(reason?.rawValue ?? 0)")
                if reason == .oldDeviceUnavailable { self?.onOutputLost?() }
            }
        })
        observers.append(center.addObserver(
            forName: AVAudioSession.mediaServicesWereResetNotification, object: session, queue: .main
        ) { [weak self] _ in
            MainActor.assumeIsolated {
                self?.isConfigured = false
                self?.activate()
            }
        })
        #endif
    }

    /// Where the sound is going and at what rate: "AirPods Pro · 48 kHz". Nil where the system does not say.
    var outputDescription: String? {
        #if os(iOS)
        let session = AVAudioSession.sharedInstance()
        guard let port = session.currentRoute.outputs.first else { return nil }
        return "\(port.portName) · \(String(format: "%g kHz", session.sampleRate / 1000))"
        #else
        return nil
        #endif
    }

    /// The rate the hardware is running at, in hertz (0 where the system does not say).
    var hardwareSampleRate: Double {
        #if os(iOS)
        return AVAudioSession.sharedInstance().sampleRate
        #else
        return 0
        #endif
    }

    /// Tells the system the app is about to play. Cheap to call again.
    func activate() {
        #if os(iOS)
        let session = AVAudioSession.sharedInstance()
        do {
            if !isConfigured {
                try session.setCategory(.playback, mode: .default, options: [])
                // The streams are 48 kHz. With the hardware at the same rate nothing is resampled on the way out;
                // a device that only runs at another rate (some Bluetooth ones) keeps its own, and the engine's
                // converter then does the work at its best quality (see `AudioOutput`).
                try? session.setPreferredSampleRate(48_000)
                isConfigured = true
            }
            try session.setActive(true)
        } catch {
            BoomTrace.note("audio session could not be activated: \(error.localizedDescription)")
            isConfigured = false
        }
        #endif
    }
}
