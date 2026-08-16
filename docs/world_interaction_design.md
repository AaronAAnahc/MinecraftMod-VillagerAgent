# 村民世界交互模块设计计划（放置 / 破坏方块）

> **状态**：计划文档 → **已按本方案落地实现（2026-08-15）**。下方 §1–§11 为设计原案；实际代码已全部编码完成（见「实现状态」小节），尚未做游戏内联调验证。
> **目标模块**：`ai/world interaction` —— 让村民能像玩家一样 **破坏** 与 **放置** 方块，并支持 LLM 下达「大规模建造结构」的指令。
> **配套文档**：`docs/building_perception_design.md`（已存在的「建筑感知」模块，负责 *发现* 已有建筑）。本文档负责 *动作* 一侧，二者互补：村民用感知模块理解世界，用本模块改变世界。

> ## ✅ 实现状态（2026-08-15，已落地，待联调）
>
> 设计原案已全部落地，落地时仅修正 3 处 1.16.5 官方映射 API 名（详见 2026-08-15 工作日志）：
>
> | 计划项 | 落地文件 | 状态 |
> |---|---|---|
> | 原子放置/破坏（1 格内约束 + 站立点 + 工具感知破坏计时） | `ai/BlockInteractionAction.java`（`isStandable`/`findStandable`/`withinReach`/`tickPlace`/`tickBreak`） | ✅ |
> | 壳层 BFS 放置顺序 + 站立点求解 + `validateStructure` | `ai/BuildOrderPlanner.java`（`Structure`/`Step`/`ValidationResult`/`validateStructure`） | ✅ |
> | LLM 结构接口 + 回修循环（最多 N 次） | `ai/StructureBuilder.java`（`requestStructure`/`startBuildJob`） | ✅ |
> | 大规模建造任务状态 + NBT 持久化 | `ai/BuildJob.java`（`readNBT`/`writeNBT`/`reconcileWithWorld`） | ✅ |
> | `ActionType` 加 `PLACE/BREAK/BUILD` | `ai/VillagerAction.java` | ✅ |
> | 工具切换 + 破坏速度=玩家 | `ai/VillagerEquipmentHelper.java`（`equipBestToolForBlock`/`computeBreakTicks`，借 `FakePlayer`+`ForgeHooks.blockStrength`） | ✅ |
> | `tickBuilding` 快循环 + `ENABLE_BUILDING` 开关 | `ai/VillagerAgentManager.java` + `config/ModConfig.java` + `events/VillagerEventHandler.java:144` | ✅ |
> | 命令 `/va build place/break/structure` | `commands/VillagerAgentCommand.java` | ✅ |
>
> **待办**：`gradlew compileJava` 通过后游戏内联调（place/break 1 格内原子指令、大规模结构 LLM 接口、壳层 BFS 顺序、校验回修、破坏速度=玩家）。

---

## 0. 需求拆解（来自用户）

1. **原子指令**：村民能「走到 XX 并放置一个方块」；放置只能在 **1 格内** 完成；破坏同理，也只能破坏 **1 格内** 的方块。
2. **大规模放置**：LLM 调用一个函数，传入一个「结构」；该函数按结构 **一个一个** 地把方块放下去。
3. **放置顺序算法**：必须设计一个算法，保证结构里 **每一个方块在放置时村民都能走到 1 格内** 去放。
4. **结构合法性校验 + LLM 回修**：若 LLM 给的位置/结构无法放置，写一个 **校验函数** 判定；不合法就叫回 LLM 修改，最多重试 N 次。
5. **破坏速度 = 玩家**：破坏耗时取决于手中工具（正确工具更快）；村民 **算法地自动切换手上的工具**（不依赖 LLM）。
6. **先出计划文档，后写代码**。

---

## 1. 现有代码基础（直接复用，不要重写）

