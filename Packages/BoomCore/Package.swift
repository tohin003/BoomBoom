// swift-tools-version: 6.2
// BoomCore: plain models and the YouTube Music (InnerTube) data client. Foundation only, so it builds and tests on
// the Mac with `swift test`. Package.swift is owned by the lead; ask before adding targets or dependencies.
import PackageDescription

let package = Package(
    name: "BoomCore",
    platforms: [.iOS(.v26), .macOS(.v26)],
    products: [
        .library(name: "BoomModels", targets: ["BoomModels"]),
        .library(name: "InnerTube", targets: ["InnerTube"]),
    ],
    targets: [
        .target(name: "BoomModels"),
        .target(name: "InnerTube", dependencies: ["BoomModels"]),
        .testTarget(name: "BoomModelsTests", dependencies: ["BoomModels"]),
        .testTarget(
            name: "InnerTubeTests",
            dependencies: ["InnerTube", "BoomModels"],
            resources: [.copy("Fixtures")]
        ),
    ],
    swiftLanguageModes: [.v6]
)
