package notes.core.text

// The web codec and the server's extractor are written against JavaScript's `\s`,
// which is wider than Java's: NBSP, the U+2000 range, BOM and friends count too.

/** JavaScript's `\s` as a regex character class. */
internal const val JS_SPACE_CLASS =
    "[\\t\\n\\x0B\\f\\r \\x{a0}\\x{1680}\\x{2000}-\\x{200a}\\x{2028}\\x{2029}\\x{202f}\\x{205f}\\x{3000}\\x{feff}]"

internal fun isJsSpace(c: Char): Boolean = when (c.code) {
    0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x20, 0xA0, 0x1680, 0x2028, 0x2029, 0x202F, 0x205F, 0x3000, 0xFEFF -> true
    in 0x2000..0x200A -> true
    else -> false
}
