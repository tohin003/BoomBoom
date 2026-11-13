import BoomModels
import CoreGraphics
import Foundation
import ImageIO
import UniformTypeIdentifiers

/// The invented artists, albums, songs, videos and playlists behind `PreviewMusicAPI`. Everything is derived from
/// fixed word lists, so the same ids give the same items on every launch.
final class PreviewCatalog: Sendable {
    let artists: [ArtistItem]
    let albums: [AlbumItem]
    /// Album id → its tracks.
    let tracksByAlbum: [String: [Song]]
    /// Every album track, in catalog order.
    let songs: [Song]
    let videos: [Song]
    let playlists: [PlaylistItem]
    /// Video id → song or video.
    let songsById: [String: Song]
    let artistsById: [String: ArtistItem]
    let albumsById: [String: AlbumItem]
    let playlistsById: [String: PlaylistItem]

    static let artistNames = [
        "Juniper Vale", "Neon Harbor", "Mira Solace", "Kaito Rivers", "Velvet Static", "Sable & Pine",
        "Lumen Drive", "Asha Monroe", "Cobalt Youth", "Marlowe Grey", "Sunday Arcade", "Tidewater Choir",
        "Oro Lune", "Fennel Park",
    ]

    static let albumTitles = [
        "Afterhours Atlas", "Tin Roof Psalms", "Kodachrome Weather", "Small Hours", "Field Notes for Lovers",
        "The Long Exhale", "Harbor Lights", "Sodium Glow", "Weekend Cartography", "Blue Hour Radio",
        "Paper Planets", "Analog Heart", "Distant Shores", "Coastline Theory", "Night Bus Hymns",
        "Saltwater Diaries", "Bright Static", "Gardens of Concrete", "Heatwave Postcards", "Glow in the Margins",
        "Second Summer", "Low Orbit", "Porchlight", "Wide Awake in Lisbon", "Soft Focus", "Velour",
        "The Quiet Year", "Holograms & Honey", "Ferris Wheel Season", "Lemon Light", "Signal Fires",
        "Room Tone", "Telephone Weather", "Arcadia Lane", "Midnight Errands", "Cloud Atlas Club",
        "Overgrown", "Seaglass", "Rooftop Static", "Late Bloomer", "Every Shade of Blue", "Northbound",
    ]

    static let firstWords = [
        "Paper", "Neon", "Velvet", "Golden", "Silver", "Midnight", "Hollow", "Static", "Copper", "Glass",
        "Electric", "Quiet", "Wild", "Lucid", "Amber", "Crimson", "Salt", "Cherry", "Lonely", "Summer",
        "Winter", "Northern", "Broken", "Slow",
    ]

    static let secondWords = [
        "Moons", "Hearts", "Rivers", "Skies", "Lights", "Arcade", "Bloom", "Highway", "Letters", "Fever",
        "Tide", "Garden", "Satellites", "Echoes", "Parade", "Signals", "Dreams", "Waves", "Ghosts", "Avenue",
        "Weather", "Season", "Machines", "Horizon",
    ]

    static let playlistTitles: [(String, String)] = [
        ("Supermix", "Mix • Juniper Vale, Neon Harbor and more"),
        ("Discover Mix", "Mix • Fresh picks for you"),
        ("Chill Mix", "Mix • Mira Solace, Oro Lune and more"),
        ("New Release Mix", "Mix • Updated every Friday"),
        ("Late Night Drive", "Playlist • BoomBoom • 64 songs"),
        ("Sunday Slow-down", "Playlist • BoomBoom • 48 songs"),
        ("Indie Lift", "Playlist • BoomBoom • 70 songs"),
        ("Focus Flow", "Playlist • BoomBoom • 55 songs"),
        ("Workout Fuel", "Playlist • BoomBoom • 60 songs"),
        ("Rainy Window", "Playlist • Nadia K • 41 songs"),
        ("Golden Hour Pop", "Playlist • BoomBoom • 52 songs"),
        ("Throwback Anthems", "Playlist • Theo M • 87 songs"),
        ("Coffeehouse Acoustic", "Playlist • BoomBoom • 45 songs"),
        ("Synthwave Nights", "Playlist • Ravi P • 66 songs"),
    ]

