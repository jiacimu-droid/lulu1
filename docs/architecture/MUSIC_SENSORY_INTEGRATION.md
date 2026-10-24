# 露露机音乐感知接入说明（Cove Sensory MCP 启发）

依据：Cove Sensory MCP (Apache-2.0)，https://github.com/moonlin1213/cove-sensory-mcp 。本项目未引入 Python stdio MCP 运行时、外部依赖或拷贝代码；按 Android 现实权限单独实现音频文件感知。

## 接通的真实路径

角色设置 → 主动执行授权 → 分享音乐给角色听。选择已有 API 配置与支持音频的模型，选择 OpenAI-compatible 的 audio_url data URI 协议或 Gemini 原生 generateContent；再用系统文件选择器选择真实音频文件。使用用户授予的 content URI、受限一次性载入、基础 MIME 校验和 HTTPS 请求。选中的音频发送给用户自己设置的供应商；可能收费、留存。原始媒体不写入时间线和额外文件。

每次成功识音，记录一条来源为 sensory-music 的 Observation 于同一角色 SharedExperienceTimeline。设备感知只把该角色最近48小时、来源仍有效的感知证据投影到统一主循环；长期历史保留在时间线，不以48小时删除。删除时间线事件后缓存仅剩无效指针，下次不能将已删除描述注入角色上下文。按音频 SHA256 + 角色 + 模型/供应商线路去重，以真实仍在的事件为缓存权威；不会因为前台切后台重播音频或无端产生新模型调用。失败不写观察，不伪造已听过。

Android MediaSession 原本只获取到曲名，此次增加歌手、专辑，但明确提示元数据不是已听到音频的证据。**没有实现自动截取/录制其他音乐 App 的播放流**（涉及 MediaProjection 和播放器禁止捕获等条件），没有自动查词、没有持续监听、没有从歌曲标题猜歌词或反推主人偏好。音频分析不决定角色说什么，只提供可质疑的证据，任何欣赏与社交行为由同一角色决策模型选择。

## 线路限制与手动验收

- Gemini 原生需要 `https://generativelanguage.googleapis.com/v1beta` 风格的 API 基址、支持音频的 Gemini 模型；若保存的是 OpenAI 中转 `/v1`，不要误选原生模式。
- OpenAI-compatible 仅支持厂商实际接受 `audio_url` 内嵌 data URI 的线路，例如支持该协议的 SiliconFlow 音频模型；普通文本聊天模型、其他厂商的 `input_audio` 格式不会自动适配。
- 12 MiB 限制仅作用于本次内嵌上传的音频请求体，非用户持久音频、存储、历史记录的总量上限。较大的曲目需手动选合适的片段；原始文件不修改。
- 尚未实际运行 Android APK 编译、真机 SAF 文件权限与收费模型实测；这些是发布前必须核对的边界。
- 对第三方感官模型的误识别、不确定信息，必须保留来源，不可强制当作客观真相或人物稳定喜好。

## 2026-10-23 收尾：whale-listen 对比与离线感知

- `migratorywhale/whale-listen`（MIT）用 Basic Pitch ONNX 做音符转录、音符密度、音高与静音分析；它不分析歌词大意，不区分歌手真实情感，也不适合直接把整套 Python 依赖装进 Android。现阶段继续采用 Cove 风格的多模态语义听觉作为主要理解线路。
- 自主实现 `MusicSignalSketch.kt`：只处理验证过的 RIFF/WAVE、单声道/双声道、16-bit PCM，统计真实音频每两秒的 RMS 能量和相对高低；纯 Kotlin、不额外调用 API。**不宣称分析音高、MIDI、BPM、和弦、乐器、歌词或感情。** 无供应商配置时用户可选 WAV 获取此项免费观察；音频格式不兼容则明确失败。
- `LuluMusicSensoryRuntime.kt` 在真实付费语义感知旁附加本地波形证据，使用互斥锁避免同应用内同时请求同一音频的重复扣费。缓存身份同时涵盖用户角色、音频内容哈希、API 类型、基址和模型；旧版本无供应商来源的缓存不会直接冒充同一线路。原始事件删除后缓存指针不再生效。
- `CharacterMusicSensingSettings.kt` 显示 WAV 免费线路，即使未配置 API 也可以选择音频；`MusicSignalSketchTest.kt` 覆盖静默/强声段落、非 PCM 格式和截断输入。新增的是**波形结构**，不是第三方音乐 App 的后台声音捕获。
- 尚未证明在用户手机的音乐 App、厂商模型、完整歌词或更大文件上真实有效。APK 构建成功、真机文件选择、不同模型兼容性和长时间使用需要分别核验。首次升级更换线路可能重新计费，缓存只省去同模型同音频的重复分析。供应商错误不会被当作已完成语义识别。

参考：
- https://github.com/migratorywhale/whale-listen
- https://github.com/moonlin1213/cove-sensory-mcp

## 2026-10-23 · 身心属性与简体字幕验收

「此刻」只读现有数字身体五项与 CharacterInnerLifeStore 的有效情绪投影，未给每个角色建立第二套属性。CharacterLifeStore 的同一角色 JSON 记录最近一次重要身体变更前后差值与实际写入来源，原始活动仍归时间线。情绪轴未记录时显示缺失，不显示伪造的零值；正常衰减不产生额外模型调用。本地 Whisper 转写结果在送往现有字幕/对话前用 Android ICU Traditional-Simplified 转换显示，不重新请求 API，也不纠正原有语义识别误差。

## 2026-10-23 · 一起听房间

- 私聊工具栏「一起听」进入同一角色的听歌场景：支持手机其他音乐 App 暴露的 MediaSession 曲名/歌手信息；观察不到并不代表没有音乐。手机外部播放器**不提供可保证的声波访问**，不得让角色伪称听过。
- 本地音频使用 Android MediaPlayer 正式播放，沿用 SAF 长期授权与上次播放头；按下「播放并分享」会调用现有 LuluMusicSensoryRuntime.senseSelected，将真实音频感知写入同一角色统一时间线（可能调用收费模型）。播放与分析是独立事实：未得到成功感知回执时不能宣称听懂。暂停后恢复不会在同一个房间里重复提交同一 URI；失败可手动重新分析；成功结果还会按音频哈希和感知线路跨房间去重。
- 音乐 App 的当前歌曲仍只有 MediaSession 元数据；房间提供「提供音频 · 让角色真正听」转入系统文件选择器。即使曲名显示正确，也不意味着取得其他 App 的声波；若需要声学内容，必须选择可访问的对应音频文件。
- 房间中与角色发言复用原私聊回复流程，成功感知回执经 UserDevicePerception.context 进入统一人物上下文，无独立角色模型和第二人格。此版本没有 Android MediaProjection 捕获第三方输出，没有声卡级同步、后台播放保证或逐秒连续感官模型推理。
- 一起听入口与「一起看」在同一聊天工具栏。音乐 App 的当前歌曲和本地文件都是显式授权/用户请求后才记录为共同体验，未拿到真实分析回执不报‘听懂了’。
