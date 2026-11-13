import BoomModels
import BoomPlayer
import InnerTube
import SwiftUI
import UIKit

/// Everything that lives as long as the app does: the hidden YouTube Music tab, the data client that borrows its
/// sign-in, and the player.
@MainActor
@Observable
final class AppModel {
    let browser: HiddenBrowser
    let runtime: BoomRuntime
    var services: AppServices { runtime.services }

    @ObservationIgnored private var didStart = false
    @ObservationIgnored private var diagnostics: Diagnostics?

    init() {
        let browser = HiddenBrowser()
        let api = InnerTubeClient(sessionProvider: BrowserSessionProvider(browser: browser))
        self.browser = browser
        runtime = BoomRuntime(api: api, browser: browser)
    }

    /// Called once the app's window exists: the hidden tab only runs properly inside a window.
    func start(in window: UIWindow) {
        browser.attach(to: window)
        guard !didStart else { return }
        didStart = true
        browser.start()
        diagnostics = Diagnostics(runtime: runtime)
    }
}

/// Gives the data client the hidden tab's identity: its cookies (the Google sign-in) and the web app's own
/// version and visitor id. Until the tab has loaded for the first time, those come from the start page.
struct BrowserSessionProvider: InnerTubeSessionProviding {
    let browser: HiddenBrowser
    private let fallback = BootstrapSessionProvider(
        language: Locale.current.language.languageCode?.identifier ?? "en",
        region: Locale.current.region?.identifier ?? "US")

    init(browser: HiddenBrowser) {
        self.browser = browser
    }

    func currentSession() async throws -> InnerTubeSession {
        let snapshot = await browser.sessionSnapshot()
        var clientVersion = snapshot.clientVersion
        var visitorData = snapshot.visitorData
        if clientVersion == nil {
            let base = try? await fallback.currentSession()
            clientVersion = base?.clientVersion
            visitorData = visitorData ?? base?.visitorData
        }
        return InnerTubeSession(
            clientVersion: clientVersion ?? BootstrapSessionProvider.fallbackClientVersion,
            visitorData: visitorData,
            language: snapshot.language ?? Locale.current.language.languageCode?.identifier ?? "en",
            region: snapshot.region ?? Locale.current.region?.identifier ?? "US",
            userAgent: snapshot.userAgent,
            cookies: snapshot.cookies,
            authUser: snapshot.authUser ?? "0",
            onBehalfOfUser: snapshot.onBehalfOfUser)
    }
}
