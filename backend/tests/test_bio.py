"""Biometric & call-risk module tests.

Model-dependent tests run when the real models are installed (python -m scripts.download_models) and a face
photo is given via NS_TEST_FACE_IMAGE (CI downloads both). Logic tests (liveness rules, privacy, encryption,
fusion, call-text analysis, audio decoding) always run and use no fake model outputs in the product code -
mocks exist only inside these tests.
"""
from __future__ import annotations

import base64
import io
import os
import unittest
import wave
from pathlib import Path
from unittest import mock

os.environ["NS_NO_AUTOAPP"] = "1"
os.environ.setdefault("NS_LOG_LEVEL", "WARNING")

import numpy as np

from backend.app.biometrics import face as F, liveness, registry, scam_text
# Reuse the app instance of test_api (route handlers share module-level state, so one app per test process).
from backend.tests.test_api import C  # noqa: E402

FACE_IMG = os.environ.get("NS_TEST_FACE_IMAGE", "")
MODELS = all(registry.file_status(m)["verified"] for m in registry.MODEL_FILES) and registry.has_package("cv2")
SPEECH = os.environ.get("NS_TEST_SPEECH_FILE", "")


def wav_b64(x: np.ndarray, sr: int = 16000) -> str:
    buf = io.BytesIO()
    with wave.open(buf, "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(sr)
        w.writeframes((np.clip(x, -1, 1) * 32767).astype("<i2").tobytes())
    return "data:audio/wav;base64," + base64.b64encode(buf.getvalue()).decode()


def speechlike(seconds=3.0, sr=16000):
    t = np.arange(int(seconds * sr)) / sr
    env = (np.sin(2 * np.pi * 3 * t) > 0).astype(float)          # syllable-like on/off bursts
    x = 0.3 * env * (np.sin(2 * np.pi * 180 * t) + 0.5 * np.sin(2 * np.pi * 360 * t))
    return x + 0.002 * np.random.default_rng(0).standard_normal(len(t))


SCAM = ("Здравствуйте, вас беспокоит служба безопасности банка. С вашей карты пытаются списать деньги. "
        "Срочно продиктуйте код из СМС и никому не говорите об этом звонке.")


class TestCallText(unittest.TestCase):
    def test_scam_script_is_high(self):
        r = scam_text.analyze(SCAM)
        self.assertEqual(r["risk_level"], "HIGH")
        cats = {t["category"] for t in r["tactics"]}
        self.assertTrue({"credentials_request", "authority_impersonation", "urgency_pressure"} <= cats)

    def test_everyday_text_is_low(self):
        for t in ["Привет, купи хлеба и молока, вечером созвонимся", "Оплати, пожалуйста, счёт за интернет до пятницы",
                  "Сәлем, кешке кездесейік", "See you at the cinema at 7"]:
            self.assertEqual(scam_text.analyze(t)["risk_level"], "LOW", t)

    def test_single_tactic_never_high(self):
        r = scam_text.analyze("Срочно! Немедленно! Прямо сейчас! Последний шанс!")
        self.assertLessEqual(r["risk_score"], 45)
        self.assertNotEqual(r["risk_level"], "HIGH")

    def test_kazakh_and_english(self):
        self.assertEqual(scam_text.analyze("Банктің қауіпсіздік қызметі. Кодты айтыңыз, жедел!")["risk_level"], "HIGH")
        self.assertEqual(scam_text.analyze("This is the bank security team, read me the verification code right now")["risk_level"], "HIGH")

    def test_disclaimer_never_labels_person(self):
        r = scam_text.analyze(SCAM)
        self.assertIn("не личность", r["disclaimer"])


class TestVoiceApi(unittest.TestCase):
    def test_wav_quality_and_text(self):
        r = C.post("/api/v1/bio/voice/analyze", json={"audio": wav_b64(speechlike()), "transcript": SCAM, "transcribe": False})
        self.assertEqual(r.status_code, 200, r.content[:400])
        d = r.json()
        self.assertEqual(d["risk_level"], "HIGH")
        q = d["signal_quality"]
        self.assertAlmostEqual(q["duration_s"], 3.0, places=1)
        self.assertGreater(q["speech_ratio"], 0.2)
        self.assertFalse(d["synthetic_voice"]["available"])      # honest: no detector bundled
        self.assertIn("не утверждаем", d["disclaimer"])

    def test_silence_flagged(self):
        d = C.post("/api/v1/bio/voice/analyze", json={"audio": wav_b64(np.zeros(16000)), "transcribe": False}).json()
        self.assertIn("запись почти беззвучная — проверьте микрофон", d["signal_quality"]["issues"])
        self.assertIsNone(d["risk_level"])

    def test_resamples_44k_stereo(self):
        x = speechlike(sr=44100)
        buf = io.BytesIO()
        with wave.open(buf, "wb") as w:
            w.setnchannels(2); w.setsampwidth(2); w.setframerate(44100)
            st = np.stack([x, x], 1)
            w.writeframes((st * 32767).astype("<i2").tobytes())
        d = C.post("/api/v1/bio/voice/analyze", json={"audio": base64.b64encode(buf.getvalue()).decode(), "transcribe": False}).json()
        self.assertAlmostEqual(d["signal_quality"]["duration_s"], 3.0, places=1)

    def test_bad_inputs(self):
        self.assertEqual(C.post("/api/v1/bio/voice/analyze", json={"transcribe": False}).status_code, 422)
        self.assertEqual(C.post("/api/v1/bio/voice/analyze", json={"audio": "!!!notbase64!!!!!!!!!!"}).status_code, 422)
        mp3ish = base64.b64encode(b"ID3" + b"\x00" * 2000).decode()
        r = C.post("/api/v1/bio/voice/analyze", json={"audio": mp3ish})
        self.assertEqual(r.status_code, 422)
        self.assertEqual(C.post("/api/v1/bio/voice/analyze", json={"audio": wav_b64(np.zeros(1000))}).status_code, 422)  # too short

    def test_transcript_not_stored_by_default(self):
        d = C.post("/api/v1/bio/call/text", json={"text": SCAM}).json()
        stored = C.get(f"/api/v1/bio/history/{d['check_id']}").json()
        blob = str(stored)
        self.assertNotIn("продиктуйте код", blob)        # no quotes from the call in storage
        self.assertIn("credentials_request", blob)       # but the result is kept

    @unittest.skipUnless(SPEECH and registry.has_package("faster_whisper"), "needs faster-whisper + NS_TEST_SPEECH_FILE")
    def test_real_speech_to_text(self):
        raw = Path(SPEECH).read_bytes()
        r = C.post("/api/v1/bio/voice/analyze", json={"audio": base64.b64encode(raw).decode(), "language": "en"})
        self.assertEqual(r.status_code, 200, r.content[:800])
        d = r.json()
        self.assertIsNotNone(d.get("transcript"), d.get("speech_to_text_note"))
        self.assertIn("country", d["transcript"]["text"].lower())   # JFK sample: "...ask not what your country..."


def fake_face(yaw: float, score=0.95):
    """A YuNet-shaped row whose landmarks put the nose at the given yaw ratio (test-only helper)."""
    eye_a, eye_b = np.array([100.0, 100.0]), np.array([160.0, 100.0])
    nose = np.array([130.0 + yaw * 60.0, 130.0])
    row = np.array([70, 60, 120, 140, *eye_a, *eye_b, *nose, 110, 160, 150, 160, score], dtype=np.float32)
    return F.Face(row=row, score=score)


class TestLivenessLogic(unittest.TestCase):
    """The decision rules, with the detector mocked to known head poses."""

    def run_challenge(self, pose_for_step, n=4, same_person=True):
        ch = liveness.new_challenge()
        steps = [s["pose"] for s in ch["steps"]]
        yaw = {"center": 0.0, "left": 0.3, "right": -0.3}
        frames, faces = [], []
        for i, p in enumerate(steps):
            for _ in range(n):
                frames.append({"step": i, "image": "x" * 30})
                faces.append(fake_face(yaw[pose_for_step(i, p)]))
        it = iter(faces)
        rng = np.random.default_rng(1)
        base = rng.standard_normal(128)
        emb = lambda img, f: base if same_person else rng.standard_normal(128)  # noqa: E731
        with mock.patch.object(F, "decode_image", lambda d: np.zeros((480, 640, 3), np.uint8)), \
             mock.patch.object(F, "detect", lambda img: [next(it)]), \
             mock.patch.object(F, "embedding", emb), \
             mock.patch.object(F, "quality", lambda img, f: {"usable": True, "issues": []}):
            return liveness.verify(ch["challenge_id"], frames), steps

    def test_follows_challenge_passes(self):
        r, _ = self.run_challenge(lambda i, p: p)
        self.assertEqual(r["verdict"], "passed", r["reasons"])
        self.assertIn("liveness_token", r)

    def test_static_photo_fails(self):
        r, _ = self.run_challenge(lambda i, p: "center")
        self.assertEqual(r["verdict"], "failed")
        self.assertIn("фотография", r["reasons"][0])

    def test_wrong_order_replay_fails(self):
        flip = {"left": "right", "right": "left", "center": "center"}
        r, _ = self.run_challenge(lambda i, p: flip[p])
        self.assertEqual(r["verdict"], "failed")
        self.assertIn("порядке", " ".join(r["reasons"]))

    def test_face_swap_fails(self):
        r, _ = self.run_challenge(lambda i, p: p, same_person=False)
        self.assertEqual(r["verdict"], "failed")

    def test_challenge_is_single_use_and_random(self):
        orders = {tuple(s["pose"] for s in liveness.new_challenge()["steps"]) for _ in range(60)}
        self.assertGreaterEqual(len(orders), 4)
        for o in orders:
            self.assertEqual(set(o) - {"center"}, {"left", "right"})
        r, _ = self.run_challenge(lambda i, p: p)
        with self.assertRaises(F.InputError):
            liveness.verify("does-not-exist-123", [{"step": 0, "image": "x" * 30}])

    def test_yaw_sign(self):
        self.assertGreater(fake_face(0.3).yaw_ratio(), 0.25)
        self.assertLess(fake_face(-0.3).yaw_ratio(), -0.25)
        # landmark order must not matter
        f = fake_face(0.3); r = f.row.copy(); r[4:6], r[6:8] = f.row[6:8].copy(), f.row[4:6].copy()
        self.assertAlmostEqual(F.Face(row=r, score=0.9).yaw_ratio(), f.yaw_ratio(), places=5)


class TestEnrollPrivacy(unittest.TestCase):
    def token(self, emb):
        tok = "test-token-" + os.urandom(6).hex()
        liveness.STORE.tokens[tok] = {"t": __import__("time").time(), "embedding": emb / np.linalg.norm(emb), "quality": {}}
        return tok

    def test_consent_required_encrypted_and_deletable(self):
        consent = C.get("/api/v1/bio/consent").json()
        emb = np.random.default_rng(5).standard_normal(128).astype(np.float32)
        r = C.post("/api/v1/bio/face/enroll", json={"subject_ref": "client_A", "liveness_token": self.token(emb),
                                                   "consent": False, "consent_version": consent["version"]})
        self.assertEqual(r.status_code, 422)            # consent must be literally true
        r = C.post("/api/v1/bio/face/enroll", json={"subject_ref": "client_A", "liveness_token": self.token(emb),
                                                   "consent": True, "consent_version": consent["version"]})
        self.assertEqual(r.status_code, 200, r.content)
        blob = APP_DB().query("SELECT template_enc FROM face_templates WHERE subject_ref='client_A'")[0]["template_enc"]
        self.assertNotIn((emb / np.linalg.norm(emb)).astype(np.float32).tobytes()[:16], blob)   # not stored in clear
        same = C.post("/api/v1/bio/face/verify", json={"subject_ref": "client_A", "liveness_token": self.token(emb + 0.05)}).json()
        self.assertTrue(same["match"]); self.assertEqual(same["risk_level"], "LOW")
        other = C.post("/api/v1/bio/face/verify", json={"subject_ref": "client_A",
                                                       "liveness_token": self.token(np.random.default_rng(9).standard_normal(128))}).json()
        self.assertFalse(other["match"]); self.assertEqual(other["risk_level"], "HIGH")
        self.assertNotIn("embedding", str(C.get(f"/api/v1/bio/history/{same['check_id']}").json()))
        self.assertEqual(C.request("DELETE", "/api/v1/bio/face/subjects/client_A").status_code, 200)
        self.assertEqual(C.post("/api/v1/bio/face/verify", json={"subject_ref": "client_A", "liveness_token": self.token(emb)}).status_code, 404)

    def test_enrollment_can_be_disabled_and_erase_all(self):
        C.put("/api/v1/bio/privacy", json={"allow_enrollment": False})
        consent = C.get("/api/v1/bio/consent").json()
        r = C.post("/api/v1/bio/face/enroll", json={"subject_ref": "client_B", "liveness_token": self.token(np.ones(128)),
                                                   "consent": True, "consent_version": consent["version"]})
        self.assertEqual(r.status_code, 403)
        C.put("/api/v1/bio/privacy", json={"allow_enrollment": True})
        C.post("/api/v1/bio/call/text", json={"text": "test"})
        d = C.post("/api/v1/bio/privacy/erase-all").json()
        self.assertGreaterEqual(d["history_deleted"], 1)
        self.assertEqual(C.get("/api/v1/bio/history").json()["total"], 0)

    def test_history_off_still_allows_combined(self):
        C.put("/api/v1/bio/privacy", json={"store_history": False})
        try:
            v = C.post("/api/v1/bio/call/text", json={"text": SCAM}).json()
            self.assertFalse(v["stored"])
            self.assertEqual(C.get(f"/api/v1/bio/history/{v['check_id']}").status_code, 404)
            c = C.post("/api/v1/bio/check/combined", json={"voice_check_id": v["check_id"]}).json()
            self.assertEqual(c["risk_level"], "HIGH")
        finally:
            C.put("/api/v1/bio/privacy", json={"store_history": True})


def APP_DB():
    from backend.app.api.routes import STATE
    return STATE["db"]


class TestCombined(unittest.TestCase):
    def test_escalation_and_missing(self):
        tx = APP_DB().query("SELECT transaction_id FROM transactions WHERE risk_level='MEDIUM' LIMIT 1")
        v = C.post("/api/v1/bio/call/text", json={"text": "Срочно, это служба безопасности банка, переведите деньги на безопасный счёт"}).json()
        body = {"voice_check_id": v["check_id"]}
        if tx:
            body["transaction_id"] = tx[0]["transaction_id"]
        c = C.post("/api/v1/bio/check/combined", json=body).json()
        self.assertEqual(c["risk_level"], "HIGH")
        self.assertIn("проверка лица", c["missing"])
        self.assertTrue(c["advice"])

    def test_low_inputs_stay_low(self):
        v = C.post("/api/v1/bio/call/text", json={"text": "Привет, во сколько встречаемся завтра у кинотеатра?"}).json()
        tx = APP_DB().query("SELECT transaction_id FROM transactions WHERE risk_level='LOW' LIMIT 1")[0]["transaction_id"]
        c = C.post("/api/v1/bio/check/combined", json={"voice_check_id": v["check_id"], "transaction_id": tx}).json()
        self.assertEqual(c["risk_level"], "LOW")

    def test_validation(self):
        self.assertEqual(C.post("/api/v1/bio/check/combined", json={}).status_code, 422)
        self.assertEqual(C.post("/api/v1/bio/check/combined", json={"voice_check_id": "BC-0000000000"}).status_code, 404)
        self.assertEqual(C.post("/api/v1/bio/check/combined", json={"transaction_id": "x';--"}).status_code, 422)


class TestLimitsAndStatus(unittest.TestCase):
    def test_bio_body_limit_is_separate(self):
        big = {"text": "a" * 300_000}
        self.assertEqual(C.post("/api/v1/risk/score", json=big).status_code, 413)        # 256 KB elsewhere
        self.assertNotEqual(C.post("/api/v1/bio/call/text", json=big).status_code, 413)  # larger limit for media

    def test_status_is_honest(self):
        s = C.get("/api/v1/bio/status").json()
        keys = {m["key"]: m for m in s["models"]}
        self.assertFalse(keys["synthetic_voice"]["installed"])
        self.assertEqual(keys["face_detector"]["verified"], MODELS)
        self.assertIn("text", s["consent"])

    def test_headers_allow_camera_same_origin_only(self):
        h = C.get("/api/v1/bio/status").headers
        self.assertIn("camera=(self)", h["permissions-policy"])

    @unittest.skipIf(MODELS, "models installed")
    def test_face_endpoints_report_missing_models(self):
        img = "data:image/png;base64," + base64.b64encode(b"\x89PNG\r\n\x1a\n" + b"\x00" * 100).decode()
        r = C.post("/api/v1/bio/face/analyze", json={"image": img})
        self.assertIn(r.status_code, (422, 503))
        r = C.post("/api/v1/bio/face/liveness/challenge")
        self.assertEqual(r.status_code, 503)
        self.assertIn("download_models", str(r.json()))


@unittest.skipUnless(MODELS and FACE_IMG and Path(FACE_IMG).exists(), "needs downloaded models and NS_TEST_FACE_IMAGE")
class TestRealFaceModels(unittest.TestCase):
    """Runs the real YuNet + SFace models (CI downloads them)."""

    @classmethod
    def setUpClass(cls):
        import cv2
        cls.cv2 = cv2
        cls.img = cv2.imread(FACE_IMG)
        cls.b64 = "data:image/jpeg;base64," + base64.b64encode(Path(FACE_IMG).read_bytes()).decode()

    def test_detects_face_with_landmarks(self):
        d = C.post("/api/v1/bio/face/analyze", json={"image": self.b64}).json()
        self.assertGreaterEqual(d["faces_found"], 1, d)
        self.assertGreater(d["faces"][0]["score"], 0.8)

    def test_no_face_in_blank_image(self):
        ok, buf = self.cv2.imencode(".jpg", np.full((300, 300, 3), 127, np.uint8))
        d = C.post("/api/v1/bio/face/analyze", json={"image": base64.b64encode(buf.tobytes()).decode()}).json()
        self.assertEqual(d["faces_found"], 0)
        self.assertFalse(d["usable"])

    def test_same_face_matches_itself_and_flipped(self):
        faces = F.detect(self.img)
        e1 = F.embedding(self.img, faces[0])
        flipped = self.img[:, ::-1].copy()
        e2 = F.embedding(flipped, F.detect(flipped)[0])
        self.assertGreater(F.cosine(e1, e2), F.SFACE_COSINE_THRESHOLD)

    def frames_from(self, images, ch):
        frames = []
        for i, _ in enumerate(ch["steps"]):
            for im in images(i):
                ok, buf = self.cv2.imencode(".jpg", im)
                frames.append({"step": i, "image": base64.b64encode(buf.tobytes()).decode()})
        return frames

    def test_photo_attack_fails_liveness(self):
        """Holding up a photo: same picture, slightly moved/tilted, in every step -> no real head turn."""
        cv2, img = self.cv2, self.img
        h, w = img.shape[:2]

        def tilted(k):
            src = np.float32([[0, 0], [w, 0], [w, h], [0, h]])
            d = 0.06 * w * np.sin(k)
            dst = np.float32([[d, 0], [w - d, d * 0.3], [w - d, h - d * 0.3], [d, h]])
            return cv2.warpPerspective(img, cv2.getPerspectiveTransform(src, dst), (w, h))

        ch = C.post("/api/v1/bio/face/liveness/challenge").json()
        frames = self.frames_from(lambda i: [tilted(i + j / 4) for j in range(3)], ch)
        r = C.post("/api/v1/bio/face/liveness/verify", json={"challenge_id": ch["challenge_id"], "frames": frames}).json()
        self.assertNotEqual(r["verdict"], "passed", r)
        self.assertNotIn("liveness_token", r)


class DemoScenarioTests(unittest.TestCase):
    """Presentation mode: made-up input, real analysis, nothing stored. Pins what the demo shows."""

    def test_script_and_call(self):
        s = C.get("/api/v1/bio/demo/scenario").json()
        r = C.post("/api/v1/bio/demo/call", json={"number": s["number"]}).json()
        self.assertEqual(r["risk_level"], "MEDIUM"); self.assertFalse(r["stored"])

    def test_speech_grows_to_high(self):
        from backend.app.biometrics import demo_scenario as D
        first = C.post("/api/v1/bio/demo/text", json={"text": D.caller_text(1)}).json()
        full = C.post("/api/v1/bio/demo/text", json={"text": D.caller_text(len(D.CALL))}).json()
        self.assertNotEqual(first["risk_level"], "HIGH")
        self.assertEqual(full["risk_level"], "HIGH")
        cats = {t["category"] for t in full["tactics"]}
        self.assertIn("credentials_request", cats); self.assertIn("safe_account_transfer", cats)

    def test_sms_phishing_and_normal(self):
        from backend.app.biometrics import demo_scenario as D
        sms = C.post("/api/v1/bio/demo/sms", json={"text": D.SMS}).json()
        self.assertEqual(sms["risk_level"], "HIGH"); self.assertNotIn("4821", str(sms))   # the code itself is never returned
        ph = C.post("/api/v1/bio/demo/text", json={"text": D.PHISHING}).json()
        self.assertEqual(ph["risk_level"], "HIGH"); self.assertIn("phishing_link", {t["category"] for t in ph["tactics"]})
        self.assertEqual(C.post("/api/v1/bio/demo/text", json={"text": D.NORMAL}).json()["risk_level"], "LOW")

    def test_demo_is_not_stored(self):
        before = C.get("/api/v1/bio/history").json()["total"]
        C.post("/api/v1/bio/demo/text", json={"text": "Продиктуйте код из СМС, срочно"})
        self.assertEqual(C.get("/api/v1/bio/history").json()["total"], before)


class LinkCheckTests(unittest.TestCase):
    def test_links(self):
        from backend.app.biometrics import link_check as L
        self.assertGreaterEqual(L.check("https://kaspi-verify.top/login")["score"], 70)
        for u in ("https://kaspi.kz/shop", "https://www.halykbank.kz", "https://egov.kz/cms/ru", "https://t.me/x"):
            self.assertEqual(L.check(u)["score"], 0, u)
        self.assertGreaterEqual(L.check("https://bit.ly/3xYz")["score"], 35)
        self.assertNotEqual(scam_text.analyze("Скидки тут https://bit.ly/abc")["risk_level"], "HIGH")
        self.assertEqual(scam_text.analyze("Смотри фото https://photos.google.com/share/abc")["risk_level"], "LOW")
