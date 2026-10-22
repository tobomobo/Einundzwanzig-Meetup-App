// swift-tools-version: 6.1
import PackageDescription

let package = Package(
    name: "MeetupCore",
    platforms: [.iOS(.v17), .macOS(.v14)],
    products: [
        .library(name: "MeetupCore", targets: ["MeetupCore"]),
        .library(name: "MeetupNostr", targets: ["MeetupNostr"])
    ],
    dependencies: [
        .package(url: "https://github.com/21-DOT-DEV/swift-secp256k1", exact: "0.23.2"),
        .package(url: "https://github.com/krzyzanowskim/CryptoSwift", exact: "1.8.4")
    ],
    targets: [
        .target(name: "MeetupCore"),
        .target(name: "MeetupNostr", dependencies: [
            .product(name: "P256K", package: "swift-secp256k1"),
            .product(name: "CryptoSwift", package: "CryptoSwift")
        ]),
        .testTarget(name: "MeetupCoreTests", dependencies: ["MeetupCore"]),
        .testTarget(name: "MeetupNostrTests", dependencies: ["MeetupNostr"], resources: [
            .copy("Fixtures")
        ])
    ],
    swiftLanguageModes: [.v6]
)
