# 合作游戏沉浸场景 · 2026-10-30

### G-02/G-03 · 游戏不是电话轮流答话：独立目标、低费用局内节拍与玩家插话改主意（源码/回归已提交；当前 JVM/APK 和 Redmi 待验）

- **根因**：小岛 `IslandTravel.advance`、`IslandHouseProjects.advance` 与摸金 `RelicCoopTravel.advance` 之前只在主人主动消耗游戏回合时推进；角色虽然通过同一人物 Game 模型选定采木、施工、寻宝、支援或送宝，却会在玩家停手时卡住。游戏不能照搬电话式 A→B 一句一句等人指令，也不能改成每隔几秒购买一次模型推理。
- **共用真实机制**：`GameCoopPacing.kt` 由角色本人已选且仍有效的游戏计划触发 6 秒局内物理节拍，双方真实规则新增 `companion_tick`，不替玩家移动、不伪造玩家 `wait`、不额外发奖；仅在游戏前台、手柄未连续移动、模型未思考、主人没有正在说话且玩家附近无即时危险时推进。各角色计划独立，不因甲已选择建房阻止乙判断；行动结束/失败后由原模型看真实新状态决定下一目标；新用户发言可打断原计划进入同一角色模型，是否改主意由本人决定。角色倒地救援计划例外允许继续走真实救援路线。跨回合行动不每格购买模型调用，模型自然选`none`则不强制行动。
- **语音和画面**：两款游戏现在复用电话已有可选 STT/VAD 持续开麦、本人语音 TTS、播报时暂停录音以降低回声；小岛沿原角色坐标显示正式发言头顶气泡，摸金用底部中央克制的电影字幕条，不盖住地图战斗与战利品。两者队伍详情都改成按需打开的对话弹窗；横屏游戏动作区保持固定。均不另建影子人格/历史。`IslandActorPlay.kt`/`RelicActorPlay.kt` 提示词补合作游戏节奏、角色主动目标和玩家话语可改变想法但非强制指令。小岛入口支持暂停自主；摸金按现有队伍与实际阶段调度。
- **真实提交与回归**：核心源码 `0c183ca`、`346ad57`、`2408418`、`d86705e`、`ab05358`、`c52e630`、`482755d`、`d00ee4d`、`43275ad`、`893cf2e`、`d2ca20f`、`4ee4c81`、`735b9f9`、`dd307a8`、`cfc541e`；`GameCoopPacingTest.kt` 由 `0c83cd3` 和 `443d392` 覆盖无计划不推进、已选择路线真实逐步执行、取消计划、濒危暂停与玩家倒地救援例外。旧 CI `43732105642` 在 `GameSceneDeck.kt:220` 外层 `maxWidth` 隐式接收者引用处失败，修复 `88c6954` 将父布局值捕获后传入内部列；必须以包含此修复的后继成功流水线验收，不可拿旧红灯替代。
- **同伴数量与费用门禁补正**：真实核查发现「甲的路线在本地推进一格」会提高游戏回合数，从而让尚无计划的乙再买一次 Game 模型；连续 6 秒一次就变成严重计费风险。生产 `009c4a2`、`24d9be0`、`5b844ea`、`f207dc8` 在两款独立队伍适配器保存「上一次纯伙伴节拍 / 哪些人物的旧目标真的已结束」；只允许目标完成的本人或收到了新主人发言的人重新决策，其他人不因甲走路被唤醒。纯判定与回归 `696d461` 覆盖两角色、计划继续、本人结束和新主人讲话；`companion_tick` 始终本地执行，不读模型。这一门禁本轮仍待 Kotlin/JVM/APK 实际通过，不以源码测试存在冒充跑绿。
- **仍待**：核最终 main push `testPhoneUnitTest`／`assemblePhone` 与新增测试，失败按首错修；Redmi 实测开麦→用户真实发言→本人模型改目标→原地合法施工/找箱→头像/字幕/音色，双游戏连麦同时移动、横竖屏 360/412dp、TTS 回声和 API 费用、重进存档、退出/转后台不偷跑。编译通过不等于真机可玩。

### 2026-11-16 · G-02.6 · 游戏实时语音草稿不再冒充人物头顶正式发言（源码提交，CI/真机待验）

- **根因**：正式 `IslandCompanionPanel` 将 `IslandGameOpenMic.partial` 的流式临时转写直接送到 `onDialogue("player", ...)`，而同一回调也用于玩家人物头顶字幕；VAD 后续可能修改/丢弃草稿，导致地图人物头顶显示并未确认说过的话。原面板已能独立展示临时转写，不必复制进世界空间。
- **生产修复**：`716275e` 只移除 provisional partial→world-space dialogue 的错误投影，保留麦克风面板实时草稿；`ProviderCallInput.onText` 确认后仍通过 `submitPlayerMessage` 进入头顶字幕、队伍消息、原时间线及角色原模型决策，不增加模型请求或虚构发言。源码 blob `e579f1559ea249f875ef24bef9454becdcf827c6`；本轮未新增测试（需 Compose/真实 STT 生命周期及手机屏幕验收）。
- **验收与下一入口**：本轮无当前 SHA 的 JVM/APK/index 成功证据；检查 `IslandCompanionPanel.kt` 和 `IslandGameOpenMic.kt`：连续语音草稿在面板更新，头顶字幕仅在完整 `onText` 后出现；取消/静音草稿不得留下虚假发言。Redmi 360/412dp、TTS 回声、帧率、真实模型仍待验。后续继续核最新 main push 的 APK 首错，勿把索引绿当 APK 绿。



### 2026-11-15 · G-02 · 玩家横屏实测反馈：通话式交流、人物头顶字幕及顶部状态（源码已提交；APK/真机待验）

- **反馈与根因**：横屏菜单从 TopEnd 4dp 绘制，但 LuluGamesApp 同区宿主按钮占顶部 40dp，造成叠压；物资曾被压成六个狭窄卡；角色语音和发言藏在需主动展开的队伍面板，原麦克风只提供一次性 Android 识别。主人要求边通话边玩，持续开麦、角色用音色回应、角色头顶显示其实际发言，常用材料进顶部状态，菜单不再常驻横屏侧栏。
- **生产改动**：5c67659、b8ca467、fb2b93b 固定顶部宿主安全区及回归；2a75271、9600236、9bfdca5 将生命、资源放在顶部并去掉重复资源卡；483f3f3、9a32d37 将小岛的管理菜单改为按需顶部下拉、合作语音放入横屏底部中央（竖屏避开顶栏）；f844ab6 增加 IslandGameOpenMic，玩家手动开麦后复用电话已有 ProviderCallInput 的选定 STT/VAD（支持 ElevenLabs Scribe），将真实完整发言送给原 IslandActorPlay.decide，未新增逐帧付费模型。9e22dfc、dac16f3 保持本人已选音色 TTS，播放时暂停收音防止回声，音色被关闭时显式告知。06c42a6、091e474、a635e6e、a326325、a84c93d 在正式 BlockIslandCanvas 人物坐标上显示角色和玩家本人头顶气泡，长句轮播分页，不另建人物事实 Store；原始发言仍进 GameTeamConversationTimeline。90a8916、28c48b9 新增字幕分行及完整分页回归。
- **验证与边界**：源码已提交 main；本轮密集提交使前几个 push 的 Lulu APK run 被后继取消，最新主线完整 JVM/APK success 与 Redmi 实机仍未取得。下一轮须验证横竖屏/放大字体、开麦权限→所选 Scribe→角色模型决策→小人字幕→本人音色、菜单不遮挡返回与按钮、持续移动时能交流、TTS 回声与退出页面停止录音，并看费用计数。持续麦克风只在玩家主动开麦和游戏前台工作，不是后台电话或免费无限识别。

### 2026-11-15 · G-03.5 · 真实玩家反馈：宝箱不是连续点击，镜头可以主动观察（源码/回归已提交，CI/真机待验）

- **证实的体验断点**：过去宝箱虽经 `RelicChestPresentation.playerAction` 逐次执行 `pry/open` 和真实回合、掉落，但主键、中心键、战术入口直接“按几下开箱”，最后仅小卡片定时消失；地图镜头只能自动追随/定位，无触控平移或缩放。
- **玩家正式路径**：`3182897` 新增 `RelicChestInspection` 全屏检视：拖动观察 2.5D 箱体，必须从锁舌把手开始、向右完成一次滑动才在原 `RelicEscapeLedger` 提交一次真实回合，未完成手势不会消耗回合；旁边保留小字号无障碍备用操作。`c121804`、`2b1a102` 把快捷键、摇杆中心、战术开箱统一改为进入此页，箱子真实打开后由旧 `RelicChestPresentation.committed` 校验入包/落地，只展示已经提交的实际宝物。
- **画面与镜头**：`d95f8b6`、`135ee9f`、`c11e1ee` 为俯视遗迹地图增加拖动/捏合缩放与可见时的镜头复位，渲染和点选共用同一相机投影，不移动人物、不消耗回合；箱体检视可左右旋转的是本地 2.5D 展示，**不是伪称已实现真 3D 自由镜头**。`1d0010f`、`4e7ac1f` 将开出实物后的原 360dp 小弹窗变为全屏展厅，玩家可以转动鉴赏，不再倒计时自动关闭；保留回执与撤离结算的真实事实。
- **验证**：`cf3097d` 新增滑动门槛测试，`10e8311` 扩展镜头平移/缩放触点反投影回归，`58a2096` 更新新展厅的视口尺寸测试，`ab253cd` 收紧锁舌操作必须从把手开始。所有提交在现有 main；尚无覆盖最终代码的 `Lulu APK` 完整 success、360/412dp 横屏截图、Redmi 触控和真实战斗开箱实测，不能宣称美术已验通。下一轮检查新 HEAD 的 `testPhoneUnitTest` / `assemblePhone`，并真机验轨道滑动、旋转与镜头双指缩放是否互抢手势，敌人回合是否恰好只推进一次，自动完成后不能重开刷战利品。

### 2026-11-15 · 角色总独玩快艇骰子／不主动约人：能力上下文与居民日程打通（源码/回归已提交，CI/真实模型待验）

- **根因核查**：`AutonomousGameCatalog` 和 `playAutonomousGame` 实际已接通记忆配对、快艇骰子、方块小岛、迷雾摸金四款真实角色独玩引擎；但主动感知的 `AutonomousAffordanceContext`、世界社交即兴决策的 `AutonomousSocialRuntime` 仍以旧文案告诉模型只有前两款能独玩，让角色持续偏向骰子。居民每日规划的 `residentAgendaActions`、动作示例又遗漏虽已注册的 `invite_duel_game` / `respond_duel_invite`，造成看得见邀请能力却不能真正安排未来对局；最近个人游戏筛选将 AI 与 AI 双人记录（`playedWithCharacter=false`）误归类为独玩。
- **生产修复**：`d0aa9eb`、`fb96214` 将四款动态权威 ID、独玩真实规则、主人六款有效邀约、数字熟人两种双人对战工具同时呈现在同一角色决策中，不强制轮换或替角色决定去邀请谁；`b819faf`、`e9c7385` 更新社交即兴游戏选择与合法 JSON 示例；`d2acf02` 使居民每日/到点决策与计划真正允许邀请/应邀，传递目标人物 ID，验证游戏类型、原文、对方邀请 ID 与接受/拒绝，仍交给实时权限和真实收件人决定；`f07cbea`、`799d57b` 修复原自主感知的个人独玩记录投影，区分独玩、人物对战和主人同玩。
- **回归**：`9f77363`、`05a89ae` 覆盖四款独玩能力与邀请工具、个人游戏历史不混入双方对战；`c38cf6a` 覆盖居民能计划并校验人物邀约/应邀及目标 ID 保留。相关源码与测试均在正式 `main`。连接器 `fetch_commit_workflow_runs` 不返回 main push CI、combined status 无 checks；**尚无本轮 APK/JVM 成功、Redmi 真机和真实模型行为验收**，不能将模型日后一定换游戏当作已证事实。
- **功能边界及验收入口**：人物间自主对战当前仅 `yacht_dice` 和 `memory_match` 的**两人局**，收到实际邀请后对方自行应邀才真实结算；并非任意 3 人以上组局或六款全部支持自动人物比赛。主人可收到六款游戏馆邀约，角色四款独玩；若用户需要三个以上 AI 同局，需独立扩展多人对局规则和参加者确认。下一步核包含 `05a89ae` 的 main push `Lulu APK/JVM` 对应 `AutonomousAffordanceContextTest`、`ResidentDailyPolicyTest` 首错，再在实际角色/居民模型中核游戏选择、真实好友邀约→对方应邀→双方成绩/记忆与去重；不因示例而规定机械轮流或刷邀请。


### 2026-11-15 · G-03.5 · 地图点选与摇杆中心互动一致（源码/回归已提交，CI/真机待验）

