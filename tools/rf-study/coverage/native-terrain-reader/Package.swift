// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "TerrainReaderStudy",
    platforms: [.macOS(.v13), .iOS(.v16)],
    products: [.library(name: "TerrainReader", targets: ["TerrainReader"])],
    targets: [
        .target(name: "TerrainReader"),
        .testTarget(name: "TerrainReaderTests", dependencies: ["TerrainReader"])
    ]
)
