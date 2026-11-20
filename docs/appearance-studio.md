# Lulu CSS 装修工坊：原生 Compose 主题

## 2026-11-18 新入口

原来九套固定外观已从用户可选目录移除。新安装只保留一个不可见的原生兜底（paper）；旧用户本机保存的自定义外观记录没有被直接删除。桌面「美化」现在直接进入 CSS 装修工坊，不再陈列预设卡片。

用户可以编辑 CSS、导入 .css 文本、导出 .css 文本、按页面切换预览、导入 PNG/JPEG/WebP 背景图，并保存到 App 私有 SharedPreferences。图片复制进 App 的私有文件目录，不经服务器，不依赖在线托管。每张素材最多 8MB、长宽最多 8192px，显示时降采样到约 1536px。

## 可用语法

这是受控的原生 CSS 子集，而不是直接在 Android 上运行 Web CSS。不会执行脚本、HTML、网络 URL 或 @import。未实现的声明会提示但不会运行，语法错误不能应用。

- :root 对所有 App 生效；#Home、#Chat、#Settings 等分别控制 LuluAppearanceCatalog.scopes 对应的页面。
- 主题变量：--paper、--card、--card-strong、--ink、--muted、--border、--accent、--on-accent、--soft；支持 background-color、color、font-family、color-scheme、border-radius。
- 颜色格式：#RRGGBB 与 #RRGGBBAA；长度：dp 数值。
- 当前已在 Compose 真控件接入 #Home .launcher（桌面图标）、#Home .companion（桌面伴侣卡）、#Chat .bubble（聊天气泡）的 offset-x、offset-y、left、top、padding。
- 背景图使用系统选择器导入的本地 `asset://` 路径。可用 `background-size: cover | contain | stretch` 和 `background-position` 调整铺法与对齐。
- 通用页面区域和已接入通用装饰的原生控件支持 `linear-gradient(角度, 色值, 色值)`、`border: 1dp solid #颜色`、四角圆角和 `box-shadow: 横向偏移 纵向偏移 模糊 颜色`。渐变色标等间距，可写 2–8 个。Android 8.0/8.1 的硬件路径阴影显示为无模糊的偏移阴影。

## 聊天气泡和贴纸

在装修工坊选「聊天 · 对话」或「聊天 · 群聊」，可以单独选角色、自己或两边，导入气泡底图、底层装饰或贴纸。草稿会即时显示在预览里，应用后进入真实对话。文字与语音转写使用 `.bubble`、`.bubble-role` 和 `.bubble-user`；播放条使用 `.bubble-voice`。

`::before` 画在底图上、文字下方，`::after` 画在文字上方。两层都可以超出气泡边缘，可以用作蕾丝、蝴蝶结、气泡尾巴或四角小图。装饰使用 `width`、`height`、`left`、`top`、`right`、`bottom` 定位，支持 dp 和百分比，并可设 `rotate: -10deg`、`opacity: 0.8`。两层默认各 28dp，分别对齐顶部起点与顶部终点，且不占用排版或触控区域。用 `content: none` 可以关闭继承下来的装饰。

```css
#ChatDetail .bubble-role {
  background-image: linear-gradient(135deg, #fff9fc, #f7e1eb);
  color: #513d4d;
  border: 1dp solid #e5b4c8;
  border-radius: 7dp 20dp 20dp 20dp;
  box-shadow: 0 3dp 8dp #a7668338;
  padding-inline: 14dp;
}
#ChatDetail .bubble-role::after {
  content: "";
  background-color: #e8a6bf;
  width: 9dp;
  height: 9dp;
  right: -3dp;
  top: -4dp;
  rotate: 45deg;
}
```

把示例的 `background-color` 换成工坊导入的透明图片，就能使用自选贴纸。要用整张气泡框图片，可设置 `background-size: stretch`，再用 `padding-inline` 和 `padding-block` 给文本留空。

示例 CSS：

    :root {
      --paper: #fff9f1;
      --ink: #2c2730;
      --accent: #9f5e52;
      --on-accent: #ffffff;
      font-family: serif;
      border-radius: 16dp;
    }
    #Chat {
      --paper: #f6f0fa;
    }
    #Home .launcher {
      offset-x: 4dp;
      offset-y: -3dp;
    }
    #Chat .bubble {
      offset-x: 6dp;
    }

## 数据、布局与安全

源码：data/LuluCssRuntime.kt（CSS 解析、校验、存档、私有资产）、design/LuluCssDecor.kt（Compose 修饰符与背景渲染）、design/LuluAppearanceTheme.kt（Material3 颜色和字体注入）、LuluAppearanceScreen.kt（编辑与导入）、LuluMigrationRootAppV2.kt（各页面作用域），桌面和消息组件接入命名位置钩子。

草稿只有点击「应用到页面」后才覆盖原存档；导入失败不会破坏已保存的主题。导出 CSS 当前不附带图片，因此其他手机需重新选择素材。普通页面预览展示配色与背景，聊天页另有即时气泡预览。

**尚未完成**：所有独立界面任意元素的拖动/尺寸/层级控制、素材打包分发、所见即所得的真机实时结构编辑。尤其 Canvas 游戏、Live2D 原生绘制、输入法与系统栏不能直接按 Web CSS 重排。扩展时必须逐页面挂载原生修饰符，并确认手势命中、滚动、返回和恢复能力。

## 验收

已添加 CSS 作用域、颜色、无网络图片、格式校验、原预设移除的单元测试。还需检查最新 main push 的完整 Kotlin 编译、JVM 回归与 APK，以及 360dp/412dp、字体放大、桌面拖拽/聊天长按/背景图/进程重启/异常素材等 Redmi 真机路径。工程 CI 通过也不等于真机视觉效果已完成。

参考仓库的 MOTIONSITES_NATIVE_UI_GUIDE.md。本轮读取 MotionSites 上游主 SKILL、案例索引、craft 和 review；具体页面美术依然以用户导入的素材和用户写的 CSS 为准。
