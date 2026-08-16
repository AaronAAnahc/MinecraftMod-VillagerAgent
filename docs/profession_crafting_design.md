# 村民职业工作流合成系统设计文档（Profession-Based Crafting）

> 状态：草案 / 待确认（2026-08-16）
> 目标：把当前手写死配方（`RecipeRegistry`，仅 5 条）替换为「按职业 + 原版机制」驱动的动态合成系统，让村民像原版村民「上班」一样在各自职业工作站生产，并由 LLM 在对话中生成可执行 TODO。

---

## 1. 目标与动机

| 现状痛点 | 新方案 |
|---|---|
| `RecipeRegistry` 手写 5 条配方，且按职业分组死板 | 可合成目录从**原版 `VillagerTrades` 会售卖的物品**提炼，按「职业 + 等级」组织 |
| `finalizeCrafting()` 用 `available.get(0)` 乱选第一个可合成配方 | 目标 item 来自 **LLM/玩家** 明确意图（TODO），或村民自主日程 |
| 合成作用在 `AgentInventory` | 直接在该背包上校验/消费/产出（**它本就是这个 mod 的「真实工作背包」**，见 §9）；材料读**原版 `RecipeManager`** |
| 村民「crafting」只是走到 `JOB_SITE` 站一下 | 在职业工作站**真实消费材料 → 产出物品**（含大师级随机附魔） |
| 玩家聊天只产生对话文本 | LLM 在对话中可生成 **TODO（craft / move / trade / socialize … 任意可执行行动）**，村民入队并由统一派发器执行 |

核心原则（来自用户）：
1. **不是通用合成台**：村民在**自己职业的工作站**上班式生产（铁匠在锻造台/砂轮、盔甲匠在高炉等）。
2. **catalog = 职业会卖的可合成物品**，排除绿宝石相关（如工具匠→工具，盔甲匠→盔甲）。
3. **材料读取仍参照原版合成表**（从 `RecipeManager` 反查 ingredients）。
4. **附魔物品按原版货物规则**：大师级村民可生成随机附魔盔甲/工具，参照 `VillagerTrades.EnchantedItemForEmeraldsTrade` + `EnchantmentHelper.addRandomEnchantment`。
5. **执行时再校验材料**：任务只记录意图，真正合成前那一刻才 `recipe.matches()`，中途丢材料 → 合成失败（不提前扣材料）。

---

## 2. 整体数据流

```
玩家对话 / 自主日程
      │
      ▼
 LLM（系统提示含：职业、等级、当前背包、可合成目录、TODO 协议）
      │  回复可能为自然语言 + 结构化意图标记
      ▼
 意图解析（正则 / JSON / function-calling 兜底）
      │  识别到 TODO: craft <item> x<qty>
      ▼
 入队 AgentGoal(type=CRAFT, targetItem, qty)   ← 复用现有 agent.getGoals()
      │
      ▼
 VillagerActivitySystem / 新 ticker 消费该 Goal
      │
      ├─ 校验：item 是否在「职业 catalog + 当前等级」内（代码判定，不信任 LLM）
      ├─ 取材料：RecipeManager 反查 recipe → getIngredients()
      ├─ 导航：走到 getBrain().getMemory(JOB_SITE)（职业工作站）
      └─ 执行阶段末尾「再校验」一次 materials
            │ 通过 → 扣材料 + 产出 output（+ 大师级随机附魔）→ 入真实背包
            └ 失败 → 任务失败 + 记 memory + 回话告知
```

---

## 3. 模块设计

### 3.1 ProfessionCraftCatalog（职业 → 可合成目录）

- 数据结构：
  ```java
  Map<String /*profession*/, Map<Integer /*level 1..5*/, List<CraftableEntry>>> CATALOG;
  class CraftableEntry {
      ResourceLocation item;     // 可合成物品，如 minecraft:iron_pickaxe
      boolean enchantable;       // 是否可能在大师级附魔
      boolean masterOnly;        // 是否仅大师级可合成
  }
  ```
