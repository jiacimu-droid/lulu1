#!/data/data/com.termux/files/usr/bin/bash
set -eu
ROOT="$HOME/.lulu/xhs-termux"
PIDFILE="$ROOT/server.pid"
if [ ! -f "$PIDFILE" ]; then echo "没有记录正在运行的 MCP 进程";exit 0;fi
PID="$(cat "$PIDFILE")"
if [ -n "$PID" ] && kill -0 "$PID" 2>/dev/null; then
  kill "$PID"
  echo "已停止本机小红书只读 MCP 服务"
fi
rm -f "$PIDFILE"
