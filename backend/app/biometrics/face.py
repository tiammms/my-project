"""Face detection, quality checks and embeddings (OpenCV YuNet + SFace).

All functions take image bytes in memory; nothing is written to disk.
"""
from __future__ import annotations

import base64
import binascii
import re
from dataclasses import dataclass

import numpy as np

from . import registry

MAX_IMAGE_BYTES = 6 * 1024 * 1024
MAX_SIDE = 1280                 # images are downscaled to this before detection
MIN_DET_SCORE = 0.80
SFACE_COSINE_THRESHOLD = 0.363  # OpenCV's documented same-identity threshold for SFace (cosine)
BLUR_MIN = 40.0                 # variance of Laplacian on the face crop; heuristic quality gate, not a model
DARK_MAX, BRIGHT_MIN = 45.0, 215.0

_DATA_URL = re.compile(r"^data:([a-z]+/[a-z0-9.+-]+);base64,", re.I)


class InputError(ValueError):
    pass


def decode_b64(data: str, max_bytes: int) -> tuple[bytes, str | None]:
    """Accepts a data URL or bare base64. Returns (bytes, declared mime)."""
    mime = None
    m = _DATA_URL.match(data[:100])
    if m:
        mime = m.group(1).lower()
        data = data[m.end():]
    if len(data) > (max_bytes * 4) // 3 + 8:
        raise InputError(f"файл больше {max_bytes // (1024 * 1024)} МБ")
    try:
        raw = base64.b64decode(data, validate=True)
    except (binascii.Error, ValueError):
        raise InputError("файл повреждён (некорректный base64)")
    if not raw:
        raise InputError("файл пустой")
    return raw, mime


def sniff_image(raw: bytes) -> str:
    if raw[:3] == b"\xff\xd8\xff":
        return "jpeg"
    if raw[:8] == b"\x89PNG\r\n\x1a\n":
        return "png"
    if raw[:4] == b"RIFF" and raw[8:12] == b"WEBP":
        return "webp"
    if raw[:2] == b"BM":
        return "bmp"
    raise InputError("формат изображения не поддерживается (нужен JPEG, PNG, WEBP или BMP)")


def decode_image(data: str) -> np.ndarray:
    cv2 = registry.cv2_module()
    raw, _ = decode_b64(data, MAX_IMAGE_BYTES)
    sniff_image(raw)
    img = cv2.imdecode(np.frombuffer(raw, np.uint8), cv2.IMREAD_COLOR)
    if img is None or img.size == 0:
        raise InputError("не удалось прочитать изображение")
    h, w = img.shape[:2]
    if min(h, w) < 64:
        raise InputError("изображение слишком маленькое (минимум 64 пикселя)")
    s = MAX_SIDE / max(h, w)
    if s < 1:
        img = cv2.resize(img, (int(w * s), int(h * s)), interpolation=cv2.INTER_AREA)
    return img


@dataclass
class Face:
    row: np.ndarray          # raw YuNet row: x, y, w, h, 5 landmarks (x, y), score
    score: float

    @property
    def box(self):
        x, y, w, h = self.row[:4]
        return [float(x), float(y), float(w), float(h)]

    @property
    def landmarks(self) -> dict:
        p = self.row[4:14].reshape(5, 2)
        names = ["eye_a", "eye_b", "nose", "mouth_a", "mouth_b"]
        return {n: [round(float(a), 1), round(float(b), 1)] for n, (a, b) in zip(names, p)}

    def yaw_ratio(self) -> float:
        """Horizontal nose offset from the eye midpoint, in inter-ocular distances, along the eye line.

        Positive = nose shifted towards the image's right edge. In an un-mirrored camera frame this means
        the person turned their head to THEIR left. A flat photo cannot produce this change (the nose and
        eyes move together), which is what the liveness challenge relies on.
        """
        p = self.row[4:14].reshape(5, 2).astype(float)
        a, b, nose = p[0], p[1], p[2]
        if b[0] < a[0]:
            a, b = b, a
        e = b - a
        d = float(np.hypot(*e))
        if d < 1e-3:
            return 0.0
        u = e / d
        mid = (a + b) / 2
        return float(np.dot(nose - mid, u) / d)


