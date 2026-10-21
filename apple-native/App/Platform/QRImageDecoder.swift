import CoreFoundation
import Foundation
import ImageIO
import Vision

enum QRImageError: Error {
    case tooLarge
    case invalidImage
    case noCode
    case multipleCodes
    case payloadTooLarge
}

/// Decodes raw QR text from supplied image data; parsing and trust belong to the caller.
actor QRImageDecoder {
    private static let maximumDataBytes = 16 * 1_024 * 1_024
    private static let maximumPixelCount = 24_000_000
    private static let maximumPayloadBytes = 4_096

    init() {}

    // Synchronous actor isolation keeps ImageIO and Vision work off the main actor.
    func decode(_ data: Data) throws -> String {
        guard data.count <= Self.maximumDataBytes else { throw QRImageError.tooLarge }

        let options = [kCGImageSourceShouldCache: false] as CFDictionary
        guard let source = CGImageSourceCreateWithData(data as CFData, options),
              // Reject containers with multiple images: Vision's data initializer
              // does not specify which frame it will decode.
              CGImageSourceGetCount(source) == 1,
              let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, options)
                as? [CFString: Any] else {
            throw QRImageError.invalidImage
        }

        let width = try pixelDimension(properties[kCGImagePropertyPixelWidth])
        let height = try pixelDimension(properties[kCGImagePropertyPixelHeight])
        guard width <= Self.maximumPixelCount / height else { throw QRImageError.tooLarge }

        // Preflight reads metadata only; no pixels or thumbnails are expanded above.
        let request = VNDetectBarcodesRequest()
        request.symbologies = [.qr]
        let handler = VNImageRequestHandler(data: data, options: [:])
        do {
            try handler.perform([request])
        } catch {
            throw QRImageError.invalidImage
        }

        var uniquePayload: String?
        var uniqueBytes: Data?
        for observation in request.results ?? [] {
            guard let payload = observation.payloadStringValue, !payload.isEmpty else { continue }
            guard payload.utf8.count <= Self.maximumPayloadBytes else {
                throw QRImageError.payloadTooLarge
            }
            let bytes = Data(payload.utf8)
            if let uniqueBytes, uniqueBytes != bytes {
                throw QRImageError.multipleCodes
            }
            uniquePayload = payload
            uniqueBytes = bytes
        }

        guard let uniquePayload else { throw QRImageError.noCode }
        return uniquePayload
    }

    private func pixelDimension(_ value: Any?) throws -> Int {
        guard let number = value as? NSNumber,
              CFGetTypeID(number) == CFNumberGetTypeID() else {
            throw QRImageError.invalidImage
        }
        let dimension = number.doubleValue
        guard dimension.isFinite, dimension > 0,
              dimension.rounded(.towardZero) == dimension else {
            throw QRImageError.invalidImage
        }
        // Bound the value before converting to Int, including malicious metadata.
        guard dimension <= Double(Self.maximumPixelCount) else { throw QRImageError.tooLarge }
        return Int(dimension)
    }
}
