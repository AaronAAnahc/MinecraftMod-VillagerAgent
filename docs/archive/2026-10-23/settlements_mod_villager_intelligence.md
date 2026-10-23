# Settlements 模组如何实现村民智能 —— 系统详解

> 参考来源：`Reference/Settlements-Alpha-mainline`（breezes 的 *Settlements* 模组，Minecraft 1.21.1 / NeoForge）
> 目标：把"村民智能"这件事拆成可理解的子系统，并记录其实现要点，供本项目的 VillagerAgent 借鉴。

---

## 0. 一句话概括

Settlements 没有把村民当成"执行原版 POI 行为的木偶"，而是给每个村民装了一套**分层认知架构**：

> **感知（事件总线）→ 记忆（衰减空间记忆 + 情景知识库）→ 性格/遗传（决定作息与偏好）→ 规划（每日计划，含可选 LLM 叠加）→ 行为（状态机 + 工作流）→ 导航与社交（ gossip 传播）**

整条链路围绕一个**全局世界事件总线（WorldEventBus）**运转——村民通过它"听说"世界发生的事，但受到**反心灵感应（anti-telepathy）**规则约束：只能直接感知身边的事件，远距离信息只能靠村民之间口口相传（gossip）。

---

## 1. 整体分层架构

代码严格分成三层（这是理解整个模组的关键）：

| 层 | 包路径 | 职责 | 是否依赖 Minecraft |
|----|--------|------|-------------------|
| **domain** | `domain.ai.*` | 纯领域逻辑：行为契约、记忆类型、规划算法、知识库、感知门控、条件谓词 | **否**（可在单元测试里跑，无 mock） |
| **application** | `application.ai.*` | 把 domain 接到 Minecraft：Brain 实现、传感器工厂、规划器装配、LLM 网关、gossip 会话 | 部分 |
| **infrastructure** | `infrastructure.minecraft.*` | 与 MC 引擎对接：实体、方块、世界生成、网络包、渲染、配置注解 | 是 |

这种分层的好处：AI 的"智能逻辑"（规划、记忆、知识）全部在 domain 层写成无状态、可序列化的纯 Java，不碰 MC 内部类，因此可以脱离游戏单独测试；而所有"怎么在 MC 里落地"的脏活都隔离在 infrastructure 层。

> 对我们 VillagerAgent 的启发：当前 `ai/` 包直接揉在 mod 代码里，可借鉴这种 domain/application/infrastructure 三分法，把"感知/记忆/规划"的纯逻辑抽出来。

---

## 2. 大脑入口：Brain 与实体契约

核心接口：

- `IBrain`（`domain.ai.brain`）：每个村民拥有一颗大脑。暴露 `tick(int delta)`、`getMemory/setMemory/updateSites` 等。**注意 `updateSites` 的类型被收窄成 `MemoryType.DecayingSpatialMemoryType`**——编译器强制只有"衰减型空间记忆"能走站点更新路径，从而消除了旧代码里的运行时 `isDecaying()` 守卫。
- `ISettlementsBrainEntity`：能拥有大脑的实体（`SettlementsEntity` 的子接口），提供 `getSettlementsBrain()`、`getNavigationManager()`、`lookAt(Location)`。
- `VillagerBrain`（`application.ai.brain`）：`IBrain` 的具体实现，持有 `BaseVillager`。它内部有一个 `VillagerMemoryAccess`（实现 `MemoryAccess` 端口），把"记忆读写"路由到 vanilla `Brain` 或自定义的 `SettlementsMemoryStore`。

`VillagerBrain` 的 `tick(delta)` 会遍历并 tick 所有 `ISensor`；传感器在 `initialize()` 时才从 Dagger 服务图注入（因为实体构造时、客户端上服务图不可用）。

---

## 3. 感知层（Perception）

村民不是每 tick 全图扫描，而是**监听一个全局事件总线**，再按"是否听得到"过滤。

