import Foundation

/// Plain text extraction only. This is deliberately not an HTML renderer or a DOM.
/// Each input byte is scanned a bounded number of times, including malformed tags/entities.
enum HTMLText {
    static func plain(_ html: String) -> String {
        var scanner = HTMLScanner(html)
        var output: [UInt8] = []
        output.reserveCapacity(scanner.bytes.count)
        var hidden: String?
        while let token = scanner.next() {
            switch token {
            case .text(let range):
                if hidden == nil { output.append(contentsOf: scanner.bytes[range]) }
            case .tag(let name, let closing, _):
                if let current = hidden {
                    if closing && name == current { hidden = nil; output.append(32) }
                } else {
                    if !closing && (name == "script" || name == "style") { hidden = name }
                    output.append(32)
                }
            case .ignored:
                if hidden == nil { output.append(32) }
            }
        }
        // Decode after stripping actual tags: escaped mathematical comparisons remain visible.
        return entities(String(decoding: output, as: UTF8.self))
            .split(whereSeparator: { $0.isWhitespace }).joined(separator: " ")
    }

    static func firstImage(_ html: String) -> URL? {
        var scanner = HTMLScanner(html)
        var hidden: String?
        while let token = scanner.next() {
            guard case .tag(let name, let closing, let attributes) = token else { continue }
            if let current = hidden {
                if closing && name == current { hidden = nil }
                continue
            }
            if !closing && (name == "script" || name == "style") { hidden = name; continue }
            if name == "img", !closing,
               let src = scanner.attributes(in: attributes)["src"],
               let url = SafeURL.web(entities(src)) { return url }
        }
        return nil
    }

    static func entities(_ text: String) -> String {
        let bytes = Array(text.utf8)
        var result: [UInt8] = []
        result.reserveCapacity(bytes.count)
        var index = 0
        while index < bytes.count {
            if bytes[index] == 38 {
                var end = index + 1
                while end < bytes.count, end - index <= 32,
                      isASCIIAlphaNumeric(bytes[end]) || bytes[end] == 35 { end += 1 }
                if end < bytes.count, bytes[end] == 59 {
                    let key = String(decoding: bytes[(index + 1)..<end], as: UTF8.self)
                    var decoded = namedEntities[key]
                    if key.hasPrefix("#") {
                        let hex = key.hasPrefix("#x") || key.hasPrefix("#X")
                        if let code = UInt32(key.dropFirst(hex ? 2 : 1), radix: hex ? 16 : 10),
                           let scalar = UnicodeScalar(code),
                           !CharacterSet.controlCharacters.contains(scalar) || [9, 10, 13].contains(code) {
                            decoded = String(scalar)
                        }
                    }
                    if let decoded {
                        result.append(contentsOf: decoded.utf8); index = end + 1; continue
                    }
                }
            }
            result.append(bytes[index]); index += 1
        }
        return String(decoding: result, as: UTF8.self)
    }

    private static let namedEntities: [String: String] = {
        var values = ["amp": "&", "lt": "<", "gt": ">", "quot": "\"", "apos": "'", "nbsp": " ",
                      "euro": "€", "ndash": "–", "mdash": "—", "hellip": "…", "lsquo": "‘", "rsquo": "’",
                      "ldquo": "“", "rdquo": "”", "bull": "•", "trade": "™", "minus": "−", "times": "×",
                      "divide": "÷", "larr": "←", "rarr": "→", "uarr": "↑", "darr": "↓", "harr": "↔"]
        let latinNames = "iexcl cent pound curren yen brvbar sect uml copy ordf laquo not shy reg macr deg plusmn sup2 sup3 acute micro para middot cedil sup1 ordm raquo frac14 frac12 frac34 iquest Agrave Aacute Acirc Atilde Auml Aring AElig Ccedil Egrave Eacute Ecirc Euml Igrave Iacute Icirc Iuml ETH Ntilde Ograve Oacute Ocirc Otilde Ouml times Oslash Ugrave Uacute Ucirc Uuml Yacute THORN szlig agrave aacute acirc atilde auml aring aelig ccedil egrave eacute ecirc euml igrave iacute icirc iuml eth ntilde ograve oacute ocirc otilde ouml divide oslash ugrave uacute ucirc uuml yacute thorn yuml"
        for (index, name) in latinNames.split(separator: " ").enumerated() {
            if let scalar = UnicodeScalar(0xA1 + index) { values[String(name)] = String(scalar) }
        }
        return values
    }()
}

private func isASCIIAlpha(_ byte: UInt8) -> Bool { (65...90).contains(byte) || (97...122).contains(byte) }
private func isASCIIAlphaNumeric(_ byte: UInt8) -> Bool { isASCIIAlpha(byte) || (48...57).contains(byte) }
private func isHTMLSpace(_ byte: UInt8) -> Bool { [9, 10, 12, 13, 32].contains(byte) }

private struct HTMLScanner {
    enum Token {
        case text(Range<Int>)
        case tag(String, closing: Bool, attributes: Range<Int>)
        case ignored
    }
    let bytes: [UInt8]
    private var index = 0
    init(_ text: String) { bytes = Array(text.utf8) }

