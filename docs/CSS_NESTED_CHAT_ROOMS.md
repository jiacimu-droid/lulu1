# 聊天内嵌小房间 · CSS 自由装修

「一起听」「一起看」仍然是 **Lulu1 聊天页面内部的房间**，不是独立 App、另一个角色或新的聊天存档。点击聊天工具栏进入对应房间，返回仍在同一段聊天里。CSS 只影响呈现，不会改动播放 API、媒体授权、真实聊天消息或角色的记忆。

## 使用

在 **设置 → CSS 装修工坊 → 选择要装修的页面** 选择「聊天 · 一起听」或「聊天 · 一起看」。

- 选择「聊天 · 一起听」，点击 **插入一起听样式** 得到可编辑的起点；可点 **替换唱片图片**，选择手机上的 PNG/JPG/WebP 素材。导入素材生成本地 `asset://` 令牌；图片不会被上传服务器。
- 选择 **房间背景** 导入一张图片，CSS 加到 `#ListenTogether .music-room`。
- 选择「聊天 · 一起看」，可以插入放映室皮肤并上传背景。
- 最后点击 **应用到页面**。不满意可以在工坊清空 CSS，恢复默认样式。

CSS 采用继承关系：

```
:root
  └─ #Chat
       └─ #ChatDetail
            ├─ #ListenTogether
            └─ #WatchTogether
```

例如 `#Chat { --accent: #d39aab; }` 会被两个房间继承；只编辑 `#ListenTogether .music-vinyl` 不会影响其他页面。

## 一起听的细粒度选择器

| 选择器 | 控制对象 |
| --- | --- |
| `#ListenTogether` | 房间主题、页面背景 |
| `#ListenTogether .music-room` | 完整房间框体与背景图 |
| `#ListenTogether .music-mini` | 展开的聊天悬浮窗皮肤 |
| `#ListenTogether .music-mini-compact` | 收起后的迷你播放条 |
| `#ListenTogether .music-mini-hidden` | 隐藏时的入口小图标 |
| `#ListenTogether .music-header` | 房间顶部标题栏 |
| `#ListenTogether .music-source` | 本机媒体 / 本地音乐入口 |
| `#ListenTogether .music-artwork` | 双人唱片区域的整体高度 |
| `#ListenTogether .music-links` | 双头像附近的连接线颜色与线宽 |
| `#ListenTogether .music-avatar-role` | 角色头像尺寸、独立替换素材 |
| `#ListenTogether .music-avatar-user` | 用户头像尺寸、独立替换素材 |
| `#ListenTogether .music-vinyl` | 唱片尺寸与替换图片（旋转状态不变） |
| `#ListenTogether .music-track-title` | 歌曲标题、字号与颜色 |
| `#ListenTogether .music-track-artist` | 歌手名称 |
| `#ListenTogether .music-progress` | 进度条颜色 |
| `#ListenTogether .music-controls` | 播放控件分区 |
| `#ListenTogether .music-play` | 播放/暂停主按钮 |
| `#ListenTogether .music-caption` | 歌词提示 / 音乐说明文字 |
| `#ListenTogether .music-actions` | 歌单、聊天操作 |
| `#ListenTogether .music-playlist` | 歌单子视图 |
| `#ListenTogether .music-playlist-card` | 歌单卡片 |
| `#ListenTogether .music-chat` | 房间底部的聊天输入区 |
| `#ListenTogether .music-chat-input` | 输入框 |

```css
#Chat { --accent: #bf7897; }
#ListenTogether { --paper: #fff7fa; }
#ListenTogether .music-room { border-radius: 28dp; }
#ListenTogether .music-artwork { height: 210dp; }
#ListenTogether .music-vinyl { width: 120dp; }
#ListenTogether .music-avatar-role { width: 58dp; }
#ListenTogether .music-avatar-user { width: 58dp; }
#ListenTogether .music-links {
  color: #d79aaf; border-color: #f0d8df; border-width: 8dp;
}
#ListenTogether .music-track-title { font-size: 19sp; color: #493845; }
#ListenTogether .music-play { background-color: #ae7290; color: #ffffff; }
#ListenTogether .music-playlist-card { background-color: #ffffff; border-radius: 18dp; }
```

替换原有唱片，请从装修工坊「替换唱片图片」导入；它会生成类似下面的实际本地引用：

```css
#ListenTogether .music-vinyl {
    background-image: url("asset://你导入后生成的令牌.webp");
    background-size: contain;
    width: 120dp;
}
```

这里的令牌只是示意，不能直接粘贴使用，必须用 App 导入图片得到真实的 `asset://` 资源。

## 一起看选择器

`#WatchTogether`、`.watch-room`、`.watch-header`、`.watch-player`、`.watch-controls`、`.watch-chat`；使用方法一致，调整房间背景、放映区、控制区和聊天区外观。

## 范围与安全

当前是 **Android Compose 原生 CSS 子集**，并不是一个 WebView 浏览器，因此暂不支持任意 HTML、JavaScript、外部 CSS、CSS 动画、复杂选择器或远程图片 URL。可用的尺寸为 `dp`，字号为 `sp`；美化页面内可用 App 自己导入的背景资源。

唱片可更换为个人素材，播放/暂停、上一首/下一首、聊天发送与已有媒体会话仍调用原来的真实功能。部分尺寸有上限/下限，避免误把头像或点击目标缩到不能用；仍建议不同屏幕实测，恢复原生 CSS 是最简单的撤销方式。

**验收区别：** 这里记录的是源码支持的选择器与预期行为；APK 成功构建和真机渲染必须独立验证，不能仅因为源码已提交就当作验证通过。
