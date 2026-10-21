// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "MeetupCore",
    platforms: [.iOS(.v17), .macOS(.v14)],
    products: [.library(name: "MeetupCore", targets: ["MeetupCore"])],
    targets: [
        .target(name: "MeetupCore"),
        .testTarget(name: "MeetupCoreTests", dependencies: ["MeetupCore"])
    ],
    swiftLanguageModes: [.v6]
)
