import BoomModels
import UIKit

/// Invented covers drawn on the device and kept as files, so the gallery shows real pictures without a network:
/// a mix of bright and dark ones to check the player's contrast. `-covers picsum` uses picsum.photos instead.
enum CoverArt {
    private static let palettes: [(UIColor, UIColor)] = [
        (UIColor(red: 0.98, green: 0.84, blue: 0.20, alpha: 1), UIColor(red: 0.99, green: 0.55, blue: 0.25, alpha: 1)),
        (UIColor(red: 0.10, green: 0.16, blue: 0.40, alpha: 1), UIColor(red: 0.45, green: 0.20, blue: 0.62, alpha: 1)),
        (UIColor(red: 0.95, green: 0.95, blue: 0.93, alpha: 1), UIColor(red: 0.80, green: 0.86, blue: 0.95, alpha: 1)),
        (UIColor(red: 0.05, green: 0.35, blue: 0.30, alpha: 1), UIColor(red: 0.20, green: 0.70, blue: 0.55, alpha: 1)),
        (UIColor(red: 0.70, green: 0.10, blue: 0.20, alpha: 1), UIColor(red: 0.98, green: 0.45, blue: 0.40, alpha: 1)),
        (UIColor(red: 0.04, green: 0.04, blue: 0.06, alpha: 1), UIColor(red: 0.22, green: 0.22, blue: 0.28, alpha: 1)),
        (UIColor(red: 0.55, green: 0.80, blue: 0.98, alpha: 1), UIColor(red: 0.20, green: 0.45, blue: 0.90, alpha: 1)),
        (UIColor(red: 0.95, green: 0.60, blue: 0.75, alpha: 1), UIColor(red: 0.55, green: 0.30, blue: 0.70, alpha: 1)),
    ]

    private static let usesPicsum = UserDefaults.standard.string(forKey: "covers") == "picsum"

    static func artwork(_ seed: String, width: Int = 600, height: Int = 600) -> Artwork {
        let url = usesPicsum
            ? URL(string: "https://picsum.photos/seed/\(seed)/\(width)/\(height)")
            : file(for: seed, width: width, height: height)
        guard let url else { return .empty }
        return Artwork(thumbnails: [Thumbnail(url: url, width: width, height: height)])
    }

    private static func file(for seed: String, width: Int, height: Int) -> URL? {
        let index = abs(seed.unicodeScalars.reduce(7) { $0 &* 31 &+ Int($1.value) }) % palettes.count
        let name = "cover-\(index)-\(width)x\(height).png"
        guard let folder = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first else { return nil }
        let url = folder.appending(path: name)
        if FileManager.default.fileExists(atPath: url.path()) { return url }
        let size = CGSize(width: width, height: height)
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        let image = UIGraphicsImageRenderer(size: size, format: format).image { context in
            let (top, bottom) = palettes[index]
            let cg = context.cgContext
            if let gradient = CGGradient(
                colorsSpace: CGColorSpaceCreateDeviceRGB(), colors: [top.cgColor, bottom.cgColor] as CFArray,
                locations: [0, 1]) {
                cg.drawLinearGradient(gradient, start: .zero, end: CGPoint(x: size.width, y: size.height), options: [])
            }
            UIColor.white.withAlphaComponent(0.22).setFill()
            let side = min(size.width, size.height)
            cg.fillEllipse(in: CGRect(
                x: size.width * 0.45, y: size.height * 0.15, width: side * 0.6, height: side * 0.6))
            UIColor.black.withAlphaComponent(0.18).setFill()
            cg.fill(CGRect(x: 0, y: size.height * 0.72, width: size.width, height: size.height * 0.06))
        }
        try? image.pngData()?.write(to: url)
        return url
    }
}
