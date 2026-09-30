"""Hộp thoại xem trước webcam + chụp ảnh 'live', dùng cho xác thực khuôn mặt."""
import tkinter as tk
from tkinter import ttk

import cv2
from PIL import Image, ImageTk


def capture_photo(parent, title="Chụp ảnh (webcam)"):
    """Mở hộp thoại xem trước webcam. Trả về bytes JPEG nếu người dùng bấm Chụp, None nếu Hủy/lỗi."""
    cap = cv2.VideoCapture(0, cv2.CAP_DSHOW)
    if not cap.isOpened():
        cap = cv2.VideoCapture(0)
    if not cap.isOpened():
        tk.messagebox.showerror("Không mở được webcam", "Không tìm thấy webcam hoặc đang bị ứng dụng khác dùng.")
        return None

    dlg = tk.Toplevel(parent)
    dlg.title(title)
    dlg.resizable(False, False)
    dlg.transient(parent.winfo_toplevel())
    dlg.grab_set()

    video_label = ttk.Label(dlg)
    video_label.pack(padx=8, pady=8)
    hint = ttk.Label(dlg, text="Canh mặt vào giữa khung hình, đủ sáng, rồi bấm Chụp.", foreground="#555")
    hint.pack(pady=(0, 4))

    result = {"frame": None, "running": True}

    def on_close():
        result["running"] = False
        cap.release()
        dlg.destroy()

    def do_capture():
        ok, frame = cap.read()
        if ok:
            result["frame"] = frame
        result["running"] = False
        cap.release()
        dlg.destroy()

    bar = ttk.Frame(dlg)
    bar.pack(pady=(0, 8))
    ttk.Button(bar, text="Chụp", command=do_capture).pack(side="left", padx=4)
    ttk.Button(bar, text="Hủy", command=on_close).pack(side="left", padx=4)
    dlg.protocol("WM_DELETE_WINDOW", on_close)

    imgtk_holder = {}

    def update_frame():
        if not result["running"]:
            return
        ok, frame = cap.read()
        if ok:
            rgb = cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)
            im = Image.fromarray(rgb)
            im.thumbnail((480, 480))
            imgtk = ImageTk.PhotoImage(im)
            imgtk_holder["img"] = imgtk  # giữ tham chiếu, tránh bị garbage-collect
            video_label.configure(image=imgtk)
        dlg.after(33, update_frame)

    update_frame()
    parent.wait_window(dlg)

    frame = result["frame"]
    if frame is None:
        return None
    ok, buf = cv2.imencode(".jpg", frame, [cv2.IMWRITE_JPEG_QUALITY, 90])
    if not ok:
        return None
    return buf.tobytes()
