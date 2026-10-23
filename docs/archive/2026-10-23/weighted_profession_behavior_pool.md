# 加权职业行为池（Weighted Profession Behavior Pool）完全详解

> 来源：`Reference/Settlements-Alpha-mainline`（breezes 的 Settlements 模组，MC 1.21.1 / NeoForge）。
> 本文只讲一件事——**"加权职业行为池"到底是怎么工作的**：从它是什么、权重存哪、谁给权重重排，到权重最终如何决定"这个村民今天上午先干哪件事"。

---

## 1. 一句话定位

加权职业行为池 = **"职业 → 它能干的活 + 每件活被排进日程的相对偏好"** 的映射表。

它回答两个问题：
1. **这个职业能干什么**（可用性，availability）——由 `ProfessionBehaviorPool` + 通用池决定。
2. **这些活里更该先干哪个**（偏好，preference）——由每条目的 `weight` 决定，并在规划时被若干**乘数**（休息日策略 × 机会预测）二次调制。

它不是硬性的"槽位数量"，而是一个**相对调度倾向**：权重越大，在打包日程窗口时被选中的概率越高。

---

## 2. 它在整条规划管线里的位置

```
[注册]  PoolModule（DI）          —— 每个职业一份 ProfessionBehaviorPool
   │
[解析]  BehaviorPoolResolver       —— 职业池 + 通用池 → List<WeightedBehavior>
   │
[规划]  HeuristicPlanGenerator → DayPlanComposer → WindowPacker（权重被消费）
   │
[执行]  PlanRunner / IBehaviorCatalog.createBehavior(key) —— 真正跑起来
```

关键设计：**能力目录（catalog）与职业职责（pool）分离**。
- `IBehaviorCatalog` = 全量"能力库"（每个行为一条元数据 + 一个工厂）。
- `ProfessionBehaviorPool` = "职位描述"（引用 catalog 里的 key + 给权重）。
- 加一个新职业 → 只加一份 pool 定义；加一个新行为 → 只加 catalog 条目 + 在相关 pool 里引用，两边互不侵入。

---

## 3. 数据模型（分层拆解）

### 3.1 `BehaviorKey` —— 稳定标识

```java
public record BehaviorKey(String id) { ... }
```

- 一个行为一个稳定字符串 id（`"eat_food"`、`"milk_cow"`、`"forge_tool"`…），常量按主题分组（Universal / Farming / Smithing / Animal / Nitwit…）。
- **稳定性是硬约束**：key 会被序列化进已保存的计划里，改名会静默弄坏旧存档。
- 职业→行为映射**不在这里**，而在 pool。

### 3.2 `PoolEntry` —— 池里的一条（key + 权重）

```java
public record PoolEntry(BehaviorKey key, int weight) {
    // weight < 0 直接抛异常
    public static PoolEntry of(BehaviorKey key) { return new PoolEntry(key, 1); }       // 默认权重 1
    public static PoolEntry of(BehaviorKey key, int weight) { ... }
}
```

**权重语义（关键）**：
- `weight = 1`（默认）→ 正常出现。
- `weight > 1`（如 `FISHING, 5`）→ 在打包窗口里出现得更频繁。
- `weight = 0` → **仍对"显式系统"可用**（事件触发、LLM 点名、pin），但**被排除在常规启发式打包之外**——即"平时不自己主动干，但被点名时能干"。

### 3.3 `ProfessionBehaviorPool` —— 一份职业的行为表

```java
@Builder @Getter
public class ProfessionBehaviorPool {
    private final VillagerProfessionKey profession;
    @Singular private final List<PoolEntry> entries;   // 只列职业专属行为
    public boolean contains(BehaviorKey key) { ... }
}
```

- 只列**职业专属**行为；通用行为（吃、逛、休息、交易）**不重复列出**，由 resolver 自动合并。
- 通过 Dagger 的 `@IntoSet` 多绑定注册（见 §4）。

