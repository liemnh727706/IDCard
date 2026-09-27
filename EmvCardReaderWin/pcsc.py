"""Lớp mỏng gọi PC/SC (winscard.dll) bằng ctypes, không cần thư viện ngoài."""
import ctypes
from ctypes import wintypes

_w = ctypes.WinDLL("winscard.dll")

SCARD_SCOPE_USER = 0
SCARD_SHARE_SHARED = 2
SCARD_PROTOCOL_T0 = 1
SCARD_PROTOCOL_T1 = 2
SCARD_LEAVE_CARD = 0

ERRORS = {
    0x8010000C: "Không có thẻ trong đầu đọc (SCARD_E_NO_SMARTCARD)",
    0x80100066: "Thẻ không phản hồi, kiểm tra hướng cắm/chiều chip (SCARD_W_UNRESPONSIVE_CARD)",
    0x80100069: "Thẻ vừa bị rút ra (SCARD_W_REMOVED_CARD)",
    0x8010002E: "Không tìm thấy đầu đọc thẻ (SCARD_E_NO_READERS_AVAILABLE)",
    0x8010001D: "Dịch vụ Smart Card chưa chạy (SCARD_E_NO_SERVICE)",
    0x8010000B: "Đầu đọc đang được ứng dụng khác dùng độc quyền (SCARD_E_SHARING_VIOLATION)",
    0x8010001F: "Lỗi chung từ trình điều khiển đầu đọc (SCARD_F_COMM_ERROR)",
}


class PcscError(Exception):
    pass


def _check(rc, what):
    rc &= 0xFFFFFFFF
    if rc != 0:
        raise PcscError(f"{what}: {ERRORS.get(rc, 'lỗi')} [0x{rc:08X}]")


class _IoRequest(ctypes.Structure):
    _fields_ = [("dwProtocol", wintypes.DWORD), ("cbPciLength", wintypes.DWORD)]


_w.SCardEstablishContext.argtypes = [wintypes.DWORD, ctypes.c_void_p, ctypes.c_void_p, ctypes.POINTER(ctypes.c_size_t)]
_w.SCardReleaseContext.argtypes = [ctypes.c_size_t]
_w.SCardListReadersW.argtypes = [ctypes.c_size_t, wintypes.LPCWSTR, ctypes.c_void_p, ctypes.POINTER(wintypes.DWORD)]
_w.SCardConnectW.argtypes = [ctypes.c_size_t, wintypes.LPCWSTR, wintypes.DWORD, wintypes.DWORD,
                             ctypes.POINTER(ctypes.c_size_t), ctypes.POINTER(wintypes.DWORD)]
_w.SCardDisconnect.argtypes = [ctypes.c_size_t, wintypes.DWORD]
_w.SCardTransmit.argtypes = [ctypes.c_size_t, ctypes.POINTER(_IoRequest), ctypes.c_char_p, wintypes.DWORD,
                             ctypes.c_void_p, ctypes.c_void_p, ctypes.POINTER(wintypes.DWORD)]
_w.SCardStatusW.argtypes = [ctypes.c_size_t, ctypes.c_void_p, ctypes.POINTER(wintypes.DWORD),
                            ctypes.POINTER(wintypes.DWORD), ctypes.POINTER(wintypes.DWORD),
                            ctypes.c_void_p, ctypes.POINTER(wintypes.DWORD)]
for _f in ("SCardEstablishContext", "SCardReleaseContext", "SCardListReadersW", "SCardConnectW",
           "SCardDisconnect", "SCardTransmit", "SCardStatusW"):
    getattr(_w, _f).restype = ctypes.c_long


class _ReaderState(ctypes.Structure):
    _pack_ = 1
    _fields_ = [("szReader", wintypes.LPCWSTR), ("pvUserData", ctypes.c_void_p),
                ("dwCurrentState", wintypes.DWORD), ("dwEventState", wintypes.DWORD),
                ("cbAtr", wintypes.DWORD), ("rgbAtr", ctypes.c_ubyte * 36)]


_w.SCardGetStatusChangeW.argtypes = [ctypes.c_size_t, wintypes.DWORD, ctypes.POINTER(_ReaderState), wintypes.DWORD]
_w.SCardGetStatusChangeW.restype = ctypes.c_long


