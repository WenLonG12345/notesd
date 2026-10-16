import CoreGraphics
import Foundation
import ImageIO
import Testing
import UniformTypeIdentifiers

@testable import NotesCore

struct PrepareImageTests {
    private func image(_ w: Int, _ h: Int, alpha: Bool) -> CGImage {
        let ctx = CGContext(
            data: nil, width: w, height: h, bitsPerComponent: 8, bytesPerRow: 0,
            space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
        ctx.setFillColor(CGColor(red: 1, green: 0, blue: 0, alpha: alpha ? 0.5 : 1))
        ctx.fill(CGRect(x: 0, y: 0, width: w, height: h))
        return ctx.makeImage()!
    }

    private func encode(_ image: CGImage, _ type: UTType, _ props: [CFString: Any] = [:]) -> Data {
        let out = NSMutableData()
        let dest = CGImageDestinationCreateWithData(out, type.identifier as CFString, 1, nil)!
        CGImageDestinationAddImage(dest, image, props as CFDictionary)
        CGImageDestinationFinalize(dest)
        return out as Data
    }

    private func properties(_ data: Data) -> [CFString: Any] {
        let source = CGImageSourceCreateWithData(data as CFData, nil)!
        return CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as! [CFString: Any]
    }

    @Test func aLargePhotoIsCappedTurnedUprightAndStripped() throws {
        let photo = encode(
            image(4000, 3000, alpha: false), .jpeg,
            [
                kCGImagePropertyOrientation: 6,
                kCGImagePropertyGPSDictionary: [
                    kCGImagePropertyGPSLatitude: 52.5, kCGImagePropertyGPSLatitudeRef: "N",
                ],
                kCGImagePropertyExifDictionary: [kCGImagePropertyExifUserComment: "secret"],
            ])
        #expect(properties(photo)[kCGImagePropertyGPSDictionary] != nil)
        let prepared = try PrepareImage.prepare(photo)
        #expect(prepared.type == "image/jpeg")
        // Orientation 6 is a quarter turn: the stored 4000×3000 shows as 3000×4000.
        #expect(prepared.width == 1536 && prepared.height == 2048)
        let props = properties(prepared.bytes)
        #expect(props[kCGImagePropertyGPSDictionary] == nil)
        #expect((props[kCGImagePropertyOrientation] as? Int ?? 1) == 1)
        let exif = props[kCGImagePropertyExifDictionary] as? [CFString: Any]
        #expect(exif?[kCGImagePropertyExifUserComment] == nil)
    }

    @Test func transparencyKeepsPNGAndOpaquePNGBecomesJPEG() throws {
        let clear = try PrepareImage.prepare(encode(image(10, 20, alpha: true), .png))
        #expect(clear.type == "image/png" && clear.width == 10 && clear.height == 20)
        let opaque = try PrepareImage.prepare(encode(image(10, 20, alpha: false), .png))
        #expect(opaque.type == "image/jpeg")
    }

    @Test func gifsPassThroughAndOtherBytesAreRefused() throws {
        let gif = encode(image(3, 2, alpha: false), .gif)
        let prepared = try PrepareImage.prepare(gif)
        #expect(prepared.bytes == gif && prepared.type == "image/gif" && prepared.width == 3)
        #expect(throws: ImageError("That picture couldn’t be read.")) { try PrepareImage.prepare(Data("<svg/>".utf8)) }
    }
}
