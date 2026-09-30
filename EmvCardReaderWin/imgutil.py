"""Tiện ích ảnh dùng chung: chuẩn hóa về JPEG, thu nhỏ trước khi gửi/lưu vào báo cáo."""
import io

from PIL import Image


def normalize_to_jpeg(data: bytes, max_dim: int = 1280, quality: int = 88) -> bytes:
    """Giải mã bằng Pillow (hỗ trợ cả JPEG2000, PNG, BMP...) rồi mã hóa lại thành JPEG chuẩn,
    thu nhỏ nếu cạnh dài hơn max_dim. Dùng cho ảnh gửi lên FaceID service và ảnh chèn vào PDF -
    ảnh gốc (chụp điện thoại/webcam) có thể vài MB, không thu nhỏ thì chậm và PDF nặng."""
    im = Image.open(io.BytesIO(data)).convert("RGB")
    if max(im.size) > max_dim:
        scale = max_dim / max(im.size)
        im = im.resize((int(im.width * scale), int(im.height * scale)), Image.LANCZOS)
    out = io.BytesIO()
    im.save(out, format="JPEG", quality=quality)
    return out.getvalue()
