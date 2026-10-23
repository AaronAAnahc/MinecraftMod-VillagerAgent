# 床 → 房间 搜索算法实现说明（以实际代码为准）

> 配套文档：`docs/building_detection_implementation.md`（落地版实现记录）、`docs/building_perception_design.md`（关系型结构解析的抽象方案）。
> 本文以**实际代码**为主，逐层描述"给定一个床，如何找到它所在的封闭房间/建筑"这一搜索链路的真实实现，并标注与上层消费的对接点、已知坑。

> **修订记录（2026-08-16）**：`BuildingLocator.locateBed` 算法已按以下四点重构（代码为唯一权威）：
> 1. 植物方块（草/树苗/花等 `Material.PLANT` / `REPLACEABLE_PLANT`）不再算实心墙/屋顶（新增 `NON_SOLID_MATERIALS` 排除表）。
> 2. 床是两格：检测床两半，标记全部子栅格为床并作洪泛种子；`solid` 中床格置非实心，使距离场不以床为墙（**距离场忽略床**）。
> 3. **大气判定 = `skyOpen ∪ longRun`（两条独立测试 OR，`longRun` 保留但阈值收紧）**：`atmospheric[c]` = 满足 `skyOpen`(竖直列通天) **或** `longRun`(某轴双向严格大于 `AIR_RUN` 的连续空气) 的空气格。此前 `AIR_RUN=12` 会把封闭的"大房间/长走廊"误判成大气（中心格天然满足长程阈值，再经 `growBigAir` 扩散）；现 `AIR_RUN` 由 12→16 且改为**双向都严格大于**阈值（单边缺口不算），普通封闭房间（≤16 格宽）不再命中 long-run，室内大空间不再被误标（2026-08-16 修复）。⚠️ 注意：`longRun`/`computeBigAir`/`growBigAir`/`AIR_RUN` **均保留在代码中**（见 §2.1 与 `BuildingLocator.java`），并非删除。
> 4. 分水岭改为**同步双类别测地线 BFS**（见 §3）：室内(非大气)种子与室外(大气，取 `D==max(室内种子D)`)种子各从距离 0 每轮扩一步，相遇等距处即边界。室外种子若不在恰好的 `D==maxInteriorD` 环上，则**向下扫描取最大的 `D<max` 大气格**（实在没有再向上扫），避免从远处大气灌入。最终**用大气占比(`BIG_AIR_FRACTION`)判定哪一坨是室内/室外**：占比低的一坨是房间，高的是大气。原 Meyer 优先级队列(by D 降序)实现已移除。
>
> 涉及源码：
> - `ai/vision/BuildingLocator.java` —— 单床 → 建筑记录（距离场 + 同步双类别测地线分水岭）
> - `ai/world/WorldStructureIndex.java` —— 全维度建筑索引、事件驱动失效、队列式重扫
> - `ai/world/BuildingRecord.java` —— 建筑记录（支持多床）
> - `ai/world/StructureIndexSavedData.java` —— 索引持久化（`WorldSavedData`）
> - `ai/VillagerVisionSystem.java` —— 村民消费方（Mid 层 queryNear）
> - `ai/VillagerActivitySystem.java` —— resting 行为（注意：仍走原版 HOME POI，见 §7）
> - `events/VillagerEventHandler.java` —— Forge 事件订阅 → 索引失效触发

---

## 1. 算法边界：这里的"床-房间搜索"指什么

在本项目中，"床-房间搜索"**不是**村民为自己找一张床来睡觉的寻路（那是原版 Minecraft 的 `MemoryModuleType.HOME` POI 机制，见 §7）。

它指的是：**给定世界中任意一张床的方块坐标，反推出"这张床所在的、被墙围起来的封闭房间/建筑"是什么样**（粗粒度 AABB、类型、多床集合）。即"以床为锚点，向外探测封闭空间"的几何搜索。

整条链路分两层：

```
事件/加载 (ChunkEvent / BlockEvent)
        │  offerBed / indexChunk / onBlockChanged / onBedRemoved
        ▼
WorldStructureIndex  ──(每 tick processPending: FLOODS_PER_TICK=1)──►  BuildingLocator.locateBed(bed)
   byId / byChunk / claimedBeds / rejectedBeds / pending                     │ 距离场 + 同步双类别测地线分水岭
        ▲ 一次洪泛，一床只扫一次                                            ▼
        └────────────────────────── add / remove / markDirty ──►  BuildingRecord (多床)
   (持久化到 StructureIndexSavedData)
                                                        │
                                                        ▼
                                              村民消费：queryNear() / getAt()
```

- **`BuildingLocator`** 回答"给定一张床，它所在的封闭建筑长什么样"（粗粒度：AABB、类型、多床集合）。它**从不对世界做周期扫描**，床由调用方喂入。
- **`WorldStructureIndex`** 回答"世界里有哪些建筑、任意坐标属于哪栋"，并把发现过程做成**事件驱动、队列式、一次扫一次**的增量索引，跨重启持久化。
- 村民侧（`VillagerVisionSystem` / `VillagerAgentData`）只调用 `queryNear` / `getAt`，**自己从不对世界做洪泛**。

