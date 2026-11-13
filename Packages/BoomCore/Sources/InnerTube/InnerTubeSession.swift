import Foundation

// The contract between the hidden browser tab (which owns the Google sign-in) and the data client.
// Owned by the lead: the data worker builds on these types and does not change their public shape.

/// What a request to YouTube Music's web API needs to look like the web app's own request.
public struct InnerTubeSession: Sendable, Equatable {
    /// `INNERTUBE_CLIENT_VERSION` of the `WEB_REMIX` client, as the live page reports it ("1.20261105.01.00").
    public var clientVersion: String
    /// `VISITOR_DATA`: sent as `X-Goog-Visitor-Id` and in the request context.
    public var visitorData: String?
    /// `hl`, such as "en".
    public var language: String
    /// `gl`, such as "IN".
    public var region: String
    public var userAgent: String
    /// The `.youtube.com` cookies by name. Empty when signed out.
    public var cookies: [String: String]
    /// `X-Goog-AuthUser`: which of the browser's Google accounts ("0").
    public var authUser: String
    /// A brand account's id (`DELEGATED_SESSION_ID`), sent as `context.user.onBehalfOfUser`.
    public var onBehalfOfUser: String?

    public init(
        clientVersion: String,
        visitorData: String? = nil,
        language: String = "en",
        region: String = "US",
        userAgent: String = InnerTubeSession.desktopSafariUserAgent,
        cookies: [String: String] = [:],
        authUser: String = "0",
        onBehalfOfUser: String? = nil
    ) {
        self.clientVersion = clientVersion
        self.visitorData = visitorData
        self.language = language
        self.region = region
        self.userAgent = userAgent
        self.cookies = cookies
        self.authUser = authUser
        self.onBehalfOfUser = onBehalfOfUser
    }

    /// The cookie the `SAPISIDHASH` authorization is computed from.
    public var sapisid: String? { cookies["SAPISID"] ?? cookies["__Secure-3PAPISID"] }

    public var isSignedIn: Bool { sapisid != nil }

    /// The hidden tab and the data client present themselves as desktop Safari, so both get the same web app.
    public static let desktopSafariUserAgent =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) "
        + "Version/26.0 Safari/605.1.15"
}

/// Supplies the session for each request. In the app this is backed by the hidden browser tab; in tests and before
/// the tab has loaded, by `BootstrapSessionProvider` (signed out).
public protocol InnerTubeSessionProviding: Sendable {
    func currentSession() async throws -> InnerTubeSession
}