### 3.1 世界事件总线 `WorldEventBus`
- **Kafka 风格的日志 + 游标模型**：全局只追加（append-only）一个 `List<WorldEvent>`，每条事件带单调递增的 `seq`。
- **每个村民一个独立游标 `lastSeenSeq`**：每次 tick 只处理"自上次以来的 delta"，互不阻塞。
- **TTL 驱逐**：事件约 5 秒（100 tick）后从总线移除（由 `WorldEventBusReaperServerEvents` 每 tick 调用 `evict`）。
- **纪律约束**：只在语义边界 emit（绝不每 tick 发状态）；`SYSTEM` 命名空间的事件绕过感知门（基础设施信号，不是可观测事实）。

### 3.2 `PerceptionGate` —— 反心灵感应
判定一个村民能否"感知到"某事件，两步：
1. `SYSTEM` 命名空间事件一律拒绝（系统信号不可见）。
2. **曼哈顿区块距离** > `MAX_PERCEPTION_CHUNK_RADIUS`（=4 区块 = 64 格）的事件拒绝。直接用事件信封里的区块坐标算，O(1)，无需世界查询。

> 关键规则：**远距离知识只能靠 gossip，不能通过总线直接获得。** 这是"反心灵感应"的硬约束。

### 3.3 `ObservationBuffer` → `ObservationFactory` → `PerceptionPipeline`
- `ObservationBuffer`：固定容量环形缓冲（默认 50）。满则静默覆盖最旧项——高频例程扫描预期会溢出，只有"值得注意的事件"才活到 drain。
- `ObservationFactory`：把 `WorldEvent` 转成 `Observation`（带类型、actor、坐标、时间戳）。
- `PerceptionPipeline.tick(...)`（application 层）每 tick 做四件事：
  1. 用村民游标 `visitDelta` 拉取新事件；
  2. 过 `PerceptionGate`，太远的事件丢弃；
  3. 通过的事件转成 `Observation` 写入该村民的 `ObservationBuffer`；
  4. drain 缓冲，逐条用 `MemoryImportanceGate.score(...)` 打分，**达阈值才晋升为 `KnowledgeEntry` 存入 `VillagerKnowledgeStore`**。

打分时会区分"自己干的事"（salience 加成）和"亲眼目睹的事件"（`isSelfWitnessed` 强制记住）。

### 3.4 传感器 `ISensor`
除了事件总线，村民还有主动 `ISensor`：每 tick（受 cooldown 约束）`doSense` 产出 `IMemoryWrite` 写回大脑。例如在附近探测方块站点（作物、工作台、水源），写入**衰减空间记忆**。

---

## 4. 记忆层（Memory）

记忆分成两类，用密封接口 `MemoryType<T>`（sealed，恰好两个实现）区分：

### 4.1 `VanillaMemoryType` —— 接原版 Brain
需要 vanilla `MemoryModuleType` 的记忆（标量/实体/标志/列表），直接走 vanilla `Brain` 的 `get/set/eraseMemory`。

### 4.2 `DecayingSpatialMemoryType` —— 自定义衰减记忆
承载 `List<GlobalPos>`（如"我记得村里所有成熟作物的位置"），**带每个条目的 TTL 衰减**。特点：
- 不能直接 `setMemory` 覆盖，只能走 `IBrain.updateSites(report, nowTick)` 的 **upsert + 确认缺席（confirmed-absence）** 协议。
- 读取时若不在服务端（拿不到 dimension key）直接返回空——因为 `GlobalPos` 需要维度信息。

### 4.3 `SettlementsMemoryStore` + `DecayingSpatialSiteStore`
- 按 `identifier` 维护多个 `DecayingSpatialSiteStore`（空间记忆子库）。
- 每个子库有 `retentionTicks`（保留时长）和 `maxEntries`（上限）。
- 淘汰策略：① 索引查询时**就近-K 截断**；② 库内**最旧优先驱逐（stalest-first eviction）**。
- **瞬态、不序列化**：加载后用一个扫描周期从索引重建，对"方块资源站点"是可接受的代价（设计文档 §4）。

> 对 VillagerAgent 的启发：当前 `WorldStructureIndex` 也是"距离场 + 房间索引"，可考虑类似"按类型密封的 MemoryType + 衰减空间记忆"来表达"我见过的床/作物/工作台位置"，避免每次都重扫。

