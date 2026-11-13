import Foundation

/// One rendition of a picture, as YouTube Music lists it.
public struct Thumbnail: Codable, Hashable, Sendable {
    public var url: URL
    public var width: Int
    public var height: Int

    public init(url: URL, width: Int, height: Int) {
        self.url = url
        self.width = width
        self.height = height
    }
}

/// A picture in several sizes. `thumbnails` is sorted smallest first.
public struct Artwork: Codable, Hashable, Sendable {
    public var thumbnails: [Thumbnail]

    public init(thumbnails: [Thumbnail] = []) {
        self.thumbnails = thumbnails.sorted { $0.width < $1.width }
    }

    public static let empty = Artwork()

    public var isEmpty: Bool { thumbnails.isEmpty }

    /// Width divided by height of the largest rendition (1 for covers, about 1.78 for video stills).
    public var aspectRatio: Double {
        guard let last = thumbnails.last, last.height > 0 else { return 1 }
        return Double(last.width) / Double(last.height)
    }

    /// The best URL for drawing at `pixelWidth` pixels wide.
    ///
    /// Google's image servers (`*.googleusercontent.com`, `yt3.ggpht.com`) resize on request, so for those the size in
    /// the URL is rewritten and any size can be had. For other hosts (video stills on `i.ytimg.com`) the smallest
    /// listed rendition that is wide enough is returned, or the largest one.
    public func url(pixelWidth: Int) -> URL? {
        guard let largest = thumbnails.last else { return nil }
        let wanted = max(pixelWidth, 1)
        if let resized = Artwork.resizedGoogleImageURL(largest, pixelWidth: wanted) {
            return resized
        }
        return (thumbnails.first { $0.width >= wanted } ?? largest).url
    }

    /// The largest rendition as listed.
    public var largestURL: URL? { thumbnails.last?.url }

    /// Rewrites `...=w120-h120-l90-rj` (or `=s120...`) to the wanted size. Returns nil for hosts that do not resize.
    static func resizedGoogleImageURL(_ thumbnail: Thumbnail, pixelWidth: Int) -> URL? {
        guard let host = thumbnail.url.host(), host.hasSuffix("googleusercontent.com") || host.hasSuffix("ggpht.com")
        else { return nil }
        let text = thumbnail.url.absoluteString
        guard let equals = text.lastIndex(of: "="), text[equals...].count < 64 else { return nil }
        let base = text[..<equals]
        let options = text[text.index(after: equals)...]
        let ratio = thumbnail.width > 0 ? Double(thumbnail.height) / Double(thumbnail.width) : 1
        let height = max(Int((Double(pixelWidth) * ratio).rounded()), 1)
        if options.hasPrefix("w") {
            // Keep the flags after the size ("-l90-rj", "-p-l90-rj").
            let flags = options.split(separator: "-").dropFirst(2).joined(separator: "-")
            let tail = flags.isEmpty ? "" : "-\(flags)"
            return URL(string: "\(base)=w\(pixelWidth)-h\(height)\(tail)")
        }
        if options.hasPrefix("s") {
            let flags = options.split(separator: "-").dropFirst(1).joined(separator: "-")
            let tail = flags.isEmpty ? "" : "-\(flags)"
            return URL(string: "\(base)=s\(pixelWidth)\(tail)")
        }
        return nil
    }
}
