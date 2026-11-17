# 游戏／见面网页先审后迁移：同源预览契约（2026-11-17）

> 主人明确要求：网页上看到的游戏／见面画面，要与正式 App 的画面一致；以后先在网页修改、试玩，主人接受后才合并进 App，尽量避免“每看一版就安装新 APK”。
>
> **当前尚未达标。** `preview/play.html` 是 2026-11-17 临时单独编写的 HTML/JS Canvas 原型。正式 Android 游戏和见面由原生 Compose/Canvas 绘制，并维护权威 Kotlin 规则、状态、角色决策和持久化。不能将这个原型冒充 App 镜像，更不能把演示局的行为写入角色事实。

## 实际绘制源（优先级高于旧网页）

| 场景 | 正式画面源 | 真实状态／动作 |
| --- | --- | --- |
| 方块小岛 | `app/src/main/java/com/jiacimu/lulu/games/BlockIslandScreen.kt`、`BlockIslandArt.kt` | `BlockIslandRules.kt`、`LuluGameStore` 和实际角色行动 |
| 迷雾摸金 | `app/src/main/java/com/jiacimu/lulu/games/RelicEscapeScreen.kt`、`RelicEscapeArt.kt` 等 | `RelicEscapeRules`、藏宝与队伍存档 |
| 见面 | `app/src/main/java/com/jiacimu/lulu/DigitalWorldMeetingSceneExperience.kt`、`DigitalWorldGameScene.kt` | `MeetingExperienceStore`、真实角色位置、接触同意和统一对话链 |
| 其他小游戏 | `app/src/main/java/com/jiacimu/lulu/games/` 下各正式 Screen | 各自 Kotlin 规则／正式存档 |

## 2026-11-17 · 素材共用网页原型进展（非 Android 同源已完成）

- 首批提交 `e0ef4b6`：新增 `preview/world-core.js` 单一浏览器内核（场景、格网、移动、碰撞、路径与简单试验规则），`worlds.js` 的同一摄像机和 Kenney CC0 PNG 图集渲染，`worlds.html` 可切换见面／小岛／摸金，测试 8 项在本地 Node 通过；PNG 二进制已纳入仓库。
- 后续增量保留见面边移动边离线留言，保持试玩同伴不声称真实 AI；`preview/index.html` 与旧 `play.html` 的三张场景卡转向本素材页，其他小游戏保留旧玩法。
- **不得宣称已经一模一样**：当前 Android 游戏规则、存档、真实角色模型与画面都还没有通过共享 WebView/协议接入；这是第一版统一风格和共享底座的**网页视觉可玩样稿**，主人同意之前不替换原生画面。后续先把同一场景数据映射到 Android 权威状态，再接 Kotlin↔WebView动作／回执桥及相同图集，实测视觉差异与 FPS，才算达到“一份界面两端使用”。

## 当前可执行的视觉对照基线

`preview/compare.html`：在手机浏览器打开，选择游戏，导入**正式 App 当前截图**，以相同尺寸显示 `preview/play.html` 作为比较对象；可裁掉状态栏，并用透明叠加找出差异。文件仅在浏览器本地读取，不上传服务器。

此工具能检查颜色、地图、布局、字阶、控制按钮、安全区等**视觉差异**，**不能**单独证明交互、玩法、模型行为、游戏存档一致。截图未提供或未检查时，状态必须为“未完成原生对照”。浏览器 Canvas 的测试结果只属于浏览器。

## 目标架构：一份正式界面，不做两套“看起来相似”的页面

1. **先锁定原生基准。** 固定与正式 App 同一场景的种子／存档快照、人物位置、视角、屏幕分辨率和状态（普通／弹窗／失败／放大字体等），保存 Android 真正渲染出的画面供对照，不能用网页模拟图伪装基准。
2. **建立真正的共用绘制源。** 两种候选技术：A. 将目标场景的 UI／Canvas 渲染迁移为共享 Web 资源，并在 Android 的 WebView 和手机浏览器消费同一份文件；B. 经过专项构建可行性核实后，将 Compose 界面拆成跨 Android/Web 共用的 Kotlin Multiplatform 渲染模块。现有 Kotlin Compose 绘图**不能**直接在普通 HTML 页面运行。不得仅复制颜色配置/贴图就叫“共用”。
3. **保留权威规则在 App。** 若选择共享 WebView：Android 侧使用受约束的双向动作桥，负责读取既有 Kotlin 状态、执行合法行动、确认结果并回传；网页侧用**同一结构的测试夹具**驱动 UI。不能在 JS 再造一份正式掉率／怪物回合／角色主动性、不会在浏览器把虚拟人物动作写成真人经历。
4. **按垂直场景迁移，不一口气替换所有游戏。** 先一个地图（建议方块小岛）做到原画面与网页在同一宽高、同一视角下的主视觉、HUD、弹层、动作动画对齐；再完成触摸、真实动作和存档链；然后迁移迷雾摸金和见面。旧 Compose 页面保留作回退，直至新路径验通。
5. **以后实行网页先验收。** 先调整**共用的**界面源，网页以固定测试快照即时试玩，主人确认；变更合并后相同资源进入 Android，CI 核打包及真实 Kotlin 行动，再用 Redmi 一次性复核系统导航、语音、帧耗时和权限。网页批准≠安卓平台全部通过，不冒称零 APK 验收。

## 明确不能复用／不能伪造

- 现在的 `preview/play.html` 尚不是共用绘制源，不能以它的颜色、美术或局内数值覆盖主人已在 Android 确认的样式和规则。
- 不能通过本地模拟人物台词声称已调用真实角色模型；不能将网页存档、动作或虚假胜负写入正式聊天、长期记忆或游戏档案。
- 网页和 App 的最终像素会受到字体渲染、系统安全区和屏幕密度影响。若验收标准为严格“像素完全一致”，必须使用同一渲染器、固定字库／资源与视口，并持续做实际截图差异测试。**双独立引擎无法凭文字承诺完全一致**。
- 如果只有网页验证、没有 Android 对照／桥接／持久化，应明确标“浏览器独立演示”，不能标“App 同版”。

## 下一可实施代码包

- **先方块小岛 G-02**：从正式 `BlockIslandScreen.kt`、`BlockIslandArt.kt` 和一份权威 `BlockIslandState` 快照开始，把页面层和状态投影拆出受测试的共同协议，确定共享渲染技术，保留旧场景作回退；逐帧对照 HUD、地图、按钮及施工状态。
- **再迷雾摸金 G-03**：复用已确定的绘制与输入适配方式，但不跨游戏复制宝物/回合代码。
- **见面 W-01**：使用同一视觉协议承接人物坐标、接触同意、移动与持续会话，不能靠假 NPC 演出代替真实 AI 决策。
- 当前工具和文档 **只是原生对照工作流的首步**，不能宣称上述共享渲染迁移已完成。

继续按 `AGENTS.md`、`LULU_EXPERIENCE_ROADMAP.md` §二、`COOP_GAME_SCENE_PROGRESS.md` 及 `docs/MOTIONSITES_NATIVE_UI_GUIDE.md` 的现有分工验收，不另造人物/游戏事实中心。
