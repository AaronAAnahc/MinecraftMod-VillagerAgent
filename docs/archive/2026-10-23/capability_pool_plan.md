# 能力池（Capability Pool）设计与村民行为差异规划

> 状态：**设计计划 v2**（未实现）。
> 前置阅读：`docs/weighted_profession_behavior_pool.md`（职业行为池机制）、`docs/villager_agent_intelligence_pipeline.md`（两档智能管线）、`docs/settlements_mod_villager_intelligence.md` §7.6（LLM 驱动行为）。

---

## 0. 结论先行

**职业池 ≠ 能力池。** 渔民靠钓鱼吃饭（职业职责），但图书管理员周末去河边甩两杆（业余爱好）——这靠"职业"解释不了，只能靠"个体能力"解释。

三层叠加：

1. **职业池**——"主业是什么、该优先干什么"（同职业一致，静态）。
2. **能力池**——"会什么、练到多强"（每村民一份，动态成长，**硬编码**）。
3. **行为目录**——"做什么需要什么能力、最低几级"（静态元数据）。

渔业行为能否进入某村民的日程，由能力池决定（`fishing >= 1` 解锁）；权重多高，由职业池 + 能力等级 + 情境共同决定。

---

## 1. 三个概念的边界

| 概念 | 数据形态 | 变化频率 | 作用 |
|---|---|---|---|
| 职业职责池 `ProfessionPool` | 职业 → 行为列表 + 主业权重 | 静态（同职业一致） | 主业优先级 |
| 能力池 `CapabilityPool` | 村民 → `Map<能力, 等级>` | 动态（事件/聊天/衰减） | 解锁 + 权重 + 执行调制 |
| 行为目录 `Catalog` | 行为 → 元数据（能力/最低等级/耗时/冷却/机会） | 静态 | "做什么需要什么" |

---

## 2. 能力池数据模型

### 2.1 `CapabilityId` —— 硬编码枚举（不是数据驱动）

```java
public enum CapabilityId {
    FARMING, FISHING, SMITHING, BREWING, ENCHANTING,
    CRAFTING, GATHERING, SOCIAL, COMBAT
    // 首批 9 个全做
}
```

**为什么硬编码**：你点破了关键——能力跟动作挂钩，动作是写死的代码（`FarmingAction`/`CraftingAction`/`CombatAction`…），所以能力也写死成枚举。枚举是类型安全的门控 key，编译器保证不会拼错，也不用维护一份 JSON 映射。**不做**"能力 id 从配置文件加载"——那会跟写死的 action 代码脱节，得不偿失。

- 稳定 id，进 NBT，**不可改名**（同 `BehaviorKey` 教训）。
- 首批 9 个全上，覆盖现有 farmer/weaponsmith/toolsmith/librarian/cleric 主业 + 常见业余。

### 2.2 `CapabilityPool` —— 每村民能力表

```java
public class CapabilityPool implements INBTSerializable<CompoundNBT> {
    private final Map<CapabilityId, Integer> xpByCapability;
    private final Map<CapabilityId, Long> lastPracticedDay;   // 衰减用

    public int levelOf(CapabilityId id);            // 0 = 完全不会
    public void addXp(CapabilityId id, int n);      // 事件/聊天升级入口（同时刷新 lastPracticedDay）
    public void decayTo(long today);                // 按 §4.3 衰减曲线扣 XP
    // NBT: { fishing:{xp:120, day:42}, farming:{xp:45, day:41}, ... }
}
```

- 等级用**独立的分级表**（默认 11 级，`proficiency.json` 改 thresholds 可扩），与 `VillagerProficiency` 的 5 级原版交易表分开——能力等级比交易等级更细。
- 存 `VillagerAgentData`（跟 AI 数据放一起），**不另开 Forge capability**——只有 AI 村民需要，且要跟规划器/门控就近读写。

### 2.3 初始等级：出生全 0 + 随机爱好 1 个，认领职业方块才 +3

**出生时**（尚未选职业）：所有能力 0，只随机 1 个"爱好" = 1（用 `planSeed` 抽，确定性）。

