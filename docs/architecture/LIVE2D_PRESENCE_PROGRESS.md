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

## 2026-10-29 18:48：已结束 TTS 片段的迟到开声不得复活（L1/L2）

- **真实根因**：此前仅用当前 `audioVisualSequence/audioSegmentIndex` 拦截旧 `playing`；`finished/interrupted` 会将它们清零，导致同一旧片段的迟到 `onStarted` 被当成新的合法开声。正常结束后可能重新张嘴、重启已完成的拍点；打断后同轮后续片段也可能复活。即使同一 sequence/index，错误的 segmentId 仍可改写当前音频身份，令真正的结束回调被拒绝。
- **修复**：`tools/live2d/src/stage.js` 在真实结束/打断时只记最小的已终止音频身份；完成片段不可重开，打断后的同一轮不可补播，旧轮迟到回调不可越过新轮。对带明确 `segmentId` 的逐句计划校验 `onStarted` 归属；同序号/索引但不同 ID 的迟到事件不能抢占当前语音。模型重新加载清空版本，隐藏时废弃的当前 score 标为终止；旧无版本语音继续原路径。不更改角色 Motion 选择、TTS 请求、Store 或人物主循环。
- **回归**：在现有真实 stage VM 回归中新增错误片段 ID、同段迟到 start、结束后重开、打断后下一段迟到，以及新轮正常开声。仅触及 `tools/live2d/src/stage.js`、`tools/live2d/test/conversation-performance.mjs` 和本专项文档；未改变现有索引生成器覆盖的 `app/scripts/preview` 集合，不手工改索引。
- **验证边界**：提交时为源码与回归待 CI；必须再查 Live2D JS 测试、Android 单测、assemblePhone、APK 收集/发布。真机 15/30 FPS、打断后重说、音画同步与触摸并发仍待独立验收。

## 2026-10-29 19:53：隐藏视频后旧 TTS 开声复活（L1/L2，CI/真机待验）