- **两种构建方式（待确认，见 §6 Q1）**：
  - **A. 手写每职业每级表**：明确可控，易排除绿宝石/限定大师附魔，维护成本低（物品集稳定）。
  - **B. 自动从 `VillagerTrades` 派生**：运行时扫描 `VillagerTrades.WEAPONSMITH_TRADES` / `TOOLSMITH_TRADES` / `ARMORER_TRADES`（类型 `Map<Integer, ITrade[]>`），对每个 trade 调 `getOffer(merchant, random)` 抽「售出物品」，过滤掉绿宝石与收购型（输出是绿宝石的）。
    - **难点**：`ITrade` 是工厂，`getOffer` 需传入 `Merchant` + `Random`；且附魔/随机项在运行期才确定，只能拿到 **base item**（附魔是 `getOffer` 内临时加的）。因此自动派生只能拿到「未附魔基底物品」，附魔档位仍要手填。
  - **推荐（混合）**：手写 catalog 作主源（稳定可控），但用 `VillagerTrades` 作为「灵感/校验源」确保不漏不错；材料一律运行期从 `RecipeManager` 取（见 3.2）。
- **排除规则**：`minecraft:emerald` 及其变体；收购型 trade（`EmeraldForItemsTrade` 之类，输入物品、输出绿宝石）不进 catalog。

### 3.2 材料读取（原生配方）

- 来源：`ServerWorld.getRecipeManager().getRecipes()`，按输出 Item 反查 `ICraftingRecipe` / `AbstractCookingRecipe`。
- 取 `recipe.getIngredients()` → 每个 `Ingredient.getItems()` 拿到可接受输入集合 → 与村民**真实背包**比对（数量累加）。
- shaped / shapeless 对「材料集合 + 数量」无影响，仅摆放方式不同（生产阶段简化，见 3.4）。

### 3.3 工作台路由（职业工作站）

- 村民 `getBrain().getMemory(MemoryModuleType.JOB_SITE)` 已存**职业方块坐标**，`VillagerActivitySystem.handleCrafting` 已会走到这里 —— 直接复用。
- catalog 内每个 entry **不需要单独指定工作站**：村民就在自己的 `JOB_SITE`「上班」。
- ⚠️ **重要事实**：原版里 工具/盔甲/武器 实际是用**合成台（Crafting Table）**做的；职业方块（锻造台 / 砂轮 / 高炉）只是工作站与补货点，本身不跑合成网格。因此本设计的「在工作站生产」= **站在 `JOB_SITE` 做 consume→produce**，而非真的在职业方块上摆网格。若以后要「真·网格」，需额外处理（见 3.4 / Q4）。
- 参考职业 → 工作站方块（1.16.5 标准映射，来自 `PointOfInterestType`）：

  | 职业 | 工作站 | 典型可合成（catalog 候选） |
  |---|---|---|
  | armorer | BLAST_FURNACE | 锁链/铁/钻石 盔甲 |
  | toolsmith | SMITHING_TABLE | 铁/钻石 镐/斧/铲/锄 |
  | weaponsmith | GRINDSTONE | 铁/钻石 剑/斧 |
  | butcher | SMOKER | （熟食，材料来自配方） |
  | fisherman | BARREL | 鱼竿等 |
  | fletcher | FLETCHING_TABLE | 弓/箭/弩 |
  | leatherworker | CAULDRON | 皮革盔甲/马铠 |
  | librarian | LECTERN | 书/附魔书 |
  | mason | STONECUTTER | 切石制品 |
  | shepherd | LOOM | 旗帜/地毯 |
  | cartographer | CARTOGRAPHY_TABLE | 地图/旗帜图案 |
  | cleric | BREWING_STAND | 药水 |
  | farmer | COMPOSTER | （面包等，材料来自配方） |

### 3.4 合成执行

- **简化模型（推荐初版）**：在 `JOB_SITE` 旁，村民真实背包满足 `recipe.matches` → 扣材料 → 产出 `output` ItemStack → 入背包。不模拟网格摆放。
- **可选真实网格**：开 `CraftingInventory`/容器，按 shaped 行主序回填 3×3（镜像变体是 edge case），取结果。工程量大，建议作为后续增强（见 Q4）。
- **执行时再校验（满足「中途丢材料 → 失败」）**：进入 ACTING 阶段末尾、`produce` 之前，再 `recipe.matches(inventory)` 一次；失败 → 任务失败 + 记 memory + 回话告知。绝不提前扣材料。

### 3.5 随机附魔（参照原版村民货物生成）

- **原版钩子（已确认存在于 1.16.5 映射）**：
  - `net.minecraft.entity.merchant.villager.VillagerTrades$EnchantedItemForEmeraldsTrade`
  - `net.minecraft.enchantment.EnchantmentHelper`
