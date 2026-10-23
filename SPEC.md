# SPEC: 统一目标漏斗下的作息调度 + 更严格的战斗门槛

> 关联文档：`docs/架构反思与精简方案.md`（单一目标漏斗，mode 退化为 routine 生成器）
> 流程：spec → 审批 → 实现。本文件未批准前不写任何实现代码。

## Overview
把"睡觉"做成统一目标漏斗（`AgentGoal`）里的一条 **routine goal**：由**时段事件窗口**按时注入、按时淘汰，夜里它拿**非 emergency 最高 priority（8）**，权重也最高。同时把战斗的**主动攻击门槛收紧**，禁止村民赤手空拳单挑/群殴僵尸。

设计铁律（用户明确要求）：
- **不写任何硬规则去抬高睡眠优先级**。睡眠只是漏斗里一个按时间窗口注入的高 priority routine goal，靠调度器自然胜出。
- **不在 `tickFarming` 里加"夜里 return"**。夜间事件窗口根本不注入 `farm` goal，农业自然停手。
- 延续架构文档：睡眠正常路径 = `rest`/`sleep` goal → `SleepSkill.execute`；`tickRest`（modal）仅作无 goal 时的兜底。
- **非 emergency 不做确定性取最高优先级**：`GoalScheduler` 维持两段软采样，priority 8 只"最可能"胜出，不保证必定压过其它非 emergency goal（用户已确认）。

## Data Models

### 1) 事件窗口（新，建议放 `ai/RoutineScheduler.java`）
```java
/** 一天内某时段要注入的一组 routine goal 模板。dayTime 取 world.getDayTime()%24000。 */
public class RoutineWindow {
    long startTick, endTick;          // 含起点、含终点（0..23999 内经度）
    String id;                        // "day_farm" / "night_rest" 等
    List<RoutineSpec> specs;
}
public class RoutineSpec {
    String goalType;                  // "farm" | "rest" | "sleep"
    int priority;                     // 1..10
    double weight;                    // GoalScheduler 同优先级带内加权
    // 可选参数，如 farmMode="maintain"|"reclaim"
    String param;
}
```
默认窗口（常量表，可作为后续 config 化的种子）：
- `night_rest`：12541–23458（原版睡眠窗），注入 `rest` goal，**priority=8（非 emergency 最高）、weight=4.0**。
- `day_farm`：0–12000（白天主耕作段），注入 `farm` goal，priority=5、weight=2.0（沿用现 `ensureFarmRoutineGoal` 取值）。
- 其余时段暂不注入 routine（留空，村民走 agenda/ambition goal 或 idle）。

### 2) AgentGoal 增量
- 新增可选字段 `routineWindowId`（String，默认 null）+ getter/setter，用于识别某 routine goal 由哪个窗口注入，便于窗口切换时精准淘汰。（**实现时确认 `VillagerAgentData` 是否把 goals 写进 NBT**；若不持久化则无需 NBT 处理，routine goal 每 tick 重生。）

### 3) 战斗门槛配置（新增/改动 `ModConfig` 的 "Combat" 段）
| key | 类型 | 默认 | 含义 |
|-----|------|------|------|
| `combat_require_weapon` | bool | true | 个体主动攻击必须手持真武器（`findBestWeaponDamage > BASE_DAMAGE`）；赤手永不单挑、永不被算入冲锋攻击力 |
| `combat_solo_win_factor` | double | 0.7 | 单挑条件（仅已武装）：`myTtk <= tTtk * factor` |
| `combat_charge_total_power` | double | 5.0 | **集结冲锋门槛（取代原 `RALLY_MIN_MEMBERS` + DPS 比较）**：响应者攻击力总和 ≥ 此值才允许群体冲锋 |
| `combat_charge_min_health` | double | 10.0 | 个体主动攻击门槛：村民自身生命值 ≥ 此值才出手；低于此值即便群体已冲锋也只去汇合点牵制 |

> **取消项**：`combat_rally_min_members`（原 `RALLY_MIN_MEMBERS=2`）与 `combat_rally_safety_margin` 一并移除。集结改为"总攻击力公式"判定，不再数人头、也不直接和威胁 DPS 比。
> **赤手村民**：仍 `respond` 报到并跑向汇合点**牵制（pin）**，但 `combatPower≈0`、不满足 `combat_require_weapon`，故永不主动攻击；其价值是吸引僵尸仇恨、为武装村民创造输出窗口。

