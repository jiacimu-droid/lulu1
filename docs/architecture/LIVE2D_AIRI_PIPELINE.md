# Lulu1 Live2D / AIRI 分层渲染架构（2026-10-22）

## 目标与边界
本次参照 AIRI `packages/stage-ui-live2d/src/composables/live2d/motion-manager.ts` 的 pre/post/final 分层思想和 `motion-control-spring.ts` 的阻尼模型，**不**直接复制其 Vue 运行时、Unity 组件、模型素材或插件 API。Lulu1 继续采用 Android 电话统一会话 + WebView/Pixi Cubism4 + Android 原生帧显示兼容通道。没有取得官方 Cubism Native SDK 之前，不能假称已完成 Android 原生 GPU 直出。

## 事实与角色意图
`LuluVoiceCallSession` 保持唯一通话 ID、语音/TTS 与原始事实时间线。普通电话和 Live2D 视频共享这条会话；`InteractionSignalBridge` 的设备回执描述真实视频显示和模式切换。角色仍然只在原有模型回复里自主选择 `live2d` / `beats`，`Live2DVisualActionBus` 必须对当前模型实际参数与 exp3/motion3 文件进行验证。渲染层不自主做情绪判断、不会创建第二人格或调用新 API。

## 渲染阶段与所有权
1. **Cubism Core / Motions / Expressions / Physics**：模型作者的材质、眨眼、表情和耳朵、头发等原始形变。
2. **`visual-layers.mjs`**：使用 `cubism-parameters.mjs` 的真实参数索引，在 `beforeModelUpdate` 阶段运行。首要默认行为是正视摄像头的 neutral gaze 与头部；角色要求的头/身体由 `pose-spring.mjs` 有阻尼地进入、离开；物理的第二层头/身体跟随只在主动姿态时保留；对于模型确实支持的 `ParamBreath`，用轻微、独立的呼吸代替随机摇头。非选中脸部参数由原始模型表情持有，已选中表情在结束后归还。模型不存在的参数不会被伪造。
3. **自然眨眼**：只在模型无 EyeBlink 参数组且眼睑实际存在时临时覆盖眼睑；不扰乱微笑/眯眼的基础开合。
4. **真实语音口型**：实时音量优先，缺少 PCM 时仅在确实播放语音期间采用兼容口型；说完渐进复位，不因只有文本或排队中的 TTS 就张嘴。
5. **输出**：Cubism `model.update()` 绘制帧，再由经过测量的 Android 视频桥接送到 ImageView。不能把 WebGL ticker 帧率等同于 Android 真正显示的 FPS。

上述步骤的 Hook 选择是有意的：`beforeModelUpdate` 位于模型自带呼吸/物理更新之后，可避免头部参数被稍后覆盖。不得把最后参数控制退回到 `motionManager.update` 前面。

## 语音动作时间线
`speech-performance.mjs` 专门持有单次角色已授权的、经验证的 `beats`；采用 `performance.now()` **单调时钟**，不再使用 Pixi 的 delta / JPEG 解码间隔累加计时。没有播放就排队，播放器进入 playing 才开始，在 finish/interruption 时清空剩余动作；重复 playing 不重播，同一会话的后续新动作替换旧计划；后台暂停与换模型时清理残留。渲染帧跳过时会在下一个帧按顺序追赶已到期动作，不额外呼叫模型。Android 对一次回复批量派发单个 JS 事务，避免眉眼和歪头在不同帧里被分别下发。

## 测试与风险
- `node tools/live2d/test/parameters.mjs`：真实 Cubism 参数索引、自然静止、头部动作复位、面部参数交还、呼吸与单调时钟动作演奏、低帧率及取消条件。
- GitHub Actions 中继续运行 Android 单元测试、打包 APK；没有额外工作流，也不绕过旧兼容渲染路线。
- 真机必须实测口型、连续眨眼、连续/中断 TTS、背景恢复、动画卡顿、实际 FPS / p95 和视频切语音后记忆回执。
- 架构分层可以改善动画一致性和调度成本，但**无法单独解决每帧 WebGL 读回 → JPEG → Android 解码的性能瓶颈**。将来要达到 GPU 直出，应正式引入已获授权的 Cubism Native SDK，并保留兼容路线以便回退。
