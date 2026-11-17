# 露露机 · 专用歌声转换 GPU 服务（Seed-VC）

这是**实际执行歌声转换的独立 GPU 端**，不是音色参数开关，也不属于 ElevenLabs 的标准 Voice Changer。Android 端在 `app/src/main/java/com/jiacimu/lulu/ElevenLabsSingingSvc.kt`。模型使用官方 [Seed-VC](https://github.com/Plachtaa/seed-vc) 的 F0-conditioned SVC 推理，不提交权重，也不冒称已经部署。

## 为什么需要它

主人已经听过：Eleven Music 原唱音准正常，改成角色音色后发闷、压嗓、跑调。此前 `eleven_multilingual_sts_v2` 主要为普通说话变声而设计，不是歌声音高保真工具。Seed-VC 的**歌声模式**用原唱 F0 曲线控制输出，`--auto-f0-adjust False`、`--semi-tone-shift 0` 防止为迎合说话声线把整首歌强行升降调。仍须真实试听确认音色和旋律，不保证任意 TTS 声线都能直接成为优质歌手。

## 一次性部署（需要有独立 GPU 的 Linux 主机或 GPU 云服务）

**目前尚无本项目托管的可直接使用的公共歌声 API。** 一定要部署 GPU 服务并填入 HTTPS 地址；不要把这个 Python 服务跑在 Redmi 手机上，也不要用公开未授权的接口承载角色音频。

在有 NVIDIA GPU、Python 3.10、FFmpeg 的 Linux 主机上：

```bash
git clone https://github.com/Plachtaa/seed-vc.git /opt/seed-vc
python3.10 -m venv /opt/seed-vc/.venv
source /opt/seed-vc/.venv/bin/activate
pip install -r /opt/seed-vc/requirements.txt
# 以已检出的露露机仓库为例
pip install -r /path/to/lulu1/tools/singing-svc/requirements.txt
export SEED_VC_ROOT=/opt/seed-vc
export SVC_API_TOKEN="$(openssl rand -hex 32)"
# 自己保管这个 token；不要写入 Git、截图或提交到 issue
python -m uvicorn server:app --app-dir /path/to/lulu1/tools/singing-svc \
  --host 127.0.0.1 --port 8097
```

Seed-VC 首次推理可能下载模型权重。部署前核对原项目许可、显存需求、使用条款与云 GPU 费用。模型是另一个项目，API 执行环境和费用由服务部署方提供；仅提交本目录不会自动创建 GPU。

用 Caddy/Nginx/Cloudflare Tunnel 将 `127.0.0.1:8097` **安全发布为 HTTPS**，例如 `https://singing.your-domain.example/v1/singing/convert`。不要将未认证的 `8097` 直接暴露公网。确保公网 TLS 证书有效，且用户从手机网络能访问。

然后打开露露机：**语音设置 → 角色唱歌 → 使用角色已有音色 → 歌声专用 Seed-VC**，填写 HTTPS 完整地址和上面的 `SVC_API_TOKEN`。这个 token **只属于歌声 GPU 服务**，与已有 ElevenLabs API Key、Voice ID 完全不同；ElevenLabs 的两个字段不会让你重复填写。

## 实际调用路径

1. Eleven Music v2.5 生成原创原曲（已有）。
2. ElevenLabs Stem Separation 拆成干声和伴奏；拆出的两条 MP3 按原歌曲缓存到角色私有目录，重试时不重复付分轨费用。
3. 首次转换使用**角色已保存的 ElevenLabs Voice ID 和共享 API Key**生成短参考说话音频，缓存，后续歌曲复用；这一步可能消耗少量 ElevenLabs TTS 额度。
4. Android 用 HTTPS + Bearer 将 **原唱纯人声**、**角色声线参考**一起传给本服务 `POST /v1/singing/convert`。
5. 服务调用 Seed-VC 的 44.1kHz **F0-conditioned 歌声模型**，默认音程平移为 0；返回 44.1kHz MP3 人声。Android 在本地和原伴奏重新混合，保留原曲作 A/B 对比。
6. 旧版普通语音变声只保留为**明确选中的实验方式**。Seed-VC 没部署或服务报错时，不会自动降级成旧版、不把原唱冒充为角色唱歌，也不会自动重新付音乐生成费。

### 服务协议

- `POST /v1/singing/convert`
- `Authorization: Bearer <SVC_API_TOKEN>`
- Multipart: `source_vocals` = 已分离 MP3, `reference_voice` = 已缓存角色 MP3, `pitch_shift` = `0`
- 成功：`200 Content-Type: audio/mpeg`，流式 44.1kHz 双声道 MP3
- 错误：401 未授权、413 文件过大、422 格式/时长错误、429 GPU 正忙、502 模型失败、503 未配置模型/密钥、504 超时
- 每次只接受一个推理；上限 26MB，原唱约 180 秒，参考最长 31 秒。服务不留存上传的音频；完成/出错会删除工作目录。手机角色生成的参考音频及歌曲由应用私有目录存储。

### 验收与费用

先做一首 **20–30 秒原创歌曲**：播放「听生成原版」→「听角色版」。如果歌声尚不自然，保留原曲，不反复付费生成新歌；只针对同一原曲修改模型/参考声线，并比较音准、高音、低音与咬字。原唱好听不证明生成的参考 TTS 天然适合唱高音；这仍然需要试听或提供干净的歌声参考。

原曲生成、首次分轨、首次 ElevenLabs TTS 参考、GPU 运算各自可能计费。分轨和声线参考均缓存；不会重复消耗 Eleven Music 额度，已完成的成功角色版会缓存成与旧版不同的文件名。

## 测试与已知限制

- JVM：`ElevenLabsSingingSvcTest` 覆盖 URL 白名单、模式选择、未配置拒绝和不同引擎结果隔离。
- GPU：本目录的 `test_server.py` 用模拟推理验证鉴权、无效 MP3、音高参数以及单结果输出；**不表示已在实际 GPU 上通过**。
- Seed-VC 是 GPU 资源密集的模型，纯手机 APK 不包含其模型权重；不应把“代码已提交”当作“GPU 服务已部署”。
- 真机与真实模型音质需在 GPU 部署后再验证；Android 成功打包也不代表转换后的歌声必然好听。
