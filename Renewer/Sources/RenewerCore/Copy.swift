import Foundation

/// Every word the menu-bar item shows, in one place, so the copy lint sees them all:
/// `CopyTests` checks them all. Plain words: say what happens and what to do.
public enum Copy {
    // MARK: Menu items

    public static let renewNow = "Renew Now"
    public static let renewAutomatically = "Renew Automatically"
    public static let quit = "Quit"

    // MARK: The bar

    /// The small glyph before the days left, so this item can't be mistaken for another app's renewer.
    public static let barSymbol = "music.note"
    public static let barRenewing = "Renewing…"
    public static let barNoDate = "No date"
    public static let barExpired = "Expired"

    // MARK: The end date

    public static let noEndDate = "No end date found"
    public static let fromXcode = "Date from Xcode's newest profile"
    public static func expires(_ when: String) -> String { "Expires \(when)" }
    public static func expired(_ when: String) -> String { "Expired \(when)" }

    // MARK: What happens next

    public static let planRenewing = "Renewing now. This takes a few minutes."
    public static let planNoDate = "Install BoomBoom once with scripts/renew.sh --from-tree"
    public static let planNoBuild = "To renew from here, install once with scripts/renew.sh --from-tree"
    public static let planOff = "Renews only when you choose Renew Now"
    public static let planAfterExpiry = "Renews right after it expires"
    public static let planDue = "Renews at the next check"
    public static let planWaitPhone = "Renews when your iPhone is reachable"
    public static let planSeveral = "Renews when only your iPhone is connected"
    public static func planIn(_ span: String) -> String { "Renews in \(span)" }
    public static func planRetry(_ time: String) -> String { "Tries again at \(time)" }

    // MARK: The last result

    public static let notRenewedYet = "Not renewed yet"
    public static let seeLog = "The log has the details."
    public static func renewed(_ when: String) -> String { "Renewed \(when)" }
    public static func renewedUnmoved(_ when: String) -> String { "Renewed \(when); the end date didn't move" }
    public static func couldNotRenew(_ when: String) -> String { "Couldn't renew \(when)" }
    public static func installed(_ when: String) -> String { "Installed \(when)" }

    // MARK: Time words

    public static func today(_ time: String) -> String { "today at \(time)" }
    public static func tomorrow(_ time: String) -> String { "tomorrow at \(time)" }
    public static func yesterday(_ time: String) -> String { "yesterday at \(time)" }
    public static func onDay(_ day: String, _ time: String) -> String { "\(day) at \(time)" }
    public static func days(_ count: Int) -> String { count == 1 ? "1 day" : "\(count) days" }
    public static func hours(_ count: Int) -> String { count == 1 ? "1 hour" : "\(count) hours" }
    public static func minutes(_ count: Int) -> String { count == 1 ? "1 minute" : "\(count) minutes" }

    // MARK: Why it didn't renew

    public static let unreachable = "Your iPhone wasn't reachable. Connect it, then choose Renew Now."
    public static let stillExpired = "It was installed, but its end date is still in the past. " + seeLog

    public static func reason(_ outcome: RenewOutcome) -> String {
        switch outcome {
        case .renewed: return "It was renewed."
        case .busy: return "Another renewal was already running."
        case .noIPhone: return unreachable
        case .noXcode: return "Xcode isn't set up. Open Xcode once and sign in with your Apple ID."
        case .noProject:
            return "The saved copy of BoomBoom has no Xcode project. Install once with scripts/renew.sh --from-tree."
        case .snapshotFailed: return "A copy of the sources couldn't be made. " + seeLog
        case .signingFailed:
            return "Xcode couldn't sign the app. Check your Apple ID in Xcode › Settings › Accounts, then choose Renew Now."
        case .buildFailed: return "The build failed. " + seeLog
        case .installFailed(let locked):
            return locked
                ? "Your iPhone was locked. Unlock it, then choose Renew Now."
                : "It was built but not installed. Unlock your iPhone and keep it nearby, then choose Renew Now."
        case .noAppOrDate: return "It was built, but the app or its end date wasn't found. " + seeLog
        case .usage: return "Renewing couldn't start. " + seeLog
        case .timedOut: return "Renewing took too long, so it was stopped. " + seeLog
        case .couldNotStart: return "The renew script couldn't be started. " + seeLog
        case .failed(let code): return "Renewing stopped with code \(code). " + seeLog
        }
    }

    public static func reason(_ refusal: Refusal) -> String {
        switch refusal {
        case .noIPhone: return unreachable
        case .severalPhones:
            return "More than one iPhone is connected. Leave only yours connected, then choose Renew Now."
        case .noBuild: return "No installed copy is recorded yet. Install once with scripts/renew.sh --from-tree."
        }
    }

    // MARK: Notifications (each at most once per end date)

    public static func unreachableNotice(when: String, expired: Bool) -> NoticeWords {
        NoticeWords(
            title: expired ? "BoomBoom has expired" : "BoomBoom expires \(when)",
            body: "Your iPhone wasn't reachable, so it wasn't renewed. Unlock it on the same Wi-Fi as this Mac, "
                + "or connect it with a cable. It renews by itself once your iPhone is reachable.")
    }

    public static func failedNotice(reason: String, when: String, expired: Bool) -> NoticeWords {
        NoticeWords(
            title: "BoomBoom wasn't renewed",
            body: reason + (expired ? " It has expired." : " It expires \(when)."))
    }

    public static func noBuildNotice(when: String, expired: Bool) -> NoticeWords {
        NoticeWords(
            title: expired ? "BoomBoom has expired" : "BoomBoom expires \(when)",
            body: "It can't be renewed from the menu bar until it's installed once with scripts/renew.sh --from-tree.")
    }
}

/// Why Renew Now didn't run renew.sh at all.
public enum Refusal: Equatable, Sendable {
    case noIPhone
    case severalPhones
    case noBuild
}

public struct NoticeWords: Equatable, Sendable {
    public var title: String
    public var body: String

    public init(title: String, body: String) {
        self.title = title
        self.body = body
    }
}
