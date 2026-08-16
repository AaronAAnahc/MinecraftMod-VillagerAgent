# VillagerAgent 视觉模块设计草案（v2）

> ⚠️ **本文档部分内容已过时（设计草案，非当前实现）**：大气判定原案为"通天列 OR 长串空气占比"，与 2026-08-16 实际代码**不完全一致**——当前 `BuildingLocator` 的大气判定 = `skyOpen ∪ longRun`（两条独立测试 OR）：`skyOpen`=竖直列通天，`longRun`=某轴**双向严格大于 `AIR_RUN=16`** 的连续空气。`longRun`/`computeBigAir`/`growBigAir` 均保留，但 `AIR_RUN` 由 12→16 且改双向严格，使普通封闭房间不再被误判为大气。以 `bed_room_search_implementation.md` / `building_detection_implementation.md` 为准。

> 目标：把现有 `VillagerVisionSystem`（随机采样 → 一段文字 → 注入 LLM）升级为**分层感知**系统：
> 远景靠 **chunk 内容记忆**、中景靠 **建筑结构识别**、近景靠 **视锥体精扫**。
> 核心约束（沿用 `building_perception_design.md`）：**不引入额外神经网络/模型**，所有"检测"在 Java/模组侧用确定性几何算法完成，产物为结构化文本/JSON 喂给 LLM。

---

## 0. 现状与痛点

| 维度 | 现状 | 问题 |
|------|------|------|
| chunk 记忆 | `LinkedHashSet<Long> visitedChunks` 仅存坐标 | 进了 chunk 只记"来过"，不知道里面**有什么** |
| 视野感知 | 随机采样 50/15/20 点（树/洞/水） | 漏检率高、无朝向、无实体、无方块级细节 |
| 建筑识别 | `docs/building_perception_design.md` 方案完整但**零代码** | 未落地，且未与 chunk 记忆挂钩 |
| 区块结构 | 无 | 不知道某 chunk 内有房子/农田/矿脉 |

用户要求的四件事：
1. 行走过的 chunk 记住其**主要内容**（泥土/庄稼/树等，非全量）
2. 当前视野方块用**视锥体剔除**
3. 对每个区块内有意义的**结构进行检测与记录**（建筑识别）
4. 对当前视野内方块做**更详细的记录**

---

## 1. 设计目标

- **分层感知**：远景 = chunk 记忆摘要（"我去过北边那片森林，有房子"）；中景 = 建筑结构解析；近景 = 视锥内精扫（"眼前 3 格有个工作台"）。
- **内容记忆而非全量**：每个 chunk 只存主导方块类别 + 特征标签 + 检测到的结构，token/内存预算可控。
- **视锥剔除做节流**：只对朝向内、范围内的方块做高成本操作（精扫 / 实体检测），避免全区域暴力扫描。
- **建筑识别沉淀进 chunk memory**：复用 `building_perception_design.md` 的关系型解析，结果嵌入对应 chunk。
- **预算可控**：内容采样一次性（进 chunk 时）、结构扫描节流/异步、精扫按需（对话/决策前）。

---

## 2. 整体架构

```
┌─ 感知层（Java/模组侧，确定性算法，无 NN）──────────────────────────────┐
│                                                                        │
│  ① ChunkContentSampler ── 进 chunk 时跑一次 ─┐                         │
│                                            ▼                         │
│  ② ChunkMemoryStore  (每 chunk 存内容摘要+结构) ◀──┐                   │
│         │                                        │                   │
│         │  structures ◀── ③ BuildingLocator(廉价定位)──┐              │
│         │         │ 产出 BuildingCandidate(detailed=false)            │
│         │         └── ④ StructureParser(进场才跑) 复用 building_perception │
│         │               (关系型确定性解析，仅进入 coarseBounds 时触发) │
│         ▼                                                              │
│  ④ FrustumCuller ── 仅对朝向内方块 ──▶ ⑤ DetailedViewRecorder          │
│         (视锥剔除)                         (视锥内精扫: 显著方块+实体)   │
└───────────────────────────────┬────────────────────────────────────────┘
                                ▼ 结构化文本 / JSON
┌─ 表征层 ─────────────────────────────────────────────────────────────┐
│  buildEnvironmentSummary() 重写为分层组装：                          │
│    [chunk 记忆摘要] + [视野内结构] + [视锥精扫细节] + [实体]           │
└───────────────────────────────┬────────────────────────────────────────┘
                                ▼
                          理解层：LLM
```

---

## 3. 模块一：ChunkMemory 内容记忆（升级 `VillagerAgentData`）

### 3.1 数据结构

新增 `ChunkMemory` 类（建议放 `ai/memory/ChunkMemory.java`）：

```java
public class ChunkMemory {
    public long chunkKey;                 // ChunkPos.asLong
    public long lastVisitedTick;          // 最近一次进入的游戏刻

    /** 主导方块类别计数（采样统计，非全量） */
    public EnumMap<BlockCategory, Integer> blockCounts = new EnumMap<>(BlockCategory.class);

    /** 特征标签：forest / farmland / water / village / mountain / cave / ore ... */
    public Set<String> tags = new HashSet<>();

    /** 显著性 0~1：决定是否长期保留细节（见 3.3） */
    public float saliency = 0f;

    /** 个人见过的建筑（轻量引用：只记共享 WorldStructureIndex 里的 id + 是否进过）；全量建筑由 §5.6 共享索引维护 */
    public List<Long> knownBuildingIds = new ArrayList<>();

    /** 视锥精扫留下的显著方块（仅保留"值得记住"的，见 §6） */
    public List<BlockObservation> notableBlocks = new ArrayList<>();
}
```

`BlockCategory` 枚举（按用户举例"泥土/庄稼/树"归类，避免逐方块记录）：

```java
enum BlockCategory {
    DIRT, GRASS, SAND, STONE, WATER,
    WOOD_LOG, LEAVES,          // "树"
    CROP,                      // "庄稼"（含小麦/胡萝卜/马铃薯等）
    BUILDING,                  // 木板/石头/砖/玻璃等人工方块
    ORE,                       // 煤矿/铁矿等（裸露或浅层）
    PATH,                      // 路/耕地
    OTHER
}
```

`BlockObservation`（视锥精扫产物，§6 用；也用于 chunk 记忆里的"显著方块"）：

```java
public class BlockObservation {
    public BlockPos localPos;    // 相对所属 chunk 原点的局部坐标 (0..15, y, 0..15)，省内存
    public String blockId;       // 如 "minecraft:crafting_table"
    public String note;          // 可选：如 "crops ready to harvest", "chest"
    // 需要全局坐标时：chunkOrigin + localPos 重建
}
```

`EntityObservation`（实体记忆，§3.2.2 用）：

```java
public enum EntityCategory { HOSTILE, ANIMAL, VILLAGER, PLAYER, ITEM, OTHER }

public class EntityObservation {
    public String entityId;          // 如 "minecraft:creeper" / "minecraft:cow"
    public EntityCategory category;  // 用于快速过滤（找怪物 / 找动物 / 找村民）
    public BlockPos globalPos;       // 当时所在全局坐标（会漂移，见 §3.2.2 注意）
    public long seenTick;            // 记录时的游戏刻，用于判断新鲜度
}
```

