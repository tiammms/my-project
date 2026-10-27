"""Fine-tunes AASIST-L (Jung et al., 2022; code and weights: github.com/clovaai/aasist, MIT) on real vs synthetic
speech, evaluates it honestly and exports ONNX for the Android app.

  python train_eval.py --aasist <clone> --data work/data --out work/out

Evaluation (all numbers go to metrics.json):
  * baseline = the original AASIST-L weights (trained on ASVspoof 2019 LA, English, studio audio) without changes;
  * fine-tuned model;
  each on the test set, under two conditions: "clean" and "phone" (simulated cellular codec + loudspeaker + microphone).
  Test sources are split into: piper voices never used in training, eSpeak variants never used in training,
  and engines never used in training at all (MMS-TTS, Silero). Real test speech = FLEURS test recordings.
  The decision threshold is picked on the validation split (target: about 2 % false alarms on real speech, phone
  condition) and then applied unchanged to the test set.
"""
from __future__ import annotations

import argparse
import csv
import hashlib
import json
import math
import pathlib
import sys
import time

import numpy as np
import soundfile as sf
import torch
import torch.nn.functional as F

from channel import N, condition, crop

TARGET_FPR = 0.02


def load_model(aasist: pathlib.Path, weights: pathlib.Path | None):
    sys.path.insert(0, str(aasist))
    from models.AASIST import Model
    cfg = json.load(open(aasist / "config" / "AASIST-L.conf"))["model_config"]
    m = Model(cfg)
    if weights is not None:
        m.load_state_dict(torch.load(weights, map_location="cpu"))
    return m


def read_manifest(data: pathlib.Path):
    rows = list(csv.DictReader(open(data / "manifest.csv", encoding="utf-8")))
    audio = {}
    for r in rows:
        x, _ = sf.read(data / r["path"], dtype="float32")
        audio[r["path"]] = x
    return rows, audio


@torch.no_grad()
def scores(model, rows, audio, cond: str, seed: int = 7, batch: int = 16) -> np.ndarray:
    """P(synthetic) for each clip: centre 4 s, fixed random channel per clip (same for every model)."""
    model.eval()
    out = []
    for i in range(0, len(rows), batch):
        xs = []
        for j, r in enumerate(rows[i: i + batch]):
            rng = np.random.default_rng(seed * 100003 + i + j)
            xs.append(condition(crop(audio[r["path"]], None), cond, rng))
        logits = model(torch.from_numpy(np.stack(xs)).float())[1]
        out.append(F.softmax(logits, dim=1)[:, 0].numpy())          # index 0 = spoof, 1 = bona fide (AASIST)
    return np.concatenate(out) if out else np.zeros(0)


def eer(real: np.ndarray, fake: np.ndarray) -> float:
    if len(real) == 0 or len(fake) == 0:
        return float("nan")
    ts = np.unique(np.concatenate([real, fake]))
    best = 1.0
    for t in ts:
        fpr = np.mean(real >= t); fnr = np.mean(fake < t)
        best = min(best, max(fpr, fnr))
    return float(best)


def threshold_for(real: np.ndarray, fpr: float) -> float:
    return float(np.quantile(real, 1 - fpr)) if len(real) else 0.5


def evaluate(model, rows, audio, threshold: float | None):
    res = {}
    test = [r for r in rows if r["split"] == "test"]
    groups = {}
    for i, r in enumerate(test):
        if r["label"] == "bonafide":
            key = "real"
        elif r["engine"] in ("piper", "espeak"):
            key = f'{r["engine"]} (voices not used in training)'
        else:
            key = f'{r["engine"]} (engine not used in training)'
        groups.setdefault(key, []).append(i)
        groups.setdefault(f"{key} / {r['lang']}", []).append(i)
    for cond in ("clean", "phone"):
        all_s = scores(model, test, audio, cond)
        s = {k: all_s[np.array(v)] for k, v in groups.items()}
        c = {}
        for k, v in s.items():
            if k.startswith("real"):
                c[k] = {"n": len(v), "false_alarm_rate": None if threshold is None else round(float(np.mean(v >= threshold)), 4)}
            else:
                lang = k.split(" / ")[1] if " / " in k else None
                real = s["real / " + lang] if lang else s["real"]
                c[k] = {"n": len(v), "eer": round(eer(real, v), 4),
                        "detected": None if threshold is None else round(float(np.mean(v >= threshold)), 4)}
        res[cond] = c
    return res


