# Live2D 面对面表现专项进度（L1–L3）

更新：2026-10-29。仅负责真实 Motion 逐句编排、实际 TTS 同步、倾听/停顿/打断、触摸/表情/嘴型/物理协调、模型库校准复用与低帧率。遵守 `AGENTS.md`、统一人物契约及 2026-10-29 大纲；不另建人物状态或付费模型循环。

## 基线与分类

| 目标 | 2026-10-29 源码证据 | 分类 |
|---|---|---|
| L1 逐句 Motion | `Live2DDialoguePlan` 的 speech id/text/motion/transition → `Live2DVisualActionBus` 的真实模型 Motion3 资源检查 → `LuluVoiceCallSession` 逐段 TTS → `Live2DSpeechTimeline` → `Live2DCallStage` → `tools/live2d/src/stage.js`。`Live2DSpokenMotionContract` 漏项最多一次元数据补正，不重复做整轮聊天。v6605 的原工作流已完整成功。 | **已有源码且 CI 验通，真机待验**；不能重复从零做一套 |
| L2 非说话状态/打断 | stage 已有 listening、speaking、真实 utterance started/finished/interrupted 与 visualSequence 门禁，PCM 嘴型独立；有 `Live2DConversationPerformance` 与本地身体/视线过渡。 | **已有待验**：真实倾听、思考、停顿、打断后动作是否自然、是否抢播旧片段 |
| L3 触摸/模型/帧率 | 触摸 hit/校准、实际 Motion3、触摸/眼型/PCM 参数分层、WebGL/JPEG/Native 兼容显示、FPS/P95 诊断、模型库选择均已有正式入口。 | **已有待验**：15/30FPS、触摸时讲话、模型切换、低端设备发热与真实显示帧率 |
| 任意词级时间戳、任意 Motion 关键帧冻结 | 现有协议仅保证真实 TTS 片段及播放进度；未取得跨 TTS 提供商词级时间戳，`continue` 不是无限冻结姿态。 | **可选增强**，不假称已经实现 |

基线：`10bb37bfeb620b554c5983d8e16024e2f5ab2327` 的 [Lulu APK #40833765696](https://github.com/jiacimu-droid/lulu1/actions/runs/40833765696) 已完成成功并发布 [v6605](https://github.com/jiacimu-droid/lulu1/releases/tag/lulu-v6605)。它是**编译/单测/发布证据，不是真机动作验收**。本轮开始时其他专项正在修改社交、内驱与聊天提交；本专项不碰其共享 ToolRouter、Store、人物决策网关。

## 2026-10-29 本轮真实缺口：连续句 Motion3 的异步/低帧率竞争

- **根因一**：stage 的 `motion(..., transition='continue')` 只比较上次保存的 model/group/index。前一段真实 Motion3 已经播放完、但低 FPS 的下一次 render tick 尚未清除 `authoredMotion` 时，下一句被错误认为仍在播放，直接跳过，出现“第二/三句站桩”。触摸触发的同名动作也可能被误认成角色本人本句的选择。
- **根因二**：旧 Motion3 的异步加载 Promise 在新选择之后才返回时，原回调仅看新动作是否 started 和 group/index 是否相同；如果新动作尚在加载且恰好同名，`stopAllMotions()` 会把**新**动作一起停掉。这是播放权归属/版本竞争，不是模型没有选择 Motion。
- **源码修复**：`tools/live2d/src/stage.js` 的 continue 仅在同一来源的同一动作**仍在等待或播放中**时复用；检测到 Cubism `isFinished()===true` 则允许下一句重新开播。迟到的旧 Promise 只有在**没有任何新动作接管**且当前管理器仍显示旧资源时才可 stop，不再凭相同 group/index 杀掉后继选择。角色仍自行选择 Motion；程序只维护实际播放生命周期和取消版本。
- **回归**：`tools/live2d/test/touch-hit.mjs` 用真实 stage `motion()` 函数和可控异步模型，覆盖 pending continue 不重复加载、已结束片段重新播放、同名旧 Promise 不杀新选择、当前动作 continue、触摸来源不冒充角色、无后继时旧动作仍可停止。
- **提交**：[d561907](https://github.com/jiacimu-droid/lulu1/commit/d561907529949b05bee907ad3c2944fac7a231e7)、[04387b6](https://github.com/jiacimu-droid/lulu1/commit/04387b6eeb6a68243c55c4f55011df6fd74fbe41)（现有 main，无分支/PR/Issue）。
- **验证分层**：源码及回归已提交；本轮提交后的 [Lulu APK #40841896554](https://github.com/jiacimu-droid/lulu1/actions/runs/40841896554) 在写入本记录时仍为 pending，**尚不能宣称通过**。本次仅改 `tools/live2d/`（现有代码索引生成器统计 app/scripts/preview；工具源码不在该索引的文件表中），不手工伪造 `CODE_INDEX.md`。若索引工作流对后续 main 自动刷新，以工作流真实结果为准。

## 待真机验收

同一回复至少连续五句（含两次同名 `continue`、已完成后重播、两种不同 Motion）；分别以 15/30 FPS 模拟负载，核对第二/三句可见动作、开始与 TTS 首音的实际差距、前一动作尚在加载时新选择不被取消。再测打断/重开、触摸与讲话并发、模型切换/校准复用、WebGL 与 Android 实际送达 FPS/P95、发热和不同 TTS 服务。只看 JS 函数调用或 APK 编译不算视觉通过。

下一轮应先读取最新 main、对应 CI 与手机诊断，再选一个可证实的 L1–L3 断点；不要重新实现已存在的 speech/视觉总线。