### 3.2 内容采样 `ChunkContentSampler`（进 chunk 时跑一次，简单版）

**核心思路（用户指定）**：进 chunk 时，把这个 chunk 在**站立层附近的一个薄 slab** 完整扫一遍——x、z 覆盖整块 16×16，y 只取 `[entryY-3, entryY+4]`（站立脚下 3 格到头顶 4 格，共 8 格）。对每个方块做**类别计数 + 显著物记录**，不存全量。

```java
public static ChunkMemory sample(LivingEntity villager, ServerWorld world, int cx, int cz) {
    ChunkMemory cm = new ChunkMemory();
    cm.chunkKey = ChunkPos.asLong(cx, cz);
    BlockPos vp = villager.blockPosition();
    int yLo = vp.getY() - 3, yHi = vp.getY() + 4;     // 仅站立层 ±3~+4
    int x0 = cx << 4, z0 = cz << 4;
    for (int x = x0; x < x0 + 16; x++) {
        for (int z = z0; z < z0 + 16; z++) {
            for (int y = yLo; y <= yHi; y++) {
                BlockState st = world.getBlockState(new BlockPos(x, y, z));
                BlockCategory cat = classify(st);
                if (cat == null) continue;             // 空气不计入
                cm.blockCounts.merge(cat, 1, Integer::sum);
                if (isNotable(st, cat)) {              // 矿/工作台/箱/床/门/成熟作物/熔岩...
                    cm.notableBlocks.add(new BlockObservation(
                        new BlockPos(x - x0, y, z - z0), // 存局部坐标，省内存
                        st.getBlock().getRegistryName().toString()));
                }
            }
        }
    }
    deriveTags(cm);          // 据计数生成 forest/farmland/water/village/ore 等标签
    sampleEntities(villager, world, cm);  // §3.2.2 实体记忆
    return cm;
}
```

- **成本**：16×16×8 = **2048 次 `getBlockState`**，进 chunk 仅一次、结果缓存；村民脚下 chunk 必已加载，无磁盘 IO 开销。即使 100 村民分摊也极小（每次抽样 < 1 ms 量级）。
- **局限（重要，预期代价）**：只覆盖站立层 ±3.5 格。**树冠、地下洞穴、高层墙/屋顶**会被忽略——这正是"先做简单版"的取舍。弥补：① 视锥精扫（§6）对**当前视线内**的高处/远处方块补扫；② 房屋由共享 `WorldStructureIndex`（§5.6）单独扫描（不受此 slab 限制）。

### 3.2.1 方块分类 `classify()`（feasible：全部基于 1.16.5 既有 Tag/Block 判断）

```java
static BlockCategory classify(BlockState s) {
    if (s.isAir()) return null;
    if (s.is(BlockTags.LOGS))      return WOOD_LOG;
    if (s.is(BlockTags.LEAVES))    return LEAVES;
    if (s.is(BlockTags.DIRT_LIKE) || s.getBlock()==Blocks.GRASS_BLOCK
        || s.getBlock()==Blocks.GRASS_PATH) return DIRT;
    if (s.getBlock()==Blocks.SAND || s.getBlock()==Blocks.SANDSTONE) return SAND;
    if (s.is(BlockTags.GOLD_ORES) || s.is(BlockTags.IRON_ORES)
        || s.is(BlockTags.COAL_ORES) || s.is(BlockTags.DIAMOND_ORES)
        || s.is(BlockTags.EMERALD_ORES) || s.is(BlockTags.REDSTONE_ORES)
        || s.is(BlockTags.LAPIS_ORES) || s.is(BlockTags.COPPER_ORES)) return ORE;
    if (s.getFluidState().is(FluidTags.WATER)) return WATER;
    if (s.getBlock()==Blocks.LAVA) return OTHER;          // 危险地标，单独打 tag
    if (s.getBlock()==Blocks.COBBLESTONE || s.getBlock()==Blocks.STONE
        || s.is(BlockTags.STONE_BRICKS)) return STONE;
    if (s.getBlock()==Blocks.FARMLAND || s.is(BlockTags.CROPS)) return CROP;
    if (isBuildingBlock(s)) return BUILDING;              // 木板/石砖/玻璃/楼梯/台阶...
    return OTHER;
}

/** 只记"值得回想的"方块，避免 notableBlocks 膨胀 */
static boolean isNotable(BlockState s, BlockCategory c) {
    if (c == ORE) return true;                            // 矿物必记
    Block b = s.getBlock();
    return b==Blocks.CRAFTING_TABLE || b==Blocks.FURNACE || b==Blocks.CHEST
        || b==Blocks.BED || b==Blocks.DOOR || b==Blocks.BARREL
        || s.getBlock()==Blocks.FARMLAND                   // 耕地（可能种了作物）
        || s.is(BlockTags.CROPS)                          // 作物
        || s.getFluidState().is(FluidTags.LAVA);          // 熔岩（危险地标）
}

/** 据计数生成高层标签 */
static void deriveTags(ChunkMemory cm) {
    int wood = cm.blockCounts.getOrDefault(WOOD_LOG,0) + cm.blockCounts.getOrDefault(LEAVES,0);
    int crop = cm.blockCounts.getOrDefault(CROP,0);
    int water= cm.blockCounts.getOrDefault(WATER,0);
    int build= cm.blockCounts.getOrDefault(BUILDING,0);
    if (wood  > 30) cm.tags.add("forest");
    if (crop  > 20) cm.tags.add("farmland");
    if (water >  0) cm.tags.add("water");
    if (build > 40) cm.tags.add("village");
    if (cm.blockCounts.containsKey(ORE)) cm.tags.add("ore");
    if (cm.notableBlocks.stream().anyMatch(b -> b.blockId.contains("lava"))) cm.tags.add("danger_lava");
}
```

> `isBuildingBlock` 用 `BlockTags.PLANKS` / `BlockTags.STONE_BRICKS` / `WALLS` / `WOODEN_DOORS` 等现有 tag 组合判断；阈值（30/20/40）首版写死，后续可进 `ModConfig`。

### 3.2.2 实体记忆 `sampleEntities()`（当前系统完全缺失的能力）

进 chunk 时，把附近实体（含相邻 chunk）的名字与位置记下来：

```java
static void sampleEntities(LivingEntity v, ServerWorld w, ChunkMemory cm) {
    AxisAlignedBB box = new AxisAlignedBB(v.blockPosition()).inflate(24); // 含邻近 chunk
    for (Entity e : w.getEntities(v, box)) {          // 排除自己
        EntityCategory cat = categorize(e);
        cm.entities.add(new EntityObservation(
            e.getType().getRegistryName().toString(),
            cat,
            e.blockPosition(),
            w.getGameTime()));
    }
}

static EntityCategory categorize(Entity e) {
    if (e instanceof ItemEntity)      return EntityCategory.ITEM;
    if (e instanceof ServerPlayerEntity) return EntityCategory.PLAYER;
    if (e instanceof VillagerEntity)  return EntityCategory.VILLAGER;
    if (e instanceof AnimalEntity)    return EntityCategory.ANIMAL;
    if (e instanceof MonsterEntity)   return EntityCategory.HOSTILE;
    return EntityCategory.OTHER;
}
```

