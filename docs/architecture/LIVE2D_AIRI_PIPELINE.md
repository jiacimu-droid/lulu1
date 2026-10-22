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

## 2026-10-22：身体参数所有权、视觉表达与语音同步优化

参考 [ai-live2d-body](https://github.com/zziying/ai-live2d-body) 的「大脑与身体分离、被动反射与主动表达分层」以及 AIRI 的最终渲染阶段。本轮只优化原有 `tools/live2d` WebGL 执行器，不新建角色、记忆、API 线路或电话会话。

1. **Cubism 参数边界**：从实际 Moc3 读取参数索引、最小值和最大值，并在加载后缓存；最终写入不再假定所有模型都支持同样的角度／眼形区间，也避免每帧重复查询模型元数据。
2. **角色自主表情的控制权归还**：`visual-layers.mjs` 在角色主动控制眉眼、笑容前保存原作者的表情姿态。临时 cue 结束时平滑归还到原表情或此时已经变化的作者表情，左右眼分别处理，不将所有表情硬复位为 0/1；静态目光保持镜头居中。
3. **声音相关而非随机抖动的嘴角**：只有播放器提供真实 PCM 幅度时，主动笑意才随实际声音能量有轻微变化；没有 PCM 不伪造振幅、不按正弦波随机抽动笑容。嘴巴张合仍由原音频路线驱动，专门占据口部的动作仍需实测静音间隙或语音结束。
4. **特殊眼形优先**：导入 exp3 明确操作眼睑、眼形，或表情名称具有明确星星眼／爱心眼／闭眼语义时，后备程序眨眼暂时让位，不抢同一个 Cubism 物理参数。结束后恢复自然眨眼；没有资源不能虚构新的动作。
5. **播报事件去重**：播放状态轮询与真实 utterance 事件可能同时上报 playing。重复回调不重置同一句的表情节拍／模拟口型起点。表情不要求额外大模型请求。

新增 `tools/live2d/test/parameters.mjs` 回归：实际参数范围、原生笑容归还、单眼左右不同的眼形恢复、期间原作者表情更新、星星眼眨眼保护，以及现有音频节拍和中断门槛。测试源码断言可在 JS 环境执行；正式 GitHub Actions `node tools/live2d/test/parameters.mjs` 和 Android APK 仍以其实际运行结果为准。

**仍未改变**：Android 兼容画面使用 WebGL → JPEG → Android Image；本轮不能宣称已经 GPU 原生直出或彻底解决卡顿。触摸角色→真实通话感知回执也未在本轮接通，后续实现必须复用同一电话角色决策与权限，不可建第二个人格／收费回合。当前机型仍需实测：星星眼播放/恢复，完整发言、半句停顿、音频中断、连续说话、长通话的清晰度和 p95 帧间隔。