---

## 2. 核心算法：`BuildingLocator.locateBed`

入口：`public static BuildingRecord locateBed(World world, BlockPos bed)`（`BuildingLocator.java` 第 132 行）。

### 2.1 关键常量

| 常量 | 值 | 含义 | 位置 |
| --- | --- | --- | --- |
| `REACH_CAP` | `16` | `hasOverheadCover` 向上查屋顶的封顶层数（仅 Step 0 用） | 第 71 行 |
| `MAX_SCAN_BLOCKS` | `2000` | **总扫描预算（世界方块数，非子格）**。扫描盒由该预算推导（见 `deriveBoxDims`），竖直偏置使细高建筑（教堂塔楼）仍能源到顶；子格数 = 2000×SUBDIV³(×8)。唯一体积旋钮，不再设长/宽/高 | 见常量区 |
| `SUBDIV` | `2` | 每方块轴 2 个采样点 → 0.5 格分辨率（每块 8 子体素） | 第 68 行 |
| `AIR_RUN` | `16` | **长程空气阈值（子格，=8 格）**。某轴**两向都严格大于** `AIR_RUN` 连续空气才算 long-run（见 Step 5）。由 12→16：普通封闭房间不再命中，仅真正开阔空间命中 | 第 75 行附近 |
| `BIG_AIR_FRACTION` | `0.85` | Step 7 按**大气占比**判定哪一坨是房间——占比 **< 0.85** 的 blob 为室内(房间)、**≥ 0.85** 为露天大气 | 第 72 行 |
| `MIN_ROOM` | `8` | 房间块数少于此 → 不算房子，返回 `null` | 第 73 行 |
| `ATMOSPHERE_HALO` | `2` | Step 3 flood 大气穿透上限：从封闭空气跨入露天空气后，最多再扩 2 格大气即停（见 Step 3） | 第 87 行附近 |
| `NON_SOLID_MATERIALS` | `Material.PLANT`, `Material.REPLACEABLE_PLANT` | 植物方块排除表：草/高草/蕨/树苗/花/死灌木等不算实心墙/屋顶（见 §2.3） | 第 88 行附近 |

> **大气判定（2026-08-16 修订）**：`longRun` 启发量**保留**，`AIR_RUN` 由 12 调高到 16，且改为**两向都严格大于**阈值（单边 >6 格、另一边 12 格这种单边缺口不算长程）。这样普通封闭房间（≤16 格宽）不再命中 long-run，不会被判成大气；只有真正开阔空间（或真开口）才命中。`computeBigAir`/`growBigAir` 随之保留。

### 2.2 执行步骤（函数内编号对应注释与代码行）

**Step 0 — 顶部遮挡预筛 `hasOverheadCover`**（新签名：`List<BlockPos>` 床两半）
先 `collectBedBlocks` 取床两半（给定半格 + 水平相邻仍为 `BEDS` 的格），沿**每个**床半格正上方 `REACH_CAP` 层查非空气非液体块（玻璃也算顶），任一有顶即通过；全无 → 直接 `return null`（露天床，便宜早退）。

**Step 0.5 — 预算推导建盒（单一体积旋钮，不再设长/宽/高）**
不再用 `estimateReach` 自适应估距，也不设独立的水平半边长 / 竖直跨度常量。`MAX_SCAN_BLOCKS`(2000) 是唯一体积旋钮：`deriveBoxDims(2000)` 以**竖直偏置**推导盒尺寸——`SY = √(预算)`（占主导，让细高建筑教堂塔楼仍能源到顶），`X/Z` 均分余量。`locateBed` 中盒**水平居中于床**、**竖直锚在床下方一格向上生长**（含地板、向上到顶），并 clamp 到 [0,255]。例：预算 2000 → 盒约 `6×44×6`（子格 `12×88×12`≈12672，远小于原来的 865 万）。代价是宽大房屋（>~6 格宽）会被截，但总扫描量被牢牢限制。

**Step 1 — 逐块实心度**
在自适应盒内（`xMin..xMax` / `yMin..yMax` / `zMin..zMax`）每方块一次 `getBlockState`，调用 `isWall` 判定实心，存入 `blockSolid[SX][SY][SZ]`。**床格本身强制非实心**（家具不是墙）。

**Step 2 — 下采样子栅格**
`SUBDIV=2` 把每方块扩成 2×2×2 子体素（0.5 格分辨率，对应设计文档要的"8 子体素/块"）。线性化成一维数组 `solid[N]`（`N = LX*LY*LZ`），`neighbors()` 只算 6 邻接、越界返回 -1。
额外建 `bedMask[N]`：把床两半的每一子体素都标为床，并把 `solid` 中对应格置 `false`（确保距离场不以床为墙）。

