"""Guard the user-installable phone APK against the known ART VerifyError.

R8 rewrote the unusually large Kotlin coroutine
LuluDeviceToolBridge.respond(...) to u2.x.h(...), which ART rejected with an
Undefined high register. Do not silently ship the same optimized shape again.
This is NOT an emulator smoke test: runtime verification still needs a device.
"""
from __future__ import annotations

import pathlib
import sys
import zipfile


def verify(apk: pathlib.Path) -> None:
    if not apk.is_file():
        raise SystemExit(f"Missing APK: {apk}")
    with zipfile.ZipFile(apk) as archive:
        corrupt = archive.testzip()
        if corrupt is not None:
            raise SystemExit(f"Corrupt APK archive entry: {corrupt}")
        dex_names = [name for name in archive.namelist()
                     if name.startswith("classes") and name.endswith(".dex")
                     and "/" not in name]
        if not dex_names:
            raise SystemExit("Phone APK has no classes*.dex")
        markers = [
            b"Lcom/jiacimu/lulu/system/LuluDeviceToolBridge;",
            b"Lcom/jiacimu/lulu/MigrationActivity;",
            b"Lcom/jiacimu/lulu/StartupFailureRecovery;",
        ]
        blobs = [archive.read(dex) for dex in dex_names]
        missing = [name.decode("ascii") for name in markers
                   if not any(name in blob for blob in blobs)]
        if missing:
            raise SystemExit(
                "Phone APK does not contain original app class descriptors: "
                + ", ".join(missing)
                + ". R8/minify may have been re-enabled; do not publish the APK.")
    print(
        "OK: phone APK keeps the affected reply class unobfuscated. "
        "This guards against the previous R8 VerifyError, but cannot replace "
        "an Android 16 on-device launch test."
    )


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("Usage: python scripts/verify_phone_apk_bytecode.py <phone.apk>")
    verify(pathlib.Path(sys.argv[1]))