- **原版做法**：`EnchantedItemForEmeraldsTrade.getOffer(...)` 内用
  `EnchantmentHelper.addRandomEnchantment(Random rand, ItemStack stack, int enchantLevel, boolean allowTreasure)` 给基底物品加随机附魔；`enchantLevel` 由该 trade 的构造参数决定（如武器匠大师级对应高附魔等级）。
- **我们的做法**：
  - catalog 中标记 `enchantable=true` 且村民达到**大师级**（`VillagerData.getLevel() == 5`）时，产出后调用：
    ```java
    EnchantmentHelper.addRandomEnchantment(villager.getRandom(), outputStack, level, false);
    ```
  - `level` 参照该职业 vanilla trade 里给的数值（例如武器匠大师附魔等级约为 `5 + rand(15)` 量级 —— 具体数值需对照 `VillagerTrades` 的构造参数确认）。
  - 仅对「可附魔物品」（武器/工具/盔甲/书）生效；绿宝石/材料类不附魔。

### 3.6 LLM TODO 系统（对话中产生 + 执行）

- **现状缺口**：`AgentGoal` + `processGoals` + `generateNewGoals`(随机) + `executeCraftGoal`(stub 只记 memory)；`ChatMessagePacket` 对话纯文本、无 action 解析。
- **协议（待确认 Q3 触发方式）**：LLM 回复用固定标记输出意图，例如：
  - 行内标记：`TODO: craft minecraft:iron_pickaxe x1`
  - 或 JSON：`{"action":"craft","item":"minecraft:iron_pickaxe","qty":1}`
  - `LLMService` 当前只返回纯文本 → 先用**正则/JSON 解析兜底**；Gemini/OpenAI 可后续上 **function calling**（工具声明 `craft(item, qty)`）。
- **解析落点**：在 `ChatMessagePacket.handle` 的 `thenAccept(response)` 里解析回复 → 生成 `AgentGoal("craft", ...)` 入队（复用 `agent.getGoals()`）。
- **执行落点**：`executeCraftGoal` 接 `ProfessionCraftCatalog` + `RecipeManager`：
  1. 校验 item 在村民「职业 catalog + 当前等级」内；
  2. 不满足 → 拒绝（记 memory / 回话「我还不会做这个」）；
  3. 满足 → 设 `currentAction`/`scheduledActivity` 为 CRAFT 目标 → 走 3.3 / 3.4 流程。

### 3.7 TODO 作为「泛化可执行行动」（不只是 craft）

> 用户补充：聊天除了产生 craft 的 TODO，也能产生「移动到某地」「社交」「交易」等其它 TODO；**TODO 应是村民可执行的行动之一，而非 craft 专属**。

- **现有两层模型（已具备雏形，正好复用）**：
  - `AgentGoal`（TODO 意图）：含 `goalType`（"gather"/"craft"/"trade"/"build"/"socialize"…）、`targetLocation`、`targetItem`、`targetQuantity`、priority、importance；持久化在 `agent.getGoals()`。
  - `VillagerAction`（执行单元）：含 `ActionType` 枚举——`CRAFT / MOVE / GATHER / BUILD / HARVEST / PLACE / BREAK …`，带 `ActionPhase`（WALKING…）；一次只跑一个，存于 `agent.getCurrentAction()`。
  - `VillagerActivitySystem`：当前由 `scheduledActivity`（每日 LLM 排程字符串）**驱动**出 `VillagerAction`。
- **缺口**：`scheduledActivity` 字符串开关只覆盖硬编码的几种（crafting/explore/rest）；**聊天产生的 goal 没有任何派发路径**。且 `AgentGoal.goalType` 与 `VillagerAction.ActionType` 目前没被统一映射。
- **新架构（统一派发）**：
  1. **单一意图来源**：TODO 来自三处——① 每日 LLM 排程（现有 `generateGoalsForProfession`/每日 prune）；② 玩家聊天请求（「去东边的井」「帮我做个铁镐」）；③ 村民在对话/思考中自主决定。
  2. **Goal→Action 派发器**（新增 `TodoActionDispatcher` 或并入 `VillagerActivitySystem`）：每 tick 取**优先级最高且未完成**的 `AgentGoal`，按其 `goalType` 映射成对应 `VillagerAction`：

     | AgentGoal.goalType | VillagerAction | 说明 |
     |---|---|---|
     | `craft` | `CRAFT` | 走 §3.3/§3.4（catalog 校验 + JOB_SITE + 执行时再校验） |
     | `move` | `MOVE` | 导航到 `targetLocation`（聊天「去某地」即此） |
     | `gather` | `GATHER` | 拾取 `targetLocation` 附近掉落物 |
     | `trade` | （交易流程） | 与玩家/村民交易 |
     | `socialize` | （社交/对话） | 走到目标村民/玩家旁 |
     | `build` | `BUILD` | 多格建造 Job |

  3. **取代 `scheduledActivity` 字符串开关**：每日 LLM 排程不再直接设字符串，而是**生成/更新 `AgentGoal` 列表**（含「去工作」「回家休息」等）。这样 craft / move / rest 都走同一条 goal→action 管线，消除两套并行状态。