**Step 3 — 3D 连通空气洪泛（带大气穿透上限）**
从 `bedMask` 的床子格（非实心）出发 seed `inC`（`atmoBudget=MAX_VALUE` 表示"室内、不限步"），`ArrayDeque` + 6 邻接 flood。**关键 cap**：一旦 flood 从"封闭室内空气"跨入"露天空气"（`openSkyUp[n]` 为真，即该格竖直列在扫描盒内无实心方块），就给这个大气格一个 `ATMOSPHERE_HALO`（默认 2）的预算，此后每再往外走一格大气就 `−1`，预算 ≤1 时**停止继续往大气里扩散**。于是 `inC` 被限制在"房间本体 + 每个开口外约 2 格的薄壳"，不再灌满整片天空——既加速 flood，也让调试视图从"一大团青色云"变成"门口一圈薄壳"。
- 完全封闭（无开口、无露天格）的房间：所有格 `atmoBudget=MAX_VALUE`，照常整屋 flood，cap 不触发。
- 带玻璃窗的开口：玻璃是实心（`isWall` 为真），列被截断 → 窗后空气不算 `openSkyUp` → 仍走室内分支，不泄漏。
- 床全被掩埋（无床子格可 seed）→ `return null`。
- `openSkyUp` 在 flood 前、仅基于 `solid` 算一次（不依赖 `inC`，故可前置），Step 5 的 `skyOpen = openSkyUp && inC` 直接复用，省掉一次重复列扫描。

**Step 4 — 3D 距离场**
多源 BFS：所有 `solid[i]` 入队 `D[i]=0`，向空气格扩散，`D[i]` = 到最近墙的**测地（图）步数**。因为床格已非实心，**床不参与距离源 → 距离场忽略床**（否则床会在房间中央打出一处伪"近墙"凹陷）。封闭空间离墙最远点 = 房间中心（分水岭种子来源）。

**Step 5 — 大气信号（`skyOpen` ∪ `longRun`）**
- `skyOpen[i] = openSkyUp[i] && inC[i]`：沿列从顶向下扫，某空气格上方竖直通透到天空（扫描盒内无实心块）→ 标记通天（可靠"露天"信号）。`openSkyUp` 只依赖 `solid`，在 Step 3 洪泛前算好、Step 5 复用。
- `longRun[i]`（来自 `computeBigAir`）：某轴**两向都严格大于 `AIR_RUN`(16 子格=8 格)**的连续空气才算长程（单边缺口不算，见 §2.1）。`AIR_RUN` 由 12→16 后，普通封闭房间（≤16 格宽）中心格两向都不足 8 格 → 不命中；只有真正开阔空间才命中。
- `bigAir[i] = inC[i] && (skyOpen[i] || longRun[i])`，再经 `growBigAir` 区域生长（6 邻接中 ≥4 个是 bigAir 则扩散，闭合小缝隙使室外连成一片）。于是**大气 = 通天格 ∪ 长程空气格**：封闭大房间/长走廊内部连不到天、也不满足长程 → 不是大气；只有门口/窗/天窗等真开口或真正开阔空间才命中。

**Step 6 — 同步双类别测地线分水岭**（详见 §3）
区域极大值（连通等-D 平台）作种子，分室内(非大气)/室外(大气)两类；室外种子取 `D==max(室内种子D)` 的大气格，使两 front 在门窗交汇（该距离环上无大气格时，向下取最大 `D<max` 的大气格，再不行向上扫）。多源 FIFO BFS，每个种子从距离 0 起每轮扩一步，相遇**等距**处即边界。

**Step 7 — 按大气占比判定室内/室外并集房间**（原 `basin[i]` 概念改为 `label[i]` + `boundary[i]` + 大气占比）
先统计室内 blob 与室外 blob 各自的大气占比（`atmospheric[i]` 的 cell 数 / blob 总 cell 数）。占比 **< `BIG_AIR_FRACTION`(0.85)** 的一坨判定为**房间**，另一坨为露天大气；两坨都高/都低时取占比更低者，平局归室内。被判定为房间的 cell（非边界）并集出 AABB（`roomBlock[][][]` 去重计数）。`roomBlocks < MIN_ROOM` → `return null`。

**Step 8 — 包围盒外扩 + 类型**
AABB 在 **±x / ±y / ±z 各 +1**，把 1 格厚墙体壳包进屋子；**不再 clamp 回扫描盒**——扫描盒只是空气采样范围，包络必须始终外扩一格包裹内部空气（床贴墙时空气贴到盒边、+1 被截断会致该角只相接不包围）。仅 Y 夹到世界边界 [0,255]，X/Z 不受扫描盒限制。
`coarseType` 由 `classifyType` 给出：房间外接实心块 ≥50% 嵌入岩石（6 邻居中 ≥5 实心）→ `cave_house`，否则 `house`。

