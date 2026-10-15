"""REST endpoints of the biometric & call-risk module (/api/v1/bio/...)."""
from __future__ import annotations

import json
import threading
import time
from collections import OrderedDict
from typing import Literal, Optional

from pydantic import BaseModel, ConfigDict, Field

from ..biometrics import audio, face as F, fusion, liveness, registry, scam_text, speech
from ..biometrics.store import CONSENT_TEXT, CONSENT_VERSION
from .framework import HTTPError
from .routes import STATE, api

TAG = ["biometrics"]
REF = r"^[A-Za-z0-9_\-]{1,40}$"


def _bio():
    return STATE["bio"]


# recent results kept in process memory (30 min) so the combined check works even with history disabled
_recent: "OrderedDict[str, tuple[float, dict]]" = OrderedDict()
_rlock = threading.Lock()


def _remember(result: dict):
    with _rlock:
        _recent[result["check_id"]] = (time.time(), result)
        while len(_recent) > 200 or (_recent and time.time() - next(iter(_recent.values()))[0] > 1800):
            _recent.popitem(last=False)


def _lookup(check_id: str) -> dict | None:
    with _rlock:
        v = _recent.get(check_id)
    if v:
        return v[1]
    d = _bio().get_check(check_id)
    return d["result"] if d else None


def _guard(fn, *a, **kw):
    try:
        return fn(*a, **kw)
    except F.InputError as e:
        raise HTTPError(422, str(e))
    except registry.ModelUnavailable as e:
        raise HTTPError(503, f"Модель недоступна: {e.what}", {"how_to_fix": e.how})


def _save(kind: str, result: dict, summary: str, private_keys: tuple = ()) -> dict:
    stored = {k: v for k, v in result.items() if k not in private_keys}
    stored["kind"] = kind
    stored["summary"] = summary
    cid = _bio().record(kind, stored, summary)
    result.update({"check_id": cid, "kind": kind, "summary": summary, "stored": stored.get("stored", False)})
    _remember({**stored, "check_id": cid})
    return result


# ---------------------------------------------------------------- models
class ImageIn(BaseModel):
    model_config = ConfigDict(extra="forbid")
    image: str = Field(..., min_length=20, max_length=9_000_000, description="data URL or base64 (JPEG/PNG/WEBP/BMP, <= 6 MB)")


class FrameIn(BaseModel):
    model_config = ConfigDict(extra="forbid")
    step: int = Field(..., ge=0, le=20)
    image: str = Field(..., min_length=20, max_length=2_000_000)


class LivenessIn(BaseModel):
    model_config = ConfigDict(extra="forbid")
    challenge_id: str = Field(..., min_length=10, max_length=64, pattern=r"^[A-Za-z0-9_\-]+$")
    frames: list[FrameIn] = Field(..., min_length=1, max_length=liveness.MAX_FRAMES)


class EnrollIn(BaseModel):
    model_config = ConfigDict(extra="forbid")
    subject_ref: str = Field(..., pattern=REF, description="Pseudonymous id (no real names)")
    liveness_token: str = Field(..., min_length=10, max_length=64)
    consent: Literal[True] = Field(..., description="Explicit consent to store an encrypted face template")
    consent_version: str = Field(..., max_length=20)


class VerifyIn(BaseModel):
    model_config = ConfigDict(extra="forbid")
    subject_ref: str = Field(..., pattern=REF)
    liveness_token: Optional[str] = Field(None, min_length=10, max_length=64)
    image: Optional[str] = Field(None, min_length=20, max_length=9_000_000)


class VoiceIn(BaseModel):
    model_config = ConfigDict(extra="forbid")
    audio: Optional[str] = Field(None, min_length=20, max_length=21_000_000, description="data URL/base64; WAV always, other formats with requirements-voice.txt")
    transcript: Optional[str] = Field(None, max_length=scam_text.MAX_TEXT, description="Text of the call typed or pasted by the user")
    transcribe: bool = True
    language: Literal["auto", "ru", "kk", "en"] = "auto"


class TextIn(BaseModel):
    model_config = ConfigDict(extra="forbid")
    text: str = Field(..., min_length=1, max_length=scam_text.MAX_TEXT)


class CombinedIn(BaseModel):
    model_config = ConfigDict(extra="forbid")
    face_check_id: Optional[str] = Field(None, pattern=r"^BC-[0-9A-F]{10}$")
    voice_check_id: Optional[str] = Field(None, pattern=r"^BC-[0-9A-F]{10}$")
    transaction_id: Optional[str] = Field(None, max_length=40, pattern=r"^[A-Za-z0-9_\-]+$")


