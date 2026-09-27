import UIKit
import Vision

/// OCR ảnh chạy hoàn toàn trên máy bằng Vision framework, không gửi ảnh đi đâu.
enum TextRecognizer {
    static func recognize(_ image: UIImage, completion: @escaping (Result<String, Error>) -> Void) {
        guard let cgImage = image.cgImage else {
            completion(.failure(BacError.message("Không đọc được ảnh"))); return
        }
        let request = VNRecognizeTextRequest { request, error in
            if let error = error { completion(.failure(error)); return }
            let text = (request.results as? [VNRecognizedTextObservation] ?? [])
                .compactMap { $0.topCandidates(1).first?.string }
                .joined(separator: "\n")
            completion(.success(text))
        }
        request.recognitionLevel = .accurate
        request.usesLanguageCorrection = false
        request.recognitionLanguages = ["vi-VN", "en-US"]
        DispatchQueue.global(qos: .userInitiated).async {
            do { try VNImageRequestHandler(cgImage: cgImage, orientation: cgOrientation(image.imageOrientation)).perform([request]) }
            catch { completion(.failure(error)) }
        }
    }

    private static func cgOrientation(_ o: UIImage.Orientation) -> CGImagePropertyOrientation {
        switch o {
        case .up: return .up
        case .down: return .down
        case .left: return .left
        case .right: return .right
        case .upMirrored: return .upMirrored
        case .downMirrored: return .downMirrored
        case .leftMirrored: return .leftMirrored
        case .rightMirrored: return .rightMirrored
        @unknown default: return .up
        }
    }

    /// Vision đọc rất tốt chữ/số nhưng thường bỏ dòng chứa nhiều ký tự '<' liền nhau (đệm MRZ).
    /// Cắt ảnh theo nhiều mức bề ngang để tránh phần đệm, giống chiến lược của bản Windows.
    static func recognizeMultiCrop(_ image: UIImage, completion: @escaping (String) -> Void) {
        let fractions = stride(from: 0.2, through: 0.98, by: 0.03).map { $0 }
        var results: [String] = []
        let group = DispatchGroup()
        group.enter()
        recognize(image) { if case .success(let t) = $0 { results.append(t) }; group.leave() }
        for f in fractions {
            guard let cg = image.cgImage else { continue }
            let w = max(Int(Double(cg.width) * f), 50)
            guard let cropped = cg.cropping(to: CGRect(x: 0, y: 0, width: w, height: cg.height)) else { continue }
            let img = UIImage(cgImage: cropped, scale: image.scale, orientation: image.imageOrientation)
            group.enter()
            recognize(img) { if case .success(let t) = $0 { results.append(t) }; group.leave() }
        }
        group.notify(queue: .main) { completion(results.joined(separator: "\n")) }
    }
}