返回 `BuildingRecord(id, seedBed, 外扩后 bounds, sealRadius=maxRoomD/SUBDIV, roomBlocks, type)`。
`id = bed.asLong() & 0x7FFFFFFFFFFFFFFFL`（每张床唯一）。

### 2.3 阻挡物定义（`isWall` / `isRoof`）

```java
private static final Set<Material> NON_SOLID_MATERIALS = new HashSet<>(Arrays.asList(
        Material.PLANT, Material.REPLACEABLE_PLANT
));
private static boolean isWall(BlockState st) {
    Material m = st.getMaterial();
    if (m == Material.AIR || m.isLiquid()) return false;            // 空气、液体可穿过
    if (NON_SOLID_MATERIALS.contains(m)) return false;              // 植物方块不算实心
    if (st.getBlock().isIn(BlockTags.CLIMBABLE)) return false;      // 梯子/藤蔓：房屋判定视作透明
    return true;
}
private static boolean isRoof(BlockState st) {
    return isWall(st);   // 屋顶与墙同义
}
```

规则：**空气、液体、植物方块可穿过；其余（玻璃、树叶、石、木…）算墙/可遮挡**。

- ✅ **植物方块排除表** `NON_SOLID_MATERIALS`：`Material.PLANT`（树苗/花/死灌木/甜浆果丛…）、`Material.REPLACEABLE_PLANT`（草/高草/蕨…）——这些是草之类的植物方块，**不再算实心墙/屋顶**，房间里的草坪、房子外的树篱不会分割或封死空腔。要加更多放行项，往这个 `Set` 里加即可。
- ✅ **梯子类方块**（`BlockTags.CLIMBABLE`：梯子、藤蔓等）——**视作透明**，房屋判定不挡空气洪泛（细高塔楼的梯子/楼梯不打断空腔）；同时也**不算屋顶**，若床正上方只有梯子则仍会被 `hasOverheadCover` 判为露天。
- ✅ **花盆**（`Material.DECORATION`）——非空气非液体且不在排除表 → 仍算墙，洪泛被挡（与之前一致）。
- ✅ 箱子、台阶、火把、牌子、地毯、铁轨等装饰块同样被算作墙（通常无害）。
- ⚠️ 反向提醒：玻璃、树叶这类"透明但实心"的块**仍**算墙（设计决定）；若日后想让薄装饰件/植物之外也穿过，往 `NON_SOLID_MATERIALS` 加对应 `Material` 即可。

---

## 3. 距离场分水岭（同步双类别测地线）详解

> 2026-08-16 重构：原 Meyer 优先级队列(by D 降序)实现已移除，改为下方**同步双类别测地线 BFS**。

### 3.1 为什么改成"同步双类别"

把 `D` 当地形海拔：离墙越近海拔越低，房间中心/室外中心海拔最高。

- **旧版痛点**：按 `D` 降序出队（优先队列），室外开阔区 `D` 最大、先被处理，经门口把近门室内细胞抢先认领 → 床掉进大气盆地，房间被吞。
- **新版思路**：室内(非大气)种子与室外(大气)种子**都从距离 0 起步、每轮各扩一圈**（FIFO 多源 BFS）。这样当两个不同种子的 front 相遇时，**二者到各自种子的测地距离相等** → 相遇点天然落在"等距脊线"上，即门窗交界处。相遇 cell 标为 `boundary`（排除）。

### 3.2 种子与类别（Step 6a）

1. 扫描 `inC` 内 `D` 的**区域极大值**（连同等-D 连通平台 = 一个种子盆地/plateau）。
2. 每个 plateau 判定类别：若其任一 cell 为 `atmospheric`（连到天）→ **室外/大气(exterior)**；否则 → **室内(interior)**。
3. 计算 `maxInteriorD = max(D among all interior seeds)`。

### 3.3 室外种子的关键修正（Step 6b）

> 你强调的点：室外**不**从"大气里 D 最大的那格"起步（那样起始位置离房屋太远），而取 **`D == maxInteriorD` 的大气方块** 作为室外 front 种子。这样室外 front 与室内 front 处于相近的"离墙距离"，二者才会在门窗处交汇，而非在开阔地深处相遇。

- 取所有 `inC && atmospheric && D == maxInteriorD` 的 cell 作室外种子（同一距离环，保证 coherent）。
- **兜底（你指定的修正）**：若该距离环上没有任何大气 cell，则**向下扫描取最大的 `D < maxInteriorD` 的大气格**作室外种子（这些格就在墙外紧贴门口，仍能让两 front 在门窗交汇）。一般来讲不可能没有 `D<max` 的大气格；若连 `D<max` 都没有（极罕见），则**从 `D=maxInteriorD` 开始向上扫**，直到某个 `D` 存在大气格，从该距离环起步。**不再**退回"把所有大气 plateau 都作种子"——那样种子会从远处大气起步，front 经门口灌入吞掉房间。
- 若无任何室内种子（整片开阔）→ 全部大气，房间数 0 → `return null`。

