#!/bin/sh
# Fail closed: upstream Go MCP contains write tools and must never be
# exposed to the internet without authentication.
set -eu
if [ -z "${AUTH_TOKEN:-}" ] || [ "${#AUTH_TOKEN}" -lt 32 ]; then
  echo "Refusing public MCP: AUTH_TOKEN must be a random secret of >=32 chars" >&2
  exit 78
fi
export COOKIES_PATH="${COOKIES_PATH:-/app/data/cookies.json}"
export HOME="${HOME:-/app/data/home}"
export XDG_CONFIG_HOME="${XDG_CONFIG_HOME:-/app/data/config}"
# XDG_CACHE_HOME is deliberately left as upstream /app/cache; its bundled
# Chromium would be hidden if we mounted a fresh persistent cache volume.
mkdir -p /app/data/home /app/data/config
cd /
exec /app/app -port :18060
