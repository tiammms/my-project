"""Trains the small real-vs-synthetic speech model (model.py), evaluates it honestly and exports ONNX for the app.

  python train_eval.py --data work/data --out work/out

Honest evaluation (everything goes to metrics.json):
  * the decision threshold is chosen on the validation split (target: about 2 % false alarms on real speech
    heard through the simulated phone channel) and then applied unchanged to the test set;
  * the test set has real speech of OTHER people (FLEURS test recordings) and synthetic speech of
      - Piper voices never used in training,
      - eSpeak variants never used in training,
      - MMS-TTS (an engine used in training, but other sentences),
      - Silero TTS: an engine never used in training at all;
  * each is measured on clean audio and through the simulated phone channel (codec + loudspeaker + microphone).
"""
from __future__ import annotations

import argparse
import csv
import hashlib
import json
import pathlib
import time

import numpy as np
import soundfile as sf
import torch
import torch.nn.functional as F

from channel import N, condition, crop
from model import Exported, SpoofNet

TARGET_FPR = 0.02
TRAIN_ENGINES = {"human", "piper", "espeak", "mms"}


def read_manifest(data: pathlib.Path):
    rows = list(csv.DictReader(open(data / "manifest.csv", encoding="utf-8")))
    audio = {r["path"]: sf.read(data / r["path"], dtype="float32")[0] for r in rows}
    return rows, audio


@torch.no_grad()
def scores(model, rows, audio, cond: str, seed: int = 7, batch: int = 64) -> np.ndarray:
    """P(synthetic) per clip: centre 4 s, a fixed random channel per clip (identical for every model)."""
    model.eval()
    out = []
    for i in range(0, len(rows), batch):
        xs = [condition(crop(audio[r["path"]], None), cond, np.random.default_rng(seed * 100003 + i + j))
              for j, r in enumerate(rows[i: i + batch])]
        out.append(F.softmax(model(torch.from_numpy(np.stack(xs)).float()), dim=1)[:, 0].numpy())
    return np.concatenate(out) if out else np.zeros(0)


def eer(real: np.ndarray, fake: np.ndarray) -> float:
    if len(real) == 0 or len(fake) == 0:
        return float("nan")
    best = 1.0
    for t in np.unique(np.concatenate([real, fake])):
        best = min(best, max(np.mean(real >= t), np.mean(fake < t)))
    return float(best)


def group(r) -> str:
    if r["label"] == "bonafide":
        return "real speech (other people)"
    return {"piper": "Piper, voices not used in training", "espeak": "eSpeak, variants not used in training",
            "mms": "MMS-TTS, engine used in training, other sentences",
            "silero": "Silero, engine NOT used in training"}[r["engine"]]


def evaluate(model, rows, audio, thr: float):
    test = [r for r in rows if r["split"] == "test"]
    res = {}
    for cond in ("clean", "phone"):
        s = scores(model, test, audio, cond)
        groups = {}
        for i, r in enumerate(test):
            groups.setdefault(group(r), []).append(i)
            groups.setdefault(f"{group(r)} / {r['lang']}", []).append(i)
        c = {}
        for k, idx in sorted(groups.items()):
            v = s[np.array(idx)]
            if k.startswith("real"):
                c[k] = {"n": len(v), "false_alarm_rate": round(float(np.mean(v >= thr)), 4)}
            else:
                lang = k.split(" / ")[1] if " / " in k else None
                real = s[np.array(groups["real speech (other people)" + (f" / {lang}" if lang else "")])]
                c[k] = {"n": len(v), "detected": round(float(np.mean(v >= thr)), 4), "eer": round(eer(real, v), 4)}
        res[cond] = c
    return res


class Clips(torch.utils.data.Dataset):
    def __init__(self, rows, audio, crops: int):
        self.rows, self.audio, self.crops = rows, audio, crops

    def __len__(self):
        return len(self.rows) * self.crops

    def __getitem__(self, i):
        r = self.rows[i % len(self.rows)]
        rng = np.random.default_rng()
        x = condition(crop(self.audio[r["path"]], rng), "phone" if rng.random() < 0.6 else "clean", rng)
        return torch.from_numpy(x).float(), 0 if r["label"] == "spoof" else 1


def train(model, rows, audio, epochs, budget_min, log):
    tr = [r for r in rows if r["split"] == "train" and r["engine"] in TRAIN_ENGINES]
    va = [r for r in rows if r["split"] == "val"]
    y = np.array([0 if r["label"] == "spoof" else 1 for r in tr])
    w = torch.tensor([len(y) / (2 * (y == 0).sum()), len(y) / (2 * (y == 1).sum())], dtype=torch.float32)
    dl = torch.utils.data.DataLoader(Clips(tr, audio, 2), batch_size=32, shuffle=True, num_workers=3,
                                     drop_last=True, persistent_workers=True)
    opt = torch.optim.AdamW(model.parameters(), lr=2e-3, weight_decay=1e-3)
    sched = torch.optim.lr_scheduler.OneCycleLR(opt, max_lr=2e-3, total_steps=epochs * len(dl))
    vr = [r for r in va if r["label"] == "bonafide"]; vf = [r for r in va if r["label"] == "spoof"]
    best, best_state, t0 = 9.0, None, time.time()
    for ep in range(epochs):
        model.train()
        tot = n = 0
        for x, t in dl:
            loss = F.cross_entropy(model(x, specaug=True), t, weight=w)
            opt.zero_grad(); loss.backward(); opt.step(); sched.step()
            tot += float(loss) * len(t); n += len(t)
        e = float(np.mean([eer(scores(model, vr, audio, c), scores(model, vf, audio, c)) for c in ("clean", "phone")]))
        log(f"epoch {ep + 1}: loss {tot / n:.4f}  val EER {e:.4f}  ({(time.time() - t0) / 60:.1f} min)")
        if e < best:
            best, best_state = e, {k: v.detach().clone() for k, v in model.state_dict().items()}
        if (time.time() - t0) / 60 > budget_min:
            log("time budget reached")
            break
    model.load_state_dict(best_state)
    return best


