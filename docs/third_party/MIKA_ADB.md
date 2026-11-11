# Mika wireless debugging transport (Android)

Upstream: https://github.com/wangzan101/Mika
Upstream paths: `core/src/main/kotlin/com/wangzan101/mika/core/adb/*.kt`, `app/src/main/java/com/wangzan101/mika/cap/phone/shell/adb/{AndroidAdb,NsdAdbServiceFinder}.kt`.
License: Apache License, Version 2.0. See `LICENSE-MIKA-APACHE-2.0.txt`. Attribution and original copyright notices in upstream files apply. Lulu1's changes: source files moved into the app source set, provenance comment added; their original packages are retained for isolated upstream merges. No Mika agent, prompts, models, personas, or memory are bundled.

The transport **only** supports local-device Android Wireless Debugging ADB pairing and connection. It is not a virtual-display implementation by itself. Security: peer transport accepts only addresses belonging to this device; local ADB key wrapped by Android Keystore, never added to role tools or model prompts. Requires deliberate owner pairing; disable/revoke in Android system settings.
