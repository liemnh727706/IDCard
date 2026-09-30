"""Phân tích văn bản OCR mặt thẻ và so khớp với dữ liệu chip."""
import re
import unicodedata

import banks

LEVELS = ("MATCH", "PARTIAL", "MISMATCH", "MISSING")

def strip_diacritics(s: str) -> str:
    n = unicodedata.normalize("NFD", s)
    n = "".join(c for c in n if unicodedata.category(c) != "Mn")
    return n.replace("Đ", "D").replace("đ", "d")


# Nhãn thường in trước họ tên trên thẻ sinh viên/CCCD. So khớp trên bản ĐÃ BỎ DẤU của dòng OCR
# (để không phụ thuộc OCR có nhận đúng dấu ở phần nhãn hay không), rồi cắt lấy phần còn lại của
# DÒNG GỐC (có dấu) từ đúng vị trí đó - bỏ dấu bằng NFD không đổi số ký tự nên vị trí khớp 1-1.
_NAME_LABEL_RE = re.compile(r"(?:ho\s*va\s*ten|ho\s*ten|full\s*name|name)\s*[:\-]?\s*", re.IGNORECASE)


def extract_name(text: str):
    """Tìm dòng chứa họ tên có dấu trên ảnh OCR (thẻ sinh viên/CCCD), ưu tiên theo nhãn
    "Họ và tên"/"Full name". Trả về chuỗi tên nguyên dấu, hoặc None nếu không thấy."""
    best = None
    for raw in text.splitlines():
        line = raw.strip()
        if not line:
            continue
        m = _NAME_LABEL_RE.search(strip_diacritics(line))
        candidate = line[m.end():].strip() if m else None
        if candidate is None:
            continue
        # Loại bỏ nhiễu OCR: chỉ giữ chữ cái (có dấu), khoảng trắng, dấu chấm ở cuối
        candidate = re.sub(r"[^A-Za-zÀ-ỹ\s]", " ", candidate).strip()
        candidate = re.sub(r"\s{2,}", " ", candidate)
        if not candidate:
            continue
        words = candidate.split(" ")
        # Tên hợp lệ: 2-6 từ, từ nào cũng viết hoa chữ đầu (OCR thẻ SV/CCCD thường in hoa cả dòng
        # nên chấp nhận cả ALL-CAPS), không lẫn số.
        if 2 <= len(words) <= 6 and all(w[0].isalpha() for w in words):
            best = candidate
            break
    return best


def name_matches(chip_name_no_diacritics: str, ocr_name_with_diacritics: str) -> bool:
    """So khớp tên không dấu (chip) với tên có dấu (OCR) sau khi bỏ dấu cả hai."""
    a = strip_diacritics(chip_name_no_diacritics).upper().split()
    b = strip_diacritics(ocr_name_with_diacritics).upper().split()
    return sorted(a) == sorted(b)


def parse_ocr(text: str):
    pans, exps = [], []
    for line in text.splitlines():
        if sum(c.isdigit() or c in "OoIlSB" for c in line) >= 12:
            fixed = "".join({"O": "0", "o": "0", "I": "1", "l": "1", "S": "5", "B": "8"}.get(c, c) for c in line)
            for m in re.finditer(r"(?:\d[ \-]?){13,19}", fixed):
                d = re.sub(r"\D", "", m.group())
                if 13 <= len(d) <= 19 and d not in pans:
                    pans.append(d)
    for m in re.finditer(r"(0[1-9]|1[0-2])\s*[/\-]\s*(\d{2})(?!\d)", text):
        v = m.group(2) + m.group(1)
        if v not in exps:
            exps.append(v)
    return {"pans": pans, "expiries": exps, "text": text, "name": extract_name(text)}


def _fmt(yymm):
    return f"{yymm[2:]}/{yymm[:2]}"


def _name_tokens(name: str):
    n = banks.normalize(name.replace("/", " "))
    return [t for t in re.split(r"[^A-Z]+", n) if t]