---

## 5. 知识与社交（Knowledge & Gossip）

### 5.1 `VillagerKnowledgeStore` —— 情景知识库
每个村民一个，存 `KnowledgeEntry`（情景记忆）：
- 容量上限 `MAX_ENTRIES = 200`，用 `LinkedHashMap` 保插入序，**满了就淘汰最旧的**（FIFO），知识自然衰减。
- `admit(entry)` 返回 `AdmitResult`，实现**佐证感知（corroboration）**：
  - 同一事实被**不同独立来源**再次确认 → 现有条目权重 +`CORROBORATION_BUMP`(0.1)，返回 `CORROBORATED_EXISTING`；
  - 同一来源重复分享 → 纯 no-op（`IGNORED_DUPLICATE`）；
  - 超传播跳数（`MAX_HOP_COUNT`）→ `REJECTED_HOP_CAP`。
- `shareableEntries()`：返回可对外 gossip 的条目。

### 5.2 Gossip —— 唯一的远距离信息传播方式
- `GossipSession`：两个村民之间的一次配对知识交换。发起方在创建会话时就**锁定要分享的那条 `entryToShare`**（其 store 里"最重要的一条可分享条目"），保证接收方拿到一致副本。
- 生命周期：`GossipInvite` 触发 → 发起方 deposit → 接收方匹配 cue → 复制进自己 store（COMPLETED）/ 超时未接（ABORTED）。
- 这与 `PerceptionGate` 配合：直接感知被距离卡死，gossip 才是"谣言扩散"的渠道，且受 hop cap 与 TTL 双重限制，不会无限传播。

### 5.3 `GossipWeightCalculator` / `KnowledgeEntry`
- `KnowledgeEntry` 记录起源观测 id、来源、跳数、坐标（packed）、salience 分数、shareable 标志。
- 权重计算决定了"哪条记忆最值得分享/保留"。

> 对 VillagerAgent 的启发：我们目前 LLM 产出的 todo 是"个人记忆"，缺乏"村民间信息差/谣言"维度。Settlements 的 gossip + hop cap + 佐证，是制造涌现社群行为的低成本手段。

---

## 6. 性格与遗传（Personality & Genetics）

"智能"不只是会办事，还要有**个体差异**，否则全村整齐划一很假。

### 6.1 `VillagerPersonality`（"人设卡"）
持久化的 persona：`adjectives`（形容词）、`characterSketch`（人物小传）、`speechStyle`（说话风格），带 `PersonalityStatus`（PENDING/READY/FAILED）生命周期。由 LLM 的 persona 网关生成（见 §10）。

### 6.2 `GeneticsProfile`（遗传）
- 用 `EnumMap<GeneType, Gene>` 表示完整基因，每个基因是 0~1 的连续值。
- 初始化时按高斯分布（均值 0.4，标准差 0.133）随机播种。
- `crossover(other, random)`：与另一亲本杂交——偶尔（1% 野突变）全随机，否则取平均 + ±5% 突变。这保证村民"后代"有遗传差异。
- 基因影响具体行为：例如 `MilkCowBehavior` 用 `villager.getExpertise()` 决定每次挤几桶奶（`expertiseMilkLimit`）。

### 6.3 `Chronotype` —— 确定性作息人格
这是很巧的设计：**用稳定 seed（通常村民 UUID）派生出两只时间偏移**，打破全村同步：
- `wakeSleepOffsetTicks`：±40 游戏分钟，早起鸟早起也早睡，夜猫子反之（用 Murmur3 预混 seed 避免相邻 UUID 的强相关）。
- `mealOffsetTicks`：±12 游戏分钟，小到保留"全村大致共餐"的社交窗口。
- **决定性（deterministic）**是硬性要求：`PlanRunner` 在不同 tick 重算 wake，`Chronotype` 必须每次给出相同偏移，否则计划对不上。

> 对 VillagerAgent 的启发：我们目前用 `scheduledActivity` 驱动，容易全村同拍。可借鉴 `Chronotype` 用 UUID-seed 做"去同步化"偏移。

---

