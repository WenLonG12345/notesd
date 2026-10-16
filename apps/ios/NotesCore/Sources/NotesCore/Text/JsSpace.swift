// The web codec and the server's extractor are written against JavaScript's `\s`,
// which is wider than Swift's ASCII whitespace: NBSP, the U+2000 range, BOM and
// friends count too. JavaScript strings are UTF-16, so nothing outside the BMP does.

/// JavaScript's `\s`.
func isJsSpace(_ c: Unicode.Scalar) -> Bool {
    switch c.value {
    case 0x09...0x0D, 0x20, 0xA0, 0x1680, 0x2028, 0x2029, 0x202F, 0x205F, 0x3000, 0xFEFF: true
    case 0x2000...0x200A: true
    default: false
    }
}
