"""Audio decoding and signal-quality measurements (in memory only).

These are measurements of the recording (level, clipping, noise, how much of it is speech). They are NOT a
deepfake detector and are never presented as one.
"""
from __future__ import annotations

import io
import wave
from math import gcd

import numpy as np

from . import registry
from .face import InputError, decode_b64

SR = 16000
MAX_AUDIO_BYTES = 15 * 1024 * 1024
MAX_SECONDS = 120.0
MIN_SECONDS = 0.5


def _resample(x: np.ndarray, sr: int) -> np.ndarray:
    if sr == SR:
        return x
    from scipy.signal import resample_poly
    g = gcd(sr, SR)
    return resample_poly(x, SR // g, sr // g).astype(np.float32)


def _decode_wav(raw: bytes) -> np.ndarray:
    try:
        with wave.open(io.BytesIO(raw)) as w:
            ch, sw, sr, n = w.getnchannels(), w.getsampwidth(), w.getframerate(), w.getnframes()
            if n / max(sr, 1) > MAX_SECONDS + 1:
                raise InputError(f"запись длиннее {int(MAX_SECONDS)} секунд")
            frames = w.readframes(n)
    except wave.Error as e:
        raise InputError(f"WAV-файл не читается ({e}); поддерживается PCM 8/16/24/32 бит")
    if sw == 1:
        x = (np.frombuffer(frames, np.uint8).astype(np.float32) - 128) / 128
    elif sw == 2:
        x = np.frombuffer(frames, "<i2").astype(np.float32) / 32768
    elif sw == 3:
        b = np.frombuffer(frames, np.uint8).reshape(-1, 3)
        i = (b[:, 0].astype(np.int32) | (b[:, 1].astype(np.int32) << 8) | (b[:, 2].astype(np.int32) << 16))
        i = np.where(i >= 1 << 23, i - (1 << 24), i)
        x = i.astype(np.float32) / (1 << 23)
    elif sw == 4:
        x = np.frombuffer(frames, "<i4").astype(np.float32) / (1 << 31)
    else:
        raise InputError("неподдерживаемая разрядность WAV")
    if ch > 1:
        x = x[: len(x) - len(x) % ch].reshape(-1, ch).mean(axis=1)
    return _resample(x, sr)


def _decode_pyav(raw: bytes) -> np.ndarray:
    """MP3/M4A/OGG/WEBM/FLAC... via PyAV (FFmpeg bindings, installed with faster-whisper)."""
    import av
    limit = int((MAX_SECONDS + 1) * SR)
    chunks, total = [], 0
    try:
        with av.open(io.BytesIO(raw), mode="r") as c:
            if not c.streams.audio:
                raise InputError("в файле нет звуковой дорожки")
            rs = av.AudioResampler(format="s16", layout="mono", rate=SR)
            for frame in c.decode(audio=0):
                for f in rs.resample(frame):
                    a = f.to_ndarray().reshape(-1)
                    chunks.append(a)
                    total += len(a)
                if total > limit:
                    raise InputError(f"запись длиннее {int(MAX_SECONDS)} секунд — обрежьте её")
            for f in rs.resample(None):
                chunks.append(f.to_ndarray().reshape(-1))
    except InputError:
        raise
    except Exception as e:
        raise InputError(f"аудиофайл не удалось декодировать ({type(e).__name__}: {str(e)[:120]})")
    if not chunks:
        raise InputError("в файле нет звука")
    return np.concatenate(chunks).astype(np.float32) / 32768.0


def decode_audio(data: str) -> tuple[np.ndarray, str]:
    raw, mime = decode_b64(data, MAX_AUDIO_BYTES)
    if raw[:4] == b"RIFF" and raw[8:12] == b"WAVE":
        x, how = _decode_wav(raw), "wav"
    elif registry.has_package("av"):
        x, how = _decode_pyav(raw), "pyav"
    else:
        raise InputError("поддерживается только WAV. Для MP3/M4A/OGG/WEBM установите requirements-voice.txt "
                         "или запишите голос прямо на странице")
    dur = len(x) / SR
    if dur < MIN_SECONDS:
        raise InputError("запись слишком короткая (меньше 0,5 секунды)")
    if dur > MAX_SECONDS + 1:
        raise InputError(f"запись длиннее {int(MAX_SECONDS)} секунд — обрежьте её")
    if not np.isfinite(x).all():
        raise InputError("в аудио некорректные значения")
    return x, how


def signal_quality(x: np.ndarray) -> dict:
    dur = len(x) / SR
    peak = float(np.max(np.abs(x))) if len(x) else 0.0
    rms = float(np.sqrt(np.mean(x ** 2))) if len(x) else 0.0
    clip = float(np.mean(np.abs(x) >= 0.999))
    hop = int(0.02 * SR)
    n = len(x) // hop
    fr = x[: n * hop].reshape(n, hop) if n else np.zeros((1, hop), np.float32)
    e = 10 * np.log10(np.mean(fr ** 2, axis=1) + 1e-10)
    noise = float(np.percentile(e, 10))
    loud = float(np.percentile(e, 95))
    speech = e > max(noise + 10, -55)
    speech_ratio = float(speech.mean())
    snr = loud - noise
    issues = []
    if rms < 0.003:
        issues.append("запись почти беззвучная — проверьте микрофон")
    if clip > 0.01:
        issues.append("звук перегружен (клиппинг) — говорите дальше от микрофона")
    if speech_ratio < 0.1:
        issues.append("в записи почти нет речи")
    if snr < 12:
        issues.append("много фонового шума")
    return {"duration_s": round(dur, 2), "rms_dbfs": round(20 * np.log10(rms + 1e-10), 1),
            "peak": round(peak, 3), "clipping_ratio": round(clip, 4), "speech_ratio": round(speech_ratio, 3),
            "snr_estimate_db": round(snr, 1), "issues": issues, "usable": not issues or issues == ["много фонового шума"],
            "note": "Это измерения качества записи, а не определение подделки голоса."}
