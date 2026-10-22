# 露露机 × xpzouying Go 小红书 MCP：手机优先的真实搜索与阅读

> 正式接入目标：`xpzouying/xiaohongshu-mcp`（Go），**不是**仓库旧的 `@the-thing/xiaohongshu-mcp` Node 适配器。依据用户上传的《小红书 MCP · 从零到门铃》完整教程，尤其是 **ARM64 源码编译、Xvfb 非无头启动、Cookie 路径优先级、登录与真实读帖验证**。
>
> 本目录是可复现的部署配置与客户端对接，不代表云主机已创建、账号已登录或真机已阅读成功。

## 路线选择

- **优先：独立云端 Chromium + Go MCP。** 浏览器不依赖露露机 WebView/Termux 在手机前台；你仍通过手机里的露露机完成扫码和读帖测试。
- **备选：单手机 Ubuntu 24.04 ARM64/proot。** 按教程 Go 1.24 编译 + Xvfb；仅在另一软件/服务持续运行时可用，Android 省电和重启仍可能杀进程。
- 原先 Android 内置 WebView 保留**前台**登录/浏览/手动阅读；不再假装它能在锁屏后持续执行 JS。
- DeepSeek Search/Fetch 与 Tavily 是**通用网页搜索**，不能代替小红书登录后搜索+详情。

## A. 云端部署（最符合目标，优先尝试）

`tools/xhs-xpzouying/Dockerfile` 继承**上游官方镜像**，沿用其内置 Chromium/MCP，不引入第二套未验证的爬取实现。关键环境变量：

| 环境变量 | 设置值 | 目的 |
|---|---|---|
| `AUTH_TOKEN` | 自己生成的**强随机长字符串**，建议至少 32 字节 | 保护公开的 MCP |
| `COOKIES_PATH` | `/app/data/cookies.json` | 固定真实会话，消除 cwd/cookies.json 抢优先级 |
| `HOME` | `/app/data/home` | 让浏览器数据位置持久 |
| `XDG_CONFIG_HOME` | `/app/data/config` | 持久配置 |
| `PORT` | 不依赖此字段改端口；上游默认监听 **18060** | 不要把 Railway 随机 PORT 当成 Go -port 参数 |

Railway 手机浏览器部署：

1. Railway 使用 GitHub 的 `jiacimu-droid/lulu1` 仓库，新建一个 **Dockerfile** 服务，路径选 `tools/xhs-xpzouying/Dockerfile`，构建上下文为仓库根。
2. **创建 Volume，挂载到 `/app/data`**。没有可持久化的存储时不进入登录步骤，否则 redeploy 可能清空 Cookie。
3. 在 Railway Variables 设置随机 `AUTH_TOKEN`（不要写入 GitHub、聊天、公开 Issue），确认请求只经平台分配的 **HTTPS** 域名访问，目标端口为 `18060`。
4. 在露露机「设置 → 联网与阅读 → 小红书独立阅读线路」填写 `https://你的域名/mcp` 与相同 Token。**不使用云端的 localhost**。
5. 顺序测试：**测试服务 → 检查登录 → 获取二维码/保存相册 → 用小红书 App 扫描 → 再次检查登录 → 搜索新关键词并真的打开一篇笔记**。
6. 只有真实搜索与详情读取成功才自动开启「角色自主阅读：优先使用独立 MCP」。到角色的「主动执行授权」开启只读阅读能力。
7. 如果只有搜索标题，没有正文：仍然是**未完成**。如果读取图片失败，只记录实际已读的文字，不能伪称看过图片。

**重要安全边界**：上游官方 MCP *本身包含发布、评论、点赞、收藏等写入工具*。露露机客户端只允许调用五个只读工具（`check_login_status`、`get_login_qrcode`、`list_feeds`、`search_feeds`、`get_feed_detail`），但客户端白名单**不是服务端只读保证**。必须保护云端 `AUTH_TOKEN`、Cookie 和 HTTPS 入口，不得把无鉴权的 Go MCP 直接公开。若未来需要严格服务端只读，必须在后端部署独立的工具白名单代理，而不是放宽客户端权限。

