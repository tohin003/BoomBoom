import BoomModels
import CryptoKit
import Foundation

/// Builds requests that look like the YouTube Music web app's own (`WEB_REMIX`) calls.
enum InnerTubeRequest {
    static let origin = "https://music.youtube.com"
    /// `X-Youtube-Client-Name` of `WEB_REMIX`.
    static let clientNameNumber = "67"

    static func url(endpoint: String) -> URL? {
        URL(string: "\(origin)/youtubei/v1/\(endpoint)?prettyPrint=false")
    }

    /// The `context` every call carries: the client, the language and region, and the brand account if any.
    static func context(for session: InnerTubeSession) -> JSON {
        var client: JSON = [
            "clientName": "WEB_REMIX",
            "clientVersion": .string(session.clientVersion),
            "hl": .string(session.language),
            "gl": .string(session.region),
        ]
        if let visitorData = session.visitorData { client.set("visitorData", .string(visitorData)) }
        var user: JSON = .object([:])
        if let onBehalfOfUser = session.onBehalfOfUser, !onBehalfOfUser.isEmpty {
            user.set("onBehalfOfUser", .string(onBehalfOfUser))
        }
        return ["client": client, "user": user]
    }

    static func make(
        endpoint: String,
        body: JSON,
        session: InnerTubeSession,
        now: Date = Date(),
        timeout: TimeInterval = 20
    ) throws -> URLRequest {
        guard let url = url(endpoint: endpoint) else { throw MusicAPIError.unreadable("bad endpoint \(endpoint)") }
        var fullBody = body
        fullBody.set("context", context(for: session))

        var request = URLRequest(url: url, timeoutInterval: timeout)
        request.httpMethod = "POST"
        request.httpBody = try fullBody.data()
        request.httpShouldHandleCookies = false
        for (name, value) in headers(for: session, now: now) {
            request.setValue(value, forHTTPHeaderField: name)
        }
        return request
    }

    static func headers(for session: InnerTubeSession, now: Date = Date()) -> [(String, String)] {
        var headers: [(String, String)] = [
            ("Content-Type", "application/json"),
            ("Accept", "*/*"),
            ("Accept-Language", session.language),
            ("User-Agent", session.userAgent),
            ("Origin", origin),
            ("X-Origin", origin),
            ("Referer", origin + "/"),
            ("X-Youtube-Client-Name", clientNameNumber),
            ("X-Youtube-Client-Version", session.clientVersion),
        ]
        if let visitorData = session.visitorData {
            headers.append(("X-Goog-Visitor-Id", visitorData))
        }
        // Signed out, no cookie at all: the API answers without one, and the visitor id carries the region.
        if session.isSignedIn, let sapisid = session.sapisid {
            headers.append(("Cookie", cookieHeader(session.cookies)))
            headers.append(("X-Goog-AuthUser", session.authUser))
            headers.append(("Authorization", authorization(sapisid: sapisid, timestamp: Int(now.timeIntervalSince1970))))
        }
        return headers
    }

    /// `SAPISIDHASH <unix>_<sha1("<unix> <SAPISID> <origin>")>`.
    static func authorization(sapisid: String, timestamp: Int, origin: String = origin) -> String {
        let digest = Insecure.SHA1.hash(data: Data("\(timestamp) \(sapisid) \(origin)".utf8))
        let hex = digest.map { String(format: "%02x", $0) }.joined()
        return "SAPISIDHASH \(timestamp)_\(hex)"
    }

    /// All cookies as one header, sorted by name so the header is stable.
    static func cookieHeader(_ cookies: [String: String]) -> String {
        cookies.sorted { $0.key < $1.key }.map { "\($0.key)=\($0.value)" }.joined(separator: "; ")
    }
}