**认领职业方块**（workstation，原版村民转职事件）：对应职业能力 **+3**。

| 村民 | 爱好 | 认领职业后 |
|---|---|---|
| 正常村民 | 随机 1 个 =1 | 对应职业能力 +3 |
| 无业村民（unemployed） | 随机 1 个 =1 | 无（还没认领工作站） |
| 傻子（nitwit） | **无爱好（全 0）** | 永不认领，action 另写 |

- **爱好 == 认领职业** 时：1 + 3 = **4 级**起步（比普通新手高一级）。
- 等级 3 = "职业第一级"；等级 1 = "会一点"；等级 0 = 完全不会。
- 跟原版对齐：村民出生无业、认领工作站才转职，能力随之 +3。
- 起步值不代表上限，谁都能把任何能力练上去。

### 2.4 与现有 `VillagerProficiency` 的关系（关键决策）

`VillagerProficiency`（已有）是单一标量 XP/等级，喂原版交易 UI。两者并存：

| | `VillagerProficiency`（已有） | `CapabilityPool`（新增） |
|---|---|---|
| 维度 | 单一"总等级" | 分能力等级 |
| 用途 | 原版交易 UI / 交易报价解锁 | 行为解锁 + 权重 + 执行调制 |
| 升级来源 | 交易 XP | 事件/聊天驱动 |

`VillagerProficiency` 的 level 由**职业主能力的等级**映射：`vanillaLevel = (主能力等级 - 1) / 2`（渔民总等级跟随 fishing），交易 UI 自然爬升。**不做**"删掉 VillagerProficiency 改 map"的大迁移。

### 2.5 能力等级 ↔ 原版交易等级对齐（每 2 级 = 原版 1 级）

**每 2 级能力 = 原版 1 个交易等级**，公式 `vanillaLevel = max(0, (capabilityLevel - 1) / 2)`：

| 能力等级 | 含义 | 原版交易等级 |
|---|---|---|
| 0 | 不会 | — |
| 1 | 会 | — |
| 2 | 更会一点 | — |
| 3 | 职业第一级 | Novice(1) |
| 4 | 第一级·熟练 | Novice(1) |
| 5 | 职业第二级 | Apprentice(2) |
| 6 | 第二级·熟练 | Apprentice(2) |
| 7 | 职业第三级 | Journeyman(3) |
| 8 | 第三级·熟练 | Journeyman(3) |
| 9 | 职业第四级 | Expert(4) |
| 10 | 第四级·熟练 | Expert(4) |
| 11 | 职业第五级 | Master(5) |

- 交易解锁**完全复用原版"职业等级→可交换物品"表**，用能力等级除以 2 映射过去，不自己发明。
- 偶数级（4/6/8/10）不涨交易等级、只给 buff（速度等）；奇数级（3/5/7/9/11）才涨交易等级。
- 能力上限默认 11（到 Master）；`CapabilityPool` 用独立分级表（`proficiency.json` 改 thresholds 可扩），与 `VillagerProficiency` 的 5 级交易表分开。

---

## 3. 能力 ↔ 行为解锁等级关系

行为目录里声明 `requiredCapability + minLevel`，能力达标才解锁。示例（每个行为单独设，不全局一刀切）：

| 能力 | 1 级解锁 | 2 级 | 3 级 |
|---|---|---|---|
| fishing | 钓鱼（岸边/鱼竿） | 更快上钩 | **下水抓鱼（潜水）** |
| farming | 种植/收获 | 开垦新地 | 大面积高效耕作 |
| smithing | 熔炼矿石 | 锻造铁工具 | 锻造高级装备 |
| brewing | 简单药水 | 高级药水 | 定制药水 |
| enchanting | 低级附魔 | 中级附魔 | 高级附魔 |
| crafting | 基础合成 | 复杂合成 | 高级合成 |
| gathering | 采集基础资源 | 高效采集 | 稀有资源 |
| social | 基本聊天 | 交易/求偶 | 领导/组织 |
| combat | 自保/逃跑 | 主动防御 | 战斗技巧 |