### 3.4 同步 BFS 与边界（Step 6c）

```
label[] = -1, dist[] = -1, boundary[] = false
所有种子入队 seedQ，label=各自id，dist=0   // 室内每 plateau 一个 id；室外共用 exteriorLabel
while seedQ 非空:
    cur = poll(); cl = label[cur]; cd = dist[cur]
    for n in 6邻接(inC):
        if label[n] == -1:  label[n]=cl; dist[n]=cd+1; enqueue(n)   // 首次认领
        else if label[n] != cl && dist[n] >= cd:  boundary[n] = true // 异源等距相遇 = 边界
```

- **大气种子集 ∩ 非大气种子集 的交界 cell** → `boundary` = **房屋 / 大气 界面**（门口/窗洞）。
- **室内种子 ∩ 室内种子**（不同 interior plateau）的交界 → `boundary` = **室内门 / 窗**。
- 因每个种子从 0 起、FIFO 每轮扩一步，相遇 cell 的 `dist` 对两源相等，边界精确落在脊线（**确认：是的，确定区域极大值种子后，室内与室外都标记了，BFS 每个种子每轮只往外走一步，相遇处二者到各自种子的距离相似**）。

### 3.5 室内/室外判定（Step 7）—— 按大气占比

同步 BFS 只负责**找到边界**（哪条 cell 是房屋/大气交界）。具体"哪一坨是房间"由**大气占比**决定（你指定的偏好）：

- 统计 `interior` 集合（被室内种子到达、非边界）与 `exterior` 集合（被 `exteriorLabel` 到达、非边界）各自的大气占比 = `atmospheric` cell 数 / 集合总 cell 数。
- 占比 **< `BIG_AIR_FRACTION`(0.85)** 的一坨 = **房间**；另一坨 = **露天大气**。
- 两坨都高（都像大气）或都低（都像室内）时为歧义：取占比更低者作房间；平局归室内（种子本就从非大气极大值出发）。
- 房间 = 判定为室内的那一坨（非边界），并集出 AABB；`boundary` 与另一坨排除。

> 用占比判定比纯靠种子类别更稳：即使某些室内极大值意外落在大气格、或种子类别判反，占比这一数据驱动的信号能纠正。边界仍由等距相遇保证落在门窗脊线。

### 3.6 固有局限

若洞连通的是**有顶暗格**（非 sky、且连不到天），两个室内盆地都合格，Step 7 一并并入 AABB → 仍可能溢出（验证脚本 Scenario E）。根治需另加**连通颈宽判定**（门口 ≥2 格才算门，1×1 洞作壁橱/隔断），作用于分水岭合并阶段。

---

## 4. 建筑索引与更新机制 `WorldStructureIndex`

### 4.1 数据结构（第 58–67 行）

```
byId:        Map<long id, BuildingRecord>          // 主表，id = seedBed.asLong() & 0x7FFF...
byChunk:     Map<chunkLong, List<BuildingRecord>>  // 按包围盒覆盖的 chunk 反查，加速 getAt/queryNear
claimedBeds: Set<long>        // 已属于某建筑的床（永不再扫）
rejectedBeds:Set<long>        // 扫过但不在房子里的床（负缓存）
pending:     Deque<long>      // 待洪泛的床；pendingSet 去重
```

持久化：`byId + 三个床集合` 全部写入 `StructureIndexSavedData`（经 `WorldSavedData`），重启不重扫。`BuildingRecord.writeNBT/readNBT` 用 `ListNBT(LongNBT)` 存多床（`beds` 字段；旧存档无该字段时 `readNBT` 回退到 `seedBed`，向后兼容）。

### 4.2 增量发现（无周期全扫）

- **chunk 加载** → `VillagerEventHandler.onChunkLoad`（第 301 行）订阅 `ChunkEvent.Load` → `indexChunk(chunk)` → 从 chunk 的 block-entity 表读床（`BuildingLocator.bedsInChunk`，零方块扫描）→ `offerBed`。
- **玩家放/拆方块** → `onBlockPlaced` / `onBlockBroken`（第 314、333 行）订阅 `BlockEvent.EntityPlaceEvent` / `BreakEvent` → `offerBed`（床）/ `onBedRemoved`（床）/ `onBlockChanged`（其它块）。
- **每 tick** → `VillagerAgentManager.tickAgents` → `processPending(world, FLOODS_PER_TICK=1)`（第 252 行）：每 tick 最多 1 次洪泛，整村发现摊到几秒内，不卡服。

### 4.3 `processPending` 主循环（第 252–309 行）

