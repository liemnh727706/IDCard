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