- **门控**放 `VillagerSkill.canHandle`（已是纯谓词）：`CapabilityPool.levelOf(required) < minLevel` 直接返回 false。
- **效果调制**放 `execute`：等级越高越快/越多/越成功。
- **等级提升 buff 硬编码在代码里**：每个能力每级的提升效果（钓鱼速度、action 解锁、产出加成）写死成代码映射（如 `FishingAction.SPEED_BY_LEVEL[]` 或 `CapabilityBuffs` 静态表），不做 JSON 数据驱动——因为效果本身就是代码逻辑（读哪个数值、解锁哪个 action）。

---

## 4. 能力来源与升级

### 4.1 事件升级源（挂到已有 action 完成点）

| 事件 | +XP 能力 |
|---|---|
| 收获作物 / 开垦耕地（FarmingAction） | +farming |
| 熔炼 / 锻造（CraftingAction smith 分支） | +smithing |
| 合成任意物品（CraftingAction） | +crafting |
| 击杀怪物 / 防御集结（CombatAction / VillageSignalBoard） | +combat |
| 成功交易（VillagerTradeSystem 成交） | +social |
| 吸收掉落物（ItemAttractionSystem） | 按物品种类（矿石→smithing，作物→farming…） |
| （未来）钓鱼 / 酿造 | +fishing / +brewing |

### 4.2 聊天升级源（你新增的）

**跟对应职业的村民聊天 → 缓慢获得该职业能力经验**：
- 跟铁匠聊天 → +smithing（慢）、跟农民聊天 → +farming（慢）…
- 这给了"拜师"一个无 LLM 的机制：新手村民主动找老师傅聊天就能入门，不需要 LLM 生成教学内容。
- 数值上比"亲手做"慢很多（例如亲手做 +10，聊天 +1），保持"实践为主、请教为辅"。

### 4.3 XP 衰减曲线（慢 → 快，含 2 天宽限期）

每个能力记 `lastPracticedDay`。**RUST_DAYS 设大一点**（默认 14），且**有 XP 增加就衰减清零，今天 + 明天两天不衰减，后天起才开始**：

```
effectiveDays = max(0, daysSinceLastUse - 2)           // 2 天宽限期
retention = max(0, 1 - (effectiveDays / RUST_DAYS)^2)   // RUST_DAYS 默认 14
effectiveXp = xp × retention
```

- 前 2 天完全不掉（宽限），第 3 天起才按平方曲线"慢起快落"衰减——短期不练没事，长期荒废才掉得凶。
- 每次 `addXp`（练习/聊天）刷新 `lastPracticedDay`，衰减归零、宽限期重新计。
- **掉级 = 重新锁定配方**（你已定）：effectiveXp 跌破等级门槛 → 掉级 → `unlockedRecipes` 里该等级以上的配方移除（见 §5）。

---

## 5. 配方解锁：SkillCraftCatalog + 每村民可用配方表

**不是拆两张表**——把现有 `ProfessionCraftCatalog` **改名 `SkillCraftCatalog`**，一张统一目录，每个配方声明 `{输出, requiredCapability, requiredLevel}`：

| 配方 | requiredCapability | requiredLevel |
|---|---|---|
| 木剑、熔炉、面包、箱子（通用） | `crafting` | 1~5 |
| 鱼竿 | `fishing` | 3 |
| 药水 | `brewing` | 3 |
| 附魔书 | `enchanting` | 3 |
| 铁剑 | `smithing` | 3 |
| 锄头 | `farming` | 3 |

**每村民"可用配方表"（unlockedRecipes）**：
- 一开始**为空**。
- 能力升级：扫描 `SkillCraftCatalog`，把 `requiredCapability == X && requiredLevel <= 新等级` 的配方**加进** unlockedRecipes。
- 能力掉级：把 `requiredLevel > 当前等级` 的配方**移出**（掉级即锁）。
- 判定能否合成：配方在 unlockedRecipes 里，且兜底校验 `levelOf(requiredCapability) >= requiredLevel`。
- unlockedRecipes 存 `VillagerAgentData`（NBT），每条记录其 requiredLevel。

