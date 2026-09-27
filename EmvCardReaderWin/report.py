"""Tạo báo cáo từ thông tin đã đọc: PDF (có font tiếng Việt), TXT, JSON; và in ra máy in.

Cấu trúc báo cáo (dict):
  title, generated, sensitive (str cảnh báo),
  sections: [ {heading, rows: [(nhãn, giá trị)], text: str, photo: bytes|None} ]
"""
import ctypes
import datetime
import io
import json
import os
import subprocess
import tempfile
import textwrap
import threading

FONT_DIR = os.path.join(os.environ.get("WINDIR", r"C:\Windows"), "Fonts")
SENSITIVE_NOTE = ("TÀI LIỆU CHỨA DỮ LIỆU CÁ NHÂN NHẠY CẢM. Chỉ dùng cho chủ thẻ hoặc khi có sự đồng ý của "
                  "chủ thẻ; bảo quản, không chia sẻ và hủy khi không còn cần thiết.")


def new_report(title, sections, sensitive=SENSITIVE_NOTE):
    return {"title": title, "generated": datetime.datetime.now().strftime("%d/%m/%Y %H:%M:%S"),
            "sensitive": sensitive, "sections": sections}


# ------------------------------------------------------------------ TXT / JSON

def to_text(rep) -> str:
    out = [rep["title"], "Thời điểm tạo: " + rep["generated"], "", "!! " + rep["sensitive"], ""]
    for sec in rep["sections"]:
        out += ["=" * 70, sec["heading"].upper(), "=" * 70]
        for k, v in sec.get("rows", []):
            out.append(f"{k:32s}: {v}")
        if sec.get("text"):
            out += ["", sec["text"]]
        if sec.get("photo"):
            out.append("(có ảnh chân dung, chỉ hiển thị trong file PDF)")
        out.append("")
    return "\n".join(out)


def to_json(rep) -> str:
    data = {"title": rep["title"], "generated": rep["generated"], "sensitive": rep["sensitive"], "sections": []}
    for sec in rep["sections"]:
        d = {"heading": sec["heading"]}
        if sec.get("rows"):
            d["fields"] = {k: v for k, v in sec["rows"]}
        if sec.get("text"):
            d["text"] = sec["text"]
        data["sections"].append(d)
    return json.dumps(data, ensure_ascii=False, indent=2)


# ------------------------------------------------------------------ PDF

def _register_fonts():
    from reportlab.pdfbase import pdfmetrics
    from reportlab.pdfbase.ttfonts import TTFont
    names = {"VN": "arial.ttf", "VN-B": "arialbd.ttf", "VN-Mono": "cour.ttf"}
    ok = True
    for name, fn in names.items():
        path = os.path.join(FONT_DIR, fn)
        if os.path.exists(path):
            if name not in pdfmetrics.getRegisteredFontNames():
                pdfmetrics.registerFont(TTFont(name, path))
        else:
            ok = False
    if ok:
        return "VN", "VN-B", "VN-Mono"
    return "Helvetica", "Helvetica-Bold", "Courier"   # không có tiếng Việt


def _photo_flowable(data, max_w, max_h):
    from PIL import Image as PILImage
    from reportlab.platypus import Image
    try:
        im = PILImage.open(io.BytesIO(data)).convert("RGB")
    except Exception:  # noqa: BLE001
        return None
    buf = io.BytesIO()
    im.save(buf, "PNG")
    buf.seek(0)
    w, h = im.size
    k = min(max_w / w, max_h / h)
    return Image(buf, width=w * k, height=h * k)


