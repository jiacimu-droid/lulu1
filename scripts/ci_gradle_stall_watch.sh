#!/usr/bin/env bash
# Capture evidence when Gradle's full Android/JVM build stops producing output.
# This watcher never interrupts tests, changes their timeout or reads environment secrets.
set -euo pipefail

build_log="${1:?usage: ci_gradle_stall_watch.sh BUILD_LOG DIAGNOSTIC_LOG}"
diagnostic_log="${2:?usage: ci_gradle_stall_watch.sh BUILD_LOG DIAGNOSTIC_LOG}"
interval="${CI_STALL_POLL_SECONDS:-30}"
threshold="${CI_STALL_SILENCE_SECONDS:-180}"
repeat="${CI_STALL_REPEAT_SECONDS:-600}"
for value in "$interval" "$threshold" "$repeat"; do
  if ! [[ "$value" =~ ^[1-9][0-9]*$ ]]; then
    echo "CI stall watch intervals must be positive integer seconds" >&2
    exit 2
  fi
done
mkdir -p "$(dirname "$diagnostic_log")"
last_snapshot=0

while true; do
  sleep "$interval"
  [[ -f "$build_log" ]] || continue
  modified_at="$(stat -c '%Y' "$build_log" 2>/dev/null)" || continue
  now="$(date +%s)"
  quiet_for=$((now - modified_at))
  (( quiet_for >= threshold )) || continue
  (( last_snapshot == 0 || now - last_snapshot >= repeat )) || continue
  last_snapshot=$now

  {
    echo "===== Gradle output silent for ${quiet_for}s at $(date -u '+%Y-%m-%dT%H:%M:%SZ') ====="
    echo "Process snapshot (PID, PPID, RSS KiB, elapsed, state, executable; no command arguments):"
    ps -eo pid=,ppid=,rss=,etime=,stat=,comm= || true
    if command -v jcmd >/dev/null 2>&1 && command -v timeout >/dev/null 2>&1 &&
       [[ "${CI_STALL_SKIP_JCMD:-0}" != "1" ]]; then
      count=0
      while read -r pid; do
        [[ -n "$pid" ]] || continue
        count=$((count + 1))
        (( count <= 6 )) || break
        echo "----- JVM thread snapshot PID $pid -----"
        # Thread.print has no heap/local values or JVM command-line credentials.
        # A blocked JVM attachment must not block the CI observer itself.
        timeout 12s jcmd "$pid" Thread.print -l 2>&1 ||
          echo "Thread snapshot unavailable for PID $pid (process exited, blocked, or access denied)"
      done < <(ps -eo pid=,comm= | awk '$2 == "java" { print $1 }')
    else
      echo "JVM thread snapshot unavailable (jcmd/timeout missing or disabled for watcher self-test)"
    fi
    echo "===== End snapshot ====="
  } >> "$diagnostic_log" 2>&1
  echo "::warning::Gradle produced no output for ${quiet_for}s; process/JVM evidence saved to ci-logs/gradle-stall-diagnostics.log"
done