### 3.4 `BehaviorPlanningMetadata` —— 行为描述符（规划系统看到的行为画像）

这是"给规划器看的行为元数据"，**职业无关**：

| 字段 | 作用 |
|---|---|
| `key` | 稳定标识 |
| `displayName` / `description` | 展示 + 喂给 LLM prompt（`toPromptLine()`） |
| `category` | `WORK / SOCIAL / SELF_CARE / LEISURE / COMBAT`（粗分类） |
| `intensity` | `HEAVY / LIGHT / NONE`（劳动强度，用于休息日抑制） |
| `requiredChannels` | 行为占用哪些"通道"，决定能否与别的行为并发（channel 不相交即可并发） |
| `relevantMemoryHints` / `producedObservationHints` | 记忆/观察提示词 |
| `opportunities` | 声明"干这活需要哪些机会"（`OpportunityRequirement`），为空 = 永不打折 |
| `estimatedDuration` | 预估耗时（GameTicks），打包器用它占时间窗 |
| `maxRunDuration` | 单次运行的真实时长上限（ClockTicks，默认 2 分钟），超时被 PlanRunner 强停 |
| `cooldown` | 冷却区间（`CooldownRange`），打包时每次发射抽一个冷却值 |
| `preconditionSummary` / `interruptible` | 前置条件摘要 / 是否可中断 |

### 3.5 `WeightedBehavior` —— 描述符 + 权重（解析后的产物）

```java
public record WeightedBehavior(BehaviorPlanningMetadata descriptor, int weight) {
    public BehaviorKey key() { return this.descriptor.getKey(); }
}
```

- 这是 resolver 交给规划器的**干净产物**：规划器只碰 `List<WeightedBehavior>`，**绝不直接碰职业池**。
- 权重允许为 0（描述符还在，但打包时被 `weight > 0` 过滤器筛掉）。

### 3.6 支撑枚举

```java
enum BehaviorCategory { WORK, SOCIAL, SELF_CARE, LEISURE, COMBAT }
enum WorkIntensity  { HEAVY, LIGHT, NONE }
```

### 3.7 `IBehaviorCatalog` —— 能力库

```java
Optional<BehaviorPlanningMetadata> getDescriptor(BehaviorKey key);
Optional<IBehavior<BaseVillager>> createBehavior(BehaviorKey key);  // 每次调用造新实例，实例不跨村民/槽位共享
boolean exists(BehaviorKey key);
List<BehaviorPlanningMetadata> getAllDescriptors();
```

规划期只查描述符；执行期才用 key 换出**全新** `IBehavior` 实例。

---

## 4. 注册：`PoolModule`

用 Dagger `@Provides @IntoSet` 把每份 `ProfessionBehaviorPool` 注入一个 `Set<ProfessionBehaviorPool>`。示例（截取）：

```java
@Provides @IntoSet
static ProfessionBehaviorPool fishermanPool() {
    return ProfessionBehaviorPool.builder()
            .profession(VillagerProfessionKey.FISHERMAN)
            .entry(PoolEntry.of(BehaviorKey.FISHING, 5))   // 钓鱼权重 5 —— 渔夫的主业
            .entry(PoolEntry.of(BehaviorKey.TAME_CAT))      // 驯猫权重 1 —— 偶尔
            .build();
}
```

观察要点：
- 农民池塞了 13 个行为（harvest_*、cultivate_plot、tame_wolf、walk_dog…），因为原版农民能干的事最多。
- 渔夫把 `FISHING` 权重抬到 5，其余默认 1——**同一职业内部也有主次**。
- `NITWIT` / `NONE` 只给 `SCAVENGE` 之类，且往往给高权重（4），表示"它们也就只干这个"。

---

## 5. 解析：`BehaviorPoolResolver`

**唯一**把"池(可用性) + 目录(元数据) + 通用条目"三者合并的地方：

