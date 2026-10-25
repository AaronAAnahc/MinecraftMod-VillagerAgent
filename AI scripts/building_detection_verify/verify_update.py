"""
verify_update.py — model WorldStructureIndex update/invalidation behavior.

Reproduce two reported symptoms:
  (1) removing a bed does not update building detection;
  (2) changing a building interior does not update detection.

Only cache/invalidation hooks are ported: byId/byChunk/claimed/rejected/pending
and onBlockChanged/onBedRemoved/markDirty/processPending. locate_bed is a minimal
bed-seeded air-flood AABB substitute; the focus is whether the cache changes.
This diagnostic model does not establish parity with the current Java algorithm.

Run: python verify_update.py
"""
from collections import deque, defaultdict


MASK = 0x7FFFFFFFFFFFFFFF


def maskbed(bed):
    # ID calculation used by BuildingLocator.locateBed
    return bed.as_long() & MASK


class BlockPos:
    def __init__(self, x, y, z):
        self.x, self.y, self.z = x, y, z
    def as_long(self):
        # 21-bit signed fields: y@0, z@21, x@42  (no overlap, clean round-trip)
        return ((self.x & 0x1FFFFF) << 42) | ((self.z & 0x1FFFFF) << 21) | (self.y & 0x1FFFFF)
    @staticmethod
    def of(k):
        x = (k >> 42) & 0x1FFFFF; z = (k >> 21) & 0x1FFFFF; y = k & 0x1FFFFF
        if x & 0x100000: x -= 0x200000
        if z & 0x100000: z -= 0x200000
        if y & 0x100000: y -= 0x200000
        return BlockPos(x, y, z)
    def immutable(self):
        return self
    def distSqr(self, o):
        dx = self.x - o.x; dy = self.y - o.y; dz = self.z - o.z
        return dx*dx + dy*dy + dz*dz


class BuildingRecord:
    def __init__(self, id, seedBed, boundsMin, boundsMax, coarseType):
        self.id = id; self.seedBed = seedBed
        self.boundsMin = boundsMin; self.boundsMax = boundsMax
        self.coarseType = coarseType
    def contains(self, p):
        return (self.boundsMin.x <= p.x <= self.boundsMax.x and
                self.boundsMin.y <= p.y <= self.boundsMax.y and
                self.boundsMin.z <= p.z <= self.boundsMax.z)


class World:
    """Minimal world: blocks[(x,y,z)] = type string; 'bed' denotes a bed."""
    def __init__(self):
        self.blocks = {}
        self.loaded = set()  # Loaded chunk coordinates (cx,cz)
    def set_block(self, p, t):
        self.blocks[(p.x, p.y, p.z)] = t
    def get_block(self, p):
        return self.blocks.get((p.x, p.y, p.z))
    def is_bed(self, p):
        return self.get_block(p) == "bed"
    def ensure_loaded(self, cx, cz):
        self.loaded.add((cx, cz))


def has_sky_above(world, p):
    for y in range(p.y + 1, 256):
        if world.get_block(BlockPos(p.x, y, p.z)) not in (None, "air", "bed"):
            return False
    return True


def locate_bed(world, bed):
    """Substitute: flood six-connected air from the bed and bound reachable air.
    Return BuildingRecord, or None for a buried bed/no air.
    If reachable air touches the sky, coarseType='open' (connected to atmosphere).
    This model limits scanning to a box within 16 blocks of the bed to bound leakage through doors.
    """
    if not world.is_bed(bed):
        return None
    R = 16
    x0, x1 = bed.x - R, bed.x + R
    y0, y1 = bed.y - 2, bed.y + 16
    z0, z1 = bed.z - R, bed.z + R
    start = (bed.x, bed.y, bed.z)
    seen = set([start])
    q = deque([start])
    air_cells = []
    open_to_sky = False
    while q:
        x, y, z = q.popleft()
        air_cells.append((x, y, z))
        if has_sky_above(world, BlockPos(x, y, z)):
            open_to_sky = True
        for dx, dy, dz in ((1,0,0),(-1,0,0),(0,1,0),(0,-1,0),(0,0,1),(0,0,-1)):
            nx, ny, nz = x+dx, y+dy, z+dz
            if nx < x0 or nx > x1 or ny < y0 or ny > y1 or nz < z0 or nz > z1:
                continue
            n = (nx, ny, nz)
            if n in seen: continue
            b = world.get_block(BlockPos(nx, ny, nz))
            if b in (None, "air", "bed"):
                seen.add(n); q.append(n)
    if not air_cells:
        return None
    xs = [c[0] for c in air_cells]; ys = [c[1] for c in air_cells]; zs = [c[2] for c in air_cells]
    bmin = BlockPos(min(xs), min(ys), min(zs))
    bmax = BlockPos(max(xs), max(ys), max(zs))
    typ = "open" if open_to_sky else "house"
    return BuildingRecord(maskbed(bed), bed.immutable(), bmin, bmax, typ)


