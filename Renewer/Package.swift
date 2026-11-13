// swift-tools-version: 6.2
// BoomBoom Renewer: a Mac menu-bar item that shows how long the iPhone app's free 7-day signing has left and renews
// it through ../scripts/renew.sh. Its own package: the app, Packages/ and Tools/ never see it.
//   swift build --package-path Renewer -c release --product BoomBoomRenewer
//   swift test --package-path Renewer
// Then Renewer/install.sh puts it in place. See Renewer/README.md.
import PackageDescription

let package = Package(
    name: "BoomBoomRenewer",
    platforms: [.macOS(.v26)],
    products: [
        .executable(name: "BoomBoomRenewer", targets: ["BoomBoomRenewer"]),
    ],
    targets: [
        // Everything that decides or parses, as plain values: days left, the decision to renew, devicectl's device
        // list, renew.sh's output and exit codes, the state file, profiles' dates, the log's redaction. No UI.
        .target(name: "RenewerCore"),
        // The menu-bar app (SwiftUI MenuBarExtra, LSUIElement): scheduling, subprocesses, notifications.
        .executableTarget(name: "BoomBoomRenewer", dependencies: ["RenewerCore"]),
        .testTarget(name: "RenewerCoreTests", dependencies: ["RenewerCore"]),
    ],
    swiftLanguageModes: [.v6]
)
