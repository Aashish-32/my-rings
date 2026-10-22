// swift-tools-version:5.9
import PackageDescription

// OuraKit: the ring protocol and event decoders, ported from the Android app (which ports open_oura's oura-protocol crate).
// Pure Swift with no UI, so it can be unit-tested on a macOS CI runner without an iPhone.
let package = Package(
    name: "OuraKit",
    platforms: [.iOS(.v16), .macOS(.v13)],
    products: [.library(name: "OuraKit", targets: ["OuraKit"])],
    targets: [
        .target(name: "OuraKit"),
        .testTarget(name: "OuraKitTests", dependencies: ["OuraKit"], resources: [.copy("Resources")]),
    ]
)
