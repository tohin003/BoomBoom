import BoomModels
import Foundation

// The states a screen's requests go through, kept free of SwiftUI so the self-check can drive the page models.

/// Where a request for a screen's content stands.
enum LoadState<Value> {
    case loading
    case failed(String)
    case loaded(Value)

    var value: Value? {
        if case .loaded(let value) = self { return value }
        return nil
    }

    var isLoaded: Bool { value != nil }
}

extension LoadState: Equatable where Value: Equatable {}

/// Words for the listener when a request fails. The error's own text is for the log only.
func userMessage(for error: Error) -> String {
    switch error as? MusicAPIError {
    case .network: "Couldn't connect. Check your connection and try again."
    case .signedOut: "Sign in to see this."
    case .unauthorized: "Your sign-in has run out. Sign in again to continue."
    case .unreadable: "Couldn't read this page right now."
    case nil: "Something went wrong. Try again."
    }
}

/// Drops shelves whose ids are already on the page, so a continuation cannot repeat an identity.
func appending(_ new: [Shelf], to old: [Shelf]) -> [Shelf] {
    var seen = Set(old.map(\.id))
    return old + new.filter { seen.insert($0.id).inserted }
}

/// The next part of a list that grows by continuation as the listener scrolls.
enum MoreState: Equatable, Sendable {
    /// The list is complete.
    case end
    /// More exists and has not been asked for.
    case ready(String)
    /// The part for this token is on its way.
    case loading(String)
    /// Asking with this token failed; it can be asked again.
    case failed(String)

    init(token: String?) {
        self = token.map { .ready($0) } ?? .end
    }

    var token: String? {
        switch self {
        case .end: nil
        case .ready(let token), .loading(let token), .failed(let token): token
        }
    }

    /// The token to ask with now: none while a part is on its way, or at the end.
    var askable: String? {
        switch self {
        case .ready(let token), .failed(let token): token
        case .end, .loading: nil
        }
    }

    var isLoading: Bool {
        if case .loading = self { return true }
        return false
    }
}

/// A list on screen and where its next part stands.
struct Paged<Content> {
    var content: Content
    var more: MoreState
}

extension Paged: Equatable where Content: Equatable {}
extension Paged: Sendable where Content: Sendable {}

/// One kind of request a screen makes: its content, or the next part of a list.
///
/// Starting a request cancels the one out, so only the newest is ever answered. The answer arrives on the main actor,
/// and never after its request was replaced or cancelled. Whatever is out is cancelled when the slot's owner goes away
/// (a page popped), so `answer` closures should hold their owner weakly.
@MainActor
final class RequestSlot {
    private var task: Task<Void, Never>?

    /// A request is on its way.
    var isBusy: Bool { task != nil }

    @discardableResult
    func start<Value: Sendable>(
        _ fetch: @escaping @Sendable () async throws -> Value,
        answer: @escaping @MainActor (Result<Value, any Error>) -> Void
    ) -> Task<Void, Never> {
        task?.cancel()
        let task = Task { [weak self] in
            let result: Result<Value, any Error>
            do {
                result = .success(try await fetch())
            } catch {
                result = .failure(error)
            }
            guard !Task.isCancelled, let self else { return }
            self.task = nil
            answer(result)
        }
        self.task = task
        return task
    }

    func cancel() {
        task?.cancel()
        task = nil
    }

    isolated deinit {
        task?.cancel()
    }
}

/// Something the listener switches on or off (saved to the library, subscribed), shown switched at once.
///
/// One request is out at a time. Taps made while it is out are kept as the newest wish and sent after it, so the
/// service sees the changes in order and the last tap wins. A refused request puts back what the service last
/// confirmed and drops the wishes made after it.
struct OptimisticToggle: Equatable, Sendable {
    enum Phase: Equatable, Sendable {
        case settled
        /// A request to switch to `value` is out; `then` is a different value wished for since.
        case sending(Bool, then: Bool?)
    }

    /// What the service last said.
    private(set) var confirmed: Bool
    private(set) var phase: Phase = .settled

    init(_ confirmed: Bool) {
        self.confirmed = confirmed
    }

    /// What to show.
    var isOn: Bool {
        switch phase {
        case .settled: confirmed
        case .sending(let value, let then): then ?? value
        }
    }

    var isSending: Bool { phase != .settled }

    /// A tap. Returns the value to send now, or nil when a request is already out (the wish is sent after it).
    mutating func flip() -> Bool? {
        switch phase {
        case .settled:
            phase = .sending(!confirmed, then: nil)
            return !confirmed
        case .sending(let value, let then):
            let wish = !(then ?? value)
            phase = .sending(value, then: wish == value ? nil : wish)
            return nil
        }
    }

    /// The answer to the request out. Returns the next value to send when the listener changed their mind meanwhile.
    mutating func answered(succeeded: Bool) -> Bool? {
        guard case .sending(let value, let then) = phase else { return nil }
        guard succeeded else {
            phase = .settled
            return nil
        }
        confirmed = value
        if let then, then != value {
            phase = .sending(then, then: nil)
            return then
        }
        phase = .settled
        return nil
    }

    /// A freshly loaded page says what the service holds. While a request is out, its answer still decides.
    mutating func reload(_ value: Bool) {
        confirmed = value
    }
}
