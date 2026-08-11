// swift-tools-version: 5.10
import PackageDescription

let package = Package(
    name: "binderclip-mac",
    platforms: [.macOS(.v13)],
    products: [
        .executable(name: "BinderClip", targets: ["BinderClip"])
    ],
    targets: [
        .executableTarget(
            name: "BinderClip",
            path: "Sources"
        ),
        .testTarget(
            name: "BinderClipTests",
            dependencies: ["BinderClip"],
            path: "Tests/BinderClipTests"
        )
    ]
)
