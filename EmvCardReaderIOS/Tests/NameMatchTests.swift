import XCTest
@testable import EmvCardReader

final class NameMatchTests: XCTestCase {
    func testExtractsNameWithLabelUnaccented() {
        XCTAssertEqual(extractOcrName("Ho va ten: NGUYEN VAN A\nMSSV: 21123456"), "NGUYEN VAN A")
    }

    func testExtractsNameWithLabelAccented() {
        XCTAssertEqual(extractOcrName("TRUONG DAI HOC\nHọ và tên: NGUYỄN VĂN A\nMSSV: 21123456"), "NGUYỄN VĂN A")
    }

    func testExtractsNameFullNameLabel() {
        XCTAssertEqual(extractOcrName("FULL NAME: TRAN VAN B\nID: 123"), "TRAN VAN B")
    }

    func testReturnsNilWithoutLabel() {
        XCTAssertNil(extractOcrName("khong co nhan gi ca\n1234567890"))
    }

    func testNamesMatchIgnoringDiacriticsAndOrder() {
        XCTAssertTrue(namesMatch("NGUYEN VAN A", "Nguyễn Văn A"))
        XCTAssertTrue(namesMatch("DANG THI BICH NGOC", "Đặng Thị Bích Ngọc"))
    }

    func testNamesMismatchDifferentPerson() {
        XCTAssertFalse(namesMatch("NGUYEN VAN A", "Nguyễn Văn B"))
    }
}
