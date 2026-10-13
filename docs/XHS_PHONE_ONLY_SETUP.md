# 只有一部安卓手机：小红书 MCP 接入露露机

> 2026-10-13 按上游实际 README、Docker 文件和在线部署平台文档整理。不需要 Windows、macOS、Termux，也不要求手机一直开浏览器。

## 为什么不能在露露机里“直接打开开关”？

所选开源项目 [xpzouying/xiaohongshu-mcp](https://github.com/xpzouying/xiaohongshu-mcp) 的真实服务依赖 Linux x64 Chromium 浏览器自动化。它发布的程序面向 Windows x64 / Linux x64 / macOS ARM64；当前**没有可直接安装的安卓 MCP 服务端 APK**。露露机已接好客户端，但还要有一个正在运行的服务端。

## 优先探索：Termux 本地免费方案（尚非即装即用）

如果你已经安装 Termux，这是零月租路线：Termux 在**这部手机**启动小红书 MCP，露露机用 `http://127.0.0.1:端口/mcp` 访问。露露机 2026-10-13 已开放只限回环地址的 HTTP，不必购买服务器。

**但原作者 xpzouying/xiaohongshu-mcp 明确只提供 Linux x64 / Windows x64 / macOS ARM64，未支持 Android 或 Linux ARM64。不能把它的 Linux x64 文件当成安卓文件执行。** 要真正在 Termux 使用，有两条实验方向：

- `jobsonlook/xhs-mcp`：Python + Node，直接请求无需重型 Chromium，但要求手工导出网页登录 Cookie，且工具名称及 stdio 传输与露露机客户端不兼容，需要编写可信赖的协议适配器。
- Termux Chromium + Playwright/Go Rod：社区已验证 ARM64 Chromium 在 Termux 中运行，但必须改造上游浏览器查找/下载安装逻辑。运行开销高，尤其 Android 后台与省电机制会终止长驻进程。

**两条都还没有在真实小红书账号上验证成功，不能只复制一行安装命令就声称已经连通。** 目前可先在 Termux 内检查环境（只检测，不会登录或部署小红书）：

```sh
uname -m
command -v python
command -v node
command -v chromium
```

如果 `uname -m` 显示 `aarch64`，证实使用 ARM64；未显示 Chromium 不等于手机不能安装。设备未授权前，请不要把 Cookie 或 Token 发给第三方或上传到公开仓库。哪怕 Termux 本地 MCP 已启动，后台持续运行仍受安卓进程限制。

参考：
- https://github.com/jobsonlook/xhs-mcp
- https://github.com/Jobians/playwright-termux
- https://github.com/termux/termux-app

## 备选：纯手机浏览器 + Railway 云端 Docker（可能需要付费）

用手机浏览器操作 [Railway 项目管理](https://railway.com/dashboard)，部署镜像由云端服务器完成，与你是否有电脑无关。

1. 在手机上登录 Railway（可使用 GitHub 账户）；点击创建项目 / New Project。
2. 新增服务选 **Docker Image**（不是 GitHub 手机端执行）。镜像名称填 `xpzouying/xiaohongshu-mcp`。若平台无法拉取镜像，可改用从上游 GitHub 仓库创建服务，由 Railway 构建自带 Dockerfile，但构建资源、网络兼容性需另行验证。
3. 服务环境变量按下表设置。`AUTH_TOKEN` 用密码管理器生成一个长而随机的值；**不得使用这些文档中的占位值**：

   | Key | Value |
   |---|---|
   | `AUTH_TOKEN` | 你的随机长 Token（以后在露露机输入同一个） |
   | `HOME` | `/app/data/home` |
   | `XDG_CONFIG_HOME` | `/app/data/config` |
   | `COOKIES_PATH` | `/app/data/cookies.json` |

4. 在 Railway 服务的存储设置添加 **持久化 Volume**，挂载到 `/app/data`。没有持久化卷，服务重启或更新后可能丢失小红书登录状态。
5. 在服务 Networking / Public Networking 中 **Generate Domain**，把服务公开端口设为 `18060`（此项目默认在 :18060 监听）。Railway 应分配 HTTPS 域名。若访问 `https://<域名>/health` 得到正常健康检查结果，代表服务已经启动；**不代表小红书已经登录**。
6. 露露机 → **设置 → MCP 与联网服务** → 小红书 MCP，填写 `https://<分配的域名>/mcp` 和同一个 `AUTH_TOKEN`，点击“保存并测试”。
7. 如果显示未登录，点页面里的“获取登录二维码” → “保存到相册”。打开手机上的 **小红书 → 我 → 左上角菜单 → 扫一扫 → 相册**，选择刚保存的 QR 图片；确认授权，返回露露机再测试连接。二维码短时有效，过期请重新获取。
8. 为需要的角色单独开启“角色设置 → 主动执行授权 → 主动逛小红书（只读）”。再在聊天中请求“去小红书搜一下……，读一篇后告诉我你的想法”，或允许后台自主感知时按兴趣选择此工具。

> **费用提示**：Railway 免费试用/基础资源较有限；Docker 中有 Chromium，0.5GB 内存可能无法正常运行。长期 24 小时在线可能需要 Hobby 或更高额度，费用随内存和 CPU 占用变化。先查 Railway 当前价格和免费额度，**不要假设一定免费**。你未主动要求开通付费服务前，我不会替你创建付费订阅。

> **账号安全**：Cloud Docker 里的浏览器将保存小红书登录 Cookie。Railway 存储、服务日志、访问 Token 均属于敏感配置。千万不要把登录二维码、Cookie、Token 公开贴在 GitHub Issue 或聊天里；服务 HTTPS + MCP Token 必须一起配置。平台地址被未经授权的人访问会带来账号风险。尽管客户端目前仅开放搜索和读帖，上游服务端本身仍含写入工具，因此**尤其要保护 Token**。

## 手机直接运行 MCP 的备选方案

安卓 Termux / proot-linux 理论上可以尝试搭建 Linux 图形浏览器，但上游没有官方安卓适配；CPU 架构、Chromium 安装、内存与系统后台限制都可能造成失败，无法保证“手机锁屏也运行”。不推荐作为首选。

第三方 x-mcp 浏览器扩展也是面向桌面浏览器；安卓浏览器扩展兼容性不保证，因此**不把它冒充无电脑的现成解法**。

## 露露机内部行为

- MCP 服务端负责获取真实笔记；露露机客户端负责角色授权和追溯笔记来源。
- 只有确实拿到 `data.note.desc` 后才标记已阅读；搜索结果不是阅读正文。
- 角色可以形成自己的独立感想，不自动评论、点赞、发布帖子。
- 软件/手机后台的模型唤醒依然受安卓后台限制，**云端部署只解决浏览器服务持续在线**，不等于角色每分钟都自主行动。
- “网络可访问”“MCP 工具正常”“小红书已登录”“成功获取笔记正文”是四个不同的状态。必须逐步验证。

上游文档：
- [项目 README](https://github.com/xpzouying/xiaohongshu-mcp)
- [Docker 部署说明](https://github.com/xpzouying/xiaohongshu-mcp/blob/main/docker/README.md)
- [Railway Docker 服务部署](https://docs.railway.com/services)
- [Railway 官方价格](https://railway.com/pricing)
