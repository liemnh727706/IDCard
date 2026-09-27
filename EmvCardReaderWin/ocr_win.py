"""OCR ảnh mặt thẻ bằng Windows.Media.Ocr (chạy hoàn toàn trên máy, không gửi ảnh đi đâu)."""
import asyncio
import os


async def _ocr(path):
    from winrt.windows.graphics.imaging import BitmapDecoder
    from winrt.windows.media.ocr import OcrEngine
    from winrt.windows.storage import FileAccessMode, StorageFile

    f = await StorageFile.get_file_from_path_async(os.path.abspath(path))
    stream = await f.open_async(FileAccessMode.READ)
    decoder = await BitmapDecoder.create_async(stream)
    bmp = await decoder.get_software_bitmap_async()
    engine = OcrEngine.try_create_from_user_profile_languages()
    if engine is None:
        raise RuntimeError("Windows chưa có gói ngôn ngữ OCR (Settings > Time & Language > Language).")
    result = await engine.recognize_async(bmp)
    return chr(10).join(line.text for line in result.lines)


def ocr_image(path: str) -> str:
    return asyncio.run(_ocr(path))


def ocr_image_multicrop(path: str, fractions=None) -> str:
    """OCR nhiều lần trên ảnh bị cắt theo các mức bề ngang khác nhau.

    Windows OCR bỏ nguyên dòng chứa chuỗi ký tự đệm '<<<' của MRZ, nên cắt bỏ phần đuôi
    để dòng còn lại chỉ có chữ và số; ghép kết quả các lần cắt rồi để bộ phân tích MRZ lọc.
    """
    import tempfile

    from PIL import Image

    fractions = fractions or [round(0.2 + 0.03 * i, 2) for i in range(28)]
    im = Image.open(path).convert("RGB")
    w, h = im.size
    texts = [ocr_image(path)]
    for f in fractions:
        fd, tmp = tempfile.mkstemp(suffix=".png")
        os.close(fd)
        try:
            im.crop((0, 0, max(int(w * f), 50), h)).save(tmp)
            texts.append(ocr_image(tmp))
        finally:
            os.remove(tmp)
    return chr(10).join(texts)
