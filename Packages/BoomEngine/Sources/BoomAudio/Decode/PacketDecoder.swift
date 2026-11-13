import AudioToolbox
import AVFoundation
import Foundation

public enum DecodeError: Error, Sendable, Equatable {
    /// The system has no decoder for this stream as described.
    case unsupported(String)
    /// A packet could not be decoded.
    case failed(String)
}

/// Decodes compressed audio packets (AAC or Opus) to 32-bit float PCM with the system's decoders.
///
/// The decoder keeps state between calls, so packets must be given in stream order. After a jump, call `reset()`
/// and start a few packets early (`prerollPackets`), throwing away what those produce: the first packets after a
/// reset do not decode cleanly on their own.
public final class PacketDecoder {
    /// Planar 32-bit float at the stream's own sample rate.
    public let outputFormat: AVAudioFormat
    /// PCM frames one packet decodes to.
    public let framesPerPacket: Int
    /// Packets to decode and discard before the first wanted one after `reset()`.
    public let prerollPackets: Int

    private let inputFormat: AVAudioFormat
    private let converter: AVAudioConverter

    public init(track: AudioTrackInfo) throws {
        var description = try Self.streamDescription(for: track)
        guard let input = AVAudioFormat(streamDescription: &description) else {
            throw DecodeError.unsupported("no audio format for \(track.codec.rawValue)")
        }
        let channels = AVAudioChannelCount(max(1, min(Int(description.mChannelsPerFrame), 2)))
        guard let output = AVAudioFormat(
            commonFormat: .pcmFormatFloat32, sampleRate: description.mSampleRate, channels: channels,
            interleaved: false),
            let converter = AVAudioConverter(from: input, to: output)
        else {
            throw DecodeError.unsupported("no decoder for \(track.codec.rawValue) at \(description.mSampleRate) Hz")
        }
        if !track.magicCookie.isEmpty { converter.magicCookie = track.magicCookie }
        inputFormat = input
        outputFormat = output
        self.converter = converter
        framesPerPacket = Int(description.mFramesPerPacket)
        // AAC overlaps each packet with the one before; Opus needs 80 ms to settle (its SeekPreRoll).
        prerollPackets = track.codec == .opus ? max(4, 3840 / max(1, Int(description.mFramesPerPacket))) : 2
    }

    /// Forgets everything decoded so far. Call before decoding from a different place in the stream.
    public func reset() {
        converter.reset()
    }

    /// Decodes packets that follow on from the previous call. Returns nil when they produce no audio yet.
    public func decode(_ packets: some Collection<Data>) throws -> AVAudioPCMBuffer? {
        guard !packets.isEmpty else { return nil }
        let totalBytes = packets.reduce(0) { $0 + $1.count }
        let largest = packets.reduce(0) { max($0, $1.count) }
        guard totalBytes > 0 else { return nil }

        let compressed = AVAudioCompressedBuffer(
            format: inputFormat, packetCapacity: AVAudioPacketCount(packets.count), maximumPacketSize: largest)
        guard let descriptions = compressed.packetDescriptions else {
            throw DecodeError.failed("no room for packet sizes")
        }
        var offset = 0
        for (index, packet) in packets.enumerated() {
            packet.withUnsafeBytes { bytes in
                guard let base = bytes.baseAddress else { return }
                compressed.data.advanced(by: offset).copyMemory(from: base, byteCount: bytes.count)
            }
            descriptions[index] = AudioStreamPacketDescription(
                mStartOffset: Int64(offset), mVariableFramesInPacket: 0, mDataByteSize: UInt32(packet.count))
            offset += packet.count
        }
        compressed.byteLength = UInt32(offset)
        compressed.packetCount = AVAudioPacketCount(packets.count)

        // Room for every packet's frames, plus one packet of slack for what the decoder was still holding.
        let capacity = AVAudioFrameCount((packets.count + 1) * framesPerPacket)
        guard let output = AVAudioPCMBuffer(pcmFormat: outputFormat, frameCapacity: capacity) else {
            throw DecodeError.failed("no room for \(capacity) frames")
        }

        var handedOver = false
        var failure: NSError?
        let status = converter.convert(to: output, error: &failure) { _, inputStatus in
            if handedOver {
                // More will come on the next call: keep the decoder's state.
                inputStatus.pointee = .noDataNow
                return nil
            }
            handedOver = true
            inputStatus.pointee = .haveData
            return compressed
        }
        if status == .error {
            throw DecodeError.failed(failure?.localizedDescription ?? "the decoder refused the packets")
        }
        return output.frameLength > 0 ? output : nil
    }

