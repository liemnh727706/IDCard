"""Tab xác thực chính danh bằng khuôn mặt: gọi FaceID service tự host (InsightFace)."""
import io
import queue
import threading
import tkinter as tk
from tkinter import filedialog, messagebox, ttk

import faceid_client
import report
import report_ui
import webcam
from imgutil import normalize_to_jpeg
from PIL import Image, ImageTk


class FaceIdTab(ttk.Frame):
    def __init__(self, parent, get_cccd_photo, get_emv_ocr_photo_hint):
        super().__init__(parent, padding=8)
        # get_cccd_photo: () -> bytes|None  (ảnh DG2 đã đọc ở tab CCCD, nếu có)
        self.get_cccd_photo = get_cccd_photo
        self.get_emv_ocr_photo_hint = get_emv_ocr_photo_hint
        self.q = queue.Queue()
        self.ref_bytes = None
        self.live_bytes = None
        self.result = None
        self.ref_imgtk = None
        self.live_imgtk = None

        cfg_url, cfg_key = faceid_client.load_config()

        box = ttk.LabelFrame(self, text="Cấu hình FaceID service (tự host, không phải bên thứ ba)", padding=8)
        box.pack(fill="x")
        ttk.Label(box, text="URL:").grid(row=0, column=0, sticky="e")
        self.url_entry = ttk.Entry(box, width=48)
        self.url_entry.insert(0, cfg_url)
        self.url_entry.grid(row=0, column=1, padx=4, pady=2, sticky="w")
        ttk.Label(box, text="API key:").grid(row=1, column=0, sticky="e")
        self.key_entry = ttk.Entry(box, width=48, show="*")
        self.key_entry.insert(0, cfg_key)
        self.key_entry.grid(row=1, column=1, padx=4, pady=2, sticky="w")
        ttk.Button(box, text="Lưu cấu hình", command=self.save_config).grid(row=0, column=2, rowspan=2, padx=8)

        photos = ttk.Frame(self)
        photos.pack(fill="x", pady=8)

        ref_col = ttk.Frame(photos)
        ref_col.pack(side="left", padx=16)
        ttk.Label(ref_col, text="Ảnh chân dung tham chiếu\n(CCCD hoặc thẻ SV)", justify="center").pack()
        self.ref_canvas = ttk.Label(ref_col, background="#EEEEEE", width=22)
        self.ref_canvas.pack(pady=4)
        ref_btns = ttk.Frame(ref_col)
        ref_btns.pack()
        ttk.Button(ref_btns, text="Lấy từ CCCD (DG2)", command=self.use_cccd_photo).pack(side="left", padx=2)
        ttk.Button(ref_btns, text="Chọn ảnh...", command=self.pick_ref_file).pack(side="left", padx=2)
        ttk.Button(ref_btns, text="Webcam...", command=self.capture_ref_webcam).pack(side="left", padx=2)

        live_col = ttk.Frame(photos)
        live_col.pack(side="left", padx=16)
        ttk.Label(live_col, text="Ảnh chụp trực tiếp người cầm thẻ\n(live)", justify="center").pack()
        self.live_canvas = ttk.Label(live_col, background="#EEEEEE", width=22)
        self.live_canvas.pack(pady=4)
        live_btns = ttk.Frame(live_col)
        live_btns.pack()
        ttk.Button(live_btns, text="Webcam...", command=self.capture_live_webcam).pack(side="left", padx=2)
        ttk.Button(live_btns, text="Chọn ảnh...", command=self.pick_live_file).pack(side="left", padx=2)

        bar = ttk.Frame(self)
        bar.pack(fill="x", pady=6)
        ttk.Button(bar, text="Kiểm tra chính danh", command=self.verify).pack(side="left")
        report_ui.add_buttons(bar, lambda: self.build_report(), "FaceID")

        self.result_label = ttk.Label(self, text="Chưa có kết quả.", font=("Segoe UI", 11, "bold"))
        self.result_label.pack(anchor="w", pady=6)
        self.detail_label = ttk.Label(self, text="", foreground="#555", wraplength=560, justify="left")
        self.detail_label.pack(anchor="w")

        note = ttk.Label(self, foreground="#8a1c1c", wraplength=700, justify="left",
                         text="Lưu ý: đây không phải kiểm tra chống giả mạo (liveness) - chỉ đảm bảo ảnh là chụp "
                              "mới, không chống được việc chụp lại một tấm ảnh in. Ảnh khuôn mặt là dữ liệu sinh "
                              "trắc học nhạy cảm - chỉ dùng với người đã đồng ý, không lưu/chia sẻ ảnh.")
        note.pack(anchor="w", pady=(12, 0))

        self.after(100, self.poll)

    def save_config(self):
        faceid_client.save_config(self.url_entry.get().strip(), self.key_entry.get().strip())
        messagebox.showinfo("Đã lưu", "Đã lưu cấu hình FaceID vào faceid_local.json (không lên Git).")

    # ---- lấy ảnh tham chiếu
    def use_cccd_photo(self):
        data = self.get_cccd_photo()
        if not data:
            messagebox.showwarning("Chưa có ảnh CCCD",
                                   "Chưa đọc CCCD kèm ảnh chân dung (DG2). Đọc CCCD ở tab CCCD và tick "
                                   "'Đọc ảnh chân dung DG2' trước, hoặc chọn/chụp ảnh khác.")
            return
        self._set_ref(data)

    def pick_ref_file(self):
        path = filedialog.askopenfilename(title="Chọn ảnh chân dung",
                                          filetypes=[("Ảnh", "*.jpg *.jpeg *.png *.bmp"), ("Tất cả", "*.*")])
        if path:
            with open(path, "rb") as f:
                self._set_ref(f.read())

    def capture_ref_webcam(self):
        data = webcam.capture_photo(self, "Chụp ảnh chân dung tham chiếu")
        if data:
            self._set_ref(data)

    def pick_live_file(self):
        path = filedialog.askopenfilename(title="Chọn ảnh người cầm thẻ",
                                          filetypes=[("Ảnh", "*.jpg *.jpeg *.png *.bmp"), ("Tất cả", "*.*")])
        if path:
            with open(path, "rb") as f:
                self._set_live(f.read())

    def capture_live_webcam(self):
        data = webcam.capture_photo(self, "Chụp ảnh live người cầm thẻ")
        if data:
            self._set_live(data)

    def _set_ref(self, data: bytes):
        try:
            data = normalize_to_jpeg(data)
        except Exception as e:  # noqa: BLE001
            messagebox.showerror("Ảnh không đọc được", f"Không giải mã được ảnh: {e}")
            return
        self.ref_bytes = data
        self.ref_imgtk = self._thumb(data)
        self.ref_canvas.configure(image=self.ref_imgtk)
        self.result = None
        self._render_result()

    def _set_live(self, data: bytes):
        try:
            data = normalize_to_jpeg(data)
        except Exception as e:  # noqa: BLE001
            messagebox.showerror("Ảnh không đọc được", f"Không giải mã được ảnh: {e}")
            return
        self.live_bytes = data
        self.live_imgtk = self._thumb(data)
        self.live_canvas.configure(image=self.live_imgtk)
        self.result = None
        self._render_result()

    @staticmethod
    def _thumb(data: bytes):
        im = Image.open(io.BytesIO(data)).convert("RGB")
        im.thumbnail((180, 220))
        return ImageTk.PhotoImage(im)

    # ---- gọi service
    def verify(self):
        if not self.ref_bytes or not self.live_bytes:
            messagebox.showwarning("Thiếu ảnh", "Cần cả ảnh tham chiếu và ảnh live.")
            return
        url, key = self.url_entry.get().strip(), self.key_entry.get().strip()
        if not url:
            messagebox.showwarning("Thiếu URL", "Nhập địa chỉ FaceID service.")
            return
        if not messagebox.askyesno(
            "Dữ liệu sinh trắc học nhạy cảm",
            f"Ảnh khuôn mặt sẽ được gửi tới FaceID service tại '{url}'. "
            "Chỉ tiếp tục nếu người trong ảnh đã đồng ý."
        ):
            return
        self.result_label.config(text="Đang gửi ảnh và so khớp... (lần đầu sau khi server khởi động có thể mất "
                                      "đến 1 phút do phải nạp model)", foreground="#555")
        self.detail_label.config(text="")

        def work():
            try:
                res = faceid_client.verify(url, key, self.ref_bytes, self.live_bytes)
                self.q.put(("done", res))
            except faceid_client.FaceIdError as e:
                self.q.put(("error", str(e)))
            except Exception as e:  # noqa: BLE001
                self.q.put(("error", f"Lỗi không mong đợi: {e}"))

        threading.Thread(target=work, daemon=True).start()

    def poll(self):
        try:
            while True:
                kind, payload = self.q.get_nowait()
                if kind == "done":
                    self.result = payload
                    self._render_result()
                elif kind == "error":
                    self.result = None
                    self.result_label.config(text="Lỗi", foreground="#c0392b")
                    self.detail_label.config(text=payload)
        except queue.Empty:
            pass
        self.after(100, self.poll)

    def _render_result(self):
        r = self.result
        if not r:
            self.result_label.config(text="Chưa có kết quả." if (self.ref_bytes and self.live_bytes)
                                      else "Chưa đủ ảnh để kiểm tra.", foreground="#555")
            self.detail_label.config(text="")
            return
        pct = r["similarity"] * 100
        label = {"match": "KHỚP", "no_match": "KHÔNG KHỚP"}.get(r["decision"], "KHÔNG CHẮC CHẮN - cần kiểm tra thủ công")
        color = {"match": "#0a8a00", "no_match": "#c0392b"}.get(r["decision"], "#c87800")
        self.result_label.config(text=f"{label}  (độ giống {pct:.1f}%)", foreground=color)
        th = r["thresholds"]
        self.detail_label.config(
            text=f"Ngưỡng khớp: {th['match'] * 100:.0f}%  |  Ngưỡng loại: {th['reject'] * 100:.0f}%")

    def build_report(self):
        if not self.result:
            return None
        r = self.result
        label = {"match": "KHỚP", "no_match": "KHÔNG KHỚP"}.get(r["decision"], "KHÔNG CHẮC CHẮN")
        rows = [
            ("Kết luận", label),
            ("Độ giống (cosine similarity)", f"{r['similarity'] * 100:.1f}%"),
            ("Ngưỡng khớp / loại", f"{r['thresholds']['match'] * 100:.0f}% / {r['thresholds']['reject'] * 100:.0f}%"),
            ("FaceID service", self.url_entry.get().strip()),
        ]
        sections = [{"heading": "Kết quả xác thực khuôn mặt", "rows": rows}]
        return report.new_report("Báo cáo xác thực chính danh (khuôn mặt)", sections)
