// swift-tools-version: 6.2
// BoomEngine: everything between YouTube's player and the listener's ears. Owned by the lead.
//   CBoomAudio  the real-time side in portable C: a lock-free ring buffer, the render routine, and the equaliser,
//               amplifier, limiter, meters and spectrum (BoomDSP)
//   BoomAudio   demuxers (fragmented MP4, WebM), decoding, the AVAudioEngine graph
//   BoomPlayer  what the screens talk to: queue and playback state, equaliser settings, the sign-in session
import PackageDescription

let package = Package(
    name: "BoomEngine",
    platforms: [.iOS(.v26), .macOS(.v26)],
    products: [
        .library(name: "BoomAudio", targets: ["BoomAudio"]),
        .library(name: "BoomPlayer", targets: ["BoomPlayer"]),
    ],
    dependencies: [
        .package(path: "../BoomCore"),
    ],
    targets: [
        .target(name: "CBoomAudio"),
        .target(name: "BoomAudio", dependencies: ["CBoomAudio"]),
        .target(
            name: "BoomPlayer",
            dependencies: [
                "BoomAudio",
                .product(name: "BoomModels", package: "BoomCore"),
            ],
            resources: [.process("Resources")]
        ),
        .testTarget(
            name: "BoomAudioTests",
            dependencies: ["BoomAudio", "CBoomAudio"],
            resources: [.copy("Fixtures")]
        ),
        .testTarget(
            name: "BoomPlayerTests",
            dependencies: ["BoomPlayer", .product(name: "BoomModels", package: "BoomCore")]
        ),
    ],
    swiftLanguageModes: [.v6]
)
