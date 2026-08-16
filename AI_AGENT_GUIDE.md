# VillagerAgent Mod — AI Agent Reference Guide

> **Purpose:** Fast on-boarding for an AI assistant picking up this project. Read this before touching any code.

> ⚠️ **本文档主体快照于 2026-04-23，部分章节已过时**。截至 2026-08-16 的架构演进（分层感知 Far/Mid/Near、决策 Harness、职业合成、世界交互建造、长期任务 Agenda、converse 目标等）**未回写进本文**，请同时参考 `docs/` 下的设计/实现文档与 `docs/项目现状与待办清单.md`。下方 §6/§8 已补记 2026-08-16 增量；其余章节以代码为准。

---

## 1. Goal & Environment
Transform vanilla Minecraft 1.16.5 villagers into autonomous "Generative Agents" (Stanford-AI-Town style).
- **Platform:** Minecraft 1.16.5 · Forge · Java 8+
- **LLM Backend:** Configurable via `ModConfig` — Gemini (default), OpenAI, Anthropic, Ollama.
- **Day cycle:** 24 000 ticks per day. All time-based logic uses `world.getDayTime() % 24000`.
- **Thread safety:** All LLM calls are `CompletableFuture` (off server thread). Results are applied back with `world.getServer().execute(...)` or via `volatile` guards.

---

## 2. Package Layout
All source lives under `com.github.AaronAA0721.villageragent`.

| Sub-package | Purpose |
|---|---|
| `ai/` | All agent logic — see §3 |
| `config/` | `ModConfig` — Forge config values (API key, model, feature flags) |
| `events/` | `VillagerEventHandler` — wires tick methods into Forge events |
| `mixin/` | `VillagerEntityMixin` — suppresses vanilla AI when agent is active |
| `network/` | `ChatMessagePacket` — sends villager dialogue to clients |

---

## 3. Key Files in `ai/`

### Data & State
| File | Role |
|---|---|
| `VillagerAgentData` | **Central state bag** per villager. Holds name, profession, personality, memories (List\<String\>), relationships (Map\<UUID→int\>), inventory, goals, daily schedule, needs, chunk memory, mood (to be added). NBT-persisted via `VillagerAgentSavedData`. |
| `VillagerAgentSavedData` | WorldSavedData that serialises/deserialises all agent data across saves. |
| `AgentInventory` | Simple slot-based inventory. Items added/removed by farming/crafting systems. |
| `DailySchedule` | Holds 4 `ScheduledTask` entries (morning/afternoon/evening/night). `getCurrentTask(dayTimeTick)` returns the active task. |

### Orchestration
| File | Role |
|---|---|
| `VillagerAgentManager` | **Main dispatcher.** `tickAgents()` (slow, every N ticks), `tickFarming()` (fast, every 3 ticks), `tickCombat()` (fast, every 2 ticks), `tickSocial()` (gated 200 ticks), `tickThoughts()` (gated 40 ticks per villager). |
| `VillagerEventHandler` | Subscribes to Forge `WorldTickEvent`; calls all `tickXxx()` methods and item pickup. |

### Perception
| File | Role |
|---|---|
| `VillagerVisionSystem` | Builds an NL environment summary (time, weather, biome, trees, water, caves, exact position, 4 in-sight chunks, all known chunks). Called every `ENV_REFRESH_INTERVAL = 1200` ticks and cached in `agent.environmentSummary`. |

### Behaviour Systems
| File | Role |
|---|---|
| `VillagerSchedulePlanner` | Dawn planning (ticks 0–2000): async LLM generates 4-slot `DailySchedule`. Evening reflection (ticks 13000–15000): 1 reflective memory sentence. Falls back to profession defaults if LLM fails. |
| `VillagerActivitySystem` | Translates `scheduledActivity` into movement: `exploring` (random 20–50 block target), `resting` (walk to bed/HOME memory), `crafting` (3-phase: walk→ACTING 200 ticks→finalize). |
| `VillagerSocialSystem` | Every 200 ticks scans idle villager pairs ≤5 blocks apart. LLM generates 4-line dialogue. Tone based on relationship score (5 tiers: close friends → hostile). Relationship gain: +2/+4/+1 by tier. |
| `VillagerNeedsSystem` | Hunger (0–100, decays 5pt/1200 ticks, auto-eat below 40). Fatigue (0–100, rises at night 13000–23000, recovers by day). Both exposed as NL description via `buildNeedsDescription()`. |
| `FarmingAction` | Mature-crop harvesting + seed planting. Includes sorted candidate lists, stuck-timeout, WAITING phase for seed pickup before replanting. |
| `CombatAction` | Scans for hostile mobs, equips best weapon, walks toward target, attacks on cooldown. Disengages if target flees or dies. |
| `CraftingAction` | Validates recipe ingredients, calls `recipe.craft(inventory)`. `RecipeRegistry` maps profession→available recipes. |