    init() {
        var artists: [ArtistItem] = []
        var albums: [AlbumItem] = []
        var tracksByAlbum: [String: [Song]] = [:]
        var songs: [Song] = []
        var videos: [Song] = []
        var titleIndex = 0
        for (artistIndex, name) in Self.artistNames.enumerated() {
            let artistId = "UCbb" + Self.slug(name)
            let millions = Double(3 + (artistIndex * 37) % 90) / 10
            let artist = ArtistItem(
                id: artistId, name: name, artwork: Self.square(artistId),
                detailText: String(format: "%.1fM subscribers", millions))
            artists.append(artist)
            let ref = ArtistRef(name: name, id: artistId)
            for albumIndex in 0..<3 {
                let title = Self.albumTitles[(artistIndex * 3 + albumIndex) % Self.albumTitles.count]
                let albumId = "MPREb_" + Self.slug(title)
                let types = ["Album", "Album", "EP", "Single"]
                let type = albumIndex == 2 ? types[(artistIndex + 2) % 4] : "Album"
                let album = AlbumItem(
                    id: albumId, playlistId: "OLAK5uy_" + Self.slug(title), title: title, artists: [ref],
                    year: String(2026 - (artistIndex + albumIndex * 3) % 9), typeText: type,
                    artwork: Self.square(albumId), isExplicit: (artistIndex + albumIndex) % 5 == 1)
                albums.append(album)
                let count = type == "Single" ? 2 : type == "EP" ? 5 : 9 + (artistIndex + albumIndex) % 5
                var tracks: [Song] = []
                for _ in 0..<count {
                    let songTitle = Self.songTitle(titleIndex)
                    let videoId = "bb" + String(format: "%05d", titleIndex)
                    var credited = [ref]
                    if titleIndex % 6 == 4 {
                        let guest = Self.artistNames[(artistIndex + 5) % Self.artistNames.count]
                        credited.append(ArtistRef(name: guest, id: "UCbb" + Self.slug(guest)))
                    }
                    tracks.append(
                        Song(
                            videoId: videoId, title: songTitle, artists: credited,
                            album: AlbumRef(name: title, id: albumId),
                            duration: TimeInterval(148 + (titleIndex * 37) % 150), artwork: album.artwork,
                            isExplicit: titleIndex % 7 == 3, kind: .song,
                            playsText: Self.countText(titleIndex, unit: "plays"),
                            likeStatus: titleIndex % 9 == 0 ? .like : .indifferent,
                            isAvailable: titleIndex % 41 != 40))
                    titleIndex += 1
                }
                tracksByAlbum[albumId] = tracks
                songs.append(contentsOf: tracks)
            }
            for videoIndex in 0..<3 {
                let source = songs[songs.count - 1 - videoIndex * 3]
                let videoId = "bv" + String(format: "%05d", artistIndex * 3 + videoIndex)
                let suffix = ["(Official Video)", "(Live Session)", "(Visualizer)"][videoIndex]
                videos.append(
                    Song(
                        videoId: videoId, title: "\(source.title) \(suffix)", artists: [ref],
                        duration: (source.duration ?? 200) + 12, artwork: Self.wide(videoId),
                        isExplicit: source.isExplicit, kind: .video,
                        playsText: Self.countText(artistIndex * 3 + videoIndex + 5, unit: "views")))
            }
        }
        let playlists = Self.playlistTitles.enumerated().map { index, entry in
            PlaylistItem(
                id: (index < 4 ? "RDTMAK5uy_bb" : "PLbb_") + Self.slug(entry.0), title: entry.0,
                detailText: entry.1, artwork: Self.square("pl" + Self.slug(entry.0)))
        }
        self.artists = artists
        self.albums = albums
        self.tracksByAlbum = tracksByAlbum
        self.songs = songs
        self.videos = videos
        self.playlists = playlists
        var byId: [String: Song] = [:]
        for song in songs + videos { byId[song.videoId] = song }
        songsById = byId
        artistsById = Dictionary(uniqueKeysWithValues: artists.map { ($0.id, $0) })
        albumsById = Dictionary(albums.map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
        playlistsById = Dictionary(uniqueKeysWithValues: playlists.map { ($0.id, $0) })
    }

    // MARK: Picking

    /// `count` songs starting at a seed-dependent place, wrapping around, skipping greyed-out ones.
    func songs(seed: Int, count: Int) -> [Song] {
        let pool = songs.filter(\.isAvailable)
        let start = abs(seed * 31) % pool.count
        return (0..<count).map { pool[(start + $0 * 7) % pool.count] }
    }

    func videos(seed: Int, count: Int) -> [Song] {
        (0..<count).map { videos[(abs(seed) + $0 * 5) % videos.count] }
    }

    func albums(seed: Int, count: Int) -> [AlbumItem] {
        (0..<count).map { albums[(abs(seed) * 3 + $0 * 4) % albums.count] }
    }

    func artists(seed: Int, count: Int) -> [ArtistItem] {
        (0..<min(count, artists.count)).map { artists[(abs(seed) + $0 * 3) % artists.count] }
    }

    func playlists(seed: Int, count: Int) -> [PlaylistItem] {
        (0..<min(count, playlists.count)).map { playlists[(abs(seed) + $0) % playlists.count] }
    }

    func albums(of artistId: String) -> [AlbumItem] {
        albums.filter { $0.artists.first?.id == artistId }
    }

    func songs(of artistId: String) -> [Song] {
        songs.filter { $0.artists.contains { $0.id == artistId } && $0.isAvailable }
    }

    func videos(of artistId: String) -> [Song] {
        videos.filter { $0.artists.first?.id == artistId }
    }

    // MARK: Making

    static func songTitle(_ index: Int) -> String {
        let count = firstWords.count
        let first = index % count
        let second = (index / count + first * 5) % secondWords.count
        return "\(firstWords[first]) \(secondWords[second])"
    }

    static func slug(_ text: String) -> String {
        String(text.lowercased().unicodeScalars.filter { CharacterSet.alphanumerics.contains($0) }.map(Character.init))
    }

    /// A hash that stays the same between launches (Swift's own does not).
    static func stableHash(_ text: String) -> Int {
        text.unicodeScalars.reduce(5381) { ($0 &* 33 &+ Int($1.value)) & 0x7FFF_FFFF }
    }

    static func countText(_ seed: Int, unit: String) -> String {
        let value = 1 + (seed * 53) % 480
        return value >= 100 ? "\(value / 10)M \(unit)" : "\(value * 10)K \(unit)"
    }

    static func square(_ seed: String) -> Artwork {
        PreviewArt.artwork(seed: seed, pixels: (24, 24), points: (400, 400))
    }

    static func wide(_ seed: String) -> Artwork {
        PreviewArt.artwork(seed: seed, pixels: (32, 18), points: (640, 360))
    }

    static func banner(_ seed: String) -> Artwork {
        PreviewArt.artwork(seed: seed, pixels: (36, 24), points: (1080, 720))
    }
}

/// Invented cover art that needs no network: a tiny two-colour picture with a soft disc, encoded as a `data:` URL
/// (URLSession loads those like any other). Drawn larger, the few pixels blur into a smooth gradient.
enum PreviewArt {
    static func artwork(seed: String, pixels: (Int, Int), points: (Int, Int)) -> Artwork {
        guard let url = dataURL(seed: seed, width: pixels.0, height: pixels.1) else { return .empty }
        return Artwork(thumbnails: [Thumbnail(url: url, width: points.0, height: points.1)])
    }