- **解析落点统一**：`ChatMessagePacket.handle` 的 `thenAccept(response)` 里解析回复 → 可能生成**多个** `AgentGoal`（一句「我去东边井打水顺便给你做个铁镐」可拆成 `move` + `craft`）。解析协议见 §3.6。

---

## 4. 与现有系统对接点

| 现有组件 | 改动 |
|---|---|
| `RecipeRegistry`（手写 5 条） | 废弃，由 `ProfessionCraftCatalog` 取代 |
| `VillagerActivitySystem.handleCrafting` / `finalizeCrafting` | 不再 `available.get(0)` 乱选；消费来自 `AgentGoal(CRAFT)` 的目标 item |
| `CraftingAction.executeCraft` | 唯一调用方 `CraftingRequestPacket` 是死代码；可复用其「按名查 + 校验」骨架，底层换成真实背包 + RecipeManager + JOB_SITE 导航 |
| `AgentInventory` | 即本 mod 的工作背包，无需另接村民原版 `Inventory`；合成直接在其上校验/消费/产出（见 §9 双背包说明） |
| `ChatMessagePacket` | 增加 action/TODO 解析分支 |
| `VillagerAgentData.generateChatResponse` | system 提示注入「可合成目录 + TODO 协议」 |

---

## 5. 风险与注意

- **A. 双背包澄清（更正前版「假背包」表述）**：本 mod 实际有「两个背包」——(1) 我们自写的 `AgentInventory`（27 格，`ai/AgentInventory.java`），是 AI 所有系统（farm/craft/trade/装备）统一操作的**工作背包**；(2) 原版 `VillagerEntity` 自带的 8 格背包（Minecraft 给所有村民的内置库存，原版用来存作物/礼物）。我们通过 `VillagerEntityMixin.wantsToPickUp` **强制返回 false**，把掉落物全部导向 `AgentInventory`，从而「架空」了原版那个背包。**结论**：在 craft 设计里，`AgentInventory` 就是「真实背包」——直接在其上校验/消费/产出即可，不需要另接村民原版 `Inventory`；「材料中途丢失 → 失败」语义改为：任务只记意图，**执行那刻**再对 `AgentInventory` 做 `recipe.matches()`（中途若被并发交易/玩家取走材料等扣掉，则 matches 失败 → 合成失败）。
- **B. 工作站 ≠ 真实合成点**：见 3.3 说明，初版用「站在 JOB_SITE 做 consume→produce」规避网格模拟。
- **C. 自动派生 catalog 的难点**：运行期 `getOffer` 才能抽输出，且只能拿基底物品；附魔档位需手填。故推荐手写/混合。
- **D. 等级系统**：可合成档位依赖 `VillagerEntity.getVillagerData().getLevel()`（1=Novice … 5=Master）。需读取并随升级解锁 catalog 高阶项。
- **E. TODO 解析鲁棒性**：「物体是否存在 / 材料是否够」**必须代码判定**，不能信任 LLM 输出。
- **F. 物品名歧义**：玩家/LLM 可能用中文/英文/别名。最佳做法让 LLM 输出 `minecraft:<id>` 形式 ID；否则用 `I18n`/别名表解析。

---

## 6. 已确认决策（2026-08-16，与用户对齐）

1. **Catalog 来源**：**手写每职业每级表**。从原版 `VillagerTrades` 各职业「每级会交易/售出的物品」抄一份作为种子，后续按需求手动补充。不自动派生（避免 `ITrade.getOffer` 运行期解析的坑）。注意：catalog 只列「职业会做的物品」，具体交易经济（价格/收购）仍由 LLM 驱动。
2. **材料来源**：**暂假设材料已在村民真实背包**。合成只校验背包内是否有足够材料；材料收集机制后续单独做。
3. **TODO 触发方式**：**两者皆可**——既支持玩家请求（「帮我做个铁镐」），也支持村民在对话/思考中自主决定把 `craft X` 加入 TODO。
4. **合成执行保真度**：**初版简化 consume→produce**——村民走到 `JOB_SITE` 后扣材料、产物品，不模拟合成网格摆放；真网格作为后续增强。

