import Foundation

/// UUIDv7 (RFC 9562): note ids that sort by creation time, so new notes sort chronologically.
public enum Uuid7 {
    public static func generate(nowMillis: Int64 = Int64(Date().timeIntervalSince1970 * 1000)) -> String {
        var rng = SystemRandomNumberGenerator()
        var bytes = (0..<16).map { _ in UInt8.random(in: 0...255, using: &rng) }
        for i in 0..<6 { bytes[i] = UInt8(truncatingIfNeeded: nowMillis >> (40 - 8 * i)) }
        bytes[6] = (bytes[6] & 0x0F) | 0x70
        bytes[8] = (bytes[8] & 0x3F) | 0x80
        let hex = bytes.map { String(format: "%02x", $0) }.joined()
        let h = Array(hex)
        return
            "\(String(h[0..<8]))-\(String(h[8..<12]))-\(String(h[12..<16]))-\(String(h[16..<20]))-\(String(h[20...]))"
    }
}
