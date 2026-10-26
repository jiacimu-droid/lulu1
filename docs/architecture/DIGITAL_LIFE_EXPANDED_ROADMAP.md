# 数字生命研究与实施总表（扩展36条）

日期：2026-10-26。需求：主人将筛选、排期和实现交由项目协作方处理，体验后反馈；原则以当前AGENTS与统一人物契约为准。原1–20编号保留，21–36补充世界、声音、记忆及评估方向，部分细化原有大类。条目包含项目、论文、模型和验证方法，数量不等于已安装项目数，也不是实时世界排名。

## 当前先做什么

P0：优先直接改善真实陪伴与可操作性。此次实现独立文字表达存档，以及可选Jev原生批量方向参考；均不默认更改用户模型、不每轮增加固定收费调用。原9条部分吸收的结果保持，第18条新增“已有阶段可分别选模型”的工程落地，不能视为已测试所有品牌。

P1：在现有闭环上继续改，涉及新服务先核实协议、费用和设备；模型效果用相同人物/记忆/任务比较。P2：需要新增GPU、3D运行时或真实服务预算的原型，先保留资料，不常驻启用。暂缓：重复或明显不适配的整套迁入方案。

| 编号 | 优先 | 项目/模型 | 能改善的体验 | 采用方法、费用与当前状态 |
|---|---|---|---|---|
| 1 | P0 | SocioMind | 心理与经历连续 | 已部分借鉴到原心理记录/召回，无新模型轮次；不移植独立心理库。 |
| 2 | P0 | M2-her | 中文角色表达、人物差异 | 表达一致性思路已借鉴；本次表达存档可独立选择兼容文字模型。M2-her具体协议/64K预算仍需实测，不代表已自动切换。 |
| 3 | P0 | Jev / Decisions | 多方向逐项考虑 | 此次新增可选原生 `consider_directions` 工具；一包多问、全结果回流、同请求不重复付费，不用概率替角色行动。详见专门指南。 |
| 4 | P0 | Graphiti / JAM | 有来源、可修正的记忆 | 已补原事件语境与历史解释；不再建图数据库或第二套事实库。 |
| 5 | P1 | Proactive AI 时间线 | 真实跟进、承诺到时行动 | 继续用原持久日程和唤醒；已补主角逐动作联系边界、居民到期撤销竞态与独立/依赖步骤交接，复用同一OrderedCharacterActions，不新增平行调度器。 |
| 6 | P0 | AI Town / Generative Agents | 世界里行动确实改变状态 | 已修活动失败过早结束原休息；补齐居民真实多步依赖、每步动机关联及旧执行ID重放，移除单独简化执行循环与第9步起静默丢失；继续沿原世界状态与回执，不搬Convex。 |
| 7 | P1 | nanoMuse | 手机后台目标持续与恢复 | 复用Android现有执行器；基础重叠，不复制GPL代码或另建控制服务。 |
| 8 | P0 | ProMemAgent | 遇到情境想起旧打算 | 已部分借鉴到动机候选召回；检索命中后由本人判断，不等于等待条件已满足。 |
| 9 | P0 | ReasoningBank / Hermes | 用得上旧成功和失败 | 已补经验适用/停止条件检索；不训练模型或自动认证永久方法。 |
| 10 | P0 | Reconsider | 回忆的时机和分寸 | 已接原记忆上下文内的取舍指导，无额外记忆判断模型。 |
| 11 | 暂缓 | Soul of Waifu | 身体、情绪、动机耦合 | 先审原状态来源与主观理解，已有基础重叠；不复制Windows/GPL模块或固定激素阈值。 |
| 12 | P1 | AIRI / DLP3D | 表情、动作、口型与呈现 | 保留小狼Live2D路线；有用部分需对原参数/语音时间线核验，不直接换3D身体。 |
| 13 | P2 | Qwen Omni Realtime | 听看与实时反馈 | 独立候选，需真实音视频协议、手机延迟、上下文和费用验收；未替换通话。 |
| 14 | P0 | ProAgent / ProV-Mem | 视觉证据可靠与适时反馈 | 已部分借鉴采集时间/归属/授权有效性；没有新增高频识图或固定开口阈值。 |
| 15 | 暂缓 | OASIS | 居民社会活动 | 目前不搬大规模社会模拟器；少量居民与朋友圈继续用原系统。 |
| 16 | P2 | Project Sid / PIANO | 长目标与社会协作 | 仅研究分工，不迁Minecraft社会引擎或多收费脑区；不拆分角色人格。 |
| 17 | P1 | MiniMax / DeepSeek 等文字模型 | 性价比、理解及工具选择 | 待同人物真实多轮对照；按实际中转账单计算，不把官方token价当用户按次价。 |
| 18 | P0 | Kimi / GPT / Gemini 等模型 | 思考与文字分别选型 | 此次独立表达存档已接现有阶段；单次在线私聊仍一次决定直接发言，不强制再润色。未更换用户思考型号。 |
| 19 | P1 | memU / Haiku | 记忆整理与成本 | 原提取连接继续用；先测漏记、误归属、修正与撤回，不新增第二记忆后端。 |
| 20 | P1 | ASR / sherpa / Fish / MiniMax Speech | 听清、好听、低延迟 | 按ASR/TTS分别选；新本地库先测中文名字、速度、发热、下载和许可。 |
| 21 | P1原型 | HY-World 2.0 | 可探索的持久场景资产 | 重点评估离线生成/导出后复用。手机需资产LOD/碰撞/渲染测量；生成环境有算力成本，未部署。 |
| 22 | P2 | HY-WorldPlay | 相机/动作驱动的视频世界 | 持续推理有算力/传输成本；不把画面里的物品、动作当原世界执行事实。未启用。 |
| 23 | P2 | SolarWM / H3 Realtime | 可交互的连续视频场景 | 10月22日发布实时交互路线；服务器生成与手机渲染分开评估，未租GPU、未下载权重。 |
| 24 | P2 | AlayaWorld | 长时视频世界一致性 | 关注长时观察与相机控制，先原型核验漂移/时延；未作为世界事实权威或常驻服务。 |
| 25 | P2 | World Labs Marble / World API | 异步生成可探索3D环境 | 可比较按次生成后复用；API需独立账户与报价，不把网页订阅当API额度，未购买调用。 |
| 26 | 暂缓 | Genesis World | 物理交互与具身实验 | 适合研究碰撞/物理接口；Python/GPU仿真不直接放入Android伴侣主循环。 |
| 27 | P2 | Habitat-Sim | 场景导航和可复现具身测试 | 可用于未来服务器/开发环境验证世界行动，当前不增加手机运行依赖。 |
| 28 | P1 | Qwen3-ASR | 中文、方言及语音转写 | 0.6B/1.7B候选，区别于文字思考模型；先评适配/量化/流式/名字和手机耗电，未替换Whisper。 |
| 29 | P1 | Qwen3-TTS | 角色声音、韵律和流式合成 | 可作为独立语音服务候选；部署与原音色迁移、长段稳定性另验，不靠更换聊天模型获得声音。 |
| 30 | P1 | IndexTTS 2.5 | 可控情绪、语速、发音 | 当前官方路线已到2.5，列为独立TTS候选；实际中文/长段体验及授权音色另测，未迁入权重。 |
| 31 | P1 | CosyVoice 3 | 流式语音与自然韵律 | 比较小模型服务成本、首包/停顿/中文稳定，不据宣传直接替换现有声音。 |
| 32 | P1研究 | Letta | 状态化记忆与上下文管理 | 借鉴记忆职责与验证方式；本仓已有时间线/心理Store，不整体接入另一agent服务器。 |
| 33 | P1研究 | Hindsight | 事实、经历与认识的区分 | 研究保留/召回/反思边界，先对照原来源撤回；不复制默认偏好或增加固定反思调用。 |
| 34 | P1 | LongMemEval | 长期记忆验收 | 建本仓相似情境：旧信息更新、时间顺序、跨轮证据与无依据问题；不宣称跑过外部正式基准。 |
| 35 | P1研究 | Mem0 | 记忆提取和更新 | 对照新增/纠正/删除链路与开销，优先修已有权威存储；没有另存第二份用户画像。 |
| 36 | P2 | WorldFoundry | 世界模型推理与评估统一 | 用于未来比较场景/视频模型的候选基础设施，不作为新世界生成模型或手机依赖。 |

