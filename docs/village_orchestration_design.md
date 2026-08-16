# 多智能体编排实现方案（VillageBlackboard + VillageEventBus）

> 对应《技术力提升计划书》模块三 +《游戏AI技能规划》技能 #5（群体 AI / 角色分配 / 警报传播）。
> 目标：把村庄从「各自为战的村民」升级为「共享黑板 + 事件总线」的多智能体系统，做出**可控涌现**（威胁 → 分工）——这是对标 JD「NPC→NPC 网状交互 / 群体智能 / 可控涌现」的最强演示。
> 状态：**部分实现**。核心「威胁 → 号召 → 群殴」已落地（2026-08-16：`CombatAction.wouldWinSolo` TTK 判定 + `VillageSignalBoard` 号召信号板 + `FLEE` 逃跑动作）；其余事件（NIGHT_FALLING / RESOURCE_SURPLUS / VILLAGER_MISSING 等）与完整 `VillageBlackboard`/`VillageEventBus` 仍是设计。

---

## 1. 目标与动机

- **现状**：村民各自独立；唯一的多体交互是 `Gossip Propagation`（30% 概率改第三方好感）+ 刚做的 `VillagerTradeSystem`（村民间交易）。
- **缺口**：没有「全村共享状态」与「事件广播」，做不出「一个村民发现威胁 → 广播 → 其他人分工」的涌现行为。
- **目标**：引入**共享黑板**（全村状态）+ **事件总线**（发布/订阅），让局部事件触发全局协调，且全程可控（角色分配由规则+职业决定，LLM 只做高层目标）。

---

## 2. 可复用的现有底座（不要重写）

| 现有组件 | 如何复用 |
|---|---|
| `CombatAction` / `tickCombat` | 已能扫描威胁、追击、攻击——威胁事件的**源头**就在这里 |
| `VillagerAgentManager.processGoals` | 统一派发目标（craft/gather/move/converse/trade/socialize/goto）——分工后只需入队对应 goal |
| `WorldStructureIndex.queryNear` / `goto` 目标 | 建筑索引可直接作「避难所」（`TODO: goto house` 已实现） |
| `resolveVillagerPartner` | 已能按职业/实体找村民——角色分配时找「战斗职业」等 |
| `Gossip Propagation` | 从「只传好感」扩展为「也传意图」（谁在做什么） |
| `StructureIndexSavedData` / `VillagerAgentSavedData` | `WorldSavedData` 持久化模式，黑板照搬 |
| `DebugOverlay` / `DebugSync` | 已就绪的调试可视化通道，黑板状态直接画上去 |

---

## 3. 整体架构

```
CombatAction 发现威胁 ──publish──►  VillageEventBus
                                        │
                                        ▼
                                 VillageBlackboard（WorldSavedData，全村共享）
                                   threatLevel / threatPos / roles / foodStock
                                        │
                                        ▼
                              订阅者反应（已实现：TTK 判定 + 号召群殴）
                    ┌──────────────────────┬────────────────────────┐
                    ▼                      ▼                        ▼
                ALARMER（敲钟）          单挑：wouldWinSolo(TTK)      号召群殴：VillageSignalBoard
          （离钟最近的村民，Raid 专用）  （能赢→攻击；不能赢→跑+喊） （记录响应人数，凑够一起冲）
                    │                      │                        │
                    ▼                      ▼                        ▼
             敲钟→32 格内村民躲床        能赢→startCombatAction      respond 登记→FLEE 逃跑→shouldCharge
             +48 格内袭击者 Glowing      不能赢→FLEE 逃跑+喊人        20 格内村民赶来→群殴
```

- **分工边界**：战/躲由**规则代码**决定（`wouldWinSolo` 的 TTK 比较 + `VillageSignalBoard` 号召信号板），**不烧 LLM token**；敲钟报警复用原版「谁离钟近谁敲钟」的确定性规则（Raid 专用，独立机制）。这样涌现**可控**——不会「全村冲上去送死」或「全跑没人守」。

---

## 4. `VillageBlackboard`（共享黑板，WorldSavedData）

