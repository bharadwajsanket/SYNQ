// swift-tools-version: 5.10
import PackageDescription

let package = Package(
    name: "SYNQMac",
    platforms: [.macOS(.v13)],
    products: [.executable(name: "SYNQMac", targets: ["SYNQMac"])],
    targets: [
        .executableTarget(name: "SYNQMac"),
        .testTarget(name: "SYNQMacTests", dependencies: ["SYNQMac"]),
    ],
    swiftLanguageVersions: [.v5]
)
