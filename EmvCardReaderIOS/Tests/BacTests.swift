import CommonCrypto
import XCTest
@testable import EmvCardReader

// Vector mẫu từ ICAO Doc 9303 Part 11, Appendix D
final class BacTests: XCTestCase {
    func testKeysMatchIcaoExample() {
        let info = Bac.mrzInfo(doc: "L898902C<", dob: "690806", doe: "940623")
        XCTAssertEqual(String(data: info, encoding: .ascii), "L898902C<369080619406236")
        let seed = SHA1(info).prefix(16)
        XCTAssertEqual(Bac.derive(seed: seed, c: 1).hexString, "AB94FDECF2674FDFB9B391F85D7F76F2")
        XCTAssertEqual(Bac.derive(seed: seed, c: 2).hexString, "7962D9ECE03D1ACD4C76089DCE131543")
    }

    func testMutualAuthAndSecureMessagingMatchIcaoExample() throws {
        let kEnc = Data(hex: "AB94FDECF2674FDFB9B391F85D7F76F2")
        let kMac = Data(hex: "7962D9ECE03D1ACD4C76089DCE131543")
        var sent: [String] = []
        let tx: Transceiver = { apdu in
            sent.append(apdu.hexString)
            if apdu[apdu.index(apdu.startIndex, offsetBy: 1)] == 0x82 {
                return Data(hex: "46B9342A41396CD7386BF5803104D7CEDC122B9132139BAF2EEDC94EE178534F2F2D235D074D74499000")
            }
            return Data(hex: "9000")
        }
        let session = try Bac.mutualAuth(tx: tx, kEnc: kEnc, kMac: kMac,
                                         rndIc: Data(hex: "4608F91988702212"), rndIfd: Data(hex: "781723860C06C226"),
                                         kIfd: Data(hex: "0B795240CB7049B01C19B33E32804F0B"))
        XCTAssertEqual(sent[0], "008200002872C29C2371CC9BDB65B779B8E8D37B29ECC154AA56A8799FAE2F498F76ED92F25F1448EEA8AD90A728")
        XCTAssertEqual(session.ssc.hexString, "887022120C06C226")
        sent.removeAll()
        _ = try? session.send(cla: 0x00, ins: 0xA4, p1: 0x02, p2: 0x0C, data: Data(hex: "011E"))
        XCTAssertEqual(sent[0], "0CA4020C158709016375432908C044F68E08BF8B92D635FF24F800")
    }

    private func SHA1(_ d: Data) -> Data {
        var digest = [UInt8](repeating: 0, count: 20)
        d.withUnsafeBytes { _ = CC_SHA1($0.baseAddress, CC_LONG(d.count), &digest) }
        return Data(digest)
    }
}