好处：图书管理员练高 crafting 能造生活用品，但造不出鱼竿（fishing 0 级）——**职业专精护城河保住，crafting 有独立价值**。通用和职业配方在同一张表里，只是 requiredCapability 不同。

---

## 6. 行为差异编排（三层 + 并集候选 + 一个公式）

### 6.1 候选集 = 职业池 ∪ 能力解锁池（并集，不是纯乘法）

纯乘法有个坑：**非职业行为职业权重 = 0，乘什么都还是 0**，图书管理员永远钓不了鱼。所以：

```
候选集 = 职业池行为（主业，职业权重高）
       ∪ 能力解锁行为（levelOf(required) >= minLevel 的所有行为，业余权重由等级给）
```

- 渔民：fishing 来自职业池（权重 5）+ 能力（等级 3）→ 主业高权重。
- 图书管理员：fishing 只能来自能力解锁（练到 >=1）→ 业余低权重。

### 6.2 有效权重公式

```
effectiveWeight(行为, 村民, 时段)
    = baseWeight                     // 职业池给主业高、能力解锁给业余低
    × capabilityMultiplier(level)    // 1 + 0.3×level（0 级 = 0 = 未解锁）
    × contextMultiplier(time, mood, opportunity)   // 时段/心情/需求/机会
```

结果 `<= 0` 剔除。

### 6.3 三层差异源（粗 → 细）

| 层 | 变量 | 粒度 | 谁给 |
|---|---|---|---|
| 职业层 | `baseWeight`（主业 vs 业余） | 同职业一致 | 职业池 |
| 能力层 | `capabilityMultiplier(level)` | 每村民每能力 | 能力池 |
| 情境层 | `contextMultiplier` | 此刻 | 时段/心情/需求/机会 |

---

## 7. 执行调制

能力等级影响"选不选"（门控）也影响"干得好不好"（效果）：
- 门控：`canHandle` 检查 `levelOf(required) >= minLevel`。
- 效果：`execute` 内读 `levelOf`——farming 等级→每 tick 多耕几块/更高成功率；fishing→更快上钩/更好掉落；combat→攻击加成。

---

## 8. 聊天行为（LLM 可选，你新增）

聊天是**无 LLM 也能触发**的行为，且是能力经验的来源（§4.2）：

- `SocializeSkill`（无 LLM）：两村民进入"聊天状态"（气泡 + 社交值缓慢增长），持续 N 秒，**不需要 LLM**。
- 有 LLM：`VillagerSocialSystem`（已有）叠加实际对话内容。
- 无 LLM：从 `default_chat_lines.json` 抽默认对话（如"今天天气不错""收成怎么样？"），或纯状态显示"（两个村民在聊天）"。
- 触发：由社交 routine goal 驱动（职业池里通用 `socialize`），LLM 关掉也照常发生——只是内容降级为默认台词。

---

## 9. 调度机制：怎么"取下一件事" + 打断 + todo 生命周期

### 9.1 抽取：EMERGENCY 硬优先，其余两段式软抽样

你问的对：为了照顾低 priority 事件，**设 EMERGENCY 挡位（priority ≥ 9）无条件优先执行；没有 EMERGENCY 时，其余 todo 用两段式软抽样**。

```
pickNextTodo(goals):
    1. emergency = goals.filter(priority >= 9)
       if emergency 非空: return 其中 priority 最高的那个       // 硬优先，立即执行
    2. 其余 goals 按 priority 分组 → Map<priority, List<Goal>>
    3. 加权抽一个"挡位"：P(挡位 T) ∝ T                          // priority 越大越容易被抽到
    4. 挡位 T 内，按 goal 的 weight（能力×情境权重）加权随机取一个
```

- **EMERGENCY 硬优先**（无条件先跑）+ **非紧急软优先**（高 priority 挡位更常被抽到、低挡位也有机会）——两者并存：紧急事不排队，低 priority 日常事也有机会被抽到。
- 与 LLM 叠加兼容：LLM 只改 goal 的 priority/weight/存在，抽取逻辑不变。

