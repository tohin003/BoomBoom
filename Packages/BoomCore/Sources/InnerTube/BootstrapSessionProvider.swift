import BoomModels
import Foundation

/// A signed-out session read from the YouTube Music start page, for tests and for app start before the hidden
/// browser tab is ready.
///
/// Fetches `https://music.youtube.com/` as desktop Safari, reads `INNERTUBE_CLIENT_VERSION` and `VISITOR_DATA` from
/// the page's `ytcfg.set({...})`, and keeps them for about 12 hours. When the page cannot be read it falls back to a
/// recent known client version (without a visitor id) and tries the page again a few minutes later.
public actor BootstrapSessionProvider: InnerTubeSessionProviding {
    /// A recent `WEB_REMIX` version, used when the page cannot be read.
    public static let fallbackClientVersion = "1.20261110.04.00"

    let language: String
    let region: String
    let urlSession: URLSession
    let refreshInterval: TimeInterval
    let retryInterval: TimeInterval

    /// What the provider has in hand. One of these at a time, so "cached" and "being fetched" cannot disagree.
    private enum State {
        case empty
        /// The start page is being read; everyone who asks meanwhile waits for the same answer.
        case fetching(Task<(InnerTubeSession, Bool), Never>)
        case ready(session: InnerTubeSession, expires: Date)
    }

    private var state = State.empty

    public init(
        language: String = "en",
        region: String = "US",
        urlSession: URLSession = .shared,
        refreshInterval: TimeInterval = 12 * 3600
    ) {
        self.language = language
        self.region = region
        self.urlSession = urlSession
        self.refreshInterval = refreshInterval
        self.retryInterval = 5 * 60
    }

    public func currentSession() async throws -> InnerTubeSession {
        let task: Task<(InnerTubeSession, Bool), Never>
        switch state {
        case .ready(let session, let expires) where expires > Date():
            return session
        case .fetching(let running):
            task = running
        case .empty, .ready:
            let language = language, region = region, urlSession = urlSession
            task = Task { await Self.fetchSession(language: language, region: region, urlSession: urlSession) }
            state = .fetching(task)
        }
        let (session, fromPage) = await task.value
        // Others may have asked, or invalidated, while this call waited: only the fetch still in progress lands.
        if case .fetching(let running) = state, running == task {
            state = .ready(session: session, expires: Date().addingTimeInterval(fromPage ? refreshInterval : retryInterval))
        }
        try Task.checkCancellation()
        return session
    }

    /// Forgets the cached session, so the next call reads the page again. A read already under way is left to
    /// finish: its answer is as fresh as a new one would be.
    public func invalidate() {
        if case .ready = state { state = .empty }
    }

    /// The session from the start page, and whether the page could be read.
    static func fetchSession(language: String, region: String, urlSession: URLSession) async
        -> (InnerTubeSession, Bool)
    {
        let fallback = InnerTubeSession(clientVersion: fallbackClientVersion, language: language, region: region)
        guard let url = URL(string: "https://music.youtube.com/?hl=\(language)&gl=\(region)") else {
            return (fallback, false)
        }
        var request = URLRequest(url: url, timeoutInterval: 15)
        request.httpShouldHandleCookies = false
        request.setValue(InnerTubeSession.desktopSafariUserAgent, forHTTPHeaderField: "User-Agent")
        request.setValue(language, forHTTPHeaderField: "Accept-Language")
        request.setValue("text/html,application/xhtml+xml", forHTTPHeaderField: "Accept")
        // "Reject all" consent: without it, EU visitors are redirected to consent.youtube.com.
        request.setValue("SOCS=CAI", forHTTPHeaderField: "Cookie")
        guard let (data, response) = try? await urlSession.data(for: request),
            let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode),
            http.url?.host() == "music.youtube.com",
            let html = String(data: data, encoding: .utf8)
        else { return (fallback, false) }
        let config = parseConfig(html: html)
        guard let version = config.clientVersion else { return (fallback, false) }
        return (
            InnerTubeSession(clientVersion: version, visitorData: config.visitorData, language: language, region: region),
            true
        )
    }

    /// `INNERTUBE_CLIENT_VERSION` and `VISITOR_DATA` from the `ytcfg.set({...})` calls of the page.
    static func parseConfig(html: String) -> (clientVersion: String?, visitorData: String?) {
        var clientVersion: String?
        var visitorData: String?
        for object in ytcfgObjects(in: html) {
            guard let json = try? JSON(data: Data(object.utf8)) else { continue }
            clientVersion = clientVersion ?? json["INNERTUBE_CLIENT_VERSION"].text
            visitorData = visitorData ?? json["VISITOR_DATA"].text
                ?? json["INNERTUBE_CONTEXT", "client", "visitorData"].text
        }
        // A page that changed how it calls ytcfg: look for the keys anywhere.
        clientVersion = clientVersion ?? firstStringValue(of: "INNERTUBE_CLIENT_VERSION", in: html)
        visitorData = visitorData ?? firstStringValue(of: "VISITOR_DATA", in: html)
        return (clientVersion, visitorData)
    }

    /// The object literals passed to `ytcfg.set(`, found by matching braces outside of strings.
    static func ytcfgObjects(in html: String) -> [String] {
        var objects: [String] = []
        var searchStart = html.startIndex
        while let call = html.range(of: "ytcfg.set(", range: searchStart..<html.endIndex) {
            searchStart = call.upperBound
            guard let open = html[call.upperBound...].firstIndex(where: { !$0.isWhitespace }), html[open] == "{"
            else { continue }
            var depth = 0
            var inString = false
            var escaped = false
            var index = open
            while index < html.endIndex {
                let character = html[index]
                if inString {
                    if escaped { escaped = false } else if character == "\\" { escaped = true } else if character == "\"" {
                        inString = false
                    }
                } else if character == "\"" {
                    inString = true
                } else if character == "{" {
                    depth += 1
                } else if character == "}" {
                    depth -= 1
                    if depth == 0 {
                        objects.append(String(html[open...index]))
                        searchStart = html.index(after: index)
                        break
                    }
                }
                index = html.index(after: index)
            }
        }
        return objects
    }

    static func firstStringValue(of key: String, in html: String) -> String? {
        guard let keyRange = html.range(of: "\"\(key)\":\"") else { return nil }
        let rest = html[keyRange.upperBound...]
        guard let end = rest.firstIndex(of: "\"") else { return nil }
        let value = String(rest[..<end])
        return value.isEmpty ? nil : value
    }
}
