"""Trích 3 khóa BAC từ văn bản OCR của dòng MRZ TD1 (CCCD). Chỉ nhận khi chữ số kiểm tra khớp."""
FIX = {"O": "0", "Q": "0", "D": "0", "I": "1", "L": "1", "Z": "2", "S": "5", "B": "8", "G": "6"}


def check_digit(s: str) -> int:
    w, tot = (7, 3, 1), 0
    for i, ch in enumerate(s):
        v = int(ch) if ch.isdigit() else (ord(ch) - 55 if ch.isalpha() else 0)
        tot += v * w[i % 3]
    return tot % 10


def _digits(s):
    out = []
    for ch in s:
        if ch.isdigit():
            out.append(ch)
        elif ch in FIX:
            out.append(FIX[ch])
        else:
            return None
    return "".join(out)


def _valid_date(d):
    return 1 <= int(d[2:4]) <= 12 and 1 <= int(d[4:6]) <= 31


def _norm(t: str) -> str:
    return "".join(ch for ch in t.upper().replace("«", "<").replace("‹", "<").replace("〈", "<")
                   if not ch.isspace())


def _scan(s: str):
    """Quét một chuỗi liền, trả về (doc, doc_score, (dob, doe), date_score)."""
    dates, dscore = None, -1
    for i in range(len(s) - 14):
        w = s[i:i + 15]
        b, bc, e, ec = _digits(w[0:6]), _digits(w[6]), _digits(w[8:14]), _digits(w[14])
        if None in (b, bc, e, ec) or w[7] not in "MF<":
            continue
        if check_digit(b) == int(bc) and check_digit(e) == int(ec) and _valid_date(b) and _valid_date(e):
            score = 2 if s[i + 15:i + 18] == "VNM" else 1
            if score > dscore:
                dscore, dates = score, (b, e)
    doc, best = None, -1
    j = s.find("VNM")
    while j >= 0:
        if j + 13 <= len(s):
            raw, cd = s[j + 3:j + 12], _digits(s[j + 12])
            cand = None
            if cd is not None:
                if check_digit(raw) == int(cd):
                    cand = raw.replace("<", "")
                else:
                    d = _digits(raw)
                    if d and check_digit(d) == int(cd):
                        cand = d
            if cand:
                score = (3 if s[max(j - 2, 0):j] in ("ID", "1D", "I<", "10") else 0) + (1 if cand.isdigit() else 0)
                if score > best:
                    best, doc = score, cand
        j = s.find("VNM", j + 1)
    return doc, best, dates, dscore


def parse(text: str):
    """Trả về (doc, dob, doe, ghi chú) hoặc None nếu không nhận ra gì.

    Quét từng dòng OCR trước (tránh ghép sai qua ranh giới dòng); chỉ khi còn thiếu mới quét chuỗi ghép.
    """
    doc, best, dates, dscore = None, -1, None, -1
    for chunk in [_norm(x) for x in text.splitlines()] + [_norm(text)]:
        if doc and dates and chunk == _norm(text):
            break
        d, ds, dt, dts = _scan(chunk)
        if d and ds > best:
            doc, best = d, ds
        if dt and dts > dscore:
            dates, dscore = dt, dts
    dob, doe = dates if dates else (None, None)
    if doc is None and dob is None:
        return None
    if doc and dob and doe:
        note = "Đã đọc đủ số giấy tờ, ngày sinh, ngày hết hạn (chữ số kiểm tra khớp)."
    else:
        note = (f"Mới đọc được một phần (số giấy tờ: {'có' if doc else 'thiếu'}, "
                f"ngày tháng: {'có' if dob and doe else 'thiếu'}). Hãy chụp lại rõ hơn hoặc nhập tay phần thiếu.")
    return doc, dob, doe, note