def export(model, out: pathlib.Path, log):
    model.eval()
    path = out / "saqtan_voice.onnx"
    torch.onnx.export(Exported(model), torch.zeros(1, N), str(path), input_names=["audio"],
                      output_names=["p_synthetic"], opset_version=17)
    import onnxruntime as ort
    sess = ort.InferenceSession(str(path))
    rng = np.random.default_rng(3)
    diff = 0.0
    for _ in range(5):
        a = (rng.standard_normal((1, N)) * 0.1).astype(np.float32)
        with torch.no_grad():
            ref = float(Exported(model)(torch.from_numpy(a))[0, 0])
        diff = max(diff, abs(ref - float(sess.run(None, {"audio": a})[0][0, 0])))
    log(f"onnx parity max |diff| = {diff:.2e}")
    assert diff < 1e-3
    return path


def examples(model, rows, audio, out: pathlib.Path, thr: float):
    """Two clips for the app's demo screen: the first Russian real test recording (FLEURS, CC BY 4.0) and the first
    Russian eSpeak test clip, both through the simulated phone channel, with the model's output. Not cherry-picked."""
    res = {}
    pick = {"real": next(r for r in rows if r["split"] == "test" and r["label"] == "bonafide" and r["lang"] == "ru"),
            "synthetic": next(r for r in rows if r["split"] == "test" and r["engine"] == "espeak" and r["lang"] == "ru")}
    for name, r in pick.items():
        x = condition(crop(audio[r["path"]], None), "phone", np.random.default_rng(11))
        pcm = np.clip(np.round(x * 32767), -32768, 32767).astype(np.int16)
        sf.write(out / f"example_{name}.wav", pcm, 16000, subtype="PCM_16")
        x16 = pcm.astype(np.float32) / 32768.0                    # exactly what the app feeds the model
        with torch.no_grad():
            p = float(Exported(model.eval())(torch.from_numpy(x16[None]))[0, 0])
        res[name] = {"source": r["path"], "p_synthetic": round(p, 4), "flagged": p >= thr}
    return res


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", default="work/data")
    ap.add_argument("--out", default="work/out")
    ap.add_argument("--epochs", type=int, default=30)
    ap.add_argument("--budget-min", type=float, default=100)
    a = ap.parse_args()
    torch.manual_seed(0); np.random.seed(0)
    out = pathlib.Path(a.out); out.mkdir(parents=True, exist_ok=True)
    logf = open(out / "train.log", "w")

    def log(s):
        print(s, flush=True); logf.write(s + "\n"); logf.flush()

    rows, audio = read_manifest(pathlib.Path(a.data))
    log(f"clips: {len(rows)}")
    model = SpoofNet()
    log(f"parameters: {sum(p.numel() for p in model.parameters())}")
    t0 = time.time()
    best = train(model, rows, audio, a.epochs, a.budget_min, log)
    print(f"::notice title=train progress::trained, best val EER {best:.4f}, {(time.time() - t0) / 60:.0f} min", flush=True)
    vr = [r for r in rows if r["split"] == "val" and r["label"] == "bonafide"]
    thr = float(np.quantile(scores(model, vr, audio, "phone"), 1 - TARGET_FPR))
    metrics = {
        "model": "SpoofNet (ml/voice_spoof/model.py): log-STFT + 4 conv blocks, trained from scratch",
        "parameters": sum(p.numel() for p in model.parameters()),
        "input": "16 kHz mono, 64600 samples (4.04 s), float = int16 / 32768", "output": "P(synthetic) 0..1",
        "train_engines": sorted(TRAIN_ENGINES - {"human"}), "best_val_eer": round(best, 4),
        "train_minutes": round((time.time() - t0) / 60, 1),
        "threshold": round(thr, 4), "threshold_rule": f"{TARGET_FPR:.0%} false alarms on validation real speech, phone channel",
        "test": evaluate(model, rows, audio, thr),
    }
    torch.save(model.state_dict(), out / "saqtan_voice.pth")
    path = export(model, out, log)
    metrics["onnx"] = {"file": path.name, "sha256": hashlib.sha256(path.read_bytes()).hexdigest(), "bytes": path.stat().st_size}
    metrics["examples"] = examples(model, rows, audio, out, thr)
    metrics["counts"] = json.load(open(pathlib.Path(a.data) / "counts.json"))
    json.dump(metrics, open(out / "metrics.json", "w"), ensure_ascii=False, indent=1)
    log(json.dumps(metrics, ensure_ascii=False, indent=1))


if __name__ == "__main__":
    main()