---

## 7. 落地计划（分阶段）

- **Phase 0 — ProfessionCraftCatalog**：手写初版（先 3 个 gear 职业：weaponsmith / toolsmith / armorer），定义 `CraftableEntry` 与等级门槛。
- **Phase 1 — 原生配方材料读取 + 真实背包集成**：`RecipeManager` 反查 + 对接真实 `Inventory`；替换 `AgentInventory` 依赖。
- **Phase 2 — 执行流程**：`handleCrafting` 改为消费 `AgentGoal(CRAFT)` 目标；导航 `JOB_SITE`；执行时再校验 + produce。
- **Phase 3 — 随机附魔**：大师级 `enchantable` entry 走 `EnchantmentHelper.addRandomEnchantment`。
- **Phase 4 — LLM TODO + 统一派发**：`ChatMessagePacket` 解析任意意图（craft/move/trade…）→ 入队 `AgentGoal`；新增 `TodoActionDispatcher`，把「优先级最高未完成任务」按 `goalType` 映射为 `VillagerAction`（取代 `scheduledActivity` 字符串开关）；`executeCraftGoal` 落地校验+执行。
- **Phase 5 — 全职业铺开 + 调试 overlay**：config 开关控制，调试可视化合成任务状态。

---

## 8. 原版引用速查（1.16.5 映射，已核对）

- `net.minecraft.entity.merchant.villager.VillagerTrades`（含 `EnchantedItemForEmeraldsTrade`、`EnchantedBookForEmeraldsTrade`、`DyedArmorForEmeraldsTrade` 等 inner trade 工厂）
- `net.minecraft.enchantment.EnchantmentHelper.addRandomEnchantment(Random, ItemStack, int, boolean)`
- `VillagerEntity.getVillagerData().getLevel()` → 1..5
- `MemoryModuleType.JOB_SITE` → 职业工作站坐标（村民已存）
- `ServerWorld.getRecipeManager().getRecipes()` → 全量 `IRecipe`

---

## 9. 双背包说明（为什么会有「两个背包」）

- **我们写的**：`ai/AgentInventory.java` —— 一个干净的 27 格 `List<ItemStack>` 背包，带 NBT 序列化与 GUI 包装。AI 的 farm / craft / trade / 装备**全部在它上面操作**。这是我们**主动设计**的工作背包。
- **Minecraft 自带的**：`VillagerEntity` 本体就有一个 8 格背包（所有村民都有，原版用来存作物、礼物等）。**这不是我们写的**，是基类的内置库存。
- **我们如何二选一**：`mixin/VillagerEntityMixin.java` 的 `wantsToPickUp` 注入对「受管理村民」强制返回 `false`，于是掉落物**不会**进原版 8 格背包（否则那些物品对我们系统「隐形」），全部改由 `ItemAttractionSystem` 吸进 `AgentInventory`。
- **对 craft 设计的影响**：`AgentInventory` 在本 mod 里就是「真实工作背包」。合成直接在其上 `getItems()` / `removeItem()` / `addItem()`，无需另接村民原版 `Inventory`。「材料中途丢失 → 失败」由「执行刻再 `recipe.matches(AgentInventory)`」保证——若执行前材料被并发交易/玩家取走，`matches` 失败即合成失败，不提前扣材料。

---

## 10. 实现状态（2026-08-16，已落地）

> 下述 Phase 0–4 已全部编码完成；代码尚未由用户在本机编译（按约定由用户跑 `gradlew compileJava` 验证）。

