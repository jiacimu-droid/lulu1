# 本机小红书阅读（2026-10-14）

设置 → MCP 与联网服务 →「使用已登录的本机网页」默认开启。先通过「登录 / 检查小红书网页」完成登录，再打开「阅读 / 搜索（与角色共用）」验证首页、关键词和正文。角色仍必须在执行授权中开启「主动逛小红书（只读）」。

内置登录网页的搜索导航（包括 SPA 路由）和笔记点击会转入原生阅读；不再显示出问题的搜索半屏弹层或调用网页详情弹窗。登录、用户资料仍保留原网页。这里只接管阅读入口，不代表修复网站自身接口拒绝访问。

手动阅读和角色 `read_xiaohongshu` 共用 `XhsReadingEngine`。本机路径不要求 Termux 或第二次 MCP 登录；使用默认 WebView 会话，不导出 Cookie。每次导航创建真实尺寸的附着 WebView，35 秒限时、串行执行并在取消/离开前台后释放。解析网页本次已收到的 SSR/Vue 状态、限定笔记接口响应及笔记容器，不自造签名接口，不点击发布/点赞/评论，也不尝试绕过验证码或访问限制。

本机路径需要露露机 Activity 保持前台，不能宣称关屏后永久自主浏览。已配置 MCP 时后台继续使用 MCP；关闭本机开关可明确改用 MCP。一次列表选中的详情继续使用同一提供方，失败不在后台偷偷切换账号来源。

真实正文、作者、来源和个人感想保存在同一角色原始时间线证据中，访问令牌只用于当次导航，不注入模型/记忆。可选学习理解进入既有学习账本，删除来源撤回派生理解。新阅读也进入既有好奇/分享后续选择，但不自动替角色发送消息。

验证边界：自动解析回归、学习证据测试与 APK 构建可确认代码行为，不能替代用户账号下的真实网站接口与安卓机验收。平台限制必须在网页端由用户处理；没有正文不生成阅读经历。

---

# 露露机：小红书阅读 / 华为手环健康 MCP（可选）

适用：Android lulu1；2026-10 的 Streamable HTTP MCP 连接器。本仓库包含 Android 只读客户端和复用本机网页登录会话的阅读引擎；不会预置账号、Cookie 或后台常驻服务器。

## 用户要完成的连接步骤

1. 在一台可持续开机的电脑 / 自建服务器上**部署并完成登录**下列开源服务。
2. 云端服务使用 HTTPS `/mcp` 和强随机 Token。若使用 Termux 在同一手机上启动兼容 MCP，则仅允许 `http://127.0.0.1:端口/mcp` / `localhost`；**不可把未鉴权的服务公开到互联网**。
3. Android 露露机：**设置 → MCP 与联网服务**，填 HTTPS MCP 地址和 Bearer Token，点击“保存并测试”。这是全局共享连接配置，不在任何角色设置里。
4. 在每个需要自主操作的角色上，通过 **角色设置 → 主动执行授权** 分别开启“主动逛小红书（只读）”或“读取手环健康 MCP（只读）”。角色授权与全局连接独立。
5. 先在聊天中测试，再观察后台 WorkManager 自主感知；模型可以选择行动也可以选择不行动，不强行每轮爬帖。

> 电脑的 `localhost:18060` 指电脑本身；Android 手机内填 `localhost` 会指向手机自己。请使用实际可访问的 HTTPS 反向代理或隧道地址。当前客户端只对同手机的 `127.0.0.1` / `localhost` 开放明文 HTTP；其他网络地址必须 HTTPS，避免传输登录 Token 和健康数据时泄露。

## 小红书：xpzouying/xiaohongshu-mcp

上游：https://github.com/xpzouying/xiaohongshu-mcp

Windows 版可在 https://github.com/xpzouying/xiaohongshu-mcp/releases 下载：

- `xiaohongshu-login-windows-amd64.exe`
- `xiaohongshu-mcp-windows-amd64.exe`

先运行登录程序并按上游流程登录小红书账号；再开启带鉴权的服务（PowerShell 示例）：

```powershell
$env:AUTH_TOKEN = "换成随机的长字符串"
.\xiaohongshu-mcp-windows-amd64.exe
```

默认上游本机地址 `http://localhost:18060/mcp`，需要用 HTTPS Tunnel / 反向代理映射为手机可访问的 `https://your-host.example/mcp`。Windows 电脑睡眠/关机后，该服务也会下线；想要不依赖电脑在线，请部署在可信的 24 小时服务器。由于小红书使用浏览器登录，账号可能过期，服务不能永久保证登录状态。

露露机**只允许**这 4 个上游只读工具经由自己的工具适配器调用：

- `check_login_status`（设置页检测）
- `list_feeds`（阅读首页推荐）
- `search_feeds(keyword)`（搜索真实主题）
- `get_feed_detail(feed_id, xsec_token)`（再真正打开笔记）