### 9.2 打断（抢占）—— 用 priority 挡位，不用布尔"立刻行动"标签

你问"加一个必须立刻行动的标签进 todo 打断其他行为？"——我建议**不要布尔标签**，改用 **priority 挡位做抢占**，因为布尔标签解决不了"多个立刻行动怎么办"。

定义三个挡位：

| 挡位 | priority | 语义 |
|---|---|---|
| EMERGENCY | 9–10 | 立即行动（防御、逃生、濒死自救） |
| URGENT | 7–8 | 尽快（饥饿觅食、睡觉、限时 todo） |
| ROUTINE | 1–6 | 日常（职业主业、业余、社交、闲逛） |

抢占规则：
```
shouldPreempt(current, candidate) =
    candidate.priority >= 9                          // EMERGENCY 无条件抢
    || candidate.priority - current.priority >= 3    // 或比当前高 3 档以上
```

**多个"立刻行动"todo 怎么办**：它们都进 todo，按 priority 排序，**同一时刻只有一个 running（单活动槽）**。最高的先跑，跑完（或被打断）后下一个最高接着跑。所以"很多紧急事"自然退化成"一个优先级队列逐个执行"，无需特殊机制。

### 9.3 打断时 todo 怎么处理（收菜收到一半）

当前 goal 被抢占时：
1. 标记 **SUSPENDED（挂起）**，**不删除、不判完成**。
2. 它的 FSM 状态**保留在 agent 上**（我们架构里 FSM 状态本来就落 `agent`/`currentAction`/NBT）——挂起 = 停止 tick 推进，恢复 = 继续 tick，**不需要重新找耕地/重新找牛**。
3. 塞回 todo 列表（priority 不变或略降，避免一回来又立刻抢）。
4. 抢占 goal（defend）完成后，挂起 goal 按 priority 参与下一轮调度，从中断点继续。

以"收菜收到一半怪物来了"为例：`FarmMaintainSkill` 的当前阶段状态在 agent 上 → defend 抢占 → farming goal 挂起 → defend 打完 → farming goal 恢复，从之前那几块地接着收。

### 9.4 todo 生命周期

`AgentGoal` 增加字段区分"怎么移除"：

| lifecycle | 语义 | 移除时机 |
|---|---|---|
| `ROUTINE` | 周期性日常（职业主业/业余/社交/闲逛） | **完成/执行后不移除**，由 routine 生成器按周期刷新 |
| `ONE_SHOT` | 一次性任务（LLM 派的活、事件目标） | 完成 → 移除 |
| `EXPIRING` | 带时限任务 | 完成或超时（`deadlineTick` 过了）→ 移除 |

外加运行时状态 `suspended`（挂起，见 §9.3），与 lifecycle 正交。

`AgentGoal` 新增字段：`lifecycle`、`deadlineTick`（EXPIRING）、`suspended`、`weight`（调度用）。

---

## 10. 在现有基础上的修改计划（分阶段）

### Phase A —— 数据模型 + 门控（打地基）

| 动作 | 落点 |
|---|---|
| 新增 `CapabilityId`（硬编码枚举，9 个） | `ai/proficiency/CapabilityId.java` |
| 新增 `CapabilityPool`（xp + lastPracticedDay，NBT） | `ai/proficiency/CapabilityPool.java` |
| `VillagerAgentData` 挂 `CapabilityPool` + 初始种子（出生全 0 + 爱好 1，认领职业 +3） | 复用现有 NBT |
| `AgentGoal` 加 `lifecycle`/`deadlineTick`/`suspended`/`weight` | `ai/AgentGoal.java` |
| 新增 `BehaviorCatalog`（行为→requiredCapability/minLevel/baseWeight/耗时/冷却） | `ai/behavior/BehaviorCatalog.java` |
| `VillagerSkill.canHandle` 加能力门控 | 各 skill |

### Phase B —— 升级 + 衰减 + 聊天

