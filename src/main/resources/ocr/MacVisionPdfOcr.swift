import AppKit
import Foundation
import PDFKit
import Vision

guard CommandLine.arguments.count >= 2 else {
    FileHandle.standardError.write(Data("usage: pdf-ocr <pdf> [max-pages]\n".utf8))
    exit(2)
}

let input = URL(fileURLWithPath: CommandLine.arguments[1])
let maximum = CommandLine.arguments.count >= 3 ? (Int(CommandLine.arguments[2]) ?? 30) : 30
guard let document = PDFDocument(url: input) else {
    FileHandle.standardError.write(Data("cannot open PDF\n".utf8))
    exit(3)
}

let pageCount = min(document.pageCount, max(1, maximum))
for pageIndex in 0..<pageCount {
    autoreleasepool {
        guard let page = document.page(at: pageIndex) else { return }
        let bounds = page.bounds(for: .mediaBox)
        let width: CGFloat = 1800
        let height = max(1, width * bounds.height / max(1, bounds.width))
        let image = page.thumbnail(of: NSSize(width: width, height: height), for: .mediaBox)
        var proposed = NSRect(origin: .zero, size: image.size)
        guard let cgImage = image.cgImage(forProposedRect: &proposed, context: nil, hints: nil) else { return }

        let request = VNRecognizeTextRequest()
        request.recognitionLevel = .accurate
        request.recognitionLanguages = ["vi-VN", "en-US"]
        request.usesLanguageCorrection = true
        do {
            try VNImageRequestHandler(cgImage: cgImage, options: [:]).perform([request])
            let observations = (request.results ?? []).sorted {
                if abs($0.boundingBox.midY - $1.boundingBox.midY) > 0.012 {
                    return $0.boundingBox.midY > $1.boundingBox.midY
                }
                return $0.boundingBox.minX < $1.boundingBox.minX
            }
            let lines = observations.compactMap { $0.topCandidates(1).first?.string }
            if !lines.isEmpty {
                print("\n--- PAGE \(pageIndex + 1) ---")
                print(lines.joined(separator: "\n"))
            }
        } catch {
            FileHandle.standardError.write(Data("page \(pageIndex + 1): \(error)\n".utf8))
        }
    }
}