```
for i in 0..budget:
    取队首床 key（pending.poll，pendingSet 去重）
    ── 周边 chunk 必须全部加载 ──
       用 getChunkNow(cx,cz) 判空（不强制加载），任一处 null → continue（出队丢弃，见 G4）
    ── 床还在吗？ ──
       world.getBlockState(bed).is(BEDS) 为假 → onBedRemovedInternal(key)，跳过
    ── 已在某建筑内？ ──
       getAt(bed) != null（或 claimed） → 把它 addBed 绑进同一屋，claimedBeds.add，跳过
    ── 否则 ──
       BuildingLocator.locateBed(world, bed)
           record != null → add(record)
           record == null → rejectedBeds.add(key)  // 负缓存
```

### 4.4 失效 / 重扫路径（第 153–224 行）

```
方块变化 (onBlockChanged, 第 177 行)
   → getAt(pos) 找到所属建筑 → remove(id) + enqueue(seedBed)   // 该建筑丢弃，种子床重扫
   → 找不到 → 12 格内被拒床复活重扫（可能刚补上最后一道墙）

床被拆 (onBedRemoved, 第 207 行)
   → 找到含此床的记录（含"床的另一半"也匹配）→ removeBed(bed)
        · 床集空 → remove(整屋)              // 最后一张床没了 = 整屋消失
        · 否则 → remove(整屋) + enqueue(存活床)   // 丢弃冻结几何的旧记录，
                                                   // 从存活床重新洪泛，房间随世界更新
   → 不在任何记录 → 清 claimed/rejected/pending 缓存

每次更新 (pruneStaleBeds, 挂于 processPending, 节流 ~5s)
   → 遍历每栋楼的 beds，逐张用 world.getBlockState 核对是否仍存在（含"另一半"兜底）
        · 某床的世界里已不存在 → 从 beds / claimedBeds 剔除
        · 存活床为空 → remove(整屋)          // 幽灵"无床"楼彻底消失
        · 仅部分床没了 → 保留存活床，必要时把 seedBed 提升为存活床
   → 目的：兜底所有"拆床事件漏掉"的路径（拆的是没被记录的那一半、活塞/爆炸、
           存档漂移），让无床残留永远不可能长期存在。

整屋重扫 (processPending)
   · 周边 ±scanHorizontalHalf() chunk 未全加载 → 该床出队，留待下次 chunk 加载/采样重入队
   · 床已不属于任何建筑(claimed)或已在记录内 → 若记录内则 addBed 绑定同一屋，跳过
   · 否则 locateBed → add(record) / rejectedBeds.add(key)
```

> **效果**：结构性改动（敲墙、开门、玩家手放/拆方块）会触发重扫更新——这条机制是接通的、可用的。

### 4.5 多床合并（一间房两张床 → 同一栋）

`BuildingRecord` 持有 `List<BlockPos> beds`（`seedBed` 始终是首个/稳定 id 源；`beds[0]` 恒含 seedBed）。

- **`add`**（第 112 行）：新建记录时，包围盒内其它待处理床直接 `addBed` 绑进同一屋。
- **`processPending`**（第 287 行）：一张床开始检测时若 `getAt(bed) != null`（已在某屋包围盒内）→ 把它 `addBed` 到那个屋，**而非像旧版只标记 claimed 后丢弃**。这正对应"发现床已在已有房子包围盒内"的场景。
- **`onBedRemoved` / `onBedRemovedInternal`**：按"哪个记录含此床"移除；删最后一张床→整屋消失；否则丢旧记录并从存活床 `enqueue` 重新洪泛（几何随世界刷新，见 §4.6 G1 修复）。

行为：9×9 房两床 → 先定位的床生成整屋（包围盒已含两床连通空气），后定位的床发现自己在盒内 → 并入同一 `BuildingRecord.beds`，两床同属一栋。

### 4.6 已知缺陷（更新机制的真实坑）

| # | 缺陷 | 现象 | 根因 / 位置 | 严重度 | 状态 |
| --- | --- | --- | --- | --- | --- |
| G1 | **拆非种子床 / 多床屋不更新** | 拆一张床后房间记录仍冻结在首次检测几何 | 旧 `onBedRemoved` 删除/提升种子后**从不重洪泛**，几何停在第 1 次检测 | 中 | **已修（2026-08-16）** |
| G2 | **内部变化不可见** | 屋里放箱/家具，记录无变化 | `BuildingRecord` 只存粗 AABB+type+seedBed+多床；无内部模型；重扫结果通常相同 | 中 |
| G3 | **机械改动不失效** | 活塞推墙、爆炸、液体流动后索引陈旧 | 这些**不触发 `BlockEvent`**；而 `markDirty` 是**死代码（全工程无调用方）**，本应作此用途 | 高 |
| G4 | **未加载即丢弃（backoff）** | 周边 chunk 未加载时该床出队 | `processPending` 第 309 行 `if (!chunksLoaded) continue;` 出队后**不加回**；仅当后续 chunk 加载或采样再次 `offerBed` 才重试。静态已加载小区若扫描盒不全加载则延迟/漏检 | 低-中 |
| G5 | **无床残留（幽灵楼）** | 场景里出现"没有床却仍被识别为房子"的建筑；放床再拆也不消失 | `BuildingRecord.beds` 只记录床的**其中一半**（seedBed）；拆掉没被记录的那一半时 `hasBed` 查不到 → 走"清缓存"分支建筑不动；原版自动移除另一半但不保证再触发 `BreakEvent`；存档重载时 `loadNBT` 只从 `seedBed` 重建 `claimedBeds`，幽灵床永久残留 | 中 | **已修（2026-08-16）** |