**注意（新鲜度）**：实体会移动，存的坐标是"记录那一刻"的。对策：① 只在进 chunk 时刷新；② 实际交互前若需要精确位置，用 §6 视锥精扫实时重查；③ `seenTick` 用于判断该记忆是否过期（如 > 1 游戏日则降级为"曾经见过"）。

### 3.3 显著性评分 `saliency`

决定长期保留策略，避免 `MAX_KNOWN_CHUNKS=512` 很快被无意义 chunk 占满：

```
saliency = w1 * hasStructure + w2 * hasCrop + w3 * hasOre + w4 * recentVisit
```

- `saliency` 高的 chunk（有房子/农田/矿）优先保留、可承载更细的 `notableBlocks`；
- 低显著性纯地形 chunk 只留 `tags`，不存 `notableBlocks`，节省内存。

### 3.4 `VillagerAgentData` 改造

- 将 `private final LinkedHashSet<Long> visitedChunks` 改为
  `private final LinkedHashMap<Long, ChunkMemory> chunkMemories`（保留插入顺序以支持 LRU 淘汰）。
- `updateChunkMemory(int cx, int cz)`：进入新 chunk 时，调 `ChunkContentSampler` 生成/刷新 `ChunkMemory`，并对 8 邻居标记 `known`（邻居可只记坐标不采样，省成本）。
- 新增访问器：`getChunkMemory(cx,cz)`、`getAllChunkMemories()`、`pruneLowSaliency()`。
- **NBT 序列化改造**：原 `VisitedChunks` 是 `long[]`，现需序列化每个 `ChunkMemory`（blockCounts / tags / saliency / structures / notableBlocks）。建议新增 `ChunkMemories` ListNBT，每项为 CompoundNBT。反序列化时重建 `LinkedHashMap`。

### 3.5 记忆查询助手（"不用重新扫描世界就能回想"）

村民需要"凭记忆找东西"时，直接查 `chunkMemories`，**不重新扫描世界**。以下方法挂在 `VillagerAgentData` 上：

```java
/** 在记忆里找工作台（不需要重新扫描） */
public List<BlockPos> findWorkbenches() {
    List<BlockPos> out = new ArrayList<>();
    for (ChunkMemory cm : chunkMemories.values())
        for (BlockObservation b : cm.notableBlocks)
            if (b.blockId.equals("minecraft:crafting_table"))
                out.add(toGlobal(cm.chunkKey, b.localPos));
    return out;
}

/** 在记忆里找某类矿（如 "minecraft:iron_ore"） */
public List<BlockPos> findOre(String oreId) { /* 类似，过滤 blockId */ }

/** 回想附近村庄/房屋：结合共享索引 + 个人 knownBuildingIds */
public List<String> rememberNearbyHouses(int radiusChunks, BlockPos here) {
    List<String> out = new ArrayList<>();
    // 1) 个人记忆：标记过 village 标签的 chunk
    for (ChunkMemory cm : chunkMemories.values())
        if (cm.tags.contains("village")) out.add("在 (" + x + "," + z + ") 附近有房屋群");
    // 2) 共享索引（§5.6）：含山洞房
    WorldStructureIndex idx = ...; // 单例
    for (BuildingRecord r : idx.queryNear(here, radiusChunks))
        out.add("房屋 " + r.id + " 约在 " + r.coarseBounds + "（" + r.coarseType + "）");
    return out;
}

/** 回想水/熔岩（导航用：找水源、避开熔岩） */
public boolean remembersWaterNearby() { /* 任一 chunk tags 含 water */ }
public boolean remembersLavaDanger()  { /* 任一 chunk tags 含 danger_lava */ }

/** 回想某类实体（找羊/找怪/找其他村民） */
public List<EntityObservation> rememberEntities(EntityCategory cat, long maxAgeTicks) { /* 过滤 */ }
```

- **为什么放在个人数据上**：每个村民只查自己的 `chunkMemories`，互不干扰、零共享锁。
- **房屋记忆的两种来源**：① 个人 `tags` 里的 `"village"`（便宜、粗糙）；② 共享 `WorldStructureIndex` 的精确结果（含山洞房，**§5.6 的主算法**）。两者都可在"想回家/想找活干"时调用，无需重扫世界。
- **"其他容易记的东西"**：除上表外，还顺手记：`tags` 已含 forest/farmland/water/ore/danger_lava 等高层语义；村民自身的 `JOB_SITE`（来自原版 brain，已在 `VillagerAgentManager.checkJobBlockRestock` 用过）即"我的工作台在哪"；出生/常驻 chunk 可单独标记 `homeChunk`。

### 3.6 可行性小结（chunk 记忆简单版）

| 维度 | 结论 |
|------|------|
| 计算成本 | 每 chunk 进一次 2048 次 `getBlockState` + 一次范围实体查询，< 1 ms，缓存后零复算。**可行，无性能风险。** |
| 数据来源 | 全部用 1.16.5 既有 API（`BlockTags`、`Blocks.*`、`getFluidState`、`getEntities`、`getLookAngle`），**无需反射/混元/mixin**。 |
| 内存 | 每 chunk 仅存 `EnumMap` 计数 + 若干 `notableBlocks`/`entities`；靠 `MAX_KNOWN_CHUNKS=512` + `saliency` 淘汰控制。 |
| 主要取舍 | 仅扫站立层 ±3.5 格，**漏树冠/地下/高层结构**——由 §6 视锥补扫 + §5.6 房屋索引补足，符合"先做简单版"目标。 |
| 实体漂移 | 坐标会过时；用 `seenTick` 标记新鲜度，交互前以 §6 实时重查兜底。 |
| 可落地性 | 纯 Java 数据遍历，不依赖 LLM、不依赖渲染线程，**可独立编译测试**。 |

---

## 4. 模块二：FrustumCuller 视锥体剔除（新增 `ai/vision/FrustumCuller.java`）

复用现有 `VillagerVisionSystem` 已实现的 `yRot → 8 方向` 逻辑，扩展为**连续视锥**。

### 4.1 视锥参数 & 前向向量

```
eye    = villager 眼睛位置 (blockPosition + ~1.6 高)
forward= villager.getLookAngle()   // 见下，1.16.5 原生、最稳
fovDeg = 70                        // 水平/垂直统一 FOV（圆锥近似），可调
range  = 16~24 格                  // 近景精扫半径
```

**前向向量——优先用 `LivingEntity.getLookAngle()`**：1.16.5 的 `LivingEntity` 自带 `getLookAngle()`，按实体当前 `yRot`/`xRot` 返回**单位前向向量**，自动处理俯仰角与 MC 的坐标约定，**避免手算符号错误**（这是最容易踩的坑）。

