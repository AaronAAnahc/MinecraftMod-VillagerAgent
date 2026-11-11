# Mine Shaft System Design

Village mine shaft structure + miner AI for underground resource extraction.

## Physical Structure

### Mine Shaft (Vertical)
- 2×2 or 3×3 vertical shaft with ladders, generated within village boundaries.
- Miner's hut adjacent to shaft entrance, containing a miner workstation block.
- Initial depth: iron ore optimal layer only (Y≈16 in 1.16.5).
- At each developed layer, a small platform room (roughly 5×5×3) serves as the staging area for branching tunnels.

### Expansion Layers (Unlocked by Miner Level)

| Miner Level | Target Y | Primary Ore | Notes |
|---|---|---|---|
| Lv1 (initial) | Y≈16 | Iron | Default starting depth |
| Lv2 | Y≈14 | Gold, Lapis Lazuli | Gold concentrated Y≈12-15; lapis Y≈14-16 |
| Lv3 | Y≈8 | Diamond | Diamond concentrated Y≈5-12 |
| Lv4 | Y≈12 | Emerald | Mountain biome only (Y≈4-32) |

Expansion process: miner digs downward from current deepest level, placing ladders as they go, until reaching the target Y. Then excavates a platform room and begins branching tunnels.

## Tunnel Layout: Branch Mining (Fish-bone Pattern)

From each platform room:
- One 2×1 main corridor extends outward in a cardinal direction.
- Every 2 blocks along the main corridor, a 1×2 side branch extends perpendicular.
- Side branches are typically 10-20 blocks long before the miner turns back.

```
          ████████████████████
          █  ·  █  ·  █  ·  █    · = side branch end
          █  ·  █  ·  █  ·  █
  ════════════════════════════    ═ = main corridor
          █  ·  █  ·  █  ·  █
          █  ·  █  ·  █  ·  █
          ████████████████████
                    ↑
              platform room
```

## Miner Behavior

### In-Tunnel Mining
- **Stone and ore in the tunnel path**: miner breaks the block normally (plays dig animation, block is destroyed, drop item).
- **Fluids (water/lava)**: miner places cobblestone to seal, then continues.
- **Gravel/sand**: miner digs through (block falls, miner collects).

### Adjacent Ore (Non-Destructive Harvesting)
Ore blocks visible from the tunnel but not in the direct path:
- Miner plays mining animation facing the ore block.
- After animation completes, ore item drops (as if mined).
- **The ore block is NOT destroyed** — it remains in the world.
- Each ore block can be harvested this way **once per game day** (tracked per-block).
- This preserves the underground landscape while still providing resources.

### Surface Ore Memory
- Existing `ChunkContentSampler` + `GossipChannel` ore memory system is retained.
- Miners may choose to travel to a remembered surface ore vein instead of going underground.
- Decision weight: known surface veins (closer, easier) vs. advancing tunnel (reliable yield).

## Data Structures

### MineShaft
```
MineShaft {
    UUID id
    BlockPos entrance          // shaft entrance at surface
    int currentDepth           // deepest developed Y level
    List<MineLevel> levels     // developed platform levels
    UUID ownerVillageMarket    // which market/village owns this shaft
}
```

### MineLevel
```
MineLevel {
    int yLevel                      // Y coordinate of platform room
    BlockPos platformCenter         // center of the platform room
    List<MineTunnel> tunnels        // branching tunnels from this level
    Set<BlockPos> discoveredOres    // ore positions found at this level
    Set<BlockPos> harvestedToday    // ores already harvested today (reset daily)
}
```

### MineTunnel
```
MineTunnel {
    Direction mainDirection    // cardinal direction of main corridor
    int mainLength             // how far the main corridor extends
    List<TunnelBranch> branches
}

TunnelBranch {
    int offsetAlongMain        // distance along main corridor where branch starts
    Direction branchDirection  // perpendicular to main
    int branchLength           // how far this branch extends
}
```

All data persisted via NBT (survives server restart). One village can have multiple mine shafts.

## Miner Upgrade Path

| Level | Capabilities | Required Tool |
|---|---|---|
| Lv1 | Iron layer only | Stone/Iron Pickaxe |
| Lv2 | Expand to gold/lapis layer | Iron Pickaxe |
| Lv3 | Expand to diamond layer | Iron/Diamond Pickaxe |
| Lv4 | Expand to emerald layer (mountain biome required) | Diamond Pickaxe |

Miner level is determined by `CapabilityPool` MINING level. Expansion is triggered as a goal when the miner's level meets the threshold and the shaft has not yet been expanded to the corresponding depth.

## Integration Points

- **Goal types**: `mine_tunnel` (advance tunnel), `mine_expand` (dig shaft deeper + new platform), `mine_harvest` (harvest adjacent ores at existing level)
- **BehaviorCatalog**: register above goal types with MINING capability gate
- **RoutineScheduler**: miners with MINING ≥ 1 seed `mine_tunnel` as repeatable routine
- **FailureRecoveryPlanner**: missing pickaxe → acquire_item sub-goal
- **WorldEventBus**: tunnel construction emits BLOCK_CHANGED events (nearby villagers may learn about new ore discoveries)