```java
private static final List<PoolEntry> UNIVERSAL_ENTRIES = List.of(
    PoolEntry.of(BehaviorKey.EAT_FOOD),        // 吃
    PoolEntry.of(BehaviorKey.TRADE_INITIATE),  // 交易
    PoolEntry.of(BehaviorKey.COURTSHIP_INITIATE), // 求偶
    PoolEntry.of(BehaviorKey.DONATE_EMERALDS), // 捐绿宝石
    PoolEntry.of(BehaviorKey.MANAGE_CHESTS),   // 管理箱子
    PoolEntry.of(BehaviorKey.CRAFT_GOODS),     // 造杂货
    PoolEntry.of(BehaviorKey.PET_ANIMAL)       // 撸动物
);

public List<WeightedBehavior> resolve(VillagerProfessionKey profession) {
    // 1) 先放职业专属条目
    // 2) 再 putIfAbsent 合并通用条目（通用条目不覆盖职业已声明的权重）
    // 3) 对每个 key 查 catalog.getDescriptor(...)，存在才包成 WeightedBehavior
}
```

注意 `putIfAbsent`：**职业自己声明的权重优先于通用默认**；通用条目只补空缺。

**此处不做任何 day-type 过滤**——休息日的加权由下游 `RestDayPolicy` 乘数完成，resolver 保持纯函数。

---

## 6. 权重如何被消费（核心机制）

### 6.1 三阶段管线

`HeuristicPlanGenerator` 是薄适配器，真正干活在 `DayPlanComposer.compose`：

```
compose(context, intent)
  1. Random random = new Random(context.planSeed())       // 单一种子，全流程可复现
  2. PlanFrame frame = computeFrame(context)               // 醒/睡/上工/下工边界（含 Chronotype 偏移）
  3. List<PlanBand> bands = deriveBandsForFrame(...)       // 上午/下午/晚上 三个时间窗
  4. anchors = computeAnchors(...)                          // 用餐锚点 + intent 里的 pin
  5. for band : fillBand(...)                               // 每窗的空闲区间用贪心打包
```

### 6.2 Band 推导 —— 时间窗 + 候选池 + 基础优先级 + packFactor

`deriveBands` 产出 3 个 `PlanBand`，每个 band 携带**独立候选池**和**基础优先级**：

| Band | 候选池（工作日） | 基础优先级 | packFactor |
|---|---|---|---|
| MORNING | **WORK 类**（`workBehaviors()`） | 70 | 0.75 |
| AFTERNOON | social(高CHA在前) + LIGHT work + SELF_CARE + LEISURE | 50 | 0.55 |
| EVENING | SOCIAL + LEISURE + SELF_CARE（**无 work**） | 40 | 0.55 |

- **休息日 / NITWIT**：MORNING 改用全量 `availableBehaviors`（而非纯 WORK），优先级降到 55。
- AFTERNOON 的 social 排序由**遗传基因 CHARISMA ≥ 0.45** 决定：高魅力社交优先，低魅力社交垫底——同一职业不同个体，下午的安排会不一样。
- `packFactor` 是"超排"旋钮：`WindowPacker` 发射成功后光标只前进 `round(duration × packFactor)` 而非完整 duration，让一个时间窗能塞进比原始宽度"更多（因为通常低估耗时）"的行为。

### 6.3 有效权重公式

这是理解"加权"两字最关键的一行。`DayPlanComposer.fillBand` 里：

```java
PlannerPolicy.EffectiveWeightMultiplier multiplier =
        fill.multiplier().andThen(PlannerPolicy.opportunityMultiplier(context)); // 两个乘数相乘

candidate = new PackingCandidate(key,
        Math.max(0.0, behavior.weight() * multiplier.apply(behavior)),  // ← 有效权重
        duration, cooldown);
```

即：

```
effectiveWeight = baseWeight
                × restDayMultiplier(category, intensity)   // 仅休息日非 1
                × opportunityMultiplier(0.2 或 1.0)         // 永远叠加
```