| 现有类 / 常量 | 关键约定 | 本模块如何复用 |
|---|---|---|
| `VillagerAction` | `ActionType` 枚举 + `ActionPhase{SEARCHING,WALKING,ACTING,WAITING}` 状态机；携带 `targetBlockPos`、`stuckTicks` | 新增 `PLACE` / `BREAK` / `BUILD` 三种 `ActionType`，复用整套「先走后做」状态机 |
| `FarmingAction` | `INTERACT_RANGE_SQ = 2.0`（=1 格交互距离）；`harvestBlockAt` 用 `world.destroyBlock`，`plantSeedAt` 用 `world.setBlock(..., 3)` | 放置/破坏的「距离判定 + 实际改方块」直接照搬此模式（无需 raycast） |
| `VillagerActivitySystem` / `VillagerAgentManager` | `villager.getNavigation().moveTo(x+0.5, y, z+0.5, speed)`；每 N tick 推进状态机；`findFirstReachable` 用 `createPath(pos,1)` 判可达 | build/place/break 的移动与可达判定照搬 |
| `CombatAction.findBestWeaponDamage` | **扫描库存选最优工具并装备到主手**（纯算法，无 LLM） | 破坏时复用同一思路：`equipBestToolForBlock` |
| `VillagerEquipmentHelper` | 装备/切换手部与护甲 | 扩展工具切换方法 |
| `WorldStructureIndex` / `BuildingRecord` | 区块编辑回调 `onBlockChanged` / `markDirty` 自动重算建筑索引 | 村民每放/破一块，索引自动更新；建完可登记为 `BuildingRecord` |
| `LLMService.queryLLM(system, user)` → `CompletableFuture<String>`（异步，4 线程池，`.exceptionally` 兜底） | 异步 LLM 调用 | 结构生成、结构校验回修都走它 |
| `ModConfig` | 所有开关都是 `ForgeConfigSpec.*Value`，`ENABLE_WORLD_INTERACTION` 已存在 | 新增 `ENABLE_BUILDING` 等开关 |

---

## 2. 整体架构（新增 / 修改的文件）

```
ai/
├── VillagerAction.java            [改] 新增 PLACE / BREAK / BUILD ActionType
├── BlockInteractionAction.java    [新] 原子层：单块放置 / 破坏（工具感知破坏计时，1格内约束）
├── BuildOrderPlanner.java         [新] 放置顺序算法 + 站立点求解 + validateStructure()
├── StructureBuilder.java          [新] LLM 接口层：解析结构 JSON → 校验 → 启动 BuildJob
├── BuildJob.java                  [新] 大规模建造任务状态（待放队列 + 锚点 + 进度，可 NBT 持久化）
├── VillagerEquipmentHelper.java   [改] 新增 equipBestToolForBlock / computeBreakTicks（借 FakePlayer+ForgeHooks）
├── VillagerAgentData.java         [改] 保存当前 BuildJob + isInBuildState，NBT 读写
├── VillagerAgentManager.java      [改] 新增 tickBuilding(world) 快循环（仿 tickFarming/tickCombat）
├── config/ModConfig.java          [改] 新增 ENABLE_BUILDING / 交互距离 / 块间隔 / 最大重试
└── events/VillagerEventHandler.java [改] 在 WorldTickEvent 里调用 tickBuilding
```

数据流：
```
LLM 决定「我要建 X」
   │  (StructureBuilder.requestStructure → queryLLM 要结构 JSON)
   ▼
BuildOrderPlanner.validateStructure()
   │  ok? ──否──► 带错误回修 queryLLM（最多 N 次）──仍否──► 放弃 + 记 memory
   ▼ ok
BuildOrderPlanner.computeBuildOrder()  →  (有序块列表, 每块对应站立点)
   ▼
BuildJob（入 VillagerAgentData，NBT 持久化）
   ▼
tickBuilding 每 tick 取下一个块：
   BlockInteractionAction.walkToAndPlace / walkToAndBreak  （复用 WALKING→ACTING 状态机）
   ▼
每放/破一块 → WorldStructureIndex.onBlockChanged（自动更新索引）
```

---

## 3. 原子指令层（核心约束：1 格内）

### 3.1 「1 格内」与「站立点」模型

村民不是瞬移放方块，必须站到一个 **合法站立点** 再操作。定义：

- **目标块 T**：要放（当前为空气）或要破（当前为某方块）的位置。
- **站立点 A**（villager 占用的格）：满足
  1. `A` 是空气（村民身体所在）；
  2. `A-1`（脚下方块）是 **实心** —— 已放置的方块 / 世界原方块 / 地面；
  3. `A+1`（头顶）是空气（有空间站）；
  4. `A` 与 `T` 的 **切比雪夫距离 ≤ 1**（即 26 邻域，包含正交+对角相邻）。
- **交互距离判定**：沿用 `FarmingAction.INTERACT_RANGE_SQ = 2.0`（村民 BlockPos 到 T 的 `distSqr ≤ 2.0` 即视为已到位）。