### 3b) 攻击力公式（`CombatAction`）
每个村民的作战能力 = 手持武器伤害 + 职业等级加成（赤手 → 0）：
```
isArmed(agent)     = findBestWeaponDamage(agent, null) > BASE_DAMAGE + ε
combatPower(agent) = isArmed ? (weaponDamage + levelBonus) : 0.0
weaponDamage(agent)= findBestWeaponDamage(agent, null)   // 赤手=1.0，铁剑≈6~7，钻剑≈8
levelBonus(agent)  = agent.getLevel() * LEVEL_POWER_BONUS // 默认 0.3/级（实现时确认 getLevel API）
总攻击力 totalPower = Σ combatPower(responder)           // 仅响应喊话者；赤手贡献 0
```

## User Flows / API Contracts

### Flow A — routine 目标生成（每 tick，主循环里对每个村民调用一次）
```
ensureRoutineGoals(villager, agent, world):
  window = activeWindow(world.getDayTime()%24000)   // 时间驱动，不看 scheduledActivity
  wantTypes = window.specs.map(s->s.goalType)
  // 1) 淘汰其它窗口遗留的 routine goal
  for g in agent.getGoals():
      if g.getLifecycle()==ROUTINE && !wantTypes.contains(g.goalType): remove g
  // 2) 注入本窗口缺失的 routine goal
  for s in window.specs:
      if !hasGoalOfType(agent, s.goalType):
          g = new AgentGoal(s.goalType, desc, s.priority)
          g.setLifecycle(ROUTINE); g.setWeight(s.weight); g.setRoutineWindowId(window.id)
          if "farm".equals(s.goalType): g.setFarmMode(resolveFarmMode(world, villager))  // maintain/reclaim
          agent.getGoals().add(g); BrainLog.goalAdded(...)
```
→ 替换现 `ensureFarmRoutineGoal`（`VillagerAgentManager:1055`，依赖 `scheduledActivity=="farming"` 的旧逻辑）。

### Flow B — 夜间睡眠（goal 驱动，无硬规则）
1. 夜间窗口注入 `rest` goal（priority 8）。
2. `processGoals` → `GoalScheduler.pickNextGoal`：EMERGENCY(≥9) 硬优先；否则按既有两段软采样，**priority 8 在非 emergency 中数值最高、权重最高，自然胜出**（farm 等低 priority routine 已被窗口淘汰）。
3. `BehaviorExecutor.findHandler` → `SleepSkill.canHandle`("rest"/"sleep") → `SleepSkill.execute`（goal 路径）→ 走到床 → `trySleep`。
4. 与 Modal 兜底共存：当 running goal 非 farm 时 `setScheduledActivity(null)`（现 `:1450`），modal `tickRest` 不再触发，避免双路睡眠。

### Flow C — 更严格的主动攻击（反应式，照常非 LLM 运行）
调用点 `performCombatActions`（`:252`）不变；内部门槛收紧：

- **单挑（直接发现威胁）**：`CombatAction.wouldWinSolo` 在 `combat_require_weapon` 开启且未武装时直接 `false`；已武装且 `myTtk <= tTtk * solo_win_factor` 才单挑，否则 `rallyOrFlee`。
- **集结冲锋（群体）**：`VillageSignalBoard.shouldCharge` 改为按**总攻击力**判定——
  `totalPower(响应者) = Σ combatPower(r) >= combat_charge_total_power` 即允许冲锋（不再数人头、不再比威胁 DPS）。移除 `RALLY_MIN_MEMBERS`。
- **赤手村民只牵制、不攻击**：在喊话响应分支（`:284-300`）与 `rallyOrFlee`→冲锋处，个体是否 `startCombatAction` 由
  `isArmed(agent) && villager.getHealth() >= combat_charge_min_health` 把关；不满足的村民改为 `moveTo(rally.lastPos)` 跑去汇合点**牵制**，并持续 `respond` 维持警报。
- 赤手村民仍正常 `respond` 报到（用于汇合点导航），但 `combatPower=0`，既不推高总攻击力、也永不主动攻击——消除"赤手群殴僵尸"。

### Flow D — 与战斗/ siege 的交互（不回归）
- 村民空闲时被僵尸贴脸：`performCombatActions` 反应式介入（flee/charge），**暂停** running goal（含可能的 rest goal）；威胁消失后 `resumeSuspendedGoals` 恢复睡眠。生存 > 睡眠，符合直觉。
- 村民夜里还在犁地（farmMove 进行中）窗口切到 night：`rest`(8) 经 `shouldPreempt`（diff≥3）抢占 farm(5)，farm ROUTINE goal 被窗口淘汰、不重生，次日再注入。