def train(model, rows, audio, epochs: int, budget_min: float, lr: float, log):
    tr = [r for r in rows if r["split"] == "train"]
    va = [r for r in rows if r["split"] == "val"]
    y = np.array([0 if r["label"] == "spoof" else 1 for r in tr])
    w = torch.tensor([len(y) / (2 * max(1, (y == 0).sum())), len(y) / (2 * max(1, (y == 1).sum()))], dtype=torch.float32)
    opt = torch.optim.Adam(model.parameters(), lr=lr, weight_decay=1e-4)
    steps = epochs * math.ceil(len(tr) / 16)
    sched = torch.optim.lr_scheduler.CosineAnnealingLR(opt, steps, eta_min=lr / 20)
    rng = np.random.default_rng(42)
    best, best_state, t0 = 9.0, None, time.time()
    for ep in range(epochs):
        model.train()
        order = rng.permutation(len(tr))
        tot = 0.0
        for b in range(0, len(order), 16):
            idx = order[b: b + 16]
            if len(idx) < 2:
                continue
            xs = [condition(crop(audio[tr[i]["path"]], rng), "phone" if rng.random() < 0.6 else "clean", rng) for i in idx]
            x = torch.from_numpy(np.stack(xs)).float()
            loss = F.cross_entropy(model(x, Freq_aug=True)[1], torch.from_numpy(y[idx]).long(), weight=w)
            opt.zero_grad(); loss.backward(); opt.step(); sched.step()
            tot += float(loss) * len(idx)
            if (time.time() - t0) / 60 > budget_min:
                break
        vr = [r for r in va if r["label"] == "bonafide"]; vf = [r for r in va if r["label"] == "spoof"]
        e = np.mean([eer(scores(model, vr, audio, c), scores(model, vf, audio, c)) for c in ("clean", "phone")])
        log(f"epoch {ep + 1}: loss {tot / len(tr):.4f}  val EER {e:.4f}  ({(time.time() - t0) / 60:.1f} min)")
        if e < best:
            best = e
            best_state = {k: v.detach().clone() for k, v in model.state_dict().items()}
        if (time.time() - t0) / 60 > budget_min:
            log("time budget reached")
            break
    model.load_state_dict(best_state)
    return best


def export(model, out: pathlib.Path, log):
    model.eval()
    x = torch.zeros(1, N)

    class Wrapped(torch.nn.Module):          # app-facing output: P(synthetic), one number
        def __init__(self, m):
            super().__init__(); self.m = m
        def forward(self, audio):
            return F.softmax(self.m(audio)[1], dim=1)[:, 0:1]

    path = out / "saqtan_voice_aasist_l.onnx"
    torch.onnx.export(Wrapped(model), x, str(path), input_names=["audio"], output_names=["p_synthetic"], opset_version=17)
    import onnxruntime as ort
    sess = ort.InferenceSession(str(path))
    rng = np.random.default_rng(3)
    diffs = []
    for _ in range(5):
        a = (rng.standard_normal((1, N)) * 0.1).astype(np.float32)
        with torch.no_grad():
            ref = float(Wrapped(model)(torch.from_numpy(a))[0, 0])
        got = float(sess.run(None, {"audio": a})[0][0, 0])
        diffs.append(abs(ref - got))
    log(f"onnx parity max |diff| = {max(diffs):.2e}")
    assert max(diffs) < 1e-3
    return path, hashlib.sha256(path.read_bytes()).hexdigest()


