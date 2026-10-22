#!/usr/bin/env bash
# Use INSIDE Ubuntu 24.04 arm64/proot, not Android's ordinary shell.
# Based on the user's v2.5.0 Go source + Xvfb + stable cookie path workflow.
set -euo pipefail
ACTION="${1:-status}"
ROOT="${LULU_XHS_ROOT:-$HOME/.local/share/lulu-xhs}"
APP="$ROOT/bin/xhs-mcp"
LOGDIR="$ROOT/log"
COOKIE="$ROOT/data/cookies.json"
AUTH_FILE="$ROOT/data/auth-token"
PID_FILE="$ROOT/run/xhs.pid"
XVFB_PID_FILE="$ROOT/run/xvfb.pid"
PORT="${LULU_XHS_PORT:-18061}"
DISPLAY_NO="${LULU_XHS_DISPLAY:-:99}"
SRC_TAG="${LULU_XHS_TAG:-v2.5.0}"
mkdir -p "$ROOT/bin" "$ROOT/data" "$ROOT/run" "$LOGDIR"
chmod 700 "$ROOT/data"
alive() { [[ -f "$1" ]] && kill -0 "$(cat "$1")" 2>/dev/null; }
need() { command -v "$1" >/dev/null || { echo "缺少 $1。请在 proot Ubuntu 内安装后重试" >&2; exit 1; }; }
ensure_token() {
  if [[ ! -s "$AUTH_FILE" ]]; then
    need openssl
    (umask 077; openssl rand -hex 32 > "$AUTH_FILE")
  fi
  chmod 600 "$AUTH_FILE"
}
fix_chromium_exec() {
  local cache="${XHS_BROWSER_CACHE_DIR:-$HOME/.cache/xiaohongshu-mcp/browser}"
  if [[ -d "$cache" ]]; then
    find "$cache" -type f \( -name chrome -o -name chrome_crashpad_handler -o -name chrome-sandbox \) -exec chmod u+x {} + || true
  fi
}
install_xhs() {
  need curl; need tar; need go
  if ! go version | grep -Eq 'go1\.(2[4-9]|[3-9][0-9])'; then
    echo "Go 需要 >=1.24。请安装官方 linux-arm64 包（教程要求 Go 1.24）。" >&2; exit 1
  fi
  need Xvfb
  local sourceDir="$ROOT/source"
  rm -rf "$sourceDir"
  mkdir -p "$sourceDir"
  echo "下载上游 $SRC_TAG 源码（不需要 git）"
  curl --fail --location --retry 3 --silent --show-error \
    "https://github.com/xpzouying/xiaohongshu-mcp/archive/refs/tags/$SRC_TAG.tar.gz" \
    | tar -xz --strip-components=1 -C "$sourceDir"
  (cd "$sourceDir"; CGO_ENABLED=0 GOOS=linux GOARCH=arm64 go build -trimpath -o "$APP" .)
  chmod 700 "$APP"
  ensure_token
  echo "ARM64 程序已编译：$APP"
  echo "持久会话：$COOKIE"
}
start_xhs() {
  [[ -x "$APP" ]] || { echo "先运行 bash arm64-proot.sh install" >&2; exit 1; }
  ensure_token
  fix_chromium_exec
  need Xvfb
  if ! alive "$XVFB_PID_FILE"; then
    Xvfb "$DISPLAY_NO" -screen 0 1280x900x24 > "$LOGDIR/xvfb.log" 2>&1 &
    echo "$!" > "$XVFB_PID_FILE"
    sleep 2
    if ! alive "$XVFB_PID_FILE"; then echo "Xvfb 启动失败，请检查 $LOGDIR/xvfb.log" >&2; exit 1; fi
  fi
  if alive "$PID_FILE"; then echo "服务已运行 PID=$(cat "$PID_FILE")"; return; fi
  # Cookie precedence: COOKIES_PATH > current working directory > /tmp.
  # Never start from a source checkout containing an empty cookies.json.
  cd /
  export COOKIES_PATH="$COOKIE"
  export AUTH_TOKEN="$(cat "$AUTH_FILE")"
  export DISPLAY="$DISPLAY_NO"
  nohup "$APP" -headless=false -port ":$PORT" > "$LOGDIR/xhs.log" 2>&1 &
  echo "$!" > "$PID_FILE"
  sleep 3
  if ! alive "$PID_FILE"; then echo "MCP 未能启动：$LOGDIR/xhs.log" >&2; exit 1; fi
  echo "Go MCP 已启动：http://127.0.0.1:$PORT/mcp"
  echo "在露露机填相同端口和 $AUTH_FILE 内的 Token，然后进行扫码/登录/真实读帖测试。"
}
stop_xhs() {
  if alive "$PID_FILE"; then kill "$(cat "$PID_FILE")" || true; fi
  rm -f "$PID_FILE"
  if alive "$XVFB_PID_FILE"; then kill "$(cat "$XVFB_PID_FILE")" || true; fi
  rm -f "$XVFB_PID_FILE"
  echo "已停止属于此脚本的 MCP 和 Xvfb（Cookie 未删除）"
}
status_xhs() {
  if alive "$PID_FILE"; then
    echo "进程在运行 PID=$(cat "$PID_FILE")"
  else
    echo "MCP 未运行（手机重启/proot 退出后需要重启）"
  fi
  if command -v curl >/dev/null; then
    echo "HTTP状态（405仅说明服务有响应，不说明已登录）："
    curl -s -o /dev/null -w '%{http_code}\n' --connect-timeout 3 \
      "http://127.0.0.1:$PORT/mcp" || true
  fi
  echo "Cookie固定位置：$COOKIE；敏感 Token：$AUTH_FILE"
}
case "$ACTION" in
  install) install_xhs ;;
  start) start_xhs ;;
  restart) stop_xhs; start_xhs ;;
  stop) stop_xhs ;;
  status) status_xhs ;;
  *) echo "用法：bash arm64-proot.sh {install|start|status|restart|stop}" >&2; exit 2 ;;
esac