- **根因**：正式地图点击相邻宝箱后，中心互动仍只读旧摇杆朝向；若朝向另一只箱，玩家点选目标与真实撬锁对象不一致。既有 `relicQuickAction` 已具备合法目标与物理动作判定，不另建规则。
- **本轮**：`7822e74` 在 `RelicQuickAction.kt` 让已选中的合法近距离实体互动优先，远距离/空地不越权；`09a12c0` 将 `RelicEscapeScreen.kt` 的真实 `selected` 接入中心键；`b95ffdc` 新增双宝箱不同朝向、真实撬锁只推进选中箱、远处选择回退的回归。源码 blob `e14d19ad`、`637136b8`；测试 blob `abf5c3a9`。不改变掉率、实体结算、模型自主性或长按摇杆纯移动。
- **CI 与下一入口**：`09a12c0` 对应 main push Lulu APK [#43655340356](https://github.com/jiacimu-droid/lulu1/actions/runs/43655340356) 查询时 pending；`b95ffdc` 尚无可确认的 JVM/APK/index 成功证据。下一轮先查该测试 `centerInteractionHonorsTappedNearbyChestBeforeJoystickFacing` 的 JVM 首错，再在 Redmi 横屏检查点击左侧箱→中心互动不误撬右侧箱、触控/遮挡/FPS；真机与真实模型未验。

### 2026-11-14 · G-02/G-03 · 原世界游戏语音和队伍对话接入统一时间线（源码及回归已提交，CI/真机待验）

- **现状核验**：快艇骰子有真实结算入 `SharedExperienceTimeline`，小岛和摸金有邀请/开局/部分执行及结算证据；但 `IslandCompanionPanel` / `RelicCompanionPanel` 的玩家队伍发言及角色回应以前仅进局内临时状态，`CharacterAndScenario` 游戏模型不会自动召回普通 `Full` 上下文的近期时间线。现阶段麦克风是 `RecognizerIntent` 按次发言，不是免按键全双工电话。
- **真实改动**：新增 [GameTeamConversationTimeline.kt](https://github.com/jiacimu-droid/lulu1/commit/8faaa2882535a31e0edf69048d83a4fae2c705f3)，将玩家文字/语音识别结果和模型实际选择且被场景接纳的角色发言写进**现有原始时间线**，按真实游戏存档/局 ID 归档并分别发送给在场参与者；不建立影子聊天 Store、不替角色编造话、不把语音识别当原始录音。两款局内入口接线见 [小岛](https://github.com/jiacimu-droid/lulu1/commit/bc6bd04970b225c92abc50030f031182bff32087)、[摸金](https://github.com/jiacimu-droid/lulu1/commit/3c883e8a506407b517458a7a5f1254a5e418f842)。每次原游戏模型选择读取同局该角色可见的最近八条队伍交流，沿原 `ModelUsage.Game` 一次调用，不增加第二个付费对话模型；见 [小岛事实](https://github.com/jiacimu-droid/lulu1/commit/625d7ca686bcf312fe8dc957019cfa3391f3f528)、[摸金事实](https://github.com/jiacimu-droid/lulu1/commit/34d084f9ad40bfaa4bfa5936a53c24c7182899a8)。
- **边界与回归**：[GameTeamConversationTimelineTest](https://github.com/jiacimu-droid/lulu1/commit/460093f93a68086aae6db091f3bfcdd2371b0982) 覆盖每位真实在场者都有各自来源事件、非在场者隔离、跨局隔离、玩家语音识别标记、迟到回调同 ID 不修订历史，以及非队员不能冒充发言。尚需以包含测试提交的真实 main push APK/JVM 和真机/真实游戏模型验证；本连接器无法可靠列出 main push 的 Actions 结果，**不宣称成功**。**未实现**：免按键实时双向语音、后台游戏与电话打通；本次仍为现有按麦克风说话 + 人物 TTS 发声。方块小岛没有自动将每一步场景动作写入原时间线，旅程总结仍沿既有「记录旅程」；继续按真实玩家场次验收完善，不以当前对话补丁冒充完整活动结算。

### 2026-11-14 · 即时私聊中的角色邀玩能力认知补强（源码/测试已提交；真机/真实模型待验）

- **真实反馈**：主人在江渡私聊说《迷雾摸金》时，角色回答“我这边发不了邀请”，把内置游戏误当成需要主人自己打开的外部游戏。截图只证明这一轮实际回答错误，不证明已取得原模型输出、当前安装 APK 版本或执行日志。
- **事实核查**：主分支已有 `CompanionActionRuntime.send_game_invite` → `[游戏邀约|gameId|title]` 真实私聊消息 → 接受并跳转真实游戏的路径；`GameInvitationCatalog` 支持六款。旧前台 `capabilityContext` 只提到 `send_game_invite(gameId,text)`，没有提供准确游戏名称→ID，而在线即时私聊只列通用 `game_invite/solo_game` 参数，容易使模型混淆“手机游戏/外部App”与内置游戏馆能力（属于风险点，非已证明单次推理根因）。
- **本次改动**：[c86090c](https://github.com/jiacimu-droid/lulu1/commit/c86090c21e57de8ddb4fdf55db001e32abdcb5a0) 与转义纠正 [ef61ab6](https://github.com/jiacimu-droid/lulu1/commit/ef61ab6aa7eada991ccc2621bef9605beca3ae42) 在同一 `GameInvitationCatalog` 上生成 `send_game_invite` 可执行说明及六款游戏名称/ID；[54f0b80](https://github.com/jiacimu-droid/lulu1/commit/54f0b80bca985d69b59ba26d8dbba914fb6b65d8) 让在线私聊也读取此单一能力说明，明确 `action=game_invite` 或 `steps.send_game_invite` 可生成真实卡片，角色仍自己决定要不要邀请；[0f2019c](https://github.com/jiacimu-droid/lulu1/commit/0f2019c5f49edd4095f5a01d0a1e93833376f016) 回归逐项检查六个真实 ID 一致和非口头代办。
- **验证边界/下一步**：仅已提交代码及测试；当前 commit status 未返回 checks，`fetch_commit_workflow_runs` 未返回 push runs，**尚不能称编译/CI/真机验收成功**。下一轮查覆盖 [ef61ab6](https://github.com/jiacimu-droid/lulu1/commit/ef61ab6aa7eada991ccc2621bef9605beca3ae42) 的真实 main push Actions `testPhoneUnitTest`/APK；随后 Redmi 私聊说“我们玩摸金吧”→同一角色自行选择发送《迷雾摸金》可点邀约→接受→进入同一局，读回真实回执，不得再误称“发不了邀请”。若仍失败，抓当轮模型 JSON、工具回执、安装版本，继续查真实执行和认知断点，不以追加固定话术代替。

### 2026-11-14 · G-02/G-03 · 双游戏玩家操作闭环自主检查（源码/测试已提交，CI/真机待验）

- **目标**：主人要求在两款游戏中主动发现手感缺口，以真实玩家的“进入→方向操作→互动→结果/继续”而非仅截图/文案验收；对应唯一看板 G-02.1/6 与 G-03.1/5。本轮不扩独立状态源，也不增加任何逐帧模型调用。
- **G-03 玩家真实开箱路径**：正式 `RelicEscapeScreen` 原有长按方向键反复执行 `relicQuickAction(open/altar/silence/...)`，可在摇杆持续行走时无意中把新设计的多回合箱子自动撬完。生产 [8870caf](https://github.com/jiacimu-droid/lulu1/commit/8870caf6c5faa99bb235a5aa18f310f92e3b9dfa) 将摇杆定义为纯 `relicDirectionAction("move")`，并修复角色站上宝箱/祭坛本格时中心互动失效；界面接线 [13ab308](https://github.com/jiacimu-droid/lulu1/commit/13ab308b12e334597933d449c2c76f984e14c1b2)。回归 [9133f3a](https://github.com/jiacimu-droid/lulu1/commit/9133f3a2524a429791126e2f9c33c88e26309bdc) 覆盖撞上/站上宝箱仍未被自动开，必须中心按钮主动继续撬锁；祭坛/钟楼不被长按移动直接触发。物理 `RelicEscapeRules` 的一次性战利品和角色独立游戏规则保持不变。
- **G-02 撞墙体验和回合性能**：正式 `BlockIslandScreen` 对无效方向碰撞反复 `save(island.copy(world=...notice))`，长按摇杆每195ms 有潜在多次无意义 SharedPreferences apply()；树、矿、怪、动物、倒地同伴与建筑阻挡又常不清楚该做什么。[f9575e8](https://github.com/jiacimu-droid/lulu1/commit/f9575e8beb3543dbe15cf205270e94c65acbd7ae) 提供只读 `IslandManualMovement.blockedHint`，[41d9e3f](https://github.com/jiacimu-droid/lulu1/commit/41d9e3fe7cbedf247839bba4e7d9e8621df878d5) 将阻挡提示改为 2.3 秒局内暂态，移动/选择/行动可清除，不影响存档及角色动作；救援提示按真实野果数 [4b866e5](https://github.com/jiacimu-droid/lulu1/commit/4b866e588c0601acfbb3c872249bc5e19343b247) 显示行动或缺料。回归 [29b614d](https://github.com/jiacimu-droid/lulu1/commit/29b614d021d693a204f9f25fc7d1ccef6016e217)、[ed8d5ec](https://github.com/jiacimu-droid/lulu1/commit/ed8d5ec3f9df278423ac3452e5440b6f4e5260e2) 覆盖方向无效、树林/怪物/救援/水域，提示不增回合/资源/存档值。
- **CI 首错接力**：旧 [APK/JVM #43575432235](https://github.com/jiacimu-droid/lulu1/actions/runs/43575432235) 在 Kotlin 编译后 `testPhoneUnitTest` 的 `persistedLedgerForwardsAdjacentChestCoordinatesAndNeverMintsEarlyLoot` 失败；根因：旧测试对重新开启已开箱要求整个 snapshot 对象相等，然而无效操作应显示“已开”错误文本，确实只有 `notice` 不同，金币/回合/真实实物无变化。[4395e95](https://github.com/jiacimu-droid/lulu1/commit/4395e95bbac874abeff36a2c405631a59541be5d) 改为直接验证无新增回合/战利品/掉落/仓库/事件/结算，仍要求唯一奖励，不删防重回归。**此提交后仍未有覆盖所有新增 UX 改动的 APK/JVM success 或 Redmi 横屏真实手指长按/FPS/节奏实测证据；不能宣称已验通。**
- **下一轮入口**：查最新 main 的 `RelicQuickActionTest.joystickNeverAutoPriesEvenWhenHeldTowardAChest`、`joystickNeverAutoInvokesOrDisablesLandmarks` 与 `IslandManualMovementTest.blockedWalkingExplainsAnActionWithoutModifyingWorld` 及上面防重测试的真实 `testPhoneUnitTest` 状态。若首错就修；绿色后用 Redmi 360/412dp 先试玩小岛撞怪/采矿/救援、摸金摇杆经过紫箱不自动开奖→中心按钮逐步揭晓→真实撤离。只有真机截图和实测才算美术/手感验收；继续体验推进长期资源目标与敌人威胁。


### 2026-11-14 · G-03.5 · 玩家开箱动作与随机品级断言继续修正

- 后继 APK run [#43568418919](https://github.com/jiacimu-droid/lulu1/actions/runs/43568418919) 在 3028 测试中仅 `generatedMapHasRealReachableChestsAndExitOnEverySeed` 失败（第 782 行），是旧固定种子必须包含高档箱的断言，不代表真实地图路径不可达。另一轮 `44e2791` 虽换成七档品级断言，却把换行写成字面 `\\n`，同一行 `//` 吞掉全部校验；提交 [7218b55](https://github.com/jiacimu-droid/lulu1/commit/7218b550fa962489e24e30e86f7c37166245edda) 恢复真实执行的 `grade 0..6`、`grade ↔ tier`、同 seed 一致性断言，不改概率和原奖励。
- 核心玩家交互 `RelicEscapeScreen.action` 原来临时转译 `open → pry`，但已有 `RelicQuickActionTest` 直接调用 `RelicEscapeRules.act(open)` 并断言一次开箱，完全未验新 UX。生产 [1b0c2d3](https://github.com/jiacimu-droid/lulu1/commit/1b0c2d3a670f4803b68cfd34dbcb32bda9f6f784) / [c894c35](https://github.com/jiacimu-droid/lulu1/commit/c894c35cbc1d61b8afca3ab6c3d44deaf2bd02b5) 收敛为唯一 `RelicChestPresentation.playerAction` 纯逻辑，供地图、HUD、中央摇杆复用；角色独玩和同伴动作仍不被此玩家输入适配器偷偷改写。
- [3530b4e](https://github.com/jiacimu-droid/lulu1/commit/3530b4ef640c4abe86e8bfbdb409293295fae3b0) 替换两处“一次开箱即获奖”的旧测试，检查玩家实际需要多次撬锁、没有提前获得战利品、最后才一次入包、重复点不刷；[ba461d5](https://github.com/jiacimu-droid/lulu1/commit/ba461d5734aa0c091177eb4868f013c8607765f2) 进一步通过真实 `RelicEscapeLedger`、相邻宝箱目标坐标与重建 Store 检查每轮保存、断点继续及一次性回执。
- **当前分层**：最新源码/测试已提交 `main`；本轮新的完整 APK/JVM CI 与手机横屏手感、紫→金/红/彩过程、怪物真实反击仍待核验。旧失败 run 与后继待测 SHA 不能混记。下一入口核最新运行中是否有新增 Robolectric 首错，成功后才进行 Redmi 真机逐步开箱/警戒钟楼/长局撤离的实际试玩。


### 2026-11-14 · G-03 玩家反馈：低威胁战斗／缺少互动开箱／七档宝箱与探索决策

本轮直接在 main 改真实规则、地图、HUD 和单测，不另建重复 Issue：

- 怪物曾被短剑每回合无限取消蓄力，确为规则缺陷：现在普通怪仅首次短剑打断；精英仍会反击。新图提高敌人基础 HP、血条同步，护甲怪大幅削弱廉价短剑而保留长矛射程和弩弓穿甲/弹药取舍（`RelicEscapeRules.kt`、`RelicArsenal.kt`、`RelicEscapeWorld.kt`）。
- 新探险的实际物理宝箱生成普通/精良/稀有/珍贵紫/史诗金/传说红/传世幻彩七档，数值 `RelicQuality` 唯一源；品级决定真实鉴定价值与 `RelicLoot.grade` 保存值，独立确定性低概率从紫升金、红、彩。旧存档 `grade=-1` 保持原三层实体类型及原概率/历史，避免读档破坏。颜色统一用于地图、手册、营地与藏宝馆（`RelicEscapeRules.kt`、`RelicEscapeArt.kt`、`RelicTreasureGallery.kt`、`RelicEscapeLobby.kt`、`RelicEscapeScreen.kt`）。
- 玩家实操 `open` 经现有 Screen/ledger 路由转为多次 `pry`：每次撬锁推进真实回合、触发敌方行动并持久记 `chestProgress`；到阶段中点才按真正 seeded 结果逐档显光（紫→金→红→彩可能连续显现），最后一次才执行原有一次性物品、弩箭及藏宝馆结算；直接开箱的 AI/旧规则接口保留，不虚构阶段物品。地图内宝箱下有真实进度条，HUD/D-pad 显示当前档位和撬锁步骤。关键修复 `RelicEscapeLedger.perform` 的 x/y 转发白名单同时接受 `pry`、`silence`，否则场景按钮会失败。
- 探索不再只有找箱：新图布置可见、可真拆的警戒钟楼 `alarm`；玩家可消耗一套工具关闭，或省下工具冒险继续搜贵箱；未拆时从第二箱起，打开较高级箱可能唤出真正可战斗的追兵。警报、潜行/陷阱、分段撬锁、危险撤离形成风险取舍，不自动强迫玩家走固定路线。角色陪玩仍复用原权威 `RelicRun`，不增加独立收费模型回合。
- 回归已新增在 `RelicEscapeRulesTest`：七档价格/保存、紫色升阶 deterministic、不重掷、逐次撬锁与恢复/重复结算、短剑不能无限定身、钟楼有实体怪追击及拆除状态持久。**当前只有源码与测试提交，无新 HEAD 完整 APK/JVM success、真机触控或 360/412dp 截图验收；不可宣称所有难度已经平衡。** MotionSites 主 SKILL 已读，examples/INDEX、craft、review 本轮外网读取不可用，视觉验收仍须补真机截图审图。
- 本轮代表提交：`35649dc`（规则/持久品级），`5172858`（新图和血量），`ccfa851`（用户撬锁），`d0b8a82`/`89b596f`（警戒钟楼），`0af7cb1`（真实坐标传递），`577d6d7`/`269cfcde`/`a8ef034`（阶梯变光与美术），`6faf6c1`（回归）；所有主线增量已在现有 main。
- 下一入口：查本轮或其后继最新 main 的 Android/JVM/索引真实 CI 首错；如编译问题先修，并在 Redmi 真机依次验收紫箱中途变色、怪物贴身攻击/格挡/躲避、警戒塔拆除/不拆追兵、武器差异与长局续存。后续再依据真实体验补更多独特房间机关，而不是把地图全变成箱子。


### 2026-11-14 · 角色游戏邀请交互与共同游玩状态（源码和测试已提交；CI、真机待验）

- 核查已有链路：角色主动 send_game_invite → 私聊游戏邀约卡 → 玩家点击 → 根据 gameId 进入真实游戏；当前目录六款：方块小岛、迷雾摸金、深海回声、海龟汤、快艇骰子、记忆配对。原入口已存在，本轮不重复造第二套。
- 6cd19cbb9e8569de2270b9008d70fd989f6bdb6a：QqChatMessageComponents.kt 移除固定 86dp 黑色大标题，改为紧凑随主题卡片；显示邀请人、权威游戏名、原邀请语和 48dp 接受并进入按钮，文字超长安全截断；只有游戏馆目录里的 ID 可生成有效卡，防止未知 ID 跳入游戏馆或旧消息伪装标题。
- 955fda2969da1236470a6c917c49368069e2ce46：QqStyleChatDetailScreen.kt 接受前验证真实事件成功，进入前从 selectCharacter 改为 selectCharacters(listOf(inviterId))；后者会开启 playWithCharacter，修复上一局单人状态残留使被邀请人未进入陪玩模式的真实缺口。
- 9f4f8e66ccab772788f8bf50558e5ff7d3e31029 / 3f71043c35c529be9ecd1cd63b53eae8e8820e21：新增六款邀约 ID/规范标题解析测试，补单人模式后点击邀约打开角色共同游玩的回归。
- 证据分层：Kotlin 正式源码及 JVM 回归已提交；当前连续并发 push 导致早期 APK runs cancelled，不能视为成功。待后继 SHA 完整 JVM/APK 成功、真机 360/412dp 深浅主题截图、游戏进出与真实角色合作逐款核实。游戏具体内核玩法继续各自 G-02/G-03 接力。


## 本轮接通

- 方块小岛与迷雾摸金使用 GameSceneDeck：真实地图铺满主区域，采集/战斗/前往随目标切换，建造、背包、补给、历史等放进单一行囊抽屉。保留游戏馆原横竖屏切换，回放改从原游戏馆历史入口打开，不再浮在战斗操作区上。
- 小岛保留主干现有的队伍文字/麦克风消息、逐角色投递确认、角色自选目标、救援及地图标记。模型返回期间玩家可以继续操作；仅将仍有效的原选择合并到最新世界，不能覆盖玩家的新资源/回合或应用到新岛。
- 摸金由 RelicActorPlay 按每位同伴身份和真实可见游戏现场选移动、开箱、战斗、治疗、救援或观察。关闭本局队友的固定跟随规则，防止一人既执行模型决定又被规则操控。每角色每回合至多一次实际操作，原宝箱、独立背包、生命、药剂及探索范围直接改变；同一 ledger 同步提交，既有结算/仓库/时间线仍是权威来源。实际游戏行动或玩家发言才触发模型，没有按帧或定时收费思考。
- 两个场景复用 IslandCompanionVoice 与现有 LuluSpeechEngine，顺序播放各人已配置音色。语音开关在原队伍栏，保留关闭状态，后台及离开场景停止；语音失败可见，不擅自替换音色。使用云音色按现有供应商计费。
- 小岛新增离线程序绘制的蘑菇、蝴蝶、夜间萤火与云影；摸金加入离线原创营地原画和近景跟随镜头，迷雾仍决定可见物件，不向模型泄露隐藏宝箱/敌人。

## 本地验证及边界

- 使用 Kotlin 2.1.0 编译真实纯规则源码；JVM 规则测试使用 JSON/JUnit，网络模型入口用不允许调用的桩。为脱离 Android 的本地运行，仅在临时测试副本移除 Robolectric runner/注解；提交的正式测试仍使用 Robolectric。40 项游戏规则/消息投递回归通过。
- Kotlin PSI 解析游戏目录通过。随后使用真实 Android SDK 35、JDK 21、Gradle 8.10.2 完成 `:app:compileDebugKotlin`（Android/Compose 全源码编译）并通过；触摸层 IntSize/Float 镜头比例的编译错误已采用主干修正。
- 正式 `:app:testDebugUnitTest` 指定 IslandActorPlayTest、RelicActorPlayTest、BlockIslandRulesTest、ExpeditionRulesTest、IslandTeamInboxTest 五类，40项 Android/Robolectric 回归通过（失败、错误、跳过均为0）。这一级没有移除 runner，没有模型网络桩替代正式应用代码。
- 全量 Android 测试和完整手机 APK 打包仍由原 Lulu APK 流水线核验，不能以 Kotlin 编译或这40项测试代替 APK 成功。真机横竖屏、字体放大、麦克风、实际模型自主性、云音色质量、耗电和长时稳定性未在本地验收。
- 本轮主要改造小岛与摸金；其他游戏沿用现有实现。回合行动提供自主选择机会，不宣称支持独立于游戏回合的无限实时模型操作。


## 继续优化：已选行程的执行、走路与横屏布局

- 小岛 `approach` 从“一次模型决定只走一格”升级为“本人选择一个目的地，保存行程并随现有游戏回合持续执行”。执行数据附在原 BlockIslandState，v2旧存档保持兼容；目标消失/移动、道路阻塞、倒地、离队、关闭自主操作和本人 stop_travel 都有实际结束回执。到达只证明已走到目标旁，不能冒充采集、战斗或获得材料。
- 路线执行与旧固定队友模式互斥，每人每个世界回合至多走一步；同一回合重放、保存恢复均不重复走路。没有新轮询服务或按帧模型调用，角色仍通过同一角色游戏入口选目标、交谈和取消，执行器仅检查地形、碰撞与实际进度。none 只观察/交谈并保留既有行程，stop_travel 是明确取消，不占第二次移动。
- 人物相邻格移动按现有145ms寻路节奏做显示插值，复活/换场等跨格位移直接切换；逻辑碰撞、坐标与存档不随显示动画改变。队伍栏展示本人名字、生命和正在前往的坐标，点击沿用原地图标记入口；提示以人物名呈现，不展示内部角色ID。
- 小岛、摸金共用的横屏布局将队伍栏放在右侧，主操作留在底部；队伍内容高度受实际视口约束并可滚动，保留横竖切换时的会话保存状态。手机实际触控、键盘和大字体效果仍需真机验收。
- 新增10项持续行程、真实回合/重放、恢复、结束、取消、多人所有权、旧存档及坏执行元数据回归；独立JVM规则回归50项通过。真实 Android/Compose 编译通过，正式 Robolectric 六类50项全部通过，失败/错误/跳过均为0；完整APK和真机验收仍单独核对。


## 2026-10-30 清晨交付核验

- 已核对发布 `lulu-v6898`（北京时间2026-10-30 08:21:55），构建源提交为 `fa1165763a62a98c3ab84bb831e37e66058d5059`。GitHub compare 的共同祖先分别就是 `752533b3dcffbe0acecc653429ca2a8dba5c7c03` 与 `ff1e407887ef501fdb151bfbc38fba09d12a87eb`，不是上述游戏改动之前的旧包。
- 正式 [Lulu APK流水线](https://github.com/jiacimu-droid/lulu1/actions/runs/40977895938) 的真实日志显示：`:app:verifyArtSafeVariants :app:testDebugUnitTest :app:assemblePhone` 整体成功，`BUILD SUCCESSFUL in 11m 10s`；手机包收集、最终DEX检查和Release发布均成功。本条是完整Android单测/手机APK证据，独立于先前40/50项规则回归；日志没有提供全量测试汇总数，因此不补写推测数量。
- 已实际下载并检查 [ARM64 APK](https://github.com/jiacimu-droid/lulu1/releases/download/lulu-v6898/Lulu-arm64.apk)：29,285,405字节，ZIP完整性通过，SHA-256为 `034a9d8decf8ed0a742712963abab1d0f1c3eb43fa64e010a5d7db0f6d9dc177`。包内 `META-INF/version-control-info.textproto` 的revision与上述源提交完全一致；DEX类定义包含GameSceneDeck、两游戏页面、RelicActorPlay、IslandActorPlay、IslandTeamInbox、IslandTravel与IslandCompanionVoice。
- 原创营地WebP已在最终APK资源 `res/Sk.webp` 中逐字节核对，SHA-256为 `1109f321eab03f4e5c972fe647941c3cef0ce7e621a375ec14ba3f852a04ccc0`，与发布源素材一致。源码再次确认摸金的移动/开箱/战斗/治疗/救援真实修改原局状态、小岛读取最新状态合并、队伍消息按角色确认、语音沿本人音色串行播放并在离开/后台停止。
- 发布源已包含后续触控布局修复：方向键固定左下、采集/战斗等主动作固定右下；背包管理横屏采用右侧场内面板，移动不藏在抽屉；游戏馆统一管理进出游戏的横竖屏请求。此处记录既有主干成果，不将其他窗口的实现认作本轮新增。
- 本次读取的main为 `b7607428f764702a9657968a071c48e4ad450eab`，游戏源码/游戏测试与上述发布源无差异。更新主干最近一次失败 `6e3185e` 卡在Live2D的exp3眼部所有权测试，尚未进入Android游戏编译；另一窗口已提交对应修复并启动 [后续流水线](https://github.com/jiacimu-droid/lulu1/actions/runs/40983048341)。该后续构建在本次核验时尚未完成，不能将v6898称作包含后续所有主干修改的新包。
- 未进行安卓真机启动、触控/大字体/方向恢复、实际模型陪玩与云音色体验验收；源码、单测和最终包内容核对不替代这些体验结论。本轮未改游戏行为代码，仅补充可复核交付证据，因此无需重新生成代码索引。


## 2026-11-03：玩法目标、近身交互与生存感（main 已提交，真机待验）

- 本轮针对用户实玩反馈（工作台建完不知道做什么、摸金宝物用途不清、移动与开箱必须反复点地图、战斗缺乏读招和成就感），直接修改既有两款游戏规则与场景，不新建游戏入口或第二个状态源。
- 方块小岛：旧存档兼容地增加饱食度、存活守夜次数、击退怪物计数与最近倒地回合；吃野果/鱼可补饱食度，长期不吃会扣生命；在实际篝火/木床附近撑到黎明且没有近期倒地才得到守夜补给。当前场景显示工作台→石剑→营地→击怪→守住三夜的引导性阶段挑战。工作台依旧只负责制作已有四类工具，不虚构建造收益；方向键靠近树矿/补给箱直接采集，中间✦可近身操作、在工作台附近打开原“制作”菜单。
- 迷雾摸金：复用现有 RelicEscapeRules 和 RelicEscapeLedger 物理箱子、仓库及撤离结算，近身宝箱/掉落/补给/祭坛/机关/出口可用方向键或中间✦直接交互，不必先地图点格；攻击保留明确按键，不因长按移动自动出刀。场景显示当前带出目标、宝物估值、撤离倒数、附近怪物生命和蓄力提示，明确永久仓库售宝→铜币→药剂/武器升级路径。保留既有空载撤离合法性，避免擅自更改老存档/原结算契约。
- 游戏 D-pad 中央✦由纯装饰改为可点击且可读的上下文互动键；两款游戏继续复用一套 GameSceneDeck，无额外按帧或常驻模型调用。
- 已补 BlockIslandRulesTest 的真实饱食/守夜奖励/旧存档/怪物击杀回归，以及 RelicQuickActionTest 的无地图选中开箱、迷雾不可偷开与不自动攻击回归。**本轮仅确认测试源码已提交，没有执行过正式 Android/Compose 编译、Robolectric 全量测试或新 APK / 真机操控验收**；不得套用本文件 2026-10-30 的历史 CI 证据宣称本轮已通过。需根据最新 main 的 GitHub Actions 和实际 APK 进一步核对。
- 这轮属于真实的第一段可玩性修复，而不是宣称两款游戏从此拥有完整 BOSS/关卡/职业/装备成长；后续可基于真机手感再扩展敌人招式、危险撤离的风险回报、NPC 实际协作与更丰富的目标结算。


## 2026-11-03：手机虚拟摇杆的持续移动和滑动转向

- 来源：实际玩家希望「按住就一直往前走，手指拖向别的方向立即转向，松手立即停止」，而不是反复点一次走一格。之前 `GameDirectionalPad` 是四个独立手势区，虽有 `onPress` 延迟重复，但不能在同一根手指下连续改变方向；小岛/摸金还分别以 420ms/580ms 每格的周期显得迟钝。
- 现改成共享的 138dp 虚拟摇杆：触下立即行动，按住持续发出真实的邻格行走操作，滑动动态改方向，移到中心停止、抬手停止。独立中心「✦」保留原场景互动，中心误滑出去不会在松手时误触发互动。支持辅助功能方向键的语义点击；每次真实走路仍由原世界规则进行碰撞、回合/敌方行动/存档和伙伴状态变更，不假装连续坐标已经是权威位置。
- 小岛走路节奏改为 195ms 每步，仍由已有 145ms 插值与跟随镜头平滑展示；摸金为 340ms 每步，避免按住造成远超预期的敌人回合突增。摸金的角色/相机补充 175ms 纯显示层插值，摄像机触摸选格使用相同浮点视角换算；瞬移、倒地或变更对局不做跨地图飞行。
- 已识别模型费用风险：角色陪玩原本可能以每个游戏回合作为新模型决策触发；现在摇杆按住时小岛复用既有 `navigating` 抑制被动请求、摸金新增 `continuousTravel` 以合并每次连续行走的被动思考，在松手后根据最新真实局势再决策。摸金仍允许玩家明确发送的队伍消息在移动期间唤醒角色，不以移动状态吞消息。
- 新增 `GameJoystickDirectionTest`：中心死区、四方向连续拖动、对角线优势方向、越过摇杆边界的拖动方向；扩展 `RelicMapCameraTest` 检查分数格镜头居中与点击命中。保留方向键长按到墙/怪物时的合法拒绝机制，不自动打怪或绕过地形。
- 边界：本轮是连续操作与视觉插值，而**非**把底层地图和所有行动改成每帧连续的自由坐标。完全无格子的像素级移动需要与碰撞、怪物行动时钟、拾取半径和实时战斗一起改；旧存档/回合规则本轮保持不动。
- 已写入正式 main 并让原有 code-index 工作流接手索引；**尚未获得本轮完整 Android 编译、Robolectric 运行和实体手机触控验收证据**，不能称为已验通。


## 2026-11-03：Lulu APK 连续排队与 Kotlin 编译失败（CI 待验）

- 实查 Actions：#7393（run 41583980021）在同一 `lulu-apk-refs/heads/main` 并发组长期执行，其工作停在 `Test and build phone APK`；`release.yml` 当时使用 `cancel-in-progress: false`，导致较新的 #7438 挂在 pending（无任何 build job）。频繁新提交使 #7434～#7437 等待运行被替换并显示 cancelled；**取消不是编译失败**。#7393 最终于 2026-11-03T07:23:59Z 取消，不能宣称它已打包成功。
- #7438（run 41593792883，源提交 `665675f`）随后真正运行到 `:app:compileDebugKotlin`，2026-11-03T07:28:16Z 结束为 failure。日志三条真实错误：`GameSceneDeck.kt:259:21` 与 `:269:45` 的 restricted suspending scope；`RelicEscapeArt.kt:66:31` 的 Compose `State<Offset>` 委托 getter。该轮 Live2D、SDK/NDK 安装已过，且尚未走到 APK 收集/发布。
- 同一 main 上已针对三条编译错误修正摇杆重复动作的 coroutine scope 和 Relic 镜头的 Compose state 读取（`f804835`、`1654a78`）。前一项的修复分支没有另开，直接落 main；不得仅凭源码判断 Gradle 已通过。
- 工作流增加 `jobs.build.timeout-minutes: 65` 与 `Test and build phone APK.timeout-minutes: 40`（提交 `34dd405`）。保留原本“不打断正在正常构建的那次”的并发语义，但限制异常挂起时间，不让一个 runner 无限占用发布队列；没有绕过单测和签名/发布步骤。
- #7439 先于全部修复启动；#7441（run 41598258805，提交 `34dd405`）包含这两处编译修复及超时配置，在记录时尚为 pending，**未经 CI 通过、APK 产物、真机验收**。验收以 #7441 或其后同源 main 的实际完整构建日志、Release 和 APK 哈希为准；任何 cancelled/failure 均不得标记通过。


## 2026-11-03：Lulu APK 堵队列与虚拟摇杆 Kotlin 编译故障复盘

- Actions #7435–#7437 为 `cancelled`，不是 Kotlin 编译失败。#7393（run 41583980021）曾长时间停在 `Test and build phone APK`，旧工作流在 `lulu-apk-refs/heads/main` 共用并发组且 `cancel-in-progress: false`，使手动发起的 #7438 也一直 `pending`、无任何 Job；之后 #7393 被取消才放行。
- #7438（run 41593792883）真正运行后在 `:app:compileDebugKotlin` 报三个编译错误：`GameSceneDeck.kt` 第 259、269 行在限制挂起作用域里使用 `coroutineScope/awaitPointerEvent`，及 `RelicEscapeArt.kt` 第 66 行 `State<Offset>` 缺少 `getValue` 委托支持。正式日志而非猜测证实本轮有源码编译问题。
- 修复提交：`f8048352` 把持按重复协程创建移到受限事件作用域外（事件读取仍在作用域内）；`1654a783` 将镜头最新位置改用 `State.value` 并清除重复导入。#7439（run 41598168249）的日志不再出现 `GameSceneDeck` 两条编译错误，仅报修复前提交中的 `RelicEscapeArt` 状态委托错误，提供了一次局部验证；这不等于最终 APK 通过。
- 工作流提交 `686152ca` 将手动 `workflow_dispatch` 与自动 `push` 并发组分离，既保持频繁提交时的单个运行保护，也不让手动发起的构建被旧自动任务永久堵住；为 `build` 设置 65 分钟上限，避免默认长时间挂死。其他并行提交一度导致 `timeout-minutes` 重复声明，已在 `c355f8a1` 删除重复键。
- #7443（run 41598389134，源提交 `c355f8a1`）已进入实际 Job 执行；**截至本轮记录，尚未获得最终成功/失败结论，更没有新的完整 APK/真机验收证据。** 必须读取该 run 最终日志与产物后才能在总看板标记 CI 已通过；不重启失败的历史旧 SHA 假装是新版本。


### 2026-11-03 追加：CI 失效根因和实际修复交接

- 完整读取 #7438 job `125783563587` 原始日志，确认唯一真正的编译错误为 `GameSceneDeck.kt:259:21`、`:269:45`（restricted pointer-event suspend context）以及 `RelicEscapeArt.kt:66:31`（Compose State 委托 getter）。这些不是 VPN、NDK 或缓存错误。源码层分别已有同日 main 修复提交 `f804835` 和 `1654a78`。
- #7439 `f804835` 在第二份修复提交前已启动，日志仅剩 `RelicEscapeArt.kt:66:31`，符合提交时间线；不能把它当作完整修复后的失败。
- 旧 #7393 原日志表明 `:app:compileDebugKotlin`、`:app:compileDebugUnitTestKotlin` 均通过，随后在 `:app:testDebugUnitTest` 无输出长时间滞留，直到取消；卡住的具体测试尚未可证实定位。为排查此类无输出挂起，已在现有 `app/build.gradle.kts` 的 `unitTests.all` 添加 Gradle `testLogging.events("started", "failed")`（`3d6cc7b`），不跳过任何测试，未来运行日志可以看到进入哪条测试。
- `release.yml` 后续同日提交采用按 `github.event_name` 分开的手动/自动并发组；保留 `cancel-in-progress: false` 和已增加的 65 分钟整任务、40 分钟编译测试步骤上限。手动请求不必等待旧 push 构建释放通道，但可能与自动构建并发消耗两个 runner；不认定并发本身造成代码错误。
- 最近检查：#7441（完整两处 Kotlin 修复及超时配置）正在 `Test and build phone APK`；#7443 同样正在该步骤；#7447 是更新后 `main` 的待运行自动构建。三轮截至此处**均无成功 APK 和 Release 证据**，旧失败/取消不算通过。后续看最新完整成功运行的 Gradle 测试、APK 校验与产物并按证据更新进度。


### 2026-11-03：#7441／#7443 失败归因与 Wolf 咯咯笑测试修复

- 核验完整 GitHub Actions 失败日志：#7441（run 41598258805）和 #7443（run 41598389134）均已通过此前的 Kotlin 编译环节，并在 `:app:testDebugUnitTest` 的 2291 项测试里仅有一项失败：`Live2DMotionCatalogTest.describeNamesWolfMotionsAndPassesOthersThrough`。
- 精确差异：第 19 号 LuluWolf “咯咯笑”动作已经从机械左右晃动改成下巴轻点、肩膀随后细颤，生产目录 `Live2DMotionCatalog.kt` 反映主人希望的自然轻颤，但 `Live2DMotionCatalogTest.kt` 仍断言旧动作说明。此前修复 Kotlin 触控与 State 编译问题本轮已经得到进阶验证。
- 已按**现行真实动作契约**更新正式测试对第 19 号动作的精确预期值，保留第 20 号难过动作、目录覆盖与全部测试；不还原旧机械摇晃，不跳过或放宽测试。源码提交：`3c00ae39bbdc856f2c9023de6214e8fa5769e67c`。
- 此提交自动触发 Lulu APK #7448（run 41599481697）；检查时尚为 pending（之前的 #7447 在 in_progress）。**尚无 #7448 的新测试、APK 收集和 Release 成功结果**；不能将“已修好一项确定失败”标为 APK 已完成。后续以 #7448 或更晚**包含该源码提交**的完整 Actions 成功、实际 APK 与真机体验为验收依据。


### 2026-11-03：APK 全量测试耗时核算与消除跨变体双编译

- 真实成功样本为 [Lulu APK #7452](https://github.com/jiacimu-droid/lulu1/actions/runs/41599835451)，源 SHA `812f52925ba5b497e423adcc619a730dd3fdb49c`，`testDebugUnitTest` 成功、`assemblePhone` 成功、上传/发布成功，Release [lulu-v7452](https://github.com/jiacimu-droid/lulu1/releases/tag/lulu-v7452) 含 29,254,884 字节的 ARM64 APK。这是正式构建通过，不代表后续 main 版本或实机验通。
- 该 Job 原始日志约 2296 条单元测试 `STARTED` 事件、约 370 个测试类；`:app:testDebugUnitTest` 从 08:04:25Z 到最后一条 `STARTED` 08:06:41Z，约 2 分 16 秒；Gradle `BUILD SUCCESSFUL in 12m 12s`。同一工作流先编译 `:app:compileDebugKotlin`（08:01:50Z），后编译 `:app:compilePhoneKotlin`（08:07:14Z），测试只占完整发布耗时一小部分；直接删除大量测试难有对应大幅收益，反而损失覆盖。
- 2026-11-03 在现有 `.github/workflows/release.yml` 原工作流中将 `:app:verifyArtSafeVariants :app:testDebugUnitTest :app:assemblePhone` 改为 `:app:verifyArtSafeVariants :app:testPhoneUnitTest :app:assemblePhone`；仍运行**同一份完整 JVM/Robolectric 测试源码，不删测试、不缩短断言**，使测试与最终安装包共享 `phone` 编译任务，避免先 Debug 后 Phone 两套编译图。
- 优化提交 `33016229328e7128a819ea83280432d7f8f709e1`，触发 [Lulu APK #7464](https://github.com/jiacimu-droid/lulu1/actions/runs/41602005363)；检查时 #7464 仍 pending，前一次 #7462 正运行。**这一优化尚无完整 Android 运行验证，也没有可证实的节省分钟数**。必须以 #7464 或包含上述优化提交的后续成功构建确认 Gradle 确实执行 `testPhoneUnitTest`、单测数、APK 产物与发布一致性；若测试变体不兼容，应依据真实日志修正规则而不是跳过测试。


## 2026-11-09：方块小岛入口／工作台职责调整（G-02；源码已提交，CI、真机、语音待验）

- 主人实玩指出：直接进大地图、菜单里混入制作和建造、工作台需打开通用菜单、缺少可感知的目标、角色语音和自主游玩的入口不明显。根因是 `BlockIslandScreen` 的 `GameSceneDeck.tools` 同时承载工作台、建造、地图/库存/设置；通用游戏馆又提前弹出选人页，世界没有自己的启程阶段。
- 新增 `BlockIslandEntry / BlockIslandLobby`：从游戏馆直接进入小岛专用大厅，玩家队伍槽和“＋邀请（选择同行）”、真实旧档继续、冒险目标预览、进入游玩。入场从已有 Store 选择角色，世界在 `BlockIslandScreen` 初始化，旧存档不因大厅重建而清空；恢复存档的已选同伴先显示。**首次实现仅是玩家选人；后继提交已改为角色本人决定接受/拒绝（见下文）**；后者须另接角色本人决定与成功回执，不能冒充已完成。
- 工作台互动改为游戏内就地配方面板，需距离≤2格，配方由 `IslandWorkbenchRecipes` 同时提供给真实执行规则与显示层；建造从右手快捷栏直接进入建筑列表，选择后回地图定位。通用菜单仅保留地图、行囊、伙伴、图鉴、设置；吃野果/烤鱼归行囊，采集、战斗、建造在 HUD。点击任务条能查看生存→探险→家园的目标顺序与存档实际进度。
- 仍沿用既有 `IslandCompanionPanel` 的按麦克风识别成文字→角色本人 `IslandActorPlay.decide`→校验与物理执行→回执，以及 `IslandCompanionVoice` 的可开关音色朗读；**未实现常开免按键双向通话或角色自行接受邀请**。游戏操作仍可在模型等待时进行，原真实行动与旧存档格式未换中心。
- 源码提交：大厅 `9e3f579` / `443136b` / `b83427d`；就地交互、任务、菜单 `e971b49` / `c2846fe` / `e1330b3`；配方单源 `7ab6d50` / `ffea168` / `363c0e3`。新增 `IslandWorkbenchRecipesTest`（`f79f4ea`）校验材料、工作台距离、拒绝重复扣费，**目前仅提交测试源码，未取得对应 JVM/APK 运行通过证据**。
- **下轮 G-02 固定入口**：先核本轮最新 push SHA 的 `compilePhoneKotlin`、`testPhoneUnitTest` 与 APK；失败修首错，成功后实测横屏大厅、弹窗遮挡、旧档同伴续接、真实 ASR/音色权限与收费、角色决策与真机帧率。随后继续真实邀请接受/拒绝、目标奖励和协作建造玩法，不得把本轮界面结构调整宣称为完整游戏改造。

- **2026-11-09 同行邀请后继修复**：原先大厅的玩家点选不代表角色同意，现新增 `IslandInvitation.ask` 使用被邀请角色本人身份、场景、关系的游戏模型，返回严格 JSON 布尔 `accept`，只在有效且明确同意后加入；拒绝、API 失败及非法回复不自动入队，玩家可独自开始、既有存档同伴延续；等待期间阻止重复邀请与误开始。提交 `9013714`、`2299a69`、`72fe1e0`，解析回归 `f993eea`、Robolectric 修正 `c455397`。邀请是一次显式收费模型请求；没有伪造角色“答应”，但**实际模型意愿/Android APK 尚未运行验收**。队伍栏语音入口易见性 `f2830ad`；旧版家园继续兼容 `820f743`。


## 2026-11-09：小岛开局大厅连续 APK 编译失败的实证修复（G-02；新 CI 未验）

- 此轮读取 [#846](https://github.com/jiacimu-droid/lulu1/issues/846)、[#847](https://github.com/jiacimu-droid/lulu1/issues/847)、[#848](https://github.com/jiacimu-droid/lulu1/issues/848)、[#849](https://github.com/jiacimu-droid/lulu1/issues/849) 的实际 Actions jobs 日志，四轮均在 `Test and build phone APK → :app:compilePhoneKotlin` 失败，最早一条均指向 `BlockIslandLobby.kt` 的 `var selected by rememberSaveable { mutableStateOf(savedIsland?.world?.friends?.map { it.id } ?: emptyList()) }`，导致委托 `setValue` 泛型推断失败，后续 `forEach` / `minus` / `plus` / `take` 皆为同一根因引起的类型级联错误；不是模型、安卓权限或网络问题。已显式指定双层不变泛型 `var selected: List<String>` + `mutableStateOf<List<String>>`，保留旧岛队伍和新增邀请语义。生产修复 [`cfa1859`](https://github.com/jiacimu-droid/lulu1/commit/cfa18597fa0895998ae42b7d6a8442009404bfa2)；**未拿到该修复后的有效 JVM/APK 运行结果，不关闭 CI 工单、不宣称已通过**。
- 同一轮审查菜单职责调整发现：此前“工作台制作”所在菜单撤除后，玩家 `rest` 的入口随之丢失，虽然 `BlockIslandRules.act("rest")` 仍可在真实篝火／床铺旁执行。新增 `islandCampNearby` 与 `islandCampRestAvailable`，在游戏场景的中心互动／主操作键出现“在营地休息”，沿原恢复生命、位置、间隔与回合规则执行，不再塞回通用菜单。生产提交 [`6b18223`](https://github.com/jiacimu-droid/lulu1/commit/6b1822340742357bb32fb8958233d2ec8e140cbd)、[`7888c3e`](https://github.com/jiacimu-droid/lulu1/commit/7888c3ebe5a664a728677364e2bfd10b64cbb7bd)、[`fa6e7f3`](https://github.com/jiacimu-droid/lulu1/commit/fa6e7f32472d53022c8fd10220b191195737c997)；测试 [`54aa57a`](https://github.com/jiacimu-droid/lulu1/commit/54aa57ab1ea507e75d0d2871715624d058cb285a) 覆盖物理营地、生命、冷却、重复操作；**测试源码已写但尚未获得运行成功**。
- **下一轮固定 G-02**：核包含 `cfa1859` 及 `54aa57a` 的完整 phone Kotlin/JVM/APK CI，失败只修实际第一个错误；通过后复核动作入口、旧世界+多伙伴真机横屏、Android 语音识别与音色播放。注意 #846~#849 属于修复提交前的旧 SHA，不能把它们的红灯误当修复后失败或宣称成功。

## 2026-11-12 G-03 真正 2D 美术 + 回合紧迫感（实做，CI/真机待验）

主人否定前轮写实 3D 效果图（此图只是概念、无法直接跑在 APK），要求实际修改现有地图。针对正式 `RelicEscapeScreen` / `RelicEscapeArt`，本轮按 MotionSites 的信息层次与视觉精度原则、现有 2D Canvas 渲染实现：
- `8d2a661` 新增纯展示投影 `RelicTensionPresentation`：隐藏敌人与死亡敌人不触发伪警报；可见三格内蓄力、本人低血、倒地救援、珍贵战利品与撤离原有 3 回合进度由 `RelicRun` 的真实事实推导，无虚假秒级时钟，也不替玩家决定撤离。
- `1491697` 将投影接进正式 `GameSceneDeck.notice` 与顶部状态/目标板：提示优先级与剩余撤离回合真实同步，避免冗余的长解说文本；仅改变 UI 不新增行动或模型调用。
- `fc05cc3` 用原创离线 Canvas 绘制的三种材质宝箱、宝石切面、珍贵箱金冠装饰替代普通统一箱；地上的真实掉落物按 tier 渲染不同切面，撤离门按保存的 `extraction` 亮起 0–3 个点；本人周边仅在真实危险/撤离/倒地显示克制状态圈，删掉原覆盖范围很大的红色径向遮罩。
- `f5e1a04` 新增 `RelicTensionPresentationTest`，测试回合取代假秒数、危险仅对可见蓄力活敌人显示、救援进度、低血与真实持有珍贵宝物。不改变地图可见性、敌人攻击、战利品或持久化。
- `73e8d93` 对纯展示投影做 `remember(run)`，防止地图动画重绘每帧重复搜怪物。

**工程证据**：这批提交已写入现有 main；对应最终 head [Lulu APK/JVM #43060458609](https://github.com/jiacimu-droid/lulu1/actions/runs/43060458609) 在查询时仍 pending/后继可能取消，**未确认 APK 编译或新单测通过**。基于 Compose 源码审查可确认更新了可运行的渲染入口，但无法凭聊天内的静态概念图宣称真实截图效果。待最近覆盖这批提交的最终 APK/JVM terminal success 后，按 360dp/412dp 真机实际检查宝箱可辨识、HUD 不挡地图、低端帧率、方向键/开箱/撤离、字体放大与横屏；如果 CI 首错就在本范围先修，若后继提交成功再标工程通过。下一轮继续 G-03.5 的真实游玩/界面闭环而不是再出 3D 假效果图。


## 2026-11-12 G-02.4 · 方块小岛可玩生存循环、场景留白与玩家建房（源码已提交，APK/真机待验）

主人实测横屏地图受顶部/底部系统栏、超大 HUD 及任务卡遮挡；也反馈建房路径不明、缺可持续食物、多动物只需一键收服。这轮直接修改正式 Android 游戏源码：

- **全景可见**：`LuluGamesApp` 对小岛取消 54dp 顶部全宽保留区，保留右上可操作的小型半透明浮动栏；按原沉浸模式范围隐藏系统导航区（离开恢复）。`GameSceneDeck` 允许小岛使用可折叠精简状态；`BlockIslandScreen` 删除常驻目标/地标/解说叠卡，只保留点击展开的当前目标、必要的施工指引和紧急危险。画布计算不再扣除已取消的 54dp 顶栏。不能只凭源码说真机视觉通过。
- **真实食物路线**：新增 `IslandGarden` 和 `BlockIslandState.gardens` 同源持久数据；花 2 木摆菜圃，耗 1 野果播种，生长至少 4 回合后松土，总计 12 回合（冬季 18）成熟才按当时季节得到 4～5 野果。播种/松土/收获分别占真实回合、可因操作过早被拒、不能重复收获；拆除清理作物；旧 v2 存档默认空菜圃。原有鱼竿钓鱼＋篝火烤鱼链保留，并修复仅饱食不足而生命满时烤鱼按钮不能点的问题；低饱食自动展示先补给的目标文案。
- **一块块建房**：新增暖灯原木屋和松林小屋两种玩家 3×3 蓝图，不给免费建筑；蓝图校验连续 9 格地形、已有材料/生物/玩家/同伴占位及附近位置。玩家指定蓝图后按下一施工格亲手建 9 步共耗 17 木，不能站在自己要建的实体墙上，寻路只能到施工格**旁边**；完工复用已有项目的正式屋顶/门窗绘制，拆除已造部分须重新修复才恢复屋顶。角色本人独立选屋型/施工权仍保留。
- **动物渐进关系**：兔与鸭各需两次真实喂食、狐狸需三次，每次必须间隔至少四个世界回合、各次真的扣除野果/小鱼；关系进度及喂食回合存入现有 `IslandAnimal`，旧已结识动物安全保持已结识。图鉴/现场可查看信任进度；原真实家园跟随/礼物冷却只在完成结识后生效。
- **温和季节氛围**：原 2D Canvas 草地/树林/水域/矿石规则不变；春夏秋冬对地面进行克制的色调变化，菜圃展示播种/发芽/红果三种真实存档状态，不添加模型调用或大幅每帧计算。

提交链（主线覆盖：`ab616e0`, `6d54772`, `8dd98c5`, `e50cdce`, `099c0f6`, `f591e91`, `5209a65`, `d426e7b`, `459c019`, `a5c3fec`, `c6a7eee`, `2edc300` 及期间的界面/动作/美术连接补充），回归文件 `IslandGardenTest`、`IslandPlayerHouseTest`、扩展 `BlockIslandRulesTest`。这是**已在源码连接的可玩第一批**，不是已经达到《我的世界》规模。编译/APK 测试必须以最后覆盖本链的实际成功 Actions 为准；上轮频繁连续提交触发前一 run 被取消，**取消不算失败或成功**。真机待测：手机横屏沉浸栏/退出还原、360dp/412dp、蓝图可点与真实可寻路施工9步、旧档导入、野果可长期供给、动物多次结识、季节色与低端设备帧率。

下一步 G-02.5：优先 CI 首错/真机可玩后续，完整小屋可装饰/多屋与区域保护、更多食谱与生产链（种植/捕鱼/烹饪）、动物栖息行为、可辨识的季节地貌，保持规则-存档-真实反馈-渲染一条线，不扩 UI 遮挡也不把角色自主选择写死。


## 2026-11-12 G-02.6 · 采矿装备、晶石闭环、方向键与死亡代价（源码接通，CI/真机待验）

主人实测：方向键撞树/矿即采集，木斧/石镐只有不透明的奖励数值，宝石无用，血条归零立即满血复活且资产全保留，缺乏需要真的经历的选择。本轮按**真实代码**纠正这些断点：

- `IslandManualMovement` 将方向键严格限定为移动/朝向；碰树、矿、箱子时不会替玩家调用采集。采集/攻击/开箱走原 `islandTargetAction`＋现实快捷键，保留靠近即选目标，不要求反复点击小地图；对资源碰撞给一次可见提示，长按方向键不重复写盘。新增 `IslandManualMovementTest` 检查树/矿/晶矿/补给与敌人。
- 现有 **木斧=伐木 4 木/次（徒手 2）**、**石镐=采石 4 石/次（徒手 2）**、石剑=攻击4伤（徒手2）、鱼竿=钓鱼实际用途写在工作台/行囊/目标快捷键。工具制作后自动生效，无不必要的装备页或第二库存。
- 矿石中稳定生成肉眼可辨的**蓝色晶矿**，没石镐不能破坏，避免徒手提前耗尽稀有矿；石镐正确采掘掉落一颗真实晶石（`BlockIslandState.crystals`，v2 增量存档）。晶石可在工作台制作 1 次性 **晶石护符**（消耗 1 木/1 石/1 晶石，挡下致命伤时实际消耗），或建 **护夜晶灯**（3 木/2 石/1 晶石，夜间距离3格内实际抵消一点怪物伤害）。夜晚地图有真正的晶灯光圈，且已保存建筑/晶石可跨重启复原。旧存档无字段默认0。
- **生命归零**：明确返回营地、6/12生命；仅随身可采集资源的各一半散落在倒下地点，屋子和制作好的工具保留。丢失位置、数量、多个掉落点均存于同一 v2 存档（`lostPacks`），场景以真物件显示，玩家靠近手动点 **找回遗失背包** 才按保存数据逐项取回；重复领取不产出资源，水上桥边掉落亦能恢复。护符若生效则不产生掉落。晶灯夜间减伤、饥饿同旧生命条的扣减规则仍在。
- 模型同伴会看见当前晶石、工具及背包数量；拥有石镐时可自主选择有效蓝晶采矿动作并得到真实晶石（不替模型决策）。旧固定采集模式不能绕过石镐要求直接毁掉晶矿，补资源也不会在遗失背包上生成树或石块。所有采集资源均在同一权威存档原子结算。
- 新增 `IslandProgressionTest` 五组（晶矿/装备、晶灯减伤、死亡找回、护符一次性、旧存档）及 `IslandManualMovementTest`，并扩展工作台晶石配方回归；此处不报告本轮未拿到的 APK 成功或真实手机体验。

**下一轮优先验收**：同一最新 SHA 的 `Lulu APK` 及其 JVM测试实际通过，针对失败日志首错修复；真机实测撞矿不再自动收集、制作木斧/石镐效率回馈、蓝晶矿可辨识、晶灯夜晚减伤、生命归零保留掉落点/多次身故不刷资源、手机退出与回档复现。后续 G-02.7 扩展材料加工、食谱/农作物、家具用途、野兽互动的多阶段过程和地貌/昼夜生态，不通过叠加数值冒充全部完成。


## 2026-11-14 · 两款正式游戏新手引导与 HUD 审查（源码已提交，CI/真机待验）

- 用户反馈：摸金宣称7档宝物，但价值次序/获得规则不够直观；游戏操作、菜单、画面主体与设置需要合理分层。实际 Compose 审查：左下持续摇杆、右下固定游戏动作、中间地图的结构合理；小岛现有地图/行囊/伙伴/图鉴/设置分区；摸金仍有战术/道具/仓库/收藏堆在长滚动面板。矮横屏右侧情报可能因控件保留高度而几乎看不到，藏在情报里的玩法入口不稳定。只是代码审查，未获取本轮真机截图。
- 摸金源码 0388b2a、c45a743：把密集帮助改为 入门/七档品级/合作 三栏可滚动手册，按真实鉴定价值逐条解释普通70–99、精良100–132、稀有185–247、珍贵430–459、史诗460–492、传说封印之心650起、传世秘宝晨星之核6000。工具页增直接查看入口，正式紧凑 HUD 绑定独立指南。明确七档仅为鉴定展示，既有实体掉落/存档仍是三类，不改概率和实物结算。
- 小岛源码 da0c6de、6b4afa1：原9段说明分为 入门/家园/生存 三栏滚动手册，讲清方向键与主动采集、工作台/制作、房屋施工、食物/农田、装备与晶石、死亡遗失背包。当前目标旁加指南入口，正式 HUD 也可一键打开，不堆常驻大文字。
- 共用场景源码 44abb709：GameSceneDeck 紧凑生命栏旁新增可选44dp独立指南触控入口，不依赖会被压缩的滚动情报区，主体地图/左下摇杆/右下交互区保持原位，竖屏宽度给右上队伍留出空间。弹窗正文按当前设备 dp 限高并可滚动。
- 本轮重新阅读 MotionSites SKILL、craft、review、INDEX 及3d-collectible-hero、interactive-discovery、neo-museum 案例，移植的是视觉主次与低干扰导航原则，不复制 web 代码或营销段落。
- 未验收：本批改动所在最新主分支的 phone JVM/APK、真机360/412dp、矮横屏、字体放大、对话框触控和实际画面美术效果。下一轮 G 专项先核有效后继 push 的首个真实 CI 结果，再用真机截图评价这三处信息密度/触控；摸金准备与收藏菜单的职责进一步拆分仍是后续工作，不能冒充当前已完整美化。


## 2026-11-14 · 双游戏正式菜单解耦 + 首次引导 + 品级一致性（二轮，主线源码已提交）

- G-03 实际游戏内菜单不再把生命/目标/战术/战利品/事件沿一列无止境堆叠：`RelicMenuSections.kt` 定义冒险中的 现场/战术/背包/记录 四组；`RelicEscapeScreen.kt` 仅显示当前组内容，顶部栏目固定，下面独立滚动；真正战斗快捷键、摇杆和物理地图仍由 `GameSceneDeck` 保持固定。现有角色队伍状态归现场，右上独立队伍通话功能不变。`8649e787`、`d3d4d79c`；新增 `RelicMenuSectionsTest` 两条规则回归 `daf6370`。不可将游戏内隐藏的准备支路与真正营地主界面混淆：正常返营由 `RelicEscapeLobby` 接管，原整备/商店/藏宝馆仍是权威入口。
- G-02 小岛菜单保持 地图/行囊/伙伴/图鉴/设置 的既有职责，不重置角色存档或按钮。`60b071d` 把导航提升到滚动内容上方并缩小标题，横向轻滑切换，下方长图鉴/设置独立滚动，避免必须回到最顶部才能换栏目；小岛地图、摇杆、快捷按钮不改。
- 首次进入真实冒险场景，教程自动且仅一次展示：摸金 `2ca3550`；小岛 `7f7deef`。复用各自已有新手手册和永久可点击的「指南」入口，已看过后不再每局打断；使用本地 SharedPreferences 标记，无新增 LLM 费用或游戏物理回合。
- G-03 藏品材料色从真正 `RelicTreasureGallery.accent` 抽出共享 `rankAccent`（`0398604`），七档说明使用与实际馆藏相同的金属/石色，不用七行同色文字（`666f759`）。`RelicEscapeLobby` 的单屏工具栏增加「指南」二级弹层（`6f29f912`），玩家出发前就能阅读四步操作与七档价值，未新增宝物、概率或虚假成就。营地其他原有入口保持整备/商店/藏宝馆。
- 工程核对：本轮对修改的 6 个 Kotlin 文件做了只读括号/代码入口结构检查，全部平衡，GitHub 更新成功并保持并行 main 的其余最新提交。**这不等于 Kotlin/Compose 真编译、testPhoneUnitTest 成功、APK 发布或 Redmi 实机视觉验收**。GitHub combined status 对本批新提交未返回 CI context；不可宣称 APK 通过。下一轮先查包含 `6f29f912` 后继 push 的 Lulu APK/JVM 真正成功/首错，按日志修复，真机 240–412dp 重点验矮横屏菜单内容、四按钮触摸、团队角控、指南弹窗高度/滚动、画面主体、视觉与宝物图鉴色。已经存在的 4个游戏主线/专项定时接力继续负责，不新建重复任务。

## 2026-11-14 · 双游戏美术增量：海岛手作工作台与真实珍宝缩略画

- G-02 实际美化：`6b6c3b5` / `3c631f3` 让《方块小岛》大厅的真实玩家/角色席位及邀请卡使用明亮奶油、鼠尾草和木质海岸色；《摸金》同一共享组件默认保持原冷色。没有改变邀请结果、队伍上限、存档或入口。地图 `76d3448` 将真正可摆放/操作的工作台绘制从六块平面矩形改为桌面斜切、腿架、木纹、铁锤、刻刀和铜钉的原创 Canvas 插画，仍绑定原结构实体，渲染固定次数图元。
- G-03 实际美化：`8f3ac1e` + `c11c9a8` 让藏宝馆每件真实收入的藏品条目显示对应原始 `RelicTreasureArt` 造型和 `RelicTreasureGallery.accent` 品级材质，而非只有文字、通用菱形和估价；当前选择通过相同 ID 高亮，原展出/分享/出售回调不变。复用原鉴赏展台渲染，无重复物品源、网络资源或额外模型请求。
- 工程核对：五个修改文件从当前 main 重新读取，目标入口分别存在且花括号数量配平；`Refresh code index #43562243156`（源 `76d3448`）success 并产生 `a21e2c9` 索引提交。此静态检查不是 Kotlin/Compose 编译或测试。新的 [Lulu APK/JVM #43562243159](https://github.com/jiacimu-droid/lulu1/actions/runs/43562243159) 当前 pending，先前连续 push 的取消运行不算编译失败；后续须查覆盖上述五个提交的完整有效 success/首错。
- 视觉限制与下一证据：本轮没有安卓实机运行截图、360/412dp 横竖屏、放大字体或低端机 FPS 记录。需在真机检查：海岛浅色席位在真实插画上可辨、紧凑席位可正常移除/邀请、工作台小尺寸工具轮廓能认出；摸金珍宝缩略画不裁切、稀有品级颜色与放大鉴赏一致。没有新增生成图的原因是既有海岛原创背景/地表图集和珍宝原创矢量已被正式打包，先把真正存在的素材用于真实页面，避免替代掉已获认可画风。


## 2026-11-14 G-03.5 · 七档随机宝箱与地图可达性回归修正

- main `8ed351d` 的 [Lulu APK/JVM #43568418919](https://github.com/jiacimu-droid/lulu1/actions/runs/43568418919) 实际 **failure**：3028 tests、1 failed、1 skipped；唯一失败 `RelicEscapeRulesTest.generatedMapHasRealReachableChestsAndExitOnEverySeed` 第 782 行。此前索引 #43568418916 success **不能替代 APK**。失败根因是旧测试要求每个固定 seed 必有 `PRECIOUS` 箱，与现有 `RelicEscapeWorld` 七档概率真实随机生成相冲突；并非地图不可达，也不能为通过测试强塞保底稀有奖励。
- 测试提交 [`44e2791`](https://github.com/jiacimu-droid/lulu1/commit/44e2791fb2d0571eb707b3bbbef6b8b188d351b4)，`RelicEscapeRulesTest.kt` blob `29bed525`：保留多 seed 的地图、出口与所有宝箱路径验证；改验 0..6 合法品级、`RelicQuality.at(grade)` 对应真实 tier、相同 seed 的箱子品级一致。**只改根因级旧断言，不改真实掉落概率/库存/角色决策。**
- 下一轮：查询包含 `44e2791` 的 main push JVM/APK/index 对应 head SHA；失败查首错，成功后继续 `RelicVaultPage.kt` 筛选→展出/出售→重新进入的 Compose 交互与横屏真机截图，检查真实实体库存和稀有展品。此轮无真机、真实模型与 FPS 证据。

## 2026-11-14 G-03.5 · 开箱演出实物凭证回归

- `593b8cad` 在既有 `RelicQuickActionTest.chestUsesRealActionAndCannotOpenTwice` 中验证多次撬锁后的最终开箱必须产生 `RelicChestPresentation.committed` 实体回执，回执 run/chest 与真实背包物件一致，`isCurrentOpening` 仅承认已开箱，重复对同一前后状态不产生第二份凭证；不修改掉率、游戏物理规则或 UI 演出。
- 测试 blob `de4c2518fae9ef8e29f5db60f3f346fd57b22063`，生产 `RelicChestPresentation.kt` blob `d09b4d1a3dbe836c59ed98d6262da0825ef4ba09`（未改）。本次 CI JVM/APK/index 尚未确认，不能宣称回归通过；真机横屏/帧率、实物落地与撤离入库另验。下一入口：`RelicQuickActionTest.kt` 失败首错或 `RelicChestPresentation.committed` 掉地分支与撤离库存闭环。

## 2026-11-14 G-03.5 · 满包开箱实体掉地与存档回归（工程待验）

- `RelicQuickActionTest.fullSatchelOpeningProducesOnlyGroundReceiptAndSurvivesReload` 以真实规则多次撬锁后开箱，验证已满背包不凭空增加物品、真实珍宝掉在箱位、`RelicChestPresentation.committed` 返回 `inBag=false` 且与权威掉落一致、重复提交不产生凭证、JSON 存档重载仍保留箱盖开启与地面实物。只改测试，未修改概率、奖励、AI 决策或渲染。
- 测试提交 `27208707`、nullable 恢复夹具修正 `794cb4dc`；测试 blob `301107eac7b06b86d723964c8f5d6a953061dbb4`，正式 `RelicChestPresentation.kt` blob `d09b4d1a3dbe836c59ed98d6262da0825ef4ba09`（未改）。当前 GitHub commit status 与 PR workflow 查询未取得有效 push JVM/APK/index 终态，不能标绿；真机掉地拾取、撤离入库、横屏帧率与美术仍待验。下一实际文件 `RelicQuickActionTest.kt` / `RelicEscapeRules.kt`，先核本测试 JVM 首错，再补从地面拾取到真实撤离仓库的完整实体链。


## 2026-11-15 G-03.5 · 地面珍宝拾取后撤离实体回归（CI 待验）

- 新增 `RelicQuickActionTest.groundTreasureMustBePickedUpBeforeItCanLeaveTheRun`：真实满包开箱落地→不拾取直接撤离仅记遗失→真实丢包腾容量→存档重读→拾取同一物品 ID→重复拾取无奖励→撤离成功 `secured` 唯一包含实物且重读保持。测试提交 `d7bb48939845543b8c401d0a92c1b63ad79aaa32`，测试 blob `aae4b416e390480288577320e3dd1727038a1ce0`；生产规则未改。该提交的 Lulu APK/JVM run #43612226879 查询时 pending，索引同 SHA 待终态，不能标通过。下轮先查 JVM 首错与拾取/撤离回归，再进入正式 `RelicEscapeScreen.kt` 的掉地交互与藏宝馆重进；真机/真实模型/FPS 独立待验。

## 2026-11-15 G-03.5 · 地面实物与地标重叠的玩家优先交互

- 正式 `RelicQuickAction.kt` 原先在选中地格时先执行祭坛/钟楼/补给等地标判断，才检查同格真实掉落。开箱满包后宝物若落在仍可互动的地标格，地图主按钮无法直接拾取；玩家只能打开深层背包菜单。`5010cf8` 将有容量的真实 `RelicDrop` 拾取判断移到地标前，仍由 `RelicEscapeRules.act("pickup", lootId)` 验证近距/容量/实体并扣世界回合；无实物时地标仍可操作。没有改变掉率或自动拾取。
- `ae95fe6` 在 `RelicQuickActionTest.physicalGroundLootWinsOverOverlappingActiveLandmark` 加入真实地面珍宝与未消耗祭坛同格的回归：地图按钮必须选择实体拾取，权威背包新增同一 ID、地面消失，祭坛保持未消耗。源码 blob `146a28f9d5fe22da46c471cf2d9e1d828efac469`，测试 blob `04256568b3693e42e5390f16ca2396d73a5c796a`。对应 main push 的 JVM/APK/index 尚未取得终态；不能声称测试或 APK 成功。下一步先看 `ae95fe6` 对应 push JVM 首错，再检查正式地图同格多掉落、背包满容量提示和真机小屏 HUD/拾取/撤离入库；真实模型与帧率独立待验。


## 2026-11-15 G-03.5 · 宝箱与地面实物同格的可拾取闭环（CI 待验）

- 正式 `RelicQuickAction.kt` 原先在同格未开宝箱前返回 `open`，导致满包开箱或其他合法掉落与另一未开宝箱重叠时，地面物品无法通过主操作拾取。提交 `54e9e68d8fcbf5b1b74a8b8d618839faf4746714` 将可容纳的真实 `RelicDrop` 放在宝箱判断前，仍由 `RelicEscapeRules.act` 负责容量、距离、唯一 ID 和实物扣除，不伪造奖励。源码 blob `dd39856d208b646b7d334fd4d0f928a4ce275276`。
- `457f87020af43323f156cf51a72d35d1bce6f8ea` 新增 `RelicQuickActionTest.droppedTreasureOverlappingUnopenedChestRemainsRecoverable`：优先拾取同格地面物品、背包获得唯一实物、地面消失、箱子未被误开、之后可继续开箱，存档重读一致。测试 blob `a80adba43c1c4dc3cf429d6b25e4e5a20a4d7a4c`。
- 查询该测试提交 head_sha 的 Actions runs 返回 0 项，**JVM/APK/index 均未确认成功**；无真机横屏、帧率、模型协作或新美术验收。下一入口：`RelicQuickActionTest.kt` 最新 main push JVM 首错；随后验证满包同格多掉落时的可见容量提示与 `RelicEscapeScreen.kt` 正式地图操作。


## 2026-11-15 G-03.5 · 方向键脚下实物优先拾取（新 CI 待验）

- 正式 `RelicQuickAction.kt` 的 `relicNearbyAction` 之前先检查面朝方向及相邻格的宝箱/地标，最后才看玩家脚下；因此即使脚下已有真实掉落，面对邻格宝箱按中心键仍会撬邻格箱子。`3f261e476dffb30852aa753aef1d3f9132b62858` 只将脚下合法 `pickup` 提前，仍调用原 `RelicEscapeRules.act` 进行物理距离、容量、实体 ID、回合和存档结算；脚下无可拾取物时仍保持面朝方向优先。源码 blob `4c85e1fc4b67f9b90539c72d8a80dbc5441e996f`。
- `f1950d27261028e4dab81047acc3533d35266450` 新增 `RelicQuickActionTest.dpadPicksUpPhysicalLootUnderfootBeforeFacingAnotherChest`：面对相邻未开宝箱时先拾取脚下实体、背包唯一 ID、地面消失、邻箱不误开，随后能继续开箱并重读存档；测试 blob `76d073ba2e5994cbdc31bac8ce8fae7a13fe7f71`。没有改变掉率/角色选择/美术。
- 已确认旧 `457f870` 的 main push `Lulu APK #43623479963` 和索引 `#43623479961` 均 completed/success；较新 `28adf80` 的 `Lulu APK #43628014586`、索引 `#43628014598` 均 completed/success。**这些不是本轮测试 SHA**。本轮查询时生产提交 `3f261e4` 的 APK #43629400242 / 索引 #43629400264 仍 in_progress，测试 `f1950d2` 的新 push 结果尚未出现，故 JVM/APK/index 不能标绿。下一实际入口：`RelicQuickActionTest.kt` 新测试 JVM 首错，`RelicEscapeScreen.kt` 的 D-pad 交互与满包提示；真机横屏、帧率、真实模型协作仍待独立验收。


## 2026-11-15 G-03.5 · 明确选中远处敌人不得误伤旁边敌人（新 CI 待验）

- 正式 `RelicQuickAction.kt` 的 `relicAttackTarget` 原先在选中的活敌人不可攻击时，退而选取其他可攻击敌人，导致玩家明确点远处目标后攻击键可能误打近处另一只。生产 `53456def43d0f5a286cec6953c5fdede04155a6e` 仅在明确选中活敌人但不可攻击时返回不可用；点主角位置时的附近敌人快捷攻击保持原状，不自动攻击、不花回合、不更改角色选择或敌人掉落。源码 blob `194fa6ddf01bf060fc3375773408be9b41e0f48f`。
- 回归 `db8edfa905b3fe62cc5d4fb4ffe0ce3d130c7156` 在 `RelicQuickActionTest.explicitlySelectedDistantEnemyNeverRedirectsAttackToNearbyEnemy` 检查远处选中不可重定向、附近快捷攻击仍可用、明确选择近敌有效、射程提示真实。测试 blob `d98bb2d2114786e55224699312ad9fe06e7c6255`。提交后 main HEAD 与测试提交一致；GitHub combined statuses 为空，PR-only workflow 查询无记录，**不能据此判定 main push JVM/APK/index 成功或失败**。下一入口：核后继 main push 的真实 Actions JVM/APK/index 与 `RelicQuickActionTest.kt` 首错，再查 `RelicEscapeScreen.kt` 攻击按钮禁用提示及 360dp/412dp 真机横屏触控。真实模型、帧率与视觉另验。

## 2026-11-15 G-03.5 · 已击败的手动选中敌人不得重定向攻击

- `RelicQuickAction.kt` 修复手动选中已死亡敌人时错误改打附近活敌人的目标归属缺口；明确选中活敌但超出射程的既有保护保持。`eb9c910220f208f50ec83acfc539c6b5911678db`，源码 blob `4d52c20628f5107dc01ff3f93faa94d371c874ba`。
- `RelicQuickActionTest.defeatedSelectedEnemyNeverRedirectsAttackToAnotherLivingEnemy` 覆盖已击败目标不重定向、未指定目标仍能攻击近敌、指定近敌可攻击；`e92f7235bf0fbd3935f68f9c1a74c19ea5bb9198`，测试 blob `6843ffd9f7102f9ae632ea2849864a748a3cb3c8`。不更改怪物掉落、生命、角色自主意愿或物理回合。
- 已核此前 `db8edfa` 的 Lulu APK/JVM #43634622678 与索引 #43634622606 均 success；**这不代表新测试已通过**。本轮新 push 的 JVM/APK/index 需按 `e92f723` 或后继包含该提交的 head_sha 复核；真机触控、横屏、帧率、美术、模型协作仍未验。下一入口 `RelicQuickActionTest.kt` 新测试 CI 首错、`RelicEscapeScreen.kt` 攻击键禁用状态与实际点选后重进场景。


## 2026-11-15 G-03.5 · 选中空地不得误打另一只怪（生产+回归已提交）

- 根因：`relicAttackTarget` 之前只排除了“选中格仍有怪物”的重定向；玩家明确选中空地（包括怪物移走后的旧位置）时，攻击键仍会自动选附近另一只怪。生产提交 `f0c95e523148addaf5f9870873ecea502962a3df`，blob `69a22aee3f69e535579519797469b4ab31f10d54`：仅当光标位于主角本人格时允许附近快捷攻击，任何其他明确选中的格子都不重定向；不改变攻击伤害、怪物 AI、角色选择、掉率或物理结算。
- `RelicQuickActionTest.explicitlySelectedEmptyTileNeverRedirectsAttackToNearbyEnemy` 新增空地/主角格/近敌格三种目标断言；提交 `a33994fae563931f0b4effbb5a0d31c1832a9375`，blob `af81dd84c48efcee5478cf747bd26ed561307724`。之前 `e92f723` 对应 APK/JVM #43640173370、索引 #43640173377 均 completed/success；本轮测试 SHA 的 APK/JVM #43645324149 pending、索引 #43645324165 queued，**不可标新测试/APK 已通过**。
- 下一实际入口：先核 `a33994f` 的 JVM 首错与 APK 产物，再在 `RelicEscapeScreen.kt` 验证空格选择/主角快捷攻击/战斗 HUD 禁用提示，360/412dp 横屏与真实帧率仍需真机。美术与模型协作未在本轮改动。

## 2026-11-15 G-03.5 · 移动后隐式攻击光标跟随主角（CI 待验）

正式 RelicEscapeScreen.kt 原先仅在开局初始化 selected 为主角格。方向键移动后 selected 仍停旧坐标，先前仅主角格允许自动近敌攻击的修复因此会误禁用正常快捷攻击。现在仅在 ledger 成功提交 move 且主角实际改变位置时，使用 relicSelectionAfterMove 将原本指向旧主角格的隐式光标移动到新主角格；手动点选的其他目标保持不变，失败移动不移动光标。

生产 RelicQuickAction.kt commit f0c5bbed / blob c8d1f0f4；RelicEscapeScreen.kt commit c47f885b / blob ab0d0eb2；测试 RelicQuickActionTest.kt commit b8453c56 / blob ec59e9ca。回归覆盖成功移动、手动目标保持、未移动不变、真实近敌攻击。测试提交的 main push Actions 查询返回零项，JVM/APK/index 未确认通过；真机横屏、帧率、美术和模型协作仍待验。下一入口：按 b8453c56 或后继 main push SHA 查 testPhoneUnitTest/assemblePhone/index 首错，再实测方向键移动后的攻击键和目标光标。

## 2026-11-15 G-03.5 · 显式地图目标不再误操作另一只宝箱（CI 待验）

- 根因：`relicNearbyAction` 在地图光标明确选中远处/空格时，若该格没有可执行互动，仍回退到摇杆朝向并自动撬开另一只邻近宝箱；玩家按中心键会操作并非自己选中的实体。`144f372d251252ce28187f28c41552e96b134fed` 修复：选中合法近处实体仍执行原动作；明确选中无效非主角格时中心键禁用，不改选邻箱；光标位于主角格时继续提供方向键快捷互动。实物生成/掉率/回合/角色决策仍归原规则。生产 `RelicQuickAction.kt` blob `f1aef49dc624dff8854336c2d0b1bd9e1501ffc7`。
- `19141acfbf165ae3b809350cf640b2b3ae808dac` 新增 `RelicQuickActionTest.explicitUnreachableSelectionNeverOpensDifferentAdjacentChest`，覆盖远处/空地不重定向、回到主角格恢复方向互动、显式点中邻箱仍可互动、未实际提交动作时箱子/撬锁进度不变；测试 blob `ab465fdaeb97cf4ca5b164f9fec354b113b025f0`。
- 新测试 SHA 的 Actions `Refresh code index` #43665148848 查询时 queued，Lulu APK/JVM 需继续按 head_sha 取实际结果，不得把索引或旧成功当作新 APK 绿；真机 360/412dp 横屏、触控、帧率、美术与真实模型仍待验。下一实际文件：`RelicEscapeScreen.kt` 中 `GameDirectionalPad` 中心按钮在光标显式选中无效目标时的禁用提示与真机点击；先核 `RelicQuickActionTest` 的 JVM 首错。

## 2026-11-15 G-03.5 · 显式点选邻箱不被脚下掉落抢操作（源码+实体回归待 CI）

- 正式 `RelicQuickAction.kt` 原先先返回脚下可拾取掉落，再检查玩家显式点选的邻箱/救援目标；造成玩家点箱按中心键却拾取另一位置的物品。现在非主角格的显式选中优先执行该格合法实体交互，不能操作时返回空；光标在主角格才优先拾取脚下实物，再走面向快捷操作。生产 commit `c6c70fdb84000baf3abb9297a68c5ded531f46b9`，blob `d9c945fa8b318e293ad9b8f74ff1c4a925de473a`。
- `RelicQuickActionTest.explicitAdjacentChestWinsOverUnrelatedUnderfootTreasure` 用真实撬锁进度、地面物品、拾取入包与存档重载核查两次不同操作互不吞掉；测试 commit `09d5e071deaaa553425595f1934d0ebdbef95561`，blob `21966234dda85a002f691bfc507df75d3bb04db4`。未改掉率/怪物/角色意愿。JVM/APK/index 必须按包含测试的 main push SHA 核，真机触控与帧率未验。下一文件 `RelicEscapeScreen.kt`，验中心按钮在显式点箱和脚下掉落共存时的触控及 360dp 横屏遮挡。

### 2026-11-15 · G-03.5 · 撤离期间点选空地不再误推进守门回合

- 生产 `b0325d4`（`RelicQuickAction.kt` blob `b8916bd7`）：原来 `EXTRACT` 阶段任何无法互动的选中格都会回退成 `wait`，可能把地图选中空地当成主动守门；现在仅主角格提供明确等待动作，地图点选空地不推进世界。角色自主选择及权威撤离规则未改。
- 最终生产 HEAD `d6c8175`（blob `b8916bd7`），保留显式选中目标的合法导航，禁止无关空地自动回退为守门 `wait`；回归最终 `dd5ac07`（`RelicQuickActionTest.kt` blob `224505bd`）：主角格可等待、其他格不自动等待、中心互动不擅自等待、读档回合/背包不变。`dd5ac07` 的 workflow 查询返回空列表（该查询仅覆盖 PR 触发运行），不能据此断言 main push APK/JVM/index 结果；真机与模型待验。
- 下一文件：`RelicEscapeScreen.kt` 核正式地图选中格与守门按钮显示；按 `b417d84` main push 查 JVM/APK/index，不能借旧绿色结果。


## 2026-11-15 G-03.5 · 升级背包容量与正式地图拾取统一（待 CI/真机）

- 发现 `RelicQuickAction.relicQuickAction` 的地面实物候选使用固定 `RelicEscapeRules.CAPACITY=8`，但权威 `RelicEscapeRules.act("pickup")`、正式 HUD 与永久升级均使用 `heroCapacity(run)=8+2*satchelLevel`。玩家花资源升级到 10/12 格后，装满原 8 格时，地图/方向键仍拒绝显示合法珍宝拾取，造成升级收益在正式操作中失效。
- 生产 `6ff2740`（`RelicQuickAction.kt` blob `a22bbe984569ff5257eb4a24ae7774d66b7d8536`）统一从权威容量读取候选，不改物理拾取/奖励/模型选择；测试 `d1cf2ef`（`RelicQuickActionTest.kt` blob `7443a565417f6952a87a1db922fe2bb06e180a7a`）覆盖原 8 格已满、升级后拾取两格稀有实物、方向键与地图候选、实际入包、存档复进及无升级拒绝，避免只改文字。
- 当前仅确认源码与测试提交 main；最新 main push 的 JVM/APK/index 结果仍需按实际 head_sha 核实，真机 360/412dp 横屏点击、帧率、动画和真实模型独立待验。下一文件：`RelicQuickActionTest.kt` 的 CI 首错、`RelicEscapeScreen.kt` 满包/升级背包触控反馈。

## 2026-11-15 G-03.5 · 同格未开宝箱与地面实物不再互相遮蔽（源码/回归待 CI）

- 生产 `1206142d02717d6d88e9c1d621697fdae4d62c0c`，`RelicQuickAction.kt` blob `ea4a93822207272bdfcbd9234708e83f5a6251e2`：此前同一格同时存在未开启宝箱与真实掉落时，快捷动作先返回拾取，导致宝箱一直无法从中心互动打开。现在同格未开箱优先撬锁；已开箱才拾取，保留原权威掉落、容量、回合、仓库和角色自主选择。
- 回归 `55c4c8778fce3f488eb43adf4b4e148ae82398eb`，`RelicQuickActionTest.kt` blob `2c2d09f6179c4374bb52e06bdaa6ff751e00e3ce`：重叠实体点选/方向互动、真实撬锁进度、地面实物不消失、开启后拾取及重新读档。该回归源码已提交，**JVM 尚未确认运行通过**。
- 连接器 commit-workflow 查询只覆盖 PR 触发，本轮返回空；不能据此判定 main push 的 JVM/APK/index。下一实际文件 `RelicQuickActionTest.kt` 检查 JVM 首错及 `RelicEscapeScreen.kt` 真机同格点击/横屏 HUD；真机、真实模型、帧率及美术审图仍待验。

## 2026-11-15 G-02.3 · 未完工房屋蓝图占地保护（源码/回归已提交，CI 待验）

- 正式 `BlockIslandRules.canPlaceBuilding/canPlantAt` 现在拒绝在 `building/paused` 房屋工程尚未铺设的九格预留位置插入独立墙体/家具或树苗；否则原 `house_step` 的物理施工会因地块被改动而永久卡住。保留已铺设格、拆除/完成后自由重建、原资源扣除和伙伴决定权；无假建筑。源码 `168b0f5`、`cebf67d`，最新生产 blob `c3b22e5f4eac6538710bfd60ef2d91bab3692495`。
- 新增 `BlockIslandRulesTest.unfinishedHouseBlueprintCannotBeOverbuiltAndSurvivesReload`，覆盖蓝图格禁止独立建墙/种树、拒绝不扣资源/回合、重读存档继续占地、工程移除后可建造。测试 `5568acd`，blob `335e5e4895f19dba95f0c01274d5adc2f3b18b4e`。**JVM/APK/index 未取得本 SHA 的 push 工作流终态，不得标通过**；真机横屏建造预览/触控/FPS 和人物协作独立待验。下一实际文件 `IslandBuildingControls.kt` 与 `BlockIslandRulesTest.kt`，核预览拒建原因与 JVM 首错。


## 2026-11-15 G-02.3 · 房屋蓝图预览与终局禁建一致性（CI 待验）

- 正式 `IslandBuildingControls.preview` 原先即使 `world.finished` 仍可能返回 `allowed=true`，与「无法继续建造」提示冲突；已有房屋工程时尝试另一张蓝图只提示空地不足。`51536db` 将终局预览统一禁用并明确提示当前工程须完成/续建/拆除，仍由原 `BlockIslandRules.act` 执行实际施工及扣料，无影子建筑或奖励。
- `IslandPlayerHouseTest` 新增蓝图占用、终局各建造入口禁用和预览不改真实存档回归：`19099e3`、`2b26a19`；生产 blob `7d095d9a727d7f3fc40c838dbf339adcfcee7ac9`，测试 blob `51f508f8b0b4ceb294e1893ea26ab011c97a1e04`。
- main push `2b26a197` 的 Lulu APK/JVM #43701707170 pending、Refresh code index #43701707195 queued（查询时），**未证明 JVM/APK/索引成功**；前两次 push 均被新提交取消。下一入口 `IslandPlayerHouseTest.kt` 的 JVM 首错与 `BlockIslandScreen.kt` 的预览按钮禁用行为；Redmi 横屏/360dp/412dp、FPS 和同伴模型仍待独立验收。

## 2026-11-15 G-03.5 · 正式战斗 HUD 与地图点选同源（CI 待验）

- 根因：`RelicEscapeScreen.kt` 主攻击按钮与战斗提示一直读取 `RelicCombatSkills.attackTarget(run,facing)`，忽略地图光标；即使 `RelicQuickAction.kt` 已对显式点选的敌人、空地和死亡目标禁止重定向，主按钮仍可能攻击朝向上的另一只敌人。正式屏幕现改用既有 `relicAttackTarget(run,selected)`，攻击键、提示统一使用既有 `relicAttackHint`，不改变敌人血量/掉落、武器射程或 AI 角色意愿。生产 commit `11aae0604538e5cf2560476953eacae4e183c2b3`，`RelicEscapeScreen.kt` blob `b2949f99c2720d5deffa71c7e6c1e65b5f77524e`。
- 已有 `RelicQuickActionTest` 覆盖显式点敌、空地、已击败目标、主角格快捷攻击及移动后光标跟随，不重复添加同类测试。此前 `2b26a197` 的 main push APK/JVM #43701707170 和索引 #43701707195 已 completed/success，但不代表本次改动通过；本次 `11aae060` 对应 APK/JVM #43710916717 与索引 #43710917374 查询时 queued。下一入口：该 SHA 的 `testPhoneUnitTest`/`assemblePhone` 首错，以及 `RelicEscapeScreen.kt` 360/412dp 横屏点敌→攻击、点空地→不误伤、移动后攻击、低端机帧率实测；真实模型与美术审图另验。


## 2026-11-15 G-03.5 · 战术目标不可达时禁用移动键（CI/真机待验）

- 正式 `RelicEscapeScreen.kt` 战术页此前仅以 `visible` 启用「向目标一步」「潜行一步」，但已探明目标可能被墙、守卫或其他障碍阻断；`RelicEscapeRules.path` 返回 null 时按钮点击无任何动作/回执。生产提交 `992f411890ec07c42df684e2a2cfb35ba89e617b` 改为用同一权威 `path` 结果同时控制按钮可用状态与实际下一步，禁止已知不可达目标的空点击，不揭雾、不移动、不扣资源、不替角色决定。源码 blob `7aa52f001ad680265026914a96732b0170bddb69`。已有 `RelicObjectiveRouteTest` 对敌人/迷雾阻路和路径恢复覆盖规则，本轮不重复造同类路径测试；Compose 矮横屏触控仍待设备验证。
- 本轮 main push 的 commit statuses 为空，PR-only workflow 查询无记录，均不代表 APK/JVM/index 通过；下一入口按 `992f411` 对应 main push 的 Actions JVM/APK/index 终态核实，再看 `RelicEscapeScreen.kt` 战术页目标可达/不可达状态及 360dp 横屏实际点按；真机帧率、原创美术审图和真实角色协作独立待验。


## 2026-11-15 G-03.5 · 地图点选手势稳定性（待 CI/真机）

- `RelicEscapeArt.kt` 的 Canvas 点击手势原先把每次 Compose 重组产生的 `onSelect` 回调作为 `pointerInput` key，可能导致地图动画/选择更新时取消并重建点击识别器。生产 `caf5b6483d50cd209e73fab6bf6681adeb04a6db` 改用 `rememberUpdatedState(onSelect)`，key 仅保留地图尺寸、场景及焦点等真正需要重启手势的条件；仍使用权威 `relicMapCamera.tileAt` 和原选择回调，不改移动、掉落、战斗或人物决策。源码 blob `0dd323902a406a508605b0f47fdbc22e5d38cc96`。
- 本轮无新增 JVM 测试（属于 Compose 手势生命周期问题，不能用规则测试假装触控验收）。前一 `282a2a5` 对应 Lulu APK/JVM #43721148872 已 completed/failure（build 的 Test and build phone APK 步骤失败），不能算绿；本次提交及交接后的 main push 需独立核 JVM/APK/index。下一实际入口：`RelicEscapeArt.kt` 360/412dp 横屏连续点选/拖拽与手势不中断、`RelicEscapeScreen.kt` 地图 HUD，以及新 SHA APK 首错；帧时间和美术仍待真机。
