// Apple Vision OCR as a long-lived helper: reads one image path per line on stdin, prints one JSON line per image
// ({"path":..., "text":...}) and flushes, so the JVM pays the process start once. The macOS upgrade over the portable
// PP-OCR path; nothing is sent anywhere (Vision runs on the device).
import Foundation
import Vision
import AppKit
setvbuf(stdout, nil, _IOLBF, 0)
while let path = readLine() {
    autoreleasepool {
        var text = ""
        var lines: [[String: Any]] = []
        if let image = NSImage(contentsOfFile: path), let cg = image.cgImage(forProposedRect: nil, context: nil, hints: nil) {
            let request = VNRecognizeTextRequest()
            request.recognitionLevel = .accurate
            request.usesLanguageCorrection = true
            try? VNImageRequestHandler(cgImage: cg).perform([request])
            for observation in request.results ?? [] {
                guard let candidate = observation.topCandidates(1).first else { continue }
                let deltaX = (observation.topRight.x - observation.topLeft.x) * CGFloat(cg.width)
                let deltaY = (observation.topRight.y - observation.topLeft.y) * CGFloat(cg.height)
                lines.append([
                    "text": candidate.string, "confidence": candidate.confidence,
                    "angle": atan2(deltaY, deltaX) * 180 / .pi,
                    "height": observation.boundingBox.height, "width": observation.boundingBox.width,
                ])
            }
            text = lines.compactMap { $0["text"] as? String }.joined(separator: "\n")
        }
        let obj: [String: Any] = ["path": path, "text": text, "lines": lines]
        if let data = try? JSONSerialization.data(withJSONObject: obj), let line = String(data: data, encoding: .utf8) { print(line) }
    }
}
