# 露露机音乐感知接入说明（Cove Sensory MCP 启发）

依据：Cove Sensory MCP (Apache-2.0)，https://github.com/moonlin1213/cove-sensory-mcp 。本项目未引入 Python stdio MCP 运行时、外部依赖或拷贝代码；按 Android 现实权限单独实现音频文件感知。

## 接通的真实路径

角色设置 → 主动执行授权 → 分享音乐给角色听。选择已有 API 配置与支持音频的模型，选择 OpenAI-compatible 的 audio_url data URI 协议或 Gemini 原生 generateContent；再用系统文件选择器选择真实音频文件。使用用户授予的 content URI、受限一次性载入、基础 MIME 校验和 HTTPS 请求。选中的音频发送给用户自己设置的供应商；可能收费、留存。原始媒体不写入时间线和额外文件。

每次成功识音，记录一条来源为 sensory-music 的 Observation 于同一角色 SharedExperienceTimeline。设备感知只把该角色最近48小时、来源仍有效的感知证据投影到统一主循环；长期历史保留在时间线，不以48小时删除。删除时间线事件后缓存仅剩无效指针，下次不能将已删除描述注入角色上下文。按音频 SHA256 + 角色去重，以真实仍在的事件为缓存权威；不会因为前台切后台重播音频或无端产生新模型调用。失败不写观察，不伪造已听过。

Android MediaSession 原本只获取到曲名，此次增加歌手、专辑，但明确提示元数据不是已听到音频的证据。**没有实现自动截取/录制其他音乐 App 的播放流**（涉及 MediaProjection 和播放器禁止捕获等条件），没有自动查词、没有持续监听、没有从歌曲标题猜歌词或反推主人偏好。音频分析不决定角色说什么，只提供可质疑的证据，任何欣赏与社交行为由同一角色决策模型选择。

## 线路限制与手动验收

- Gemini 原生需要 `https://generativelanguage.googleapis.com/v1beta` 风格的 API 基址、支持音频的 Gemini 模型；若保存的是 OpenAI 中转 `/v1`，不要误选原生模式。
- OpenAI-compatible 仅支持厂商实际接受 `audio_url` 内嵌 data URI 的线路，例如支持该协议的 SiliconFlow 音频模型；普通文本聊天模型、其他厂商的 `input_audio` 格式不会自动适配。
- 12 MiB 限制仅作用于本次内嵌上传的音频请求体，非用户持久音频、存储、历史记录的总量上限。较大的曲目需手动选合适的片段；原始文件不修改。
- 尚未实际运行 Android APK 编译、真机 SAF 文件权限与收费模型实测；这些是发布前必须核对的边界。
- 对第三方感官模型的误识别、不确定信息，必须保留来源，不可强制当作客观真相或人物稳定喜好。