> 用 `world.setBlock` / `world.destroyBlock` 直接改方块，**不需要 raycast / 视线**；只要村民物理上站在相邻合法站立点即可。这与 `FarmingAction` 一致。

### 3.2 原子放置 `walkToAndPlace(standCell, T, blockType)`

```
1. 计算站立点 A = standCell（由 BuildOrderPlanner 预先给出；单块指令则由 villager 周围 26 邻域里选第一个合法 A）。
2. 若 villager 距 T 的 distSqr > INTERACT_RANGE_SQ：
      villager.getNavigation().moveTo(A.x+0.5, A.y, A.z+0.5, speed);   // WALKING 相位
      卡住超时（参照 STUCK_TIMEOUT）则放弃。
3. 到位（ACTING 相位）：
      a. 校验 T 仍为空气且村民手持对应 block item（不够则记 memory 放弃）；
      b. world.setBlock(T, blockType.defaultBlockState(), 3);         // 3 = 需要更新+广播
      c. 从库存移除 1 个该 block item；
      d. addMemory("在 (x,y,z) 放置了 block")；
      e. WorldStructureIndex.instance(world).onBlockChanged(T);
```

### 3.3 原子破坏 `walkToAndBreak(standCell, T)` —— 工具感知破坏速度

这是「破坏速度=玩家」的落点，**纯算法，无 LLM**：

```
1. 计算合法站立点 A（同 3.1）。
2. 走到 A 内（WALKING）。
3. 到位（ACTING 相位）：
   a. BlockState state = world.getBlockState(T);
   b. 若 state 不可破坏（hardness < 0）→ 放弃。
   c. 工具选择（算法）：
        toolStack = VillagerEquipmentHelper.equipBestToolForBlock(villager, agent, state);
        // 扫描库存所有 item，取对 state 的 getDestroySpeed 最高者，装备到 MAINHAND
   d. 计算破坏耗时：
        breakTicks = VillagerEquipmentHelper.computeBreakTicks(state, toolStack);   // 见 §6
        // 进入「破坏中」子相位，每 tick 累加进度 = 1/breakTicks；
        // 进度满 → world.destroyBlock(T, true)（掉落物由 ItemAttractionSystem 拾取）
        // 破坏期间村民停止导航、面向 T（getLookControl().setLookAt）。
   e. addMemory("破坏了 block 于 (x,y,z)")；onBlockChanged(T)。
```

> 复用 `FarmingAction.harvestBlockAt` 的 `world.destroyBlock(T, true)` 即可掉落物品；村民不亲手捡，物品由既有的 `ItemAttractionSystem` 自动吸引。

---

## 4. 大规模放置：LLM 结构接口

### 4.1 结构规格（LLM 输出的 JSON）

LLM 在「我想建一个 X」时，输出一个严格 schema 的结构（用 Gson 解析；提示词强制 JSON，不聊天）：

```json
{
  "name": "小木屋",
  "anchor": [ "RELATIVE", 0, 0, 3 ],
  "blocks": [
    { "x": 0, "y": 0, "z": 0, "block": "minecraft:oak_planks" },
    { "x": 1, "y": 0, "z": 0, "block": "minecraft:oak_planks" },
    { "x": 0, "y": 1, "z": 0, "block": "minecraft:oak_log" }
  ]
}
```

- 坐标 **相对 anchor**（整数，切比雪夫相邻即可，不需对齐 grid）。
- `block` 用注册名（`minecraft:xxx`），`StructureBuilder` 解析为 `Block`。
- 一个 `anchor` 决定整个结构的世界落点。

### 4.2 `StructureBuilder.requestStructure(agent, goalText)`

```
1. system/user 提示词要求 LLM 仅输出上面的 JSON（给 2~3 个示例，强调：结构必须连到地面/已有方块、不要悬空孤岛）。
2. queryLLM(...).thenAccept(json -> {
       Structure s = Gson.parse(json);
       ValidationResult r = BuildOrderPlanner.validateStructure(s, anchorWorld, world, agent);
       if (r.ok)  startBuildJob(agent, s, r.order);          // 见 §5.5
       else       reviseWithLLM(agent, s, r, retriesLeft);    // 见 §5.4
   });
```

---

## 5. 放置顺序算法（本模块最难、最关键的部分）

### 5.1 目标

