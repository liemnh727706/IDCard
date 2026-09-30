import Foundation

public struct FaceVerifyResult {
    public let similarity: Double
    public let decision: String
    public let matchThreshold: Double
    public let rejectThreshold: Double
    public var label: String {
        switch decision {
        case "match": return "KHOP"
        case "no_match": return "KHONG KHOP"
        default: return "KHONG CHAC CHAN - can kiem tra thu cong"
        }
    }
}

public enum FaceIdError: Error, CustomStringConvertible {
    case message(String)
    public var description: String { if case .message(let m) = self { return m } else { return "" } }
}

/// Goi FaceID service tu host (InsightFace) de so khop 1:1 anh chan dung voi anh chup live.
/// KHONG gui du lieu toi ben thu ba nao - day la server do ban tu dung va tu van hanh.
public enum FaceIdClient {
    private static let userAgent = "EmvCardReaderIOS/1.0 (iOS)"

    public static func verify(url: String, apiKey: String, imageA: Data, imageB: Data,
                              completion: @escaping (Result<FaceVerifyResult, FaceIdError>) -> Void) {
        guard let endpoint = URL(string: url.trimmingCharacters(in: CharacterSet(charactersIn: "/")) + "/face/verify") else {
            completion(.failure(.message("URL khong hop le: \(url)"))); return
        }
        let boundary = "----EmvCardReaderIOS\(UUID().uuidString)"
        var req = URLRequest(url: endpoint)
        req.httpMethod = "POST"
        req.timeoutInterval = 90 // lan goi dau server phai nap 5 model ONNX, co the mat gan 1 phut
        req.setValue(apiKey, forHTTPHeaderField: "X-API-Key")
        req.setValue(userAgent, forHTTPHeaderField: "User-Agent")
        req.setValue("multipart/form-data; boundary=\(boundary)", forHTTPHeaderField: "Content-Type")

        var body = Data()
        func addPart(name: String, filename: String, data: Data) {
            body.append("--\(boundary)\r\n".data(using: .utf8)!)
            body.append("Content-Disposition: form-data; name=\"\(name)\"; filename=\"\(filename)\"\r\n".data(using: .utf8)!)
            body.append("Content-Type: image/jpeg\r\n\r\n".data(using: .utf8)!)
            body.append(data)
            body.append("\r\n".data(using: .utf8)!)
        }
        addPart(name: "image_a", filename: "a.jpg", data: imageA)
        addPart(name: "image_b", filename: "b.jpg", data: imageB)
        body.append("--\(boundary)--\r\n".data(using: .utf8)!)
        req.httpBody = body

        URLSession.shared.dataTask(with: req) { data, response, error in
            if let error = error {
                completion(.failure(.message("Khong ket noi duoc toi FaceID service (\(url)): \(error.localizedDescription). "
                    + "Kiem tra may co Internet, dia chi server dung chua.")))
                return
            }
            guard let http = response as? HTTPURLResponse, let data = data else {
                completion(.failure(.message("Khong nhan duoc phan hoi tu server"))); return
            }
            guard (200...299).contains(http.statusCode) else {
                let detail = (try? JSONSerialization.jsonObject(with: data) as? [String: Any])??["detail"] as? String
                    ?? String(data: data, encoding: .utf8) ?? ""
                completion(.failure(.message("Server tra loi (\(http.statusCode)): \(detail)")))
                return
            }
            guard let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                  let similarity = json["similarity"] as? Double, let decision = json["decision"] as? String,
                  let thresholds = json["thresholds"] as? [String: Any],
                  let matchTh = thresholds["match"] as? Double, let rejectTh = thresholds["reject"] as? Double else {
                completion(.failure(.message("Phan hoi khong dung dinh dang JSON mong doi")))
                return
            }
            completion(.success(FaceVerifyResult(similarity: similarity, decision: decision,
                                                 matchThreshold: matchTh, rejectThreshold: rejectTh)))
        }.resume()
    }
}
