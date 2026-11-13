import Foundation
import Testing
import WebKit
@testable import BoomPlayer

/// The hidden browser's profiles, without the network: nothing is loaded, only the bookkeeping and the replacing of
/// the web view are checked. The checks run before the browser's own first look at its cookies (the test machine's
/// stores hold none), so they stay within one turn of the main actor except where noted.
///
/// Every test makes a real web view, and the whole test target shares the main actor with suites that wait on the
/// clock: so one test at a time, as few web views as will do, a yield after the heavy steps where it is safe, and no
/// launch sweep of data stores (each browser would delete the others').
@MainActor
@Suite(.serialized) struct HiddenBrowserProfileTests {
    private func makeBrowser(_ defaults: UserDefaults) -> HiddenBrowser {
        HiddenBrowser(defaults: defaults, sweepsForgottenStores: false)
    }

    /// Settings in which the default profile was signed in at the last launch.
    private func signedInDefaults() throws -> UserDefaults {
        let defaults = try #require(UserDefaults(suiteName: "boom.profiles.\(UUID().uuidString)"))
        let snapshot = WebSessionSnapshot(cookies: ["SAPISID": "x"], userAgent: HiddenBrowser.userAgent)
        defaults.set(try JSONEncoder().encode(snapshot), forKey: "web.sessionSnapshot")
        return defaults
    }

    @Test func anEmptyDefaultProfileIsUsedBeforeANewOne() async throws {
        let defaults = try #require(UserDefaults(suiteName: "boom.profiles.\(UUID().uuidString)"))
        let browser = makeBrowser(defaults)
        #expect(browser.addProfile() == HiddenBrowser.defaultProfileID)
        #expect(browser.profileIDs.count == 1)
        await Task.yield()
    }

    @Test func profilesStopAtThree() async throws {
        let defaults = try signedInDefaults()
        let browser = makeBrowser(defaults)
        let second = try #require(browser.addProfile())
        #expect(UUID(uuidString: second) != nil)
        #expect(browser.profileIDs == [HiddenBrowser.defaultProfileID, second])
        #expect(browser.activeProfileID == HiddenBrowser.defaultProfileID, "adding does not switch")
        // The new profile is empty, so asking again gives it back rather than a third.
        #expect(browser.addProfile() == second)
        #expect(browser.websiteDataStore(forProfile: second) !== browser.dataStore)
        #expect(browser.websiteDataStore(forProfile: second) === browser.websiteDataStore(forProfile: second))

        // Signed in (as the sheet would leave it), it no longer counts as free: a third is made, and then no more.
        let signedIn = try JSONEncoder().encode(WebSessionSnapshot(cookies: ["SAPISID": "y"], userAgent: HiddenBrowser.userAgent))
        defaults.set(signedIn, forKey: "web.sessionSnapshot.\(second)")
        let third = try #require(browser.addProfile())
        #expect(third != second)
        defaults.set(signedIn, forKey: "web.sessionSnapshot.\(third)")
        #expect(browser.addProfile() == nil)
        #expect(browser.profileIDs.count == HiddenBrowser.maximumProfiles)
        await Task.yield()
    }

    @Test func switchingReplacesTheWebViewAndAnnouncesIt() async throws {
        let defaults = try signedInDefaults()
        let browser = makeBrowser(defaults)
        // The one sign-in there was before profiles is the default profile, on WebKit's default store.
        #expect(browser.profileIDs == [HiddenBrowser.defaultProfileID])
        #expect(browser.activeProfileID == HiddenBrowser.defaultProfileID)
        #expect(browser.isSignedIn)
        #expect(browser.dataStore === WKWebsiteDataStore.default())

        let second = try #require(browser.addProfile())
        var phaseChanges = 0
        var changes = 0
        browser.onPhaseChange = { phaseChanges += 1 }
        browser.onChange = { changes += 1 }
        let oldView = browser.webView
        #expect(browser.phase == .loading)

        browser.switchProfile(to: second)
        #expect(browser.activeProfileID == second)
        #expect(browser.webView !== oldView)
        #expect(oldView.navigationDelegate == nil)
        #expect(browser.webView.navigationDelegate === browser)
        #expect(browser.dataStore === browser.websiteDataStore(forProfile: second))
        #expect(browser.webView.configuration.websiteDataStore === browser.dataStore)
        #expect(browser.webView.customUserAgent == HiddenBrowser.userAgent)
        #expect(phaseChanges == 1 && changes >= 1, "announced although the phase was already .loading")
        #expect(!browser.isSignedIn)
        #expect(defaults.string(forKey: "web.activeProfile") == second)
        // Safe to let the pending cookie reads run now: both read the new, empty profile, which changes nothing.
        await Task.yield()

        // Back to the default profile: the empty one it leaves is not kept.
        browser.switchProfile(to: HiddenBrowser.defaultProfileID)
        #expect(browser.isSignedIn)
        #expect(browser.profileIDs == [HiddenBrowser.defaultProfileID])
        #expect(phaseChanges == 2)
        await Task.yield()
    }

    @Test func theProfilesAndTheActiveOneSurviveARestart() async throws {
        let defaults = try signedInDefaults()
        let second = UUID().uuidString
        let snapshot = WebSessionSnapshot(cookies: ["SAPISID": "y"], userAgent: HiddenBrowser.userAgent)
        defaults.set(try JSONEncoder().encode(snapshot), forKey: "web.sessionSnapshot.\(second)")
        let leftEmpty = UUID().uuidString
        defaults.set([HiddenBrowser.defaultProfileID, second, leftEmpty], forKey: "web.profiles")
        defaults.set(second, forKey: "web.activeProfile")

        do {
            let browser = makeBrowser(defaults)
            // The profile an unfinished "add account" left behind is gone; the signed-in ones stay.
            #expect(browser.profileIDs == [HiddenBrowser.defaultProfileID, second])
            #expect(browser.activeProfileID == second)
            #expect(browser.isSignedIn)
            #expect(browser.isSignedIn(profile: HiddenBrowser.defaultProfileID))
            #expect(browser.profiles.filter(\.isActive).map(\.id) == [second])
        }
        // The browser is gone; the store its web view used is deleted, as the launch sweep would have.
        await Task.yield()
        if let uuid = UUID(uuidString: second) { try? await WKWebsiteDataStore.remove(forIdentifier: uuid) }
    }
}