    private static func dataURL(seed: String, width: Int, height: Int) -> URL? {
        let hash = PreviewCatalog.stableHash(seed)
        let hue = Double(hash % 360) / 360
        let first = rgb(hue: hue, saturation: 0.62, brightness: 0.92)
        let second = rgb(hue: (hue + 0.12 + Double(hash % 7) / 30).truncatingRemainder(dividingBy: 1),
                         saturation: 0.75, brightness: 0.5)
        let disc = rgb(hue: (hue + 0.5).truncatingRemainder(dividingBy: 1), saturation: 0.4, brightness: 1)
        let space = CGColorSpaceCreateDeviceRGB()
        guard let context = CGContext(
            data: nil, width: width, height: height, bitsPerComponent: 8, bytesPerRow: 0, space: space,
            bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue),
            let gradient = CGGradient(colorsSpace: space, colors: [first, second] as CFArray, locations: [0, 1])
        else { return nil }
        context.drawLinearGradient(
            gradient, start: .zero, end: CGPoint(x: width, y: height), options: [.drawsAfterEndLocation])
        context.setFillColor(disc.copy(alpha: 0.75) ?? disc)
        let size = Double(min(width, height)) * (0.35 + Double(hash % 5) / 20)
        let x = Double(hash % 97) / 97 * (Double(width) - size)
        let y = Double((hash / 97) % 89) / 89 * (Double(height) - size)
        context.fillEllipse(in: CGRect(x: x, y: y, width: size, height: size))
        guard let image = context.makeImage() else { return nil }
        let data = NSMutableData()
        guard let destination = CGImageDestinationCreateWithData(data, UTType.png.identifier as CFString, 1, nil)
        else { return nil }
        CGImageDestinationAddImage(destination, image, nil)
        guard CGImageDestinationFinalize(destination) else { return nil }
        return URL(string: "data:image/png;base64," + (data as Data).base64EncodedString())
    }

    private static func rgb(hue: Double, saturation: Double, brightness: Double) -> CGColor {
        let sector = hue * 6, index = Int(sector) % 6, fraction = sector - Double(Int(sector))
        let p = brightness * (1 - saturation)
        let q = brightness * (1 - saturation * fraction)
        let t = brightness * (1 - saturation * (1 - fraction))
        let (red, green, blue): (Double, Double, Double) = switch index {
        case 0: (brightness, t, p)
        case 1: (q, brightness, p)
        case 2: (p, brightness, t)
        case 3: (p, q, brightness)
        case 4: (t, p, brightness)
        default: (brightness, p, q)
        }
        return CGColor(srgbRed: red, green: green, blue: blue, alpha: 1)
    }
}
