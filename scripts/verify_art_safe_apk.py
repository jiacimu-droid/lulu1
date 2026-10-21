#!/usr/bin/env python3
"""Fail CI if an installable Lulu APK silently switches back to R8 obfuscation.

The Android 16 user's real VerifyError rejects the R8-rewritten
LuluDeviceToolBridge.respond coroutine. This is a build identity smoke test,
not a replacement for launching the APK on the affected Android device.
"""
from __future__ import annotations

import sys
import zipfile
from pathlib import Path


ESSENTIAL_DESCRIPTORS = (
    b"Lcom/jiacimu/lulu/system/LuluDeviceToolBridge;",
    b"Lcom/jiacimu/lulu/MigrationActivity;",
    b"Lcom/jiacimu/lulu/StartupFailureRecovery;",
)


def verify(path: Path) -> None:
    if not path.is_file():
        raise RuntimeError(f"APK not found: {path}")
    with zipfile.ZipFile(path) as apk:
        broken = apk.testzip()
        if broken is not None:
            raise RuntimeError(f"Corrupt APK entry: {broken}")
        dex_names = [
            info.filename
            for info in apk.infolist()
            if info.filename.startswith("classes")
            and info.filename.endswith(".dex")
            and "/" not in info.filename
        ]
        if not dex_names:
            raise RuntimeError("APK contains no classes.dex")
        all_dex = [apk.read(name) for name in dex_names]
        missing = [
            item.decode("ascii")
            for item in ESSENTIAL_DESCRIPTORS
            if not any(item in blob for blob in all_dex)
        ]
        if missing:
            raise RuntimeError(
                "Critical classes have been removed or renamed/obfuscated: "
                + ", ".join(missing)
                + ". Do NOT publish: Android 16 may fail at launch with VerifyError."
            )
    print(
        f"ART-safe packaging checks passed: {len(dex_names)} dex files, "
        "LuluDeviceToolBridge retains its original descriptor. "
        "Still requires an Android on-device launch test."
    )


if __name__ == "__main__":
    try:
        verify(Path(sys.argv[1]))
    except (IndexError, ValueError, OSError, RuntimeError, zipfile.BadZipFile) as exc:
        print(f"ART-safe APK verification FAILED: {exc}", file=sys.stderr)
        sys.exit(1)