### Memory Management
- `addMemory(String)` in `VillagerAgentData`: when `memories.size() >= 40`, triggers async LLM to compress oldest 20 into a summary (guarded by `volatile boolean summarizingMemories`). Summary prepended as `[Summary of earlier memories] ...`.
- Memories capped at ~40 entries in practice.

### LLM Integration
- `LLMService.queryLLM(systemPrompt, userPrompt)` → `CompletableFuture<String>`. Thread pool of 4.
- Supported: `gemini`, `openai`, `anthropic`, `ollama`. Configured via `ModConfig.LLM_API_TYPE`.
- All call sites handle `.exceptionally(...)` with graceful fallback.

### Spontaneous Thoughts
- `VillagerAgentManager.tickThoughts()`: every 40 ticks, checks each villager for nearby players (≤20 blocks). Per-villager cooldown `THOUGHT_COOLDOWN_TICKS = 6000` (~5 MC minutes). Generates 1 first-person inner thought, broadcasts in italic grey `§7[Name thinks] §o...`, stores as memory.

---

## 4. Key Constants (quick reference)

| Constant | Value | Location |
|---|---|---|
| Day length | 24 000 ticks | everywhere |
| Night start | 13 000 | `VillagerNeedsSystem`, `VillagerSocialSystem` |
| Memory threshold | 40 entries → compress 20 | `VillagerAgentData` |
| Max known chunks | 512 | `VillagerAgentData` |
| Env refresh | 1 200 ticks | `VillagerAgentManager` |
| Thought cooldown | 6 000 ticks | `VillagerAgentManager` |
| Social cooldown | 6 000 ticks | `VillagerSocialSystem` |
| Craft work duration | 200 ticks | `VillagerActivitySystem` |
| Farming scan chance | 0.5%/tick | `VillagerAgentManager` |

---

## 5. Adding a New Feature — Checklist
1. New behaviour → add a `tickXxx(World)` static method in `VillagerAgentManager`.
2. Wire it in `VillagerEventHandler.onWorldTick()`.
3. Gate it with a feature flag in `ModConfig` if it should be optional.
4. Any new per-villager state → add field + getter/setter in `VillagerAgentData`, persist in `writeToNBT`/`readFromNBT`.
5. Run `gradlew compileJava` after every change.

---

## 6. Implemented Systems (as of 2026-04-23)
- ✅ LLM-generated name & personality at spawn
- ✅ Memory system with async summarisation (threshold 40)
- ✅ Daily schedule (LLM planned at dawn, evening reflection)
- ✅ Needs system (hunger + fatigue)
- ✅ Farming (harvest + plant, stuck timeout, seed-wait replant)
- ✅ Combat (scan, chase, attack, disengage)
- ✅ Crafting work phase (walk → 200-tick work → item production)
- ✅ Social conversations (relationship-tone driven, 5 tiers)
- ✅ Spontaneous thought bubbles (near players, 5-min cooldown)
- ✅ Spatial memory (LRU chunk set, 4 in-sight chunks, full map in LLM prompt)
- ✅ Environment sensing (exact time/24000, biome ID+desc, position, weather)
- ✅ Mood System (HAPPY/CONTENT/NEUTRAL/ANXIOUS/DISTRESSED — derived from needs+relations, injected into all 4 LLM call sites)
- ✅ Player Greeting (8-block range, 2400-tick cooldown per player, gold chat text, async LLM)
- ✅ Gossip Propagation (30% chance per social conversation, nudges listener's opinion ±2, memory logged for both)

### New Systems Detail (as of 2026-04-23)

**Mood System** (`VillagerAgentData.Mood` enum, `VillagerNeedsSystem.deriveMood/buildNeedsDescription`):
- 5 tiers: HAPPY, CONTENT, NEUTRAL, ANXIOUS, DISTRESSED
- Score based on hunger (<20→-2, <50→-1), fatigue (>80→-2, >50→-1), avg relationship (>40→+2, >10→+1, <-10→-1, <-40→-2)
- Re-derived every needs tick, stored as `agent.mood`
- Injected into: chat response system prompt, schedule planner user prompt, social conversation user prompt, thought bubble user prompt
- Per-villager last-greeted map: `Map<UUID, Long> lastGreetedPlayer` in `VillagerAgentData`

**Player Greeting** (`VillagerAgentManager.tickGreeting`):
- Scan every 20 ticks; per-villager + per-player cooldown: 2400 ticks (~2 MC min)
- Triggers when `player.distanceToSqr(villager) <= 64.0` (8 blocks)
- LLM prompt includes name, profession, personality, mood, activity, environment
- Broadcasts in gold `§eName: §fgreeting text` to all players within 20 blocks
- Stores "Greeted player X: ..." as a memory

**Gossip Propagation** (`VillagerSocialSystem.propagateGossip`):
- Called at end of `broadcastDialogue` with 30% probability
- Gossiper picks a third-party villager with |relation| > 10
- Listener's relation to that third party nudged ±2 (direction matches gossiper's opinion)
- Both parties receive a memory entry: gossiper records "Told X about Y", listener records "A spoke well/poorly of B (±2)"