```java
package ...ai.village;

public class VillageBlackboard extends WorldSavedData {
    private static final String NAME = "villageragent_village_blackboard";

    // 全村共享状态（写 NBT 持久化，跨重启保留）
    private int    threatLevel;              // 0=和平 1=警戒 2=威胁（随时间衰减）
    private BlockPos threatPos;              // 最近威胁位置（可能过期）
    private long   threatTick;               // 威胁发生游戏刻（衰减用）
    private int    foodStock;                // 全村食物储备估计（粗粒度）
    private boolean isNight;
    private Map<UUID, String> roles;         // villagerId -> "defender"/"alarmer"/"worker"/"refugee"
    private Map<UUID, BlockPos> lastKnown;   // 村民最后已知位置（记忆衰减，供 VILLAGER_MISSING 用）
    private List<String> sharedGoals;        // 共享目标文本，如 "defend village" / "gather wheat"

    // 单例（每维度一份，仿 WorldStructureIndex.instance(world)）
    public static VillageBlackboard instance(ServerWorld world) { ... }

    // 读写
    public int  getThreatLevel() { ... }
    public void setThreat(int level, BlockPos pos, long tick) { ...; setDirty(); }
    public String assignRole(UUID id, String role) { ... }   // 角色自分配，见 §6
    public String getRole(UUID id) { ... }

    // NBT 序列化（写 threatLevel/threatPos/threatTick/foodStock/roles/lastKnown）
    public void load(CompoundNBT nbt) { ... }
    public CompoundNBT save(CompoundNBT nbt) { ... }
}
```

- 持久化沿用 `StructureIndexSavedData` 的套路：`WorldEvent.Load`/`Save` 里自动 load/save，重启不丢黑板。

---

## 5. `VillageEventBus`（事件总线）

```java
package ...ai.village;

public class VillageEvent {
    public enum Type { THREAT_DETECTED, RESOURCE_SURPLUS, NIGHT_FALLING, VILLAGER_MISSING, BUILDING_DESTROYED }
    public final Type type;
    public final BlockPos pos;
    public final UUID sourceVillagerId;   // 谁发布（可能 null）
    public final String payload;          // 附加信息（威胁实体 id / 资源 item id …）
    public final long tick;
}

public final class VillageEventBus {
    // 发布入口：任何系统（CombatAction / 黑板 / tick）都可调
    public static void publish(ServerWorld world, VillageEvent e) {
        VillageBlackboard bb = VillageBlackboard.instance(world);
        switch (e.type) {
            case THREAT_DETECTED:   bb.setThreat(2, e.pos, e.tick); dispatchThreat(world, e); break;
            case NIGHT_FALLING:     bb.isNight = true;  dispatchNight(world, e); break;
            case RESOURCE_SURPLUS:  dispatchResource(world, e); break;
            case VILLAGER_MISSING:  dispatchMissing(world, e); break;
            default: break;
        }
    }

    // 威胁分工（核心涌现）：见 §5.1
    private static void dispatchThreat(ServerWorld world, VillageEvent e) { ... }
    // 入夜：非夜行村民回家（goto house）
    private static void dispatchNight(ServerWorld world, VillageEvent e) { ... }
    // 资源盈余：广播招揽「采集」目标
    private static void dispatchResource(ServerWorld world, VillageEvent e) { ... }
}
```

### 5.1 威胁分工（THREAT_DETECTED）—— 已实现（2026-08-16）

> 两次修正：① 原版村民**没有战斗职业**（armorer/toolsmith/weaponsmith 都是工匠，不是战士），不按职业分工；② 战/躲不再由 LLM 读装备决定，改为**规则代码的 TTK 判定 + 号召信号板群殴**（`VillageSignalBoard`），零 token 成本。

```
单个村民（CombatAction.wouldWinSolo，规则代码）:
  TTK 判定：打死对方耗时(威胁血量 / 我的DPS) < 对方打死我耗时(我的血量 / 威胁DPS)
    成立   → 直接 startCombatAction 攻击
    不成立 → 号召：respond 登记 + FLEE 逃跑 + 喊人

号召信号板（VillageSignalBoard，村庄级信息管道，非两两通信）:
  RallyCall = { threatUuid, lastPos, members:{村民UUID→DPS}, groupDps, charging }
  respond():      去重登记响应村民，累加 groupDps
  shouldCharge(): memberCount>=2 且 groupDps>=threatDps → 集体冲锋
  nearestRally(): 20 格内能"听到"号召的村民赶来帮忙
  pruneExpired(): 每 tick 清理 15s 无人响应的过期号召

群殴成型（人多力量大的涌现）:
  1 个村民打不过 → 跑 + 喊
  周围村民听到号召(20 格) → 跑来登记响应
  响应人数够(≥2 且 合计DPS≥威胁DPS) → 所有在场村民一起冲上去
```

- **为什么可控**：分工全程规则代码（TTK + 信号板），不用 LLM，所以「谁冲谁撤」可预测、可复现、零 token——面试演示时能稳定展示「一个发现 → 逃跑喊人 → 三五成群围殴」。
- **接入点**：`VillagerAgentManager.performCombatActions` 空闲分支——发现威胁先 `wouldWinSolo`，打不过走 `rallyOrFlee`（`VillageSignalBoard.respond` + `startFleeAction`）；并新增「无直接威胁但听到号召」分支赶来帮忙。
- **敲钟实现（Raid 专用，独立于号召群殴）**：原版村民敲钟走 brain `RingBellTask`，但本 mod 已 mixin 抑制原版 AI → 需模组侧「让 alarmer 走到钟旁 + 触发钟响」。钟响可直接调 `BellBlockEntity`（或 `world.blockEvent(bellPos, …)`）模拟原版响铃，复用其「32 格躲床 + 48 格 Glowing」效果，不必重造。敲钟是「躲床 + 标记袭击者」，号召是「聚众反击」，二者互补、不冲突。