```java
// 推荐：直接取，无需手算
Vec3 forward = villager.getLookAngle();

// 备用：手动公式（仅当 getLookAngle 不可用）。注意 MC 约定 yaw=0→+Z(南)、xRot 下视为负
static Vec3 forwardManual(float yawDeg, float pitchDeg) {
    float yaw = (float)Math.toRadians(yawDeg);
    float pit = (float)Math.toRadians(pitchDeg);
    return new Vec3(
        -Math.sin(yaw) * Math.cos(pit),
        -Math.sin(pit),
         Math.cos(yaw) * Math.cos(pit)).normalize();
}
```

> 与旧 `VillagerVisionSystem` 中 `yRot → 8 方向` 的约定一致（0=South, 90=West, ±180=North, -90=East）；`getLookAngle()` 与其等价的连续版本，二者不会冲突。

### 4.2 视锥测试 `isInView`

```java
/** block 中心是否在 (eye, forward, fov, range) 定义的视锥（圆锥）内 */
public static boolean isInView(Vec3 eye, BlockPos block, Vec3 forward, float fovDeg, double range) {
    double dx = block.getX() + 0.5 - eye.x;
    double dy = block.getY() + 0.5 - eye.y;
    double dz = block.getZ() + 0.5 - eye.z;
    double dist = Math.sqrt(dx*dx + dy*dy + dz*dz);
    if (dist > range || dist < 0.5) return false;     // 超距 / 太近
    double cosAngle = (dx*forward.x + dy*forward.y + dz*forward.z) / dist;
    double cosHalf  = Math.cos(Math.toRadians(fovDeg / 2));
    return cosAngle >= cosHalf;                         // 夹角 ≤ 半 FOV ⇒ 在视锥内
}
```

> **圆锥近似**（`fov` 单参数）足够"我在看什么"语义。若要更像人眼（水平比垂直宽），可拆成 `fovH`/`fovV` 分别测，API 不变。也可复用 Minecraft 渲染层 `Frustum` 做一致性校验，但模组侧自实现不依赖渲染线程，更可控。

### 4.3 可行性分析（与现有代码关系 & 风险）

- **成本**：每次测试 O(1)；配合 DetailedViewRecorder 的"先取范围候选（实体/方块）再逐个过滤"天然限流。range≤24 内候选有限（数百级），每帧可承受。
- **pitch 来源已解决**：用 `getLookAngle()` 直接拿到含俯仰的前向向量，不再依赖手读 `xRot`；村民通常平视（xRot≈0），pitch 贡献小，但保留以支持"抬头看树/矿"。
- **与现有代码关系**：`VillagerVisionSystem.getDirectionIndex()` / `DIR_DX_DZ` 保留作远景"粗糙 4-chunk 视野"描述（已写入 LLM）；`FrustumCuller` 负责近景精确剔除，二者互补。
- **已知局限（简单版可接受，进阶再补）**：圆锥测试**不处理遮挡**——墙后的方块/实体也会被判定"在视锥内"。村民近距离交互（精扫 range≤24、且多贴脸）遮挡场景少，首版可接受；进阶可加 `world.clip(new ClipContext(eye, target, ...))` 射线遮挡检测（见 §11 开放问题）。
- **实体 vs 方块统一**：`isInView` 同时适用于 `BlockPos` 和实体 `blockPosition()`，无需两套逻辑。

---

## 5. 模块三：两阶段建筑感知（新增，复用 `building_perception_design.md`）

把建筑感知拆成**两阶段（coarse-to-fine / LOD）**：先用极廉价方式**定位**"哪里有建筑、大概是什么"，路过即记忆；只有村民**真正进入**建筑时才跑昂贵的**体积精细解析**。绝大多数建筑只花 Stage 1 的成本，算力/延迟被严格限制。

全量建筑由 **共享 `WorldStructureIndex`**（§5.6）维护，只计算一次、所有村民共享；每个村民的个人 `ChunkMemory` 仅保留"我见过/进过哪些建筑 id"的轻量引用。单栋建筑用 `BuildingCandidate(detailed=false)` 存入共享索引，村民进场后升级为 `StructureRecord(detailed=true)`。

### 5.1 阶段一：BuildingLocator（廉价定位，路过即记录）

**目标**：以最低复杂度回答"这片区域有没有建筑、大概是什么"，**不做体积解析**。

**方法（按复杂度升序，可组合）**：

1. **原生 POI / 结构查询（近乎免费，优先）**
   - `world.getPoiManager().getInRange(PoiType.BED, pos, r)` → 床 POI 即"房屋"；`BELL`→村庄中心；`LIBRARY`/`BOOKSHELF`→图书馆。
   - `world.structureFeatureManager().getStructureAt(pos)` → 是否落在已注册结构（村庄/女巫小屋/要塞…）内。
   - 优点：O(范围) 查询、零方块扫描、对原生结构 100% 精确。
   - 局限：抓不到**玩家/村民自建且无 POI** 的房子。

2. **廉价表面/特征扫描（中等，覆盖自建）**
   - 只在**地表层**（地形高度向下探 1–3 层）做 2D/2.5D 扫描，识别"建筑签名"，比全体积体素化便宜约一个数量级：
     - **屋顶平面**：一片连续的 BUILDING 类方块水平面，位于地面之上、下方为空 → 屋顶。
     - **门方块**：`Blocks.DOOR` / `IRON_DOOR` → 强烈的"房屋入口"信号，扫描 chunk 即可定位。
     - **竖直墙段**：一段连续的 BUILDING 类竖直方块。
     - **夜间亮窗**：发光方块（火把/点亮窗户）在夜间是极廉价的建筑信号。

3. **密度启发**：chunk 内 BUILDING 类方块占比超阈值 → "建成区"。

**粗类型猜测（Stage 1 即可给出，够路过记忆用）**：
- `well`：水 + 围栏环；`barn`：大单间无内墙；`house`：床 POI + 门 + 墙；`wall`：细长 BUILDING 连续段；其余 `unknown`。

**输出 `BuildingCandidate`**：

```java
public class BuildingCandidate {
    public long chunkKey;
    public AABB coarseBounds;     // 粗略包围盒，定位 + 进场判定用
    public String coarseType;     // "house"/"barn"/"well"/"wall"/"unknown"
    public boolean detailed = false;  // 是否已细化
    public int confidence;        // 0-100，多信号叠加
}
```

写入共享 `WorldStructureIndex`（`detailed=false`）。村民**路过即记忆"这里有个房子"**（个人 `knownBuildingIds` 记 id），此阶段零体积解析。

### 5.2 阶段二触发：进入才跑（节流）

仅当 villager 的 `BlockPos` 落入某 `BuildingCandidate.coarseBounds`（AABB 测试，近乎免费）时，才对该候选跑 `StructureParser` 精细解析。Stage 1 先生成候选；精细解析推迟到**真正进场**才发生，不在每个 chunk 都跑整片扫描。

### 5.3 解析管线（照搬 design doc §4，代码化）

