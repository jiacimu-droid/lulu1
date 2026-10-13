#!/data/data/com.termux/files/usr/bin/bash
# Termux ARM64 only. Read-only Xiaohongshu adapter for the Lulu Android app.
# Downloaded files are plain source from the user's own lulu1 repository.
set -eu
if ! command -v pkg >/dev/null 2>&1; then
  echo "请在 Termux 中运行，而不是浏览器或普通安卓命令框。"; exit 1
fi
if [ "$(uname -m)" != "aarch64" ]; then
  echo "目前仅整理了 aarch64 手机安装路径，检测到：$(uname -m)"; exit 1
fi
echo "[1/5] 检查基础依赖"
if ! command -v curl >/dev/null 2>&1; then pkg install -y curl; fi
if ! command -v node >/dev/null 2>&1; then pkg install -y nodejs-lts; fi
if ! command -v chromium-browser >/dev/null 2>&1 && ! command -v chromium >/dev/null 2>&1; then
  echo "未检测到 Chromium，安装 Termux 官方 X11 仓库软件包（可能下载数百 MB）"
  pkg install -y x11-repo
  pkg install -y chromium
fi
BROWSER="$(command -v chromium-browser || command -v chromium || true)"
if [ -z "$BROWSER" ]; then
  echo "Chromium 仍未找到，请检查 pkg install chromium 的报错"; exit 1
fi
echo "Chromium: $BROWSER"
echo "Node: $(node -v)"

echo "[2/5] 下载开源只读桥接代码"
DEST="$HOME/.lulu/xhs-termux"
mkdir -p "$DEST"
chmod 700 "$DEST"
BASE="https://raw.githubusercontent.com/jiacimu-droid/lulu1/main/tools/xhs-termux"
for file in package.json server.mjs patch-browser.mjs start.sh stop.sh; do
  curl --fail --location --silent --show-error --retry 2 "$BASE/$file" -o "$DEST/$file"
done
chmod 700 "$DEST/start.sh" "$DEST/stop.sh"

echo "[3/5] 安装固定版本的 Node.js 开源 MCP 依赖"
cd "$DEST"
npm install --omit=dev --no-fund --no-audit
echo "[4/5] 给固定版本浏览器客户端适配 Termux 启动参数"
node patch-browser.mjs
echo "[5/5] 立即启动只读本机 MCP"
bash "$DEST/start.sh"
echo ""
echo "完成后在露露机 → 设置 → MCP 与联网服务 → 小红书，填入："
echo "http://127.0.0.1:18060/mcp"
echo "Token 留空，然后点保存并测试。未登录则点获取登录二维码。"
echo "重启本地服务：bash ~/.lulu/xhs-termux/start.sh"
