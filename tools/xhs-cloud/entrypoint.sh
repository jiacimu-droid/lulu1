#!/bin/sh
set -eu
mkdir -p /data /data/.cache
# Railway/Docker volumes may initially be owned by root. Prepare it then
# drop privileges before launching Chromium/Node; no privileged browser.
chown -R node:node /data
export HOME=/data
exec gosu node node /app/server.mjs
