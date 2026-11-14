import Foundation
import WebKit

#if canImport(UIKit)
import UIKit
#endif

/// The hidden browser tab: one web view on music.youtube.com, with ads stripped, kept silent, and with BoomBoom's
/// script inside (`Resources/boom-tap.js`). It holds the listener's Google sign-in (the web view's own cookies),
/// and its player fetches the audio that the app then plays itself.
///
/// Up to three Google accounts are kept apart in browser profiles: each profile is a data store of its own, so its
/// cookies, storage and the page's memory of past songs never mix with another's. Only the active profile has a web
/// view; switching takes that one down and builds a new one on the other profile's store.
///
/// This app is installed from Xcode and never reviewed for the App Store, so a few WebKit switches that have no
/// public name are used where they help; each is looked up by name and skipped when missing.
@MainActor
public final class HiddenBrowser: NSObject, SessionBackend {
    public static let home = URL(string: "https://music.youtube.com/")!
    /// The tab presents itself as desktop Safari, so it gets the full web app and its media-source player.
    public static let userAgent =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) "
        + "Version/26.0 Safari/605.1.15"
    /// How many Google accounts the app keeps at once, each in its own profile.
    public static let maximumProfiles = 3
    /// The profile on WebKit's default data store: where the one sign-in lived before there were profiles, so it
    /// carries on without any moving of data. It always exists; signing it out only empties it.
    public static let defaultProfileID = "default"

    /// Where the tab stands. Every change goes through `move(to:)`.
    enum TabPhase: Equatable {
        /// The start page is loading: at launch, after a failure, after the sign-in changed, or in a new web view
        /// after an account switch.
        case loading
        /// A page is in. `hasPlayer` turns true once its player takes commands (the site makes the player when a
        /// page is opened onto a song, and keeps it from then on).
        case loaded(at: Date, hasPlayer: Bool)
        /// The capture broker is opening a song's own page in the tab. To the rest of the app the tab stays usable.
        case switchingPage
        case failed(String)
    }

    private(set) var phase: TabPhase = .loading

    /// The tab's state as the screens need it.
    public var state: BackendState {
        switch phase {
        case .loading: .starting
        case .loaded, .switchingPage: .ready
        case .failed(let message): .failed(message)
        }
    }

    /// Whether the active profile is signed in.
    public private(set) var isSignedIn = false
    public var onChange: (@MainActor () -> Void)?

    /// Called for everything the script reports.
    var onEvent: (@MainActor (TapEvent) -> Void)?
    /// Called on every change of `phase`, and when the web view is replaced (the capture broker listens).
    var onPhaseChange: (@MainActor () -> Void)?

    /// True once the page's player takes commands.
    var isPlayerReady: Bool {
        if case .loaded(_, true) = phase { return true }
        return false
    }

    /// When the page in the tab finished loading; nil while one is on its way.
    var pageLoadedAt: Date? {
        if case .loaded(let at, _) = phase { return at }
        return nil
    }

    /// The tab. It is replaced when the listener switches accounts, so it is read afresh each time, never kept.
    public private(set) var webView: WKWebView
    /// The active profile's data store, where the tab's cookies (the sign-in) live.
    public private(set) var dataStore: WKWebsiteDataStore
    /// Every profile, the default one first, then in the order they were added.
    public private(set) var profileIDs: [String]
    public private(set) var activeProfileID: String

    /// The active profile's session.
    private var snapshot: WebSessionSnapshot
    private let defaults: UserDefaults
    /// The profiles' data stores, made when first needed. The sign-in sheet and the tab use the same object.
    private var stores: [String: WKWebsiteDataStore] = [:]
    /// The ad rules, compiled once and given to every web view.
    private var adRules: WKContentRuleList?
    private var hasStarted = false
    /// While the tab is failed: the wait before it is loaded again by itself, and how many times that was tried.
    private var retry: Task<Void, Never>?
    private var retriesSinceLoaded = 0
    /// The pauses before a failed tab is loaded again; the last one repeats.
    private static let retryWaits: [Duration] = [.seconds(2), .seconds(5), .seconds(15), .seconds(30), .seconds(60)]
    private var holdTask: Task<Void, Never>?
    private var cookieRefresh: Task<Void, Never>?
    #if canImport(UIKit)
    /// The window the tab lives in, so a new web view can go where the old one was.
    private weak var window: UIWindow?
    #endif
    private static let snapshotKey = "web.sessionSnapshot"
    private static let profilesKey = "web.profiles"
    private static let activeProfileKey = "web.activeProfile"

    public convenience init(defaults: UserDefaults = .standard) {
        self.init(defaults: defaults, sweepsForgottenStores: true)
    }

    /// - Parameter sweepsForgottenStores: Whether to delete, after launch, data stores no profile owns. Tests that
    ///   make several browsers side by side turn it off: each would take the others' stores for forgotten ones.
    init(defaults: UserDefaults, sweepsForgottenStores: Bool) {
        self.defaults = defaults
        var ids = [Self.defaultProfileID]
        for id in defaults.stringArray(forKey: Self.profilesKey) ?? []
        where UUID(uuidString: id) != nil && !ids.contains(id) && ids.count < Self.maximumProfiles {
            ids.append(id)
        }
        let wanted = defaults.string(forKey: Self.activeProfileKey) ?? Self.defaultProfileID
        let active = ids.contains(wanted) ? wanted : Self.defaultProfileID
        // A profile that is neither active nor signed in was left by an "add account" the app was closed during.
        ids.removeAll { id in
            id != Self.defaultProfileID && id != active && !Self.savedSnapshot(of: id, in: defaults).isSignedIn
        }
        profileIDs = ids
        activeProfileID = active

        let store = Self.makeStore(for: active)
        dataStore = store
        snapshot = Self.savedSnapshot(of: active, in: defaults)
        // Cookies are read fresh from the web view's store; the saved copy only bridges the first moments.
        isSignedIn = snapshot.isSignedIn
        webView = Self.makeWebView(on: store)
        super.init()

        stores[active] = store
        saveProfiles()
        wire(webView)
        Task {
            await refreshCookies()
            if sweepsForgottenStores { await deleteForgottenStores() }
        }
    }

    // MARK: Loading

    /// Loads YouTube Music in the tab. Call once the web view is in a window (see `attach(to:)`).
    public func start() {
        hasStarted = true
        guard webView.url == nil else { return }
        reload()
    }

    public func reload() {
        move(to: .loading)
        webView.load(URLRequest(url: Self.home))
    }

    /// Opens a page of the site in the tab (a song's own page, when the player is not there yet).
    func open(_ url: URL) {
        move(to: .switchingPage)
        webView.load(URLRequest(url: url))
    }

    /// The one place the tab's phase changes. `always` announces it even when the phase stays the same: a new web
    /// view starts in `.loading` too, and the capture broker must hear that the page under its fetch is gone.
    private func move(to next: TabPhase, always: Bool = false) {
        guard next != phase || always else { return }
        let before = state
        phase = next
        switch next {
        case .loading: BoomTrace.note("tab loading")
        case .loaded(_, let hasPlayer):
            BoomTrace.note("tab loaded \(self.webView.url?.path() ?? "-") player=\(hasPlayer) signedIn=\(self.isSignedIn)")
        case .switchingPage: BoomTrace.note("tab opening a song's page")
        case .failed(let message): BoomTrace.note("tab failed: \(message)")
        }
        retry?.cancel()
        retry = nil
        switch next {
        case .loaded: retriesSinceLoaded = 0
        case .failed: scheduleRetry()
        case .loading, .switchingPage: break
        }
        if state != before || always { onChange?() }
        onPhaseChange?()
    }

    /// A tab that failed to load is loaded again by itself, a little later each time, so music can start once the
    /// connection is back without the listener having to do anything (or even know there is a tab).
    private func scheduleRetry() {
        let wait = Self.retryWaits[min(retriesSinceLoaded, Self.retryWaits.count - 1)]
        retriesSinceLoaded += 1
        let attempt = retriesSinceLoaded
        retry = Task { [weak self] in
            try? await Task.sleep(for: wait)
            guard !Task.isCancelled, let self, self.hasStarted, case .failed = self.phase else { return }
            BoomTrace.note("tab loads again after a failure (try \(attempt))")
            self.reload()
        }
    }

    /// A page finished loading, or its player appeared.
    private func pageArrived(hasPlayer: Bool) {
        switch phase {
        case .loaded(let at, let had):
            if hasPlayer, !had { move(to: .loaded(at: at, hasPlayer: true)) }
        case .loading, .switchingPage, .failed:
            move(to: .loaded(at: Date(), hasPlayer: hasPlayer))
        }
    }

    #if canImport(UIKit)
    /// Puts the web view at the very back of a window. WebKit only runs a page properly while its view is in a
    /// window; behind the app's own views nobody sees it.
    public func attach(to window: UIWindow) {
        self.window = window
        place(webView, in: window)
    }

    private func place(_ view: WKWebView, in window: UIWindow) {
        guard view.superview !== window else { return }
        view.removeFromSuperview()
        view.isUserInteractionEnabled = false
        view.accessibilityElementsHidden = true
        view.alpha = 0.02
        window.insertSubview(view, at: 0)
    }
    #endif

    // MARK: Talking to the page

    /// Runs one of the script's commands and returns its answer.
    @discardableResult
    func call(_ expression: String) async -> Any? {
        try? await webView.evaluateJavaScript("(function(){ try { return \(expression); } catch (e) { return null; } })()")
    }

    /// For diagnostics: evaluates an expression in the page and gives its answer as text (JSON for objects).
    public func inspect(_ expression: String) async -> String {
        let script = """
            (function(){ try { var v = (\(expression)); return typeof v === 'string' ? v : JSON.stringify(v); } \
            catch (e) { return 'threw: ' + e; } })()
            """
        do {
            let value = try await webView.evaluateJavaScript(script)
            return (value as? String) ?? String(describing: value)
        } catch {
            return "failed: \(error.localizedDescription)"
        }
    }

    private func handle(_ body: Any) {
        guard let event = TapEvent(message: body) else { return }
        if case .ready = event {
            pageArrived(hasPlayer: true)
            Task { await refreshPageSession() }
        }
        onEvent?(event)
    }

    // MARK: Staying awake in the background

    /// While held, a pending call into the page keeps WebKit's page process running with the app in the
    /// background (the app itself stays alive because it is playing audio).
    func setHeldAwake(_ held: Bool) {
        if held {
            guard holdTask == nil else { return }
            holdTask = Task { [weak self] in
                while !Task.isCancelled {
                    // The web view is read on every turn: after an account switch the hold moves to the new one.
                    guard let self else { return }
                    _ = try? await self.webView.callAsyncJavaScript(
                        "return await window.__boom.hold(20000)", arguments: [:], in: nil, contentWorld: .page)
                    try? await Task.sleep(for: .milliseconds(50))
                }
            }
        } else {
            holdTask?.cancel()
            holdTask = nil
            webView.evaluateJavaScript("window.__boom && window.__boom.release()", completionHandler: nil)
        }
    }

    // MARK: The session

    /// What the data client needs to act as this tab: cookies and the web app's own identifiers.
    public func sessionSnapshot() async -> WebSessionSnapshot {
        await refreshCookies()
        return snapshot
    }

    private func refreshCookies() async {
        let profile = activeProfileID
        let byName = await Self.youtubeCookies(in: dataStore)
        // Read from a profile that was switched away from meanwhile: they are not this tab's.
        guard profile == activeProfileID, byName != snapshot.cookies else { return }
        snapshot.cookies = byName
        save()
        let signedIn = snapshot.isSignedIn
        if signedIn != isSignedIn {
            isSignedIn = signedIn
            onChange?()
            // The page in the tab still shows the old account state.
            if webView.url != nil { reload() }
        }
    }

    private func refreshPageSession() async {
        let view = webView
        guard let values = await call("window.__boom.session()") as? [String: Any], view === webView else { return }
        snapshot.clientVersion = values["clientVersion"] as? String ?? snapshot.clientVersion
        snapshot.visitorData = values["visitorData"] as? String ?? snapshot.visitorData
        snapshot.language = values["language"] as? String ?? snapshot.language
        snapshot.region = values["region"] as? String ?? snapshot.region
        snapshot.authUser = values["authUser"] as? String
        snapshot.onBehalfOfUser = values["onBehalfOfUser"] as? String
        save()
    }

    private func save() {
        Self.save(snapshot, of: activeProfileID, in: defaults)
    }

    /// Signs the active profile out; a profile other than the default one goes altogether.
    public func signOut() async {
        await removeProfile(activeProfileID)
    }

    // MARK: Profiles

    public var profiles: [SessionProfile] {
        profileIDs.map { SessionProfile(id: $0, isSignedIn: isSignedIn(profile: $0), isActive: $0 == activeProfileID) }
    }

    public var profileLimit: Int { Self.maximumProfiles }

    /// Whether a profile is signed in, as its saved session last said (only the active one is read live).
    public func isSignedIn(profile id: String) -> Bool {
        id == activeProfileID ? isSignedIn : Self.savedSnapshot(of: id, in: defaults).isSignedIn
    }

    /// The data store the sign-in sheet must use to sign in to a profile.
    public func websiteDataStore(forProfile id: String) -> WKWebsiteDataStore {
        store(for: id)
    }

    /// A profile to sign another account in to, without switching to it: an empty one if there is one (the
    /// default profile, once signed out, is used again), otherwise a new one. Nil when all are taken.
    public func addProfile() -> String? {
        if let empty = profileIDs.first(where: { !isSignedIn(profile: $0) }) { return empty }
        guard profileIDs.count < Self.maximumProfiles else { return nil }
        let id = UUID().uuidString
        profileIDs.append(id)
        saveProfiles()
        BoomTrace.note("profile \(profileIDs.count) of \(Self.maximumProfiles) added")
        onChange?()
        return id
    }

    /// Takes the tab off the active profile and puts a new web view, signed in as the other account, in its place.
    /// Whatever plays keeps playing: the capture broker hears that the page is new and asks it again.
    public func switchProfile(to id: String) {
        guard profileIDs.contains(id), id != activeProfileID else { return }
        let previous = activeProfileID
        let wasHeld = holdTask != nil
        holdTask?.cancel()
        holdTask = nil
        cookieRefresh?.cancel()
        cookieRefresh = nil
        let host = webView.superview
        takeDown(webView, on: dataStore)

        activeProfileID = id
        defaults.set(id, forKey: Self.activeProfileKey)
        dataStore = store(for: id)
        snapshot = Self.savedSnapshot(of: id, in: defaults)
        isSignedIn = snapshot.isSignedIn
        webView = Self.makeWebView(on: dataStore)
        wire(webView)
        #if canImport(UIKit)
        if let window { place(webView, in: window) } else { host?.addSubview(webView) }
        #else
        host?.addSubview(webView)
        #endif
        BoomTrace.note("profile \(position(of: id)) of \(profileIDs.count) is active, signed in: \(isSignedIn)")

        // A profile whose Google session ended while it was active holds nothing worth keeping.
        if previous != Self.defaultProfileID, !isSignedIn(profile: previous) { forget(previous) }
        move(to: .loading, always: true)
        if hasStarted { webView.load(URLRequest(url: Self.home)) }
        if wasHeld { setHeldAwake(true) }
        Task { await refreshCookies() }
    }

    /// Signs a profile out. Another profile's data store is deleted; the default one is emptied and stays. When the
    /// active profile goes, the tab moves to the first other signed-in one, or else to the default one.
    public func removeProfile(_ id: String) async {
        guard profileIDs.contains(id) else { return }
        BoomTrace.note("signing out profile \(position(of: id)) of \(profileIDs.count)")
        let store = store(for: id)
        if id == activeProfileID {
            let next = profileIDs.first { $0 != id && isSignedIn(profile: $0) } ?? Self.defaultProfileID
            // The tab leaves the profile before its data goes, so no page of it is left signed in.
            if next != id { switchProfile(to: next) }
        }
        await Self.eraseGoogleData(in: store)
        if id == Self.defaultProfileID {
            if id == activeProfileID {
                snapshot.cookies = [:]
                snapshot.authUser = nil
                snapshot.onBehalfOfUser = nil
                save()
                isSignedIn = false
                onChange?()
                reload()
            } else {
                var saved = Self.savedSnapshot(of: id, in: defaults)
                saved.cookies = [:]
                saved.authUser = nil
                saved.onBehalfOfUser = nil
                Self.save(saved, of: id, in: defaults)
                onChange?()
            }
        } else if id != activeProfileID {
            forget(id)
            onChange?()
        }
    }

    /// Called when the sign-in sheet closes, whether Google sent the listener back or they gave up. A profile that
    /// is signed in now becomes the active one; a new profile left empty is removed.
    public func didFinishSignIn(profile id: String) async {
        guard profileIDs.contains(id) else { return }
        if id == activeProfileID {
            await refreshCookies()
            return
        }
        var cookies = await Self.youtubeCookies(in: store(for: id))
        if !WebSessionSnapshot(cookies: cookies, userAgent: Self.userAgent).isSignedIn {
            // Google's last answer may still be settling into the store.
            try? await Task.sleep(for: .milliseconds(800))
            cookies = await Self.youtubeCookies(in: store(for: id))
        }
        guard profileIDs.contains(id), id != activeProfileID else { return }
        var saved = Self.savedSnapshot(of: id, in: defaults)
        saved.cookies = cookies
        Self.save(saved, of: id, in: defaults)
        if saved.isSignedIn {
            switchProfile(to: id)
        } else if id != Self.defaultProfileID {
            forget(id)
            onChange?()
        }
    }

    /// Drops a profile that is neither active nor the default one, and deletes its data store.
    private func forget(_ id: String) {
        guard id != activeProfileID, id != Self.defaultProfileID else { return }
        profileIDs.removeAll { $0 == id }
        saveProfiles()
        defaults.removeObject(forKey: Self.snapshotKey(of: id))
        stores[id] = nil
        guard let uuid = UUID(uuidString: id) else { return }
        Task { await Self.deleteStore(uuid) }
    }

    /// Deletes data stores no profile owns any more: ones whose deletion failed earlier (a web view still had them).
    private func deleteForgottenStores() async {
        for uuid in await WKWebsiteDataStore.allDataStoreIdentifiers
        where !profileIDs.contains(uuid.uuidString) {
            await Self.deleteStore(uuid)
        }
    }

    private func store(for id: String) -> WKWebsiteDataStore {
        if let known = stores[id] { return known }
        let made = Self.makeStore(for: id)
        stores[id] = made
        return made
    }

    /// The profile's place in the list, counted from 1: the trace says which profile, never whose.
    private func position(of id: String) -> Int {
        (profileIDs.firstIndex(of: id) ?? profileIDs.count) + 1
    }

    private func saveProfiles() {
        defaults.set(profileIDs, forKey: Self.profilesKey)
        defaults.set(activeProfileID, forKey: Self.activeProfileKey)
    }

    // MARK: The web view

    /// The tab's web view on a profile's data store. Every web view the tab ever has is made here, so a switch
    /// gives exactly the same tab.
    private static func makeWebView(on store: WKWebsiteDataStore) -> WKWebView {
        let configuration = WKWebViewConfiguration()
        configuration.websiteDataStore = store
        configuration.mediaTypesRequiringUserActionForPlayback = []
        configuration.allowsAirPlayForMediaPlayback = false
        #if canImport(UIKit)
        configuration.allowsInlineMediaPlayback = true
        configuration.allowsPictureInPictureMediaPlayback = false
        #endif
        tune(configuration.preferences)
        if let url = Bundle.module.url(forResource: "boom-tap", withExtension: "js"),
           let source = try? String(contentsOf: url, encoding: .utf8) {
            configuration.userContentController.addUserScript(WKUserScript(
                source: source, injectionTime: .atDocumentStart, forMainFrameOnly: true, in: .page))
        }
        let view = WKWebView(frame: CGRect(x: 0, y: 0, width: 390, height: 260), configuration: configuration)
        view.customUserAgent = userAgent
        muteAudio(of: view)
        view.isInspectable = true
        return view
    }

    /// Connects a new web view to this object: the script's messages, the navigation, the cookies, the ad rules.
    private func wire(_ view: WKWebView) {
        let controller = view.configuration.userContentController
        controller.add(MessageRelay(self), name: "boom")
        view.navigationDelegate = self
        view.configuration.websiteDataStore.httpCookieStore.add(self)
        installAdRules(on: controller)
    }

    /// Takes a web view out of service for good: nothing of it reaches this object again, and its page stops.
    private func takeDown(_ view: WKWebView, on store: WKWebsiteDataStore) {
        view.stopLoading()
        view.configuration.userContentController.removeScriptMessageHandler(forName: "boom")
        view.navigationDelegate = nil
        store.httpCookieStore.remove(self)
        view.removeFromSuperview()
        // Should anything still hold the view, its page (and that account's player) is gone all the same.
        view.loadHTMLString("", baseURL: nil)
    }

    // MARK: Data stores

    private static func makeStore(for id: String) -> WKWebsiteDataStore {
        if id == defaultProfileID { return .default() }
        // Ids are checked when read back; a bad one must never fall through to another account's store.
        guard let uuid = UUID(uuidString: id) else { return .nonPersistent() }
        return WKWebsiteDataStore(forIdentifier: uuid)
    }

    private static func deleteStore(_ uuid: UUID) async {
        // WebKit refuses while a web view still uses the store (the sign-in sheet closing, or the tab just taken
        // down): try again a little later; what stays is deleted at the next launch.
        for attempt in 0..<3 {
            do {
                try await WKWebsiteDataStore.remove(forIdentifier: uuid)
                return
            } catch {
                BoomTrace.note("a profile's data could not be deleted yet (try \(attempt + 1))")
                try? await Task.sleep(for: .seconds(2))
            }
        }
    }

    private static func youtubeCookies(in store: WKWebsiteDataStore) async -> [String: String] {
        let cookies = await store.httpCookieStore.allCookies()
        var byName: [String: String] = [:]
        for cookie in cookies where cookie.domain.hasSuffix("youtube.com") {
            byName[cookie.name] = cookie.value
        }
        return byName
    }

    /// Removes the Google and YouTube sign-in and storage from a data store.
    private static func eraseGoogleData(in store: WKWebsiteDataStore) async {
        let types: Set<String> = [WKWebsiteDataTypeCookies, WKWebsiteDataTypeLocalStorage, WKWebsiteDataTypeSessionStorage,
                                  WKWebsiteDataTypeIndexedDBDatabases]
        let records = await store.dataRecords(ofTypes: types)
        let ours = records.filter { record in
            ["youtube", "google", "gstatic", "ggpht"].contains { record.displayName.contains($0) }
        }
        await store.removeData(ofTypes: types, for: ours)
    }

    // MARK: Saved sessions

    /// The default profile keeps the key the one sign-in always had.
    private static func snapshotKey(of id: String) -> String {
        id == defaultProfileID ? snapshotKey : "\(snapshotKey).\(id)"
    }

    private static func savedSnapshot(of id: String, in defaults: UserDefaults) -> WebSessionSnapshot {
        var saved = defaults.data(forKey: snapshotKey(of: id))
            .flatMap { try? JSONDecoder().decode(WebSessionSnapshot.self, from: $0) }
            ?? WebSessionSnapshot(userAgent: userAgent)
        saved.userAgent = userAgent
        return saved
    }

    private static func save(_ snapshot: WebSessionSnapshot, of id: String, in defaults: UserDefaults) {
        if let data = try? JSONEncoder().encode(snapshot) { defaults.set(data, forKey: snapshotKey(of: id)) }
    }

    // MARK: Ads at the network level

    /// Requests to ad and tracking hosts are refused outright. The ad *instructions* inside the player's answers are
    /// removed by the script; this is the second line. No rule may match the media requests themselves
    /// (`googlevideo.com/videoplayback`): one that refused requests marked `ctier=A` refused every stream of a
    /// Premium account.
    private func installAdRules(on controller: WKUserContentController) {
        if let adRules {
            controller.add(adRules)
            return
        }
        guard let url = Bundle.module.url(forResource: "ad-rules", withExtension: "json"),
              let rules = try? String(contentsOf: url, encoding: .utf8) else { return }
        WKContentRuleListStore.default().compileContentRuleList(
            forIdentifier: "boom-ad-rules-1", encodedContentRuleList: rules
        ) { [weak self] list, _ in
            guard let list else { return }
            Task { @MainActor in
                self?.adRules = list
                controller.add(list)
            }
        }
    }

    // MARK: WebKit switches without a public name

    private static func tune(_ preferences: WKPreferences) {
        // Timers in a page that is "not visible" are slowed down; this page works unseen.
        setFeature("HiddenPageDOMTimerThrottlingEnabled", false, on: preferences)
        setFeature("HiddenPageDOMTimerThrottlingAutoIncreases", false, on: preferences)
        setFeature("DOMTimersThrottlingEnabled", false, on: preferences)
        setFeature("HiddenPageCSSAnimationSuspensionEnabled", true, on: preferences)
    }

    /// Mutes the whole page from outside it, whatever its script does: a second lock next to the one in
    /// `boom-tap.js`. A page that sounded would not only be heard, it would take the phone's audio from the app.
    private static func muteAudio(of webView: WKWebView) {
        let selector = NSSelectorFromString("_setPageMuted:")
        guard webView.responds(to: selector) else { return }
        typealias Setter = @convention(c) (AnyObject, Selector, UInt) -> Void
        let setter = unsafeBitCast(webView.method(for: selector), to: Setter.self)
        setter(webView, selector, 1)    // _WKMediaAudioMuted
    }

    private static func setFeature(_ key: String, _ enabled: Bool, on preferences: WKPreferences) {
        let listSelector = NSSelectorFromString("_features")
        let setSelector = NSSelectorFromString("_setEnabled:forFeature:")
        guard let type = WKPreferences.self as AnyObject as? NSObject.Type, type.responds(to: listSelector),
              preferences.responds(to: setSelector),
              let features = type.perform(listSelector)?.takeUnretainedValue() as? [NSObject],
              let feature = features.first(where: { ($0.value(forKey: "key") as? String) == key })
        else { return }
        typealias Setter = @convention(c) (AnyObject, Selector, Bool, AnyObject) -> Void
        let setter = unsafeBitCast(preferences.method(for: setSelector), to: Setter.self)
        setter(preferences, setSelector, enabled, feature)
    }
}