    mutating func next() -> Token? {
        guard index < bytes.count else { return nil }
        let start = index
        if bytes[index] != 60 {
            while index < bytes.count, bytes[index] != 60 { index += 1 }
            return .text(start..<index)
        }
        if matches("<!--", at: index) {
            index += 4
            while index < bytes.count, !matches("-->", at: index) { index += 1 }
            index = min(index + 3, bytes.count)
            return .ignored
        }
        index += 1
        let declaration = index < bytes.count && (bytes[index] == 33 || bytes[index] == 63)
        let closing = index < bytes.count && bytes[index] == 47
        if closing || declaration { index += 1 }
        let nameStart = index
        guard declaration || (index < bytes.count && isASCIIAlpha(bytes[index])) else {
            index = start + 1; return .text(start..<index)
        }
        while index < bytes.count, isASCIIAlphaNumeric(bytes[index]) || [45, 58].contains(bytes[index]) { index += 1 }
        let name = String(decoding: bytes[nameStart..<index], as: UTF8.self).lowercased()
        let attributesStart = index
        var quote: UInt8?
        while index < bytes.count {
            let byte = bytes[index]
            if let delimiter = quote {
                if byte == delimiter { quote = nil }
            } else if byte == 34 || byte == 39 { quote = byte }
            else if byte == 62 {
                let attributesEnd = index
                index += 1
                return declaration ? .ignored : .tag(name, closing: closing, attributes: attributesStart..<attributesEnd)
            } else if byte == 60 { return .text(start..<index) }
            index += 1
        }
        // Consume an unterminated tag once, so nested '<' inside a quote cannot cause rescans.
        return .text(start..<index)
    }

    func attributes(in range: Range<Int>) -> [String: String] {
        var result: [String: String] = [:]
        var cursor = range.lowerBound
        while cursor < range.upperBound {
            while cursor < range.upperBound, isHTMLSpace(bytes[cursor]) || bytes[cursor] == 47 { cursor += 1 }
            let start = cursor
            while cursor < range.upperBound, !isHTMLSpace(bytes[cursor]), ![47, 61].contains(bytes[cursor]) { cursor += 1 }
            guard cursor > start else { cursor += 1; continue }
            let name = String(decoding: bytes[start..<cursor], as: UTF8.self).lowercased()
            while cursor < range.upperBound, isHTMLSpace(bytes[cursor]) { cursor += 1 }
            guard cursor < range.upperBound, bytes[cursor] == 61 else { continue }
            cursor += 1
            while cursor < range.upperBound, isHTMLSpace(bytes[cursor]) { cursor += 1 }
            let quoted = cursor < range.upperBound && (bytes[cursor] == 34 || bytes[cursor] == 39)
            let quote = quoted ? bytes[cursor] : 0
            if quoted { cursor += 1 }
            let valueStart = cursor
            while cursor < range.upperBound, quoted ? bytes[cursor] != quote : !isHTMLSpace(bytes[cursor]) { cursor += 1 }
            if result[name] == nil { result[name] = String(decoding: bytes[valueStart..<cursor], as: UTF8.self) }
            if quoted, cursor < range.upperBound { cursor += 1 }
        }
        return result
    }

    private func matches(_ text: StaticString, at offset: Int) -> Bool {
        text.withUTF8Buffer { value in
            offset + value.count <= bytes.count && bytes[offset..<(offset + value.count)].elementsEqual(value)
        }
    }
}

enum XMLSafety {
    static func hasDeclarations(_ text: String) -> Bool {
        let bytes = Array(text.utf8)
        var index = 0
        let commentStart = Array("<!--".utf8), commentEnd = Array("-->".utf8)
        let cdataStart = Array("<![CDATA[".utf8), cdataEnd = Array("]]>".utf8)
        let declarationStart = Array("<!".utf8), doctype = Array("DOCTYPE".utf8), entity = Array("ENTITY".utf8)
        func matches(_ expected: [UInt8], at offset: Int, insensitive: Bool = false) -> Bool {
            guard offset + expected.count <= bytes.count else { return false }
            return expected.enumerated().allSatisfy { position, byte in
                let actual = bytes[offset + position]
                return actual == byte || (insensitive && (97...122).contains(actual) && actual - 32 == byte)
            }
        }
        while index < bytes.count {
            guard bytes[index] == 60 else { index += 1; continue }
            let end: [UInt8]?
            if matches(commentStart, at: index) { index += 4; end = commentEnd }
            else if matches(cdataStart, at: index) { index += 9; end = cdataEnd }
            else { end = nil }
            if let end {
                while index < bytes.count, !matches(end, at: index) { index += 1 }
                index = min(index + end.count, bytes.count)
                continue
            }
            if matches(declarationStart, at: index) {
                var cursor = index + 2
                while cursor < bytes.count, isHTMLSpace(bytes[cursor]) { cursor += 1 }
                if matches(doctype, at: cursor, insensitive: true) || matches(entity, at: cursor, insensitive: true) { return true }
            }
            index += 1
        }
        return false
    }
}
