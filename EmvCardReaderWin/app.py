"""EMV Chip Reader cho Windows: đọc thẻ qua đầu đọc USB (PC/SC), OCR mặt thẻ và so khớp."""
import queue
import threading
import tkinter as tk
from tkinter import filedialog, messagebox, scrolledtext, ttk

import banks
from cccd_tab import CccdTab
import emv
import match
import ocr_win
import pcsc
import report
import report_ui


class App(tk.Tk):
    def __init__(self):
        super().__init__()
        self.title("Chip Card Reader (Windows) - EMV và CCCD")
        self.geometry("980x780")
        self.q = queue.Queue()
        self.chip = None
        self.result = None
        self.ocr = None
        self.raw_log = ""
        self.show_full = tk.BooleanVar(value=False)

        top = ttk.Frame(self, padding=8)
        top.pack(fill="x")
        ttk.Label(top, text="Đầu đọc:").pack(side="left")
        self.cb = ttk.Combobox(top, state="readonly", width=42)
        self.cb.pack(side="left", padx=4)
        ttk.Button(top, text="Làm mới", command=self.refresh_readers).pack(side="left")

        self.status = ttk.Label(self, text="Cắm thẻ vào đầu đọc rồi bấm nút đọc ở tab tương ứng.", padding=(8, 0),
                                font=("Segoe UI", 10, "bold"))
        self.status.pack(fill="x")

        nb = ttk.Notebook(self)
        nb.pack(fill="both", expand=True, padx=8, pady=6)
        emv_tab = ttk.Frame(nb, padding=4)
        nb.add(emv_tab, text="Thẻ ngân hàng (EMV)")
        nb.add(CccdTab(nb, lambda: self.cb.get(), lambda t: self.status.config(text=t)), text="Căn cước công dân")

        bar = ttk.Frame(emv_tab)
        bar.pack(fill="x")
        ttk.Button(bar, text="Đọc thẻ chip", command=self.read_chip).pack(side="left", padx=(0, 4))
        ttk.Button(bar, text="Chọn ảnh mặt thẻ (OCR)...", command=self.pick_image).pack(side="left", padx=4)
        ttk.Checkbutton(bar, text="Hiện đầy đủ PAN/Track2", variable=self.show_full,
                        command=self.render).pack(side="left", padx=12)
        report_ui.add_buttons(bar, self.build_report, "TheNganHang")
        self.summary = scrolledtext.ScrolledText(emv_tab, height=14, font=("Consolas", 10), wrap="word")
        self.summary.pack(fill="x", pady=6)
        self.log = scrolledtext.ScrolledText(emv_tab, font=("Consolas", 9), wrap="none")
        self.log.pack(fill="both", expand=True)

        self.refresh_readers()
        self.after(100, self.poll)

    # ---- đầu đọc
    def refresh_readers(self):
        try:
            rs = pcsc.list_readers()
        except pcsc.PcscError as e:
            rs = []
            self.status.config(text=str(e))
        self.cb["values"] = rs
        if rs:
            self.cb.current(0)
            self.status.config(text=f"Tìm thấy {len(rs)} đầu đọc. Cắm thẻ rồi bấm 'Đọc thẻ chip'.")
        else:
            self.status.config(text="Không thấy đầu đọc thẻ. Kiểm tra cáp USB và dịch vụ Smart Card.")

    # ---- đọc chip (chạy nền để không đơ giao diện)
    def read_chip(self):
        reader = self.cb.get()
        if not reader:
            messagebox.showwarning("Thiếu đầu đọc", "Chưa chọn đầu đọc thẻ.")
            return
        self.status.config(text="Đang đọc thẻ...")

        def work():
            try:
                card = pcsc.Card(reader)
                try:
                    res = emv.read_card(card)
                finally:
                    card.close()
                self.q.put(("chip", res))
            except Exception as e:  # noqa: BLE001
                hint = {
                    "empty": "Đầu đọc báo KHÔNG có thẻ: cắm thẻ vào hẳn trong khe.",
                    "mute": "Có thẻ nhưng chip không trả lời: cắm ngược mặt/ngược đầu, chip bẩn, hoặc thẻ này "
                            "không có mặt tiếp xúc (chỉ đọc được bằng NFC/contactless).",
                    "inuse": "Ứng dụng khác đang giữ đầu đọc, đóng nó rồi thử lại.",
                }
                try:
                    h = hint.get(pcsc.reader_state(reader), "")
                except Exception:  # noqa: BLE001
                    h = ""
                self.q.put(("error", (str(e) + chr(10) + h).strip()))

        threading.Thread(target=work, daemon=True).start()

    # ---- OCR
    def pick_image(self):
        path = filedialog.askopenfilename(
            title="Chọn ảnh mặt thẻ (có số thẻ, hạn dùng)",
            filetypes=[("Ảnh", "*.jpg *.jpeg *.png *.bmp *.tif *.tiff"), ("Tất cả", "*.*")])
        if not path:
            return
        self.status.config(text="Đang OCR...")

        def work():
            try:
                self.q.put(("ocr", ocr_win.ocr_image(path)))
            except Exception as e:  # noqa: BLE001
                self.q.put(("error", f"OCR lỗi: {e}"))

        threading.Thread(target=work, daemon=True).start()

    def poll(self):
        try:
            while True:
                kind, payload = self.q.get_nowait()
                if kind == "chip":
                    self.result = payload
                    self.chip = emv.extract_chip_info(payload)
                    self.raw_log = self.build_log(payload)
                    self.status.config(text="Đã đọc xong chip." if self.chip.pan else
                                       "Đọc được thẻ nhưng không thấy PAN, xem log bên dưới.")
                elif kind == "ocr":
                    self.ocr = match.parse_ocr(payload)
                    self.status.config(text="Đã OCR xong.")
                else:
                    self.status.config(text=payload)
                self.render()
        except queue.Empty:
            pass
        self.after(100, self.poll)

    # ---- hiển thị
    def build_log(self, res):
        out = [f"ATR: {res.atr.hex().upper()}  | giao thức: T={0 if res.protocol == 1 else 1}"]
        out += res.notes
        for aid, nodes in res.nodes_by_aid.items():
            out += ["", f"---- AID {aid} ----"]
            emv.render(nodes, out)
        out += ["", "[Log APDU]"]
        for title, cmd, rsp in res.steps:
            out += [f"> {title}", f"  CMD: {cmd.hex().upper()}", f"  RSP: {rsp.hex().upper()}"]
        return "\n".join(out)

    def build_report(self):
        chip, res = self.chip, self.result
        if not (chip and chip.pan):
            return None
        full = self.show_full.get()
        pan = chip.pan if full else emv.mask_pan_digits(chip.pan)
        exp = f"{chip.expiry_yymm[2:]}/{chip.expiry_yymm[:2]}" if chip.expiry_yymm else "(không có)"
        country = {"0704": "Việt Nam (704)"}.get(chip.country, chip.country or "(không có)")
        main = [("Loại thẻ / ứng dụng", f"{chip.label} (AID {chip.aid})"),
                ("Số thẻ (PAN)", pan + ("" if full else "  [đã che bớt]")),
                ("Hạn dùng (MM/YY)", exp),
                ("Họ tên chủ thẻ (theo chip)", chip.name or "(chip không lưu tên)"),
                ("Ngân hàng / nhà phát hành", banks.describe(chip.pan)),
                ("Service code", chip.service_code or "(không có)"),
                ("Quốc gia phát hành", country)]
        sections = [{"heading": "Thông tin chính", "rows": main}]
        if self.ocr:
            lines, verdict = match.compare(self.ocr, chip)
            sections.append({"heading": "Đối chiếu với mặt thẻ (OCR)",
                             "rows": [("Kết luận", verdict)], "text": chr(10).join("- " + x for x in lines)})
        log = self.raw_log
        if not full:
            log = emv.mask_in_text(log, chip.pan)
        tech = [("Đầu đọc", self.cb.get()), ("ATR", res.atr.hex().upper() if res else ""),
                ("Giao thức", f"T={0 if res and res.protocol == 1 else 1}"),
                ("Số ứng dụng (AID)", ", ".join(res.aids) if res else "")]
        sections.append({"heading": "Thông tin kèm theo (kỹ thuật)", "rows": tech, "text": log})
        note = report.SENSITIVE_NOTE + (" Số thẻ đã được che bớt." if not full else " Số thẻ hiển thị ĐẦY ĐỦ.")
        return report.new_report("Báo cáo đọc thẻ ngân hàng gắn chip", sections, note)

    def render(self):
        chip = self.chip
        full = self.show_full.get()
        s = []
        if chip and chip.pan:
            pan = chip.pan if full else emv.mask_pan_digits(chip.pan)
            exp = f"{chip.expiry_yymm[2:]}/{chip.expiry_yymm[:2]}" if chip.expiry_yymm else "(không có)"
            s += [f"Ứng dụng      : {chip.label} (AID {chip.aid})",
                  f"PAN           : {pan}",
                  f"Hạn dùng      : {exp}",
                  f"Service code  : {chip.service_code or '(không có)'}",
                  f"Nhà phát hành : {banks.describe(chip.pan)}",
                  f"Họ tên (chip) : {chip.name or '(chip không lưu tên)'}"]
        else:
            s.append("Chưa có dữ liệu chip.")
        if self.ocr and chip and chip.pan:
            lines, verdict = match.compare(self.ocr, chip)
            s += ["", "--- So khớp với mặt thẻ (OCR) ---"] + ["- " + x for x in lines] + ["", verdict]
        elif self.ocr:
            s += ["", f"OCR: {len(self.ocr['pans'])} dãy số thẻ, {len(self.ocr['expiries'])} ngày MM/YY (chưa có chip để so khớp)"]
        else:
            s += ["", "Chưa OCR ảnh mặt thẻ."]
        self.summary.delete("1.0", "end")
        self.summary.insert("end", "\n".join(s))

        text = self.raw_log
        if chip and chip.pan and not full:
            text = emv.mask_in_text(text, chip.pan)
        self.log.delete("1.0", "end")
        self.log.insert("end", text)


if __name__ == "__main__":
    App().mainloop()