extension HiddenBrowser: WKNavigationDelegate {
    // Each callback checks that it is about the current web view: one taken down by a switch says nothing now.

    public func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        guard webView === self.webView else { return }
        pageArrived(hasPlayer: false)
        Task { await refreshPageSession() }
    }

    public func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: any Error) {
        guard webView === self.webView else { return }
        fail(error)
    }

    public func webView(
        _ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: any Error
    ) {
        guard webView === self.webView else { return }
        fail(error)
    }

    public func webViewWebContentProcessDidTerminate(_ webView: WKWebView) {
        guard webView === self.webView else { return }
        // WebKit's page process was killed (memory pressure): bring the tab back.
        BoomTrace.note("tab process terminated: reloading")
        reload()
    }

    public func webView(
        _ webView: WKWebView, decidePolicyFor navigationAction: WKNavigationAction
    ) async -> WKNavigationActionPolicy {
        // The tab stays on YouTube Music and Google's own pages; anything else (an app link, a store page) is refused.
        guard navigationAction.targetFrame?.isMainFrame ?? true, let url = navigationAction.request.url,
              let scheme = url.scheme?.lowercased() else { return .allow }
        guard scheme == "https" || scheme == "about" || scheme == "blob" else { return .cancel }
        return .allow
    }

    private func fail(_ error: any Error) {
        let code = (error as NSError).code
        // A navigation replaced by a newer one is not a failure.
        guard code != NSURLErrorCancelled else { return }
        BoomTrace.note("tab failed to load: \((error as NSError).domain) \(code)")
        move(to: .failed("Couldn't reach YouTube Music. Check your connection."))
    }
}

extension HiddenBrowser: WKHTTPCookieStoreObserver {
    public nonisolated func cookiesDidChange(in cookieStore: WKHTTPCookieStore) {
        Task { @MainActor in
            // Cookies change in bursts while signing in: look once they settle.
            self.cookieRefresh?.cancel()
            self.cookieRefresh = Task {
                try? await Task.sleep(for: .milliseconds(400))
                guard !Task.isCancelled else { return }
                await self.refreshCookies()
            }
        }
    }
}

/// Passes the page's messages on without the web view's configuration keeping the browser alive forever.
private final class MessageRelay: NSObject, WKScriptMessageHandler {
    private weak var browser: HiddenBrowser?

    init(_ browser: HiddenBrowser) {
        self.browser = browser
    }

    func userContentController(_ controller: WKUserContentController, didReceive message: WKScriptMessage) {
        MainActor.assumeIsolated {
            // A message still on its way from a web view taken down by a switch is dropped.
            guard let browser, message.webView === browser.webView else { return }
            browser.receive(message.body)
        }
    }
}

extension HiddenBrowser {
    fileprivate func receive(_ body: Any) {
        handle(body)
    }
}