- **工作日**：`restDayMultiplier` 恒为 1.0，只剩 `baseWeight × opportunityMultiplier`。
- **休息日**：`restDayMultiplier` 按 category×intensity 给系数（见 §6.7）。
- 结果 `≤ 0` 的候选被 `WindowPacker` 的 `weight > 0` 过滤器直接剔除（"降到 0 = 看不见"）。

`restDayMultiplier` 的分支（`PlannerPolicy`）：

```java
SOCIAL → socialMultiplier
SELF_CARE → selfCareMultiplier
LEISURE → leisureMultiplier
WORK + LIGHT → lightWorkMultiplier
WORK + HEAVY → heavyWorkMultiplier
其余（含 COMBAT）→ 0.0    // 战斗行为休息日被完全压制
```

### 6.4 选择策略（二选一）

接口 `SlotSelectionStrategy.select(eligible, emittedKeys, random)`，两种实现：

**(a) `WeightedRandomSelectionStrategy`（启发式默认）—— 覆盖优先的加权随机**

```java
fresh = eligible.filter(!emittedKeys.contains(key));   // 先取"还没被发射过"的
pool  = fresh.isEmpty() ? eligible : fresh;            // 全都被发射过 → 全体竞争
return RandomUtil.weightedChoice(weightMap, random);   // 累计权重随机
```

- **覆盖优先（coverage-first）**：本窗口还没出现过的 key 优先被选，保证**多样性**（密度来自多样，而非重复一个 key）。
- 全部轮过一遍后才允许重复。

**(b) `OrderedPreferenceSelectionStrategy`（LLM 叠加路径）—— 确定性有序**

- 用于 **SIS 日计划路径**：LLM 从一个封闭菜单里点名并排序行为，这个策略**完全尊重那个顺序**，不做随机。
- 没被点名的 key 排在末尾（unranked tail），只有没有任何被点名 key 可选时才轮到。
- 平局按 `BehaviorKey.id()` 字典序打破，保证与池迭代顺序无关。

**加权随机算法本体**（`RandomUtil.weightedChoice`）—— 累计权重游走：

```java
totalWeight = Σ weight
target      = random.nextDouble(0, totalWeight)
current     = 0
for entry in map:                    // LinkedHashMap 保持插入序
    current += entry.value
    if current > target: return entry.key
return last                          // 浮点累计可能差一点没到 total，最后一条兜底
```

等价于"在 [0,total) 上抽一个点，落在谁区间就选谁"，`target` 均匀 → 选中概率 ∝ weight。

### 6.5 `WindowPacker.pack` —— 贪心时间轴填充（逐行）

```java
pack(pool, startCivil, endCivil, basePriority, strategy, random, packFactor, nextEligible) {
    candidates = pool.filter(weight > 0);            // 零/负权重剔除
    cursor = startCivil + random.nextInt(0, MIN_SLOT_SPACING);  // 种子相位偏移，防同职业村民同 tick 生成雷同布局
    emitted = 0;
    while (cursor < endCivil) {
        eligible = candidates
            .filter(cursor >= nextEligible[key])                  // 冷却已过
            .filter(cursor + max(1, duration) <= endCivil);       // 还塞得下
        if (eligible.isEmpty()) { cursor += random(min/2, min+1); continue; }  // 留空档
        chosen   = strategy.select(eligible, emittedKeys, random);
        emit Slot(chosen.key, cursor, duration, max(0, basePriority - emitted)); // 优先级递减
        emittedKeys.add(chosen.key);
        nextEligible[chosen.key] = cursor + max(MIN_SLOT, cooldown.drawTicks(random)); // 抽冷却，决定下次最早
        emitted++;
        cursor += max(MIN_SLOT, round(duration * packFactor));     // 超排前进
    }
}
```

