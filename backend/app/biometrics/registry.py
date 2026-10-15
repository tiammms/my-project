"""Model registry: where model files live, how to verify them, and lazy, thread-safe loading.

Nothing here fakes a model. If a file or package is missing, the status says so and the
corresponding check returns "unavailable" with installation instructions.
"""
from __future__ import annotations

import hashlib
import importlib.util
import os
import threading
from dataclasses import dataclass
from pathlib import Path

from ..core.config import ROOT

MODEL_DIR = Path(os.environ.get("NS_BIOMETRIC_MODEL_DIR", str(ROOT / "ml/biometric_models")))


@dataclass(frozen=True)
class ModelFile:
    key: str
    filename: str
    url: str
    sha256: str
    size: int
    title: str
    license: str
    purpose: str


# SHA-256 and sizes are taken from the Git LFS pointers in github.com/opencv/opencv_zoo (main branch).
FACE_DETECTOR = ModelFile(
    key="face_detector", filename="face_detection_yunet_2023mar.onnx",
    url="https://github.com/opencv/opencv_zoo/raw/main/models/face_detection_yunet/face_detection_yunet_2023mar.onnx",
    sha256="8f2383e4dd3cfbb4553ea8718107fc0423210dc964f9f4280604804ed2552fa4", size=232589,
    title="OpenCV YuNet — детектор лиц (2023mar)", license="MIT",
    purpose="Находит лица и 5 точек: глаза, кончик носа, уголки рта")
FACE_RECOGNIZER = ModelFile(
    key="face_recognizer", filename="face_recognition_sface_2021dec.onnx",
    url="https://github.com/opencv/opencv_zoo/raw/main/models/face_recognition_sface/face_recognition_sface_2021dec.onnx",
    sha256="0ba9fbfa01b5270c96627c4ef784da859931e02f04419c829e83484087c34e79", size=38696353,
    title="OpenCV SFace — сравнение лиц (2021dec)", license="Apache-2.0",
    purpose="Шаблон лица из 128 чисел для сравнения 1:1 с зарегистрированным")
MODEL_FILES = (FACE_DETECTOR, FACE_RECOGNIZER)


def sha256_of(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


_verified: dict[str, bool] = {}


def file_status(m: ModelFile) -> dict:
    p = MODEL_DIR / m.filename
    st = {"key": m.key, "title": m.title, "file": m.filename, "license": m.license, "purpose": m.purpose,
          "installed": False, "verified": False}
    if p.exists():
        st["installed"] = True
        cache_key = f"{p}:{p.stat().st_size}:{p.stat().st_mtime_ns}"
        if cache_key not in _verified:
            _verified[cache_key] = p.stat().st_size == m.size and sha256_of(p) == m.sha256
        st["verified"] = _verified[cache_key]
        if not st["verified"]:
            st["problem"] = "контрольная сумма не совпала — запустите: python -m scripts.download_models"
    else:
        st["problem"] = "не скачана — запустите: python -m scripts.download_models (run_windows.bat делает это сам)"
    return st


def has_package(name: str) -> bool:
    return importlib.util.find_spec(name) is not None


# ---- lazy singletons -------------------------------------------------------------------------
_lock = threading.Lock()
_cache: dict[str, object] = {}


class ModelUnavailable(Exception):
    def __init__(self, what: str, how: str):
        super().__init__(what)
        self.what, self.how = what, how


def _require_file(m: ModelFile) -> Path:
    st = file_status(m)
    if not st["verified"]:
        raise ModelUnavailable(f"{m.title}: {st.get('problem', 'unavailable')}",
                               "python -m scripts.download_models  (run_windows.bat делает это автоматически)")
    return MODEL_DIR / m.filename


def cv2_module():
    if not has_package("cv2"):
        raise ModelUnavailable("OpenCV не установлен", "pip install -r requirements.txt")
    import cv2
    return cv2


def face_detector():
    """Returns a factory-locked YuNet instance. YuNet is stateful (input size), so callers hold the lock."""
    with _lock:
        if "det" not in _cache:
            cv2 = cv2_module()
            path = _require_file(FACE_DETECTOR)
            _cache["det"] = cv2.FaceDetectorYN.create(str(path), "", (320, 320), 0.7, 0.3, 50)
            _cache["det_lock"] = threading.Lock()
        return _cache["det"], _cache["det_lock"]


def face_recognizer():
    with _lock:
        if "rec" not in _cache:
            cv2 = cv2_module()
            path = _require_file(FACE_RECOGNIZER)
            _cache["rec"] = cv2.FaceRecognizerSF.create(str(path), "")
            _cache["rec_lock"] = threading.Lock()
        return _cache["rec"], _cache["rec_lock"]


ASR_MODEL_NAME = os.environ.get("NS_ASR_MODEL", "small")


def asr_status() -> dict:
    ok = has_package("faster_whisper")
    return {"key": "speech_to_text", "title": f"faster-whisper ({ASR_MODEL_NAME}) — распознавание речи на компьютере",
            "installed": ok, "verified": ok, "license": "MIT (code), MIT (Whisper weights)",
            "purpose": "Переводит запись в текст локально (русский, казахский, английский и ещё ~95 языков); ничего не отправляется в облако",
            **({} if ok else {"problem": "пакет не установлен — run_windows.bat ставит его автоматически, вручную: "
                                         "pip install -r requirements-voice.txt (веса ~480 МБ скачаются при первом использовании)"})}


def asr_model():
    with _lock:
        if "asr" not in _cache:
            if not has_package("faster_whisper"):
                raise ModelUnavailable("faster-whisper не установлен", "pip install -r requirements-voice.txt")
            from faster_whisper import WhisperModel
            _cache["asr"] = WhisperModel(ASR_MODEL_NAME, device="cpu", compute_type="int8",
                                         download_root=str(MODEL_DIR / "whisper"))
            _cache["asr_lock"] = threading.Lock()
        return _cache["asr"], _cache["asr_lock"]


SYNTHETIC_VOICE_STATUS = {
    "key": "synthetic_voice", "title": "Детектор синтезированного / клонированного голоса", "installed": False, "verified": False,
    "license": "-", "purpose": "Оценивал бы, сгенерирована ли речь синтезатором или клонированием голоса",
    "problem": ("Не подключён: проверенной открытой модели нет. Публичные модели (например, AASIST, обученная на ASVspoof 2019) "
                "плохо работают на современных системах клонирования голоса, и их оценка вводила бы в заблуждение. "
                "Чтобы включить: подключить API поставщика или модель, проверенную на свежих дипфейках (см. docs/BIOMETRICS.md)."),
}


def status() -> dict:
    files = [file_status(m) for m in MODEL_FILES]
    return {"model_dir": str(MODEL_DIR), "opencv": has_package("cv2"), "models": files + [asr_status(), SYNTHETIC_VOICE_STATUS],
            "audio_decoding": "любой формат (PyAV)" if has_package("av") else "только WAV (для MP3/M4A/OGG/WEBM установите requirements-voice.txt)"}
