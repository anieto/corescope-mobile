import Foundation
import CoreGraphics
import ImageIO
import UniformTypeIdentifiers

enum CoverageTilePNGFailure: Error, Equatable { case invalidPixels, encoding, cancelled }

/// Encodes one 256×256 straight-alpha RGBA tile without resizing or interpolation.
enum CoverageTilePNG {
    static func encode(_ rgba: Data, cancelled: () -> Bool = { false }) throws -> Data {
        guard rgba.count == 256 * 256 * 4 else { throw CoverageTilePNGFailure.invalidPixels }
        if cancelled() { throw CoverageTilePNGFailure.cancelled }
        guard let provider = CGDataProvider(data: rgba as CFData),
              let space = CGColorSpace(name: CGColorSpace.sRGB),
              let image = CGImage(width: 256, height: 256, bitsPerComponent: 8, bitsPerPixel: 32,
                bytesPerRow: 256 * 4, space: space,
                bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.last.rawValue).union(.byteOrder32Big),
                provider: provider, decode: nil, shouldInterpolate: false, intent: .defaultIntent) else {
            throw CoverageTilePNGFailure.encoding
        }
        let output = NSMutableData()
        guard let destination = CGImageDestinationCreateWithData(output, UTType.png.identifier as CFString, 1, nil) else {
            throw CoverageTilePNGFailure.encoding
        }
        CGImageDestinationAddImage(destination, image, nil)
        guard CGImageDestinationFinalize(destination) else { throw CoverageTilePNGFailure.encoding }
        if cancelled() { throw CoverageTilePNGFailure.cancelled }
        return output as Data
    }
}

extension CoverageRaster {
    func pngTile(zoom: Int, x: Int, y: Int, cancelled: () -> Bool = { false }) throws -> Data {
        try CoverageTilePNG.encode(tile(zoom: zoom, x: x, y: y, cancelled: cancelled), cancelled: cancelled)
    }
}
