"""Đọc hộ chiếu điện tử / CCCD gắn chip (ICAO 9303 eMRTD): BAC + Secure Messaging + đọc file."""
import hashlib
import os

from Crypto.Cipher import DES, DES3

AID_EMRTD = bytes.fromhex("A0000002471001")
FILES = {
    "COM": 0x011E, "DG1": 0x0101, "DG2": 0x0102, "DG11": 0x010B,
    "DG12": 0x010C, "DG13": 0x010D, "SOD": 0x011D,
}


class BacError(Exception):
    pass


def check_digit(s: str) -> int:
    w, tot = (7, 3, 1), 0
    for i, ch in enumerate(s):
        v = int(ch) if ch.isdigit() else (ord(ch) - 55 if ch.isalpha() else 0)
        tot += v * w[i % 3]
    return tot % 10


def mrz_info(doc: str, dob: str, doe: str) -> bytes:
    doc = doc.upper().ljust(9, "<")
    s = doc + str(check_digit(doc)) + dob + str(check_digit(dob)) + doe + str(check_digit(doe))
    return s.encode()


def _parity(k: bytes) -> bytes:
    out = bytearray()
    for b in k:
        b &= 0xFE
        out.append(b | (0 if bin(b).count("1") % 2 else 1))
    return bytes(out)


def derive(seed: bytes, c: int) -> bytes:
    h = hashlib.sha1(seed + c.to_bytes(4, "big")).digest()
    return _parity(h[:16])


def _pad(d: bytes) -> bytes:
    d += b"\x80"
    return d + b"\x00" * (-len(d) % 8)


def _unpad(d: bytes) -> bytes:
    i = d.rstrip(b"\x00")
    if not i.endswith(b"\x80"):
        raise BacError("Padding không hợp lệ")
    return i[:-1]


def _enc(key, data):
    return DES3.new(key, DES3.MODE_CBC, iv=b"\x00" * 8).encrypt(data)


def _dec(key, data):
    return DES3.new(key, DES3.MODE_CBC, iv=b"\x00" * 8).decrypt(data)


def mac(key: bytes, data: bytes) -> bytes:
    """ISO 9797-1 MAC algorithm 3 (retail MAC), padding method 2."""
    d = _pad(data)
    ka, kb = key[:8], key[8:16]
    h = DES.new(ka, DES.MODE_CBC, iv=b"\x00" * 8).encrypt(d)[-8:]
    h = DES.new(kb, DES.MODE_ECB).decrypt(h)
    return DES.new(ka, DES.MODE_ECB).encrypt(h)


def _xor(a, b):
    return bytes(x ^ y for x, y in zip(a, b))


def _ber_len(n: int) -> bytes:
    if n < 0x80:
        return bytes([n])
    if n < 0x100:
        return b"\x81" + bytes([n])
    return b"\x82" + n.to_bytes(2, "big")


class Session:
    """Kênh bảo mật (Secure Messaging) sau khi BAC thành công."""

    def __init__(self, card, ks_enc, ks_mac, ssc):
        self.card, self.ks_enc, self.ks_mac, self.ssc = card, ks_enc, ks_mac, ssc

    def _inc(self):
        self.ssc = (int.from_bytes(self.ssc, "big") + 1).to_bytes(8, "big")

    def send(self, cla, ins, p1, p2, data=b"", le=None):
        header = bytes([cla | 0x0C, ins, p1, p2])
        do87 = do97 = b""
        if data:
            enc = _enc(self.ks_enc, _pad(data))
            do87 = b"\x87" + _ber_len(len(enc) + 1) + b"\x01" + enc
        if le is not None:
            do97 = b"\x97\x01" + bytes([le])
        self._inc()
        m = mac(self.ks_mac, self.ssc + _pad(header) + do87 + do97)
        do8e = b"\x8E\x08" + m
        body = do87 + do97 + do8e
        apdu = header + bytes([len(body)]) + body + b"\x00"
        resp = self.card.transmit(apdu)
        if len(resp) < 2:
            raise BacError("Không có phản hồi")
        sw = resp[-2:]
        payload = resp[:-2]
        if not payload:
            return b"", sw
        self._inc()
        i, r87, r99, r8e = 0, b"", b"", b""
        while i < len(payload):
            tag = payload[i]
            ln = payload[i + 1]
            hl = 2
            if ln == 0x81:
                ln, hl = payload[i + 2], 3
            elif ln == 0x82:
                ln, hl = int.from_bytes(payload[i + 2:i + 4], "big"), 4
            val = payload[i + hl:i + hl + ln]
            raw = payload[i:i + hl + ln]
            if tag == 0x87:
                r87 = raw
                enc_data = val[1:]
            elif tag == 0x99:
                r99 = raw
                sw = val
            elif tag == 0x8E:
                r8e = val
            i += hl + ln
        if not r8e or mac(self.ks_mac, self.ssc + r87 + r99) != r8e:
            raise BacError("Sai MAC của thẻ (Secure Messaging)")
        return (_unpad(_dec(self.ks_enc, enc_data)) if r87 else b""), sw

    def select_file(self, fid: int):
        _, sw = self.send(0x00, 0xA4, 0x02, 0x0C, fid.to_bytes(2, "big"))
        return sw == b"\x90\x00"

    def read_file(self, fid: int, progress=None, chunk=0xC0):
        if not self.select_file(fid):
            return None
        head, sw = self.send(0x00, 0xB0, 0x00, 0x00, le=4)
        if sw != b"\x90\x00" or len(head) < 2:
            return None
        if head[1] < 0x80:
            total = 2 + head[1]
        elif head[1] == 0x81:
            total = 3 + head[2]
        else:
            total = 4 + int.from_bytes(head[2:4], "big")
        data = bytearray(head)
        while len(data) < total:
            off = len(data)
            if off > 0x7FFF:
                raise BacError("File lớn hơn 32KB, chưa hỗ trợ")
            n = min(chunk, total - off)
            part, sw = self.send(0x00, 0xB0, off >> 8, off & 0xFF, le=n)
            if sw != b"\x90\x00" or not part:
                raise BacError(f"Lỗi đọc file tại offset {off}: SW={sw.hex().upper()}")
            data += part
            if progress:
                progress(len(data), total)
        return bytes(data[:total])