> **G1 修复说明（2026-08-16）**：原 `onBedRemoved` 在移除床后，若仍有存活床就把记录保留（最多把 seed 提升为存活床），但那栋楼的几何从第 1 次 `locateBed` 起就再没重算过。后果：多床屋拆掉一张床、或拆掉非种子床时，房间 AABB/类型不变（`queryNear`/调试视图"看起来没更新"）。现改为：只要还有存活床，就继续处理——`remove(整屋)` 并从存活床 `enqueue` 重新洪泛（见下 G5 也强化此路径）。
>
> **G5 修复说明（2026-08-16）**：根因是床有头/尾两格，但 `beds` 只存了被 offer 的那一格。拆掉**未记录**的那一格 → `onBedRemoved` 用 `hasBed(k)` 查不到 → 建筑纹丝不动；原版移除另一格时也不保证再发 `BreakEvent`；存档重载时 `loadNBT` 仅按 `seedBed` 重建 `claimedBeds`，于是"无床楼"永久残留。两层修复：
> 1. **即时**：`onBedRemoved` 的匹配扩展为"`beds` 中的任一格 **或它的另一半**（水平相邻）"——拆哪一半都能即时命中并删楼/重洪泛。
> 2. **兜底（每次更新）**：新增 `pruneStaleBeds`，挂在 `processPending` 每 tick 调用、内部节流（`PRUNE_INTERVAL=100` tick ≈5s）。它对每栋楼的每张床用 `world.getBlockState` 核对是否仍存在（未加载 chunk 视为"在"，不误删），不存在就剔除；最后一张床没了就 `remove(整屋)`。这覆盖了所有"拆床事件漏掉"的路径（拆错半格、活塞/爆炸、存档漂移），保证无床残留不可能长期存在。
>
> **关于 G3**：`markDirty(AxisAlignedBB)`（第 153 行）方法已写好（作用：把重叠建筑删除并重扫、复活 `scanHorizontalHalf()` 内被拒床），但**没有任何调用点**——活塞/爆炸/液体应在此处调用它才能生效。

---

## 5. 村庄 POI 结论（未实现增强）

查 1.16.5 真实 API：
- 每张床生成/放置时即写入 `minecraft:home` POI，存于 `ServerWorld.getPoiManager()`。
- 本模组 `bedsInChunk` 在 chunk 加载时**直接从方块实体表读床**——拿到的就是同一份数据，且比回头查 `PoiManager` 更省（不强制加载 section）。
- 1.16.5 村庄系统**不存"每栋房的 AABB"**，房屋边界由 POI 簇动态推导，无单调用 API 能直接拿到"某村所有房子的坐标盒"。

结论：**"认出村庄里的房子并把床绑过去"已被现有"床驱动发现 + §4.5 多床合并"覆盖**。直读 `PoiType.HOME` 仅在"想给建筑打村庄分组 tag"时有价值——属可选增强，当前未做。

---

## 6. 村民侧如何消费（检索接口）

### 6.1 Mid 层感知：`VillagerVisionSystem.queryNear`
`buildEnvironmentSummary`（第 79 行）调用：
```java
List<BuildingRecord> buildings = WorldStructureIndex.instance(world).queryNear(pos, 3);
```
以村民所在 chunk 为中心、半径 3 chunk 内取出所有 `BuildingRecord`，注入 LLM 环境描述（"Nearby buildings: house (bed at x,z) ..."）。村民**自己不做任何世界扫描**——只查索引。

### 6.2 检索实现
- `getAt(BlockPos)`（第 82 行）：按坐标所在 chunk 反查 `byChunk`，遍历命中记录的 `contains(p)`。
- `queryNear(BlockPos, rChunks)`（第 90 行）：在 chunk 半径内收集 `byChunk` 中的所有记录（按 `id` 去重）。

---

## 7. 重要区分：村民"睡觉回家"导航**不**走本算法

`VillagerActivitySystem.handleResting`（第 146 行）的回家逻辑：
```java
Optional<GlobalPos> homeOpt = villager.getBrain().getMemory(MemoryModuleType.HOME);
```
它读的是**原版 Minecraft 的 `MemoryModuleType.HOME` POI**（村民自己认领的床），**不是** `WorldStructureIndex` 检测出来的 `BuildingRecord`。