1. **体素化**：把目标区域（候选 `coarseBounds` 范围 + 高度覆盖地面±若干层）读进 3D 布尔栅格（实心/空/可站立）。
2. **2.5D 柱列** `[floorY, ceilY]`（支持错层 → 存自由区间列表）。
3. **自由空间 / 房间**：形态学膨胀闭合门洞 → 连通分量 = 房间（自由体积非立方体，用 footprint 2D 掩码 + height 表达）。
4. **墙/顶/地**：取房间自由体积**边界方向**（底面→地板，顶面→屋顶，侧面→墙），内部实心体归为障碍物。
5. **开口(门/窗)**：墙面缺口 + 连通性 + 通行高度测试（门=通行高度连通，窗=高处/不通行）。
6. **柱**：四周≥3 面邻接自由空间的竖直实体（结合生成期真值/材质上下文消歧）。
7. **非连续分组**：piece-first flood fill + 连接器(门洞/栅栏/路) + 空间关系阈值并查集 → structure id。自建房屋优先用**生成期真值**（放置时存 bbox+structure id，100% 精确、零成本）。

### 5.4 输出 `StructureRecord`（升级候选）

把 `BuildingCandidate` 补成完整 `StructureRecord`（`detailed=true`），并填充 rooms/openings 等，嵌入共享 `WorldStructureIndex`（按 `id` 合并，不重复）：

```java
public class StructureRecord {
    public String id;          // "house_1"
    public String type;        // house / barn / well / wall ...
    public int[] bboxCoarse;   // [minX,minY,minZ, maxX,maxY,maxZ] 仅粗筛
    public String orientation; // "facing SOUTH"
    public List<Opening> openings;   // 门/窗
    public List<RoomFootprint> rooms;// 房间足迹+高度
    public int columnCount;
    public boolean detailed = true;  // 已细化
}
```

与 design doc §6 的 JSON 对齐，但**以 chunk 为粒度存储**（一个 chunk 可能含 0~N 个 structure）。

### 5.5 节流/异步

- 结构扫描较重，建议**异步**（另起任务或合并到现有 LLM 异步模式），避免阻塞服务器主线程。
- 结果写回对应 `ChunkMemory.structures`，同一候选只解析一次（除非世界方块被修改，可加脏标记）。

---

### 5.6 共享世界结构索引（WorldStructureIndex）—— 谁来计算、如何定位玩家/山洞房

**问题**：让每个村民各自扫描世界找房子既重复又浪费；玩家自建房屋（尤其**山洞房**）没有村庄 POI、外壳是天然石头或山体，表面扫描与"放置事件聚集"都不可靠——玩家放的床可能跨 chunk、也可能只是室外的床，不能据此判属主或判房。

**房屋定义（house）**：
> 一个**连通的空气腔**，内部含 ≥1 张**床**，且**不与大气自由连通**（或仅通过**有限数量的门窗 / 小截面开口**与外部连通）→ 判定为一栋房屋。

这一定义天然解决四大痛点：① **床不属主、跨 chunk 无关**——不管谁放的床、是否和"主人"同 chunk；② **玩家自建**——任何材质、任何位置的封闭腔 + 床都是房；③ **山洞房**——山体挖空腔 + 床即房，外壳材质完全不参与判定；④ **室外床**——腔体极小或大面积连通大气 → 自动排除。

**方案**：把"哪里有建筑"提升为世界级**共享空间索引**，只计算一次，所有村民查询即可；房屋判定以"床 + 封闭性"为核心算法（见 ②），不再依赖放置事件聚集。

#### ① 索引结构（每维度一个 `WorldStructureIndex`）

```java
public class WorldStructureIndex {
    private final Map<Long, List<BuildingRecord>> byChunk = new HashMap<>();
    public List<BuildingRecord> queryNear(BlockPos c, int rChunks); // 半径内建筑
    public BuildingRecord getAt(BlockPos p);   // 哪栋建筑包含该点(AABB)
    public void add(BuildingRecord r); public void remove(long id);
    public void markDirty(AABB region);        // 玩家改动→置脏重扫
    public void save(CompoundNBT); public void load(CompoundNBT); // 跨重启持久化
}
```

- 村民不再各自存全量建筑；个人 `ChunkMemory.knownBuildingIds` 只留"我见过/进过 id=X"的轻量引用，细节回源共享索引。

#### ② 定位算法：床锚定 + 自适应侵蚀 BFS（递增外延半径 r，0.5 步进）

> **现成做法参考**：
> - **DoorClaim**（Fabric mod，1.20.1）：纯 3D flood-fill 找"完全封闭房间"（上限 500 blocks），靠 Minecraft「关闭的门/活板门/栅栏门是实心碰撞方块」自动当墙不泄漏。
> - **Indoors Core**（Bukkit）：door/fence=「leaky（泄漏）方块」，`locateleaks` 标泄漏点 →「开口=泄漏」。
> - **Roblox Grid Room Detection**：预计算每 cell 6「阻挡面」，只沿未阻挡面扩散，到边界=出口。
> - **Distance Transform / 形态学开运算**（OpenCV 标准）：空气块到最近实心距离，核心大、门窗薄层小，阈值/腐蚀断薄连接。
>
> **本方案 = Distance Transform 的「按需自适应版本」**：不预切全距离场阈值，而用 BFS 上限作成本闸，逐步递增外延半径 r 收紧房间边界——小房间一次停、泄漏嫌疑才重跑，比一次性全距离场更省。

**核心直觉（用户提出）**：从床做 BFS，若访问空气数到上限（如 500）说明要么房大、要么开口漏到外部。此时**不让 BFS 访问「与实体方块距离 ≤ r」的空气**（即把实体"膨胀"r 格当墙），重跑；r 从 0 递增（步长 0.5）到 3。开口薄/窄时，较小 r 就把开口缝堵死 → BFS 在 500 内停下 → 房子；开口 ≥6~7 格宽时，r 到 3 都堵不住 → 仍到上限 → 不是房子。**小房间 r=0 一次就停，绝不浪费。**

仍不依赖"放置事件聚集"，以床为种子：

1. **锚定床（POI 免费）**：`world.getPoiManager().getInRange(p -> p.is(PoiTypes.BED), center, radius, true)` 拿全部床坐标（村庄/玩家/山洞房床都覆盖）。每张未归属的床作种子。
2. **扫描包围盒 + 预计算距离场 `d(p)`**：以床取 `SCAN_RADIUS`(≈24) 格 AABB。对盒内每个空气块算 `d(p)` = 到**最近实心方块表面**的距离（欧氏或曼哈顿，允许半格；等价于把实体膨胀 0.5 后到其中心的距离）。**只扫描一次**，后续所有 r 的 BFS 复用 `d(p)`，不必每次重扫世界。
3. **自适应侵蚀 BFS（关键）**：
   - `r = 0` 起，步长 `STEP=0.5`，至 `R_MAX=3.0`；每次 BFS 访问空气上限 `BFS_CAP=500`（成本闸）。
   - 可通行条件：`d(p) > r`，但**床周围 `BED_IMMUNITY`(≈2) 格豁免**（不论 r 多大都通行，确保种子区不被外墙立刻挡死）。
   - 若某 `r` 下 BFS **未**触达 `BFS_CAP` → 立即停止，判**房子**；记录该 `r` 为「最小封口半径」（`≈2r` 宽的开口被封住 = 门窗尺度）。
   - 若 `r` 递增到 `R_MAX` 仍每次都触达 `BFS_CAP` → 判**不是房子**（开口宽 ≥2·R_MAX+1≈7 格，或腔体直接连通外部大空间）。