def to_pdf(rep, path):
    from reportlab.lib import colors
    from reportlab.lib.pagesizes import A4
    from reportlab.lib.styles import ParagraphStyle
    from reportlab.lib.units import mm
    from reportlab.platypus import Paragraph, Preformatted, SimpleDocTemplate, Spacer, Table, TableStyle

    f, fb, fm = _register_fonts()
    st_title = ParagraphStyle("t", fontName=fb, fontSize=16, leading=20, spaceAfter=2)
    st_meta = ParagraphStyle("m", fontName=f, fontSize=9, textColor=colors.grey, spaceAfter=6)
    st_warn = ParagraphStyle("w", fontName=fb, fontSize=8.5, leading=11, textColor=colors.HexColor("#8a1c1c"))
    st_h = ParagraphStyle("h", fontName=fb, fontSize=12, leading=15, spaceBefore=10, spaceAfter=4,
                          textColor=colors.HexColor("#1f3a5f"))
    st_k = ParagraphStyle("k", fontName=fb, fontSize=9.5, leading=12)
    st_v = ParagraphStyle("v", fontName=f, fontSize=9.5, leading=12)
    st_mono = ParagraphStyle("mono", fontName=fm, fontSize=7.5, leading=9)

    def esc(s):
        return str(s).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    page_w = A4[0] - 36 * mm
    story = [Paragraph(esc(rep["title"]), st_title),
             Paragraph("Thời điểm tạo: " + esc(rep["generated"]), st_meta)]
    warn = Table([[Paragraph(esc(rep["sensitive"]), st_warn)]], colWidths=[page_w])
    warn.setStyle(TableStyle([("BACKGROUND", (0, 0), (-1, -1), colors.HexColor("#fdecec")),
                              ("BOX", (0, 0), (-1, -1), 0.6, colors.HexColor("#d9534f")),
                              ("LEFTPADDING", (0, 0), (-1, -1), 6), ("RIGHTPADDING", (0, 0), (-1, -1), 6),
                              ("TOPPADDING", (0, 0), (-1, -1), 4), ("BOTTOMPADDING", (0, 0), (-1, -1), 4)]))
    story += [warn, Spacer(1, 4)]

    for sec in rep["sections"]:
        story.append(Paragraph(esc(sec["heading"]), st_h))
        rows = sec.get("rows") or []
        table = None
        photo = _photo_flowable(sec["photo"], 38 * mm, 50 * mm) if sec.get("photo") else None
        tw = page_w - (44 * mm if photo else 0)
        if rows:
            table = Table([[Paragraph(esc(k), st_k), Paragraph(esc(v), st_v)] for k, v in rows],
                          colWidths=[tw * 0.36, tw * 0.64])
            table.setStyle(TableStyle([("VALIGN", (0, 0), (-1, -1), "TOP"),
                                       ("LINEBELOW", (0, 0), (-1, -1), 0.25, colors.HexColor("#cccccc")),
                                       ("BACKGROUND", (0, 0), (0, -1), colors.HexColor("#f3f6fa")),
                                       ("TOPPADDING", (0, 0), (-1, -1), 3), ("BOTTOMPADDING", (0, 0), (-1, -1), 3)]))
        if table is not None and photo is not None:
            both = Table([[table, photo]], colWidths=[tw, 44 * mm])
            both.setStyle(TableStyle([("VALIGN", (0, 0), (-1, -1), "TOP"), ("LEFTPADDING", (0, 0), (-1, -1), 0)]))
            story.append(both)
        elif table is not None:
            story.append(table)
        elif photo is not None:
            story.append(photo)
        if sec.get("text"):
            wrapped = "\n".join(textwrap.fill(line, 104, subsequent_indent="    ", break_long_words=True,
                                              replace_whitespace=False) or "" for line in sec["text"].split("\n"))
            story += [Spacer(1, 3), Preformatted(wrapped, st_mono)]

    def footer(canvas, doc):
        canvas.saveState()
        canvas.setFont(f, 8)
        canvas.setFillColor(colors.grey)
        canvas.drawString(18 * mm, 10 * mm, "Chip Card Reader - báo cáo tạo tự động, không thay thế giấy tờ gốc")
        canvas.drawRightString(A4[0] - 18 * mm, 10 * mm, f"Trang {doc.page}")
        canvas.restoreState()

    SimpleDocTemplate(path, pagesize=A4, leftMargin=18 * mm, rightMargin=18 * mm, topMargin=16 * mm,
                      bottomMargin=16 * mm, title=rep["title"], author="Chip Card Reader").build(
        story, onFirstPage=footer, onLaterPages=footer)


def save(rep, path):
    ext = os.path.splitext(path)[1].lower()
    if ext == ".pdf":
        to_pdf(rep, path)
    elif ext == ".json":
        with open(path, "w", encoding="utf-8") as fh:
            fh.write(to_json(rep))
    else:
        with open(path, "w", encoding="utf-8-sig") as fh:
            fh.write(to_text(rep))


# ------------------------------------------------------------------ In ấn

def list_printers():
    """Danh sách tên máy in và tên máy in mặc định (nếu lấy được)."""
    names = []
    try:
        r = subprocess.run(["powershell", "-NoProfile", "-Command",
                            "[Console]::OutputEncoding=[Text.Encoding]::UTF8; Get-Printer | ForEach-Object { $_.Name }"],
                           capture_output=True, text=True, encoding="utf-8", timeout=20,
                           creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
        names = [x.strip() for x in r.stdout.splitlines() if x.strip()]
    except Exception:  # noqa: BLE001
        pass
    default = ""
    try:
        from ctypes import wintypes
        sp = ctypes.WinDLL("winspool.drv")
        sp.GetDefaultPrinterW.argtypes = [wintypes.LPWSTR, ctypes.POINTER(wintypes.DWORD)]
        sp.GetDefaultPrinterW.restype = wintypes.BOOL
        size = wintypes.DWORD(0)
        sp.GetDefaultPrinterW(None, ctypes.byref(size))
        buf = ctypes.create_unicode_buffer(max(size.value, 1))
        if sp.GetDefaultPrinterW(buf, ctypes.byref(size)):
            default = buf.value
    except Exception:  # noqa: BLE001
        pass
    return names, default


def print_report(rep, printer=None):
    """Tạo PDF tạm rồi gửi ra máy in qua trình đọc PDF đang cài. Trả về thông báo kết quả."""
    fd, path = tempfile.mkstemp(suffix=".pdf", prefix="baocao_")
    os.close(fd)
    to_pdf(rep, path)
    shell = ctypes.windll.shell32
    verb, params = ("printto", f'"{printer}"') if printer else ("print", None)
    rc = shell.ShellExecuteW(None, verb, path, params, None, 0)
    msg = f"Đã gửi lệnh in tới '{printer or 'máy in mặc định'}'."
    if rc <= 32:
        os.startfile(path)  # không có lệnh in trong trình đọc PDF: mở file để bạn in thủ công (Ctrl+P)
        msg = "Trình đọc PDF trên máy không hỗ trợ in trực tiếp; đã mở file PDF, hãy nhấn Ctrl+P để in."
        keep = 900
    else:
        keep = 240

    def cleanup():
        try:
            os.remove(path)
        except OSError:
            pass
    t = threading.Timer(keep, cleanup)
    t.daemon = True
    t.start()
    return msg
