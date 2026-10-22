import Foundation

/// RFC 1952 gzip with bounded LZ77/fixed-Huffman compression and RFC 1951
/// fixed/dynamic Huffman decoding, portable across Apple/Linux SDKs.
/// The decoder checks CRC/size and bounds expansion before allocating output.
public enum BadgeGzip {
    public static func deflate(_ data: Data) throws -> Data {
        guard data.count <= 4_194_304 else { throw BadgeProtocolError.oversized }
        let bytes = Array(data)
        let header: [UInt8] = [31, 139, 8, 0, 0, 0, 0, 0, 0, 3]
        var stored: [UInt8] = []
        var index = 0
        repeat {
            let count = min(65_535, bytes.count - index), last = index + count == bytes.count
            let n = UInt16(count), inverse = ~n
            stored += [last ? 1 : 0, UInt8(n & 255), UInt8(n >> 8), UInt8(inverse & 255), UInt8(inverse >> 8)]
            stored.append(contentsOf: bytes[index..<(index + count)]); index += count
        } while index < bytes.count
        let compressed = fixedDeflate(bytes)
        var out = header + (compressed.count < stored.count ? compressed : stored)
        append(crc32(bytes), to: &out); append(UInt32(bytes.count), to: &out)
        return Data(out)
    }
    private static func fixedDeflate(_ bytes: [UInt8]) -> [UInt8] {
        var writer = Writer(), heads = Array(repeating: -1, count: 65_536), previous = Array(repeating: -1, count: 32_768)
        let lengthBase = [3,4,5,6,7,8,9,10,11,13,15,17,19,23,27,31,35,43,51,59,67,83,99,115,131,163,195,227,258]
        let lengthExtra = [0,0,0,0,0,0,0,0,1,1,1,1,2,2,2,2,3,3,3,3,4,4,4,4,5,5,5,5,0]
        let distanceBase = [1,2,3,4,5,7,9,13,17,25,33,49,65,97,129,193,257,385,513,769,1025,1537,2049,3073,4097,6145,8193,12289,16385,24577]
        let distanceExtra = [0,0,0,0,1,1,2,2,3,3,4,4,5,5,6,6,7,7,8,8,9,9,10,10,11,11,12,12,13,13]
        func hash(_ i: Int) -> Int { (Int(bytes[i]) * 251 + Int(bytes[i + 1]) * 31 + Int(bytes[i + 2])) & 65_535 }
        func reverse(_ value: Int, count: Int) -> Int {
            var n = value, result = 0
            for _ in 0..<count { result = result << 1 | n & 1; n >>= 1 }; return result
        }
        func symbol(_ symbol: Int, writer: inout Writer) {
            let code: Int, count: Int
            if symbol < 144 { code = 0x30 + symbol; count = 8 }
            else if symbol < 256 { code = 0x190 + symbol - 144; count = 9 }
            else if symbol < 280 { code = symbol - 256; count = 7 }
            else { code = 0xc0 + symbol - 280; count = 8 }
            writer.write(reverse(code, count: count), count: count)
        }
        writer.write(1, count: 1); writer.write(1, count: 2)
        var i = 0
        while i < bytes.count {
            var best = 0, distance = 0
            if i + 2 < bytes.count {
                var candidate = heads[hash(i)], attempts = 0
                while candidate >= 0, candidate < i, i - candidate <= 32_768, attempts < 32 {
                    var length = 0
                    while length < 258, i + length < bytes.count, bytes[candidate + length] == bytes[i + length] { length += 1 }
                    if length > best { best = length; distance = i - candidate }
                    if best == 258 { break }
                    let next = previous[candidate & 32_767]
                    if next >= candidate { break }; candidate = next; attempts += 1
                }
            }
            let consumed: Int
            if best >= 3 {
                var l = lengthBase.count - 1; while lengthBase[l] > best { l -= 1 }
                symbol(257 + l, writer: &writer); writer.write(best - lengthBase[l], count: lengthExtra[l])
                var d = distanceBase.count - 1; while distanceBase[d] > distance { d -= 1 }
                writer.write(reverse(d, count: 5), count: 5); writer.write(distance - distanceBase[d], count: distanceExtra[d]); consumed = best
            } else { symbol(Int(bytes[i]), writer: &writer); consumed = 1 }
            for index in i..<(i + consumed) where index + 2 < bytes.count {
                let h = hash(index); previous[index & 32_767] = heads[h]; heads[h] = index
            }
            i += consumed
        }
        symbol(256, writer: &writer)
        return writer.bytes
    }
    private struct Writer {
        var bytes: [UInt8] = []; var bit = 0
        mutating func write(_ value: Int, count: Int) {
            for i in 0..<count {
                if bit % 8 == 0 { bytes.append(0) }
                bytes[bytes.count - 1] |= UInt8(value >> i & 1) << (bit % 8); bit += 1
            }
        }
    }
    public static func inflate(_ data: Data, limit: Int = 4_194_304) throws -> Data {
        let bytes = Array(data)
        guard bytes.count >= 18, bytes.count <= 4_500_000, bytes[0...2].elementsEqual([31, 139, 8]), bytes[3] & 0xe0 == 0 else { throw BadgeProtocolError.malformed }
        var start = 10
        if bytes[3] & 4 != 0 {
            guard start + 2 <= bytes.count - 8 else { throw BadgeProtocolError.malformed }
            let count = Int(bytes[start]) | Int(bytes[start + 1]) << 8; start += count + 2
        }
        for flag in [UInt8(8), UInt8(16)] where bytes[3] & flag != 0 {
            while start < bytes.count - 8, bytes[start] != 0 { start += 1 }; start += 1
        }
        if bytes[3] & 2 != 0 {
            guard start + 2 <= bytes.count - 8 else { throw BadgeProtocolError.malformed }
            let expected = UInt32(bytes[start]) | UInt32(bytes[start + 1]) << 8
            guard crc32(Array(bytes[..<start])) & 0xffff == expected else { throw BadgeProtocolError.malformed }; start += 2
        }
        guard start < bytes.count - 8 else { throw BadgeProtocolError.malformed }
        var reader = Bits(bytes: Array(bytes[start..<(bytes.count - 8)])), out: [UInt8] = []
        var final = false
        let lengthBase = [3,4,5,6,7,8,9,10,11,13,15,17,19,23,27,31,35,43,51,59,67,83,99,115,131,163,195,227,258]
        let lengthExtra = [0,0,0,0,0,0,0,0,1,1,1,1,2,2,2,2,3,3,3,3,4,4,4,4,5,5,5,5,0]
        let distanceBase = [1,2,3,4,5,7,9,13,17,25,33,49,65,97,129,193,257,385,513,769,1025,1537,2049,3073,4097,6145,8193,12289,16385,24577]
        let distanceExtra = [0,0,0,0,1,1,2,2,3,3,4,4,5,5,6,6,7,7,8,8,9,9,10,10,11,11,12,12,13,13]
        while !final {
            final = try reader.read(1) == 1
            let type = try reader.read(2)
            if type == 0 {
                reader.align()
                let count = try reader.read(16), inverse = try reader.read(16)
                guard count ^ inverse == 65_535, out.count + count <= limit else { throw BadgeProtocolError.oversized }
                for _ in 0..<count { out.append(UInt8(try reader.read(8))) }
                continue
            }
            guard type == 1 || type == 2 else { throw BadgeProtocolError.malformed }
            let literals: Huffman, distances: Huffman
            if type == 1 {
                literals = try Huffman((0..<288).map { $0 < 144 ? 8 : ($0 < 256 ? 9 : ($0 < 280 ? 7 : 8)) })
                distances = try Huffman(Array(repeating: 5, count: 32))
            } else {
                let hlit = try reader.read(5) + 257, hdist = try reader.read(5) + 1, hclen = try reader.read(4) + 4
                guard hlit <= 286 else { throw BadgeProtocolError.malformed }
                let permutation = [16,17,18,0,8,7,9,6,10,5,11,4,12,3,13,2,14,1,15]
                var codeLengths = Array(repeating: 0, count: 19)
                for i in 0..<hclen { codeLengths[permutation[i]] = try reader.read(3) }
                let codeTree = try Huffman(codeLengths)
                var lengths: [Int] = []
                while lengths.count < hlit + hdist {
                    let symbol = try codeTree.decode(&reader)
                    if symbol <= 15 { lengths.append(symbol); continue }
                    let count: Int, value: Int
                    switch symbol {
                    case 16:
                        guard let last = lengths.last else { throw BadgeProtocolError.malformed }
                        count = try reader.read(2) + 3; value = last
                    case 17: count = try reader.read(3) + 3; value = 0
                    case 18: count = try reader.read(7) + 11; value = 0
                    default: throw BadgeProtocolError.malformed
                    }
                    guard lengths.count + count <= hlit + hdist else { throw BadgeProtocolError.malformed }
                    lengths += Array(repeating: value, count: count)
                }
                guard lengths[256] > 0 else { throw BadgeProtocolError.malformed }
                literals = try Huffman(Array(lengths[..<hlit])); distances = try Huffman(Array(lengths[hlit...]))
            }
            while true {
                let symbol = try literals.decode(&reader)
                if symbol == 256 { break }
                if symbol < 256 {
                    guard out.count < limit else { throw BadgeProtocolError.oversized }; out.append(UInt8(symbol)); continue
                }
                guard (257...285).contains(symbol) else { throw BadgeProtocolError.malformed }
                let n = symbol - 257, length = try lengthBase[n] + reader.read(lengthExtra[n])
                let d = try distances.decode(&reader)
                guard d < 30 else { throw BadgeProtocolError.malformed }
                let distance = try distanceBase[d] + reader.read(distanceExtra[d])
                guard distance <= out.count, out.count + length <= limit else { throw BadgeProtocolError.oversized }
                for _ in 0..<length { out.append(out[out.count - distance]) }
            }
        }
        guard reader.consumedBytes == reader.bytes.count, crc32(out) == little(bytes, bytes.count - 8),
              UInt32(out.count) == little(bytes, bytes.count - 4) else { throw BadgeProtocolError.malformed }
        return Data(out)
    }
    private static func append(_ number: UInt32, to out: inout [UInt8]) { for shift in stride(from: 0, to: 32, by: 8) { out.append(UInt8((number >> shift) & 255)) } }
    private static func little(_ b: [UInt8], _ i: Int) -> UInt32 { UInt32(b[i]) | UInt32(b[i+1]) << 8 | UInt32(b[i+2]) << 16 | UInt32(b[i+3]) << 24 }
    private static func crc32(_ bytes: [UInt8]) -> UInt32 {
        var crc: UInt32 = 0xffff_ffff
        for b in bytes { crc ^= UInt32(b); for _ in 0..<8 { crc = (crc >> 1) ^ ((crc & 1) != 0 ? 0xedb8_8320 : 0) } }
        return ~crc
    }
    private struct Bits {
        let bytes: [UInt8]; var bit = 0
        var consumedBytes: Int { (bit + 7) / 8 }
        mutating func align() { bit = (bit + 7) / 8 * 8 }
        mutating func read(_ count: Int) throws -> Int {
            guard count >= 0, bit + count <= bytes.count * 8 else { throw BadgeProtocolError.malformed }
            var result = 0
            for i in 0..<count { result |= Int((bytes[bit / 8] >> (bit % 8)) & 1) << i; bit += 1 }
            return result
        }
    }
    private struct Huffman {
        let codes: [Int: Int]
        init(_ lengths: [Int]) throws {
            var counts = Array(repeating: 0, count: 16), next = counts, code = 0, table: [Int: Int] = [:]
            for len in lengths { guard (0...15).contains(len) else { throw BadgeProtocolError.malformed }; if len > 0 { counts[len] += 1 } }
            var left = 1
            for len in 1...15 { left = left * 2 - counts[len]; guard left >= 0 else { throw BadgeProtocolError.malformed }; code = (code + counts[len - 1]) << 1; next[len] = code }
            for (symbol, len) in lengths.enumerated() where len > 0 {
                var reversed = 0, value = next[len]
                for _ in 0..<len { reversed = reversed << 1 | value & 1; value >>= 1 }
                table[len << 16 | reversed] = symbol; next[len] += 1
            }
            codes = table
        }
        func decode(_ reader: inout Bits) throws -> Int {
            var code = 0
            for len in 1...15 { code |= try reader.read(1) << (len - 1); if let value = codes[len << 16 | code] { return value } }
            throw BadgeProtocolError.malformed
        }
    }
}

