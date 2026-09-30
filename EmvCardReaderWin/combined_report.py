"""Gộp thông tin đã đọc/xác thực từ thẻ ngân hàng (SV), CCCD và FaceID thành một báo cáo."""
import banks
import emv
import match
import report

SEX = {"M": "Nam", "F": "Nữ", "<": "Không xác định"}
COUNTRY = {"VNM": "Việt Nam"}


def _fmt_yymmdd(s: str, dob: bool) -> str:
    import datetime
    if len(s) != 6 or not s.isdigit():
        return s
    yy = int(s[:2])
    now = datetime.date.today().year % 100
    year = (1900 + yy) if (dob and yy > now) else 2000 + yy
    return f"{s[4:6]}/{s[2:4]}/{year}"


def build(app):
    """app: instance App (có .chip, .ocr, .show_full, .cccd_tab, .faceid_tab)."""
    chip = app.chip
    cccd_fields = app.cccd_tab.fields
    if not (chip and chip.pan) and not cccd_fields:
        return None

    sections = []
    identity_rows = []
    names_found = []  # [(nguồn, tên)]

    ocr_name = app.ocr.get("name") if app.ocr else None
    if ocr_name:
        names_found.append(("OCR ảnh thẻ (có dấu)", ocr_name))
    if chip and chip.name:
        names_found.append(("Chip thẻ ngân hàng/SV (không dấu)", chip.name))
    cccd_name = (cccd_fields or {}).get("Họ tên (MRZ, không dấu)")
    if cccd_name:
        names_found.append(("Chip CCCD - MRZ (không dấu)", cccd_name))

    for src, name in names_found:
        identity_rows.append((f"Họ tên - {src}", name))

    # Đối chiếu chéo: các nguồn không dấu có khớp nhau không, và có khớp tên OCR có dấu không.
    no_diacritic_names = [n for s, n in names_found if "không dấu" in s]
    cross_lines = []
    if len(no_diacritic_names) >= 2:
        base = match.strip_diacritics(no_diacritic_names[0]).upper().split()
        all_same = all(match.strip_diacritics(n).upper().split() == base for n in no_diacritic_names[1:])
        cross_lines.append("Tên trên chip thẻ ngân hàng/SV và chip CCCD: " +
                           ("KHỚP nhau" if all_same else "KHÔNG khớp nhau - kiểm tra lại có đúng cùng một người không"))
    if ocr_name and no_diacritic_names:
        ok = any(match.name_matches(n, ocr_name) for n in no_diacritic_names)
        cross_lines.append(f"Tên OCR có dấu \"{ocr_name}\" so với tên trên chip: " +
                           ("KHỚP" if ok else "KHÔNG khớp hoặc chưa xác nhận được"))

    sections.append({"heading": "Họ tên - đối chiếu nhiều nguồn", "rows": identity_rows,
                     "text": "\n".join("- " + x for x in cross_lines) if cross_lines else ""})

    if chip and chip.pan:
        full = app.show_full.get()
        pan = chip.pan if full else emv.mask_pan_digits(chip.pan)
        exp = f"{chip.expiry_yymm[2:]}/{chip.expiry_yymm[:2]}" if chip.expiry_yymm else "(không có)"
        rows = [("Loại thẻ / ứng dụng", f"{chip.label} (AID {chip.aid})"),
                ("Số thẻ (PAN)", pan + ("" if full else "  [đã che bớt]")),
                ("Hạn dùng (MM/YY)", exp),
                ("Ngân hàng / nhà phát hành", banks.describe(chip.pan))]
        sections.append({"heading": "Thẻ ngân hàng / thẻ sinh viên", "rows": rows})

    if app.ocr_image_bytes:
        sections.append({"heading": "Ảnh mặt thẻ sinh viên (dùng để OCR đối chiếu)",
                         "photo": app.ocr_image_bytes})

    if cccd_fields:
        rows = [
            ("Số giấy tờ (CCCD)", cccd_fields.get("Số giấy tờ", "")),
            ("Ngày sinh", _fmt_yymmdd(cccd_fields.get("Ngày sinh (YYMMDD)", ""), True)),
            ("Giới tính", SEX.get(cccd_fields.get("Giới tính", ""), cccd_fields.get("Giới tính", ""))),
            ("Ngày hết hạn CCCD", _fmt_yymmdd(cccd_fields.get("Hết hạn (YYMMDD)", ""), False)),
            ("Quốc tịch", COUNTRY.get(cccd_fields.get("Quốc tịch", ""), cccd_fields.get("Quốc tịch", ""))),
        ]
        sections.append({"heading": "Căn cước công dân", "rows": [r for r in rows if r[1]],
                         "photo": app.cccd_tab.photo_bytes})

    fr = app.faceid_tab.result
    if fr:
        label = {"match": "KHỚP", "no_match": "KHÔNG KHỚP"}.get(fr["decision"], "KHÔNG CHẮC CHẮN")
        sections.append({"heading": "Xác thực khuôn mặt (FaceID)", "rows": [
            ("Kết luận", label),
            ("Độ giống", f"{fr['similarity'] * 100:.1f}%"),
        ]})
    else:
        sections.append({"heading": "Xác thực khuôn mặt (FaceID)",
                         "text": "Chưa thực hiện kiểm tra chính danh bằng khuôn mặt (xem tab FaceID)."})

    if app.faceid_tab.ref_bytes:
        sections.append({"heading": "Ảnh chân dung tham chiếu (dùng để xác thực FaceID)",
                         "photo": app.faceid_tab.ref_bytes})
    if app.faceid_tab.live_bytes:
        sections.append({"heading": "Ảnh chụp live (người cầm thẻ lúc xác thực)",
                         "photo": app.faceid_tab.live_bytes})

    note = (report.SENSITIVE_NOTE + " Báo cáo kết hợp nhiều nguồn dữ liệu cá nhân (thẻ ngân hàng, "
           "CCCD, khuôn mặt) - bảo quản đặc biệt cẩn thận.")
    return report.new_report("Báo cáo kết hợp: xác thực chính danh thẻ sinh viên + CCCD", sections, note)