## 世界模型怎么用才值得

本项目要分别维护三层：①人物自己的心理和选择；②可追溯的地点、物品、所有权、行动与独立回执；③生成/渲染出来的空间画面。世界模型优先改善第三层，第二层继续由原执行器提交，不能由视频中“出现一个包”就认定角色买包成功。

先比较“生成一次可保存的场景/全景/3D资产”，复用时不需每帧重新推理；资产存储、手机解码渲染和修改场景仍有成本。连续视频生成则还要持续GPU、流量和状态一致性预算。开源指代码/权重开放程度，不代表推理免费，也不代表代码、权重、训练素材和声音拥有同一许可。

**本批工程判断**：优先生成后复用的小场景原型；不在主人手机里直接运行大型视频模型，不常驻购买GPU。目前没有服务器或手机测量，因此不写“每小时多少钱”、不宣称已把3D世界装进APK。

## 验收与后续顺序

1. 本批先验收模型分工、Jev可配置/可关闭、批量回执、授权撤销、失败可见及幂等；不根据假接口对话给实际模型打分。
2. 随后的体验优先看：想法多样性、既定意图能否自然表达、真实行动和回执能否被后续理解、跨场景人格与记忆连续。
3. 世界资产原型先确定一种可导出的格式与一处小场景，测手机占用、加载、移动和状态同步，再决定渲染方案。预算和真实账户不可由README推断。
4. 声音按ASR/TTS分别做同样音频/句子的对照，再选择适合既有通话线路的方案。记忆方案先做来源、修正、时序和撤回的本仓回归。

