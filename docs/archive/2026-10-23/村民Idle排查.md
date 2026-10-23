# 村民显示 idle 的日志排查

核对日期：2026-10-09。此次排查使用 [运行日志](../gamelog/latest.log) 与当前源码；代码修复需要重新构建、重新启动后才会进入新的日志。

## 旧日志能确认什么

- 村民并非完全没有目标：日志中存在 `wander`、`socialize`、`fulfill_order`、`find_job_site` 等选择与动作。
- `fulfill_order` 多次返回 `WAITING`，旧日志没有写出等待分支，所以不能仅据此认定是工具、树木、路径或订单所有权的问题。
- 一些 `socialize` 返回 `SUCCESS` 却没有对话记录。旧实现找不到空闲伙伴、伙伴卸载或寻路卡住时也可能返回成功；现已改成 `WAITING`。
- 普通目标开始时原先没有更新 `currentActivity`，即使正执行移动目标，界面仍可能显示 `idle`。现已在目标切换时同步活动状态。
- 旧 `ACTION` 日志里 `Arrived at wander spot` 是移动动作的描述，动作刚开始就打印，不能证明已经到达。现改成 `Walking for wander toward ...`。
- 旧日志只打印名字，同名村民的行不能可靠地拼成同一条时间线。新日志统一附加 UUID。

## 新日志怎么读

在 `config/villageragent-common.toml` 中保持 `brain_log_enabled = true`。新的 `VillagerBrain` 日志会有以下标签：

| 标签 | 重点看什么 |
|---|---|
| `SNAPSHOT` | UUID、维度、坐标、显示活动、运行目标、当前动作、寻路状态、疲劳、农业机会、候选目标及其分数 |
| `no-candidate` | 有多少目标、多少有执行器、正分但无执行器的原因 |
| `SWITCH` / `skill-exit` | 实际开始和结束的目标、结束结果与失败原因 |
| `order-offer-empty` | 有订单却没给本村民供货目标时，是资格、已有承接者、距离还是需求方未加载 |
| `order-claim` / `order-wait` / `order-blocked` | 是否拿到订单；等待是否因为租约、需求方、库存或找不到资源，以及是否缺合格工具 |
| `resource-scan` | 范围内匹配、天然树过滤、方块是否暴露、可站立与路径探测各阶段剩下多少候选 |
| `order-source` / `order-break` / `order-collected` / `order-delivery` | 是否真正采集、获得掉落、转移物品 |
| `move-start` / `move-no-path` / `move-stuck` | 移动目标与寻路 API 是否接受、随后是否真的移动 |
| `social-empty` / `social-no-path` / `social-chat` | 社交机会是否转化为对话 |
| `job-site-empty` / `job-site-approach` | 是否有可用工作站，是否建立路径 |

`SNAPSHOT` 每个村民至多约 200 ticks 一条。其他日志主要在状态变化、失败或资源搜索时输出；村民多或订单无法满足时文件仍可能较大，排查结束可以关闭 `brain_log_enabled`。

## 推荐复现路径

1. 使用包含新 jar 的运行实例，保留一个停在原地的村民至少 20 秒。
2. 在新 `latest.log` 中按该村民的 UUID 找 `SNAPSHOT`；不要只按名字筛选。
3. 若 `activity=idle` 且 `running=none`，看 `no-candidate` 与目标分数；若有运行目标，按 `skill-exit` 或 `move-*` 追踪。
4. 若目标是 `fulfill_order`，从 `order-claim`、`order-wait` 开始，继续看 `resource-scan` 和 `order-delivery`；若只剩 `wander`，检查农业机会和订单候选为何为零。
5. 对于声称到达、采集或交付的结论，对照村民坐标、方块和双方真实背包。旧日志的 `SUCCESS` 不能单独充当世界交互完成证据。

自动检查入口为 `gradlew build --offline --console=plain`，包含 21 条订单规则断言。代码修复后的游戏表现仍需新的 Minecraft 场景日志确认。

2026-10-09 更新：新日志进一步确认，多个漫游目标落在未加载区域的 `y=0`，60 条 `move-stuck` 都指向这类位置，之后目标被当作成功进入长冷却，造成数百 tick 的空闲。现已限制漫游目标为已加载、可达的地点，缩短闲逛冷却，并把寻路失败与到达分开。村民仍使用原版 `GroundPathNavigator` 跨越地形；自定义 Brain 接管后额外执行木门开关，因为原版 Brain 中负责开门的任务不会运行。资源扫描也从“当前位置直视”改为“方块暴露且站位可达”，以免树在视线外就一直等资源。
