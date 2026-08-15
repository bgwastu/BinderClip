// swift-tools-version: 5.10
import PackageDescription

let package = Package(
    name: "binderclip-mac",
    platforms: [.macOS(.v13)],
    products: [
        .executable(name: "BinderClip", targets: ["BinderClip"])
    ],
    dependencies: [
        .package(url: "https://github.com/sparkle-project/Sparkle", from: "2.6.0"),
        .package(url: "https://github.com/stasel/WebRTC.git", from: "151.0.0")
    ],
    targets: [
        .executableTarget(
            name: "BinderClip",
            dependencies: [
                .product(name: "Sparkle", package: "Sparkle"),
                .product(name: "WebRTC", package: "WebRTC")
            ],
            path: ".",
            exclude: ["Tests"],
            resources: [.copy("Resources")]
        ),
        .testTarget(
            name: "BinderClipTests",
            dependencies: ["BinderClip"],
            path: "Tests/Direct"
        )
    ]
)
