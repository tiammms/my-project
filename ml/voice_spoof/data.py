"""Builds the real-vs-synthetic speech set (runs in CI, which has internet access).

Real speech: FLEURS (Google, CC BY 4.0) in Russian, Kazakh, English — people reading sentences.
Synthetic speech: the SAME sentences spoken by speech synthesizers:
  * Piper (neural TTS, several voices per language)         — some voices for training, OTHER voices only for testing
  * eSpeak NG ("robot" formant voice)                       — some variants for training, other variants for testing
  * MMS-TTS (Meta, VITS) and Silero TTS                     — never used for training: a test of engines the model never saw
    (both are licensed for non-commercial use; here they only generate test audio, nothing of them is shipped)

Training text = FLEURS "dev" sentences, test text = FLEURS "test" sentences, real test audio = FLEURS "test" recordings.

Output: <out>/wav/... (16 kHz mono, silence trimmed) and <out>/manifest.csv
"""
from __future__ import annotations

import argparse
import csv
import json
import os
import pathlib
import random
import subprocess
import sys
import tarfile
import urllib.request
import wave

import numpy as np
import soundfile as sf
from scipy import signal

from channel import SR, trim_silence

LANGS = {"ru": "ru_ru", "kk": "kk_kz", "en": "en_us"}
FLEURS = "https://huggingface.co/datasets/google/fleurs/resolve/main/data/{code}/{what}"
PIPER = "https://huggingface.co/rhasspy/piper-voices/resolve/v1.0.0/{path}"

# (language, voice path without extension, split)
PIPER_VOICES = [
    ("ru", "ru/ru_RU/denis/medium/ru_RU-denis-medium", "train"),
    ("ru", "ru/ru_RU/irina/medium/ru_RU-irina-medium", "train"),
    ("ru", "ru/ru_RU/dmitri/medium/ru_RU-dmitri-medium", "test"),
    ("ru", "ru/ru_RU/ruslan/medium/ru_RU-ruslan-medium", "test"),
    ("kk", "kk/kk_KZ/issai/high/kk_KZ-issai-high", "train"),
    ("kk", "kk/kk_KZ/iseke/x_low/kk_KZ-iseke-x_low", "test"),
    ("kk", "kk/kk_KZ/raya/x_low/kk_KZ-raya-x_low", "test"),
    ("en", "en/en_US/lessac/medium/en_US-lessac-medium", "train"),
    ("en", "en/en_US/amy/medium/en_US-amy-medium", "train"),
    ("en", "en/en_US/ryan/high/en_US-ryan-high", "test"),
    ("en", "en/en_GB/alan/medium/en_GB-alan-medium", "test"),
]
ESPEAK = {"ru": "ru", "kk": "kk", "en": "en-us"}
ESPEAK_TRAIN = ["", "+m3", "+f2"]
ESPEAK_TEST = ["+m7", "+f4", "+klatt"]
MMS = {"ru": "facebook/mms-tts-rus", "kk": "facebook/mms-tts-kaz", "en": "facebook/mms-tts-eng"}
SILERO = {"ru": ("ru", "v4_ru", ["aidar", "baya", "kseniya", "xenia", "eugene"]),
          "en": ("en", "v3_en", ["en_0", "en_10", "en_21", "en_45", "en_99"])}


def fetch(url: str, dst: pathlib.Path) -> pathlib.Path:
    if dst.exists() and dst.stat().st_size > 0:
        return dst
    dst.parent.mkdir(parents=True, exist_ok=True)
    tmp = dst.with_suffix(dst.suffix + ".part")
    print("download", url, flush=True)
    req = urllib.request.Request(url, headers={"User-Agent": "saqtan-ml"})
    with urllib.request.urlopen(req, timeout=120) as r, open(tmp, "wb") as f:
        while True:
            b = r.read(1 << 20)
            if not b:
                break
            f.write(b)
    tmp.rename(dst)
    print(f"  {dst.stat().st_size / 1e6:.0f} MB", flush=True)
    return dst