## 7. Systems Added After 2026-04-23

> 快照后新增的子系统，仅列要点；实现细节见 `docs/` 下对应文档。

- **分层感知（Far/Mid/Near）** — `ai/memory/ChunkMemory`（按 `BlockCategory`/`ChunkTag`/`ChunkFeature` 打标签，不存 2048 条方块）；`ai/vision/BuildingLocator`（床→房间：距离场+同步双类别分水岭）+ `ai/world/WorldStructureIndex`（事件驱动、队列式、`StructureIndexSavedData` 跨重启持久化）；`ai/vision/FrustumCuller`+`DetailedViewRecorder`（视锥实时精扫）。
- **决策 Harness**（`ai/harness/`）— `LLMGuard`（超时+熔断+重试）、`DecisionJournal`（JSONL 轨迹）、`DecisionSchema`/`ActionValidator`（schema+接地+安全校验）、`HarnessDecisionPlanner`（`decideDailyGoal`，受 `harness_drive_daily_goal` 门控，默认关）。失败哨兵 `LLMService.FAILURE_PREFIX` 防把错误串当台词广播。
- **职业合成** — `ProfessionCraftCatalog` + `NativeRecipeResolver`（原版 `RecipeManager` 反查材料），`executeCraftGoal` 走 JOB_SITE、执行刻再校验、大师级随机附魔。
- **世界交互/建造** — `BlockInteractionAction`（1 格内放置/破坏）、`BuildOrderPlanner`（壳层 BFS 顺序+校验）、`StructureBuilder`（LLM 结构+回修）、`BuildJob`（NBT 续建）；`/va build place/break/structure`。
- **长期任务/契约** — `LongTermAgenda`（`DebtAgenda`/`AcquireItemAgenda`/`DealAgenda`/`GenericAgenda`）+ `AgendaParser`（`REMEMBER:` 指令）；`playerReputation` 好感度；销账（`settleDebts`）+ 反思派生 + `converse` 目标催账。
- **目标派发** — `processGoals` 按 `goalType`（craft/gather/move/converse/trade/socialize）switch 到 `executeXxxGoal`，取代 `scheduledActivity` 字符串开关。

## 8. Pending / Next Steps

> **2026-08-16 增补**：本节原 4 项中「Trade Negotiation」已部分落地（好感度 `playerReputation` 影响销账 ±10 / 催账超限 -5，见 `TradeRequestPacket.settleDebts`）；「Home/Bed Assignment」仍待做（消费断层）。新增待办以 `docs/项目现状与待办清单.md` 为准。

- ⬜ **Weather Reactions** — seek shelter in rain, comment on storms in prompts
- ⬜ **Death Memory** — nearby villager death adds distress memory to witnesses (hook into `LivingDeathEvent`)
- ⬜ **Home/Bed Assignment** — 把 `WorldStructureIndex` 的床/建筑接回村民 `MemoryModuleType.HOME`，实现真正"回家睡觉"导航（当前 `VillagerActivitySystem.handleResting` 仍走原版 HOME POI，检测能力已具备但消费侧未接上）
- ⬜ **G3 索引失效** — `WorldStructureIndex.markDirty` 为死代码，活塞/爆炸/液体改动不触发重扫
- ⬜ **trade/socialize 目标空壳** — `executeTradeGoal`/`executeSocializeGoal` 仅走到最近玩家+写记忆，无真实经济/关系变化
- ⬜ **通用采集** — 挖矿/伐木/钓鱼等职业化采集未实现（仅 farmer 收割 + `ItemAttractionSystem` 捡掉落物）
- ⬜ **Stage 2 建筑精细解析（StructureParser）** — 房间/门窗/柱的结构化解析未实现，`BuildingRecord` 无内部模型

