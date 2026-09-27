"""Dựng báo cáo (report.py) từ dữ liệu CCCD đã đọc."""
import datetime

import bac
import report

SEX = {"M": "Nam", "F": "Nữ", "<": "Không xác định"}
COUNTRY = {"VNM": "Việt Nam"}

DG11_TAGS = {
    "5F0E": "Họ tên đầy đủ", "5F0F": "Tên khác", "5F10": "Số định danh cá nhân", "5F2B": "Ngày sinh (đầy đủ)",
    "5F11": "Nơi sinh", "5F42": "Địa chỉ", "5F12": "Số điện thoại", "5F13": "Nghề nghiệp",
    "5F14": "Chức danh", "5F15": "Tóm tắt cá nhân", "5F16": "Giấy tờ chứng minh quốc tịch",
    "5F17": "Số khác", "5F18": "Thông tin người giám hộ",
}
DG12_TAGS = {"5F19": "Cơ quan cấp", "5F26": "Ngày cấp", "5F1B": "Ghi chú", "5F1C": "Chi tiết khác",
             "5F55": "Thời điểm cá nhân hóa"}
DATE_TAGS = {"5F2B", "5F26"}


def fmt_yymmdd(s: str, kind: str) -> str:
    """Đổi YYMMDD sang dd/mm/yyyy. Ngày sinh: năm lớn hơn năm hiện tại thì hiểu là thế kỷ 20."""
    if len(s) != 6 or not s.isdigit():
        return s
    yy = int(s[:2])
    now = datetime.date.today().year % 100
    year = (1900 + yy) if (kind == "dob" and yy > now) else 2000 + yy
    return f"{s[4:6]}/{s[2:4]}/{year}"


def _value(tag: str, v: bytes) -> str:
    if tag in DATE_TAGS:
        h = v.hex()
        if len(h) == 8 and h.isdigit():
            return f"{h[6:8]}/{h[4:6]}/{h[0:4]}"
        t = v.decode("ascii", "ignore")
        if len(t) == 8 and t.isdigit():
            return f"{t[6:8]}/{t[4:6]}/{t[0:4]}"
    return bac.decode_text(v).replace("<", " ").strip()


def tag_rows(data: bytes, tagmap: dict):
    rows = []
    stack = list(bac.parse_tlv(data))
    while stack:
        tag, val, cons = stack.pop(0)
        if cons:
            stack = list(bac.parse_tlv(val)) + stack
        elif tag in tagmap and val.strip(b"\x00 "):
            rows.append((tagmap[tag], _value(tag, val)))
    return rows


def build(fields, mrz_lines, lds, present, extras, photo, atr, reader):
    """extras: {'DG11': (size, dump_lines, data_bytes), ...}; photo: bytes | None."""
    main = []
    if fields:
        main = [
            ("Họ tên (theo MRZ, không dấu)", fields.get("Họ tên (MRZ, không dấu)", "")),
            ("Số giấy tờ", fields.get("Số giấy tờ", "")),
            ("Ngày sinh", fmt_yymmdd(fields.get("Ngày sinh (YYMMDD)", ""), "dob")),
            ("Giới tính", SEX.get(fields.get("Giới tính", ""), fields.get("Giới tính", ""))),
            ("Ngày hết hạn", fmt_yymmdd(fields.get("Hết hạn (YYMMDD)", ""), "doe")),
            ("Quốc tịch", COUNTRY.get(fields.get("Quốc tịch", ""), fields.get("Quốc tịch", ""))),
            ("Nơi cấp (quốc gia)", COUNTRY.get(fields.get("Quốc gia cấp", ""), fields.get("Quốc gia cấp", ""))),
            ("Loại giấy tờ (mã)", fields.get("Loại giấy tờ", "")),
        ]
        opt = " ".join(x for x in (fields.get("Trường tùy chọn 1"), fields.get("Trường tùy chọn 2")) if x)
        if opt:
            main.append(("Trường tùy chọn trong MRZ", opt))
    sections = [{"heading": "Thông tin chính (DG1)", "rows": [r for r in main if r[1]] or [("Không đọc được DG1", "")],
                 "photo": photo}]

    extra_rows, dg13 = [], ""
    for name, (size, dump, data) in extras.items():
        body = _strip_head(data)
        if name == "DG11":
            extra_rows += [(f"{k} (DG11)", v) for k, v in tag_rows(body, DG11_TAGS)]
        elif name == "DG12":
            extra_rows += [(f"{k} (DG12)", v) for k, v in tag_rows(body, DG12_TAGS)]
        elif name == "DG13":
            dg13 = "\n".join(dump)
    if extra_rows:
        sections.append({"heading": "Thông tin kèm theo trên chip (DG11/DG12)", "rows": extra_rows})
    if dg13:
        sections.append({"heading": "Dữ liệu riêng của thẻ (DG13)", "text": dg13})
    elif extras:
        sections.append({"heading": "Thông tin kèm theo", "text": "Các file bổ sung (DG11/12/13) bị khóa hoặc không có dữ liệu."})

    tech = [("Phiên bản LDS", lds or "(không rõ)"),
            ("File dữ liệu có trên thẻ", ", ".join(present) or "(không rõ)"),
            ("Xác thực truy cập", "BAC (Basic Access Control) thành công"),
            ("Đầu đọc", reader), ("ATR", atr.hex().upper()),
            ("Xác thực chữ ký số (SOD)", "CHƯA thực hiện - chưa chứng minh dữ liệu là nguyên bản")]
    sections.append({"heading": "Thông tin kỹ thuật", "rows": tech,
                     "text": "MRZ nguyên văn:\n" + "\n".join(mrz_lines) if mrz_lines else ""})
    return report.new_report("Báo cáo đọc Căn cước công dân gắn chip", sections)


def _strip_head(f: bytes) -> bytes:
    if len(f) < 2:
        return b""
    ln = f[1]
    return f[2:] if ln < 0x80 else f[2 + (ln & 0x7F):]
