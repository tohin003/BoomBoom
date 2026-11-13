import BoomModels
import SwiftUI
import UIKit

/// Fetches and keeps pictures: in memory for scrolling, on disk between launches.
public actor ImagePipeline {
    public static let shared = ImagePipeline()

    // NSCache does its own locking, so the cache may be read without hopping onto the actor.
    private nonisolated(unsafe) let memory: NSCache<NSURL, UIImage> = {
        let cache = NSCache<NSURL, UIImage>()
        cache.totalCostLimit = 96 << 20
        return cache
    }()
    private let session: URLSession
    private var inFlight: [URL: Task<UIImage, Error>] = [:]

    public init() {
        let configuration = URLSessionConfiguration.default
        configuration.urlCache = URLCache(memoryCapacity: 16 << 20, diskCapacity: 512 << 20, directory: nil)
        configuration.requestCachePolicy = .returnCacheDataElseLoad
        configuration.httpMaximumConnectionsPerHost = 8
        configuration.timeoutIntervalForRequest = 20
        session = URLSession(configuration: configuration)
    }

    /// A picture already in memory, without waiting.
    public nonisolated func cached(_ url: URL) -> UIImage? {
        memory.object(forKey: url as NSURL)
    }

    public func image(for url: URL) async throws -> UIImage {
        if let hit = memory.object(forKey: url as NSURL) { return hit }
        if let running = inFlight[url] { return try await running.value }
        let task = Task<UIImage, Error> { [session] in
            let (data, _) = try await session.data(from: url)
            guard let decoded = UIImage(data: data) else { throw URLError(.cannotDecodeContentData) }
            return await decoded.byPreparingForDisplay() ?? decoded
        }
        inFlight[url] = task
        defer { inFlight[url] = nil }
        let image = try await task.value
        let cost = Int(image.size.width * image.scale * image.size.height * image.scale * 4)
        memory.setObject(image, forKey: url as NSURL, cost: cost)
        return image
    }

    /// The picture's overall colour, darkened so white text reads on it. For the player's background.
    public nonisolated func backdropColor(of image: UIImage) -> Color {
        guard let cgImage = image.cgImage else { return BoomColor.surface }
        var pixel = [UInt8](repeating: 0, count: 4)
        guard let context = CGContext(
            data: &pixel, width: 1, height: 1, bitsPerComponent: 8, bytesPerRow: 4,
            space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)
        else { return BoomColor.surface }
        context.interpolationQuality = .medium
        context.draw(cgImage, in: CGRect(x: 0, y: 0, width: 1, height: 1))
        let red = Double(pixel[0]) / 255, green = Double(pixel[1]) / 255, blue = Double(pixel[2]) / 255
        // Keep the hue, cap the brightness.
        let brightest = max(red, green, blue, 0.001)
        let scale = min(1, 0.42 / brightest)
        return Color(.sRGB, red: red * scale, green: green * scale, blue: blue * scale, opacity: 1)
    }
}

/// A cover, a video still or an artist's picture, loaded at the size it is drawn.
public struct ArtworkView: View {
    private let artwork: Artwork
    private let width: CGFloat
    private let height: CGFloat
    private let cornerRadius: CGFloat
    private let isCircle: Bool

    @Environment(\.displayScale) private var displayScale
    @State private var image: UIImage?

    /// - Parameters:
    ///   - width: Points. The picture is fetched at this width times the screen's scale.
    ///   - height: Points; the same as `width` when left out.
    ///   - isCircle: Artists are round.
    public init(
        _ artwork: Artwork, width: CGFloat, height: CGFloat? = nil,
        cornerRadius: CGFloat = BoomMetrics.coverRadius, isCircle: Bool = false
    ) {
        self.artwork = artwork
        self.width = width
        self.height = height ?? width
        self.cornerRadius = cornerRadius
        self.isCircle = isCircle
    }

    private var url: URL? {
        artwork.url(pixelWidth: Int((max(width, height * artwork.aspectRatio) * displayScale).rounded(.up)))
    }

    public var body: some View {
        ZStack {
            BoomColor.surfaceRaised
            if let image {
                Image(uiImage: image)
                    .resizable()
                    .aspectRatio(contentMode: .fill)
                    .transition(.opacity)
            } else {
                Image(systemName: isCircle ? "person.fill" : "music.note")
                    .font(.system(size: min(width, height) * 0.36))
                    .foregroundStyle(BoomColor.textTertiary)
            }
        }
        .frame(width: width, height: height)
        .clipShape(isCircle ? AnyShape(Circle()) : AnyShape(RoundedRectangle(cornerRadius: cornerRadius)))
        .accessibilityHidden(true)
        .task(id: url) {
            guard let url else {
                image = nil
                return
            }
            if let hit = ImagePipeline.shared.cached(url) {
                image = hit
                return
            }
            image = nil
            if let loaded = try? await ImagePipeline.shared.image(for: url), !Task.isCancelled {
                withAnimation(.easeOut(duration: 0.15)) { image = loaded }
            }
        }
    }
}