## 7. 规划层（Planning）

这是"智能"的中枢——决定村民**今天干嘛**。

### 7.1 数据结构
- `DayPlan`：一整天的有序 `PlanSlot` 序列。自带 `PlanStatus` 和 `currentSlotIndex`， slot 用完即"计划耗尽"。
- `PlanSlot`：在某段游戏时间窗内执行某个 `BehaviorKey`，带预估时长。
- `DayPlanSchedule` / `DayPlanActivityBlock`：作息框架（醒来、工作、用餐、睡眠的活动区块，不能重叠，构造时校验）。

### 7.2 `PlanGenerationContext` —— 确定性输入包
规划器拿到的不可变快照：
- `profession` / `genetics` / `scheduleProfile` / `restDayPolicy` / `dayType`
- `availableBehaviors`：该村民可执行的加权行为列表
- **`chronotypeSeed` + `planSeed`**：两个稳定 seed。
  - `planSeed` 喂给生成器内部**唯一的 `Random`**，驱动所有"选哪条行为"的抽签。范围是 `(村民, 日历日)`，因此同人同日重算得到**完全一致**的抽签序列；不同村民/不同天则发散。
  - 这保证了"异步生成也不会每次都不一样"。

### 7.3 两个规划器（双轨）
- `HeuristicAsyncPlanGenerator` / `HeuristicPlanGenerator`：**确定性启发式**，按职业权重池 + 机会预测 + chronotype 抖动，异步提交（`PlanRunner#submitNextPlanAsync`）。`PlanAuthor.HEURISTIC`。
- `LlmOverlayPlanGenerator`：**可选 LLM 叠加层**，在计划耗尽时入队（`PlanRequestService`），由 SIS（Settlements Inference Service）生成更"有性格"的日程。`PlanAuthor.LLM`。

> 关键设计：**LLM 是叠加（overlay），不是替代**。启发式永远兜底；LLM 只在开启且配置正确时覆盖某些时段，且产出仍要经过同一套 `DayPlan` 校验（空结果 vs 稀疏日是两种语义，都走 composer 的填充策略）。

### 7.4 行为目录（catalog）
规划器从"能力库"和"职业岗位描述"两张表挑行为：
- `BehaviorKey`：稳定 id（如 `HARVEST_RIPE_CROPS`、`MILK_COW`、`COURTSHIP_INITIATE`）。**必须稳定，因为会写进序列化计划里**。
- `BehaviorPlanningMetadata`：行为的"说明书"——类别、强度（`WorkIntensity`）、所需 `BehaviorChannel`、相关记忆提示、产出观察提示、`OpportunityRequirement`（机会信号）、预估时长、`maxRunDuration`、`cooldown`、`interruptible`，以及 `toPromptLine()`（给 LLM 的可用动作行）。
- `ProfessionBehaviorPool`：职业 → 其行为 key 列表（"岗位描述"）。通用行为（吃、闲逛、休息、交易）由 `BehaviorPoolResolver` 统一管理，不重复写进每个职业池。
- `WeightedBehavior`：行为 + 规划期权重；`PoolEntry` 引用目录里的行为。
- `OpportunityForecaster`：生成计划前评估哪些行为的"机会条件"当前不满足，启发式规划器把它们**降权**（`LOW_OPPORTUNITY_MULTIPLIER`）而非移除，保留韧性后备。

### 7.5 `PlanRunner` —— 计划执行器
- application 层入口，每 tick 推进当前 `PlanSlot`，取出对应 `IBehavior` 执行。
- **安全网（safety net）**：单个 slot 行为有 `DEFAULT_MAX_BEHAVIOR_RUN_TICKS`（120 秒）上限，超时强制停止并把 slot 重新入队，村民不会卡死冻结。长行为（如整理箱子、耕地）在 catalog 里声明更高的 `maxRunDuration`。
- 处理大量边界重置原因：`missing plan`、`missing successor`、`async overrun`、`backward dayTime jump` 等——任何异常都让村民优雅回退而非崩溃。

### 7.6 LLM 驱动行为：从"菜单点选"到"日程槽位"

