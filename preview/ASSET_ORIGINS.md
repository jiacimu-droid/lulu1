# 网页统一世界素材来源 · 2026-11-17
- **唯一当前素材风格**：Kenney Tiny 系列（16×16 像素、3/4 俯视）。
- **Tiny Town 正版原出处**：https://kenney.nl/assets/tiny-town （CC0；用于见面小镇、方块小岛的树、建筑、草地）
- **Tiny Dungeon 正版原出处**：https://kenney.nl/assets/tiny-dungeon （CC0；用于遗迹、角色、宝箱、墙）
- **仓库中的确定来源图集**：
  - `preview/assets/kenney-tiny-town.png`：从 https://github.com/Two-Weeks-Team/openClawWorld/blob/main/packages/client/public/assets/kenney/tiles/tinytown_tilemap.png 读取并以 PNG blob 原样提交；该项目在 `packages/client/public/assets/kenney/ASSET_MAP.md` 明示来自 Kenney Game Assets All-in-1 3.3.0、CC0、16×16。
  - `preview/assets/kenney-tiny-dungeon.png`：从 https://github.com/giobi/sgravoquest-assets/blob/main/tilesets/tiny-dungeon.png 读取并以 PNG blob 原样提交；其 README 标为 Kenney Tiny Dungeon CC0。
- **许可证**：CC0 1.0 Universal；官方允许商业及非商业用途，不强制署名。仍保留作者、来源和使用范围记录；不把第三方美术标成 Lulu 原创，不复制任何不明授权付费 itch.io 美术。
- **使用边界**：当前只装入 `preview/worlds.html` 的网页可玩美术提案，不覆盖 Android 已有资源、玩家存档或人物画像；Android 侧后续在主人同意视觉方案后共用相同 PNG 和绘制入口。原有角色决策仍由统一 Kotlin 执行链负责，不因素材工程生成“模型真的行动了”的假记录。
- **预览来源状态**：sprite 是实际被代码 `drawImage` 按图集坐标使用的二进制 PNG，不是网页用矢量代码假画的“拟素材”。