4. **判定细则与开口推断**：
   - 腔体容积 ≥ `MIN_ROOM`(≈8) 才算房间（排除极小腔/室外床）。
   - 封口半径 `r` 推断开口宽度：开口最窄处 ≈ `2r`（r=0.5 封 1 格门，r=1.0 封 2 格窗洞，… r=3 仍漏 = 开口 ≥7 格）。
   - 完全密封（r=0 就停）→ 房子（符合"封闭空气腔+床"）。
   - **多小窗累加**：多个 ≤3 格宽的小窗各自在 r=1~2 被分别封堵，BFS 在 r=2 即停 → 仍判房子（比"数咽喉≤6"更宽松合理）。
5. **记录**：`coarseBounds`=腔体 AABB、`seedBed`=床坐标、`sealRadius`=最小封口半径（推断门窗尺度），写入共享索引（`detailed=false`），待进场跑 Stage2。

**0.5 步进为何能解决「层高不足」问题（用户洞察）**：若房间只有 2 格高，空气到地板/天花板距离=1.0；当 `r` 递增到 1 时，整间房空气 `d(p)≤1` 全被挡 → 房"消失"。但用 `STEP=0.5`：`r=0.5` 时，只有「被两个实体夹在中间、到实体表面 ≤0.5」的薄缝（如 1 格宽门、墙缝）被封，**正常 2 格高房间的空气 d(p)≥1.0 > 0.5 仍可通行**；房间核心保住，仅门口薄层被剥。几何上等价于「实体膨胀 0.5 格：两个间距 1 的实体膨胀后中间空气被完全包住（0.5+0.5=1），而房间内空气距实体 ≥1 不被包」。故 0.5 步进同时实现「封门」与「留房」，是整数步进做不到的。

**需警惕的边界情况与对策**：
- **大封闭房间被误杀**：若 `BFS_CAP` 太小（500），一个合法但 >500 空气的大封闭房会在 r=0~3 都触达上限 → 误判"不是房子"。对策：① 把 `BFS_CAP` 设到"正常最大房容积"量级（如 2000~5000），泄漏因连通外部会远超并触发 r 递增，大封闭房受墙阻挡扩展到顶即停；② 或在 `R_MAX` 仍触顶时，进一步统计实际可达体积 `V`——若 `V` 逼近包围盒（≈连通外部）才判"不是房子"，若 `V` 中等（大房）则仍判房子；③ 设 `ROOM_CAP`(≈8000) 标记"超大楼/结构"另作处理而非否定。首版建议 `BFS_CAP=2000` 起步。
- **距离定义必须一致**：`d(p)` 用「到实心方块**表面**的欧氏距离」，半整数 r 才有意义；若用「到方块中心的曼哈顿距离」，墙-空气-墙缝隙的 d=1（中心距），0.5 步进将失效。务必统一。
- **成本**：最多 `R_MAX/STEP + 1 = 7` 次 BFS × `BFS_CAP`，但 `d(p)` 只算一次、各次 BFS 仅改阈值；小房间仅 1 次。比一次性全距离场（48³≈11 万格）或 Roblox 逐面标注都省。
- **床豁免别过大**：`BED_IMMUNITY` 取 2，避免豁免区绕过门口导致误判封闭。

**鲁棒性验证**：山洞房——隧道 1 格咽喉，r=0.5 即封堵 → 房子；室外床——腔体 1 块、四周 d 小，r=0 即触顶且 r=3 仍触顶（无墙可挡）→ 排除；凉亭/旷野——四周全开口、r=3 封不住 → 排除；跨 chunk 房——包围盒跨 chunk 读方块、泛洪无视边界整体找到；2 格高小房——r=0.5 保住房核心、仅剥门口 → 房子（整数步进会误杀，0.5 修正）。成本仅与房间体积成正比，只扫 ±24 格、不碰世界顶。

**多房间 / 合并（增强，非首版必需）**：同腔体其它床一并归属；或按 `sealRadius` 相近、AABB 相邻合并为同栋。首版单床单腔即可。

#### ③ 村民如何使用（不再各自计算）

- 想找房子：`WorldStructureIndex.queryNear(myPos, R)` → 拿候选列表（**含山洞房**）。
- 走进某候选 `coarseBounds` → 触发 Stage 2 `StructureParser` 细化（仅这栋、仅此人进场时）。
- 个人 `ChunkMemory.knownBuildingIds` 只存"我见过 id / 进过 id"的轻量引用。

#### ④ 与村庄原生 POI 的关系

- 世界加载时先用 `PoiManager` 的 `BED` POI **免费播种**全部床坐标（即 ② 算法的种子）；村庄房、玩家房、山洞房只要腔内有床，都会被 ② 的泛洪 + 封闭性判定统一捕获。`BELL`/`LIBRARY` 等 POI 仅作辅助标注（村心 / 功能房），原生 POI 是"免费种子"，泛洪 + 封闭判定才是判定主体。

#### ⑤ 增量更新（避免全图重扫）

- **世界加载**：POI 播种全部床 → 逐床泛洪建索引（一次性，后台**分帧**执行，避免卡顿）。
- **改动监听**：`BlockEvent` 放置 / 破坏床，或某 chunk 出现新床 / 腔体变化 → `markDirty(region)` 仅对脏区重跑受影响床的泛洪。
- **玩家挖山洞放床**：放床即被 POI 注册 → 下次增量扫描自动捕获，无需"放置陈设聚集"逻辑。

> **一句话总结**：以床为种子、用"自适应侵蚀 BFS（递增外延半径 r，0.5 步进）"判定封闭性，**统一覆盖村庄房、玩家自建、山洞房**，天然免疫"床跨 chunk / 床不属主 / 外壳是石头 / 泛洪泄漏 / 层高误杀"五大陷阱。放置事件聚集降为**可选加速器**（仅提前 `markDirty` 可疑 chunk、缩小扫描范围），不再是主路径。

## 6. 模块四：DetailedViewRecorder 视锥内精扫（新增 `ai/vision/DetailedViewRecorder.java`）

对**当前视锥内**的方块做"高细节"记录——这是用户要求的"对视野内方块做更详细记录"。

### 6.1 触发时机

-  villagers 与玩家对话前（已有 `buildEnvironmentSummary` 调用点）；
-  自主决策 LLM 调用前（未来 `generateNewGoals` 改造时）；
-  可设冷却（如每 200 tick 最多一次），避免高频。

### 6.2 记录内容（与 FrustumCuller 协作）