给定结构 `S`（一组相对坐标→方块），求出一个 **放置顺序** 与每块的 **合法站立点**，使得：

- 村民按此顺序放，轮到块 `T` 时，`T` 当前为空气、且存在一个 **当时可达的合法站立点 A**（A 与 T 切比雪夫 ≤1，A 脚下方块已实心，A 头顶空气，A 能从锚点走过去）。
- 若无法为全部块找到顺序 → 判为「不可建造」，交给 §5.4 让 LLM 修。

### 5.2 关键不变量（为什么能做出来）

MC 中方块 **不依赖支撑**（悬空也成立）。所以只要结构在 **26-邻域意义下从锚点连通**，且每块放置时旁边已有一个「实心邻居」可作脚手架，村民就能站到该邻居旁边把它放上去。这等价于 **从锚点做壳层外扩的 BFS 洪泛**。

### 5.3 算法：壳层 BFS（Accessible Build Order）

```
输入: S = { (rx,ry,rz) -> Block }，anchorWorldPos P0，world
辅助:
  occ[cell] ∈ {WORLD_SOLID, AIR_TO_PLACE, PLACED, AIR_EMPTY}
  standable(A) = world.isAir(A) && world.isSolid(A-1) && world.isAir(A+1)
  reachable(A) = A 能从 anchorStand 经「已放置/世界实心 + 空气」网格寻路到达 (createPath)
  neighborSolid(T) = ∃ N∈26邻域(N≠T): occ[N] ∈ {WORLD_SOLID, PLACED}

初始化:
  placed = { 世界在结构包围盒内的实心块 } ∪ { 地面 }
  order = []
  anchorStand = 锚点附近最近的合法站立点（若结构第 0 块就是地面，则 anchorStand 紧邻它）

循环:
  frontier = { T ∈ AIR_TO_PLACE :
                neighborSolid(T) 且 ∃ A∈26邻域(A≠T): standable(A) && reachable(A) }
  if frontier 空: break
  选 T = frontier 中「最低 y 优先、再离 anchor 最近」的一个   // 优先向下/向外铺，物理更稳
  记录 (T, 对应的 A)
  occ[T] = PLACED ; placed.add(T) ; order.add(T)
直到 AIR_TO_PLACE 清空

结果:
  if 仍有 AIR_TO_PLACE 残留 → 这些块「不可达」→ ValidationResult.ok=false，
      errors 列出每个不可达块 + 原因（无实心邻居 / 无合法站立点 / 与锚点不连通）
  else → ValidationResult.ok=true, order, 每块站立点
```

**保证性（直觉）**：每次取出的 T，其「启用邻居」N 已在 `placed` 中（实心、物理存在），T 的站立点 A 与已放置网格连通 → 村民能走到 A 放 T。T 放完成为新实心块，解锁更多块。归纳可知每个块在放置瞬间都可达。复杂度 O(V·k)，V=块数，k=26 邻域，对村民建筑（几十~几百块）足够。

### 5.4 `validateStructure` + LLM 回修

`validateStructure` 一次性做 **静态 + 顺序** 两层校验：

1. **静态层（不依赖顺序）**
   - 每块目标格当前必须是空气（否则与既有世界方块重叠 → 错误「与 (x,y,z) 原有方块冲突」）。
   - 结构整体必须从 anchor 起 **26-连通**（用 BFS 连通分量判定），否则标「存在与主体断开的孤岛」。
   - 库存是否备齐每种 block 的数量（不足 → 错误「缺少 N 个 oak_planks」，可提示 LLM 缩小结构或先去采集）。
2. **顺序层**：跑 §5.3，找出真正不可达的块。

`ValidationResult { ok, List<Error>, unreachableBlocks, suggestedFixHint }`。

回修循环：
```
reviseWithLLM(agent, s, r, retriesLeft):
  if retriesLeft == 0:
      agent.addMemory("想建「"+s.name+"」但结构始终无法放置，放弃了"); return;
  prompt = "你之前给的结构无法建造，原因：" + r.errors +
           "。请修改结构（连通到地面、去掉悬空孤岛、或换落点），只返回修正后的 JSON。"
  queryLLM(prompt).thenAccept(json -> {
      Structure s2 = Gson.parse(json);
      ValidationResult r2 = validateStructure(s2, ...);
      if (r2.ok) startBuildJob(...);
      else reviseWithLLM(agent, s2, r2, retriesLeft-1);
  });
```
> 重试上限 `BUILD_MAX_RETRIES`（默认 3）来自 `ModConfig`，避免 LLM 死循环烧 token。

