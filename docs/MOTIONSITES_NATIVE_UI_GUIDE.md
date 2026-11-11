# 露露机原生 App 美化：MotionSites 长期参考与跨窗口执行协议

> **目的**：主人已明确要求：今后任何窗口、任何代理给 `jiacimu-droid/lulu1` 做“美化 / UI 重设计 / 页面高级感 / 美术统一 / 视觉精修”，**必须主动重新阅读 MotionSites 这个开源项目**，再落实到露露机的真实 Android 页面。不能凭本次聊天印象、通用圆角卡片、随机渐变或一张想象效果图敷衍；不要要求主人在新窗口重复提供链接。
>
> **执行入口**：仓库根 `AGENTS.md` §6 → 本文件 → `docs/DESIGN_SYSTEM.md` → 当前源码/实际界面。此规则跨窗口持续生效，除非主人明确调整。
>
> **性质**：设计参考与验证流程，不是已经安装的 SDK、自动生效的主题库、现成 Android 组件库，也不是授权移植网站素材。MotionSites 是网页设计技能项目；页面实现继续使用项目自己的 Jetpack Compose / Canvas 等正式技术栈。

## 1. 固定收藏：项目地址和必读路径

- **原开源仓库**：[Motionsites-AI/claude-skill](https://github.com/Motionsites-AI/claude-skill)
- **技能主文件（每次必须读）**：[MotionSites Web Builder SKILL.md](https://github.com/Motionsites-AI/claude-skill/blob/main/plugins/motionsites-web-builder/skills/motionsites-web-builder/SKILL.md)
- **案例目录（每次必须查，选 2–3 个真正相关的案例全文）**：[examples/INDEX.md](https://github.com/Motionsites-AI/claude-skill/blob/main/plugins/motionsites-web-builder/skills/motionsites-web-builder/examples/INDEX.md)
- **设计品味 / 排版密度 / 色彩节制（每次必须读）**：[references/craft.md](https://github.com/Motionsites-AI/claude-skill/blob/main/plugins/motionsites-web-builder/skills/motionsites-web-builder/references/craft.md)
- **成品截图和返工评分规则（每次必须读）**：[references/review.md](https://github.com/Motionsites-AI/claude-skill/blob/main/plugins/motionsites-web-builder/skills/motionsites-web-builder/references/review.md)
- 按需：`references/spec-template.md`（定规格）、`references/assets.md`（素材）、`references/techniques/INDEX.md`（视觉表现与动效技巧），以最新仓库路径为准。
- 已核对的上游参考版本：`faa30d512e3973e58675c72101a7ef42ed37ae83`（2026-11-11）。**正常每次先查 main 最新版**，版本链接只作文件挪动/删除时的溯源兜底，不能拿旧快照当永远最新规范。

**不可省略“去项目里读”的步骤**：本文件是长期入口与落地规约，不是代替未来读取上游原文。如果网络或 GitHub 访问不可用，改用本文件及 `docs/DESIGN_SYSTEM.md` 已有规则继续做能完成的部分，并在验收中标明“上游本轮未能重新核对”；不得声称已读最新 MotionSites。

## 2. 每次美化的原生 App 工作步骤

1. **查看真实页面，不臆测**：先读 `AGENTS.md`、代码索引、正式路由、现有 Compose 文件、弹窗、组件与既有外观方案。辨认屏幕上真正的主要内容与主要任务；已有主人认可的配色/角色画风/交互不能被覆盖。
2. **打开 MotionSites 源项目**：读主 SKILL、craft、review 和 examples/INDEX；针对这个任务的用途、信息密度和氛围选 **2–3 个不同的高质量参考案例**，实际阅读案例全文。提炼 *为何好看*（构图、视觉重心、字体比例、层级、色彩关系、主视觉与空白、交互反馈），而不是抄 HTML、影片或品牌。
3. **做场景专属的设计规格**：写清该页的“三个气质词”、独特视觉记忆点、内容优先级、真实文案、尺寸/字阶/行距/留白/触控尺寸/组件状态/动效节奏与响应小屏策略。**同一设计系统 ≠ 所有页面套同一个模版**。
4. **改真正的 App 页面并联动弹窗**：按当前 MaterialTheme/`LuluAppearanceTheme` 和 Compose 组件实际改代码，包含主页、二级页、弹层、按钮、加载/失败/空状态。保留模型、角色决策、存档和用户已选外观。禁止用单张概念图冒充实际产品交付。
5. **看截图、批评、返工**：先真实构建/运行，在 360dp、412dp 宽度，普通与放大字体、正常/空/加载/失败、深色/现有自定义主题、键盘与系统安全区检查；条件允许时拍改前/改后截图。对照 MotionSites 的 review 思想逐项给出缺点，**必须至少修掉最弱的三个具体问题**，而不是第一次写完直接结束。
6. **按证据交付**：注明改动页面、真正的源码路径/提交、APK/JVM CI、截图是否亲眼检查、尚需真机验证什么。编译绿 ≠ 视觉绿；设计样稿 ≠ 源码已改；上游参考 ≠ 引入项目依赖。

## 3. 视觉与场景的差异化原则

| 场景 | 真正的主视觉 / 第一优先 | 不要做什么 |
| --- | --- | --- |
| 私聊、群聊、朋友圈 | 角色头像、消息节奏、真实图片与关系状态 | 大而空的 banner、长解释、用动效阻断输入 |
| 番外、小剧场、阅读 | 作品标题、故事正文、字体节奏与沉浸感 | 把模型/世界书/生成解释堆成设置表单 |
| 自序、所思/所行/所愿 | 私人日记/任务本身；保留各子页已批准的纸本风格 | 强行全部改成黑白“网页作品集” |
| 数字世界、见面 | 人物和可互动生活场景，真实位置与行为 | 大量无意义悬浮说明盖住角色 |
| 方块小岛 | 清新柔和、简约漂亮的自然场景和真实建造成果 | 简陋铺满游戏卡牌/廉价贴图、控件遮画面 |
| 摸金 | 清冷克制的珠宝展陈，让**稀有且漂亮的宝物**成为绝对焦点 | 霓虹赛博噪点、满屏小道具、假装开箱的动画 |
| 设置、模型、存档、记忆 | 快速找到功能、信息层级和可理解的权限状态 | 卡片一层套一层、无信息的大字标题 |

桌面一般遵守 `docs/DESIGN_SYSTEM.md` 的白灰基底与独立主题；沉浸式游戏不被普通工具页的白底规范约束。所有页面用统一质量标准，但采用各自的视觉语言。对低端手机尤其检查输入法、重组/帧率、耗电和动画中断。

## 4. 美术与外部素材边界

- MotionSites 的网页案例是**灵感与设计手法参考**，不是可直接复制成露露机 UI 的布局图或可直接打包的免费媒体库。不要照抄受版权保护的品牌、角色、影片、图像或完整页面；第三方素材引入前按 `docs/PROVENANCE.md` 复核来源与许可。
- 若需要原创插画或场景，先与当前页面统一美术风格，再用已有授权工具制作并**实际接入仓库资源**；只展示效果图不算完成。原生 2D 游戏要以可玩性、物理交互和真实结果为核心，不用视觉素材掩盖未完成的游戏逻辑。
- 不把 MotionSites 提示词中的商业推广内容加入露露机，也不把 web-only 的 GSAP/Three.js/鼠标 hover 生硬加到 Android。

## 5. 快速验收清单（跨窗口交接必须逐项核对）

- [ ] 已重新打开 MotionSites 主 SKILL、案例 INDEX、craft、review，读过 2–3 个与本页适配的真实案例（或明确记录访问阻塞）。
- [ ] 已查看现有路由和真实页面，保留原功能、数据、既有视觉偏好。
- [ ] 已写清本页设计目标、视觉重心、具体排版/色彩/尺寸与适配方式。
- [ ] 已改原生代码及相关弹窗/状态，不用假图片冒充代码。
- [ ] 已核对真实构建与测试；截图/预览进行了“找三处问题再修”复盘（或明确列为待真机）。
- [ ] 已在原有 UI-04/相关任务留下真实提交、工程/真机验收分层和下一步，不新建重复大纲。

**一句话长期约定**：以后主人说“把露露机这个页面美化一下”，默认触发本协议：**先去 MotionSites 里找适配参考，再按她认可的风格精修实际 App，最后看图返工**；不是靠新窗口代理随手发挥。