def reader_state(reader):
    """Trạng thái đầu đọc: 'empty' (không thẻ), 'mute' (có thẻ nhưng chip không trả lời),
    'inuse' (ứng dụng khác đang giữ), 'present' (có thẻ, sẵn sàng)."""
    ctx = ctypes.c_size_t()
    _check(_w.SCardEstablishContext(SCARD_SCOPE_USER, None, None, ctypes.byref(ctx)), "EstablishContext")
    try:
        st = _ReaderState(reader, None, 0, 0, 0)
        _check(_w.SCardGetStatusChangeW(ctx, 0, ctypes.byref(st), 1), "GetStatusChange")
        ev = st.dwEventState
        if ev & 0x200:
            return "mute"
        if ev & 0x10:
            return "empty"
        if ev & 0x100:
            return "inuse"
        if ev & 0x20:
            return "present"
        return "unknown"
    finally:
        _w.SCardReleaseContext(ctx)


def list_readers():
    ctx = ctypes.c_size_t()
    _check(_w.SCardEstablishContext(SCARD_SCOPE_USER, None, None, ctypes.byref(ctx)), "EstablishContext")
    try:
        size = wintypes.DWORD(0)
        rc = _w.SCardListReadersW(ctx, None, None, ctypes.byref(size)) & 0xFFFFFFFF
        if rc == 0x8010002E or (rc == 0 and size.value <= 2):
            return []
        _check(rc, "ListReaders")
        buf = ctypes.create_unicode_buffer(size.value)
        _check(_w.SCardListReadersW(ctx, None, buf, ctypes.byref(size)), "ListReaders")
        return [s for s in ctypes.wstring_at(buf, size.value).split("\0") if s]
    finally:
        _w.SCardReleaseContext(ctx)


class Card:
    """Kết nối tới thẻ đang cắm trong một đầu đọc."""

    def __init__(self, reader):
        self.reader = reader
        self.ctx = ctypes.c_size_t()
        self.h = ctypes.c_size_t()
        self.proto = 0
        self.atr = b""
        _check(_w.SCardEstablishContext(SCARD_SCOPE_USER, None, None, ctypes.byref(self.ctx)), "EstablishContext")
        proto = wintypes.DWORD()
        rc = _w.SCardConnectW(self.ctx, reader, SCARD_SHARE_SHARED,
                              SCARD_PROTOCOL_T0 | SCARD_PROTOCOL_T1, ctypes.byref(self.h), ctypes.byref(proto))
        if rc & 0xFFFFFFFF:
            _w.SCardReleaseContext(self.ctx)
        _check(rc, "Connect")
        self.proto = proto.value
        self._read_atr()

    def _read_atr(self):
        names = ctypes.create_unicode_buffer(256)
        nlen = wintypes.DWORD(256)
        state = wintypes.DWORD()
        proto = wintypes.DWORD()
        atr = (ctypes.c_ubyte * 64)()
        alen = wintypes.DWORD(64)
        rc = _w.SCardStatusW(self.h, names, ctypes.byref(nlen), ctypes.byref(state),
                             ctypes.byref(proto), atr, ctypes.byref(alen))
        if rc & 0xFFFFFFFF == 0:
            self.atr = bytes(atr[: alen.value])

    def _raw(self, apdu):
        pci = _IoRequest(self.proto, 8)
        recv = (ctypes.c_ubyte * 4096)()
        rlen = wintypes.DWORD(4096)
        _check(_w.SCardTransmit(self.h, ctypes.byref(pci), bytes(apdu), len(apdu), None, recv,
                                ctypes.byref(rlen)), "Transmit")
        return bytes(recv[: rlen.value])

    def transmit(self, apdu):
        """Gửi APDU, tự xử lý GET RESPONSE (61xx) và sửa Le (6Cxx) của giao thức T=0."""
        resp = self._raw(apdu)
        data = b""
        for _ in range(8):
            if len(resp) < 2:
                break
            sw1, sw2 = resp[-2], resp[-1]
            if sw1 == 0x61:
                data += resp[:-2]
                resp = self._raw(bytes([0x00, 0xC0, 0x00, 0x00, sw2]))
                continue
            if sw1 == 0x6C:
                resp = self._raw(bytes(apdu[:4]) + bytes([sw2]))
                continue
            break
        return data + resp

    def close(self):
        try:
            _w.SCardDisconnect(self.h, SCARD_LEAVE_CARD)
        finally:
            _w.SCardReleaseContext(self.ctx)