def to16k(x: np.ndarray, sr: int) -> np.ndarray:
    if x.ndim > 1:
        x = x.mean(axis=1)
    if sr != SR:
        g = np.gcd(sr, SR)
        x = signal.resample_poly(x, SR // g, sr // g)
    return x.astype(np.float32)


def save(out: pathlib.Path, rel: str, x: np.ndarray, sr: int) -> str | None:
    x = trim_silence(to16k(x, sr))
    if len(x) < SR:                      # under 1 s of speech: unusable
        return None
    p = out / "wav" / rel
    p.parent.mkdir(parents=True, exist_ok=True)
    sf.write(p, x, SR, subtype="PCM_16")
    return str(p.relative_to(out))


def fleurs(cache: pathlib.Path, lang: str, split: str, limit: int, rng: random.Random):
    """Returns [(wav_member_name, text)] and the opened tar for the requested split."""
    code = LANGS[lang]
    tsv = fetch(FLEURS.format(code=code, what=f"{split}.tsv"), cache / code / f"{split}.tsv")
    rows = []
    for line in open(tsv, encoding="utf-8"):
        c = line.rstrip("\n").split("\t")
        if len(c) >= 3:
            rows.append((c[1], c[2]))
    rng.shuffle(rows)
    tar = fetch(FLEURS.format(code=code, what=f"audio/{split}.tar.gz"), cache / code / f"{split}.tar.gz")
    return rows[:limit], tar


def build(out: pathlib.Path, cache: pathlib.Path, n_train: int, n_test: int, n_src_test: int, skip_eval_engines: bool):
    rng = random.Random(1234)
    manifest = []

    def add(path, label, split, lang, source, engine):
        if path:
            manifest.append({"path": path, "label": label, "split": split, "lang": lang, "source": source, "engine": engine})

    texts = {}
    for lang in LANGS:
        for split, n in (("dev", n_train), ("test", n_test)):
            rows, tar = fleurs(cache, lang, split, n, rng)
            want = {name: text for name, text in rows}
            got = 0
            with tarfile.open(tar) as t:
                for m in t:
                    base = os.path.basename(m.name)
                    if m.isfile() and base in want:
                        x, sr = sf.read(t.extractfile(m), dtype="float32")
                        s = "train" if split == "dev" else "test"
                        add(save(out, f"real/{lang}/{split}/{base}", x, sr), "bonafide", s, lang, "fleurs", "human")
                        got += 1
            texts[(lang, split)] = [t for _, t in rows]
            print(f"fleurs {lang} {split}: {got}", flush=True)

    # ---- Piper
    from piper.voice import PiperVoice
    for lang, vp, split in PIPER_VOICES:
        name = vp.split("/")[-1]
        onnx = fetch(PIPER.format(path=vp + ".onnx"), cache / "piper" / (name + ".onnx"))
        cfg = fetch(PIPER.format(path=vp + ".onnx.json"), cache / "piper" / (name + ".onnx.json"))
        voice = PiperVoice.load(str(onnx), config_path=str(cfg))
        pool = texts[(lang, "dev" if split == "train" else "test")]
        n_voices = sum(1 for l, _, s in PIPER_VOICES if l == lang and s == split)
        k = (len(pool) // 2 // n_voices) if split == "train" else n_src_test
        sel = pool[: k] if split == "test" else rng.sample(pool, min(k, len(pool)))
        for i, text in enumerate(sel):
            tmp = out / "tmp.wav"
            with wave.open(str(tmp), "wb") as wf:
                voice.synthesize(text, wf)
            x, sr = sf.read(tmp, dtype="float32")
            add(save(out, f"fake/piper/{name}/{i:04d}.wav", x, sr), "spoof", split, lang, f"piper:{name}", "piper")
        print("piper", name, split, len(sel), flush=True)

    # ---- eSpeak NG (robot voice)
    for lang, v in ESPEAK.items():
        for split, variants in (("train", ESPEAK_TRAIN), ("test", ESPEAK_TEST)):
            pool = texts[(lang, "dev" if split == "train" else "test")]
            k = (len(pool) // 2 // len(variants)) if split == "train" else n_src_test // len(variants) + 1
            for var in variants:
                sel = rng.sample(pool, min(k, len(pool)))
                for i, text in enumerate(sel):
                    tmp = out / "tmp.wav"
                    speed = str(rng.randint(140, 185))
                    subprocess.run(["espeak-ng", "-v", v + var, "-s", speed, "-w", str(tmp), text], check=True,
                                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
                    x, sr = sf.read(tmp, dtype="float32")
                    tag = (var or "+default").lstrip("+")
                    add(save(out, f"fake/espeak/{lang}/{tag}/{i:04d}.wav", x, sr), "spoof", split, lang, f"espeak:{lang}{var}", "espeak")
            print("espeak", lang, split, flush=True)

    if not skip_eval_engines:
        import torch
        # ---- MMS-TTS (test only: an engine the model never saw)
        from transformers import AutoTokenizer, VitsModel
        for lang, mid in MMS.items():
            tok = AutoTokenizer.from_pretrained(mid)
            model = VitsModel.from_pretrained(mid).eval()
            for i, text in enumerate(texts[(lang, "test")][:n_src_test]):
                inp = tok(text, return_tensors="pt")
                if inp["input_ids"].shape[1] == 0:
                    continue
                with torch.no_grad():
                    w = model(**inp).waveform[0].numpy()
                add(save(out, f"fake/mms/{lang}/{i:04d}.wav", w, model.config.sampling_rate), "spoof", "test", lang, f"mms:{lang}", "mms")
            print("mms", lang, flush=True)
        # ---- Silero TTS (test only)
        for lang, (l, spk_model, speakers) in SILERO.items():
            model, _ = torch.hub.load("snakers4/silero-models", "silero_tts", language=l, speaker=spk_model, trust_repo=True)
            for i, text in enumerate(texts[(lang, "test")][:n_src_test]):
                try:
                    w = model.apply_tts(text=text[:900], speaker=speakers[i % len(speakers)], sample_rate=24000).numpy()
                except Exception as e:      # some sentences contain symbols the model rejects
                    print("silero skip:", str(e)[:80])
                    continue
                add(save(out, f"fake/silero/{lang}/{i:04d}.wav", w, 24000), "spoof", "test", lang, f"silero:{lang}", "silero")
            print("silero", lang, flush=True)

    # validation = 15 % of training clips (same sources), used only to pick the threshold and the best epoch
    train = [m for m in manifest if m["split"] == "train"]
    rng.shuffle(train)
    for m in train[: int(0.15 * len(train))]:
        m["split"] = "val"
    with open(out / "manifest.csv", "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=list(manifest[0].keys()))
        w.writeheader()
        w.writerows(manifest)
    counts = {}
    for m in manifest:
        k = f'{m["split"]}/{m["label"]}/{m["engine"]}'
        counts[k] = counts.get(k, 0) + 1
    json.dump(counts, open(out / "counts.json", "w"), indent=1, sort_keys=True)
    print(json.dumps(counts, indent=1, sort_keys=True))


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default="work/data")
    ap.add_argument("--cache", default="work/cache")
    ap.add_argument("--train", type=int, default=380, help="FLEURS dev sentences per language")
    ap.add_argument("--test", type=int, default=260, help="FLEURS test sentences per language")
    ap.add_argument("--per-source-test", type=int, default=90)
    ap.add_argument("--skip-eval-engines", action="store_true")
    a = ap.parse_args()
    out = pathlib.Path(a.out); out.mkdir(parents=True, exist_ok=True)
    build(out, pathlib.Path(a.cache), a.train, a.test, a.per_source_test, a.skip_eval_engines)
    (out / "tmp.wav").unlink(missing_ok=True)
    sys.exit(0)