**一句话：LLM 不执行任何动作，它只做"选择"——从一个封闭菜单里点选哪些行为、按什么顺序、可选钉在某个半小时。** 真正执行永远走 `PlanRunner` → `IBehaviorCatalog.createBehavior(key)`，跟 LLM 无关。

**端到端流程：**

1. **触发（无周期性 sweep）**：`PlanRunner.submitNextPlanAsync` 在村民计划耗尽 / 翻日 / 时间跳变时，**先**异步提交启发式后继计划（`PlanAuthor.HEURISTIC`，永远兜底），**同时**调 `PlanRequestService.enqueueForOverlay` 触发 LLM 叠加请求。
   - 门控：`InferenceGate.isEnabled()`（SIS 总开关）+ `PlanInferenceConfig.resolvedMode() == LLM` + 没超过当天 `overlayCutoffCivilTick`（太晚就不值得要 LLM 计划了）+ 该村民没有已在途请求（dedupe，防重入）。
   - 上下文组装按 tick 分块（每 tick 最多 8 个村民），子批流式 POST，HTTP 回调整体不碰游戏线程。

2. **LLM 返回**：`VillagerPlanResult = { villagerId, Map<windowId, List<PlanSelection>> }`。
   - `windowId` = band id（`MORNING` / `AFTERNOON` / `EVENING`）。
   - 每个 `PlanSelection = { id(行为key), at(可选) }`。`at` 是 `TimeOfDay` 枚举名（如 `"AT_18_00"`）= "必须在这个半小时做"；无 `at` = "这个窗口内、按列表顺序做"。

3. **映射成 intent**：`LlmOverlayPlanGenerator.toIntent` → `PlanIntent(selections, pins)`：
   - 无 `at` 的 → 该 band 的有序 key 列表（best-first，`distinct` 去重；未知 id 不在这里过滤，交给 composer 按菜单裁剪时自然丢弃）。
   - 带 `at` 的 → `PinnedSelection`（固定时间点；`eat_food` 是"餐点"钉、压掉碰撞的默认餐点锚，其余是"非餐点"钉）。

4. **组合进同一套 `DayPlan`**：`DayPlanComposer.compose(context, intent)`：
   - `fillPolicyFor`：intent 有 selections → 用 `OrderedPreferenceSelectionStrategy`（**尊重 LLM 顺序**，确定性，且从**全菜单**取、不止 band 派生池）；intent 为空（真·无 LLM 结果）→ 用 `WeightedRandomSelectionStrategy`（启发式加权随机）。
   - `computeAnchors`：把 pins 当固定锚点放置。
   - 结果仍是同一个 `DayPlan`（有序 `PlanSlot`），走同一套校验——**LLM 只是换了一种"选哪个行为"的策略，没换数据结构**。

5. **采纳（`PlanRunner`）**：
   - `arbitrate`：同一天，启发式 arrival **永不顶掉**已 staged 的 LLM 计划；不同天，新的一天优先。
   - `adoptPendingIfReady`：**绝不 mid-slot 换计划**，只在下一个槽位边界采纳（`hasActiveCurrentSlot` 检查），先 `forceStop` 清理 teardown 义务。
   - 于是：启发式计划先落地当"地板"，LLM 结果到达后 supersede 同天 / 下个槽位采纳。

**回答三个确认问题：**

- **"行为通过加权随机填充进每日任务表？"** —— 是（启发式路径）。`DayPlanComposer` 把一天拆成 3 个 band，每 band 用 coverage-first 加权随机（`WeightedRandomSelectionStrategy`）+ `WindowPacker` 贪心打包，产出**冻结的 `PlanSlot` 有序列表**。
- **"一开始就决定了一天工作内容？"** —— 是。醒时（wake）一次性生成整天 `PlanSlot` 列表，`PlanRunner` 按序执行。**例外**：有 LLM 时，叠加计划可 mid-day 到达、在下一个槽位边界替换——所以"一天内容可被中途修订"。
- **"LLM 驱动行为怎么做的？"** —— LLM 只点选 + 排序 + 钉时间，映射成 `PlanIntent` 后仍由启发式 composer 打包成同一套计划；执行永远在 `PlanRunner`/catalog，LLM 挂了启发式地板照跑。

