import NotesCore
import UIKit

/// Text outside the dialect, kept as its exact source (spec §7.1): one
/// character, shown as written, deleted whole, never edited. A raw block
/// (code, table, rule) takes the width of its line and wraps; a raw run inside
/// a line (a code span, another image) is one line, cut short if it's too long.
///
/// A picture the apps added (`notesd:image/…`, §7.6) is drawn as the picture,
/// at its own size in points, shrunk to fit the line; a rounded box holds its
/// place until it loads. Its source still goes back as written.
nonisolated final class RawAttachment: NSTextAttachment {
    let source: String
    let isBlock: Bool
    /// The picture this run shows, if it's one the apps added.
    let picture: ImageRef?

    init(source: String, isBlock: Bool) {
        self.source = source
        self.isBlock = isBlock
        picture = isBlock ? nil : ImageRef.parse(source)
        super.init(data: nil, ofType: nil)
        if picture != nil {
            let alt = ImageRef.alt(source)
            accessibilityLabel = alt.isEmpty ? "Picture" : "Picture: \(alt)"
        }
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError("not coded") }

    private static let padX: CGFloat = 4
    private static let padBlock: CGFloat = 8

    /// What's shown: the source as written.
    private var shown: String { source }

    private static let pictureRadius: CGFloat = 10

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
        if let picture {
            let w = min(CGFloat(picture.width), available)
            let h = max((w * CGFloat(picture.height) / CGFloat(picture.width)).rounded(), 1)
            return CGRect(x: 0, y: font.descender, width: w.rounded(.down), height: h)
        }
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
        if let picture {
            let loaded = ImageLoader.shared?.image(picture.id, pixels: Int((bounds.width * format.scale).rounded(.up)))
            return UIGraphicsImageRenderer(size: bounds.size, format: format).image { _ in
                let box = CGRect(origin: .zero, size: bounds.size)
                let shape = UIBezierPath(roundedRect: box, cornerRadius: Self.pictureRadius)
                Palette.hover.setFill()
                shape.fill()
                if let loaded {
                    shape.addClip()
                    loaded.draw(in: box)
                }
            }
        }
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
