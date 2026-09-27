"""Bảng BIN thẻ nội địa Napas (tham khảo, cần cập nhật khi Napas đổi)."""
import re
import unicodedata

BANKS = {
    "970400": ('SaigonBank', ['SAIGONBANK', 'SGB']),
    "970403": ('Sacombank', ['SACOMBANK']),
    "970405": ('Agribank', ['AGRIBANK']),
    "970406": ('DongA Bank', ['DONGA BANK', 'DONGABANK']),
    "970407": ('Techcombank', ['TECHCOMBANK']),
    "970408": ('GPBank', ['GPBANK']),
    "970409": ('BacABank', ['BACABANK', 'BAC A BANK']),
    "970412": ('PVcomBank', ['PVCOMBANK']),
    "970415": ('VietinBank', ['VIETINBANK']),
    "970416": ('ACB', ['ACB']),
    "970418": ('BIDV', ['BIDV', 'DAU TU VA PHAT TRIEN']),
    "970419": ('NCB', ['NCB', 'QUOC DAN']),
    "970422": ('MB (Quân đội)', ['MBBANK', 'MB BANK', 'QUAN DOI']),
    "970423": ('TPBank', ['TPBANK', 'TIEN PHONG']),
    "970424": ('Shinhan Bank VN', ['SHINHAN']),
    "970425": ('ABBank', ['ABBANK', 'AN BINH']),
    "970426": ('MSB', ['MSB', 'HANG HAI']),
    "970427": ('VietABank', ['VIETABANK']),
    "970428": ('NamABank', ['NAMABANK', 'NAM A BANK']),
    "970429": ('SCB', ['SCB']),
    "970430": ('PGBank', ['PGBANK']),
    "970431": ('Eximbank', ['EXIMBANK']),
    "970432": ('VPBank', ['VPBANK']),
    "970433": ('VietBank', ['VIETBANK']),
    "970436": ('Vietcombank', ['VIETCOMBANK', 'NGOAI THUONG']),
    "970437": ('HDBank', ['HDBANK']),
    "970438": ('BaoVietBank', ['BAOVIETBANK']),
    "970440": ('SeABank', ['SEABANK']),
    "970441": ('VIB', ['VIB', 'QUOC TE']),
    "970443": ('SHB', ['SHB', 'SAI GON HA NOI']),
    "970444": ('CBBank', ['CBBANK']),
    "970446": ('Co-opBank', ['CO-OPBANK', 'COOPBANK', 'HOP TAC XA']),
    "970448": ('OCB', ['OCB', 'PHUONG DONG']),
    "970449": ('LPBank', ['LPBANK', 'LIENVIETPOSTBANK', 'BUU DIEN LIEN VIET']),
    "970452": ('KienLongBank', ['KIENLONGBANK']),
    "970454": ('BVBank (Bản Việt)', ['BVBANK', 'VIETCAPITALBANK', 'BAN VIET']),
    "970457": ('Woori Bank VN', ['WOORI']),
    "970458": ('UOB VN', ['UOB']),
}


def normalize(s: str) -> str:
    s = unicodedata.normalize("NFD", s)
    s = "".join(c for c in s if unicodedata.category(c) != "Mn")
    return s.replace("Đ", "D").replace("đ", "d").upper()


def lookup(pan: str):
    return BANKS.get(pan[:6]) if len(pan) >= 6 else None


def describe(pan: str) -> str:
    if len(pan) < 6:
        return "không xác định"
    b = pan[:6]
    if b in BANKS:
        return f"{BANKS[b][0]} (BIN {b}, thẻ nội địa Napas)"
    if pan.startswith("97"):
        return f"thẻ nội địa Napas, BIN {b} chưa có trong bảng tra"
    two, four = int(pan[:2]), int(pan[:4]) if pan[:4].isdigit() else 0
    if pan.startswith("4"):
        return f"Visa (BIN {b}), chưa tra được ngân hàng"
    if 51 <= two <= 55 or 2221 <= four <= 2720:
        return f"Mastercard (BIN {b}), chưa tra được ngân hàng"
    if pan.startswith("35"):
        return f"JCB (BIN {b}), chưa tra được ngân hàng"
    if pan.startswith("62"):
        return f"UnionPay (BIN {b}), chưa tra được ngân hàng"
    return f"BIN {b} chưa có trong bảng tra"


def mentioned_in(text: str):
    t = normalize(text)
    found = []
    for bin_, (name, aliases) in BANKS.items():
        if any(re.search(r"(?<![A-Z0-9])" + re.escape(a) + r"(?![A-Z0-9])", t) for a in aliases):
            found.append((bin_, name))
    return found
