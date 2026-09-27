import UIKit

/// Vẽ Report thành PDF A4 bằng UIGraphicsPDFRenderer.
enum PdfBuilder {
    private static let pageW: CGFloat = 595.2
    private static let pageH: CGFloat = 841.8
    private static let margin: CGFloat = 36

    static func render(_ report: Report) -> Data {
        let renderer = UIGraphicsPDFRenderer(bounds: CGRect(x: 0, y: 0, width: pageW, height: pageH))
        return renderer.pdfData { ctx in
            var page = 0
            var y: CGFloat = 0
            func newPage() {
                page += 1
                ctx.beginPage()
                y = margin
            }
            func footer() {
                let s = "Chip Card Reader - bao cao tao tu dong, khong thay the giay to goc     Trang \(page)"
                draw(s, font: .systemFont(ofSize: 8), color: .gray, x: margin, w: pageW - 2 * margin, y: pageH - 24)
            }
            func draw(_ text: String, font: UIFont, color: UIColor = .black, x: CGFloat, w: CGFloat, y: CGFloat) -> CGFloat {
                let attr = NSAttributedString(string: text, attributes: [.font: font, .foregroundColor: color])
                let rect = attr.boundingRect(with: CGSize(width: w, height: CGFloat.greatestFiniteMagnitude),
                                             options: [.usesLineFragmentOrigin], context: nil)
                attr.draw(with: CGRect(x: x, y: y, width: w, height: rect.height.rounded(.up) + 2),
                         options: [.usesLineFragmentOrigin], context: nil)
                return rect.height.rounded(.up) + 4
            }
            func ensure(_ h: CGFloat) {
                if y + h > pageH - margin - 20 { footer(); newPage() }
            }
            /// Vẽ nhiều dòng, mỗi dòng có thể tự xuống dòng (word-wrap) theo bề rộng w; tự sang trang khi hết chỗ.
            func block(_ text: String, font: UIFont, color: UIColor = .black, w: CGFloat) {
                for rawLine in text.components(separatedBy: "\n") {
                    let line = rawLine.isEmpty ? " " : rawLine
                    let attr = NSAttributedString(string: line, attributes: [.font: font, .foregroundColor: color])
                    let rect = attr.boundingRect(with: CGSize(width: w, height: CGFloat.greatestFiniteMagnitude),
                                                 options: [.usesLineFragmentOrigin], context: nil)
                    let h = rect.height.rounded(.up) + 2
                    ensure(h)
                    attr.draw(with: CGRect(x: margin, y: y, width: w, height: h), options: [.usesLineFragmentOrigin], context: nil)
                    y += h
                }
            }

            newPage()
            y += draw(report.title, font: .boldSystemFont(ofSize: 18), x: margin, w: pageW - 2 * margin, y: y)
            y += draw("Thoi diem tao: \(report.generated)", font: .systemFont(ofSize: 9), color: .gray, x: margin, w: pageW - 2 * margin, y: y)
            y += 4

            let warnFont = UIFont.boldSystemFont(ofSize: 8.5)
            let warnAttr = NSAttributedString(string: report.sensitiveNote, attributes: [.font: warnFont, .foregroundColor: UIColor(red: 0.54, green: 0.11, blue: 0.11, alpha: 1)])
            let warnRect = warnAttr.boundingRect(with: CGSize(width: pageW - 2 * margin - 12, height: CGFloat.greatestFiniteMagnitude), options: [.usesLineFragmentOrigin], context: nil)
            ensure(warnRect.height + 16)
            let box = CGRect(x: margin, y: y, width: pageW - 2 * margin, height: warnRect.height + 12)
            ctx.cgContext.setFillColor(UIColor(red: 0.99, green: 0.93, blue: 0.93, alpha: 1).cgColor)
            ctx.cgContext.fill(box)
            ctx.cgContext.setStrokeColor(UIColor(red: 0.85, green: 0.33, blue: 0.31, alpha: 1).cgColor)
            ctx.cgContext.stroke(box, width: 0.8)
            warnAttr.draw(with: box.insetBy(dx: 6, dy: 5), options: [.usesLineFragmentOrigin], context: nil)
            y += warnRect.height + 20

            for sec in report.sections {
                ensure(50)
                y += 8
                y += draw(sec.heading, font: .boldSystemFont(ofSize: 13), color: UIColor(red: 0.12, green: 0.23, blue: 0.37, alpha: 1), x: margin, w: pageW - 2 * margin, y: y)
                y += 2

                var photoH: CGFloat = 0
                var valW = pageW - 2 * margin - 160
                if let photoData = sec.photo, let img = UIImage(data: photoData) {
                    let w: CGFloat = 110
                    let h = min(w * img.size.height / img.size.width, 145)
                    ensure(h)
                    img.draw(in: CGRect(x: pageW - margin - w, y: y, width: w, height: h))
                    photoH = h
                    valW -= 0
                }
                let startY = y
                for (k, v) in sec.rows {
                    let kAttr = NSAttributedString(string: k, attributes: [.font: UIFont.boldSystemFont(ofSize: 9.5)])
                    let vAttr = NSAttributedString(string: v.isEmpty ? " " : v, attributes: [.font: UIFont.systemFont(ofSize: 9.5)])
                    let kh = kAttr.boundingRect(with: CGSize(width: 150, height: CGFloat.greatestFiniteMagnitude), options: [.usesLineFragmentOrigin], context: nil).height
                    let vh = vAttr.boundingRect(with: CGSize(width: valW, height: CGFloat.greatestFiniteMagnitude), options: [.usesLineFragmentOrigin], context: nil).height
                    let h = max(kh, vh) + 6
                    ensure(h)
                    kAttr.draw(with: CGRect(x: margin, y: y, width: 150, height: h), options: [.usesLineFragmentOrigin], context: nil)
                    vAttr.draw(with: CGRect(x: margin + 156, y: y, width: valW, height: h), options: [.usesLineFragmentOrigin], context: nil)
                    y += h
                    ctx.cgContext.setStrokeColor(UIColor(white: 0.8, alpha: 1).cgColor)
                    ctx.cgContext.move(to: CGPoint(x: margin, y: y))
                    ctx.cgContext.addLine(to: CGPoint(x: pageW - margin, y: y))
                    ctx.cgContext.strokePath()
                }
                if y < startY + photoH { y = startY + photoH }
                if !sec.text.isEmpty {
                    y += 4
                    block(sec.text, font: UIFont(name: "Menlo", size: 7.5) ?? .monospacedSystemFont(ofSize: 7.5, weight: .regular), w: pageW - 2 * margin)
                }
            }
            footer()
        }
    }
}