def fixtures(model, rows, audio, out: pathlib.Path, threshold: float):
    """Two short clips for the app's on-screen example and the CI check: one real (FLEURS, CC BY 4.0) and one
    eSpeak NG clip, both as heard through the simulated phone channel, with the model's expected output."""
    res = {}
    pick = {"real": next(r for r in rows if r["split"] == "test" and r["label"] == "bonafide" and r["lang"] == "ru"),
            "synthetic": next(r for r in rows if r["split"] == "test" and r["engine"] == "espeak" and r["lang"] == "ru")}
    for name, r in pick.items():
        x = condition(crop(audio[r["path"]], None), "phone", np.random.default_rng(11))
        pcm = np.clip(np.round(x * 32767), -32768, 32767).astype(np.int16)
        sf.write(out / f"example_{name}.wav", pcm, 16000, subtype="PCM_16")
        x16 = pcm.astype(np.float32) / 32768.0                 # exactly what the app feeds the model
        with torch.no_grad():
            p = float(F.softmax(model(torch.from_numpy(x16[None]))[1], 1)[0, 0])
        res[name] = {"source": r["path"], "p_synthetic": round(p, 4), "flagged": p >= threshold}
    return res


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--aasist", required=True)
    ap.add_argument("--data", default="work/data")
    ap.add_argument("--out", default="work/out")
    ap.add_argument("--epochs", type=int, default=8)
    ap.add_argument("--budget-min", type=float, default=170)
    ap.add_argument("--lr", type=float, default=1e-4)
    a = ap.parse_args()
    torch.manual_seed(0)
    torch.set_num_threads(max(1, torch.get_num_threads()))
    out = pathlib.Path(a.out); out.mkdir(parents=True, exist_ok=True)
    logf = open(out / "train.log", "w")

    def log(s):
        print(s, flush=True); logf.write(s + "\n"); logf.flush()

    aasist = pathlib.Path(a.aasist)
    rows, audio = read_manifest(pathlib.Path(a.data))
    log(f"clips: {len(rows)}")

    metrics = {"model": "AASIST-L (clovaai/aasist, MIT), fine-tuned", "input": "16 kHz mono, 64600 samples (4.04 s), float = int16 / 32768",
               "output": "P(synthetic) 0..1", "target_false_alarm_rate_on_validation": TARGET_FPR}

    base = load_model(aasist, aasist / "models" / "weights" / "AASIST-L.pth")
    vr = [r for r in rows if r["split"] == "val" and r["label"] == "bonafide"]
    t_base = threshold_for(scores(base, vr, audio, "phone"), TARGET_FPR)
    metrics["baseline_original_weights"] = {"threshold": round(t_base, 4), "test": evaluate(base, rows, audio, t_base)}
    log("baseline: " + json.dumps(metrics["baseline_original_weights"], ensure_ascii=False))

    model = base
    t0 = time.time()
    metrics["best_val_eer"] = round(float(train(model, rows, audio, a.epochs, a.budget_min, a.lr, log)), 4)
    metrics["train_minutes"] = round((time.time() - t0) / 60, 1)
    thr = threshold_for(scores(model, vr, audio, "phone"), TARGET_FPR)
    metrics["threshold"] = round(thr, 4)
    metrics["test"] = evaluate(model, rows, audio, thr)
    log("fine-tuned: " + json.dumps(metrics["test"], ensure_ascii=False))
    torch.save(model.state_dict(), out / "aasist_l_saqtan.pth")
    path, sha = export(model, out, log)
    metrics["onnx"] = {"file": path.name, "sha256": sha, "bytes": path.stat().st_size}
    metrics["examples"] = fixtures(model, rows, audio, out, thr)
    metrics["counts"] = json.load(open(pathlib.Path(a.data) / "counts.json"))
    json.dump(metrics, open(out / "metrics.json", "w"), ensure_ascii=False, indent=1)
    log(json.dumps(metrics, ensure_ascii=False, indent=1))


if __name__ == "__main__":
    main()
