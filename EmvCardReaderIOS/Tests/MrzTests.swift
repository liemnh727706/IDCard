import XCTest
@testable import EmvCardReader

final class MrzTests: XCTestCase {
    private let doc = "012345678"
    private let dob = "900215"
    private let doe = "300215"

    private func lines() -> (String, String) {
        let l1 = ("IDVNM" + doc + String(Bac.checkDigit(doc)) + "012345678901<<<").padding(toLength: 30, withPad: "<", startingAt: 0)
        let l2 = (dob + String(Bac.checkDigit(dob)) + "M" + doe + String(Bac.checkDigit(doe)) + "VNM<<<<<<<<<<<0")
            .padding(toLength: 30, withPad: "<", startingAt: 0)
        return (l1, l2)
    }

    func testCleanText() {
        let (l1, l2) = lines()
        let r = Mrz.parse("\(l1)\n\(l2)\nNGUYEN<<VAN<A<<<<<<<<<<<<<<<<<<<")!
        XCTAssertEqual(r.doc, doc); XCTAssertEqual(r.dob, dob); XCTAssertEqual(r.doe, doe)
    }

    func testOcrNoiseLettersAndSpaces() {
        let (l1, l2) = lines()
        let noisy2 = l2.replacingOccurrences(of: "0", with: "O").replacingOccurrences(of: "1", with: "I")
        let chunked = stride(from: 0, to: noisy2.count, by: 5).map { i -> String in
            let start = noisy2.index(noisy2.startIndex, offsetBy: i)
            let end = noisy2.index(start, offsetBy: 5, limitedBy: noisy2.endIndex) ?? noisy2.endIndex
            return String(noisy2[start..<end])
        }.joined(separator: " ")
        let r = Mrz.parse("\(l1)\n\(chunked)")!
        XCTAssertEqual(r.dob, dob); XCTAssertEqual(r.doe, doe)
    }

    func testGarbageReturnsNil() {
        XCTAssertNil(Mrz.parse("HELLO WORLD 12345"))
    }
}
