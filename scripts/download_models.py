"""Download the face models (OpenCV YuNet + SFace) and verify their SHA-256.

    python -m scripts.download_models          # downloads missing / corrupted files
    python -m scripts.download_models --check  # only reports status (exit 1 if something is missing)

Files go to ml/biometric_models/ (git-ignored). Speech-to-text weights (faster-whisper) download on first use.
"""
from __future__ import annotations

import sys
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from backend.app.biometrics import registry  # noqa: E402


def fetch(m: registry.ModelFile) -> None:
    dst = registry.MODEL_DIR / m.filename
    tmp = dst.with_suffix(".part")
    print(f"  downloading {m.title} ({m.size / 1e6:.1f} MB)...", flush=True)
    req = urllib.request.Request(m.url, headers={"User-Agent": "NeuroShield-model-downloader"})
    with urllib.request.urlopen(req, timeout=120) as r, open(tmp, "wb") as f:
        while True:
            chunk = r.read(1 << 20)
            if not chunk:
                break
            f.write(chunk)
    if tmp.stat().st_size != m.size or registry.sha256_of(tmp) != m.sha256:
        tmp.unlink(missing_ok=True)
        raise RuntimeError(f"checksum mismatch for {m.filename} - file NOT installed")
    tmp.replace(dst)


def main() -> int:
    check_only = "--check" in sys.argv
    registry.MODEL_DIR.mkdir(parents=True, exist_ok=True)
    ok = True
    for m in registry.MODEL_FILES:
        st = registry.file_status(m)
        if st["verified"]:
            print(f"[ok] {m.title}")
            continue
        if check_only:
            print(f"[missing] {m.title}: {st.get('problem')}")
            ok = False
            continue
        try:
            fetch(m)
            registry._verified.clear()
            print(f"[ok] {m.title} - SHA-256 verified")
        except Exception as e:  # network errors, checksum
            ok = False
            print(f"[error] {m.title}: {e}")
    asr = registry.asr_status()
    print(f"[{'ok' if asr['installed'] else 'optional'}] {asr['title']}" + ("" if asr["installed"] else f" - {asr['problem']}"))
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