### 5.5 BuildJob 状态机与持久化（大规模建造）

- `BuildJob`：持有 `anchorWorldPos`、`List<PlacedStep{ target, standCell, blockType }>`（已排序）、`int cursor`（下一个待放索引）、`UUID villagerId`。
- 存入 `VillagerAgentData`（新字段 `currentBuildJob`），随村民 NBT 序列化（`writeToNBT`/`readFromNBT`）—— **服务器重启可续建**。
- 进度：每放完一块 `cursor++`；全放完则清除 BuildJob、记 memory「建成 X」、可选 `WorldStructureIndex` 登记 `BuildingRecord`。
- 若村民中途被卡/死亡：BuildJob 保留在 `agent`，复活或重连后 `tickBuilding` 接着放（已放块 `occ` 需与真实世界对齐——启动时扫一遍实际方块即可，几行代码）。

---

## 6. 破坏速度 = 玩家（借 Forge 现有计算，零偏差）

最稳的做法是 **复刻玩家破坏进度公式**，而不是自己拍脑袋：

```java
// VillagerEquipmentHelper 新增
private static final FakePlayer FAKE = FakePlayerFactory.getMinecraft(serverWorld); // 共享、轻量

public static int computeBreakTicks(ServerWorld world, BlockState state, ItemStack tool) {
    FAKE.setItemSlot(MAINHAND, tool);
    float progressPerTick = net.minecraftforge.common.ForgeHooks
            .blockStrength(state, FAKE);   // 与玩家完全相同的每 tick 破坏进度
    if (progressPerTick <= 0) return Integer.MAX_VALUE; // 不可破坏
    return (int) Math.ceil(1.0f / progressPerTick);
}
```

- `ForgeHooks.blockStrength` 内部已正确处理：方块 hardness、`isToolEffective`（是否对路工具）、工具 tier 速度（木2/石4/铁6/钻8/金12/下界9）、效率附魔。→ 村民手持对路工具时耗时与玩家 **逐 tick 一致**。
- `equipBestToolForBlock`：扫描 `agent.getInventory()` 每个 item，取 `item.getDestroySpeed(stack, state)` 最大且 `isToolEffective` 优先者，装备到 `MAINHAND`（视觉上也展示工具，复用 `CombatAction` 的装备写法）。**完全算法、无 LLM**。
- 破坏期间进度累加 `1/breakTicks`，满则 `world.destroyBlock(T, true)`；中途村民不导航、面向 T。

> 若不想引入 FakePlayer，可改为复刻公式：`breakTicks = ceil( blockHardness * (effective?30:100) / itemDestroySpeed )`，实现期二选一，FakePlayer 优先（最准）。

---

## 7. 接入点与配置开关

`VillagerEventHandler.onWorldTick()` 新增：
```java
if (ModConfig.ENABLE_BUILDING.get()) VillagerAgentManager.tickBuilding(world);
```
`tickBuilding` 仿 `tickFarming`：快循环（每 2~3 tick），只处理 `agent.getCurrentAction().type ∈ {PLACE,BREAK}` 或 `agent.currentBuildJob != null` 的村民；建筑中不与 farming/combat 抢夺（沿用 `isInFarmingState`/`ATTACK` 的互不打断判断）。

`ModConfig` 新增：
```
ENABLE_BUILDING        (bool, 默认 true)   总开关，挂在 ENABLE_WORLD_INTERACTION 下
BUILD_INTERACT_RANGE_SQ(double, 默认 2.0)  1 格内约束，可调
BUILD_BLOCK_INTERVAL   (int,  默认 3)      大规模建造时两块之间的 tick 间隔（节流，避免卡服）
BUILD_MAX_RETRIES      (int,  默认 3)      结构校验 LLM 回修上限
BUILD_STUCK_TIMEOUT    (int,  默认 100)    单块走到站点的卡住放弃阈值
```

---

## 8. 边界情况与风险