1. 用 `FrustumCuller` 对 villager 前方 `range` 范围内的候选方块做剔除，仅保留视锥内格子。
2. 对视锥内方块**逐块/抽样**读取，筛选"显著物"写入 `BlockObservation`：
   - 可交互：`crafting_table` / `furnace` / `chest` / `bed` / `farmland`(有作物) / `door`
   - 资源：`ore`(裸露) / `log` / `crop`(成熟)
   - 危险：`lava` / `cactus`
3. **实体检测**（当前系统完全缺失）：在视锥范围内用 `world.getEntities()` 过滤 `LivingEntity`，记录敌方（僵尸/苦力怕）、中立（动物）、其他村民、掉落物。这是"看见身边有怪物"的关键补齐。
4. 视锥结果**临时**用于本次 LLM 上下文（高优先），并挑选最显著的少数写入 `ChunkMemory.notableBlocks` 长期记忆（受 `saliency` 配额限制）。

```java
public static ViewSnapshot record(VillagerAgentData agent, LivingEntity villager, ServerWorld world) {
    Vec3 eye = ...; Vec3 fwd = FrustumCuller.forwardVector(villager.yRot, villager.xRot);
    List<BlockObservation> blocks = new ArrayList<>();
    List<EntityObservation> entities = new ArrayList<>();
    // 遍历前方 range 体素 → FrustumCuller.isInView → 显著筛选 → 记录
    return new ViewSnapshot(blocks, entities);
}
```

---

## 7. `VillagerVisionSystem` 改造

`buildEnvironmentSummary()` 重写为**分层组装**（保留现有时间/天气/biome 段落，替换下方逻辑）：

```
[时间/天气/biome]                       （保留）
[精确坐标 + 当前 chunk]                  （保留）
[Chunk 记忆摘要]：
  - 描述已访问 chunk 的主导内容（"北边 (7,-21) 是森林，有房子；
    (8,-20) 是农田"），来自 chunkMemories 的 tags + structures
[视野内结构]：
  - 若当前/前方 chunk 有 StructureRecord，简述（"前方那栋朝南木屋，南墙有门"）
[视锥精扫细节]：
  - 来自 DetailedViewRecorder：眼前 3 格有工作台、2 格有成熟小麦、
    左侧苦力怕（来自实体检测）
[实体]：同上说
```

- 移除原有的 `countNearbyLogs` / `detectCave` / `detectWater` 随机采样（其职责被 `ChunkContentSampler` + `FrustumCuller` 取代），或保留 `detectCave` 作为 chunk 采样里的 `cave` tag 判定。
- `getInSightChunksDescription` 保留作为远景补充（粗糙 4-chunk 视野）。

---

## 8. 数据流与调用时机

```
世界级（一次性，所有村民共享）:
  WorldStructureIndex 由床锚定 + 泛洪判定维护，村民不各自计算:
    └─ 世界加载: PoiManager BED 免费播种床坐标 → 逐床有界空气泛洪 → 通天/开口数判封闭性 → 写入索引
    └─ 封闭空气腔 + 床 = 房子(覆盖村庄房/玩家自建/山洞房)
    └─ 村民进场某候选 coarseBounds → 触发 Stage2 StructureParser 细化(detailed=true)
    └─ BlockEvent 放置/破坏床 → markDirty(region) 仅脏区增量重扫
    └─ PlayerBuildMonitor(可选加速器): 仅提前 markDirty 可疑 chunk，缩小扫描范围

每 tick（轻量）:
  VillagerAgentData.updateChunkMemory(cx,cz)
    └─ 首次进 chunk → ChunkContentSampler 采样 → 写 ChunkMemory(地形/tags)
    └─ 查询共享 WorldStructureIndex.queryNear → 把见到的建筑 id 记入个人 knownBuildingIds
    └─ 进入某建筑 coarseBounds → 触发该栋 Stage2 StructureParser 细化(detailed=true)

对话 / 决策前（按需）:
  VillagerVisionSystem.buildEnvironmentSummary()
    └─ 读个人 chunkMemories 摘要 + 共享索引中附近建筑(含山洞房)
    └─ DetailedViewRecorder.record()（FrustumCuller 剔除 → 精扫 + 实体）
    └─ 组装分层文本 → 注入 LLM prompt
```

---

## 9. 性能预算

| 操作 | 频率 | 成本 | 控制 |
|------|------|------|------|
| ChunkContentSampler | 每 chunk 首次进入 | 2048 次 `getBlockState`（16×16×8 slab）+ 1 次范围实体查询 | 一次性缓存，不重复；< 1 ms |
| StructureParser | 仅 building 类 chunk | 较高（体素化+膨胀） | 异步 + 每 chunk 一次 + 脏标记 |
| FrustumCuller 测试 | 精扫时 | O(候选方块) | range≤24，圆锥剔除天然限流 |
| DetailedViewRecorder | 对话/决策前 + 冷却 | O(range³) 过滤后少量 | 冷却 200 tick；只记显著物 |

内存：512 chunk ×（tags 字符串 + 少数 structure/block 记录）≈ 可控；`saliency` 淘汰机制防膨胀。

---

## 10. 落地步骤 / 文件清单

| 步骤 | 文件 | 动作 |
|------|------|------|
| 1 | `ai/memory/ChunkMemory.java` | 新增：ChunkMemory / BlockCategory / BlockObservation / BuildingCandidate / StructureRecord 数据结构 |
| 2 | `ai/vision/ChunkContentSampler.java` | 新增：规则网格采样 + tags 生成 |
| 3 | `ai/vision/FrustumCuller.java` | 新增：前向向量 + `isInView` 视锥测试 |
| 4 | `ai/vision/BuildingLocator.java` | 新增（Stage 1）：POI/结构查询 + 表面扫描 → `BuildingCandidate(detailed=false)` |
| 4b | `ai/world/WorldStructureIndex.java` | 新增（§5.6）：共享空间索引 queryNear/getAt/add/remove/markDirty + 持久化；核心算法 = 床 POI 播种 → 有界空气泛洪 → 通天/开口数封闭性判定（统一覆盖村庄房/玩家自建/山洞房） |
| 4c | `ai/world/PlayerBuildMonitor.java` | 新增（§5.6，可选加速器）：监听放置/破坏事件，仅用于提前 `markDirty` 可疑 chunk 缩小扫描范围；不再作主路径 |
| 5 | `ai/vision/StructureParser.java` | 新增（Stage 2）：进入 `coarseBounds` 才跑，落地 building_perception_design §4 管线 → `StructureRecord(detailed=true)` |
| 6 | `ai/vision/DetailedViewRecorder.java` | 新增：视锥精扫 + 实体检测 |
| 7 | `ai/VillagerAgentData.java` | 改造：`visitedChunks` → `chunkMemories`；`updateChunkMemory` 接入采样 + Locator；NBT 序列化扩展 |
| 8 | `ai/VillagerVisionSystem.java` | 改造：`buildEnvironmentSummary` 分层组装；移除/降级随机采样 |
| 9 | （可选）建造系统 | 放置方块时写生成期 structure 真值，供 StructureParser §4.9 直接读取 |

---

