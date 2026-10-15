"""Active liveness check: a random head-turn challenge verified from face landmarks.

How it works
- The server issues a random sequence of poses (e.g. left -> centre -> right -> centre) with a one-time id.
- The browser shows the prompts and sends camera frames tagged with the step they belong to.
- For every frame YuNet finds the face and 5 landmarks; the yaw ratio (nose offset from the eye midpoint,
  in inter-ocular distances) tells where the head points.
- Each step must show the requested pose, in the requested order; the face must stay the same person
  (SFace embeddings) and there must be exactly one face.

What it protects against: a printed or on-screen photo (it cannot turn its head), a pre-recorded video
(it cannot follow a random order it has never seen), swapping faces mid-check.
What it does NOT protect against: a live 3D mask, a real-time deepfake injected as a virtual camera,
a person being coerced in front of the camera. This is stated in every result.
"""
from __future__ import annotations

import secrets
import threading
import time

import numpy as np

from . import face as F
from . import registry

TURN_T = 0.14        # |yaw ratio| that counts as "turned"
CENTER_T = 0.09      # |yaw ratio| that counts as "looking straight"
MIN_SPAN = 0.12      # total yaw range below this = no real head movement
CHALLENGE_TTL = 120
TOKEN_TTL = 300
MAX_FRAMES = 40

STEP_TEXT = {"left": "Медленно поверните голову влево", "right": "Медленно поверните голову вправо",
             "center": "Посмотрите прямо в камеру"}
LIMITATIONS = ("Проверка защищает от фото и заранее записанного видео. Она не защищает от объёмной маски, "
               "дипфейка в реальном времени через виртуальную камеру и от ситуации, когда человека принуждают.")


class _Store:
    def __init__(self):
        self.lock = threading.Lock()
        self.challenges: dict[str, dict] = {}
        self.tokens: dict[str, dict] = {}

    def _gc(self):
        now = time.time()
        for d, ttl in ((self.challenges, CHALLENGE_TTL), (self.tokens, TOKEN_TTL)):
            for k in [k for k, v in d.items() if now - v["t"] > ttl]:
                d.pop(k, None)
            while len(d) > 500:
                d.pop(next(iter(d)))


STORE = _Store()


def new_challenge() -> dict:
    # centre, T1, centre, T2, centre, T3 with T1..T3 random turns that include both directions:
    # 6 possible orders, so a pre-recorded video matches by chance at most 1 time in 6.
    rng = secrets.SystemRandom()
    while True:
        turns = [rng.choice(["left", "right"]) for _ in range(3)]
        if len(set(turns)) == 2:
            break
    clean = ["center", turns[0], "center", turns[1], "center", turns[2]]
    cid = secrets.token_urlsafe(16)
    with STORE.lock:
        STORE._gc()
        STORE.challenges[cid] = {"t": time.time(), "steps": clean}
    return {"challenge_id": cid, "steps": [{"pose": s, "instruction": STEP_TEXT[s]} for s in clean],
            "expires_in_s": CHALLENGE_TTL, "frames_per_step": 4, "step_duration_ms": 1800,
            "note": "Кадры обрабатываются в памяти и не сохраняются."}


def _pose_ok(pose: str, yaws: list[float]) -> bool:
    if not yaws:
        return False
    if pose == "center":
        return min(abs(y) for y in yaws) < CENTER_T
    # positive yaw = person's left in an un-mirrored frame (see Face.yaw_ratio)
    hits = [y for y in yaws if (y > TURN_T if pose == "left" else y < -TURN_T)]
    return len(hits) >= 1


