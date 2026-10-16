import SwiftUI
import UIKit

/// The web app's design tokens (apps/web/src/styles/tokens.css) as sRGB,
/// taken from Android's ui/Tokens.kt, which keeps the oklch source beside each
/// value. Each colour follows the system light/dark appearance.
nonisolated enum Palette {
    static let bg = pair(0xFFF5_F3F1, 0xFF12_1416)
    static let ink = pair(0xFF21_1F1C, 0xFFE9_EBEE)
    static let ink2 = pair(0xFF50_4C4A, 0xFFB2_B4B7)
    static let ink3 = pair(0xFF66_6260, 0xFF93_9598)
    static let accent = pair(0xFFEF_B146, 0xFFEF_B146)
    static let accentDeep = pair(0xFFDD_9314, 0xFFDD_9314)
    static let onAccent = pair(0xFF1F_1915, 0xFF1F_1915)
    static let tint = pair(0xFFBE_7200, 0xFFF0_BA59)
    static let link = pair(0xFF87_4E00, 0xFFEF_BA64)
    static let danger = pair(0xFFAC_312C, 0xFFFB_8276)
    static let solid = pair(0xFF24_211F, 0xFFE6_E8EA)
    static let onSolid = pair(0xFFF9_F8F6, 0xFF14_1618)
    static let hair = pair(0x172D_2823, 0x14FF_FFFF)
    static let hairStrong = pair(0x292D_2823, 0x29FF_FFFF)
    static let hover = pair(0x122D_2823, 0x12FF_FFFF)
    static let field = pair(0xC7FF_FFFF, 0x0FFF_FFFF)
    static let chip = pair(0x9EFF_FFFF, 0x12FF_FFFF)
    static let good = pair(0xFF0E_492A, 0xFF9E_E0B4)
    static let goodBg = pair(0xBFC0_EACD, 0x8024_5337)
    static let errBg = pair(0xD9FF_E8E3, 0xB24D_2620)
    static let errEdge = pair(0xFFFD_CAC0, 0xFF72_3B32)
    static let errInk = pair(0xFF78_2B21, 0xFFFD_CAC0)
    static let warnBg = pair(0xE0FF_EAD3, 0xBF48_2F1A)
    static let warnEdge = pair(0xFFF6_CBA5, 0xFF72_4B2B)
    static let warnInk = pair(0xFF62_3512, 0xFFF8_D8B4)

    /// Behind #tags (web: .prose [data-tag]).
    static let tagBg = pair(0x4DEF_B146, 0x4DEF_B146)

    private static func pair(_ light: UInt32, _ dark: UInt32) -> UIColor {
        UIColor { $0.userInterfaceStyle == .dark ? color(dark) : color(light) }
    }

    private static func color(_ argb: UInt32) -> UIColor {
        UIColor(
            red: CGFloat((argb >> 16) & 0xFF) / 255, green: CGFloat((argb >> 8) & 0xFF) / 255,
            blue: CGFloat(argb & 0xFF) / 255, alpha: CGFloat((argb >> 24) & 0xFF) / 255)
    }
}

extension Color {
    static let notesBg = Color(Palette.bg)
    static let ink = Color(Palette.ink)
    static let ink2 = Color(Palette.ink2)
    static let ink3 = Color(Palette.ink3)
    static let accent = Color(Palette.accent)
    static let accentDeep = Color(Palette.accentDeep)
    static let onAccent = Color(Palette.onAccent)
    static let notesTint = Color(Palette.tint)
    static let danger = Color(Palette.danger)
    static let hair = Color(Palette.hair)
    static let field = Color(Palette.field)
    static let chip = Color(Palette.chip)
    static let errBg = Color(Palette.errBg)
    static let errInk = Color(Palette.errInk)
    static let warnBg = Color(Palette.warnBg)
    static let warnInk = Color(Palette.warnInk)
    static let goodBg = Color(Palette.goodBg)
    static let good = Color(Palette.good)
}

/// The web app's three faces (apps/web/src/styles/fonts.css), bundled so the app looks the same offline.
nonisolated enum Typeface {
    /// Instrument Sans: everything that isn't a title. A variable font, 400–700.
    static func sans(_ size: CGFloat, weight: CGFloat = 400, italic: Bool = false) -> UIFont {
        variable(italic ? "InstrumentSans-Italic" : "InstrumentSans-Regular", size, weight)
    }

    /// Instrument Serif: headings, titles, quotes. One weight.
    static func serif(_ size: CGFloat, italic: Bool = false) -> UIFont {
        UIFont(name: italic ? "InstrumentSerif-Italic" : "InstrumentSerif-Regular", size: size)
            ?? .systemFont(ofSize: size)
    }

    /// JetBrains Mono: server addresses, code, raw Markdown.
    static func mono(_ size: CGFloat, weight: CGFloat = 400) -> UIFont {
        variable("JetBrainsMono-Regular", size, weight)
    }

    private static let weightAxis = 0x7767_6874  // 'wght'

    private static func variable(_ name: String, _ size: CGFloat, _ weight: CGFloat) -> UIFont {
        guard let base = UIFont(name: name, size: size) else { return .systemFont(ofSize: size) }
        if weight == 400 { return base }
        let key = UIFontDescriptor.AttributeName(rawValue: kCTFontVariationAttribute as String)
        return UIFont(descriptor: base.fontDescriptor.addingAttributes([key: [weightAxis: weight]]), size: size)
    }

    /// Scaled with the user's text size.
    static func scaled(_ font: UIFont, as style: UIFont.TextStyle = .body) -> UIFont {
        UIFontMetrics(forTextStyle: style).scaledFont(for: font)
    }
}

extension Font {
    /// Named styles, sized for phones (the web app's mobile sizes), scaling with Dynamic Type.
    static let notesDisplay = Font.custom("InstrumentSerif-Regular", size: 34, relativeTo: .largeTitle)
    static let notesHeading = Font.custom("InstrumentSerif-Regular", size: 30, relativeTo: .title)
    static let notesTitle = Font.custom("InstrumentSerif-Regular", size: 22, relativeTo: .title2)
    static let notesBody = Font.custom("InstrumentSans-Regular", size: 15, relativeTo: .body)
    static let notesLede = Font.custom("InstrumentSans-Regular", size: 16, relativeTo: .body)
    static let notesRowTitle = Font.custom("InstrumentSans-Regular", size: 16, relativeTo: .body).weight(.medium)
    static let notesRowMeta = Font.custom("InstrumentSans-Regular", size: 14, relativeTo: .subheadline)
    static let notesLabel = Font.custom("InstrumentSans-Regular", size: 13, relativeTo: .footnote).weight(.medium)
    static let notesSmall = Font.custom("InstrumentSans-Regular", size: 13, relativeTo: .footnote)
    static let notesButton = Font.custom("InstrumentSans-Regular", size: 15, relativeTo: .body).weight(.semibold)
    static let notesMono = Font.custom("JetBrainsMono-Regular", size: 14, relativeTo: .body)
    /// The wordmark: lowercase and monospaced, like the daemon it's named after.
    static let notesBrand = Font.custom("JetBrainsMono-Regular", size: 20, relativeTo: .title3).weight(.medium)
}

/// A list section's title, in ink2: the system's secondary grey is too faint on the paper background.
struct SectionHeader: View {
    let title: String

    init(_ title: String) {
        self.title = title
    }

    var body: some View {
        Text(title).font(.notesLabel).foregroundStyle(Color.ink2).textCase(nil)
    }
}