def detect(img: np.ndarray) -> list[Face]:
    det, lock = registry.face_detector()
    h, w = img.shape[:2]
    with lock:
        det.setInputSize((w, h))
        _, faces = det.detect(img)
    if faces is None:
        return []
    out = [Face(row=f.copy(), score=float(f[14])) for f in faces]
    return sorted(out, key=lambda f: f.box[2] * f.box[3], reverse=True)


def quality(img: np.ndarray, face: Face) -> dict:
    cv2 = registry.cv2_module()
    x, y, w, h = [int(round(v)) for v in face.box]
    H, W = img.shape[:2]
    x0, y0, x1, y1 = max(0, x), max(0, y), min(W, x + w), min(H, y + h)
    crop = img[y0:y1, x0:x1]
    issues = []
    if crop.size == 0:
        return {"usable": False, "issues": ["лицо вне кадра"]}
    gray = cv2.cvtColor(crop, cv2.COLOR_BGR2GRAY)
    sharp = float(cv2.Laplacian(gray, cv2.CV_64F).var())
    bright = float(gray.mean())
    rel = w / W
    if sharp < BLUR_MIN:
        issues.append("изображение размыто")
    if bright < DARK_MAX:
        issues.append("лицо слишком тёмное — добавьте света")
    if bright > BRIGHT_MIN:
        issues.append("лицо засвечено")
    if rel < 0.12:
        issues.append("лицо слишком маленькое — подойдите ближе")
    if x < 0 or y < 0 or x + w > W or y + h > H:
        issues.append("лицо частично вне кадра")
    if face.score < MIN_DET_SCORE:
        issues.append("низкая уверенность детектора")
    return {"usable": not issues, "issues": issues, "sharpness": round(sharp, 1), "brightness": round(bright, 1),
            "face_width_ratio": round(rel, 3), "detection_score": round(face.score, 3)}


def embedding(img: np.ndarray, face: Face) -> np.ndarray:
    rec, lock = registry.face_recognizer()
    with lock:
        aligned = rec.alignCrop(img, face.row)
        feat = rec.feature(aligned)
    v = np.asarray(feat, dtype=np.float32).reshape(-1)
    n = float(np.linalg.norm(v))
    if n == 0:
        raise InputError("не удалось построить шаблон лица")
    return v / n


def cosine(a: np.ndarray, b: np.ndarray) -> float:
    return float(np.dot(a, b) / (np.linalg.norm(a) * np.linalg.norm(b) + 1e-12))


def analyze(img: np.ndarray) -> dict:
    """Single-image analysis: detection + quality. No identity decision, no liveness claim."""
    faces = detect(img)
    h, w = img.shape[:2]
    res = {"image_size": [w, h], "faces_found": len(faces),
           "faces": [{"box": [round(v, 1) for v in f.box], "score": round(f.score, 3), "landmarks": f.landmarks}
                     for f in faces[:5]]}
    reasons = []
    if not faces:
        reasons.append("лицо не найдено")
        res["quality"] = {"usable": False, "issues": ["лицо не найдено"]}
    else:
        res["quality"] = quality(img, faces[0])
        if len(faces) > 1:
            reasons.append(f"в кадре {len(faces)} лица — должен быть виден только один человек")
        reasons += res["quality"]["issues"]
    res["usable"] = bool(faces) and len(faces) == 1 and res["quality"]["usable"]
    res["issues"] = reasons
    res["liveness_note"] = ("Одна фотография не доказывает, что перед камерой живой человек. "
                            "Для этого есть проверка живости (повороты головы).")
    return res