## File Structure
| 文件 | 动作 | 说明 |
|------|------|------|
| `ai/RoutineScheduler.java` | **新建** | `RoutineWindow`/`RoutineSpec` 定义 + `ensureRoutineGoals(...)` + 默认窗口常量表 |
| `ai/AgentGoal.java` | 修改 | 加 `routineWindowId` 字段 + getter/setter |
| `ai/VillagerAgentManager.java` | 修改 | 主循环改调 `ensureRoutineGoals`；删除 `ensureFarmRoutineGoal`；放宽 `:1450` 的 farm-only 不清 scheduledActivity 特例（改为通用 routine 自行重生） |
| `ai/CombatAction.java` | 修改 | 新增 `isArmed()` / `combatPower()`（公式见 §3b）；收紧 `wouldWinSolo`（未武装直接 false）；`villagerDps` 仅作参考，冲锋不再依赖它 |
| `ai/VillageSignalBoard.java` | 修改 | 移除 `RALLY_MIN_MEMBERS`；`shouldCharge` 改用 `Σ combatPower(responder) >= combat_charge_total_power`；`groupDps` 字段可保留但不再用于冲锋判定 |
| `config/ModConfig.java` | 修改 | Combat 段换成 4 个开关（`combat_require_weapon`/`combat_solo_win_factor`/`combat_charge_total_power`/`combat_charge_min_health`）；删 `combat_rally_min_members`/`combat_rally_safety_margin`；`brain_log_enabled` 写死默认（见下"配置漂移"） |
| `ai/BrainLog.java` | 修改 | `isEnabled()` 加 null 防御（`ModConfig.BRAIN_LOG_ENABLED!=null && .get()`） |
| `ai/behavior/SleepSkill.java` | 不动 | goal 路径 `execute` 已就绪；`tickRest` 退为兜底 |

## Edge Cases & Error Handling
- **夜里找不到床**：`SleepSkill` 返回 BLOCKED → 达 `BLOCKED_MAX_STREAK` 后 goal 标记完成 → 村民醒着 idle；下 tick ROUTINE 重生 `rest` goal 重试（ROUTINE 不被消费）。
- **白天窗口**：`rest` goal 被淘汰，`farm` goal 注入；无回归。
- **窗口边界抖动**：同一 tick 只属一个窗口；切换时先淘汰旧 routine 再注入新 routine，不会残留两套。
- **chunk 卸载/重启**：routine goal 每 tick 重生，无需持久化；若有 NBT 持久化则在反序列化时丢弃 `routineWindowId` 未知的 goal。
- **siege 中村民在床里**：仍按现逻辑不主动醒（currentAction≠null 跳过战斗扫描）；如未来要"床边有威胁就醒"，列为后续增强，不在本 SPEC。
- **配置漂移（P2）**：`brain_log_enabled` 在 toml 未写入时取值可能 null→true 歧义；在 `ModConfig` 写死默认并让 `BrainLog` 防御 null。

## Out of Scope
- LLM agenda / ambition goal 的夜间抑制（本 SPEC 只保证休息 priority 数值最高、权重最高，靠软采样自然胜出；非 emergency 不做确定性抢占，已确认）。
- eat / socialize / trade 等其它 routine 类型（仅实现 farm + rest）。
- 战斗从睡眠中主动唤醒的增强。
- goal 跨重启持久化（routine 不依赖）。

## Open Questions
1. `combat_charge_total_power` 默认 5.0 是否合适？（铁剑≈6~7 可单人冲锋；石剑≈5 需 2 人；木剑≈4 需 2 人）
2. `combat_charge_min_health` 默认 10.0（村民满血 20）是否合适？太低会送头，太高则残血村民完全不参战。
3. 攻击力公式是否纳入**职业加成**（`professionBonus`）？当前仅 `weaponDamage + levelBonus`。
4. 赤手村民跑去牵制时，是否要限制其与僵尸的**最小安全距离**（避免被摸死）？当前 step-4 直接 `moveTo(lastPos)` 可能贴脸——可考虑在 `FLEE_HOLD_DIST` 外侧才靠近、内侧继续 flee。
5. 非 emergency 软采样下，夜里若同时有高 weight 的非 routine goal（如 LLM 临时 ambition），睡眠可能被稀释——是否接受？（已确认不做确定性抢占，故此为已知权衡）