    // MARK: Stream descriptions

    private static func streamDescription(for track: AudioTrackInfo) throws -> AudioStreamBasicDescription {
        switch track.codec {
        case .opus:
            return AudioStreamBasicDescription(
                mSampleRate: 48_000,
                mFormatID: kAudioFormatOpus,
                mFormatFlags: 0,
                mBytesPerPacket: 0,
                mFramesPerPacket: UInt32(track.defaultPacketDuration ?? 960),
                mBytesPerFrame: 0,
                mChannelsPerFrame: UInt32(max(1, track.channels)),
                mBitsPerChannel: 0,
                mReserved: 0)
        case .aac:
            // Ask the system what the setup record describes: it knows the layers of HE-AAC and their rates.
            if let described = aacDescription(cookie: track.magicCookie) { return described }
            guard track.sampleRate > 0, track.channels > 0 else {
                throw DecodeError.unsupported("AAC without a sample rate")
            }
            return AudioStreamBasicDescription(
                mSampleRate: track.sampleRate,
                mFormatID: kAudioFormatMPEG4AAC,
                mFormatFlags: 0,
                mBytesPerPacket: 0,
                mFramesPerPacket: 1024,
                mBytesPerFrame: 0,
                mChannelsPerFrame: UInt32(track.channels),
                mBitsPerChannel: 0,
                mReserved: 0)
        }
    }

    private static func aacDescription(cookie: Data) -> AudioStreamBasicDescription? {
        guard !cookie.isEmpty else { return nil }
        return cookie.withUnsafeBytes { bytes -> AudioStreamBasicDescription? in
            guard let base = bytes.baseAddress else { return nil }
            var info = AudioFormatInfo(
                mASBD: AudioStreamBasicDescription(
                    mSampleRate: 0, mFormatID: kAudioFormatMPEG4AAC, mFormatFlags: 0, mBytesPerPacket: 0,
                    mFramesPerPacket: 0, mBytesPerFrame: 0, mChannelsPerFrame: 0, mBitsPerChannel: 0, mReserved: 0),
                mMagicCookie: base,
                mMagicCookieSize: UInt32(bytes.count))
            let infoSize = UInt32(MemoryLayout<AudioFormatInfo>.size)
            var listSize: UInt32 = 0
            guard AudioFormatGetPropertyInfo(kAudioFormatProperty_FormatList, infoSize, &info, &listSize) == noErr,
                  listSize >= UInt32(MemoryLayout<AudioFormatListItem>.size)
            else { return nil }
            let count = Int(listSize) / MemoryLayout<AudioFormatListItem>.size
            var items = [AudioFormatListItem](
                repeating: AudioFormatListItem(mASBD: info.mASBD, mChannelLayoutTag: 0), count: count)
            guard AudioFormatGetProperty(kAudioFormatProperty_FormatList, infoSize, &info, &listSize, &items) == noErr
            else { return nil }
            // The list runs from the richest layer to the plainest. Take the first one this system can decode.
            for item in items where item.mASBD.mSampleRate > 0 && item.mASBD.mChannelsPerFrame > 0 {
                return item.mASBD
            }
            return nil
        }
    }
}