要点：
- **冷却**：每发射一个 key，从它的 `CooldownRange` 抽一个冷却值，决定它下次最早何时能再被选——制造"交错"。
- **优先级递减**：越早发射的槽位优先级越高（`basePriority - emitted`），同一 band 内后塞的活优先级逐级下降。
- **种子相位偏移**：`startCivil + random[0,10分钟)`，且这个 random 来自 `planSeed` 派生的**单一种子**——既打破村民间"锁步"，又保证同一村民同一天重算可复现。
- `nextEligible` 可跨多次 `pack` 调用传递（跨 band 的节奏接缝），例如把当天已 pin 的锚点作为基线，避免填充槽撞进 pin 的冷却窗。

### 6.6 `OpportunityForecaster` —— 机会预测（把"干不了"的打折到 0.2）

- 对每个带 `OpportunityRequirement` 的行为，逐个检查需求是否满足，**AND 组合**（有一个不满足 → "缺乏机会"）。
- 需求类型（`OpportunityProbe`）：`hasAnySite`（有没有相关的衰减空间记忆点）、`holdsAnyItem`（背包里有没有某物）、`hasJobSiteBlock`（工作点是不是某方块，且**不触发同步 chunk 加载**，未加载按"缺乏"处理）。
- 无需求的行为**永不打折**。
- 结果 `lacking` 集合喂给 `opportunityMultiplier` → 缺乏者 ×0.2，其余 ×1.0。
- **永远不是 0**——只是 20% 概率权重，仍可能被选中（保留一点点随机性）。

### 6.7 `RestDayPolicy` —— 休息日的按职业权重策略

每职业一组系数（`1.0=不变，>1=放大，0=完全压制`）：

| 职业 | heavyWork | lightWork | social | selfCare | leisure |
|---|---|---|---|---|---|
| 默认（多数职业） | 0.1 | 0.3 | 1.5 | 1.3 | 1.8 |
| NITWIT | 0.0 | 0.0 | 1.3 | 1.5 | 2.0 |
| NONE（失业） | 0.0 | 0.0 | 2.0 | 1.3 | 1.5 |

休息日：重活基本被压死，社交/自理/休闲被放大；傻子和失业者重活轻活全 0（他们本来也不干活）。

---

## 7. 确定性保证（为什么重算结果不变）

- `context.planSeed()` 是**每村民（UUID 派生）× 每自然日**的种子，喂 `new Random(planSeed)`。
- 整条管线（band 推导、用餐掷骰、机会打折、加权随机、窗口相位偏移、冷却抽取）**全部**从这同一个 seeded RNG 顺序取数。
- 因此：**同一村民、同一天，重算得到完全相同的计划**；不同村民（不同 seed）得到不同计划。
- 计划生成后**冻结持久化**，`WindowPacker` 文档明言：`WeightedRandomSelectionStrategy` 的累计顺序**不可改动**，否则破坏"重载可复现"的保证。

---

## 8. 端到端数值示例（渔夫，工作日）

假设 `FISHERMAN` 池 = `{FISHING:5, TAME_CAT:1}`，合并通用池后（通用默认 1）：

```
FISHING(5)  TAME_CAT(1)  EAT_FOOD(1)  TRADE_INITIATE(1)  MANAGE_CHESTS(1)  CRAFT_GOODS(1)  PET_ANIMAL(1) ...
```

1. **Band 划分**：MORNING 只取 WORK 类 → 假设 `FISHING`、`CRAFT_GOODS` 是 WORK；AFTERNOON 取 social+lightwork+selfcare+leisure；EVENING 取 social+leisure+selfcare。
2. **有效权重**（工作日，机会都不缺）：MORNING 里 `FISHING = 5×1.0×1.0 = 5`，`CRAFT_GOODS = 1×1.0×1.0 = 1`。
3. **填充**：MORNING 窗口内第一次选（coverage-first 全体 fresh）→ 累计权重 `[5, 1]`，`FISHING` 有 5/6 概率先被选；之后 `FISHING` 进入冷却，`CRAFT_GOODS`（fresh）优先补上。结果：**上午以钓鱼为主，杂活穿插**。
4. **若渔夫今天缺"鱼竿"机会**：`FISHING` 被 `OpportunityForecaster` 判 lacking → `5×0.2 = 1.0`，降为与杂活同档，上午就不再死磕钓鱼。

