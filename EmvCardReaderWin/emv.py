"""Luồng đọc thẻ EMV (contact/contactless qua PC/SC) và parser BER-TLV."""
from dataclasses import dataclass, field

TAG_NAMES = {
    "4F": "AID", "50": "Application Label", "56": "Track1 Data", "57": "Track2 Equivalent Data",
    "5A": "PAN (số thẻ)", "5F20": "Cardholder Name (Họ tên chủ thẻ)",
    "5F24": "Expiration Date (YYMMDD)", "5F25": "Effective Date (YYMMDD)", "5F28": "Issuer Country Code",
    "5F2D": "Language Preference", "5F30": "Service Code", "5F34": "PAN Sequence Number",
    "9F1F": "Track1 Discretionary Data", "9F0B": "Cardholder Name Extended",
    "82": "AIP", "94": "AFL", "9F38": "PDOL", "88": "SFI", "84": "DF Name", "87": "Application Priority",
    "A5": "FCI Proprietary Template", "6F": "FCI Template", "BF0C": "FCI Issuer Discretionary Data",
    "61": "Application Template", "70": "Record Template", "77": "Response Format 2",
    "80": "Response Format 1", "9F12": "Application Preferred Name", "9F42": "Currency Code",
    "9F44": "Currency Exponent", "8C": "CDOL1", "8D": "CDOL2", "8E": "CVM List",
    "90": "Issuer PK Certificate", "9F46": "ICC PK Certificate", "9F32": "Issuer PK Exponent",
    "9F47": "ICC PK Exponent", "8F": "CA PK Index", "9F49": "DDOL", "9F07": "Application Usage Control",
}

SENSITIVE_TAGS = ("5A", "57", "56", "9F1F")


@dataclass
class Tlv:
    tag: str
    value: bytes
    depth: int = 0
    children: list = field(default_factory=list)


def parse_tlv(data: bytes, depth=0):
    nodes, i = [], 0
    while i < len(data):
        if data[i] in (0x00, 0xFF):
            i += 1
            continue
        start = i
        first = data[i]
        i += 1
        if first & 0x1F == 0x1F:
            while i < len(data):
                i += 1
                if not data[i - 1] & 0x80:
                    break
        tag = data[start:i].hex().upper()
        if i >= len(data):
            break
        ln = data[i]
        i += 1
        if ln & 0x80:
            n = ln & 0x7F
            if i + n > len(data):
                break
            ln = int.from_bytes(data[i:i + n], "big")
            i += n
        val = data[i:i + ln]
        i += ln
        kids = parse_tlv(val, depth + 1) if first & 0x20 else []
        nodes.append(Tlv(tag, val, depth, kids))
    return nodes


def flatten(nodes):
    out = []
    for n in nodes:
        out.append(n)
        out.extend(flatten(n.children))
    return out


def find_all(nodes, tag):
    return [n for n in flatten(nodes) if n.tag == tag]


def find_first(nodes, tag):
    r = find_all(nodes, tag)
    return r[0] if r else None


def render(nodes, out):
    for n in nodes:
        name = TAG_NAMES.get(n.tag)
        line = "  " * n.depth + n.tag + (f" ({name})" if name else "") + f" len={len(n.value)}"
        if not n.children:
            line += " = " + n.value.hex().upper()
            if n.value and all(0x20 <= b <= 0x7E for b in n.value):
                line += f'  "{n.value.decode("ascii")}"'
        out.append(line)
        render(n.children, out)


def mask_pan_digits(pan: str) -> str:
    return pan if len(pan) < 11 else pan[:6] + "*" * (len(pan) - 10) + pan[-4:]


def mask_in_text(text: str, pan: str) -> str:
    """Che PAN ở mọi nơi nó xuất hiện (TLV, Track2, log APDU)."""
    if not pan:
        return text
    masked = mask_pan_digits(pan)
    text = text.replace(pan, masked)
    return text.replace(pan.encode().hex().upper(), masked.encode().hex().upper())


# ---------------------------------------------------------------- luồng đọc

PPSE = b"2PAY.SYS.DDF01"
PSE = b"1PAY.SYS.DDF01"
FALLBACK_AIDS = [
    "A0000007270010", "A0000007271010", "A0000007270101",
    "A0000000031010", "A0000000041010", "A0000000651010", "A000000333010101",
]


@dataclass
class ReadResult:
    steps: list = field(default_factory=list)      # (tiêu đề, lệnh, phản hồi)
    aids: list = field(default_factory=list)
    nodes_by_aid: dict = field(default_factory=dict)
    atr: bytes = b""
    protocol: int = 0
    notes: list = field(default_factory=list)


def ok(resp):
    return len(resp) >= 2 and resp[-2:] == b"\x90\x00"


def body(resp):
    return resp[:-2] if len(resp) >= 2 else b""


def sel(name: bytes):
    return bytes([0x00, 0xA4, 0x04, 0x00, len(name)]) + name + b"\x00"


def zero_dol(dol: bytes) -> bytes:
    out, i = 0, 0
    while i < len(dol):
        first = dol[i]
        i += 1
        if first & 0x1F == 0x1F:
            while i < len(dol):
                i += 1
                if not dol[i - 1] & 0x80:
                    break
        if i >= len(dol):
            break
        out += dol[i]
        i += 1
    return bytes(out)