class HistoryQuery(BaseModel):
    model_config = ConfigDict(extra="forbid")
    kind: Optional[Literal["face_photo", "liveness", "face_verify", "voice", "call_text", "combined"]] = None
    limit: int = Field(50, ge=1, le=200)
    offset: int = Field(0, ge=0, le=100000)


class PrivacyIn(BaseModel):
    model_config = ConfigDict(extra="forbid")
    store_history: Optional[bool] = None
    store_transcripts: Optional[bool] = None
    allow_enrollment: Optional[bool] = None
    speech_to_text: Optional[bool] = None
    history_retention_days: Optional[int] = Field(None, ge=1, le=365)


# ---------------------------------------------------------------- status
@api.route("/api/v1/bio/status", summary="Installed models, privacy settings and consent text", tags=TAG)
def bio_status():
    b = _bio()
    return {**registry.status(), "privacy": b.privacy(), "consent": {"version": CONSENT_VERSION, "text": CONSENT_TEXT},
            "enrolled_subjects": len(b.subjects()), "history": b.counts()}


@api.route("/api/v1/bio/summary", summary="Dashboard summary of recent biometric / call checks", tags=TAG)
def bio_summary():
    b = _bio()
    b.purge_expired()
    recent = b.history(None, 10, 0)["items"]
    return {"counts": b.counts(), "recent": recent, "models": registry.status()["models"],
            "enrolled_subjects": len(b.subjects()), "privacy": b.privacy()}


# ---------------------------------------------------------------- face
@api.route("/api/v1/bio/face/analyze", "POST", summary="Detect a face in one photo and check image quality", tags=TAG,
           body=ImageIn, errors=(422, 503))
def face_analyze(body: ImageIn):
    img = _guard(F.decode_image, body.image)
    res = _guard(F.analyze, img)
    summary = (f"Лиц: {res['faces_found']}; " + ("фото пригодно для проверки" if res["usable"] else "; ".join(res["issues"]) or "—"))
    res["risk_level"], res["risk_score"] = None, None
    res["reasons"] = res["issues"]
    return _save("face_photo", res, summary)


@api.route("/api/v1/bio/face/liveness/challenge", "POST", summary="Start a liveness check: random head-turn sequence",
           tags=TAG, errors=(503,))
def liveness_challenge():
    _guard(registry.face_detector)
    return liveness.new_challenge()


@api.route("/api/v1/bio/face/liveness/verify", "POST", summary="Verify the frames recorded for a liveness challenge",
           tags=TAG, body=LivenessIn, errors=(422, 503))
def liveness_verify(body: LivenessIn):
    res = _guard(liveness.verify, body.challenge_id, [f.model_dump() for f in body.frames])
    summary = {"passed": "Живой человек подтверждён", "failed": "Проверка живости НЕ пройдена",
               "inconclusive": "Результат неоднозначен — нужно повторить"}[res["verdict"]]
    return _save("liveness", res, summary, private_keys=("liveness_token", "liveness_token_expires_in_s"))


@api.route("/api/v1/bio/consent", summary="Consent text for storing a face template", tags=TAG)
def consent():
    return {"version": CONSENT_VERSION, "text": CONSENT_TEXT}


@api.route("/api/v1/bio/face/enroll", "POST", summary="Store an encrypted face template (requires consent + passed liveness)",
           tags=TAG, body=EnrollIn, errors=(403, 409, 422, 503))
def face_enroll(body: EnrollIn):
    b = _bio()
    if not b.privacy()["allow_enrollment"]:
        raise HTTPError(403, "Регистрация лиц отключена в настройках конфиденциальности")
    if body.consent_version != CONSENT_VERSION:
        raise HTTPError(409, "Текст согласия обновился — прочитайте его снова", {"current_version": CONSENT_VERSION})
    tok = _guard(liveness.take_token, body.liveness_token)
    return {**b.enroll(body.subject_ref, tok["embedding"], registry.FACE_RECOGNIZER.filename),
            "stored": "зашифрованный шаблон (128 чисел), без фотографии",
            "how_to_delete": "Настройки и конфиденциальность → Зарегистрированные лица → Удалить"}


