# 露露机：云端独立小红书只读 MCP

> 本目录提供**可构建的服务端**，不是已部署/已登录/已验收的实例。
> 2026-10-18。适合只有 Android 手机、不希望 Termux 常驻的用户。

## 做什么

- 使用 `tools/xhs-termux/server.mjs` 同一套服务端协议和严格的五工具白名单：
  `check_login_status`、`get_login_qrcode`、`list_feeds`、
  `search_feeds`、`get_feed_detail`。
- Docker 内运行 Node + Linux Chromium + 中文字体；无需电脑长期开机。
- 云端模式（`LULU_XHS_BIND_HOST=0.0.0.0`）**必须**设随机 `AUTH_TOKEN`（至少 32 UTF-8 字节），全部 `/mcp` 请求校验 Bearer；`/health` 不泄露凭据，仅表示进程存活。
- 登录 Cookie 写入 `/data/cookies.json`，**必须挂载持久化卷**到 `/data`，否则重部署可能丢失登录。
- 不注册发布、点赞、收藏、评论、关注工具；不开放 Chromium 调试端口。
- 手机版露露机调用远端 HTTPS `/mcp`，不依赖 WebView 保持前台。

## 纯手机 Railway 部署

1. 用手机浏览器进入 Railway，使用 GitHub 连接自己的 `jiacimu-droid/lulu1` 仓库。
2. 新建 Service，选择 **Deploy from GitHub repo**；在构建设置中使用 Dockerfile 路径 `tools/xhs-cloud/Dockerfile`（构建上下文为仓库根目录）。**不要**选旧的第三方镜像直接暴露公网上的 MCP。
3. 在服务 Variables 中设置 `AUTH_TOKEN` 为密码管理器生成的随机 32 字节以上字符串，例如随机 48 字节 base64；仅保存到 Railway 与露露机的设置，不上传 GitHub。可让平台把 `PORT` 设为 `18060`。
4. 为服务添加持久化 Volume，挂载路径填 `/data`。若无 Volume 请先停止：否则无法可靠保留登录。
5. 在 Networking 生成公开 HTTPS Domain，服务 Target Port 指向进程实际端口（默认 `18060`）。
6. 检查 `https://你的域名/health` 返回 `{"ready":true,"local":false,"readOnly":true}`；这只表示服务进程在运行，**不表示已登录**。
7. 打开露露机 → 设置 → 联网与阅读 → 小红书 MCP，填 `https://你的域名/mcp` 和相同的 `AUTH_TOKEN`，保存。测试连接成功后，再点登录状态检测；未登录时用“获取登录二维码”保存到相册，使用小红书 App 扫码并完成授权。
8. 再测登录状态；最后请角色真正搜索一条**新关键词**并读取一篇笔记正文。结果里必须带真实 ID、来源和内容，图片理解必须有真实图片下载并被多模态模型处理。
9. 角色设置里开启“主动逛小红书（只读）”。后台角色的**唤醒调度仍由安卓侧控制**；独立浏览器在线不等于手机的后台唤醒永远可靠。

**手机无电脑不影响云端 Docker 构建**，但是需要一台实际运行容器的云端主机；可能按资源计费。不要未经同意开通付费套餐。

## 本地 Docker 验证（开发者，可选）

```sh
docker build -f tools/xhs-cloud/Dockerfile -t lulu-xhs-readonly .
docker volume create lulu-xhs-session
docker run --rm -p 127.0.0.1:18060:18060 \
  -e AUTH_TOKEN="填写随机生成的至少32字节长令牌" \
  -v lulu-xhs-session:/data lulu-xhs-readonly
```

**验证分四层，不可混淆：**

1. `GET /health` → 仅检查 Node 服务进程。
2. MCP `initialize` → 带 Token 和协议头，检查连接与认证，未提供 Token 应得到 401。
3. MCP `check_login_status` → 确认云端 Chromium 当前有可用小红书登录。
4. MCP `search_feeds` → `get_feed_detail` → 验证某关键词对应的真实笔记正文；错误时保留真实错误，不允许模型编造阅读成功。

如遇验证码、访问限制或登录过期，需在用户授权下使用官方登录步骤；不尝试绕过网站验证。不把 Cookie、Token、二维码或完整笔记内容放进公开 issue/log。

## 与旧方案的关系

- `tools/xhs-termux` 仍能在本机 `127.0.0.1` 上无 Token 使用；外网监听则强制 Token。
- **云端**用同一个只读 server.mjs，但独立 Chromium、数据卷、HTTPS 和 Bearer。
- `lulu1` 的 `LuluExternalMcp.kt` 已有 Streamable HTTP MCP、登录二维码、登录状态、搜索/详情及 token 参数；不需要重写手机 UI。
- 从已登录的手机 WebView **不能自动把 Cookie 传给云端容器**；云端必须独立登录一次。

## 限制

- 部署前未经过此仓库的真实 Railway + 用户账号 + Android 端到端测试；Docker 构建成功仍不能证明小红书允许阅读，登录与搜索可能受平台网络策略影响。
- 服务端在线也不能自动让安卓后台无限制启动角色任务。完全脱离手机独立感知，需要另外的云端任务调度、状态与记忆同步、推送认证，属于下一阶段。
- Dockerfile 安装 Chromium，资源消耗明显高于纯 Node；云端服务通常不是免费无限使用。
