import Foundation

/// One audio format the player's answer offers for a song.
public struct AudioOffer: Sendable, Equatable, Codable {
    public var itag: Int
    /// `audio/webm; codecs="opus"`.
    public var mimeType: String
    /// Bits per second.
    public var bitrate: Int

    public init(itag: Int, mimeType: String, bitrate: Int) {
        self.itag = itag
        self.mimeType = mimeType
        self.bitrate = bitrate
    }

    /// "Opus", "AAC".
    public var codecName: String {
        let lower = mimeType.lowercased()
        if lower.contains("opus") { return "Opus" }
        if lower.contains("mp4a") { return "AAC" }
        return "Audio"
    }
}

/// What the script in the hidden tab tells the app (see `Resources/boom-tap.js`).
enum TapEvent: Sendable, Equatable {
    /// The script is in place in a freshly loaded page.
    case hello(mediaSource: String)
    /// The page's player is there to take commands.
    case ready
    /// The player's answer about a song. `token` is set when it is the song the app asked for.
    /// `chosen` is the one audio format the script left for the player to use (the best on offer).
    case player(
        token: String?, videoId: String?, status: String?, reason: String?, seconds: Double?, isLive: Bool,
        loudnessDb: Double?, audio: [AudioOffer], chosen: AudioOffer?)
    /// The player opened an audio buffer for the app's song.
    case buffer(token: String, id: Int, mimeType: String)
    /// Bytes the player appended to that buffer.
    case append(token: String, id: Int, data: Data)
    /// The player dropped a partly appended segment and starts a new one (it jumped).
    case restart(token: String, id: Int)
    /// The player reached the end of the stream.
    case end(token: String, id: Int, seconds: Double?)
    case unsupported(String)
    case scriptError(where: String, message: String)

    init?(message body: Any) {
        guard let body = body as? [String: Any], let type = body["t"] as? String else { return nil }
        func string(_ key: String) -> String? { body[key] as? String }
        func double(_ key: String) -> Double? { (body[key] as? NSNumber)?.doubleValue }
        func int(_ key: String) -> Int? { (body[key] as? NSNumber)?.intValue }
        switch type {
        case "hello":
            self = .hello(mediaSource: string("mediaSource") ?? "")
        case "ready":
            self = .ready
        case "player":
            let offers = (body["audio"] as? [[String: Any]] ?? []).compactMap { offer -> AudioOffer? in
                guard let itag = (offer["itag"] as? NSNumber)?.intValue, let mime = offer["mime"] as? String
                else { return nil }
                return AudioOffer(itag: itag, mimeType: mime, bitrate: (offer["bitrate"] as? NSNumber)?.intValue ?? 0)
            }
            let chosen = (body["chosen"] as? [String: Any]).flatMap { offer -> AudioOffer? in
                guard let itag = (offer["itag"] as? NSNumber)?.intValue, let mime = offer["mime"] as? String
                else { return nil }
                return AudioOffer(itag: itag, mimeType: mime, bitrate: (offer["bitrate"] as? NSNumber)?.intValue ?? 0)
            }
            self = .player(
                token: string("token"), videoId: string("videoId"), status: string("status"),
                reason: string("reason"), seconds: double("seconds"), isLive: body["isLive"] as? Bool ?? false,
                loudnessDb: double("loudnessDb"), audio: offers, chosen: chosen)
        case "buffer":
            guard let token = string("token"), let id = int("id"), let mime = string("mime") else { return nil }
            self = .buffer(token: token, id: id, mimeType: mime)
        case "append":
            guard let token = string("token"), let id = int("id"), let text = string("d"),
                  let data = Data(base64Encoded: text) else { return nil }
            self = .append(token: token, id: id, data: data)
        case "restart":
            guard let token = string("token"), let id = int("id") else { return nil }
            self = .restart(token: token, id: id)
        case "end":
            guard let token = string("token"), let id = int("id") else { return nil }
            self = .end(token: token, id: id, seconds: double("seconds"))
        case "unsupported":
            self = .unsupported(string("reason") ?? "")
        case "error":
            self = .scriptError(where: string("where") ?? "", message: string("message") ?? "")
        default:
            return nil
        }
    }
}

/// What the hidden tab knows that the data client needs: who is signed in, and how the web app introduces itself.
public struct WebSessionSnapshot: Sendable, Equatable, Codable {
    public var clientVersion: String?
    public var visitorData: String?
    public var language: String?
    public var region: String?
    /// The `.youtube.com` cookies by name.
    public var cookies: [String: String]
    public var authUser: String?
    public var onBehalfOfUser: String?
    public var userAgent: String

    public init(
        clientVersion: String? = nil, visitorData: String? = nil, language: String? = nil, region: String? = nil,
        cookies: [String: String] = [:], authUser: String? = nil, onBehalfOfUser: String? = nil, userAgent: String
    ) {
        self.clientVersion = clientVersion
        self.visitorData = visitorData
        self.language = language
        self.region = region
        self.cookies = cookies
        self.authUser = authUser
        self.onBehalfOfUser = onBehalfOfUser
        self.userAgent = userAgent
    }

    public var isSignedIn: Bool { cookies["SAPISID"] != nil || cookies["__Secure-3PAPISID"] != nil }
}
