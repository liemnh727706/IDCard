"""Gọi FaceID service tự host (InsightFace) để so khớp 1:1 ảnh chân dung với ảnh chụp live.

Cấu hình (URL mặc định + API key) đọc từ faceid_local.json cạnh file này - file KHÔNG lên git
(xem .gitignore) để không lộ key khi repo công khai trên GitHub.
"""
import json
import os
import uuid

import requests

DEFAULT_URL = "https://cropnlu.duckdns.org/faceid"
_CONFIG_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), "faceid_local.json")
_USER_AGENT = "EmvCardReaderWin/1.0 (Windows)"


class FaceIdError(Exception):
    pass


def load_config():
    """Trả về (url, key). Nếu chưa có faceid_local.json, dùng URL mặc định + key rỗng."""
    if os.path.exists(_CONFIG_PATH):
        try:
            with open(_CONFIG_PATH, "r", encoding="utf-8") as f:
                data = json.load(f)
            return data.get("url", DEFAULT_URL) or DEFAULT_URL, data.get("key", "")
        except Exception:  # noqa: BLE001
            pass
    return DEFAULT_URL, ""


def save_config(url: str, key: str):
    with open(_CONFIG_PATH, "w", encoding="utf-8") as f:
        json.dump({"url": url, "key": key}, f, ensure_ascii=False, indent=2)


def verify(url: str, api_key: str, image_a: bytes, image_b: bytes) -> dict:
    """Gọi POST {url}/face/verify. Trả về {"similarity", "decision", "thresholds"}."""
    endpoint = url.rstrip("/") + "/face/verify"
    try:
        resp = requests.post(
            endpoint,
            headers={"X-API-Key": api_key, "User-Agent": _USER_AGENT},
            files={
                "image_a": ("a.jpg", image_a, "image/jpeg"),
                "image_b": ("b.jpg", image_b, "image/jpeg"),
            },
            # (connect, read) - lần gọi đầu server phải nạp 5 model ONNX của InsightFace từ đĩa;
            # với server giới hạn 2 CPU, việc này đã đo thực tế mất hơn 45s nên cần margin rộng.
            timeout=(8, 90),
        )
    except requests.RequestException as e:
        raise FaceIdError(
            f"Không kết nối được tới FaceID service ({url}): {e}. "
            "Kiểm tra máy có Internet, địa chỉ server đúng chưa."
        ) from e

    if resp.status_code != 200:
        try:
            detail = resp.json().get("detail", resp.text)
        except Exception:  # noqa: BLE001
            detail = resp.text
        raise FaceIdError(f"Server trả lỗi ({resp.status_code}): {detail}")

    try:
        return resp.json()
    except ValueError as e:
        raise FaceIdError(f"Phản hồi không phải JSON hợp lệ: {resp.text[:200]}") from e
