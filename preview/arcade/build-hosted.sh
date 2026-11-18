#!/usr/bin/env bash
# Assemble the browser review from the normal preview UI and the shared Kotlin rules.
set -euo pipefail
out="${1:?Pass a directory for the hosted preview}"
dir="$(cd "$(dirname "$0")" && pwd)"
root="$(cd "$dir/../.." && pwd)"
mkdir -p "$out/art" "$out/legacy"
cp "$dir"/{arcade.css,arcade.js,island-map.js,relic-map.js,chest-show.js} "$out/"
cp "$root/preview/world-joystick.js" "$out/"
sed 's|href="./compare.html"|href="../"|' "$root/preview/play.html" > "$out/legacy/play.html"
cp "$root/app/src/main/res/drawable-nodpi/game_island_lobby_art.png" "$out/art/island.png"
cp "$root/app/src/main/res/drawable-nodpi/game_relic_camp.webp" "$out/art/relic.webp"
cp "$dir/art/"*.webp "$dir/art/island-meshes.json" "$out/art/"
sed -e 's/<html lang="zh-CN">/<html lang="zh-CN" data-runtime="browser">/' \
    -e 's|../world-joystick.js|./world-joystick.js|' \
    -e 's|../index.html">返回露露机 ↗|./design.html">审核设计方案 ↗|' \
    -e 's|../play.html#|./legacy/play.html#|g' \
    -e 's|试玩进度留在本地服务|试玩进度保存在此浏览器|' \
    "$dir/index.html" > "$out/index.html"
# The legacy game modes are self-contained. Bring their existing play page only
# for the three previously linked tabletop previews.
if [[ ! -f "$out/rules-browser.mjs" || "${LULU_REBUILD_RULES:-1}" != 0 ]]; then
  bash "$dir/run-rules.sh" browser "$out"
fi
{
  cat <<'HTML'
<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>露露机 · 六款游戏设计审稿</title>
<link rel="stylesheet" href="./design.css">
</head>
<body>
<nav><a href="./">← 返回游戏馆试玩</a><a href="./design.pdf">下载 PDF</a></nav>
<main>
HTML
  pandoc --from=gfm --to=html "$dir/DESIGN.md"
  printf '%s\n' '</main></body></html>'
} > "$out/design.html"
cp "$dir/browser/design.css" "$out/"
