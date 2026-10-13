#!/data/data/com.termux/files/usr/bin/bash
set -eu
ROOT="$HOME/.lulu/xhs-termux"
cd "$ROOT"
PIDFILE="$ROOT/server.pid"
if [ -f "$PIDFILE" ]; then
  OLD="$(cat "$PIDFILE" || true)"
  if [ -n "$OLD" ] && kill -0 "$OLD" 2>/dev/null; then
    echo "小红书只读 MCP 已经运行，进程：$OLD"
    curl --fail --silent http://127.0.0.1:18060/health || true
    echo
    exit 0
  fi
fi
if command -v chromium-browser >/dev/null 2>&1; then
  export PUPPETEER_EXECUTABLE_PATH="$(command -v chromium-browser)"
elif command -v chromium >/dev/null 2>&1; then
  export PUPPETEER_EXECUTABLE_PATH="$(command -v chromium)"
else
  echo "缺少 Chromium，请先 pkg install chromium"; exit 1
fi
export XHS_HEADLESS=true
export XHS_COOKIES_PATH="$HOME/.lulu/xhs-termux/cookies.json"
export LULU_XHS_PORT=18060
# Service binds exclusively to 127.0.0.1; never opens a public port.
nohup node "$ROOT/server.mjs" >>"$ROOT/server.log" 2>&1 </dev/null &
PID=$!
echo "$PID" > "$PIDFILE"
sleep 3
if ! kill -0 "$PID" 2>/dev/null; then
  echo "本地 MCP 未能启动，最后 50 行诊断："
  tail -n 50 "$ROOT/server.log"
  exit 1
fi
if curl --fail --silent --show-error --max-time 5 http://127.0.0.1:18060/health; then
  echo
  echo "✅ 小红书只读 MCP 已在手机上启动：http://127.0.0.1:18060/mcp"
else
  echo "服务进程已启动，但健康检查没通过，请查看：tail -n 40 ~/.lulu/xhs-termux/server.log"
  exit 1
fi
