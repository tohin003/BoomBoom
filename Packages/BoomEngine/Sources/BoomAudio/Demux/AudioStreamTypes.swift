import Foundation

/// What the decoder needs to know about a captured audio stream.
public struct AudioTrackInfo: Sendable, Equatable {
    public enum Codec: String, Sendable, Equatable {
        case aac
        case opus
    }

    public var codec: Codec
    /// Units per second of `AudioPacket.decodeTime` and `duration`.
    public var timescale: Int
    /// The rate the container states. The decoder trusts the codec setup over this.
    public var sampleRate: Double
    public var channels: Int
    /// The codec's setup record in the form Apple's decoders take:
    /// AAC: the MPEG-4 elementary stream descriptor (the `esds` box without its 4-byte header fields).
    /// Opus: the `OpusHead` identification header.
    public var magicCookie: Data
    /// AAC only: the raw AudioSpecificConfig.
    public var audioSpecificConfig: Data
    /// Timeline units to drop at the very start of the stream (the encoder's lead-in), from the container.
    public var leadIn: Int64
    /// Packet length in timeline units when the stream states a constant one (1024 for AAC-LC).
    public var defaultPacketDuration: Int?
    /// The stream's whole length in seconds, when its header says.
    public var duration: TimeInterval?

    public init(
        codec: Codec,
        timescale: Int,
        sampleRate: Double,
        channels: Int,
        magicCookie: Data,
        audioSpecificConfig: Data = Data(),
        leadIn: Int64 = 0,
        defaultPacketDuration: Int? = nil,
        duration: TimeInterval? = nil
    ) {
        self.codec = codec
        self.timescale = timescale
        self.sampleRate = sampleRate
        self.channels = channels
        self.magicCookie = magicCookie
        self.audioSpecificConfig = audioSpecificConfig
        self.leadIn = leadIn
        self.defaultPacketDuration = defaultPacketDuration
        self.duration = duration
    }
}

/// One compressed audio packet and where it sits on the stream's timeline.
public struct AudioPacket: Sendable, Equatable {
    /// In `AudioTrackInfo.timescale` units from the start of the stream.
    public var decodeTime: Int64
    public var duration: Int
    public var data: Data

    public init(decodeTime: Int64, duration: Int, data: Data) {
        self.decodeTime = decodeTime
        self.duration = duration
        self.data = data
    }
}

public enum DemuxEvent: Sendable, Equatable {
    /// The stream's header was read. Always comes before the first packets.
    case track(AudioTrackInfo)
    case packets([AudioPacket])
}

/// Turns the bytes a web player appends to a media source buffer into audio packets. Bytes may arrive in pieces of
/// any size; `reset()` mirrors the buffer's `abort()` (the player jumps elsewhere and starts a new segment).
public protocol AudioDemuxer: AnyObject {
    func feed(_ data: Data) throws -> [DemuxEvent]
    /// Forgets partly received data. The header read so far stays valid.
    func reset()
    var track: AudioTrackInfo? { get }
}

/// Picks the demuxer for a source buffer's MIME type (`audio/mp4; codecs="mp4a.40.2"`, `audio/webm; codecs="opus"`).
public enum AudioDemuxers {
    public static func make(mimeType: String) -> (any AudioDemuxer)? {
        let lower = mimeType.lowercased()
        guard lower.hasPrefix("audio/") else { return nil }
        if lower.hasPrefix("audio/mp4") { return FragmentedMP4Demuxer() }
        if lower.hasPrefix("audio/webm") { return WebMDemuxer() }
        return nil
    }
}