def verify(challenge_id: str, frames: list[dict]) -> dict:
    with STORE.lock:
        STORE._gc()
        ch = STORE.challenges.pop(challenge_id, None)   # one-time use
    if ch is None:
        raise F.InputError("проверка устарела или уже использована — начните заново")
    steps = ch["steps"]
    if not frames:
        raise F.InputError("нет кадров")
    if len(frames) > MAX_FRAMES:
        raise F.InputError(f"слишком много кадров (максимум {MAX_FRAMES})")

    per_step: dict[int, list[dict]] = {i: [] for i in range(len(steps))}
    multi, missing = 0, 0
    trace = []
    for fr in frames:
        i = fr["step"]
        if i < 0 or i >= len(steps):
            raise F.InputError("кадр относится к несуществующему шагу")
        img = F.decode_image(fr["image"])
        faces = F.detect(img)
        if not faces:
            missing += 1
            trace.append({"step": i, "yaw": None, "faces": 0})
            continue
        if len(faces) > 1:
            multi += 1
        f = faces[0]
        y = f.yaw_ratio()
        trace.append({"step": i, "yaw": round(y, 3), "faces": len(faces)})
        per_step[i].append({"img": img, "face": f, "yaw": y})

    evidence = []
    for i, pose in enumerate(steps):
        yaws = [d["yaw"] for d in per_step[i]]
        evidence.append({"step": i, "pose": pose, "instruction": STEP_TEXT[pose], "frames_with_face": len(yaws),
                         "yaw_values": [round(y, 3) for y in yaws], "passed": _pose_ok(pose, yaws)})

    all_yaws = [t["yaw"] for t in trace if t["yaw"] is not None]
    span = (max(all_yaws) - min(all_yaws)) if all_yaws else 0.0
    face_frames = len(all_yaws)

    # identity consistency across steps (needs the recognizer; reported as unavailable otherwise)
    identity = {"checked": False}
    best = None
    try:
        embs = []
        for i in range(len(steps)):
            if per_step[i]:
                d = min(per_step[i], key=lambda d: abs(d["yaw"]))
                embs.append(F.embedding(d["img"], d["face"]))
        if len(embs) >= 2:
            sims = [F.cosine(embs[0], e) for e in embs[1:]]
            identity = {"checked": True, "min_similarity": round(min(sims), 3),
                        "threshold": F.SFACE_COSINE_THRESHOLD, "same_person": min(sims) >= F.SFACE_COSINE_THRESHOLD}
        centre = [d for i, p in enumerate(steps) if p == "center" for d in per_step[i]]
        if centre:
            d = max(centre, key=lambda d: d["face"].score - abs(d["yaw"]))
            best = (F.embedding(d["img"], d["face"]), F.quality(d["img"], d["face"]))
    except registry.ModelUnavailable as e:
        identity = {"checked": False, "reason": str(e)}

    reasons, verdict = [], "passed"
    if face_frames < max(3, len(frames) // 2):
        verdict = "inconclusive"
        reasons.append(f"лицо найдено только на {face_frames} из {len(frames)} кадров — проверьте свет и положение")
    elif span < MIN_SPAN:
        verdict = "failed"
        reasons.append("голова не поворачивалась: так выглядит фотография или неподвижный экран")
    elif not all(e["passed"] for e in evidence):
        failed = [e for e in evidence if not e["passed"]]
        turned_both = any(y > TURN_T for y in all_yaws) and any(y < -TURN_T for y in all_yaws)
        if turned_both:
            verdict = "failed"
            reasons.append("повороты были, но не в запрошенном порядке: так может выглядеть заранее записанное видео")
        else:
            verdict = "inconclusive"
            reasons.append("не выполнены шаги: " + ", ".join(f"«{e['instruction'].lower()}»" for e in failed)
                           + " — поверните голову сильнее и повторите")
    if multi:
        verdict = "failed" if verdict == "passed" else verdict
        reasons.append(f"в {multi} кадрах больше одного лица")
    if identity.get("checked") and not identity.get("same_person"):
        verdict = "failed"
        reasons.append("во время проверки лицо сменилось")
    if verdict == "passed":
        reasons.append("все повороты выполнены в случайном порядке, который нельзя было знать заранее")

    risk = {"passed": 5.0, "inconclusive": 50.0, "failed": 85.0}[verdict]
    out = {"verdict": verdict, "passed": verdict == "passed", "risk_score": risk,
           "risk_level": "LOW" if risk < 30 else "MEDIUM" if risk < 70 else "HIGH",
           "reasons": reasons, "steps": evidence, "yaw_trace": trace, "yaw_span": round(span, 3),
           "identity_consistency": identity, "frames_received": len(frames), "frames_with_face": face_frames,
           "method": "Активная проверка: случайная последовательность поворотов головы, YuNet (точки лица) + SFace (тот же человек)",
           "limitations": LIMITATIONS}
    if verdict == "passed" and best is not None:
        tok = secrets.token_urlsafe(18)
        with STORE.lock:
            STORE.tokens[tok] = {"t": time.time(), "embedding": best[0], "quality": best[1]}
        out["liveness_token"] = tok
        out["liveness_token_expires_in_s"] = TOKEN_TTL
    return out


def take_token(token: str, consume: bool = True) -> dict:
    with STORE.lock:
        STORE._gc()
        d = STORE.tokens.pop(token, None) if consume else STORE.tokens.get(token)
    if d is None:
        raise F.InputError("подтверждение живости устарело — пройдите проверку ещё раз")
    return d