## 新增官方研究入口

- HY-World 2.0：https://github.com/Tencent-Hunyuan/HY-World-2.0 ，https://github.com/Tencent-Hunyuan/HY-World-2.0/blob/main/DOCUMENTATION.md
- HY-WorldPlay：https://github.com/Tencent-Hunyuan/HY-WorldPlay
- SolarWM：https://github.com/Junchao-cs/SolarWM ，https://github.com/Junchao-cs/SolarWM/blob/main/src/solarwm/realtime_interaction/README.md
- AlayaWorld：https://github.com/AlayaLab/AlayaWorld
- World API：https://www.worldlabs.ai/blog/announcing-the-world-api ，https://platform.worldlabs.ai
- Genesis：https://github.com/Genesis-Embodied-AI/genesis-world
- Habitat-Sim：https://github.com/facebookresearch/habitat-sim
- Qwen3-ASR：https://github.com/QwenLM/Qwen3-ASR
- Qwen3-TTS：https://github.com/QwenLM/Qwen3-TTS
- IndexTTS：https://github.com/index-tts/index-tts
- CosyVoice：https://github.com/QwenAudio/CosyVoice
- Letta：https://github.com/letta-ai/letta
- Hindsight：https://github.com/vectorize-io/hindsight
- LongMemEval：https://github.com/xiaowu0162/longmemeval
- Mem0：https://github.com/mem0ai/mem0
- WorldFoundry：https://github.com/OpenEnvision/WorldFoundry

原20项更详细的吸收记录与来源继续保留在 [DIGITAL_LIFE_RESEARCH_ADOPTION.md](DIGITAL_LIFE_RESEARCH_ADOPTION.md)；型号分工见 [COMPANION_MODEL_COMPARISON.md](COMPANION_MODEL_COMPARISON.md)，Jev接入/费用见 [JEV_DIRECTIONS_GUIDE.md](JEV_DIRECTIONS_GUIDE.md)。