- **Phase 0 ✅ `ProfessionCraftCatalog`**：`ai/ProfessionCraftCatalog.java`，3 个 gear 职业（weaponsmith / toolsmith / armorer）按 1.16.5 真实交易档填充；其余 12 职业为 `// VERIFY` 种子档。提供 `isCraftable` / `canEnchant` / `getCraftableIds` / `getAllIds`，并对职业字符串做 `norm()` 归一（小写 + 去 `minecraft:` 前缀）。
- **Phase 1 ✅ 原生配方 + 真实背包**：`ai/NativeRecipeResolver.java`，`findRecipe(ServerWorld, Item|String)` 遍历 `RecipeManager.getRecipes()` 反查；`consumeAndProduce(AgentInventory, IRecipe, qty)` 在执行刻解析 `Ingredient.getItems()` → 校验 → 扣材料 → 产出。**直接用 `AgentInventory`，未另接原版 `VillagerEntity` 8 格背包**。
- **Phase 2 ✅ 执行流程**：`VillagerAgentManager.executeCraftGoal` —— Phase A 走到 `JOB_SITE`（距离 ≤ `GOAL_ARRIVE_SQ`）→ Phase B 在站位 `CRAFT_WORK_TICKS` 后执行时再校验 + `consumeAndProduce`。
- **Phase 3 ✅ 大师级随机附魔**：`executeCraftGoal` 在 `ProfessionCraftCatalog.canEnchant` 为真时调用 `enchantFirstStack`，内部 `EnchantmentHelper.addRandomEnchantment(Random, stack, 5 + rand(15), false)`。
- **Phase 4 ✅ LLM TODO + 统一派发**：
  - **解析**：`ai/TodoParser.java` 解析 `TODO: craft/gather/move/socialize/trade` 行 → `List<AgentGoal>`；`stripTodoLines` 移除玩家可见文本中的 TODO 行。
  - **系统提示注入**：`VillagerAgentData.generateChatResponse` 在系统提示中注入「可合成目录 + TODO 协议」，让 LLM 用 `minecraft:<id>` 形式产出隐藏 TODO 行。
  - **聊天接入**：`ChatMessagePacket.handle()` 的 `thenAccept` 内 `TodoParser.parse` → 按 `getMaxGoals()` 上限入队；玩家只收到 `stripTodoLines` 后的可见文本。
  - **统一派发**：原设计的独立 `TodoActionDispatcher` 类**未单独新建**，其实质逻辑落在 `VillagerAgentManager.processGoals()`——按 `goalType`（craft / move / gather / socialize / trade）switch 到对应 `executeXxxGoal`，并对目标设 `setGoalDriven(true)`，期间 `agent.setScheduledActivity(null)` 让目标接管移动（取代原 `scheduledActivity` 字符串开关）。

- **Phase 5 ⬜ 待做**：全职业目录核验 + config 驱动的调试 overlay（可视化合成任务状态）。
- **已知风险 / 待核验**：
  - 12 个非 gear 职业的 catalog 条目标注 `// VERIFY`，其中 `cleric:ender_pearl`、`librarian:quill` 等原版并不可「合成」（实为交易/不可得），`executeCraftGoal` 会在 `findRecipe` 返回 null 时诚实失败并记录 memory——建议后续清理或改为合法合成档。
  - `NativeRecipeResolver.consumeAndProduce` 的 `Ingredient.getItems()` 取第一项作「首选材料」的启发式，对多选项配方（如木板种类）可能选错具体变种；当前按「村民背包内已有的优先」规避，后续可细化。
  - `processGoals` 每 tick 只推进「优先级最高未完成任务」，高优先级职业目标未完时，聊天新增的 craft/move 目标会在其后排队执行（符合预期优先权语义）。

### 10.1 残留与死代码（2026-08-16 已收口）

> §4 曾计划「`RecipeRegistry` 废弃、`finalizeCrafting` 不再 `available.get(0)` 乱选」——**该收口已于 2026-08-16 完成**：

- ✅ **旧路径已收口**：`VillagerActivitySystem.finalizeCrafting` 改为遍历 `ProfessionCraftCatalog.getCraftableIds(profession, level)` + `NativeRecipeResolver.consumeAndProduce`（复用 goal-driven 同套解析），大师级附魔内联 `EnchantmentHelper.enchantItem`；`VillagerAgentData.buildActionRequest` 里的 `RecipeRegistry.getRecipesForProfession` 也改为 `ProfessionCraftCatalog.getAllIds`。→ `RecipeRegistry` 已从活跃路径下线，仅剩死代码引用。
- 🪦 **死代码（仍待清理，可一并删除）**：`ai/ActionExecutor.java`（三处 `// TODO` 空壳，无外部调用方）；`network/CraftingRequestPacket.java` + `ai/CraftingAction.java` + `ai/RecipeRegistry.java`（packet 无客户端发送方，`CraftingAction.executeCraft` 只被该 packet 调用；RecipeRegistry 只被 CraftingAction 引用）。
