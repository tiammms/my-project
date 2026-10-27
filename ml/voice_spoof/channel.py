"""Audio clean-up and a simulated phone channel, used the same way for real and synthetic speech.

Both classes go through exactly the same processing, so the model cannot learn "telephone sound = real person"
or "silence padding = synthesizer". The phone channel imitates what Saqtan actually hears on a phone:
the far end comes through the cellular codec (narrow band, 8 kHz, G.711 mu-law here), then out of the
loudspeaker into the phone's microphone (room echo, noise, level changes).
"""
from __future__ import annotations

import numpy as np
from scipy import signal

SR = 16000
N = 64600            # AASIST input: ~4.04 s at 16 kHz


def trim_silence(x: np.ndarray, sr: int = SR, frame: int = 400, rel_db: float = -35.0) -> np.ndarray:
    """Cuts leading/trailing silence (TTS output has digital silence, recordings have a noise floor)."""
    if len(x) < frame * 2:
        return x
    n = len(x) // frame
    e = np.sqrt(np.mean(x[: n * frame].reshape(n, frame) ** 2, axis=1) + 1e-12)
    thr = e.max() * 10 ** (rel_db / 20)
    idx = np.where(e > thr)[0]
    if len(idx) == 0:
        return x
    return x[idx[0] * frame: (idx[-1] + 1) * frame]


def normalize(x: np.ndarray, peak: float = 0.5) -> np.ndarray:
    m = np.max(np.abs(x)) + 1e-9
    return (x / m * peak).astype(np.float32)


def crop(x: np.ndarray, rng: np.random.Generator | None, n: int = N) -> np.ndarray:
    """Random crop for training, centre crop for evaluation; short clips are tiled (as in AASIST)."""
    if len(x) < n:
        x = np.tile(x, n // max(len(x), 1) + 1)
    if len(x) == n:
        return x[:n]
    s = rng.integers(0, len(x) - n) if rng is not None else (len(x) - n) // 2
    return x[s: s + n]


def _mulaw(x: np.ndarray, mu: float = 255.0) -> np.ndarray:
    x = np.clip(x, -1, 1)
    y = np.sign(x) * np.log1p(mu * np.abs(x)) / np.log1p(mu)
    q = np.round((y + 1) / 2 * mu) / mu * 2 - 1           # 8-bit quantisation
    return np.sign(q) * ((1 + mu) ** np.abs(q) - 1) / mu


def _rir(rng: np.random.Generator, sr: int = SR) -> np.ndarray:
    rt60 = rng.uniform(0.1, 0.45)
    n = int(sr * rt60)
    t = np.arange(n) / sr
    h = rng.standard_normal(n) * np.exp(-6.9 * t / rt60)
    h[0] = rng.uniform(2, 6)                               # direct path dominates (phone held close)
    return h / np.sqrt(np.sum(h ** 2))


def phone(x: np.ndarray, rng: np.random.Generator) -> np.ndarray:
    """Far end through the cellular codec, then loudspeaker -> phone microphone."""
    lo, hi = rng.uniform(200, 400), rng.uniform(3000, 3600)
    sos = signal.butter(4, [lo, hi], btype="band", fs=SR, output="sos")
    y = signal.sosfilt(sos, x)
    y = signal.resample_poly(y, 1, 2)                      # 8 kHz narrow band
    y = _mulaw(normalize(y, rng.uniform(0.3, 0.9)))
    y = signal.resample_poly(y, 2, 1)[: len(x)]
    # small loudspeaker: weak bass, a resonance, some distortion
    sos2 = signal.butter(2, rng.uniform(250, 500), btype="high", fs=SR, output="sos")
    y = signal.sosfilt(sos2, y)
    y = np.tanh(y * rng.uniform(1.0, 3.0))
    y = signal.fftconvolve(y, _rir(rng))[: len(x)]
    y = normalize(y, 1.0)
    snr = rng.uniform(12, 35)
    noise = rng.standard_normal(len(y))
    if rng.random() < 0.5:                                 # pink-ish noise
        noise = signal.lfilter([1], [1, -0.95], noise)
    noise = noise / (np.sqrt(np.mean(noise ** 2)) + 1e-9) * np.sqrt(np.mean(y ** 2)) * 10 ** (-snr / 20)
    return normalize(y + noise, rng.uniform(0.05, 0.6))


def clean(x: np.ndarray, rng: np.random.Generator) -> np.ndarray:
    """Same level changes and a faint noise floor, no channel."""
    y = normalize(x, 1.0)
    noise = rng.standard_normal(len(y)) * np.sqrt(np.mean(y ** 2)) * 10 ** (-rng.uniform(35, 50) / 20)
    return normalize(y + noise, rng.uniform(0.05, 0.8))


def condition(x: np.ndarray, kind: str, rng: np.random.Generator) -> np.ndarray:
    return phone(x, rng) if kind == "phone" else clean(x, rng)