也就是说：
- **床→房间几何检测**（本文主体）产出的 `WorldStructureIndex` 目前**只服务于村民的 LLM 环境感知（Mid 层）**，还没接到"村民回家睡觉要走到哪张床"的导航上。
- `AI_AGENT_GUIDE.md` §8 的待办里 `Home/Bed Assignment`（出生时分配 home 坐标、供 resting 导航）**尚未实现**——一旦要做，正确做法是把 `WorldStructureIndex` 里该村民所属/最近建筑的 `seedBed` 写回 `MemoryModuleType.HOME`，而不是让村民重新扫世界。

> 这是当前架构里最值得注意的"检测能力已具备、但消费侧还没接上"的断层。

---

## 8. 验证脚本

| 脚本 | 验证内容 | 关键结论 |
| --- | --- | --- |
| `AI scripts/building_detection_verify/verify_watershed.py` | `locateBed` 距离场 + 分水岭的 Python 移植 + Meyer 对照（**仍用旧 long-run 大气模型，未同步 2026-08-16 的"连天"判定，仅作历史参考**） | 旧版对有孔房溢出（A 仅 63/324、床落 hasSky 盆地）；long-run+AIR_RUN=12 → 满房 326/324、B/C 无误检、E 仍溢出（固有局限） |
| `AI scripts/building_detection_verify/verify_update.py` | `WorldStructureIndex` 缓存/失效机制的 Python 模拟 + 6 场景 | 确认 G1–G4 四类"不更新"缺口（拆床删整屋 / 内部无模型 / 机械改动无失效 `markDirty` 死代码 / 未加载即丢弃） |

> 注：`AI scripts/building_detection_verify/verify_update.py` 的 `locate_bed` 替身为简化不过门口（整盒洪泛成 `open`），仅用于压测**缓存层**逻辑，不影响 G1–G4 结论（那四个缺口与几何检测无关）。

---

## 9. 已知局限与待办

- [x] **G3（高）**：**已修（2026-08-16）**——`VillagerEventHandler` 新增 `onPistonPost`(`PistonEvent.Post`)、`onExplosion`(`ExplosionEvent.Detonate`)、`onFluidPlace`(`BlockEvent.FluidPlaceBlockEvent`)、`onCommand`(`CommandEvent`，覆盖 `/setblock` `/fill` `/clone`) 四个订阅，各自算出受影响区域后调用 `markDirty`。仅剩：命令放新床不播种、火灾/龙/凋灵自然破坏（低频，可选）。
- [ ] **G4**：`processPending` 在 chunk 未加载时**重新入队（backoff 计数）**而非丢弃，避免静态小区漏检。
- [x] **G5（无床残留/幽灵楼）**：**已修（2026-08-16）**——`onBedRemoved` 现匹配床的"另一半"（即时删楼/重洪泛），并新增 `pruneStaleBeds` 每次更新（节流 ~5s）核对每张床是否仍在世界、不在则剔除、全没了删整楼；覆盖拆错半格/活塞/爆炸/存档漂移等漏事件路径。
- [ ] **G1（可选增强）**：给"无床也识屋"机制（shell-only / 多锚点），让拆掉全部床后壳仍被记住（属可选项，与 G5 的"全没了删整楼"是取舍两端）。
- [ ] **E 溢出**：分水岭固有局限，洞口连通有顶暗格时两盆地都合格 → 加"连通颈宽判定"（1×1 洞作隔断/壁橱，≥2 格才算门）。
- [ ] **§7 消费断层**：把 `WorldStructureIndex` 的床/建筑接回村民 `MemoryModuleType.HOME`，实现真正的"回家睡觉"导航（当前 resting 仍依赖原版 POI）。⚠️ 注：2026-08-16 已新增 `TODO: goto <type>` 目标（`executeGotoBuildingGoal`）让 LLM 能"导航到最近建筑"，但这是**主动移动**消费，与"回家睡觉"的 HOME 回填是两件事，后者仍待做。
- [ ] **可选**：村庄 POI 直读，给建筑打村庄分组 tag。
- [ ] **反向提示**：当前规则把火把/牌子/地毯/铁轨等薄装饰也当墙；如需可穿过，加放行清单。

---

## 10. 编译 / 验证备忘

按约定，**AI 不跑 `gradlew` 以省 token**，由用户本地验证：

```
gradlew compileJava
```

重点盯：
1. `BuildingRecord.writeNBT/readNBT` 的 `Constants.NBT.TAG_LONG` / `LongNBT.valueOf` / `ListNBT.add` 映射名（1.16.5 标准，应无误）。
2. 多床 NBT 读写与旧存档兼容（`readNBT` 已对缺 `beds` 字段回退到 `seedBed`）。
3. 包围盒外扩后，相邻房屋的 `getAt` 会不会误并（外扩 +1 在相邻建筑贴合时有小概率重叠，必要时按 id 最近优先）。
4. 游戏内实测：有门/有洞房子 bed 识别，拆一张床（多床屋应保留、删最后一张消失），活塞推墙后索引是否过期（确认 G3 仍待修）。