class WorldStructureIndex:
    def __init__(self):
        self.byId = {}
        self.byChunk = defaultdict(list)
        self.claimedBeds = set()
        self.rejectedBeds = set()
        self.pending = deque()
        self.pendingSet = set()

    def getAt(self, p):
        for r in self.byId.values():
            if r.contains(p):
                return r
        return None

    def add(self, r):
        self.byId[r.id] = r
        for cx in range(r.boundsMin.x >> 4, (r.boundsMax.x >> 4) + 1):
            for cz in range(r.boundsMin.z >> 4, (r.boundsMax.z >> 4) + 1):
                self.byChunk[(cx, cz)].append(r)
        self.claimedBeds.add(r.seedBed.as_long())

    def remove(self, id):
        r = self.byId.pop(id, None)
        if r is None:
            return
        for cx in range(r.boundsMin.x >> 4, (r.boundsMax.x >> 4) + 1):
            for cz in range(r.boundsMin.z >> 4, (r.boundsMax.z >> 4) + 1):
                lst = self.byChunk.get((cx, cz))
                if lst:
                    lst[:] = [x for x in lst if x.id != id]
                    if not lst: del self.byChunk[(cx, cz)]
        self.claimedBeds.discard(r.seedBed.as_long())

    def enqueue(self, key):
        if key in self.claimedBeds or key in self.rejectedBeds or key in self.pendingSet:
            return
        self.pendingSet.add(key); self.pending.append(key)

    def offerBed(self, bed):
        self.enqueue(bed.as_long())

    def onBlockChanged(self, pos):
        r = self.getAt(pos)
        if r is not None:
            self.remove(r.id)
            self.enqueue(r.seedBed.as_long())
            return
        revive = [k for k in self.rejectedBeds if BlockPos.of(k).distSqr(pos) <= 12*12]
        for k in revive:
            self.rejectedBeds.discard(k); self.enqueue(k)

    def onBedRemoved(self, bed):
        k = bed.as_long()
        id = k & MASK
        if id in self.byId:
            self.remove(id)
        self.claimedBeds.discard(k)
        self.rejectedBeds.discard(k)
        if k in self.pendingSet:
            self.pendingSet.discard(k); self.pending.remove(k)

    def markDirty(self, region):
        # In the version modeled here, this method had no callers (dead code).
        hit = [r for r in self.byId.values()
               if (r.boundsMin.x <= region[0][0] <= r.boundsMax.x or region[0][0] <= r.boundsMin.x <= region[1][0])
               and (r.boundsMin.z <= region[0][1] <= r.boundsMax.z or region[0][1] <= r.boundsMin.z <= region[1][1])]
        for r in hit:
            self.remove(r.id); self.enqueue(r.seedBed.as_long())

    def processPending(self, world, budget):
        for _ in range(budget):
            if not self.pending:
                return
            key = self.pending.popleft()
            self.pendingSet.discard(key)
            bed = BlockPos.of(key)
            # Require the surrounding 33x33 box loaded; otherwise discard, as in the modeled version.
            cx0, cx1 = (bed.x - 16) >> 4, (bed.x + 16) >> 4
            cz0, cz1 = (bed.z - 16) >> 4, (bed.z + 16) >> 4
            if not all((cx, cz) in world.loaded for cx in range(cx0, cx1+1) for cz in range(cz0, cz1+1)):
                continue  # Discard without requeueing, leaving the building removed/stale.
            if not world.is_bed(bed):
                self.onBedRemoved(bed); continue
            if key in self.claimedBeds or self.getAt(bed) is not None:
                self.claimedBeds.add(key); continue
            rec = locate_bed(world, bed)
            if rec is not None:
                self.add(rec)
            else:
                self.rejectedBeds.add(key)


def build_house(world, ox, oy, oz, hx, hy, hz, with_bed=True):
    """Build an enclosed cuboid with walls, floor, ceiling, and a 1x2 doorway at z==oz by default."""
    for x in range(ox, ox+hx+1):
        for y in range(oy, oy+hy+1):
            for z in range(oz, oz+hz+1):
                edge = (x in (ox, ox+hx) or y in (oy, oy+hy) or z in (oz, oz+hz))
                if edge:
                    if z == oz and oy <= y <= oy+1:
                        world.set_block(BlockPos(x, y, z), "air")
                    else:
                        world.set_block(BlockPos(x, y, z), "wall")
                else:
                    world.set_block(BlockPos(x, y, z), "air")
    if with_bed:
        world.set_block(BlockPos(ox+2, oy+1, oz+2), "bed")
    for x in range(ox-16, ox+hx+16):
        for z in range(oz-16, oz+hz+16):
            world.ensure_loaded(x >> 4, z >> 4)


def scenario(name, fn):
    print("=" * 70)
    print("场景:", name)
    fn()


def find_building(idx):
    return next(iter(idx.byId.values())) if idx.byId else None


def A():
    w = World(); idx = WorldStructureIndex()
    build_house(w, 0, 64, 0, 8, 4, 8)
    bed = BlockPos(2, 65, 2)
    idx.offerBed(bed); idx.processPending(w, 1)
    b = find_building(idx)
    print("  检测到建筑:", b.coarseType if b else None,
          (b.boundsMin.x, b.boundsMin.z, "->", b.boundsMax.x, b.boundsMax.z) if b else "")
    print("  => 床存在时正常识别:", "OK" if b else "FAIL")


