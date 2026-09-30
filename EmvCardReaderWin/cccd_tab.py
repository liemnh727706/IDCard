"""Tab đọc CCCD gắn chip (eMRTD) trong app Windows."""
import io
import queue
import threading
import tkinter as tk
from tkinter import filedialog, scrolledtext, ttk

import bac
import cccd_report
import mrz
import ocr_win
import pcsc
import report_ui

DG_NAMES = {"61": "DG1", "75": "DG2", "6B": "DG11", "6C": "DG12", "6D": "DG13", "6E": "DG14",
            "6F": "DG15", "77": "SOD"}


class CccdTab(ttk.Frame):
    def __init__(self, parent, get_reader, set_status):
        super().__init__(parent, padding=8)
        self.get_reader, self.set_status = get_reader, set_status
        self.q = queue.Queue()
        self.photo = None
        self.photo_bytes = None
        self.report = None
        self.fields = None
        self.want_extra = tk.BooleanVar(value=True)
        self.want_photo = tk.BooleanVar(value=False)

        box = ttk.LabelFrame(self, text="Khóa mở thẻ: 3 thông tin in ở dòng MRZ mặt sau CCCD", padding=8)
        box.pack(fill="x")
        ttk.Button(box, text="Chọn ảnh mặt sau CCCD, OCR MRZ tự điền...", command=self.ocr_mrz).grid(
            row=5, column=0, columnspan=3, sticky="w", pady=(6, 0))
        ttk.Label(box, text="Dòng MRZ 1:").grid(row=0, column=0, sticky="e")
        self.mrz1 = ttk.Entry(box, width=34, font=("Consolas", 10))
        self.mrz1.grid(row=0, column=1, padx=4, pady=2, sticky="w")
        ttk.Label(box, text="Dòng MRZ 2:").grid(row=1, column=0, sticky="e")
        self.mrz2 = ttk.Entry(box, width=34, font=("Consolas", 10))
        self.mrz2.grid(row=1, column=1, padx=4, pady=2, sticky="w")
        ttk.Button(box, text="Điền tự động từ MRZ", command=self.fill_from_mrz).grid(row=0, column=2, rowspan=2, padx=8)

        ttk.Label(box, text="Số giấy tờ (9 ký tự):").grid(row=2, column=0, sticky="e")
        self.doc = ttk.Entry(box, width=14, font=("Consolas", 10))
        self.doc.grid(row=2, column=1, padx=4, pady=2, sticky="w")
        ttk.Label(box, text="Ngày sinh (YYMMDD):").grid(row=3, column=0, sticky="e")
        self.dob = ttk.Entry(box, width=10, font=("Consolas", 10))
        self.dob.grid(row=3, column=1, padx=4, pady=2, sticky="w")
        ttk.Label(box, text="Hết hạn (YYMMDD):").grid(row=4, column=0, sticky="e")
        self.doe = ttk.Entry(box, width=10, font=("Consolas", 10))
        self.doe.grid(row=4, column=1, padx=4, pady=2, sticky="w")

        opt = ttk.Frame(self)
        opt.pack(fill="x", pady=6)
        ttk.Button(opt, text="Đọc CCCD", command=self.read).pack(side="left")
        ttk.Checkbutton(opt, text="Đọc thêm DG11/DG12/DG13 (thông tin bổ sung)",
                        variable=self.want_extra).pack(side="left", padx=10)
        ttk.Checkbutton(opt, text="Đọc ảnh chân dung (DG2, chậm hơn)",
                        variable=self.want_photo).pack(side="left", padx=10)
        ttk.Button(opt, text="Xóa kết quả", command=self.clear).pack(side="right")
        report_ui.add_buttons(opt, lambda: self.report, "CCCD")

        self.progress = ttk.Label(self, text="", foreground="#555")
        self.progress.pack(fill="x")

        body = ttk.Frame(self)
        body.pack(fill="both", expand=True)
        self.out = scrolledtext.ScrolledText(body, font=("Consolas", 10), wrap="word")
        self.out.pack(side="left", fill="both", expand=True)
        self.img_label = ttk.Label(body, text="", anchor="n")
        self.img_label.pack(side="right", padx=8, anchor="n")

        self.after(100, self.poll)

    # ---- nhập liệu
    def fill_from_mrz(self):
        l1 = self.mrz1.get().strip().replace(" ", "").upper()
        l2 = self.mrz2.get().strip().replace(" ", "").upper()
        if len(l1) >= 14:
            self.doc.delete(0, "end")
            self.doc.insert(0, l1[5:14].replace("<", ""))
        if len(l2) >= 14:
            self.dob.delete(0, "end")
            self.dob.insert(0, l2[0:6])
            self.doe.delete(0, "end")
            self.doe.insert(0, l2[8:14])

    def ocr_mrz(self):
        path = filedialog.askopenfilename(
            title="Chọn ảnh mặt sau CCCD (thấy rõ 3 dòng MRZ ở đáy thẻ)",
            filetypes=[("Ảnh", "*.jpg *.jpeg *.png *.bmp *.tif *.tiff"), ("Tất cả", "*.*")])
        if not path:
            return
        self.set_status("Đang OCR dòng MRZ...")

        def work():
            try:
                self.q.put(("mrz", mrz.parse(ocr_win.ocr_image_multicrop(path))))
            except Exception as e:  # noqa: BLE001
                self.q.put(("error", f"OCR lỗi: {e}"))

        threading.Thread(target=work, daemon=True).start()

    def apply_mrz(self, r):
        if r is None:
            self.set_status("Không nhận ra dòng MRZ. Chụp sát, đủ sáng, thấy rõ 3 dòng chữ dưới đáy mặt sau thẻ.")
            return
        doc, dob, doe, note = r
        for entry, val in ((self.doc, doc), (self.dob, dob), (self.doe, doe)):
            if val:
                entry.delete(0, "end")
                entry.insert(0, val)
        self.set_status(note + " Kiểm tra lại các ô rồi bấm Đọc CCCD.")

    def clear(self):
        self.out.delete("1.0", "end")
        self.img_label.config(image="", text="")
        self.photo = None
        self.photo_bytes = None
        self.report = None
        self.fields = None
        self.progress.config(text="")

    # ---- đọc thẻ (luồng nền)
    def read(self):
        doc = self.doc.get().strip().upper()
        dob, doe = self.dob.get().strip(), self.doe.get().strip()
        if not doc or not (dob.isdigit() and len(dob) == 6 and doe.isdigit() and len(doe) == 6):
            self.set_status("Nhập đủ số giấy tờ, ngày sinh và ngày hết hạn (6 chữ số YYMMDD).")
            return
        reader = self.get_reader()
        if not reader:
            self.set_status("Chưa chọn đầu đọc thẻ.")
            return
        self.clear()
        self.set_status("Đang đọc CCCD...")
        args = (reader, doc, dob, doe, self.want_extra.get(), self.want_photo.get())
        threading.Thread(target=self._work, args=args, daemon=True).start()

    def _work(self, reader, doc, dob, doe, extra, photo):
        card = None
        try:
            card = pcsc.Card(reader)
            cands = [doc] + ([doc[-9:]] if len(doc) > 9 else [])
            sess, last = None, None
            for c in cands:
                try:
                    self.q.put(("progress", f"Xác thực BAC với số giấy tờ '{c[:2]}…'"))
                    sess = bac.authenticate(card, c, dob, doe)
                    break
                except bac.BacError as e:
                    last = e
            if sess is None:
                raise last

            lines = ["Xác thực BAC thành công.", ""]
            com = sess.read_file(bac.FILES["COM"])
            present, lds, mrz_lines, fields, extras = [], "", [], {}, {}
            if com:
                for tag, val, _ in bac.parse_tlv(_strip_head(com)):
                    if tag == "5C":
                        present = [DG_NAMES.get(f"{b:02X}", f"{b:02X}") for b in val]
                    if tag == "5F01":
                        lds = val.decode("ascii", "replace")
                        lines.append(f"Phiên bản LDS: {lds}")
                lines.append("Các file dữ liệu có trên thẻ: " + (", ".join(present) or "(không rõ)"))
                lines.append("")

            dg1 = sess.read_file(bac.FILES["DG1"])
            if dg1:
                mrz_lines, fields = bac.parse_dg1(dg1)
                lines.append("=== DG1: thông tin MRZ ===")
                lines += mrz_lines
                lines.append("")
                lines += [f"{k:24s}: {v}" for k, v in fields.items()]
            else:
                lines.append("Không đọc được DG1.")

            if extra:
                for name in ("DG11", "DG12", "DG13"):
                    if present and name not in present:
                        continue
                    self.q.put(("progress", f"Đang đọc {name}..."))
                    try:
                        data = sess.read_file(bac.FILES[name])
                    except bac.BacError as e:
                        data = None
                        lines += ["", f"=== {name}: lỗi {e} ==="]
                    if data:
                        dump = bac.dump_tlv(_strip_head(data))
                        extras[name] = (len(data), dump, data)
                        lines += ["", f"=== {name} ({len(data)} byte) ==="]
                        lines += dump
                    elif name in present:
                        lines += ["", f"=== {name}: bị khóa hoặc không đọc được ==="]

            img = None
            if photo:
                self.q.put(("progress", "Đang đọc ảnh chân dung DG2..."))
                dg2 = sess.read_file(bac.FILES["DG2"],
                                     progress=lambda a, b: self.q.put(("progress", f"DG2: {a}/{b} byte")))
                img = bac.find_image(dg2) if dg2 else None
                if not dg2:
                    info = "không đọc được DG2"
                elif img:
                    info = f"{len(dg2)} byte, đã giải mã ảnh"
                else:
                    info = f"{len(dg2)} byte, không tìm thấy ảnh JPEG/JPEG2000"
                lines += ["", "=== DG2: ảnh chân dung ===", info]

            lines += ["", "Lưu ý: chưa xác thực chữ ký số (SOD) nên chưa chứng minh dữ liệu là nguyên bản.",
                      "Dữ liệu chỉ hiển thị trên màn hình này, không được lưu hay gửi đi."]
            rep = cccd_report.build(fields, mrz_lines, lds, present, extras, img, card.atr, reader)
            self.q.put(("done", ("\n".join(lines), img, rep, fields)))
        except Exception as e:  # noqa: BLE001
            self.q.put(("error", str(e)))
        finally:
            if card:
                card.close()

    def poll(self):
        try:
            while True:
                kind, p = self.q.get_nowait()
                if kind == "progress":
                    self.progress.config(text=p)
                elif kind == "mrz":
                    self.apply_mrz(p)
                elif kind == "error":
                    self.progress.config(text="")
                    self.out.insert("end", "LỖI: " + p)
                    self.set_status("Đọc CCCD thất bại.")
                elif kind == "done":
                    text, img, self.report, self.fields = p
                    self.photo_bytes = img
                    self.progress.config(text="")
                    self.out.insert("end", text)
                    self.show_image(img)
                    self.set_status("Đã đọc xong CCCD.")
        except queue.Empty:
            pass
        self.after(100, self.poll)

    def show_image(self, data):
        if not data:
            return
        try:
            from PIL import Image, ImageTk
            im = Image.open(io.BytesIO(data))
            im.thumbnail((260, 340))
            self.photo = ImageTk.PhotoImage(im)
            self.img_label.config(image=self.photo)
        except Exception as e:  # noqa: BLE001
            self.img_label.config(text=f"Không hiển thị được ảnh:\n{e}")


def _strip_head(f: bytes) -> bytes:
    """Bỏ tag + độ dài ngoài cùng của file, trả về phần nội dung."""
    if len(f) < 2:
        return b""
    ln = f[1]
    if ln < 0x80:
        return f[2:]
    n = ln & 0x7F
    return f[2 + n:]