---

## 8. 行为层（Behavior）

行为是把"计划里的一个槽"变成"村民在 MC 里实际动作"的落点。

### 8.1 契约 `IBehavior`
统一契约（`domain.ai.behavior.contracts`）：
- `getPreconditions()`：开始前必须满足的条件列表。
- `getContinueConditions()`：运行中必须满足（一旦不满足就停，且**运行时不重复检查 preconditions**）。
- `getPreconditionCheckCooldown()` / `getBehaviorCoolDown()`：两个冷却，避免每 tick 重载服务端。
- `tickPreconditions()` → `start()` → `tick()` → `stop()`，以及任意时刻 `requestStop(reason)`。
- `getStatus()`：`BehaviorStatus`（STOPPED/RUNNING/...）。

### 8.2 运行时 `AbstractBehavior` + 状态机
- `AbstractBehavior`：实现 `tickPreconditions`（先查 behavior 冷却，再查 precondition 冷却，再逐个测 precondition，记录每个结果到 `latestPreconditionEvaluationResults` 便于调试）、`start/tick/stop` 模板方法，子类实现 `doStart/tickBehavior/doStop`。
- `VillagerStateMachineBehavior`：在 `AbstractBehavior` 之上叠加**显式状态机**：`initializeStateMachine(controlStep, endStage)`，行为内部用 `StageKey` 枚举表达阶段（如 `MILK_COW → END`）。

### 8.3 工作流原语（workflow）
复杂行为被拆成可组合的 step：
- `StagedStep` / `StageKey`：分阶段控制流。
- `BehaviorStep` / `TimeBasedStep`：带 tick 时间轴与关键帧（keyframe）的步骤，例如"举桶→等 1 秒→挤奶→2 秒后收尾"。
- `NavigateToTargetStep` + `StayCloseStep`：导航到目标并保持贴近，把"走到牛身边"从具体行为里抽出来复用。
- 行为上下文 `BehaviorContext`：跨 step 传递状态（目标、动画、产出记录）。

### 8.4 实例：`MilkCowBehavior`（挤牛奶）走查
1. **preconditions**：附近存在可挤的 `Cow`（`PerceivedEntityExistsCondition`，completionRange=1）+ 背包里有桶（`requireItem` demand signal）。
2. **onBehaviorStart**：按村民 `Expertise` 决定本次挤几桶；找最近的、可达的奶牛；声明主行为（primary deed）`COW_MILKED`。
3. **状态机**：`MILK_COW` 阶段 = `StayCloseStep(距离2) → NavigateToTargetStep → milkStep`。milkStep 是 `TimeBasedStep`：onStart 举桶+触发 INTERACT 动画，1 秒关键帧执行 `performMilk`，onEnd 收桶并在还有剩余次数时回到 `MILK_COW`。
4. **performMilk**：消耗一个桶，播放声音，产出牛奶桶入背包，记录 yield，递减计数。
5. **onBehaviorStop**：给经验奖励、停导航、清手持物、复位状态。
6. 行为完成时通过 `context.primaryDeed()` 把 `COW_MILKED` 事件**发射到 WorldEventBus**——于是其他村民可能"听说"这头牛被挤了。

### 8.5 条件谓词 `ICondition`
丰富的条件库（`domain.ai.conditions`）：`NearbyRipeCropCondition`、`NearbyBlockExistsCondition`、`NearbyBreedableAnimalPairExistsCondition`、`JobSiteBlockExistsCondition`、`KnownBlockSitesPrecondition`、`NearbyFriendlyNeedsPotionCondition`、`ItemStackCheckCondition` 等，外加 `AnyOfCondition` 组合。条件既服务行为，也服务传感器与机会预测。

---

## 9. 导航层（Navigation）