def compare(ocr, chip):
    """Trả về (danh sách dòng, kết luận). chip là emv.ChipInfo."""
    lines, lv = [], {}
    masked = lambda p: p[:6] + "*" * (len(p) - 10) + p[-4:] if len(p) > 10 else p

    if not ocr["pans"]:
        lv["pan"] = "MISSING"; lines.append("Số thẻ in: OCR không tìm thấy dãy 13-19 chữ số")
    elif chip.pan in ocr["pans"]:
        lv["pan"] = "MATCH"; lines.append(f"Số thẻ in: {masked(chip.pan)} = chip (khớp toàn bộ)")
    else:
        close = [p for p in ocr["pans"] if len(p) >= 10 and p[:6] == chip.pan[:6] and p[-4:] == chip.pan[-4:]]
        if close:
            lv["pan"] = "PARTIAL"; lines.append(f"Số thẻ in: {masked(close[0])} khớp 6 đầu + 4 cuối, khác độ dài/giữa")
        else:
            lv["pan"] = "MISMATCH"
            lines.append("Số thẻ in: " + ", ".join(masked(p) for p in ocr["pans"]) + f" khác chip {masked(chip.pan)}")

    if not chip.expiry_yymm:
        lv["exp"] = "MISSING"; lines.append("Hạn dùng chip: không có")
    elif not ocr["expiries"]:
        lv["exp"] = "MISSING"; lines.append("Hạn dùng in: OCR không tìm thấy dạng MM/YY")
    elif chip.expiry_yymm in ocr["expiries"]:
        lv["exp"] = "MATCH"; lines.append(f"Hạn dùng in: {_fmt(chip.expiry_yymm)} = chip (khớp)")
    else:
        lv["exp"] = "MISMATCH"
        lines.append("Hạn dùng in: " + ", ".join(_fmt(e) for e in ocr["expiries"]) + f" khác chip {_fmt(chip.expiry_yymm)}")

    cb = banks.lookup(chip.pan)
    ment = banks.mentioned_in(ocr["text"])
    if not cb:
        lv["bank"] = "MISSING"; lines.append(f"Ngân hàng: BIN {chip.pan[:6]} chưa có trong bảng tra")
    elif any(b == chip.pan[:6] for b, _ in ment):
        lv["bank"] = "MATCH"; lines.append(f"Ngân hàng: chip là {cb[0]}, tên in trên thẻ cũng là {cb[0]} (khớp)")
    elif ment:
        lv["bank"] = "MISMATCH"; lines.append(f"Ngân hàng: chip là {cb[0]} nhưng thẻ in tên {', '.join(n for _, n in ment)}")
    else:
        lv["bank"] = "MISSING"; lines.append(f"Ngân hàng: chip là {cb[0]}, OCR không thấy tên ngân hàng in (có thể chỉ có logo)")

    ocr_name = ocr.get("name")
    if not chip.name:
        lv["name"] = "MISSING"; lines.append("Họ tên: chip không lưu tên, không so khớp được")
    elif ocr_name:
        if name_matches(chip.name, ocr_name):
            lv["name"] = "MATCH"
            lines.append(f"Họ tên: OCR đọc \"{ocr_name}\" (có dấu), khớp với tên chip \"{chip.name}\" (không dấu)")
        else:
            toks = _name_tokens(chip.name)
            hit = [t for t in toks if t in strip_diacritics(ocr_name).upper().split()]
            if toks and hit:
                lv["name"] = "PARTIAL"
                lines.append(f"Họ tên: OCR đọc \"{ocr_name}\", chỉ khớp {len(hit)}/{len(toks)} từ so với tên chip \"{chip.name}\"")
            else:
                lv["name"] = "MISMATCH"
                lines.append(f"Họ tên: OCR đọc \"{ocr_name}\", KHÔNG khớp với tên chip \"{chip.name}\"")
    else:
        toks = _name_tokens(chip.name)
        printed = banks.normalize(ocr["text"])
        hit = [t for t in toks if re.search(r"(?<![A-Z])" + t + r"(?![A-Z])", printed)]
        if toks and len(hit) == len(toks):
            lv["name"] = "MATCH"; lines.append("Họ tên: tên trong chip xuất hiện đủ trên mặt thẻ (khớp, chưa xác định được dạng có dấu)")
        elif hit:
            lv["name"] = "PARTIAL"; lines.append(f"Họ tên: chỉ thấy {len(hit)}/{len(toks)} từ của tên chip trên mặt thẻ")
        else:
            lv["name"] = "MISMATCH"; lines.append("Họ tên: tên trong chip không thấy trên mặt thẻ (hoặc OCR chưa đọc được tên)")

    if "MISMATCH" in (lv["pan"], lv["exp"], lv["bank"]):
        verdict = "KHÔNG KHỚP: dữ liệu in khác với chip, nghi ngờ thẻ bị chỉnh sửa hoặc OCR sai"
    elif lv["pan"] == "MATCH" and lv["exp"] == "MATCH":
        verdict = "KHỚP: số thẻ và hạn dùng in trùng với chip"
        if lv["name"] == "MATCH":
            verdict += ", cả họ tên"
        elif lv["name"] in ("MISMATCH", "PARTIAL"):
            verdict += ", nhưng họ tên chưa xác nhận được, hãy kiểm tra bằng mắt"
    else:
        verdict = "CHƯA KẾT LUẬN: OCR chưa đủ dữ liệu, thử ảnh rõ hơn"
    return lines, verdict