## 11. 开放问题

- **pitch 来源**：已解决——用 `LivingEntity.getLookAngle()` 直接拿到含俯仰的单位前向向量，无需手读 `xRot`；村民通常平视，pitch 贡献小但保留。
- **视锥遮挡（进阶）**：`isInView` 是纯圆锥测试，**不处理遮挡**（墙后物体也会被判定可见）。首版可接受（精扫 range≤24、且多贴脸）；进阶加 `world.clip(ClipContext)` 射线遮挡检测，或在 `DetailedViewRecorder` 里对候选做线段遮挡剔除。
- **Stage 1 定位精度**：POI/表面扫描给出的是"粗略包围盒 + 粗类型"，可能与真实建筑有偏差；进门后的 Stage 2 会重新体素化校正，偏差不影响最终精度，只影响"进场判定"的灵敏度——`coarseBounds` 建议适当外扩 1–2 格避免漏触发。
- **自建建筑 POI 缺失**：Stage 1 的 POI 查询抓不到无 POI 的自建房屋，需靠表面扫描兜底；建议建造系统在放置床/门时注册 POI，使原生查询也能覆盖村民自建。
- **StructureParser 性能**：整片扫描体素化在 24³ 区域约 1.3 万格，单 chunk 异步可接受；因仅在进场时触发，并发压力远低于"每栋都扫"，但仍建议加全局节流。
- **实体检测范围**：视锥内实体 vs 全周围实体——建议精扫用视锥、长期记忆用周围半径（§3.2.2 `sampleEntities` 已用 24 格半径），分层处理。
- **chunk 记忆的 y-slab 盲区**：站立层 ±3.5 格之外的树冠/地下/高层结构由 §6 视锥补扫 + §5.6 房屋索引补足；若后续需要"记住高处岩柱/矿脉"，可把 y-slab 扩展为"站立层 + 局部向上探 6 格找叶子/原木"，成本仍可控。
- **与既有 `building_perception_design.md` 的 P1–P8 开放问题**仍适用（室内/室外判别、半砖占据、柱语义消歧等），实现 Stage 2 时需一并处理。

---

> 本文档为**设计草案**，聚焦"要改什么、怎么改"。下一步可挑 P0（步骤 1–3 + 7 的 chunk 内容记忆 + 视锥剔除 + BuildingLocator）先行落地，验证后再做 StructureParser（步骤 5）与 DetailedViewRecorder（步骤 6）。

---

## 附录 A：落地时的实现偏差（v3，已对照 `vision_module_implementation.md`）

下面是实际代码相对本草案的**有意偏离**，记录以免文档与实现互相打架：

1. **ChunkMemory 不存逐方块 `notableBlocks` / `entities` 列表**
   本草案 §3.1 / §3.2 写的是「把显著方块塞进 `cm.notableBlocks`」。落地改为：扫描结果**原地坍缩**为「标签位掩码（`ChunkTag`）+ 特征位掩码（`ChunkFeature`，每类只存 1 个代表坐标）+ 分类计数 + 实体计数」。即用户反馈的"不用存两千个格子的信息，只需要给 chunk 打上有什么重点方块/内容的标签"。`BlockObservation` / `EntityObservation` 仅存活于近景 `DetailedViewRecorder`，不进长期 chunk 记忆。

2. **床播种不走 `getPoiManager().getInRange(PoiType.BED, …)`**
   1.16.5 的 POI 类型/API 与 §5.6 ① 的假设不符（且无 `PoiType.BED` 这种直接用法）。落地改为 `BuildingLocator.bedsInChunk(IChunk)`——直接读 chunk 的**方块实体表**取床，零扫描；并由 `ChunkEvent.Load` 与放置/拆除床的 `BlockEvent` **事件驱动**地播种（§5.6 ⑤ 的"玩家挖山洞放床即被捕获"由拆除事件覆盖）。

3. **侵蚀距离场 3D → 逐层 2D 水平（v3；已被 v4 取代，见第 6 条）**
   本草案 §5.6"0.5 步进能解决层高不足"的推理在 **3D** 距离场下其实不成立：地板/天花板距房内每个空气格仅 0.5，会在 `r=0.5` 把整间 2 格高房蚀没。落地改用**逐层 2D 水平**距离场：地板/天花板不参与侵蚀，2 格高房间的空气 `d(p)≥1.0 > 0.5` 仍可通行，仅门口薄层被剥；水平窄开口（门/窗/隧道口）仍按整数阈值封死。这样"0.5 步进封门留房"的语义才真正成立。**⚠️ 此方案在 v4 被完全重写为分水岭，见第 6 条。**

4. **索引持久化 + 每 tick 队列泛洪，取代周期性 `rebuildAround`**
   `WorldStructureIndex` 已通过 `StructureIndexSavedData`（`WorldSavedData`）把 buildings + claimed/rejected/pending 三组床坐标全部 **NBT 持久化**，重启不重泛洪。原 §5.6 ⑤ 的"后台分帧"由 `processPending(world, FLOODS_PER_TICK=1)`（每世界 tick 最多 1 次泛洪）取代原先每 6000 tick 的暴力重建。

5. **附带说明：第 1–4 条（含 v3 的全部修正）均已落在 `vision_module_implementation.md` 的对应章节**，本文档只记录"为何偏离草案"，不重复实现细节。

6. **v4：用「3D 距离场 + 分水岭房间分割」取代「自适应侵蚀 BFS」（用户决策）**
   触发：用户在 review 时发现"床在大房子的某间小屋里，泛洪只返回小房间"——自适应侵蚀 BFS 的模型是"一床→一个封闭空腔"，大房子整屋连通体积 > `BFS_CAP` 时会被迫封口、只留下小房间。
   用户拍板改用**距离场 + 分水岭（watershed）房间分割**：
   - 用户选定 **0.5-block 细分**：每方块切 `SUBDIV=2` 采样（8 子体素/块），房间中心 1m 粒度足够算 AABB，内存/算力只有 27 分版本的 1/27。
   - 大气判定采用**用户方案的"长串空气"思路**，但落地时补了一条更可靠的"通天列"主判据：一个 basin 含**通天格**（空气格正上方整列到盒顶都是空气）= 大气；`OR` 该 basin 内"大气方块"（某方向正反两侧都 ≥`AIR_RUN=4` 串空气）占比 > `BIG_AIR_FRACTION=0.85` → 大气。纯通天判定可用 `BIG_AIR_FRACTION=1.0` 关掉补充信号。
   - 为什么能回到 3D：旧版 3D 距离场用于"侵蚀阈值"会在 `r=0.5` 蚀没 2 格高房；新版 3D 距离场只用于"找局部极大值 + 生长 basin"，不涉及侵蚀阈值，所以 3D 没问题。
   - 效果：分水岭把整屋切成多个房间 basin，再合并所有**非大气** basin 的包围盒 = **整栋屋**，彻底解决"只返回小房间"。
   - `WorldStructureIndex` / 事件播种 / `StructureIndexSavedData` 持久化那套接线**完全不变**。代码已 `compileJava` 通过。

