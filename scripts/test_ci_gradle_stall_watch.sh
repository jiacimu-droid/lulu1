#!/usr/bin/env bash
# Fast regression: active Gradle output never triggers a snapshot, sustained
# silence does; the watcher emits exactly one bounded diagnostic on first stall.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
scratch="$(mktemp -d)"
observer=""
cleanup() {
  if [[ -n "$observer" ]]; then
    kill "$observer" 2>/dev/null || true
    wait "$observer" 2>/dev/null || true
  fi
  rm -rf "$scratch"
}
trap cleanup EXIT

build_log="$scratch/gradle-build.log"
diagnostic_log="$scratch/gradle-stall-diagnostics.log"
printf 'Gradle task started\n' > "$build_log"
CI_STALL_POLL_SECONDS=1 CI_STALL_SILENCE_SECONDS=3 \
CI_STALL_REPEAT_SECONDS=30 CI_STALL_SKIP_JCMD=1 \
  bash "$root/scripts/ci_gradle_stall_watch.sh" \
  "$build_log" "$diagnostic_log" > "$scratch/warnings.log" 2>&1 &
observer=$!

# Repeated output should not trigger a false stall warning.
for tick in 1 2 3 4; do
  sleep 0.5
  echo "Gradle is still running $tick" >> "$build_log"
done
if [[ -e "$diagnostic_log" ]]; then
  echo "Watcher falsely reported a stall while Gradle emitted output" >&2
  exit 1
fi

# Silence longer than the configured threshold must capture a process snapshot.
sleep 5
if [[ ! -s "$diagnostic_log" ]] ||
   ! grep -q "Gradle output silent for" "$diagnostic_log" ||
   ! grep -q "Process snapshot" "$diagnostic_log" ||
   ! grep -q "End snapshot" "$diagnostic_log"; then
  echo "Watcher failed to capture a silent Gradle run" >&2
  exit 1
fi
if [[ "$(grep -c '^===== Gradle output silent for' "$diagnostic_log")" != "1" ]]; then
  echo "Watcher repeated a diagnostic before its cooldown" >&2
  exit 1
fi
if ! grep -q '^::warning::Gradle produced no output' "$scratch/warnings.log"; then
  echo "Watcher did not surface a CI warning" >&2
  exit 1
fi
echo "CI Gradle stall watcher regression passed"
