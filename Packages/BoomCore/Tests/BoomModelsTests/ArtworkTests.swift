import Foundation
import Testing
@testable import BoomModels

@Suite struct ArtworkTests {
    @Test func googleImagesAreResized() throws {
        let art = Artwork(thumbnails: [
            Thumbnail(url: URL(string: "https://lh3.googleusercontent.com/abc=w60-h60-l90-rj")!, width: 60, height: 60),
            Thumbnail(url: URL(string: "https://lh3.googleusercontent.com/abc=w120-h120-l90-rj")!, width: 120, height: 120),
        ])
        #expect(art.url(pixelWidth: 544)?.absoluteString == "https://lh3.googleusercontent.com/abc=w544-h544-l90-rj")
    }

    @Test func sizeOnlyGoogleImagesAreResized() throws {
        let art = Artwork(thumbnails: [
            Thumbnail(url: URL(string: "https://yt3.ggpht.com/xyz=s88-c-k-c0x00ffffff-no-rj")!, width: 88, height: 88)
        ])
        #expect(art.url(pixelWidth: 300)?.absoluteString == "https://yt3.ggpht.com/xyz=s300-c-k-c0x00ffffff-no-rj")
    }

    @Test func videoStillsPickTheSmallestThatFits() throws {
        let art = Artwork(thumbnails: [
            Thumbnail(url: URL(string: "https://i.ytimg.com/vi/x/sddefault.jpg?sqp=a")!, width: 640, height: 480),
            Thumbnail(url: URL(string: "https://i.ytimg.com/vi/x/default.jpg")!, width: 120, height: 90),
            Thumbnail(url: URL(string: "https://i.ytimg.com/vi/x/hqdefault.jpg")!, width: 480, height: 360),
        ])
        #expect(art.url(pixelWidth: 300)?.lastPathComponent == "hqdefault.jpg")
        #expect(art.url(pixelWidth: 2000)?.lastPathComponent == "sddefault.jpg")
        #expect(Artwork.empty.url(pixelWidth: 100) == nil)
    }

    @Test func artistLineJoinsNames() {
        let song = Song(videoId: "v", title: "t", artists: [ArtistRef(name: "A"), ArtistRef(name: "B"), ArtistRef(name: "C")])
        #expect(song.artistLine == "A, B & C")
    }
}
