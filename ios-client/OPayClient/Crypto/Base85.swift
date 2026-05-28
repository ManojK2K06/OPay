import Foundation

/// Z85 / Base85 encoder-decoder (ZeroMQ RFC 32 alphabet).
/// Alphabet: 0-9 A-Z a-z . - : + = ^ ! / * ? & < > ( ) [ ] { } @ % $ #
/// 4 binary bytes → 5 printable ASCII chars (ratio 1.25)
/// All characters are GSM 7-bit basic alphabet compatible.
enum Base85 {

    private static let encodeTable: [UInt8] = Array(
        "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ.-:+=^!/*?&<>()[]{}@%$#"
            .utf8
    )

    private static let decodeTable: [UInt8] = {
        var table = [UInt8](repeating: 0xFF, count: 256)
        for (i, c) in encodeTable.enumerated() {
            table[Int(c)] = UInt8(i)
        }
        return table
    }()

    // MARK: – Encode

    static func encode(_ data: Data) -> String {
        let bytes = [UInt8](data)
        var result = [UInt8]()
        result.reserveCapacity(Int(ceil(Double(bytes.count) / 4.0)) * 5)

        var i = 0
        while i < bytes.count {
            // Pad final block if needed
            var block: UInt32 = 0
            let remaining = min(4, bytes.count - i)
            for j in 0..<remaining {
                block = (block << 8) | UInt32(bytes[i + j])
            }
            if remaining < 4 {
                block <<= UInt32((4 - remaining) * 8)
            }

            var chars = [UInt8](repeating: 0, count: 5)
            for k in stride(from: 4, through: 0, by: -1) {
                chars[k] = encodeTable[Int(block % 85)]
                block /= 85
            }
            let charsToAppend = remaining < 4 ? remaining + 1 : 5
            result.append(contentsOf: chars.prefix(charsToAppend))
            i += 4
        }
        return String(bytes: result, encoding: .ascii) ?? ""
    }

    // MARK: – Decode

    static func decode(_ string: String) -> Data? {
        let bytes = [UInt8](string.utf8)
        var result = [UInt8]()
        result.reserveCapacity(bytes.count / 5 * 4)

        var i = 0
        while i < bytes.count {
            let remaining = min(5, bytes.count - i)
            var block: UInt32 = 0
            for j in 0..<remaining {
                let v = decodeTable[Int(bytes[i + j])]
                guard v != 0xFF else { return nil }
                block = block * 85 + UInt32(v)
            }
            if remaining < 5 {
                // Pad with 'u' (value 84) to complete the block
                for _ in remaining..<5 {
                    block = block * 85 + 84
                }
            }
            let outputBytes = remaining < 5 ? remaining - 1 : 4
            for k in stride(from: (outputBytes - 1) * 8, through: 0, by: -8) {
                result.append(UInt8((block >> k) & 0xFF))
            }
            i += 5
        }
        return Data(result)
    }
}
