# Live2D 模型能力驱动计划 · 小狼
2026-10-24 · 仅记录从主人上传的模型 JSON 可验证的参数能力，不上传模型贴图或 moc3。

## 事实与原则
- 文件含 model3、cdi3、physics3、VTubeStudio 映射与 9 个 exp3；没有独立 motion3 片段。
- cdi3 确认的语义参数不是承诺每个动作都有好看的形变：最终仍由 moc3 rig 和实际参数极值决定。
- 每帧写入前使用 Cubism Core 真正存在的参数 ID 和最小/最大值验证，缺少即跳过，严禁伪造新的参数；适用于导入的其他模型。
- 视觉层只能落实已选择的情绪、动作与真实音频/触摸。不得由随机表情默认替角色决定性格。
- 实际嘴型目前只有 PCM 能量，**没有音素时间轴**，因此实现的是自然幅度、嘴宽/下巴轻量联动，不伪称逐字 A/E/I/O/U 唇形同步。

## 小狼专属运行策略
- 仅当 Moc3 真实拥有 `ParamCheekPuff`、`ParamJawOpen`、`ParamAngleZ2`、`body_gensui_z`、`ParamMouthPuckerWiden` 与 `ParamBrowLForm` 才加载此配置。避免文件改名后失效，也避免把小狼形变强加给别的骨架。
- 核心调参统一在 `tools/live2d/src/wolf-rig-profile.mjs`：嘴巴不同起落时间、眉眼音量强调、脸部触摸、头身 Z 轴反射。
- 常规运行仍统一从 `stage.js` 的最终 Cubism 更新阶段注入，不创建第二套模型或独立动画循环。

## 计划表（当前实现）
| 优先级 | 场景 | 模型资源 | 接入途径 | 验收 |
|---|---|---|---|---|
| P0 | 连续口型 | ParamMouthOpenY、ParamJawOpen、ParamMouthPuckerWiden、ParamMouthFunnel、ParamMouthShrug | speech-mouth 单一最终写入者，PCM 优先，起音/收口独立时间常数 | 中途暂停不跳嘴、结束合口、不吐舌抢音频 |
| P0 | 普通说话 | ParamAngleX/Y/Z、ParamBodyAngleX/Y/Z | visual-layers 移除周期性大幅 Z 摇头，仅保留轻微呼吸与角色选择的动作 | 不再无故摇头晃脑 |
| P0 | 真实歪头 | ParamAngleZ、ParamBodyAngleZ、ParamAngleZ2、body_gensui_z | 由已有 head/body 选择与触摸反射驱动，保留物理跟随和缓回弹 | 摇头 X 与歪头 Z 明确区分 |
| P1 | 讲话轻挑眉 | ParamBrowLY/RY、眉形/眉角度 | 单眉参数只有一个写入者；PCM 强弱只给极轻微中性强调，选定表情优先 | 可非对称，停止时恢复 |
| P1 | 触摸颊鼻眉眼 | ParamCheekPuff、ParamBrowLY/RY、ParamEyeLOpen、ParamMouthFunnel | touch-reflex 按真实部位/力度/方向组合 | 鼓脸/眉眼/身体不同，不能全是眯眼 |
| P1 | 耳朵与发 | ParamAngleZ、se_wuli_x_l/r、physics3 | 触摸位点、音量/动作状态、物理次级驱动 | 头身和耳朵有分层反应 |
| P1 | 扇击 | ParamAngleZ、ParamBodyAngleZ、ParamAngleZ2、physics3 | 现有 touch-reflex 反应 + 头快身慢弹簧 | 方向正确、先头后身、缓回弹 |
| P2 | 真实拉扯脸颊 | 未发现独立左右牵拉参数 | **延期**：需要用户可编辑的 Cubism 源工程加 mesh/deformer | 不用缩放/平移冒充真实脸颊变形 |

## 版权 / 源文件
不提交主人上传的模型包本体。VTubeStudio 参数映射及 exp3 是研究结构用；不引入第三方新插件、不复制商业角色资源。

## 待真机检验
- 真实 TTS PCM 包括 0 音量间歇和无 PCM 的线路、60/120Hz 的嘴巴自然度。
- 猛扇的模型旋转锚点与物理耗散：Cubism 旋转中心由制作时 rig 决定，运行时不能凭参数硬改下巴支点。
- 自定义模型不具备相同参数时安全跳过；表情与口型互不抢占。

## 2026-10-24 二次资源审计
- CDI3 共 143 个参数，physics3 有 26 个组，90 个参数为 physics 输出，不是彼此独立的动作。
- 小狼的 `ParamMouthPuckerWiden` 实际映射 -30..30，此前驱动系数 .22 几乎不可见，改用 12 度级联动。
- physics3 第 26 组由 `ParamMouthX` 驱动 `ParamMouthX3/X4/X5`；触摸脸颊与受击时输入并平滑恢复，不直接写入物理输出。
- 耳朵的 `se_wuli_x_l/r` 属 physics 输出：小狼模式取消直接写入，交给模型自带的头部/眼睑驱动物理。
