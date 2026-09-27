import CoreNFC
import Foundation

/// Bọc NFCTagReaderSession để đọc chip ISO7816 (thẻ ngân hàng EMV hoặc CCCD/eMRTD).
@available(iOS 13.0, *)
final class NfcSession: NSObject, NFCTagReaderSessionDelegate {
    private var session: NFCTagReaderSession?
    private var onTag: ((Transceiver) -> Void)?
    private var onError: ((String) -> Void)?
    private var alertMessage: String

    init(alertMessage: String) {
        self.alertMessage = alertMessage
    }

    /// Bắt đầu một phiên đọc. `body` chạy trên nền khi phát hiện thẻ, nhận vào một Transceiver.
    /// Gọi `finish(message:)` bên trong hoặc sau khi `body` xong để đóng phiên với thông báo trên UI hệ thống.
    func start(onTag: @escaping (Transceiver, @escaping (String?) -> Void) -> Void, onError: @escaping (String) -> Void) {
        self.onError = onError
        guard NFCTagReaderSession.readingAvailable else {
            onError("Thiết bị này không hỗ trợ đọc NFC."); return
        }
        session = NFCTagReaderSession(pollingOption: [.iso14443], delegate: self, queue: nil)
        session?.alertMessage = alertMessage
        self.onTag = { tx in onTag(tx) { msg in self.finish(message: msg) } }
        session?.begin()
    }

    func finish(message: String?) {
        if let m = message { session?.alertMessage = m }
        session?.invalidate()
    }

    func fail(_ message: String) {
        session?.invalidate(errorMessage: message)
    }

    func tagReaderSessionDidBecomeActive(_ session: NFCTagReaderSession) {}

    func tagReaderSession(_ session: NFCTagReaderSession, didInvalidateWithError error: Error) {
        let nsErr = error as NSError
        if nsErr.domain == NFCErrorDomain, nsErr.code == NFCReaderError.readerSessionInvalidationErrorUserCanceled.rawValue { return }
        onError?(error.localizedDescription)
    }

    func tagReaderSession(_ session: NFCTagReaderSession, didDetect tags: [NFCTag]) {
        guard let tag = tags.first else { return }
        session.connect(to: tag) { [weak self] error in
            guard let self = self else { return }
            if let error = error {
                session.invalidate(errorMessage: "Không kết nối được thẻ: \(error.localizedDescription)")
                return
            }
            guard case let .iso7816(iso7816Tag) = tag else {
                session.invalidate(errorMessage: "Thẻ không hỗ trợ ISO7816 (không phải chip EMV/CCCD).")
                return
            }
            let tx: Transceiver = { apdu in
                var result: Data = Data()
                var txError: Error?
                let sema = DispatchSemaphore(value: 0)
                guard let command = NFCISO7816APDU(data: apdu) else {
                    throw BacError.message("APDU không hợp lệ")
                }
                iso7816Tag.sendCommand(apdu: command) { data, sw1, sw2, error in
                    if let error = error { txError = error } else { result = data + Data([sw1, sw2]) }
                    sema.signal()
                }
                sema.wait()
                if let e = txError { throw e }
                return result
            }
            self.onTag?(tx)
        }
    }
}