@api.route("/api/v1/bio/face/verify", "POST", summary="1:1 comparison with an enrolled template", tags=TAG,
           body=VerifyIn, errors=(404, 422, 503))
def face_verify(body: VerifyIn):
    b = _bio()
    tpl = b.template(body.subject_ref)
    if tpl is None:
        raise HTTPError(404, "Для этого идентификатора нет зарегистрированного лица")
    live = False
    if body.liveness_token:
        emb = _guard(liveness.take_token, body.liveness_token)["embedding"]
        live = True
    elif body.image:
        img = _guard(F.decode_image, body.image)
        faces = _guard(F.detect, img)
        if len(faces) != 1:
            raise HTTPError(422, "На фото должно быть ровно одно лицо" if faces else "Лицо не найдено")
        emb = _guard(F.embedding, img, faces[0])
    else:
        raise HTTPError(422, "Нужен liveness_token (после проверки живости) или фото")
    sim = F.cosine(emb, tpl)
    match = sim >= F.SFACE_COSINE_THRESHOLD
    reasons = [f"сходство {sim:.3f} при пороге {F.SFACE_COSINE_THRESHOLD}"]
    if match and live:
        risk = 5.0
        reasons.append("совпадает с зарегистрированным лицом, живость подтверждена")
    elif match:
        risk = 40.0
        reasons.append("совпадает, но живость НЕ подтверждена: фото могло быть предъявлено вместо человека")
    else:
        risk = 80.0
        reasons.append("не совпадает с зарегистрированным лицом")
    res = {"subject_ref": body.subject_ref, "similarity": round(sim, 4), "threshold": F.SFACE_COSINE_THRESHOLD,
           "match": match, "liveness_confirmed": live, "risk_score": risk, "risk_level": fusion.level_of(risk),
           "reasons": reasons, "method": "OpenCV SFace, косинусное сходство; порог из документации OpenCV"}
    return _save("face_verify", res, ("Совпадение" if match else "Несовпадение") + f" с {body.subject_ref} ({sim:.2f})")


@api.route("/api/v1/bio/face/subjects", summary="Enrolled subjects (no templates returned)", tags=TAG)
def subjects():
    return {"items": _bio().subjects()}


@api.route("/api/v1/bio/face/subjects/{subject_ref}", "DELETE", summary="Delete an enrolled face template", tags=TAG, errors=(404,))
def delete_subject(subject_ref: str):
    if not _bio().delete_subject(subject_ref):
        raise HTTPError(404, "Не найдено")
    return {"deleted": subject_ref}


# ---------------------------------------------------------------- voice / call
def _voice_result(call: dict | None, quality: dict | None, transcript: dict | None, asr_note: str | None) -> dict:
    synthetic = {"available": False, "reason": registry.SYNTHETIC_VOICE_STATUS["problem"]}
    res = {"signal_quality": quality, "transcript": transcript, "speech_to_text_note": asr_note,
           "call_content": call, "synthetic_voice": synthetic,
           "disclaimer": "Мы не утверждаем, что голос принадлежит мошеннику, по его звучанию. "
                         "Оценивается содержание разговора и качество записи."}
    if call:
        res["risk_score"], res["risk_level"] = call["risk_score"], call["risk_level"]
        res["reasons"] = [t["title"] for t in call["tactics"]] or ["признаков мошеннического сценария не найдено"]
        res["advice"] = [t["advice"] for t in call["tactics"]]
    else:
        res["risk_score"], res["risk_level"] = None, None
        res["reasons"] = ["содержание разговора не проанализировано (нет текста)"]
        res["advice"] = []
    return res


@api.route("/api/v1/bio/voice/analyze", "POST", summary="Analyse a recording: quality, local speech-to-text, scam-call tactics",
           tags=TAG, body=VoiceIn, errors=(422, 503))
