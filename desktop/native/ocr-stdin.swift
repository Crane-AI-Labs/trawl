// Apple Vision OCR as a long-lived helper: reads one image path per line on stdin, prints one JSON line per image
// ({"path":..., "text":...}) and flushes, so the JVM pays the process start once. The macOS upgrade over the portable
// PP-OCR path; nothing is sent anywhere (Vision runs on the device).
import Foundation
import Vision
import AppKit
setvbuf(stdout, nil, _IOLBF, 0)
while let path = readLine() {
    var text = ""
    if let image = NSImage(contentsOfFile: path), let cg = image.cgImage(forProposedRect: nil, context: nil, hints: nil) {
        let request = VNRecognizeTextRequest()
        request.recognitionLevel = .accurate
        request.usesLanguageCorrection = true
        try? VNImageRequestHandler(cgImage: cg).perform([request])
        text = (request.results ?? []).compactMap { $0.topCandidates(1).first?.string }.joined(separator: "\n")
    }
    let obj = ["path": path, "text": text]
    if let data = try? JSONSerialization.data(withJSONObject: obj), let line = String(data: data, encoding: .utf8) { print(line) }
}