def authenticate(card, doc: str, dob: str, doe: str) -> Session:
    r = card.transmit(bytes([0x00, 0xA4, 0x04, 0x0C, len(AID_EMRTD)]) + AID_EMRTD)
    if r[-2:] != b"\x90\x00":
        raise BacError(f"Thẻ không có ứng dụng eMRTD (SW={r[-2:].hex().upper()})")
    k_seed = hashlib.sha1(mrz_info(doc, dob, doe)).digest()[:16]
    k_enc, k_mac = derive(k_seed, 1), derive(k_seed, 2)

    rnd_ic = card.transmit(bytes.fromhex("0084000008"))
    if rnd_ic[-2:] != b"\x90\x00" or len(rnd_ic) != 10:
        raise BacError("GET CHALLENGE thất bại")
    rnd_ic = rnd_ic[:8]
    rnd_ifd, k_ifd = os.urandom(8), os.urandom(16)
    return _mutual_auth(card, k_enc, k_mac, rnd_ic, rnd_ifd, k_ifd)


def _mutual_auth(card, k_enc, k_mac, rnd_ic, rnd_ifd, k_ifd):
    e_ifd = _enc(k_enc, rnd_ifd + rnd_ic + k_ifd)
    m_ifd = mac(k_mac, e_ifd)
    resp = card.transmit(bytes([0x00, 0x82, 0x00, 0x00, 0x28]) + e_ifd + m_ifd + b"\x28")
    if resp[-2:] != b"\x90\x00" or len(resp) != 42:
        raise BacError(f"Xác thực BAC bị từ chối (SW={resp[-2:].hex().upper()}). "
                       "Kiểm tra lại số giấy tờ, ngày sinh, ngày hết hạn.")
    e_ic, m_ic = resp[:32], resp[32:40]
    if mac(k_mac, e_ic) != m_ic:
        raise BacError("Sai MAC trong phản hồi BAC")
    r = _dec(k_enc, e_ic)
    if r[:8] != rnd_ic or r[8:16] != rnd_ifd:
        raise BacError("Số ngẫu nhiên không khớp, phiên BAC không hợp lệ")
    seed = _xor(k_ifd, r[16:32])
    return Session(card, derive(seed, 1), derive(seed, 2), rnd_ic[-4:] + rnd_ifd[-4:])


# ------------------------------------------------------------ phân tích dữ liệu

def parse_tlv(data: bytes):
    out, i = [], 0
    while i < len(data):
        t0 = i
        first = data[i]
        i += 1
        if first & 0x1F == 0x1F:
            while i < len(data):
                i += 1
                if not data[i - 1] & 0x80:
                    break
        tag = data[t0:i].hex().upper()
        if i >= len(data):
            break
        ln = data[i]
        i += 1
        if ln & 0x80:
            n = ln & 0x7F
            ln = int.from_bytes(data[i:i + n], "big")
            i += n
        out.append((tag, data[i:i + ln], first & 0x20 != 0))
        i += ln
    return out


def parse_dg1(dg1: bytes):
    """Trả về (danh sách dòng MRZ, dict các trường)."""
    mrz = b""
    for tag, val, cons in parse_tlv(dg1):
        if tag == "61":
            for t2, v2, _ in parse_tlv(val):
                if t2 == "5F1F":
                    mrz = v2
    s = mrz.decode("ascii", "replace")
    lines = [s[i:i + 30] for i in range(0, len(s), 30)] if len(s) == 90 else \
            [s[i:i + 44] for i in range(0, len(s), 44)]
    f = {}
    if len(s) == 90:
        l1, l2, l3 = lines
        f["Loại giấy tờ"] = l1[0:2].replace("<", "")
        f["Quốc gia cấp"] = l1[2:5]
        f["Số giấy tờ"] = l1[5:14].replace("<", "")
        f["Trường tùy chọn 1"] = l1[15:30].replace("<", "")
        f["Ngày sinh (YYMMDD)"] = l2[0:6]
        f["Giới tính"] = l2[7]
        f["Hết hạn (YYMMDD)"] = l2[8:14]
        f["Quốc tịch"] = l2[15:18]
        f["Trường tùy chọn 2"] = l2[18:29].replace("<", "")
        f["Họ tên (MRZ, không dấu)"] = " ".join(p for p in l3.replace("<<", "|").replace("<", " ").split("|") if p).strip()
    return lines, f


def decode_text(v: bytes) -> str:
    for enc in ("utf-8", "utf-16-be"):
        try:
            t = v.decode(enc)
            if t.isprintable() or "\n" in t:
                return t
        except UnicodeDecodeError:
            pass
    return v.hex().upper()


def dump_tlv(data: bytes, indent=0, out=None):
    out = [] if out is None else out
    for tag, val, cons in parse_tlv(data):
        pad = "  " * indent
        if cons:
            out.append(f"{pad}{tag}:")
            dump_tlv(val, indent + 1, out)
        else:
            out.append(f"{pad}{tag} = {decode_text(val)}")
    return out


def find_image(dg2: bytes):
    for sig in (b"\xFF\xD8\xFF", b"\x00\x00\x00\x0CjP  ", b"\xFF\x4F\xFF\x51"):
        i = dg2.find(sig)
        if i >= 0:
            return dg2[i:]
    return None