上游官方参考：<https://github.com/xpzouying/xiaohongshu-mcp>，Docker 参考 <https://github.com/xpzouying/xiaohongshu-mcp/blob/main/docker/README.md>。

本地可持久自建服务器可用 `docker-compose.yml`：先在私有 shell 环境中导出 `AUTH_TOKEN`，然后从仓库根目录执行
`docker compose -f tools/xhs-xpzouying/docker-compose.yml up -d --build`。
Compose 只映射 `127.0.0.1:18060`，要让手机跨网络访问必须另配受保护的 HTTPS 入口；不支持直接暴露 18060 给公网。

## B. 依据教程的 ARM64/proot 备选（端口 18061）

在手机里的 **Ubuntu 24.04 ARM64/proot Linux shell** 运行，不是在 Windows 或普通 Android shell：

1. 安装 Go **1.24+ linux-arm64**、Xvfb、curl、tar、openssl 和 Chromium 运行库。Ubuntu 24.04 使用 `*t64` 包名。确认 `go version`、`Xvfb -help` 有响应。
2. 用 `bash tools/xhs-xpzouying/arm64-proot.sh install` 下载固定 `v2.5.0` 源码 tar.gz、Go 编译 ARM64 二进制；不依赖 `git`。
3. 用 `bash tools/xhs-xpzouying/arm64-proot.sh start` 启动 **Xvfb :99** 与 `-headless=false` 的 Go 服务。
4. 在露露机填 `http://127.0.0.1:18061/mcp`，Token 从 Ubuntu 中的 `~/.local/share/lulu-xhs/data/auth-token` **自行复制到 App（不要发给别人）**。手机内同一个 loopback 才可互通。
5. 用小红书扫码登录；再做「关键词搜索 → 详情正文」验证。文件 `~/.local/share/lulu-xhs/data/cookies.json` 是唯一路径。
6. `bash tools/xhs-xpzouying/arm64-proot.sh status|restart|stop` 管理本脚本启动的进程。重启手机或 proot 之后，需要重新启动此服务。

脚本保证启动时 `cd /` 且显式 `COOKIES_PATH`，避免源码目录里空 Cookie 抢占有效会话；每次启动检查 Chromium 可执行位。**它不能消除 Android 对 Termux/proot 进程保活的限制**，因此不是建议的长期主线路。

## 关键排错（按真实执行阶段区分）

| 症状 | 应检查 |
|---|---|
| `/mcp` 返回 405 | 只说明 HTTP 服务可能活着，**不证明登录成功** |
| 工具 `initialize` 失败 | HTTPS/Token/端口/代理/服务端是否实际响应 MCP |
| `check_login_status` 已登录，但读帖显示登录 | Cookie 优先级 `COOKIES_PATH`、是否浏览器真的使用相同数据卷 |
| 登录二维码不出现（proot） | Xvfb 是否正常、启动参数 `-headless=false`、Chromium crashpad 的执行权限 |
| 搜索无结果，或笔记打开失败 | `search_feeds` 必须返回真实 `feed_id + xsec_token`，详情同源；过期则重新搜索 |
| 笔记只有标题 | 不算已阅读；不要往角色记忆里写作完整阅读 |
| 阅读笔记后没有角色消息 | 先查工具回执与统一时间线；角色可自主决定不分享，不应强行发送 |
| 手机后台一直没有启动搜索 | 云端 MCP 在线只解决浏览器持久；手机的 WorkManager/自主感知唤醒另需验证 |

## 验收的唯一终点

- [ ] 独立服务部署后登录真实账号（不是仅有 405 / initialize）
- [ ] 从露露机手机端成功执行 `search_feeds`
- [ ] `get_feed_detail` 用同一条结果里的 ID + xsec_token 读到真正的正文
- [ ] 实际已读评论、下载图片及多模态理解均标注独立来源/成功与失败
- [ ] 角色的真实阅读结果写入统一时间线，学习/分享仍由角色自主决定
- [ ] 露露机切出到其他 App 后仍能以授权的后台唤醒读取（受 Android 调度约束，须真机验收）
- [ ] 断网、Cookie 失效、验证码、超时、服务重启时能明确报错而不虚构读过
