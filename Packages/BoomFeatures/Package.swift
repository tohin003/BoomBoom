// swift-tools-version: 6.2
// BoomFeatures: the screens (SwiftUI, iPhone only). Package.swift is owned by the lead.
//   BoomDesign     colours, type, spacing and the shared pieces every screen uses
//   FeatureBrowse  the app's frame (tabs, header) and the browsing screens: Home, Explore, Search, Library,
//                  albums, artists, playlists, settings
//   FeaturePlayer  the mini player, the full player (queue, lyrics, related) and the equaliser
// Build one module for the simulator, with your own derived data folder:
//   xcodebuild -scheme FeatureBrowse -destination 'platform=iOS Simulator,name=iPhone 17' \
//     -derivedDataPath ../../build/dd-<task> build      (run inside Packages/BoomFeatures)
import PackageDescription

let package = Package(
    name: "BoomFeatures",
    platforms: [.iOS(.v26)],
    products: [
        .library(name: "BoomDesign", targets: ["BoomDesign"]),
        .library(name: "FeatureBrowse", targets: ["FeatureBrowse"]),
        .library(name: "FeaturePlayer", targets: ["FeaturePlayer"]),
    ],
    dependencies: [
        .package(path: "../BoomCore"),
        .package(path: "../BoomEngine"),
    ],
    targets: [
        .target(
            name: "BoomDesign",
            dependencies: [
                .product(name: "BoomModels", package: "BoomCore"),
                .product(name: "BoomPlayer", package: "BoomEngine"),
            ]
        ),
        .target(
            name: "FeatureBrowse",
            dependencies: [
                "BoomDesign",
                .product(name: "BoomModels", package: "BoomCore"),
                .product(name: "BoomPlayer", package: "BoomEngine"),
            ]
        ),
        .target(
            name: "FeaturePlayer",
            dependencies: [
                "BoomDesign",
                .product(name: "BoomModels", package: "BoomCore"),
                .product(name: "BoomPlayer", package: "BoomEngine"),
            ]
        ),
    ],
    swiftLanguageModes: [.v6]
)
