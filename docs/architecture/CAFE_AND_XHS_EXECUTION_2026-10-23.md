# 2026-10-23 小红书搜索与数字身体生活闭环

## 实际代码入口
- 小红书：`XhsReadingEngine.kt`，复用已登录本机 WebView；若站点未产生关键词搜索请求，官方结果页回退提前，避免长时间无意义等待，并在性能诊断显示请求是否发出、结果是否属于该关键词、总体耗时和阶段耗时。该改动不绕过小红书验证或登录，不声称已完成真机实网测试。
- 菜单：`DigitalCafeMenu.kt`、`DigitalWorldActivityCatalog.kt`。在实际店员在岗期间，原数字世界地点工具 `use_location` 提供咖啡、花茶、热可可、三明治、小蛋糕点单与食用；原免费温水、饼干保持不变。
- 库存：`DigitalWorldActivityStateStore.kt` 原持久 Store。新菜单“点单”写入角色独立背包计数，未领取不能食用；实际消费才扣计数，食物和饮品不会因为角色随口叙述而出现。
- 身体：`EmbodiedBodyRuntime.kt` 原模拟状态与角色上下文。正式消耗有据可依地更新口渴、饥饿、舒适；模型自己决定能否、何时去吃喝，不新增固定阈值和付费调用。
- NPC：`DigitalWorldExpansionStore.kt`、`DigitalWorldEvolutionRuntime.kt`、`DigitalWorldResidentInteractionRuntime.kt`。原世界住户保存独立气质与偏好，原有居民资料只补齐空字段。店员岑灯拥有住处，手机当地时间 09:00—20:59 在咖啡角值班；只有店员真实在场才能点新菜单。NPC 的聊天与关系仍走原真实在场、真实台词、统一时间线。
- 证据：`DigitalWorldStore` 原世界执行器继续提供动作回执和 `SharedExperienceTimeline` 记录；`CompanionPresenceDialog` 的数字身体区域沿用原模拟值展示。

## 验收方式
1. 在露露机保持前台，打开本机已登录的小红书，先手动搜索关键词，再让角色执行同关键词搜索；进入「性能监测／报错日志」检查是否出现“未观察到请求”“未确认匹配”与阶段耗时，不把首页笔记算成搜索成功。
2. 在角色数字生活打开状态下，于手机本地 09:00—20:59 进入浮光咖啡角，确认店员岑灯确实在场，尝试 `cafe_order_coffee`；返回「此刻」数字身体查看数据，这一步应仅增加库存，不应降低口渴。
3. 执行 `cafe_consume_coffee`，库存减少一份、口渴降低且产生真实世界动作记录；未点单直接食用应失败。
4. 与在场店员对话，核对其稳定偏好、实际位置、对话和关系记录；在不在岗时不能假装店员提供了服务。
5. 运行原有 `app:testDebugUnitTest`、`app:assemblePhone`，再安装 APK 真机检查 Android WebView 页面、日志和存档。源码和单测都不能代替实际网站成功率。

目前是可实际点单领取的**试营业**，并无支付／虚拟钱包／收费交易实现，不能宣称已经扣款。