| 风险 / 边界 | 处理 |
|---|---|
| LLM 给悬空孤岛（不连通） | `validateStructure` 连通分量检测 → 回修 |
| 结构压在既有建筑/树上 | 静态重叠检测 → 回修或建议换锚点 |
| 村民库存方块不够 | 校验报「缺 N 个 X」→ 回修 / 记 memory 先去采集 |
| 天花板/悬空块 | 算法用「下方站立点」仍成立；只要 26-连通即可 |
| 站点是水/岩浆 | `standable` 加 `!isLiquid` 判定 |
| 服务器重启 | BuildJob 持久化，启动扫实际方块对齐 `occ` 续建 |
| 村民死亡/卡住 | BuildJob 保留，`tickBuilding` 后续建；单块卡住超时放弃并跳过 |
| 大型结构卡服 | `BUILD_BLOCK_INTERVAL` 节流；每 tick 只推进一块 |
| LLM JSON 解析失败 | Gson 捕获异常 → 当作不可建造 → 回修（带「请只返回 JSON」提示） |
| 破坏刷怪蛋/基岩等不可破块 | `hardness<0` 直接放弃 |

---

## 9. 文件清单（计划）

**新增**
- `ai/BlockInteractionAction.java` — 原子放置/破坏（含工具感知破坏计时）
- `ai/BuildOrderPlanner.java` — 壳层 BFS 顺序算法 + 站立点求解 + `validateStructure`
- `ai/StructureBuilder.java` — LLM 结构接口 + 回修循环
- `ai/BuildJob.java` — 大规模建造任务状态（可 NBT 持久化）

**修改**
- `ai/VillagerAction.java` — `ActionType` 加 `PLACE/BREAK/BUILD`
- `ai/VillagerEquipmentHelper.java` — `equipBestToolForBlock` / `computeBreakTicks`
- `ai/VillagerAgentData.java` — `currentBuildJob` 字段 + NBT
- `ai/VillagerAgentManager.java` — `tickBuilding` 快循环
- `config/ModConfig.java` — 建造相关开关
- `events/VillagerEventHandler.java` — 调用 `tickBuilding`

---

## 10. 分阶段实现步骤（写代码时的 check-list）

1. **原子层先行**：`VillagerAction` 加 `PLACE/BREAK`；`BlockInteractionAction.walkToAndPlace/walkToAndBreak`；先硬编码「村民面前 1 格放/破一个石头」做最小验证（参照 `FarmingAction` 的 `INTERACT_RANGE_SQ`）。
2. **工具切换 + 破坏计时**：`VillagerEquipmentHelper.equipBestToolForBlock` + `computeBreakTicks`（FakePlayer+ForgeHooks）；手拿木镐 vs 空手破坏原木，肉眼对比耗时是否=玩家。
3. **顺序算法**：`BuildOrderPlanner.computeBuildOrder` + 站立点求解；先用内存里一个小 3×3×3 结构单测（无世界），验证顺序全可达、无残留。
4. **校验 + 回修**：`validateStructure` 静态+顺序两层；写一个「故意悬空」的结构喂给 LLM，确认回修能把它改通（最多 3 次）。
5. **LLM 接口**：`StructureBuilder.requestStructure` + JSON 提示词 + Gson 解析；联调「村民说要建小屋 → 真建出来」。
6. **BuildJob 持久化**：NBT 序列化 + `tickBuilding` 续建；重启服务器验证不丢进度。
7. **索引联动**：每放/破一块调 `WorldStructureIndex.onBlockChanged`；建完登记 `BuildingRecord`。
8. **配置 + 事件接线**：`ModConfig` 开关 + `VillagerEventHandler` 调用；`gradlew compileJava` 全过。
9. **边界打磨**：液体站立点、`hardness<0`、库存不足提示、卡住超时。

---

## 11. 设计取舍说明

- **为什么用「壳层 BFS」而不是「自底向上分层」**：MC 方块不靠重力支撑，纯按 y 分层会错误拒绝合理的悬空/天花板结构；BFS 壳层只要求 26-连通 + 当时有合法站立点，更通用且无遗漏。
- **为什么破坏速度用 FakePlayer+ForgeHooks 而非自算**：逐 tick 与玩家完全一致，省去维护 hardness/tier/附魔表的麻烦，且后续 MC 版本升级自动跟随。
- **为什么工具切换不放进 LLM**：切换是确定性优化（取 getDestroySpeed 最大者），LLM 既慢又不可靠；与 `CombatAction.findBestWeaponDamage` 保持一致，纯算法。
- **为什么大规模建造也走「一个一个放」**：用户明确要求；原子层复用同一套 WALKING→ACTING 状态机，BuildJob 只负责「出队下一块」，职责清晰、易持久化、易重启续建。
