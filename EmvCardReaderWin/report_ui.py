"""Nút Xuất PDF/TXT/JSON và In cho các tab."""
import datetime
import os
import tkinter as tk
from tkinter import filedialog, messagebox, ttk

import report


def _confirm(parent):
    return messagebox.askokcancel(
        "Dữ liệu cá nhân nhạy cảm",
        "Báo cáo chứa dữ liệu cá nhân của chủ thẻ.\n\nChỉ tiếp tục nếu đây là thẻ của bạn hoặc chủ thẻ đã đồng ý. "
        "Hãy bảo quản file/bản in cẩn thận và hủy khi không còn cần.", parent=parent)


def export_report(parent, get_report, prefix):
    rep = get_report()
    if rep is None:
        messagebox.showinfo("Chưa có dữ liệu", "Hãy đọc thẻ trước khi xuất báo cáo.", parent=parent)
        return
    if not _confirm(parent):
        return
    ts = datetime.datetime.now().strftime("%Y%m%d_%H%M%S")
    path = filedialog.asksaveasfilename(
        parent=parent, title="Lưu báo cáo", defaultextension=".pdf", initialfile=f"{prefix}_{ts}.pdf",
        filetypes=[("PDF", "*.pdf"), ("Văn bản (TXT)", "*.txt"), ("JSON", "*.json")])
    if not path:
        return
    try:
        report.save(rep, path)
    except Exception as e:  # noqa: BLE001
        messagebox.showerror("Lỗi khi lưu", str(e), parent=parent)
        return
    if messagebox.askyesno("Đã lưu", f"Đã lưu báo cáo:\n{path}\n\nMở file ngay?", parent=parent):
        os.startfile(path)


def print_report(parent, get_report):
    rep = get_report()
    if rep is None:
        messagebox.showinfo("Chưa có dữ liệu", "Hãy đọc thẻ trước khi in.", parent=parent)
        return
    if not _confirm(parent):
        return
    names, default = report.list_printers()

    dlg = tk.Toplevel(parent)
    dlg.title("In báo cáo")
    dlg.transient(parent.winfo_toplevel())
    dlg.resizable(False, False)
    ttk.Label(dlg, text="Chọn máy in:", padding=(12, 12, 12, 4)).pack(anchor="w")
    cb = ttk.Combobox(dlg, values=names, state="readonly", width=50)
    cb.pack(padx=12)
    if default in names:
        cb.set(default)
    elif names:
        cb.current(0)
    msg = ttk.Label(dlg, text="", foreground="#555", wraplength=380, padding=(12, 6))
    msg.pack(anchor="w")

    def do_print():
        try:
            msg.config(text=report.print_report(rep, cb.get() or None))
        except Exception as e:  # noqa: BLE001
            msg.config(text=f"Lỗi khi in: {e}")

    bar = ttk.Frame(dlg, padding=12)
    bar.pack(fill="x")
    ttk.Button(bar, text="In", command=do_print).pack(side="left")
    ttk.Button(bar, text="Đóng", command=dlg.destroy).pack(side="right")
    dlg.grab_set()


def add_buttons(frame, get_report, prefix):
    ttk.Button(frame, text="In...", command=lambda: print_report(frame, get_report)).pack(side="right", padx=(4, 0))
    ttk.Button(frame, text="Xuất PDF/TXT/JSON...",
               command=lambda: export_report(frame, get_report, prefix)).pack(side="right", padx=4)
