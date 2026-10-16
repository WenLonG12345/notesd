import UIKit

/// Text outside the dialect, kept as its exact source (spec §7.1): one
/// character, shown as written, deleted whole, never edited. A raw block
/// (code, table, rule) takes the width of its line and wraps; a raw run inside
/// a line (a code span, an image) is one line, cut short if it's too long.
nonisolated final class RawAttachment: NSTextAttachment {
    let source: String
    let isBlock: Bool

    init(source: String, isBlock: Bool) {
        self.source = source
        self.isBlock = isBlock
        super.init(data: nil, ofType: nil)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError("not coded") }

    private static let padX: CGFloat = 4
    private static let padBlock: CGFloat = 8

    /// What's shown: the source as written, or, for an image, its alt text (images arrive in a later version).
    private var shown: String {
        if let m = source.wholeMatch(of: /!\[(.*)\]\(notesd:image\/[0-9a-f]{64}[^)]*\)/.dotMatchesNewlines()) {
            let alt = String(m.1)
            return alt.isEmpty ? "Image" : "Image: \(alt)"
        }
        return source
    }

    private func font(_ attributes: [NSAttributedString.Key: Any]) -> UIFont {
        let size = (attributes[.font] as? UIFont)?.pointSize ?? 17
        return Typeface.mono(isBlock ? size : size * 0.88)
    }

    private func text(_ font: UIFont, wraps: Bool) -> NSAttributedString {
        let p = NSMutableParagraphStyle()
        p.lineBreakMode = wraps ? .byCharWrapping : .byTruncatingTail
        return NSAttributedString(
            string: shown, attributes: [.font: font, .foregroundColor: Palette.ink2, .paragraphStyle: p])
    }

    override func attachmentBounds(
        for attributes: [NSAttributedString.Key: Any], location: any NSTextLocation, textContainer: NSTextContainer?,
        proposedLineFragment: CGRect, position: CGPoint
    ) -> CGRect {
        let font = font(attributes)
        let available = max(proposedLineFragment.width - position.x, 40)
        if isBlock {
            let measured = text(font, wraps: true).boundingRect(
                with: CGSize(width: available - 2 * Self.padBlock, height: .greatestFiniteMagnitude),
                options: [.usesLineFragmentOrigin], context: nil)
            return CGRect(x: 0, y: 0, width: available, height: ceil(measured.height) + 2 * Self.padBlock)
        }
        let width = ceil(text(font, wraps: false).size().width) + 2 * Self.padX
        return CGRect(x: 0, y: font.descender - 2, width: min(width, available), height: font.lineHeight + 4)
    }

    override func image(
        for bounds: CGRect, attributes: [NSAttributedString.Key: Any], location: any NSTextLocation,
        textContainer: NSTextContainer?
    ) -> UIImage? {
        let font = font(attributes)
        let format = UIGraphicsImageRendererFormat.preferred()
        return UIGraphicsImageRenderer(size: bounds.size, format: format).image { _ in
            let box = CGRect(origin: .zero, size: bounds.size)
            Palette.hover.setFill()
            UIBezierPath(roundedRect: box, cornerRadius: isBlock ? 8 : 5).fill()
            let pad = isBlock ? Self.padBlock : Self.padX
            let inner = box.insetBy(dx: pad, dy: isBlock ? pad : 2)
            text(font, wraps: isBlock).draw(
                with: inner, options: [.usesLineFragmentOrigin, .truncatesLastVisibleLine], context: nil)
        }
    }
}
