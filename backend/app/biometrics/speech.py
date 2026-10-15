"""Local speech-to-text via faster-whisper (CTranslate2 Whisper). Nothing is sent to a cloud service."""
from __future__ import annotations

import time

import numpy as np

from . import registry

LANGS = {"auto": None, "ru": "ru", "kk": "kk", "en": "en"}


def transcribe(x: np.ndarray, language: str = "auto") -> dict:
    model, lock = registry.asr_model()
    t0 = time.perf_counter()
    with lock:
        segments, info = model.transcribe(x.astype(np.float32), language=LANGS.get(language), beam_size=1,
                                          vad_filter=True, condition_on_previous_text=False)
        segs = [{"start": round(s.start, 2), "end": round(s.end, 2), "text": s.text.strip()} for s in segments]
    return {"engine": f"faster-whisper ({registry.ASR_MODEL_NAME}, CPU int8, локально)",
            "language": info.language, "language_probability": round(float(info.language_probability), 3),
            "text": " ".join(s["text"] for s in segs).strip(), "segments": segs,
            "seconds": round(time.perf_counter() - t0, 2)}
