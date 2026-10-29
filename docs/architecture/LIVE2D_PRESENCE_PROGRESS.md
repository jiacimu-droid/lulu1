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
- **提交**：[d561907](https://github.com/jiacimu-droid/lulu1/commit/d561907529949b05bee907ad3c2944fac7a231e7)、[04387b6](https://github.com/jiacimu-droid/lulu1/commit/04387b6eeb6a68243c55c4f55011df6fd74fbe41)、[1dad673](https://github.com/jiacimu-droid/lulu1/commit/1dad673fa6a5b7cec63e392e154b2e7336d27665)（VM Promise 回归按实际 event-loop flush，已做本地最小异步竞争 smoke）。均在现有 main，无分支/PR/Issue。
- **验证分层**：源码及回归已提交；本地最小异步竞争 smoke 通过。前三个中间提交的 APK run 因 main 后续提交而 cancelled，不能算成功。合并本轮修复后的 [Lulu APK #40842109101](https://github.com/jiacimu-droid/lulu1/actions/runs/40842109101) 对应 `5431c09e`（另含并行内驱专项变更），在本次核对时 **Live2D JS 构建/测试步骤已 success，Android JVM/assemblePhone/收集/发布仍在运行，不能宣称全部通过**。原有 [代码索引 #40842109009](https://github.com/jiacimu-droid/lulu1/actions/runs/40842109009) 已 success；本次仅改 `tools/live2d/`（现有代码索引生成器统计 app/scripts/preview；工具源码不在该索引文件表中），不手工伪造 `CODE_INDEX.md`。真机动作未验。

## 待真机验收

同一回复至少连续五句（含两次同名 `continue`、已完成后重播、两种不同 Motion）；分别以 15/30 FPS 模拟负载，核对第二/三句可见动作、开始与 TTS 首音的实际差距、前一动作尚在加载时新选择不被取消。再测打断/重开、触摸与讲话并发、模型切换/校准复用、WebGL 与 Android 实际送达 FPS/P95、发热和不同 TTS 服务。只看 JS 函数调用或 APK 编译不算视觉通过。

下一轮应先读取最新 main、对应 CI 与手机诊断，再选一个可证实的 L1–L3 断点；不要重新实现已存在的 speech/视觉总线。

## 2026-10-29 17:40 续修：旧 TTS 回调抢占新一句动作（L1/L2，CI/真机待验）

- **基线再核对**：先读完整 2026-10-29 Library 大纲、AGENTS、统一人物契约、成长框架、CODE_INDEX、10-28 连续性计划及本专项进度；从最新 main 的实际 stage / Android `Live2DSpeechTimeline` / `Live2DCallStage` / `conversation-performance.mjs` 确认已有真实 Motion3、逐句锚点、首音启动、音频进度与触摸所有权。未另建逐句系统或额外模型轮次。其他专项最新在处理居民日程、通话唤醒与独立动作，未改其公共 Store/ToolRouter。
- **真实缺口/根因**：Android 已把 `visualSequence + segmentIndex + segmentId` 随真实 TTS 回调送给 JS，但 stage 原本对 `finished/interrupted` 无条件清空当前 `playing`、score 和待发 Motion。旧片段迟到的完成/打断可取消新片段，尤其新决策已经到达而旧语音尚在播放时；此时 `perform` 还会把旧语音的 `playing=true` 误当成新句已开声，提前播放新句顶层 Motion3/表情。旧 `playing` 也可能倒退到前一个 segment。不是模型没有挑 Motion。
- **实际修复**：`tools/live2d/src/stage.js` 在开始回调拒绝比当前音频/模型 score 更旧的版本或倒退片段；结束/打断只接受与当前已开声的三元身份完全一致的事件。若旧音频结束而新 score 等待首音，仅释放旧嘴型，保留新决定已选的 Motion3、表情和拍点。新决定与旧音频不一致时，顶层 Motion/表情/姿态也进入原 `pendingSpeechParts`，直到新句真实 onStarted 才启动。旧无版本语音仍在全零版本时沿原入口工作；不新增 Store、定时器、LLM 请求或程序语义猜测。
- **回归**：`tools/live2d/test/conversation-performance.mjs` 在真实 stage 函数 VM 上模拟旧音频播放→新句决定→旧 interrupted→新句首音→旧 finished/playing 迟到→新句有效 finished；另测旧正常 finished→新句首音。断言新动作首音前不启动、旧回调不能清掉新 score/动作、错 segmentId 不能结束新句。代码提交 [a450736](https://github.com/jiacimu-droid/lulu1/commit/a4507367f1ea62e8011f95851d6ae8049ffef6b0)、回归 [b6f28ba](https://github.com/jiacimu-droid/lulu1/commit/b6f28ba0ddaa8b3cdab49ff63a26f4954b3eaff7)、断言校正 [41f3462](https://github.com/jiacimu-droid/lulu1/commit/41f346239865f892db8e608a9e6e29a39d81e360)，均直接 main。
- **验收层级**：提交与源码接线已完成；本轮末次 [Lulu APK #40851748381](https://github.com/jiacimu-droid/lulu1/actions/runs/40851748381) 对应 `41f3462`，截至记录时仍 pending，**不等于 JS 测试、Android JVM、assemblePhone、收集或 Release 已通过**。只修改 `tools/live2d` 和本进度文档；既有 `scripts/generate_code_index.py` 仅索引 `app/scripts/preview`，本轮未改变其索引集合，不能手工伪造 CODE_INDEX。此前 main 的 [APK #40846157083](https://github.com/jiacimu-droid/lulu1/actions/runs/40846157083) 失败点是居民 `ResidentDailyContinuityTest.dailyPlanPreservesChosenFriendshipAndFurnitureWithoutExecutingThem`，归基础专项处理，不能误报为 Live2D 编译失败。
- **真机待验**：旧语音被打断后立刻说新句、快速连读至少五句、同名 Motion continue、旧 finished/playing 迟到、15/30 FPS、触摸与说话并发，测实际首音/动作开始差、FPS/P95 和发热。没有设备视频证据前仍为「源码已改、真机未验」，不宣称动作自然度已达标。

### 17:46 CI 修正与阶段性证据

- 初次回归提交 `41f3462` 的 [APK #40851748381](https://github.com/jiacimu-droid/lulu1/actions/runs/40851748381) 在 Live2D JS 测试阶段失败，**不是生产逻辑断言失败**：VM 真实 stage 的 `let scoreVisualSequence/audioVisualSequence` 是词法绑定，不是 `bridge` 对象属性；测试误读得到 `undefined`。已改用 `vm.runInContext(...)` 读取真实 stage 词法状态，修正提交 [d11e400](https://github.com/jiacimu-droid/lulu1/commit/d11e400d523860ca976fde8d35b885504746d823)。
- [Lulu APK #40852044628](https://github.com/jiacimu-droid/lulu1/actions/runs/40852044628) 对应 `d11e400` 的 **Build bundled Live2D stage 已完成 success**，覆盖现有 JS 测试（含本轮新回归）及打包；截至更新时 Android `Test and build phone APK` 仍在运行，收集/发布 pending，不能算 Android/APK 通过。先前 `#40851748381` 的失败不可冒充成功。
- 这轮只动本专项的 `tools/live2d/src/stage.js`、`tools/live2d/test/conversation-performance.mjs` 和独立进度文档。未改变索引生成器所覆盖的 app/scripts/preview 文件；不写假索引。真机仍待测。
