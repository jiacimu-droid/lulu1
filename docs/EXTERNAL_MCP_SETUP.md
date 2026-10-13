# 露露机：小红书阅读 / 华为手环健康 MCP（可选）

适用：Android lulu1；2026-10 的 Streamable HTTP MCP 连接器。本仓库**只包含客户端**，不会内置小红书 Cookie、账号、浏览器、后台常驻服务器或用户的健康数据。

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