统一露露机工具名：`read_xiaohongshu`，参数 `{"keyword":"感兴趣的关键词"}`（空字符串代表推荐）。

后台主动感知与聊天均经由原有 `ToolRouter`，依照角色授权和角色当轮动机选择。工具做两步：**选到来源** → **读到正文**。只有完整成功才写“实际小红书阅读”证据，并可自愿产生读后感；评论、点赞、收藏和发表笔记目前**没有开放执行权限**。不能将外部笔记指令当作用户命令，不能把博主生活当角色亲历。

## 华为手环：band-health-sync + health-mcp

配对与部署详细步骤在上游：
- https://github.com/xiaoyou5602/band-health-sync
- https://github.com/xiaoyou5602/band-health-sync/blob/master/docs/GUIDE.md
- https://github.com/xiaoyou5602/health-mcp

`band-health-sync` 是第三方 Gadgetbridge 改进 APK，已经记录 Huawei Band 10 测试；并非华为官方 SDK。为了避免和华为运动健康争夺蓝牙，一些机型可能需要调整配对过程；**操作前务必阅读设备对应指南，确认历史数据备份，不要贸然清空手环/删除原 App**。

其配套健康服务器是自托管 Node.js `health-mcp`。按上游指南建立**两个不同 Token**：手机数据上传 Token 和 AI MCP 只读 Token。启用 HTTPS 与访问控制，配置正确的所在地时区。服务端返回真实已同步的数据；并非每秒实时传感器数据。

露露机实现：
- 已配置时，后台每小时通过网络读取一次 `health_read(data_type=daily_summary,days=7)`。
- 将每日步数、心率统计、睡眠总时长与阶段等映射到原有 `GadgetbridgeHealthStore`，供健康页、学习页与角色健康感知复用。
- 保留旧 Gadgetbridge 数据；MCP 的日汇总**不包含完整睡眠起止时刻**，不会杜撰起床时间。
- 角色获准后可主动调用 `read_health_mcp(data_type=current_status|steps|heart_rate|sleep|daily_summary)`；失败、无数据和未授权时均不能声称取得数据。

**限制与验证**：这些上游服务没有在你的真实账号 / 手环上完成端到端测试。代码编译通过也只证明 Android 构建，不代表服务已经部署、登录或同步成功。需要在设备上分别检查“测试连接”、实际阅读记录、后台健康新数据与网络断开回执。

## 故障排查

- HTTPS 地址检查：必须填最终的 `/mcp` 服务；配置框可自动补后缀；**localhost 只用于同一手机内运行的 Termux**，不能跨设备使用。
- 401/403：检查 Token 与服务端 `AUTH_TOKEN` 或 `HEALTH_MCP_ACCESS_TOKEN`。
- 小红书显示“未登录”：先在服务端完成登录工具/扫码，并确保登录状态可被服务读取。
- 搜索有列表但正文为空：未视为成功读帖；先用上游 MCP Inspector 单独验证 `get_feed_detail`。
- 健康 MCP 能连接但暂无数据：检查 Gadgetbridge 是否实际从手环同步，是否启用了上传，是否选对设备、时区和服务器上传令牌。
- 手环数据日期滞后：应用后台调度是 **尽力而为的定期同步**，受 Android 省电和网络状态限制，非实时。

## 根因证据边界与针对性处理（2026-10-14）

- 用户原截图：可显示列表和搜索结果，诊断 HTTP 错误为 0 但检测到详情失败。旧诊断只统计 HTTP>=400，无法识别 HTTP 200 中的业务失败；这是已确认的诊断缺陷，不是账号/网络全部可用的证明。
- 上游作者对同类详情失败列出「ID/token 不匹配」和「平台访问限制」两类可能，没有把每条报告断言成风控：https://github.com/xpzouying/xiaohongshu-mcp/issues/375#issuecomment-3977474556 。签名访问参数必须来自实际列表/链接，不能由模型拼接、裁掉 token 末尾 `=` 或沿用旧会话 URL：https://github.com/xpzouying/xiaohongshu-mcp/issues/451#issuecomment-5094719100 。
- 半屏白块存在视口/输入法高度未正确恢复或 DOM 元素遮挡两类待核实方向。Android 16 已有键盘等高留白报告，但对应项目是 Capacitor，当前 lulu1 是原生 Compose，不能照搬结论：https://github.com/ionic-team/capacitor/issues/8329 。
- 本轮原生搜索提交时主动取消输入焦点、收起键盘，使用 Compose 的 IME inset 消费机制，不手工叠加固定键盘高度。搜索/详情以原生视图呈现，不再渲染故障网页弹层。
- 读取器现在保存最近一次的实际 HTTP/业务码、页面阶段、原生高度、文档/visual viewport 高度与下方命中元素；已有诊断按钮可查看。只保存尺寸/错误元数据，不保存 Cookie、访问 token 或帖子正文到诊断。仍需设备上的实际结果，才能确定用户这一例的唯一根因。