这正是"加权"的意义：**静态权重定基调，乘数（休息日/机会）按当天现实动态改调，覆盖优先保证多样性**。

---

## 9. 对照 VillagerAgent 的移植要点

我们的 `ProfessionGoalGenerator`（静态 if-else，返回固定 4 条 goal，无权重无时段无 mood）正是要替换的对象。移植时对齐：

| Settlements 概念 | VillagerAgent 落点 |
|---|---|
| `BehaviorKey` | 新 `BehaviorKey`（稳定 id，可先映射到现有 `DailySchedule` 的 5 种 activity） |
| `PoolEntry(key,weight)` | 直接照搬 record |
| `ProfessionBehaviorPool` | 每职业一份 `Map<BehaviorKey,Integer>`（farmer/weaponsmith/toolsmith/librarian/cleric/nitwit/default） |
| `BehaviorPoolResolver` | 合并职业池 + 通用条目（吃/休息/社交/交易/逛） |
| `BehaviorPlanningMetadata` | 精简版描述符：category、intensity、**timeBias（时段乘数）**、**moodBias（心情乘数）**、levelScaling、估计耗时、冷却 |
| `DayPlanComposer` / `WindowPacker` | `DeterministicPlanGenerator`：把 4 时段当 4 个 band，按有效权重贪心采样 |
| `WeightedRandomSelectionStrategy` | coverage-first + 累计权重游走（`planSeed = hash(villagerId, day)` 保证确定性） |
| `RestDayPolicy` | 可选：`PlanDayType` + 每职业系数 |
| `OpportunityForecaster` | 后续接我们的 `WorldStructureIndex`/事件总线，做"有没有活可干"的打折 |

**关键移植约束（沿用 Settlements 的教训）**：
- weight=0 语义保留：**显式系统可用、常规打包排除**。
- 有效权重 = baseWeight × timeBias × moodBias × levelScaling，落 0 即剔除。
- 确定性：所有随机来自单一 `planSeed`，不碰 `Math.random()`/静态共享 RNG。
- 只把"职业专属"放职业池，通用行为（吃/睡/社交）在 resolver 统一合并，别每职业重复列。

---

## 10. 关键类速查表

| 类 | 层 | 职责 |
|---|---|---|
| `BehaviorKey` | domain/catalog | 稳定行为标识 |
| `PoolEntry` | domain/catalog | key + 相对权重（≥0） |
| `ProfessionBehaviorPool` | domain/catalog | 职业 → 条目表 |
| `BehaviorPlanningMetadata` | domain/catalog | 行为画像（分类/强度/机会/耗时/冷却） |
| `WeightedBehavior` | domain/catalog | 描述符 + 权重 |
| `IBehaviorCatalog` | domain/catalog | 能力库（描述符 + 工厂） |
| `PoolModule` | di | 每职业 pool 的 DI 注册 |
| `BehaviorPoolResolver` | application/catalog | 职业池+通用池 → WeightedBehavior |
| `HeuristicPlanGenerator` | application/planning | 确定性规划适配器 |
| `DayPlanComposer` | application/planning | frame→bands→anchors→fill 管线 |
| `WindowPacker` | application/planning | 贪心时间轴打包 |
| `WeightedRandomSelectionStrategy` | application/planning | 覆盖优先加权随机 |
| `OrderedPreferenceSelectionStrategy` | application/planning | LLM 有序偏好 |
| `PlannerPolicy` | application/planning | 乘数策略（restDay/opportunity）+ palette |
| `OpportunityForecaster` | application/planning | 机会缺失集（×0.2） |
| `RestDayPolicy` | domain/schedule | 休息日每职业系数 |
| `RandomUtil.weightedChoice` | shared/util | 累计权重游走 |