def read_card(card) -> ReadResult:
    r = ReadResult(atr=card.atr, protocol=card.proto)

    def tx(title, apdu):
        try:
            resp = card.transmit(apdu)
        except Exception as e:  # noqa: BLE001
            resp = f"LỖI: {e}".encode()
        r.steps.append((title, bytes(apdu), resp))
        return resp

    aids = []

    def add_aid(h):
        if h and h not in aids:
            aids.append(h)

    # 1. PPSE (contactless) rồi PSE (contact) để lấy danh sách ứng dụng
    resp = tx("SELECT PPSE 2PAY.SYS.DDF01", sel(PPSE))
    if ok(resp):
        for n in find_all(parse_tlv(body(resp)), "4F"):
            add_aid(n.value.hex().upper())

    resp = tx("SELECT PSE 1PAY.SYS.DDF01", sel(PSE))
    if ok(resp):
        fci = parse_tlv(body(resp))
        sfi_node = find_first(fci, "88")
        if sfi_node:
            sfi = sfi_node.value[0]
            for rec in range(1, 17):
                rr = tx(f"READ RECORD PSE sfi={sfi} rec={rec}", bytes([0x00, 0xB2, rec, (sfi << 3) | 4, 0x00]))
                if not ok(rr):
                    break
                for n in find_all(parse_tlv(body(rr)), "4F"):
                    add_aid(n.value.hex().upper())

    from_directory = bool(aids)
    if not aids:
        r.notes.append("Không lấy được danh sách AID qua PPSE/PSE, thử các AID phổ biến.")
        for a in FALLBACK_AIDS:
            add_aid(a)
    r.aids = list(aids)

    for aid in aids:
        resp = tx(f"SELECT AID {aid}", sel(bytes.fromhex(aid)))
        if not ok(resp):
            continue
        nodes = r.nodes_by_aid.setdefault(aid, [])
        fci = parse_tlv(body(resp))
        nodes.extend(fci)

        pdol = find_first(fci, "9F38")
        dol = zero_dol(pdol.value) if pdol else b""
        cmd_data = b"\x83" + bytes([len(dol)]) + dol
        gpo = tx(f"GET PROCESSING OPTIONS {aid}",
                 bytes([0x80, 0xA8, 0x00, 0x00, len(cmd_data)]) + cmd_data + b"\x00")

        afl = b""
        if ok(gpo):
            p = body(gpo)
            nodes.extend(parse_tlv(p))
            if p[:1] == b"\x80" and len(p) >= 4:
                afl = p[4:2 + p[1]]
            elif p[:1] == b"\x77":
                n94 = find_first(parse_tlv(p), "94")
                afl = n94.value if n94 else b""
        else:
            r.notes.append(f"GPO thất bại cho {aid}, quét thẳng các record.")

        ranges = []
        for k in range(0, len(afl) - 3, 4):
            ranges.append(((afl[k] >> 3), afl[k + 1], afl[k + 2]))
        if not ranges:
            ranges = [(s, 1, 10) for s in range(1, 11)]

        for sfi, first, last in ranges:
            for rec in range(first, last + 1):
                rr = tx(f"READ RECORD sfi={sfi} rec={rec} ({aid})",
                        bytes([0x00, 0xB2, rec, (sfi << 3) | 4, 0x00]))
                if ok(rr):
                    nodes.extend(parse_tlv(body(rr)))
                elif len(rr) == 2 and rr[0] == 0x6A:
                    break
    return r


# ---------------------------------------------------------------- tóm tắt

@dataclass
class ChipInfo:
    pan: str = ""
    expiry_yymm: str = ""
    name: str = ""
    label: str = ""
    service_code: str = ""
    country: str = ""
    aid: str = ""


def _name_from_track1(v: bytes) -> str:
    s = v.decode("ascii", "ignore")
    parts = s.split("^")
    return parts[1].strip() if len(parts) >= 3 else ""


def extract_chip_info(result: ReadResult) -> ChipInfo:
    info = ChipInfo()
    for aid, nodes in result.nodes_by_aid.items():
        pan = find_first(nodes, "5A")
        t2 = find_first(nodes, "57")
        if not (pan or t2):
            continue
        info.aid = aid
        if pan:
            info.pan = pan.value.hex().upper().rstrip("F")
        if t2:
            t = t2.value.hex().upper()
            if "D" in t:
                pan_part, rest = t.split("D", 1)
                info.pan = info.pan or pan_part
                info.expiry_yymm = rest[:4]
                info.service_code = rest[4:7]
        exp = find_first(nodes, "5F24")
        if exp:
            info.expiry_yymm = exp.value.hex().upper()[:4]
        for tag in ("5F20", "9F0B"):
            nm = find_first(nodes, tag)
            if nm and nm.value.strip(b"\x00 "):
                info.name = nm.value.decode("ascii", "ignore").strip()
                break
        if not info.name:
            t1 = find_first(nodes, "56")
            if t1:
                info.name = _name_from_track1(t1.value)
        lb = find_first(nodes, "50")
        if lb:
            info.label = lb.value.decode("ascii", "ignore")
        cc = find_first(nodes, "5F28")
        if cc:
            info.country = cc.value.hex()
        break
    return info