`INavigationManager<T>`（domain 接口）统一导航意图：
- `navigateTo(target, NavigationType, completionRange)` / `walkTo(target, range)`；
- `speedFor(type)`、`canReach(target, distance)`、`getNavigationTarget()`、`isNavigating()`、`stop()`。
- 实现层有 `SettlementsGroundPathNavigation` + `SettlementsWalkNodeEvaluator`（infrastructure）。
- `ReachabilityChecker`：判定目标可达性（行为 step 据此反应，而非静默放弃）。
- `AgilitySpeedResolver`：根据情境解析移动速度（比如抱东西走慢点）。

> 对 VillagerAgent 的启发：我们"回家睡觉"仍走原版 `HOME` POI。可借鉴 `INavigationManager` 把"走到某空间站点"抽象成统一意图，再接回 `WorldStructureIndex`。

---

## 10. LLM 集成（SIS —— Settlements Inference Service）

模组把 LLM 当成一个**可选的"认知通道"（cognition lane）**，而非核心依赖。

### 10.1 总开关 `InferenceGate`
- 由不可变 `InferenceConfig`（服务器启动时一次性物化）决定 `isEnabled()` = `enabled && hasEndpoint`。
- `isMisconfigured()`：开了却没填 endpoint → 启动告警（区别于"正常关闭"）。
- 所有注册接缝**只在接线时查一次**，不在运行时反复推导。

### 10.2 三个网关（gateway）
- **monologue**：`VillagerMonologueRequest/Result`——村民的内心独白/日记（由 `VillagerFacetDeriver`、`PersonaBundleAssembler` 等组装人格包 `PersonaBundle`）。
- **persona**：`PersonaVillagerRequest/Result`——生成 §6.1 的 `VillagerPersonality` 人设卡（`PersonaLineage` 记录血统来源）。
- **plan**：`VillagerPlanRequest/Result`/`PlanSelection`/`PlanWindowDTO`——生成 §7.3 的 LLM 叠加日程；`PlanInferenceMode` 控制模式。
- 传输层：`HttpInferenceTransport` + `InferenceRequestEnvelope` + `InferenceStreamHandle`，协议版本 `InferenceProtocol.VERSION = 1`。

### 10.3 关键约束
- **永远不阻塞游戏线程**：LLM 调用走 HTTP/异步，`PlanAuthor.LLM` 计划是"计划耗尽时再请求"的叠加。
- 启发式规划器是**硬兜底**：即使 SIS 完全没开，村民依旧有完整作息。
- 行为产出的观察/对话会经 `DialogueProvider`（含 `RehearsedDialogueProvider`、`ScriptedDialogueProvider`、`DialogueResponseSanitizer`）变成村民头顶气泡/对话，但有 `DialogueConfig` 与 `OccasionSetResolver` 控制何时说什么。

---

## 11. 作息与日程（Schedule）

- `ScheduleProfile`：按职业预设作息（游戏 tick，0=06:00）。例如渔夫 04:00 起、农民 04:30 起、图书管理员 08:00 起、傻子(nitwit) 09:00 起且"无工作安排"、无业者 07:00 起且"社交可用窗口大"。
- `RestDayPolicy` / `PlanDayType`（工作日/休息日）、`IWeekCycleProvider`：支持轮休，避免全村同时放假或同时工作。
- 与 `Chronotype` 叠加：profile 给"职业基准作息"，chronotype 给"个人偏移"，二者合成最终 wake/sleep。

---

## 12. 端到端数据流（一帧内发生了什么）

```
服务器 tick
  └─ BaseVillager.customServerAiStep()
       ├─ brain.tick(delta)
       │     └─ 各 ISensor.doSense() → 写入 Vanilla/Decaying 记忆
       ├─ PerceptionPipeline.tick(villager, runtimeState, gameTime)
       │     ├─ WorldEventBus.visitDelta(lastSeenSeq) 拉新事件
       │     ├─ PerceptionGate.admits() 过滤太远的
       │     ├─ ObservationFactory → ObservationBuffer
       │     └─ MemoryImportanceGate 打分 → 晋升 VillagerKnowledgeStore
       ├─ PlanRunner.tick(...)
       │     ├─ 推进当前 PlanSlot
       │     ├─ 取出 IBehavior，跑 preconditions → start → tick → stop
       │     └─ 行为完成/产出 → WorldEventEmitter.emit() → WorldEventBus
       └─ 社交层：触发 GossipSession（把知识传给附近村民）

（异步）PlanRunner.submitNextPlanAsync / PlanRequestService
       ├─ HeuristicAsyncPlanGenerator（确定性，永远有）
       └─ LlmOverlayPlanGenerator（仅 SIS 开启时，叠加）
```

