import Foundation
import Synchronization

#if canImport(CoreMotion) && os(iOS)
import CoreMotion
#endif

/// The turn of the listener's head, from headphones that report it (AirPods Pro, Max, 3 and later), for the spatial
/// stage: the two virtual loudspeakers stay where they are while the head turns.
///
/// "Where they are" follows the head slowly: someone who turns a corner, or settles into a chair facing another
/// way, has the loudspeakers in front again after a few seconds, as with any head-tracked sound.
final class HeadTracker: @unchecked Sendable {
    /// Called from the tracker's own queue with the head's turn to the right of the loudspeakers, in radians.
    private let onTurn: @Sendable (Float) -> Void
    /// Seconds for the loudspeakers to come round to where the head now points.
    private static let followSeconds = 8.0

    private struct Bearing {
        /// The direction the loudspeakers face from, as a yaw in radians (the headphones' frame).
        var centre: Double?
        var lastTime: TimeInterval = 0
    }
    private let bearing = Mutex(Bearing())

    #if canImport(CoreMotion) && os(iOS)
    private let manager = CMHeadphoneMotionManager()
    private let queue: OperationQueue = {
        let queue = OperationQueue()
        queue.maxConcurrentOperationCount = 1
        queue.qualityOfService = .userInteractive
        return queue
    }()
    #endif
    private let isRunning = Atomic(false)

    init(onTurn: @escaping @Sendable (Float) -> Void) {
        self.onTurn = onTurn
    }

    /// True when the headphones in use report head movement.
    var isAvailable: Bool {
        #if canImport(CoreMotion) && os(iOS)
        return manager.isDeviceMotionAvailable
        #else
        return false
        #endif
    }

    func start() {
        #if canImport(CoreMotion) && os(iOS)
        guard manager.isDeviceMotionAvailable, !isRunning.exchange(true, ordering: .relaxed) else { return }
        bearing.withLock { $0 = Bearing() }
        BoomTrace.note("head tracking on")
        manager.startDeviceMotionUpdates(to: queue) { [weak self] motion, _ in
            guard let self, let motion else { return }
            self.take(yaw: motion.attitude.yaw, at: motion.timestamp)
        }
        #endif
    }

    func stop() {
        #if canImport(CoreMotion) && os(iOS)
        guard isRunning.exchange(false, ordering: .relaxed) else { return }
        manager.stopDeviceMotionUpdates()
        BoomTrace.note("head tracking off")
        #endif
        onTurn(0)
    }

    /// One reading. `yaw` grows as the head turns to the left (seen from above, anticlockwise).
    func take(yaw: Double, at time: TimeInterval) {
        let turn: Double = bearing.withLock { state in
            guard let centre = state.centre else {
                state.centre = yaw
                state.lastTime = time
                return 0
            }
            // The shortest way round from the centre to where the head points now.
            var offset = (yaw - centre).truncatingRemainder(dividingBy: 2 * .pi)
            if offset > .pi { offset -= 2 * .pi }
            if offset <= -.pi { offset += 2 * .pi }
            // The centre drifts after the head.
            let elapsed = min(max(time - state.lastTime, 0), 0.5)
            let share = 1 - exp(-elapsed / Self.followSeconds)
            state.centre = centre + offset * share
            state.lastTime = time
            return offset * (1 - share)
        }
        // To the right is the other way round from yaw.
        onTurn(Float(-turn))
    }
}