public struct BadgeSyncEnvelope: Sendable {
    public let index: Int
    public let total: Int
    public let generation: Int
    public let badges: [CollectedBadge]
    public init(index: Int, total: Int, generation: Int, badges: [CollectedBadge]) {
        self.index = index; self.total = total; self.generation = generation; self.badges = badges
    }
    public var json: String {
        "{\"v\":1,\"i\":\(index),\"n\":\(total),\"gen\":\(generation),\"badges\":[" + badges.map(\.wireJSON).joined(separator: ",") + "]}"
    }
    public static func parse(_ plaintext: String) throws -> Self {
        let map = try BadgeWire.parse(plaintext).object
        guard map["v"]?.integer == 1, case .array(let raw) = map["badges"] else { throw BadgeProtocolError.malformed }
        let index = map["i"]?.integer ?? 0, total = map["n"]?.integer ?? 1
        guard total > 0, total <= 16, index >= 0, index < total else { throw BadgeProtocolError.malformed }
        return Self(index: index, total: total, generation: map["gen"]?.integer ?? 0, badges: raw.compactMap { try? CollectedBadge(json: $0.object) })
    }
}

public enum BadgeSyncFormat {
    public static let kind = 30078, maximumChunks = 16, payloadLimit = 30_000
    public static func dTag(_ index: Int) -> String { "badges-\(index)" }
    public static func plaintexts(_ badges: [CollectedBadge], generation: Int) throws -> [String] {
        let sorted = CollectedBadge.merge(badges, [])
        var chunks: [[CollectedBadge]] = [], current: [CollectedBadge] = []
        for badge in sorted {
            let candidate = current + [badge]
            let plain = BadgeSyncEnvelope(index: chunks.count, total: maximumChunks, generation: generation, badges: candidate).json
            let gzip = try BadgeGzip.deflate(Data(plain.utf8))
            // The encrypted plaintext is base64(gzip), then NIP-44 padding.
            if payloadChars(plaintextBytes: gzip.base64EncodedString().utf8.count) > payloadLimit {
                guard !current.isEmpty else { throw BadgeProtocolError.oversized }; chunks.append(current); current = [badge]
            } else { current = candidate }
        }
        if !current.isEmpty { chunks.append(current) }
        guard chunks.count <= maximumChunks else { throw BadgeProtocolError.oversized }
        return chunks.enumerated().map { BadgeSyncEnvelope(index: $0.offset, total: chunks.count, generation: generation, badges: $0.element).json }
    }
    public static func payloadChars(plaintextBytes n: Int) -> Int {
        let padded: Int
        if n <= 32 { padded = 32 }
        else { var power = 1; while power < n { power <<= 1 }; let chunk = power <= 256 ? 32 : power / 8; padded = chunk * ((n - 1) / chunk + 1) }
        return ((1 + 32 + 2 + padded + 32 + 2) / 3) * 4
    }
    public static func decode(_ encrypted: String, signer: any NostrSigner, publicKey: String) async throws -> BadgeSyncEnvelope {
        let plaintext = try await signer.decrypt(encrypted, from: publicKey)
        guard let zipped = Data(base64Encoded: plaintext) else { throw BadgeProtocolError.malformed }
        let data = try BadgeGzip.inflate(zipped)
        guard let text = String(data: data, encoding: .utf8) else { throw BadgeProtocolError.malformed }
        return try BadgeSyncEnvelope.parse(text)
    }
    /// Replaced chunks can leave older excess indices on relays. Ignore those
    /// only when the newest generation has its own complete, coherent set.
    public static func coherentSnapshot(_ envelopes: [BadgeSyncEnvelope]) throws -> [CollectedBadge] {
        guard let newest = envelopes.map(\.generation).max() else { return [] }
        let selected = envelopes.filter { $0.generation == newest }
        guard let total = selected.first?.total, selected.allSatisfy({ $0.total == total }),
              selected.count == total, Set(selected.map(\.index)) == Set(0..<total) else { throw BadgeProtocolError.incompleteSync }
        return CollectedBadge.merge([], selected.sorted { $0.index < $1.index }.flatMap(\.badges))
    }
}