def voice_analyze(body: VoiceIn):
    if not body.audio and not (body.transcript or "").strip():
        raise HTTPError(422, "Нужна аудиозапись или текст разговора")
    priv = _bio().privacy()
    quality = transcript = None
    asr_note = None
    text = (body.transcript or "").strip()
    if body.audio:
        x, how = _guard(audio.decode_audio, body.audio)
        quality = audio.signal_quality(x)
        quality["decoded_with"] = how
        if body.transcribe and priv["speech_to_text"]:
            try:
                transcript = speech.transcribe(x, body.language)
                if not text:
                    text = transcript["text"]
            except registry.ModelUnavailable as e:
                asr_note = f"Распознавание речи недоступно: {e.what}. Установка: {e.how}. Можно вставить текст разговора вручную."
        elif not priv["speech_to_text"]:
            asr_note = "Распознавание речи выключено в настройках конфиденциальности."
    call = scam_text.analyze(text) if text else None
    res = _voice_result(call, quality, transcript, asr_note)
    res["text_source"] = "введён пользователем" if body.transcript else ("распознан из записи" if transcript else None)
    summary = (f"{call['risk_level']}: {call['summary']}" if call else "Только качество записи (текста нет)")
    private = () if priv["store_transcripts"] else ("transcript",)
    if not priv["store_transcripts"] and res.get("call_content"):
        # evidence snippets are quotes from the call: keep them out of storage too
        stored_call = {**res["call_content"], "tactics": [{k: v for k, v in t.items() if k != "evidence"} for t in res["call_content"]["tactics"]]}
        out = _save("voice", {**res, "call_content": stored_call}, summary, private_keys=private)
        out["call_content"] = res["call_content"]
        out["transcript"] = transcript
        return out
    return _save("voice", res, summary, private_keys=private)


@api.route("/api/v1/bio/call/text", "POST", summary="Analyse the text of a call / message for scam tactics", tags=TAG,
           body=TextIn)
def call_text(body: TextIn):
    return voice_analyze(VoiceIn(transcript=body.text, transcribe=False))


# ---------------------------------------------------------------- combined
@api.route("/api/v1/bio/check/combined", "POST", summary="Combine face, voice/call and transaction risk into one decision",
           tags=TAG, body=CombinedIn, errors=(404, 422))
def combined(body: CombinedIn):
    face_r = voice_r = tx_r = None
    if body.face_check_id:
        face_r = _lookup(body.face_check_id)
        if face_r is None:
            raise HTTPError(404, "Проверка лица не найдена (возможно, история отключена и прошло больше 30 минут)")
        if face_r.get("kind") == "face_photo":
            raise HTTPError(422, "Фото без проверки живости нельзя использовать в общей оценке — пройдите проверку живости или сравнение")
    if body.voice_check_id:
        voice_r = _lookup(body.voice_check_id)
        if voice_r is None:
            raise HTTPError(404, "Проверка голоса не найдена")
        if voice_r.get("risk_score") is None:
            voice_r = None
    if body.transaction_id:
        row = STATE["db"].get_transaction(body.transaction_id)
        if row is None:
            raise HTTPError(404, "Перевод не найден")
        tx_r = json.loads(row["result_json"])
        tx_r["transaction_id"] = body.transaction_id
    if not (face_r or voice_r or tx_r):
        raise HTTPError(422, "Выберите хотя бы одну проверку")
    res = fusion.combine(face_r, voice_r, tx_r)
    res["inputs"] = {"face_check_id": body.face_check_id, "voice_check_id": body.voice_check_id,
                     "transaction_id": body.transaction_id}
    return _save("combined", res, f"{res['risk_level']}: {res['recommended_action']}")


# ---------------------------------------------------------------- history & privacy
@api.route("/api/v1/bio/history", summary="History of checks (results only)", tags=TAG, query=HistoryQuery)
def history(query: HistoryQuery):
    _bio().purge_expired()
    return _bio().history(query.kind, query.limit, query.offset)


@api.route("/api/v1/bio/history/{check_id}", summary="One stored check", tags=TAG, errors=(404,))
def history_item(check_id: str):
    d = _bio().get_check(check_id)
    if d is None:
        raise HTTPError(404, "Не найдено")
    return d


@api.route("/api/v1/bio/history", "DELETE", summary="Delete the whole check history", tags=TAG)
def history_clear():
    return {"deleted": _bio().clear_history()}


@api.route("/api/v1/bio/privacy", summary="Privacy settings", tags=TAG)
def privacy_get():
    return _bio().privacy()


@api.route("/api/v1/bio/privacy", "PUT", summary="Change privacy settings (audited)", tags=TAG, body=PrivacyIn)
def privacy_put(body: PrivacyIn):
    ch = {k: v for k, v in body.model_dump().items() if v is not None}
    if not ch:
        raise HTTPError(422, "Нет изменений")
    return _bio().set_privacy(ch)


@api.route("/api/v1/bio/privacy/erase-all", "POST", summary="Delete all biometric templates and history", tags=TAG)
def erase_all():
    with _rlock:
        _recent.clear()
    return _bio().erase_all()
