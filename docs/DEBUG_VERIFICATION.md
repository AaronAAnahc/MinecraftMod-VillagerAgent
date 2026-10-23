# 调试与验证

核对日期：2026-10-23。本文说明可执行检查和观察方法，不宣称本次整理运行过 Minecraft 验收。

## 自动检查

在项目根目录运行：

```powershell
.\gradlew.bat compileJava --console=plain
.\gradlew.bat workOrderChecks --console=plain
.\gradlew.bat goalExitChecks --console=plain
.\gradlew.bat check --console=plain
```

`WorkOrderBookChecks` 验证请求合并、承接排他性、续租/过期、库存满足、部分交付等纯 Java 订单规则；`GoalExitPolicyChecks` 验证目标保留、失败退出和冷却。`GoalSchedulerChecks` 验证 wander 兜底、抢占、加权选择及农田锚点 NBT 往返；`check` 依赖这三项。编译检查接口和映射，规则检查覆盖指定逻辑，游戏内动作、寻路、实体加载与表现需另行验收。

## 日志与 idle 排查

开启 `brain_log_enabled`，在实际运行实例的 `logs/latest.log` 按村民 UUID 筛选 `VillagerBrain`；同名村民需要分别追踪。先看 `SNAPSHOT` 的 activity、running、action、位置、路径、疲劳、农业机会与候选分数，再按下表展开。

| 标签 | 检查内容 |
|---|---|
| `no-candidate` | 目标总数、handler 资格、正分但不可执行的原因 |
| `SWITCH`、`skill-exit` | 目标切换、结果和失败原因 |
| `order-offer-empty` | 资格、订单所有权、距离、需求方加载状态 |
| `order-claim/wait/blocked` | 租约、库存、资源与工具缺口 |
| `resource-scan` | 匹配、自然树、暴露、站位与路径各阶段候选 |
| `order-source/break/collected/delivery` | 采集、掉落拾取和实际交付 |
| `move-start/no-path/stuck` | 导航请求与实际位移 |
| `social-empty/no-path/chat` | 邻居机会、寻路和实际对话 |
| `job-site-empty/approach/select/reject` | POI 候选、路径完整性、暂时排除位置与工作站接近 |
| `wander-fallback` | 每个目标的分数、冷却、handler 状态，农田锚点、普通/资源职业 |
| `farm-session-start/end`、`farm-step/done`、`farm-skip` | 连续作业会话、子动作与跳过的位置（完成标签为 `farm-step-done`） |
| `farm-memory-add/defer/forget`、`farm-travel/arrive` | 农田记录、暂缓、失效、远程前往与现场检查 |

表中斜杠缩写分别对应带同一前缀的完整标签，例如 `order-wait`、`move-stuck`。`SNAPSHOT` 每村民至多约 200 ticks 一条，排查需覆盖数次慢更新。运行目标存在时，显示活动本身不能作为停工结论；到达、采集、交付要结合实际坐标、方块、双方背包和订单状态。

漫游使用已加载且可达地点，原版 `GroundPathNavigator` 推进移动，`VillagerNavigationSupport` 处理导航辅助及失败判断。遇到卡住时检查目标高度、区块加载、门、站位和实际位移。

## 可视化

用 `/va config set debugoverlay true` 开启，以 `/va config list` 核对。`DebugSync` 向客户端发送快照，`DebugOverlay` 绘制 HUD、建筑、种子和距离场；具体显示受 `debug_show_*` 控制。HUD 是感知观察入口，行为链以服务端日志为准。

无图形时检查开关、附近受管村民、快照下发和客户端渲染；无建筑盒时检查床的加载/放置播种、pending 队列、周边区块加载、屋顶和几何预算。建筑发现来源包含区块加载床事件。

## 游戏内验收场景

1. 无 LLM，两个成年村民、天然树及可达露天矿：请求物资，观察接单、采集、拾取、交付及库存守恒。
2. 两个工人竞争一单，中断/卸载承接者：核对唯一租约及释放后的重新承接。
3. 缺工具、目标方块消失、路径阻断、满背包、部分供货：检查 WAITING/BLOCKED/FAIL、冷却和真实剩余缺口。
4. 保存重进：核对职业和需求保留，执行租约重新领取；建造任务另测 BuildJob 与世界核对。
5. 农民观察农田后移到 40–80 格外，确认 `farm-travel → farm-arrive → 农活`；存档重进、拆除锚点、阻断路径、未成熟农田也分别检查。农耕、白天疲劳睡眠、邻居社交：核对机会/需求、技能结果与真实动作。
6. 床几何：按 [建筑算法](bed_room_search_implementation.md) 的边界场景检查索引与线框。
7. LLM 启用/失效：分别检查对话、回退、线程回写与请求日志；记录实际服务、配置与响应结果。

验证报告应记录 jar/源码版本、测试世界条件、命令、日志位置和观察到的结果。当前文档没有规模/TPS、行为分布或 LLM 时延的实测结论。

### 主动丢出与拾取验收

通过 TODO 指令创建 `drop_item` / `pickup_item`（格式见执行链专题），观察 `pickup-target` 与 `pickup-collected` 日志：

- 在 8–12 格外放目标物品，关闭 `autopickup`，确认村民主动走近并取得指定数量；走到地点但未入包不得成功。
- 让物品随水流移动、合并或被另一玩家抢走；按种类目标可重查，指定 UUID 消失应报告失败。
- 背包已满或仅剩部分空间，核对物品 NBT、地面余量与目标剩余量，不能复制或吞物。
- 丢出多个不同 NBT 的同种堆叠，核对各堆叠保留 NBT；生成实体被事件取消不得扣除对应库存。
- 阻断路径、卸载实体、抢占任务后恢复，确认能退出或重新寻路。恢复被动拾取后可能继续吸取余物，这是独立机制。

`goalSchedulerChecks` 还覆盖物品 TODO 的数量、UUID 及非法参数解析；实体、寻路与库存转移需以上游戏内场景验证。

农耕连续性验收：在同一 25×25 作业区设置超过 12 株成熟作物、空农田和靠水可翻土，关闭被动拾取；应看到同一 farm 会话中反复 HARVEST→COLLECT→PLANT/TILL，直至无可执行内容才出现 farm-session-end。另测缺种子、缺锄头、满背包、不可达目标、紧急抢占和两名农民同时作业。自动调度测试覆盖农耕子动作间不让位及紧急抢占，尚未替代游戏内方块/掉落验证。

### 嵌套任务回归

`gradlew taskExecutionChecks`（同时纳入 `check`）验证执行树、三级嵌套、子结果隔离、取消后局部状态重建、规则与 LLM/玩家来源限制、任意复合技能的执行权和紧急抢占条件。`task-child-start` / `task-child-exit` 提供完整任务路径。游戏内应确认 farm 根任务连续收割/捡拾/播种/翻地、不可达目标跳过、紧急抢占后重新检查现场。自动检查不覆盖真实世界寻路。