| 动作 | 落点 |
|---|---|
| 各 action 完成点 `addXp` | FarmingAction / CraftingAction / CombatAction / TradeSystem / ItemAttractionSystem |
| 聊天 +XP（跟对应职业村民） | `VillagerSocialSystem` + 新增 `SocializeSkill` |
| XP 衰减 `decayTo(today)`（2 天宽限 + 平方曲线） | `VillagerAgentManager` 慢 tick 里按天调用 |
| 默认对话 `default_chat_lines.json` | 资源文件 + 加载器 |
| 总等级联动 | `VillagerProficiency.syncToVanilla` 跟随职业主能力 |

### Phase C —— 规划集成 + 调度

| 动作 | 落点 |
|---|---|
| 新建 `DeterministicPlanGenerator` | 候选集 = 职业池 ∪ 能力解锁池，§6.2 公式加权采样 |
| 替换 `ProfessionGoalGenerator` 静态 if-else | `VillagerSchedulePlanner.createFallbackPlan` 改调确定性生成器 |
| 调度器 `TodoScheduler.pickNextTodo`（EMERGENCY 硬优先 + 两段式软抽样） | `BehaviorExecutor`/`VillagerAgentManager.processGoals` |
| 抢占 + SUSPENDED + 生命周期 | `processGoals` / `BehaviorExecutor` |
| 加 `ENABLE_LLM_OVERLAY` | `ModConfig` |

### Phase D —— 情境调制深化（可选）

- `contextMultiplier` 接 `VillagerNeedsSystem` mood/hunger/fatigue。
- 机会预测接 `WorldStructureIndex`/事件总线打折。

---

## 11. 待你拍板的决策点（大部分已定，剩参数）

1. **RUST_DAYS 具体值**：默认 14 天够"大一点"吗？（已定：2 天宽限 + 平方曲线）
2. **聊天 XP 数值**：亲手做 vs 聊天的经验比例定个默认（如亲手做 10、聊天 1）？
3. **能力上限**：默认 11（到 Master）够吗？是否允许超 11 继续涨 buff（不再涨交易等级）？
4. **认领职业方块的 hook**：接哪个原版事件触发 +3？（村民认领 job site 的钩子，需确认）

---

## 12. 类速查表（本份新增/改动）

| 类 | 状态 | 职责 |
|---|---|---|
| `CapabilityId` | 新增 | 能力硬编码枚举（9 个） |
| `CapabilityPool` | 新增 | 每村民 `Map<能力, XP>` + lastPracticedDay + 衰减(2天宽限+平方曲线) + NBT，等级上限 11 |
| `BehaviorCatalog` | 新增 | 行为 → requiredCapability/minLevel/baseWeight/耗时/冷却 |
| `TodoScheduler` | 新增 | 两段式抽取 + 抢占 + 生命周期 |
| `DeterministicPlanGenerator` | 新增 | 职业池∪能力池 → 加权采样 |
| `SocializeSkill` | 新增 | 无 LLM 聊天行为 + 聊天得 XP |
| `SkillCraftCatalog` | 改名 | 原 `ProfessionCraftCatalog` → 统一目录，配方声明 {输出, requiredCapability, requiredLevel} |
| `UnlockedRecipes`（存 VillagerAgentData） | 新增 | 每村民可用配方表，起空，升级加入/掉级移除 |
| `default_chat_lines.json` | 新增 | 默认聊天台词 |
| `AgentGoal` | 改 | + lifecycle/deadlineTick/suspended/weight |
| `VillagerAgentData` | 改 | 挂 `CapabilityPool` + 初始种子 |
| `VillagerSkill.canHandle` | 改 | + 能力门控 |
| 各 Action | 改 | 完成点 `addXp` + 等级调制 |
| `VillagerSchedulePlanner` | 改 | fallback 改调确定性生成器 |
| `ProfessionGoalGenerator` | 退休 | 被 `DeterministicPlanGenerator` 取代 |
| `ModConfig` | 改 | + `ENABLE_LLM_OVERLAY`、RUST_DAYS、聊天 XP 参数 |