def B():
    w = World(); idx = WorldStructureIndex()
    build_house(w, 0, 64, 0, 8, 4, 8)
    bed = BlockPos(2, 65, 2)
    idx.offerBed(bed); idx.processPending(w, 1)
    before = find_building(idx)
    for y in range(64, 69):
        for x in range(2, 7):
            w.set_block(BlockPos(x, y, 8), "air")
            idx.onBlockChanged(BlockPos(x, y, 8))
    idx.processPending(w, 1)
    after = find_building(idx)
    grew = after and (after.boundsMax.z > before.boundsMax.z or after.coarseType == "open")
    print("  破墙前:", before.coarseType, "z_max=", before.boundsMax.z if before else None)
    print("  破墙后:", after.coarseType if after else None, "z_max=", after.boundsMax.z if after else None)
    print("  => 结构性改动会重扫并更新:", "OK" if grew else "FAIL")


def C():
    w = World(); idx = WorldStructureIndex()
    build_house(w, 0, 64, 0, 8, 4, 8)
    bed = BlockPos(2, 65, 2)
    idx.offerBed(bed); idx.processPending(w, 1)
    before = find_building(idx) is not None
    w.set_block(bed, "air"); idx.onBedRemoved(bed); idx.processPending(w, 1)
    after = find_building(idx) is not None
    print("  拆床前索引有建筑:", before, " 拆床后索引有建筑:", after)
    print("  => 拆床后整屋从索引消失（壳还在但无锚点）:", "OK(符合设计)" if (before and not after) else "FAIL")


def D():
    w = World(); idx = WorldStructureIndex()
    build_house(w, 0, 64, 0, 8, 4, 8)
    bed = BlockPos(2, 65, 2)
    idx.offerBed(bed); idx.processPending(w, 1)
    before = find_building(idx)
    chest = BlockPos(5, 65, 5)
    w.set_block(chest, "chest"); idx.onBlockChanged(chest)
    idx.processPending(w, 1)
    after = find_building(idx)
    same = (before.boundsMin.x == after.boundsMin.x and before.boundsMax.x == after.boundsMax.x
            and before.boundsMin.z == after.boundsMin.z and before.boundsMax.z == after.boundsMax.z)
    print("  放箱前AABB:", (before.boundsMin.x, before.boundsMin.z, before.boundsMax.x, before.boundsMax.z))
    print("  放箱后AABB:", (after.boundsMin.x, after.boundsMin.z, after.boundsMax.x, after.boundsMax.z))
    print("  => 内部场景变化但记录无变化（索引不含内部模型）:", "FAIL(用户报的'不更新')" if same else "OK")


def E():
    w = World(); idx = WorldStructureIndex()
    build_house(w, 0, 64, 0, 8, 4, 8)
    bed = BlockPos(2, 65, 2)
    idx.offerBed(bed); idx.processPending(w, 1)
    before = find_building(idx)
    for y in range(64, 69):
        for x in range(2, 7):
            w.set_block(BlockPos(x, y, 8), "air")
            w.set_block(BlockPos(x, y, 9), "wall")
    # This modeled piston move emits no BlockEvent and never calls markDirty (dead code in that version).
    idx.processPending(w, 1)
    after = find_building(idx)
    same = (after.boundsMax.z == before.boundsMax.z)
    print("  活塞前 z_max:", before.boundsMax.z, " 活塞后 z_max:", after.boundsMax.z if after else None)
    print("  => 活塞移动墙体但无事件/无 markDirty → 索引陈旧:", "FAIL(用户报的'不更新')" if same else "OK")


def F():
    w = World(); idx = WorldStructureIndex()
    build_house(w, 0, 64, 0, 8, 4, 8)
    bed = BlockPos(2, 65, 2)
    idx.offerBed(bed); idx.processPending(w, 1)
    for y in range(64, 69):
        for x in range(2, 7):
            w.set_block(BlockPos(x, y, 8), "air"); idx.onBlockChanged(BlockPos(x, y, 8))
    w.loaded.discard(((-1) >> 4, 0))
    idx.processPending(w, 1)
    w.ensure_loaded(-1 >> 4, 0)
    idx.processPending(w, 1)
    after = find_building(idx) is not None
    print("  破墙重扫后索引仍有建筑:", after, "（pending 剩余:", len(idx.pending), "）")
    print("  => chunk 未加载时重扫被丢弃且不再入队 → 建筑永久缺失:", "OK(暴露潜藏bug)" if not after else "FAIL")


if __name__ == "__main__":
    scenario("A 建房+放床 → 识别", A)
    scenario("B 敲墙(结构性) → 重扫更新", B)
    scenario("C 拆床 → 整屋从索引消失", C)
    scenario("D 屋内放箱(内部变化) → 记录不变", D)
    scenario("E 活塞推墙(无事件) → 陈旧", E)
    scenario("F 重扫时 chunk 未加载 → 永久缺失", F)
