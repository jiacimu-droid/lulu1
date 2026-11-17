"""Private, single-GPU Seed-VC song conversion service for Lulu1.

POST /v1/singing/convert
  Authorization: Bearer <SVC_API_TOKEN>
  source_vocals: audio/mpeg file (the isolated original singer)
  reference_voice: audio/mpeg file (a voice sample generated from the role's
                   existing ElevenLabs Voice ID by the phone)
  pitch_shift: 0 (keep original F0 contour, no automatic octave jump)
Response: 44.1 kHz stereo MP3 vocals (no accompaniment).

Requires an independently installed upstream Plachtaa/seed-vc and ffmpeg.
This server is not hosted by this repository; deploy behind HTTPS.
"""
from __future__ import annotations

import hmac
import os
import shutil
import subprocess
import sys
import tempfile
import threading
from pathlib import Path

from fastapi import FastAPI, File, Form, Header, HTTPException, UploadFile
from fastapi.responses import FileResponse
from starlette.background import BackgroundTask
from starlette.concurrency import run_in_threadpool

app = FastAPI(title="Lulu1 Private Singing Voice Conversion", docs_url=None, redoc_url=None)
_LOCK = threading.BoundedSemaphore(1)
_MAX_BYTES = 26 * 1024 * 1024
_VC_ROOT = Path(os.environ.get("SEED_VC_ROOT", "/opt/seed-vc")).resolve()


def _require_auth(authorization: str | None) -> None:
    secret = os.environ.get("SVC_API_TOKEN", "")
    if not secret:
        raise HTTPException(503, "SVC_API_TOKEN is not configured")
    expected = "Bearer " + secret
    if authorization is None or not hmac.compare_digest(authorization, expected):
        raise HTTPException(401, "Invalid singing-service token")


def _save_mpeg(source: UploadFile, output: Path) -> None:
    read = 0
    with output.open("wb") as target:
        while True:
            piece = source.file.read(1024 * 1024)
            if not piece:
                break
            read += len(piece)
            if read > _MAX_BYTES:
                raise HTTPException(413, "Audio upload too large")
            target.write(piece)
    if read <= 1024:
        raise HTTPException(422, "Audio must contain nonempty MP3 data")
    with output.open("rb") as f:
        start = f.read(3)
    if not (start == b"ID3" or
            len(start) >= 2 and start[0] == 0xff and start[1] & 0xe0 == 0xe0):
        raise HTTPException(422, "Input is not an MP3 file")


def _duration_seconds(source: Path) -> float:
    check = subprocess.run(
        ["ffprobe", "-v", "error", "-show_entries", "format=duration",
         "-of", "default=noprint_wrappers=1:nokey=1", str(source)],
        text=True, capture_output=True, timeout=20, check=True,
    )
    return float(check.stdout.strip())


def _run_model(source: Path, reference: Path, folder: Path, pitch_shift: int) -> Path:
    model_script = _VC_ROOT / "inference.py"
    if not model_script.is_file():
        raise HTTPException(503, "Seed-VC is not installed on this GPU server")
    seconds = _duration_seconds(source)
    reference_seconds = _duration_seconds(reference)
    if not 0 < seconds <= 185 or not 0 < reference_seconds <= 31:
        raise HTTPException(422, "Singing input or reference exceeds duration limit")

    output_dir = folder / "model-output"
    output_dir.mkdir()
    # Official upstream CLI: the F0-conditioned singing checkpoint is selected
    # by --f0-condition True. No auto-F0-adjust and zero pitch shift avoid
    # forcing a low reference voice's speaking register onto a high melody.
    cmd = [
        sys.executable, str(model_script),
        "--source", str(source),
        "--target", str(reference),
        "--output", str(output_dir),
        "--diffusion-steps", "35",
        "--length-adjust", "1.0",
        "--inference-cfg-rate", "0.7",
        "--f0-condition", "True",
        "--auto-f0-adjust", "False",
        "--semi-tone-shift", str(pitch_shift),
    ]
    try:
        subprocess.run(cmd, cwd=str(_VC_ROOT), capture_output=True, text=True,
                       check=True, timeout=540)
    except subprocess.TimeoutExpired:
        raise HTTPException(504, "GPU singing inference timed out")
    except subprocess.CalledProcessError:
        # Never echo GPU model logs or uploaded voice content to the phone.
        raise HTTPException(502, "Seed-VC inference failed; inspect private server logs")
    generated = sorted(output_dir.rglob("*.wav"), key=lambda path: path.stat().st_mtime)
    if not generated:
        raise HTTPException(502, "Seed-VC returned no converted WAV vocals")
    result = folder / "converted.mp3"
    subprocess.run([
        "ffmpeg", "-nostdin", "-y", "-v", "error", "-i", str(generated[-1]),
        "-ar", "44100", "-ac", "2", "-codec:a", "libmp3lame",
        "-b:a", "128k", str(result),
    ], capture_output=True, text=True, check=True, timeout=45)
    if result.stat().st_size <= 1024 or result.stat().st_size > _MAX_BYTES:
        raise HTTPException(502, "Invalid converted MP3 output")
    return result


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ready" if (_VC_ROOT / "inference.py").is_file() else "model_missing"}


@app.post("/v1/singing/convert")
async def convert(
    source_vocals: UploadFile = File(...),
    reference_voice: UploadFile = File(...),
    pitch_shift: int = Form(default=0),
    authorization: str | None = Header(default=None),
):
    _require_auth(authorization)
    if pitch_shift != 0:
        # Keep the original melody intact; custom transposition requires
        # shifting the instrumental too, which Lulu1 deliberately doesn't do.
        raise HTTPException(422, "pitch_shift must be zero for melody-preserving mode")
    if not _LOCK.acquire(blocking=False):
        raise HTTPException(429, "GPU is already converting another song")
    folder = Path(tempfile.mkdtemp(prefix="lulu-seedvc-"))
    try:
        source = folder / "source.mp3"
        reference = folder / "reference.mp3"
        await run_in_threadpool(_save_mpeg, source_vocals, source)
        await run_in_threadpool(_save_mpeg, reference_voice, reference)
        rendered = await run_in_threadpool(_run_model, source, reference, folder, pitch_shift)
        # FileResponse reads asynchronously after this handler returns; cleanup
        # and releasing GPU lock must therefore happen after response delivery.
        def cleanup() -> None:
            shutil.rmtree(folder, ignore_errors=True)
            _LOCK.release()
        return FileResponse(rendered, media_type="audio/mpeg",
                            filename="converted-vocals.mp3",
                            background=BackgroundTask(cleanup))
    except BaseException:
        shutil.rmtree(folder, ignore_errors=True)
        _LOCK.release()
        raise
    finally:
        await source_vocals.close()
        await reference_voice.close()
