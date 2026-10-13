# 一部安卓手机：露露机小红书只读 MCP（Termux · ARM64）

此方案无需电脑、云服务器或月租。你已确认手机架构为 `aarch64`，Termux 有 Node.js 25.8.2 和 `x11-repo`；Chromium 尚未安装。

## 这次选定的项目

- 浏览器读取底座：[JessYan0913/xiaohongshu-mcp](https://github.com/JessYan0913/xiaohongshu-mcp) / NPM [@the-thing/xiaohongshu-mcp](https://www.npmjs.com/package/@the-thing/xiaohongshu-mcp)，固定版本 `0.2.0`，ISC 开源许可证，Node.js + `puppeteer-core`，无需额外下载桌面 Chromium。
- 露露机的安卓专用本地桥接：`lulu1/tools/xhs-termux/`，仅开放 5 个 MCP 工具；不导入、不注册任何写入工具。
- 浏览器：Termux `x11-repo` 的 `chromium` ARM64 包，安装后命令一般叫 `chromium-browser`。
- 运行方式：仅绑定 `127.0.0.1:18060/mcp`，同一部手机内使用，不对公网开放。

**状态：** 适配代码和安装脚本已编写并提交，**尚未在用户真机完成登录、搜索、读帖的端到端验证**。浏览器能启动并不代表小红书一定放行。

## 第一步：在 Termux 安装并启动

打开 Termux，粘贴以下 **两行**，等待执行结束。脚本会按需从 Termux 软件源下载 Chromium（体积可能较大），下载只读桥接源文件，安装固定 NPM 依赖，适配浏览器启动参数，最后启动本机 MCP 服务。

```bash
pkg install -y curl
curl -fsSL https://raw.githubusercontent.com/jiacimu-droid/lulu1/main/tools/xhs-termux/install.sh -o ~/lulu-xhs-install.sh && bash ~/lulu-xhs-install.sh
```

如果不想执行下载的脚本，可以先用 `cat ~/lulu-xhs-install.sh` 审查内容。所有源码都在当前仓库 `tools/xhs-termux/` 目录，无需输入任何小红书密码或 Cookie。

若最终看见：

```text
{"ready":true,"local":true,"readOnly":true}
✅ 小红书只读 MCP 已在手机上启动：http://127.0.0.1:18060/mcp
```

表示 **MCP 服务端已启动**，还不代表已经登录小红书。

## 第二步：露露机里连接并登录（无电脑、无需第二台手机）

1. 在已安装的新版露露机点击 **设置 → MCP 与联网服务 → 小红书阅读 MCP → 一键使用本机 Termux · 免费**。
2. 点击 **保存并测试**。如果返回未登录，说明服务已连接但需要授权。
3. 点击 **获取登录二维码**，选择 **保存到相册**。
4. 打开手机上的小红书 App，进入它的「扫一扫」功能，从**相册**选择刚刚保存的图片完成授权（如果页面位置随小红书版本变化，寻找从相册识别二维码选项）。二维码有效期约 4 分钟。
5. 返回露露机再次点 **保存并测试**，应当看到已登录。若超时，重新获取二维码。

**注意：** 不能跳过 Termux 中的服务安装而只点露露机按钮；露露机是客户端，Termux 是本地 MCP 服务端。

## 可选：完全在 Termux 登录，不使用露露机生成二维码

如果“获取登录二维码”报 `Waiting for selector`，不用反复重装。

先在 Termux 运行 `termux-setup-storage` 并在安卓权限弹窗中点“允许”。这一步只是让脚本能把二维码保存到手机相册，不会把 Cookie 放进相册。

然后执行：

```bash
curl -fL --retry 2 -o ~/.lulu/xhs-termux/login-termux.mjs https://raw.githubusercontent.com/jiacimu-droid/lulu1/main/tools/xhs-termux/login-termux.mjs
cd ~/.lulu/xhs-termux && PUPPETEER_EXECUTABLE_PATH="$(command -v chromium-browser || command -v chromium)" XHS_COOKIES_PATH="$HOME/.lulu/xhs-termux/cookies.json" node login-termux.mjs
```

脚本将在后台启动 Termux Chromium，尝试点开登录和扫码模式，把二维码保存为 **相册中的 `lulu-xhs-login-qr.png`**。拿同一部手机的小红书 App 打开扫一扫 → 相册 → 选择该图授权；切回 Termux 按回车，程序检测到登录后把 Cookie 保存到 `~/.lulu/xhs-termux/cookies.json`，和原 MCP 服务共用。

如果没有找到网页中的二维码，它会保存 `lulu-xhs-login-page.png` **完整网页截图**，让你直观看到是否出现验证码、访问限制或者登录按钮。**没有找到二维码时不要把截图误当成可用二维码，也不要向他人发送 Cookie**。浏览器测试正常并不等于小红书允许第三方自动登录。

成功后，回露露机检查账号登录状态。Termux 脚本不需要露露机参与，也不执行发帖、点赞等操作。

## 第三步：允许角色使用

角色设置 → 主动执行授权 → 启用 **主动逛小红书（只读）**。之后你可以在聊天里说「去小红书搜一篇关于小猫的帖子，读完再说说你的想法」，也允许角色在后台感知时根据兴趣自己选择搜索/推荐阅读。

只开放如下 MCP 工具：

| 工具名 | 操作 |
|---|---|
| `check_login_status` | 检查登录态 |
| `get_login_qrcode` | 生成扫码登录图 |
| `list_feeds` | 浏览推荐列表 |
| `search_feeds` | 搜索笔记 |
| `get_feed_detail` | 读取笔记正文 |

发布、评论、点赞、收藏、关注都没有入口，在**安卓客户端和 Termux 服务端均被白名单拒绝**。角色读到真实正文后才保存阅读经历和感想，读取失败不会伪造「已读」。

## 常用维护命令

```bash
# 手机后台被杀或重启后，再启动一次
bash ~/.lulu/xhs-termux/start.sh

# 想停用时
bash ~/.lulu/xhs-termux/stop.sh

# 检查服务是否运行
curl http://127.0.0.1:18060/health

# 启动失败时，只看最近报错（不需要发送任何 Cookie）
tail -n 40 ~/.lulu/xhs-termux/server.log
```

**后台限制：** 切换到其他 App 不需要退出 Termux，但 Termux 服务进程必须存活。MIUI/澎湃 OS 的省电、内存清理、手机重启可能将它停止。需要的话可给 Termux 允许后台运行、将其电池管理设为“不限制”；这可能增加耗电。未来可结合 Termux:Boot 或 runit 实现开机自启/进程监护，但并不保证安卓永远不会杀进程。

## 常见问题

- **提示 chromium-browser 未安装**：脚本会尝试 `pkg install chromium`。若下载失败，检查网络和镜像，不要在没装好之前运行 MCP。
- **npm 安装失败**：复制报错文字/截图。此方案无 Go / Docker / Ubuntu / proot 依赖。
- **服务 /health 正常但扫码失败**：通常是 Chromium 页面选择器失效、网页风控或浏览器启动错误。查看日志中是否有 `Failed to launch browser` 或 `get_login_qrcode`，不要反复删除整个 Termux。
- **搜索提示登录失效**：扫码状态只存在本机私有文件 `~/.lulu/xhs-termux/cookies.json`；不要分享或提交到 GitHub。小红书可能要求重新登录。
- **脚本未通过新版 npm 依赖检测**：固定依赖 `@the-thing/xiaohongshu-mcp@0.2.0`；补丁脚本先校验上游源码位置，不匹配会停止而不会盲改。
- **账号安全**：这是第三方开源自动化工具，不是小红书官方 API。可能遇到验证码、风控或账号限制，请控制使用频率。MCP 不应该批量抓取。

源码：
- [Lulu 适配代码](https://github.com/jiacimu-droid/lulu1/tree/main/tools/xhs-termux)
- [上游 Node.js 只读用途所需的源码](https://github.com/JessYan0913/xiaohongshu-mcp)