### 5.2 其它事件（Phase 2，按需扩展）

| 事件 | 触发源 | 反应 |
|---|---|---|
| `NIGHT_FALLING` | 天黑 tick（复用 `VillagerSchedulePlanner` 的时间判断） | 非夜行村民 `goto house` 回家 |
| `RESOURCE_SURPLUS` | 某村民采集到大量作物/矿物 | 黑板记 `foodStock`/资源，广播「去 X 采集」给闲置村民 |
| `VILLAGER_MISSING` | 某村民长时间未更新（结合 `lastKnown`） | 邻近村民 `goto` 其最后已知位置搜寻 |
| `BUILDING_DESTROYED` | `WorldStructureIndex.onBedRemoved`/`markDirty` 删建筑时 | 相关村民记 memory + 触发「重建」意图 |

---

## 6. 角色分配（最终模型：TTK 判定 + 号召信号板，已实现）

- 不再按职业、也不再让 LLM 逐村民读装备判「战/躲」。现在的分工是：
  - **单挑判定（`CombatAction.wouldWinSolo`）**：TTK 比较，规则代码——能赢才上，打不过就跑+喊。
  - **群殴（`VillageSignalBoard`）**：打不过 → 逃跑 + 喊人 → 响应人数够（≥2 且合计 DPS ≥ 威胁 DPS）→ 一起冲。`RallyCall.members` 以村民 UUID 去重，天然避免重复响应/重复计数。
- **敲钟报警（`alarmer`）仍是独立机制**（复用原版「谁离钟近谁敲」，Raid 专用，尚未实现）——它与「号召群殴」是两回事：敲钟是「躲床 + 标记袭击者」，号召是「聚众反击」，二者互补。
- **「保护他人」自然涌现**：幼儿/残血村民打不过 → 自己发号召 → 周围成年村民赶来围殴。无需单独写「保护」规则，号召机制天然覆盖。

---

## 7. 分阶段实现（按风险从低到高）

| 阶段 | 内容 | 产出 |
|---|---|---|
| **Phase 0** | `VillageBlackboard`（WorldSavedData + instance + NBT）+ `VillageEventBus.publish` 骨架 | 黑板/事件总线就位，可存可读 |
| **Phase 1** | `THREAT_DETECTED` 分工：CombatAction 发布 → DEFEND/FLEE/ALARMER 入队 goal（复用 processGoals + goto house） | 「袭击→分工」可演示，**可控涌现核心** |
| **Phase 2** | `NIGHT_FALLING`（回家）+ `RESOURCE_SURPLUS`（招揽采集）+ `VILLAGER_MISSING`（搜寻） | 村庄日常协调 |
| **Phase 3** | 调试 overlay 显示黑板状态（threatLevel / 角色 / threatPos）+ Gossip 扩展为「传意图」 | 可视化 + 网状交互 |

---

## 8. 风险与注意事项

- **雪崩防护**：威胁事件高频触发会全村乱跑——`VillageEventBus` 加**节流**（同一威胁 N tick 内不重复广播）+ 黑板 `threatLevel` 衰减（威胁消失后自动降级，村民恢复正常活动）。
- **CombatAction 无武器门槛（已解决，2026-08-16）**：曾有的「没武器也拳头冲上去打」行为已改为 **TTK 判定**（`wouldWinSolo`）——打不过就 `FLEE` 逃跑 + 喊人，不再送死。空手村民面对一只满血僵尸会跑，面对残血怪或「号召凑够人」才会上。
- **号召雪崩防护**：`pruneExpired` 每 tick 清理 15s 无人响应的过期号召；`FLEE_GIVE_UP_SCANS` 让一直没人来帮的村民 15s 后放弃逃跑回归正常，避免「全村永续逃跑」。
- **与 goal-driven 不打架**：分工入队的 goal 走 `processGoals`，与 `executeXxxGoal` 同一套状态机；分工 goal 应设高 priority，但**不打断 combat/farming**（沿用 `processGoals` 里对 `ATTACK/HARVEST/…` 的互斥判断）。
- **角色漂移**：黑板角色在威胁解除后要**释放**（回到 worker），否则村民永远「备战」不干活。
- **持久化**：黑板用 `WorldSavedData`，但 `threatLevel`/`isNight` 这类**易变状态**可选择性不持久化（重启后重新感知），只持久化 `foodStock`/`sharedGoals` 等慢变量。
- **LLM 成本**：分工是规则代码，**不额外消耗 LLM token**；只有「威胁下说一句台词」这类可选 flavor 才走 LLM。