注意闭环：**行为发射事件 → 总线 → 别人感知 → 晋升为知识 → 影响（gossip/规划/对话）**。这是"群体智能"的来源。

---

## 13. 对 VillagerAgent 项目的可借鉴点（速记）

| Settlements 做法 | 我们的现状 | 建议 |
|---|---|---|
| `MemoryType` 密封接口 + 衰减空间记忆 | `WorldStructureIndex` 全量索引 | 用"密封 MemoryType"表达"见过的位置"记忆，按类型分库 + TTL 衰减，省去加载重算 |
| `WorldEventBus` + 每村民游标 + TTL | 暂无事件总线 | 引入轻量事件总线，让行为产出可被他人异步感知 |
| `PerceptionGate` 反心灵感应 + gossip hop cap | LLM todo 是纯个人记忆 | 加 gossip/信息差维度，制造涌现社群行为 |
| `Chronotype` 用 UUID seed 去同步 | `scheduledActivity` 易全村同拍 | 用稳定 seed 给每个村民作息加偏移 |
| 双轨规划：启发式兜底 + LLM 叠加 | LLM todo→action 未闭环 | LLM 做"叠加/覆盖"，确定性规划永远兜底，避免 LLM 挂了村民变傻 |
| `IBehavior` 契约 + 显式状态机 + 工作流 step | Skill = 状态机，FSM 状态落 agent | 复用 "precondition/continueCondition/cooldown/status" 契约 + StagedStep 组合 |
| `InferenceGate` 单一开关，接线时查一次 | config 驱动 overlay | 把"LLM 是否启用"收敛成单一 Gate，不散落各处的 enabled 判断 |
| domain/application/infrastructure 三分 | `ai/` 直接揉在 mod 里 | 把纯 AI 逻辑抽到 domain 层，便于单测与复用 |

---

## 14. 关键类速查表

| 子系统 | 关键类（包） |
|---|---|
| 大脑 | `IBrain`, `ISettlementsBrainEntity`, `VillagerBrain` (brain) |
| 事件总线 | `WorldEventBus`, `WorldEvent`, `WorldEventType`, `WorldEventEmitter` (worldevent) |
| 感知 | `PerceptionGate`, `PerceptionPipeline`, `ObservationBuffer`, `ObservationFactory`, `ISensor` |
| 记忆 | `MemoryType`(Vanilla/Decaying), `SettlementsMemoryStore`, `DecayingSpatialSiteStore`, `MemoryAccess` |
| 知识/社交 | `VillagerKnowledgeStore`, `KnowledgeEntry`, `GossipSession`, `GossipWeightCalculator` |
| 性格/遗传 | `VillagerPersonality`, `GeneticsProfile`, `Chronotype` |
| 规划 | `DayPlan`, `PlanRunner`, `HeuristicAsyncPlanGenerator`, `LlmOverlayPlanGenerator`, `PlanGenerationContext`, `OpportunityForecaster` |
| 行为目录 | `BehaviorKey`, `BehaviorPlanningMetadata`, `ProfessionBehaviorPool`, `WeightedBehavior`, `IBehaviorCatalog` |
| 行为 | `IBehavior`, `AbstractBehavior`, `VillagerStateMachineBehavior`, 各 `usecases/*Behavior`, `ICondition` |
| 导航 | `INavigationManager`, `ReachabilityChecker`, `AgilitySpeedResolver` |
| LLM | `InferenceGate`, `InferenceConfig`, monologue/persona/plan gateways, `PlanRequestService` |
| 作息 | `ScheduleProfile`, `RestDayPolicy`, `PlanDayType`, `IWeekCycleProvider` |
| 装配 | Dagger：`SettlementsComponent`, `SettlementsDagger`, `di/modules/*` |
