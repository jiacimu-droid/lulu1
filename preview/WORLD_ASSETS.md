# 露露机统一世界 · 素材分层与当前接入清单

> 网页可验收的美术提案，不是正式 Android 功能已经移植。源素材均为 Kenney CC0，商业使用允许。正式角色画像、背包、掉率仍由 App 的 Kotlin 权威存档负责。

## 已加入同一 16px 美术体系的图集

- Tiny Town (`kenney-tiny-town.png`)：草地、花、树、建筑等，Kenney Tiny Town。
- Tiny Dungeon (`kenney-tiny-dungeon.png`)：怪物、人物、地牢墙体、箱、道具，Kenney Tiny Dungeon。
- Roguelike/RPG (`kenney-roguelike-rpg.png`)：1,767 个 16×16 网格纹理／道具格，含武器、建筑部件、自然元素、藏宝物。来源为 Kenney 原图集的公共再分发版本。
- Roguelike Characters (`kenney-roguelike-characters.png`)：450 左右的模块化角色／头发／装备图块。
- Roguelike Interior (`kenney-roguelike-interior.png`)：室内家具、桌椅、灯具等。
- Roguelike City (`kenney-roguelike-city.png`)：道路、墙面、建筑外立面等。

## 真实玩法需要覆盖的类别（画面样本已接入不代表功能已全部完成）

| 归属 | 物件与不同状态 | 当前预览使用 | 正式 App 对接仍欠缺 |
| --- | --- | --- | --- |
| 见面 | 玩家、陪伴者、居民、服饰、头发、场所、室内、坐/站、牵手 | 人物精灵、素材图鉴、地图细节 | 绑定用户真实角色头像/形象与会面动作 |
| 小岛 | 地形、水岸、树、岩石、木材、矿物、工具、工作台、床、围栏、墙、屋顶、成套建筑、动物 | 自然物件、资源图鉴、工具小图、场景细节 | 建筑分层动画/所有建造阶段与真实存档 |
| 摸金 | 敌人、怪物动作、近/远程武器、背包、宝箱开闭、钥匙、陷阱、七档珍宝、战利品鉴赏、撤离门 | 敌人精灵、武器饰层、珍宝素材样本 | 怪物逐帧动作、稀有度各有独立美术、宝物3D展示/开箱和正式战斗判定 |
| 通用 | 方向摇杆、技能钮、粒子/打击效果、血条、场景过渡、光影 | 拖动摇杆 + 常规地图光影 | 正式 HUD 的同源渲染和 App 真机帧率 |

**关键差距**：当前演示地图尚未接通正式 Android 的 Kotlin 规则/角色 AI，素材本轮也不是完整游戏资产库；特别是七档珍宝的独立馆藏图、玩家换装动画、攻击特效和大型建筑施工层还要按玩法逐项补全。不能用七种颜色涂同一图标假装完成七套宝物。

## 许可与来源

Kenney 官方：
- https://kenney.nl/assets/tiny-town
- https://kenney.nl/assets/tiny-dungeon
- https://kenney.nl/assets/roguelike-rpg-pack
- https://kenney.nl/assets/roguelike-characters
- https://kenney.nl/assets/roguelike-indoors
- https://kenney.nl/assets/roguelike-modern-city

仓库镜像：
- https://github.com/Two-Weeks-Team/openClawWorld/tree/main/packages/client/public/assets/kenney
- https://github.com/superdevgit/MobileRPG/blob/main/roguelikeSheet_transparent.png

所有移入图片必须原样记录 SHA 与来源，不移入来源不明的游戏截图、付费素材或许可证不明的衍生素材。