- **重新核对**：完整阅读 10-29 Library 大纲、AGENTS、CODE_INDEX、UNIFIED_CHARACTER_CONTRACT、COMPANION_GROWTH_FRAMEWORK、最新 10-28 连续性计划及本专项进度；核对最新 main、社交专项进度、既有 v6605 发布与现行 stage/TTS/Android 入口。已有逐句 Motion3、首音事件、迟到完成/打断门禁及 native 帧回压，均未重建。L3 真机流畅度与校准仍属已有待验；词级时间戳仍是可选增强。
- **可复现根因**：`setPaused(true)` 在取消舞台 score 时先把 `scoreVisualSequence` 清为 0，随后才尝试把它记录到 `finalizedAudio`。因此隐藏时的取消墓碑分支永远不执行。真实 Android WebView 隐藏/显示时，迟到的 TTS `playing` 回调可以重新占用语音时钟，甚至使原本已丢弃的动作复活；新旧音频交接时同时需要覆盖当前 audible sequence 和已排队的新 score。
- **实际源码**：`tools/live2d/src/stage.js` 在清零前读取 `Math.max(scoreVisualSequence, audioVisualSequence)`，为隐藏时已取消的整轮建立 terminal 标记；隐藏状态直接忽略后续 onStarted（包括未知新序号），不把后台事件伪装成可见舞台动作。恢复后旧序号仍被拒绝，真正新一轮角色决策和 TTS 首音可正常启动。只维护时序事实，不改角色选择、Store、ToolRouter、额外模型调用或物理/表情所有权。
- **回归**：`tools/live2d/test/conversation-performance.mjs` 用真实 stage 函数模拟旧音频播放→新 score 排队→隐藏→旧/排队/未来回调迟到→显示→旧回调拒绝→新 score 真实首音恢复；断言 terminal 序号、零旧 Motion3 及新 Motion3 正常启动。实现提交 [2741e15](https://github.com/jiacimu-droid/lulu1/commit/2741e1554ffffe272f8dddacfab21bb9bcb85d29)、[0a775a2](https://github.com/jiacimu-droid/lulu1/commit/0a775a215977c3d81196b099d57b91aa98ea191e)，回归提交 [0002cd7](https://github.com/jiacimu-droid/lulu1/commit/0002cd72f003e08ee586f7f1dd4c66b3bfcdeff4)。
- **分层验收**：提交时 [Lulu APK #40869070191](https://github.com/jiacimu-droid/lulu1/actions/runs/40869070191) 对应 `0002cd7` 仍为 pending，**不能声称 JS 测试、Android JVM、assemblePhone、收集或发布通过**。仅修改 `tools/live2d/` 和本进度文档；既有索引生成器只扫描 `app/scripts/preview`，此轮未改变被索引的文件，不手工制造 CODE_INDEX。真机隐藏/恢复、连续五句、触摸与语音并发、15/30FPS、实际首音延迟与发热仍待验。

### 19:58 回归环境修正与 CI 分层

- 首次 [#40869070191](https://github.com/jiacimu-droid/lulu1/actions/runs/40869070191) 的 JS 失败来自回归 VM 仍把 `setPaused` 留为旧 mock，未执行实际 stage 函数，错误地读到上一轮 `finalizedAudio=3101`；[6e2aee1](https://github.com/jiacimu-droid/lulu1/commit/6e2aee1d165192566d55bf3a405075b76242e662) 已改成真实函数。第二次 [#40869265166](https://github.com/jiacimu-droid/lulu1/actions/runs/40869265166) 的 JS 失败是 VM 缺少不相关的触摸取消依赖 `cancelTactileMotion`，不是生产触摸路径失败；[3b75a94](https://github.com/jiacimu-droid/lulu1/commit/3b75a945f059b27f847a583a0b572379b65a05eb) 仅补该依赖的 mock，真实 `setPaused` 与 `onPlaybackEvent` 均保持生产函数。
- [Lulu APK #40869350440](https://github.com/jiacimu-droid/lulu1/actions/runs/40869350440) 对应 `3b75a94`：**Build bundled Live2D stage 已 completed/success**（包含实际 stage 生命周期新回归和打包）。核对时 Android JVM/assemblePhone 仍 pending，APK 收集与发布尚未通过；不得把 JS 成功冒充整包成功。真机仍未验。

## 2026-10-29 20:43：隐藏阶段粗粒度语音状态复活待发动作（L1/L2）

- **核对**：重读 Library 大纲、AGENTS、CODE_INDEX、统一人物契约、成长框架、10-28 清单、最新 main 和社交专项；v6605 的逐句 Motion/TTS、原生帧回压及模型校准已有，不重建。
- **根因**：此前隐藏时虽清除 `scoredBeats` 和版本号，却留下 `pendingSpeechParts`/`pendingExpressionName`。恢复可见后旧粗 `update(speaking=true)` 可以绕过精确 TTS 终止门禁，激活已作废的 Motion3/表情；隐藏时 `perform` 也能重新排队。
- **修复**：`tools/live2d/src/stage.js` 统一取消顶层待发动作与表情，隐藏时拒绝新 `perform`，`activatePendingSpeechMoment` 不消费暂停阶段队列；新一轮可见 TTS 首音仍可正常启动。不增加模型调用或状态中心。
- **回归**：`tools/live2d/test/conversation-performance.mjs` 使用实际 stage VM 验证旧音频→新句排队→隐藏→粗状态迟到→重新显示→真正新句开声。仅修改本专项文件，现有索引生成器不扫描 `tools/live2d`，不手写 CODE_INDEX。
- **验收边界**：本提交只是源码与回归，待 GitHub Actions 的 JS 测试、Android JVM、assemblePhone、APK 收集/发布核验；真机隐藏/恢复、低 FPS、触摸并发和音画同步未验。

## 2026-10-29 22:29：粗 speaking 快照抢占真实 TTS 片段所有权（L1/L2）

- **复核与分类**：完整读取 10-29 Library 大纲、AGENTS、CODE_INDEX、统一人物契约、成长框架、10-28 连续性计划及本专项进度，核对最新 main/并行专项与 v6605 基线。真实 Motion3、逐句 TTS ID/首音、已终止片段墓碑、隐藏取消及触摸参数分层均已有，不重建；15/30FPS、校准与自然度仍属真机待验；词级时间戳是可选增强。
- **证实的真实缺口**：Android `Live2DCallStage` 用独立 Compose effect 发送粗 `update(speaking)` 与精确 `onPlaybackEvent`，到达顺序不保证。stage 的 `update` 原本无条件重写 `playing`，可在首音前提前张嘴、真正说话中被迟到 false 关闭，或完成/打断后由迟到 true 复活；连续句在旧 `playing=true` 时收到下一段精确 started 还会跳过唇部时间重置。这是本地时序所有权错误，不是角色没选择动作。
- **实现**：仅在 `tools/live2d/src/stage.js` 增加最小的版本化语音所有权门禁：有明确 `visualSequence` 的表演由实际 onStarted/finished/interrupted 控制 `playing`，粗状态仅更新 PCM/倾听；完成、打断及隐藏后的迟到快照不再抢权。新显式无版本 cue/无版本 started 仍可恢复旧供应商的粗状态。真正换段时刷新开声时间，重复同段回调不重播。未改模型选择、人物主循环、Store、TTS API 或付费轮次。
- **回归**：现有 `tools/live2d/test/conversation-performance.mjs` 的真实 stage VM 新增首音前粗 true、播放中粗 false、结束/打断后粗 true、连续下一段开始时间以及显式 legacy cue 兼容断言。仅修改 `tools/live2d/` 和本专项进度；索引生成器覆盖 `app/scripts/preview`，本轮不改其集合，故不伪造索引。
- **验证分层**：提交时源码与回归已落 main；CI 的 Live2D JS、Android JVM、assemblePhone、收集/发布须以对应 SHA 运行结果另行核验；真机 15/30FPS、音画首音差、模型切换与发热均未验。

### 22:34 CI 静态契约同步

- 本轮首个 [APK #40892527191](https://github.com/jiacimu-droid/lulu1/actions/runs/40892527191) 的真实 `conversation-performance.mjs` 已通过，但后续 `parameters.mjs` 仍断言旧源码必须包含 `if (playing) { activatePendingSpeechMoment() }`，与新增“只有同一片段重复回调才跳过开声”事实冲突，JS 阶段失败，Android 未执行。已同步旧静态断言到新的 `newAudioFragment` 门禁，并新增精确 TTS 所有权断言；未放宽动态回归。需以后续对应 SHA 的 CI 成功为准，真机仍待验。


## 2026-10-30 00:35 · L2：通话倾听状态从 Android 贯通到实际模型姿态

- **最新 main 与职责核对**：已复读 Library 大纲、AGENTS、CODE_INDEX、人物契约、成长框架、10-28 连续性计划、本专项与基础/内驱/社交进度。v6605 逐句 Motion3/真实 TTS 首音、迟到回调版本门禁、触摸与表情所有权均已有，不重复搭建。L1 的连续五句和 L3 低 FPS 真机自然度仍是已有待验；任意词级对齐为可选增强。
- **证实根因**：Android `Live2DCallStage` 已将 `listening` 通过 `update` 送至 `stage.js`，stage 也保存该值，但最终绘制 `visualLayers.update` 从未收到/读取它，导致用户说话时模型和无输入时姿态完全相同。这里的 `listening` 仅代表通话麦克风倾听，不代表识别到用户说话、情绪或角色正在思考。
- **正式链路**：`Live2DCallStage → stage.update(listening) → applyFinalParameters → createVisualLayerRuntime.update`。只在麦克风倾听、角色没有正在发声且没有本人 Motion3/表情/显式头身脸动作或触摸时，提供幅度克制的抬头/上身注意姿态；沿已有帧率无关弹簧平滑进入与释放，保持正视，不做随机语义动作。角色本人 Motion3、触摸和表情拥有优先权；不新增角色状态、LLM 调用或定时器。
- **根因回归**：`impact-recovery.mjs` 新增实际 final actuator 的 15/30/60 FPS 比较，断言倾听与 idle 不再相同、说话不叠倾听姿态、真实 Motion3/本人显式姿态/触摸不被盖掉；`parameters.mjs` 检查正式 stage 的倾听参数接线。仅修改 `tools/live2d/` 和本专项进度文档；原索引生成器扫描 `app/scripts/preview`，本次没有索引集合变化，不手写 CODE_INDEX。
- **验收分层**：本提交是正式源码和根因回归，不等于已在 CI 运行。后续需核对该 SHA 的 Live2D JS、Android JVM、assemblePhone、APK 发布。真机仍需看倾听→角色说话→触摸→打断、不同 TTS、15/30 FPS 及实际音画差/发热。思考阶段没有可靠独立状态信号，本轮不冒充已实现“思考表演”。

## 2026-10-30 02:10 · L2：真实模型等待阶段的视线与姿态接线（源码/回归提交，CI 与真机待验）

- **基线核对**：完整 10-29 Library 大纲、AGENTS、CODE_INDEX、统一人物契约、成长框架、10-28 连续性计划及基础/社交/内驱独立进度均已复读；核对最新 main。v6605 的真实逐句 Motion3、TTS 首音、打断墓碑、触摸优先级与上轮 `listening` 末端姿态已经存在，不重复创建。L1 五句及 L3 低 FPS 真机表现仍属已有待验；词级时间戳为可选增强。
- **已证实缺口**：原 `LuluVoiceCallState.thinking` 在电话会话里是真实模型正在生成回复的状态，但 `Live2DFullscreenVideoCall → Live2DCallStage → stage.update → visualLayers.update` 仅传 `listening` 与 `speaking`，未传 `thinking`；用户说完后模型正在处理的间隔仍是普通待机，不具备 L2 的思考过渡。不能根据麦克风声音推断人物情绪。
- **完整修复**：原通话 `state.connected && state.thinking` 传到现有 WebView 状态更新，再交最终 Cubism actuator。只在非倾听、非发声、无本人 Motion3/表情/明确姿态/触摸时轻微移开视线、调整头与上身；真实开声、倾听、触摸或本人动作立即夺回优先权，原弹簧自然收回。不生成新 Motion/表情、不引入新状态中心、额外模型轮次、常态轮询或猜测语义。预览/诊断沿可选参数默认 false，行为不变。
- **根因回归**：现有 `conversation-performance.mjs` 实际执行 stage.update；`impact-recovery.mjs` 对 15/30/60 FPS 的思考→倾听/开声优先级、真实 Motion3 和触摸覆盖做最终 Cubism 参数断言；`parameters.mjs` 核对真实 Kotlin call-state → WebView JSON → JS final actuator 接线。索引仍交给原 `Refresh code index` 工作流，不手工伪造 `CODE_INDEX.md`。
- **分层验收**：源码和回归提交不代表测试已执行；需按对应提交核对 Live2D JS、Android JVM、assemblePhone、APK 收集/发布。真机连续五句、真实等待→接话、低 FPS、触摸并发与发热均未验。


## 2026-10-30 04:20 · L2/L3：视频隐藏期间真实 TTS 结束后嘴型租约未释放

- **核对基线**：完整阅读 10-29 Library 大纲、AGENTS、CODE_INDEX、统一人物契约、成长框架、10-28 连续性计划与本专项进度，并检查最新 main、基础/社交/内驱专项和 v6605 逐句 Motion/TTS 既有实现。已验通的历史层级仅限相应旧 CI；本次 L1 真实 Motion3、分句 TTS 开声与终止墓碑、L2 倾听/思考最终姿态已接线，不重复创建。连续五句自然度、低 FPS、校准和不同 TTS 的手机体验仍为已有待验；词级时间戳为可选增强。
- **已证实根因**：`stage.js.setPaused(true)` 将 `audioVisualSequence/segmentId` 清零，却保留 `playing=true`；隐藏期间真正的 `finished/interrupted` 因身份不匹配被丢弃，恢复可见时还调用 `performanceRuntime.startSpeech()`，令已经停止的语音继续张嘴。独立 Compose `update(speaking=true)` 在隐藏时仍可修改旧版粗状态。此前隐藏取消只验 Motion 不复活，没有验真实音频结束后的嘴型租约。
- **正式修复**：只在 `tools/live2d/src/stage.js` 保留隐藏前**确实开声**的版本化音频身份用于接收真实终止回调，取消待发 score/Motion/表情与 PCM；隐藏终止只释放 speaking/口型，不执行旧表情，不降低被取消的新 score 的版本墓碑。显示后若原音频仍在播放，仅允许继续当前 PCM 口型，不恢复旧 choreography；真正完成后不再张嘴。隐藏阶段粗状态不抢权，未版本化旧服务在隐藏时释放粗 speaking，回到可见后仍兼容新粗状态。没有新 Store、额外模型调用、固定动作配额或主循环改动。
- **根因回归**：在原 `tools/live2d/test/conversation-performance.mjs` 的真实 stage VM 增加隐藏中旧音频完成、隐藏后完成、待发新 score 墓碑不可降级、粗 speaking/PCM 不复活、显示后仍在播的 PCM 可继续、旧版粗状态兼容；保留此前连续片段与打断门禁测试。正式源码提交 [337042b](https://github.com/jiacimu-droid/lulu1/commit/337042bfe2442049ee79d89fc2f385eb62079470)，回归提交 [b0103e3](https://github.com/jiacimu-droid/lulu1/commit/b0103e364ef24cce6ce188f599edf817ebff2d25)，直接 main，未改其他专项文件。
- **索引与验证边界**：原 `scripts/generate_code_index.py` 只遍历 `app/scripts/preview`，本轮仅改 `tools/live2d` 与本进度文档，不改变索引文件集合，故不手工伪造 CODE_INDEX。回归提交对应原 [Lulu APK #40948163462](https://github.com/jiacimu-droid/lulu1/actions/runs/40948163462) 初查为 pending；JS 测试、Android JVM、assemblePhone、收集/发布必须继续以对应 SHA 的实际状态核验，不能宣称通过。真机隐藏→语音结束→恢复、隐藏时继续播放、15/30 FPS、首音/动作延迟、发热和不同语音服务仍未验。


### 04:24 CI 实测：Live2D JS 成功，Android Kotlin 编译被其他专项阻断

- [Lulu APK #40948163462](https://github.com/jiacimu-droid/lulu1/actions/runs/40948163462)（回归提交 `b0103e3`，job `123709185127`）实际 **completed/failure**：`Build bundled Live2D stage` **completed/success**，覆盖本轮真实 stage VM 测试；`Test and build phone APK` **completed/failure**，日志明确为 `app/src/main/java/com/jiacimu/lulu/data/CompanionOnlineStore.kt:410:26` 的 Kotlin `Boolean?` 传给 `Boolean` 参数。Android JVM/assemblePhone/收集/Release 因编译阻断**未通过**，不能将 JS 成功写成整包成功。该文件不属于 Live2D 专项，本轮没有跨职责抢改公共 Store；需由基础执行专项处理并在后续包含本提交的 main 构建重新核验。
- 真实手机上的视频隐藏、语音播放/结束、恢复嘴型及动作自然度仍未验；若新 CI 被后续 main 取消，也不能用取消状态证明通过。


## 2026-10-30 06:50 · L1：旧 Motion3 异步加载失败不能冒充新一句的播放失败

- **四类核对**：已有验通为原逐句 Motion3/首音版本门禁的既有 JS 回归，以及截至开工时最新 `0da72e45` 的 [Lulu APK #40965646539](https://github.com/jiacimu-droid/lulu1/actions/runs/40965646539) 完整 success（含 Live2D JS、Android 测试/构建、APK 收集和发布）；已有待验为连续五句、触摸与真实语音并发、模型校准复用、15/30 FPS 与真机音画/发热；本轮真实缺口是 Motion3 迟到的**拒绝**回执；可选增强为词级时间戳。v6605 已有逐句模型选择与真实 Motion3，不重复实现。
- **根因**：`tools/live2d/src/stage.js` 的 Motion3 异步成功路径已校验 `motionRevision` 与模型归属，但 Promise `catch` 不校验，旧句/旧触摸的异步加载在新句已接管后拒绝，仍发送 `motion-failed` 和“播放失败”诊断。该消息不是当前句的真实播放结果，不能因旧加载失败判断新角色动作失败。
- **正式修复**：在原 `motion()` 的异步失败路径核对修订号、当前模型实例和当前选中的动作对象。只有仍拥有播放槽位的真实失败才清除 `authoredMotion` 并发出原有 `motion-failed`；被新句、触摸或模型切换取代的迟到失败直接忽略。保留成功回执、真失败回执和原本的 Cubism 优先级；不增加模型调用、语义规则、Store 或轮询。
- **根因回归**：原 `tools/live2d/test/touch-hit.mjs` 的真实 stage `motion()` VM，增加旧加载 reject→新句 Motion 仍 pending/可启动、旧失败无错误回执/诊断、当前加载 reject→仍有一次准确 `motion-failed` 的测试；继续保留同名 Motion continue、低帧率与触摸的既有断言。源码 [c29cd80](https://github.com/jiacimu-droid/lulu1/commit/c29cd80b10fac52b31567f5e37dcc217bd7a261b)，回归 [f34dde0](https://github.com/jiacimu-droid/lulu1/commit/f34dde0543251320c45e2dacceb3a6f8ecc49eb4)，均直接 main。只修改 `tools/live2d` 两个文件及本专项进度；`scripts/generate_code_index.py` 索引 `app/scripts/preview`，本轮无索引集合变化，不手写 `CODE_INDEX.md`。
- **分层验收**：源码和回归已提交 main；对应新提交的 JS、Android JVM、assemblePhone、收集/Release 必须另查实际 CI。真机连续五句、真实异步加载失败及不同设备 FPS/温度仍未验，不能用测试源码替代设备证据。


### 06:55 实际 CI / APK 核验

- 本轮回归提交 `f34dde0` 对应 [Lulu APK #40968894322](https://github.com/jiacimu-droid/lulu1/actions/runs/40968894322)：原 `Build bundled Live2D stage` 已 **completed/success**，工作流实际执行 `node tools/live2d/test/parameters.mjs`、`node tools/live2d/test/touch-hit.mjs` 和 renderer 打包；因此本轮迟到 reject 的真实 stage VM 回归已在 CI 运行通过，而不只是提交测试文件。末次查询 `Test and build phone APK` 尚未 completed，Android JVM、assemblePhone、APK 收集/发布仍不能宣称成功。
- 同轮开工基线 [Lulu APK #40965646539](https://github.com/jiacimu-droid/lulu1/actions/runs/40965646539) 已完成所有 build/test/collect/publish 步骤 success，但其提交早于本轮代码，只能证明旧基线可构建。原索引工作流 [#40968872611](https://github.com/jiacimu-droid/lulu1/actions/runs/40968872611) 已 success；本轮仅触及不在原索引覆盖范围的 `tools/live2d` 和专项文档，索引由既有生成器管理。
- 真机仍未验：连续五句真实 Motion3、迟到失败与新句并发、触摸、不同 TTS、模型校准、15/30 FPS 和发热。不能把 JS VM 的正确回执归属冒充人物可见动作的实际自然度。


## 2026-10-30 08:51 · L3：迟到 exp3 异步失败不得清除新一句同名表情

- **四类核对**：v6605 已有逐句 Motion3、真实 TTS 片段首音、表情与嘴型分层，最近 L1 Motion3 迟到失败门禁已由 [APK #40968894322](https://github.com/jiacimu-droid/lulu1/actions/runs/40968894322) completed/success 验证；不重复开发。已有待验为真机五句、触摸/语音并发、模型校准、15/30 FPS 与发热。真实缺口是 exp3 异步失败的所有权；词级时间戳仍属可选增强。
- **真实根因**：`tools/live2d/src/stage.js` 的 `playVerifiedSpeechExpression` 在同一模型上异步调用 `model.expression(name)`。前一句 `star` 尚在加载时，后一句可再次选择 `star`；前一句的 Promise 迟到 reject，旧 `catch` 仅比较字符串 `activeExpressionName === name`，便错误调用 `resetSpeechExpression()` 清除后一句的真实 exp3 及眼部独占。同步抛错还发生在 `Promise.resolve` 之外，可能直接中断原 render/score 回调。
- **正式修复**：仅在原 stage 的 exp3 播放与复位入口增加模型实例+表达租约修订号；新选择/复位自动使旧请求失效。旧 resolve/reject 不再报告当前成功/失败或复位新表情；当前真正失败仍报告并释放眼部独占；同步抛错走同一有归属的失败回执。保留同名选择、Cubism 原表情管理器、原 Motion3 优先级和嘴型/触摸门禁，不增加模型请求、语义推断或额外状态中心。
- **根因回归**：原 `tools/live2d/test/conversation-performance.mjs` 的真实 stage VM 覆盖“同名旧 exp3 延迟 reject → 新表情保留/管理器不复位/无旧失败诊断”、“当前 exp3 reject → 释放眼型并报告”以及“同步抛错 → 不泄漏表情租约”；其余 Motion3/TTS/触摸/低帧率测试保持原样。测试源码不是执行通过的证据。
- **交付与验证边界**：本轮只修改 `tools/live2d/src/stage.js`、`tools/live2d/test/conversation-performance.mjs` 和本专项进度。现有 `scripts/generate_code_index.py` 仅覆盖 app/scripts/preview；本轮不触及索引输入，`CODE_INDEX.md` 继续由既有工作流维护，不手工伪造。对应本提交的 Live2D JS、Android JVM、assemblePhone、APK 发布需以实际 CI 结果另验；真机视觉连贯性与 exp3 资源加载竞争未验。
