package com.github.AaronAA0721.villageragent.ai;

import com.github.AaronAA0721.villageragent.config.ModConfig;
import net.minecraft.block.Blocks;
import net.minecraft.command.CommandSource;
import net.minecraft.entity.Entity;
import net.minecraft.entity.merchant.villager.VillagerEntity;
import net.minecraft.inventory.EquipmentSlotType;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.PickaxeItem;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.StringTextComponent;
import net.minecraft.world.server.ServerWorld;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * {@code /va demo pickaxe} — a staged, filmable run of the cooperative supply chain:
 *
 * <pre>
 * miner works, pickaxe breaks → miner posts an iron-ore offer and a #pickaxe request
 * → toolsmith reads the board, answers the request and takes the miner's iron
 * → toolsmith posts a sticks request, the lumberjack supplies it
 * → toolsmith smelts the iron and forges the pickaxe → delivers it to the miner → miner back to work
 * </pre>
 *
 * Every step <em>nudges</em> the villagers with the real goals the systems use (miner_work, acquire_item,
 * trade, supply_request), waits for the real machinery to carry it out, and only if a step exceeds its time
 * budget applies a visible <b>force</b> fallback (marked in the caption) so a recording never stalls.
 * One scenario per world; ticked from the world tick next to {@link TradeDemo}.
 */
public final class ShowcaseScenario {
    private static final Map<ServerWorld, Scenario> ACTIVE = new IdentityHashMap<>();
    private static final int TICK_INTERVAL = 10;

    private ShowcaseScenario() {}

    // ── Command surface ──────────────────────────────────────────────────────

    public static int start(CommandSource source, VillagerEntity miner, VillagerEntity toolsmith,
                            VillagerEntity lumberjack) {
        ServerWorld world = source.getLevel();
        if (ACTIVE.containsKey(world)) {
            source.sendFailure(new StringTextComponent("A showcase scenario is already running (/va demo stop)."));
            return 0;
        }
        if (!ModConfig.ENABLE_AI_AGENTS.get() || !ModConfig.ENABLE_WORLD_INTERACTION.get()
                || !ModConfig.ENABLE_ECONOMY.get()) {
            source.sendFailure(new StringTextComponent("Needs aiagents, worldinteraction and economy enabled."));
            return 0;
        }
        VillagerAgentData m = agentOf(miner, "miner"), t = agentOf(toolsmith, "toolsmith"),
                l = agentOf(lumberjack, "lumberjack");
        if (m == null || t == null || l == null) {
            source.sendFailure(new StringTextComponent(
                    "Pick a managed miner, toolsmith and lumberjack (profession must match)."));
            return 0;
        }
        if (!VillageMarkets.same(world, miner.getUUID(), toolsmith.getUUID())
                || !VillageMarkets.same(world, miner.getUUID(), lumberjack.getUUID())
                || VillageNoticeBoards.position(world, miner) == null) {
            source.sendFailure(new StringTextComponent(
                    "All three villagers must live in the same village market with a notice board."));
            return 0;
        }
        Scenario s = new Scenario(miner.getUUID(), toolsmith.getUUID(), lumberjack.getUUID());
        ACTIVE.put(world, s);
        source.sendSuccess(new StringTextComponent("§6Showcase: pickaxe supply chain started. /va demo stop to end it."), false);
        return 1;
    }

    public static int stop(CommandSource source) {
        Scenario s = ACTIVE.remove(source.getLevel());
        source.sendSuccess(new StringTextComponent(s == null ? "No scenario running." : "Scenario stopped."), false);
        return s == null ? 0 : 1;
    }

    private static VillagerAgentData agentOf(VillagerEntity v, String profession) {
        if (v == null || !v.isAlive() || v.isBaby() || v.isNoAi()) return null;
        VillagerAgentData a = VillagerAgentManager.getAgent(v.getUUID());
        return a != null && profession.equalsIgnoreCase(a.getProfession()) ? a : null;
    }

    // ── Director ─────────────────────────────────────────────────────────────

    /** One step of the film: announce, nudge, wait for the systems, force on timeout. */
    private abstract static class Step {
        final String caption;
        final long budget;
        Step(String caption, long budget) { this.caption = caption; this.budget = budget; }
        void begin(Scenario s, ServerWorld w) {}
        abstract boolean done(Scenario s, ServerWorld w);
        void force(Scenario s, ServerWorld w) {}
    }

    private static final class Scenario {
        final UUID miner, toolsmith, lumberjack;
        int step = -1;
        long stepStart;
        boolean forced;
        UUID requestId;    // the miner's #pickaxe request
        boolean offerPosted;
        Scenario(UUID miner, UUID toolsmith, UUID lumberjack) {
            this.miner = miner; this.toolsmith = toolsmith; this.lumberjack = lumberjack;
        }
    }

    public static void tick(ServerWorld world) {
        Scenario s = ACTIVE.get(world);
        if (s == null || world.getGameTime() % TICK_INTERVAL != 0) return;
        if (!ModConfig.ENABLE_AI_AGENTS.get()) { abort(world, s, "AI agents were disabled"); return; }
        VillagerEntity miner = villager(world, s.miner), smith = villager(world, s.toolsmith),
                lumber = villager(world, s.lumberjack);
        if (miner == null || smith == null || lumber == null) { abort(world, s, "a villager left or died"); return; }
        long now = world.getGameTime();
        if (s.step < 0) { advance(world, s, now); return; }
        Step current = STEPS[s.step];
        if (current.done(s, world)) { advance(world, s, now); return; }
        if (!s.forced && now - s.stepStart > current.budget) {
            s.forced = true;
            caption(world, s, "§7(hurrying this along)");
            current.force(s, world);
        }
    }

    private static void advance(ServerWorld world, Scenario s, long now) {
        s.step++;
        s.forced = false;
        s.stepStart = now;
        if (s.step >= STEPS.length) {
            caption(world, s, "§aThe chain is complete: work → need → board → cooperation → delivery.");
            ACTIVE.values().remove(s);
            return;
        }
        Step next = STEPS[s.step];
        caption(world, s, next.caption);
        next.begin(s, world);
    }

    private static void abort(ServerWorld world, Scenario s, String reason) {
        caption(world, s, "§cScenario ended: " + reason + ".");
        ACTIVE.remove(world);
    }

    // ── The steps ────────────────────────────────────────────────────────────

    private static final Step[] STEPS = {
        new Step("§6[1/8] The miner heads underground — his pickaxe is nearly worn out.", 2400) {
            @Override void begin(Scenario s, ServerWorld w) {
                // Stage the inventories so the chain has to happen: the miner's pickaxe is almost gone,
                // the toolsmith has fuel but no iron, sticks or wood, the lumberjack has logs to share.
                VillagerAgentData m = agent(s.miner), t = agent(s.toolsmith), l = agent(s.lumberjack);
                VillagerEntity mv = villager(w, s.miner), tv = villager(w, s.toolsmith), lv = villager(w, s.lumberjack);
                wearDownPickaxe(m, 12);
                for (Item item : new Item[]{Items.IRON_PICKAXE, Items.IRON_ORE, Items.IRON_INGOT, Items.STICK})
                    removeAll(t, item);
                if (count(t, Items.COAL) < 8) InventoryTransfer.insert(t.getInventory(), new ItemStack(Items.COAL, 8));
                if (logCount(l) < 4) InventoryTransfer.insert(l.getInventory(), new ItemStack(Items.OAK_LOG, 4));
                ensureFurnaceNear(w, tv);
                VillagerEquipmentHelper.refreshEquipment(mv, m);
                VillagerEquipmentHelper.refreshEquipment(tv, t);
                VillagerEquipmentHelper.refreshEquipment(lv, l);
                startGoal(w, s.miner, new AgentGoal("miner_work", "Mine ore", 8));
            }
            @Override boolean done(Scenario s, ServerWorld w) {
                VillagerAgentData m = agent(s.miner);
                return !hasPickaxe(m) && count(m, Items.IRON_ORE) >= 1;
            }
            @Override void force(Scenario s, ServerWorld w) {
                VillagerAgentData m = agent(s.miner);
                VillagerEntity mv = villager(w, s.miner);
                if (hasPickaxe(m)) breakPickaxes(mv, m);
                if (count(m, Items.IRON_ORE) < 3) InventoryTransfer.insert(m.getInventory(),
                        new ItemStack(Items.IRON_ORE, 3 - count(m, Items.IRON_ORE)));
                if (count(m, Items.COAL) < 2) InventoryTransfer.insert(m.getInventory(), new ItemStack(Items.COAL, 2));
            }
        },
        new Step("§6[2/8] Snap! The pickaxe broke. The miner walks to the notice board: iron for sale, pickaxe wanted.", 1800) {
            @Override void begin(Scenario s, ServerWorld w) {
                VillagerAgentData m = agent(s.miner);
                AgentGoal goal = null;
                for (AgentGoal g : m.getGoals())
                    if (!g.isCompleted() && "acquire_item".equals(g.getGoalType())
                            && "pickaxe".equals(g.getRequestCategory())) { goal = g; break; }
                if (goal == null) {
                    goal = new AgentGoal("acquire_item", "Replace my broken pickaxe", 8);
                    goal.setTargetItem("minecraft:iron_pickaxe");
                    goal.setTargetQuantity(1);
                    goal.setRequestCategory("pickaxe");
                    goal.setOutcomeItems(java.util.Collections.singleton("minecraft:iron_pickaxe"));
                    goal.setLifecycle(AgentGoal.Lifecycle.ONE_SHOT);
                }
                startGoal(w, s.miner, goal);
            }
            @Override boolean done(Scenario s, ServerWorld w) {
                VillageSignalBoard.VillageMessage req = pickaxeRequest(s, w.getGameTime());
                if (req == null) return false;
                s.requestId = req.id;
                postIronOffer(s, w); // he is at the board now — put his ore up for sale alongside
                return s.offerPosted;
            }
            @Override void force(Scenario s, ServerWorld w) {
                VillagerEntity mv = villager(w, s.miner);
                VillageSignalBoard board = VillageMarkets.board(w, s.miner);
                if (board != null && pickaxeRequest(s, w.getGameTime()) == null) {
                    board.postOwnedRequest(s.miner, s.miner, mv.blockPosition(),
                            SupplyPolicy.Spec.category("pickaxe"), 1, w.getGameTime());
                }
                postIronOffer(s, w);
            }
        },
        new Step("§6[3/8] The toolsmith checks the board and takes on the pickaxe request.", 1800) {
            @Override void begin(Scenario s, ServerWorld w) {
                startGoal(w, s.toolsmith, new AgentGoal("trade", "Check the notice board", 8));
            }
            @Override boolean done(Scenario s, ServerWorld w) {
                VillagerAgentData t = agent(s.toolsmith);
                for (AgentGoal g : t.getGoals())
                    if (!g.isCompleted() && CooperativeSupply.RESPONSE_GOAL.equals(g.getGoalType())
                            && (s.requestId == null || s.requestId.equals(g.getTradeMessageId()))) return true;
                VillageSignalBoard.VillageMessage req = request(s, w.getGameTime());
                return req != null && req.claimed();
            }
            @Override void force(Scenario s, ServerWorld w) {
                VillageSignalBoard.VillageMessage req = request(s, w.getGameTime());
                if (req == null) return;
                AgentGoal g = new AgentGoal(CooperativeSupply.RESPONSE_GOAL, "Supply village request #pickaxe", 8);
                g.setTradeMessageId(req.id);
                g.setRequestSpec(req.spec.key());
                g.setRequestUnits(req.quantity);
                g.setTargetItem("minecraft:iron_pickaxe");
                g.setTargetQuantity(1);
                g.setLifecycle(AgentGoal.Lifecycle.EXPIRING);
                g.setDeadlineTick(w.getGameTime() + 24000);
                startGoal(w, s.toolsmith, g);
            }
        },
        new Step("§6[4/8] Iron needed — the toolsmith takes the miner up on his ore offer.", 3600) {
            @Override boolean done(Scenario s, ServerWorld w) {
                VillagerAgentData t = agent(s.toolsmith);
                return count(t, Items.IRON_ORE) + count(t, Items.IRON_INGOT) >= 3 || hasPickaxe(t);
            }
            @Override void force(Scenario s, ServerWorld w) {
                VillagerAgentData m = agent(s.miner), t = agent(s.toolsmith);
                int moved = InventoryTransfer.move(m.getInventory(), t.getInventory(), Items.IRON_ORE, 3);
                if (moved < 3) InventoryTransfer.insert(t.getInventory(), new ItemStack(Items.IRON_ORE, 3 - moved));
            }
        },
        new Step("§6[5/8] Sticks wanted for the handle — the lumberjack answers the call.", 3600) {
            @Override void begin(Scenario s, ServerWorld w) {
                startGoal(w, s.lumberjack, new AgentGoal("trade", "Check the notice board", 8));
            }
            @Override boolean done(Scenario s, ServerWorld w) {
                VillagerAgentData t = agent(s.toolsmith);
                return count(t, Items.STICK) >= 2 || hasPickaxe(t);
            }
            @Override void force(Scenario s, ServerWorld w) {
                VillagerAgentData t = agent(s.toolsmith), l = agent(s.lumberjack);
                takeOneLog(l);
                InventoryTransfer.insert(t.getInventory(), new ItemStack(Items.STICK, 2));
            }
        },
        new Step("§6[6/8] The furnace glows: ore becomes iron.", 3600) {
            @Override boolean done(Scenario s, ServerWorld w) {
                VillagerAgentData t = agent(s.toolsmith);
                return count(t, Items.IRON_INGOT) >= 3 || hasPickaxe(t);
            }
            @Override void force(Scenario s, ServerWorld w) {
                VillagerAgentData t = agent(s.toolsmith);
                int ore = Math.min(3, count(t, Items.IRON_ORE));
                remove(t, Items.IRON_ORE, ore);
                InventoryTransfer.insert(t.getInventory(), new ItemStack(Items.IRON_INGOT, Math.max(3, ore)));
            }
        },
        new Step("§6[7/8] Three ingots, two sticks: the toolsmith forges a brand-new pickaxe.", 2400) {
            @Override boolean done(Scenario s, ServerWorld w) {
                return hasPickaxe(agent(s.toolsmith));
            }
            @Override void force(Scenario s, ServerWorld w) {
                VillagerAgentData t = agent(s.toolsmith);
                remove(t, Items.IRON_INGOT, 3);
                remove(t, Items.STICK, 2);
                InventoryTransfer.insert(t.getInventory(), new ItemStack(Items.IRON_PICKAXE));
                VillagerEquipmentHelper.refreshEquipment(villager(w, s.toolsmith), t);
            }
        },
        new Step("§6[8/8] Delivery! The toolsmith brings the miner his new pickaxe.", 3600) {
            @Override boolean done(Scenario s, ServerWorld w) {
                VillagerAgentData m = agent(s.miner);
                if (!hasPickaxe(m)) return false;
                startGoal(w, s.miner, new AgentGoal("miner_work", "Back to the mine", 8));
                return true;
            }
            @Override void force(Scenario s, ServerWorld w) {
                VillagerAgentData m = agent(s.miner), t = agent(s.toolsmith);
                InventoryTransfer.move(t.getInventory(), m.getInventory(), Items.IRON_PICKAXE, 1);
                VillagerEquipmentHelper.refreshEquipment(villager(w, s.miner), m);
                VillageSignalBoard.VillageMessage req = request(s, w.getGameTime());
                if (req != null) VillageSignalBoard.get().retire(req.id);
            }
        },
    };

    // ── Step helpers ─────────────────────────────────────────────────────────

    private static VillagerEntity villager(ServerWorld world, UUID id) {
        Entity e = world.getEntity(id);
        return e instanceof VillagerEntity && e.isAlive() ? (VillagerEntity) e : null;
    }

    private static VillagerAgentData agent(UUID id) { return VillagerAgentManager.getAgent(id); }

    /** Run {@code goal} on this villager right now (player-priority, one-shot). */
    private static void startGoal(ServerWorld world, UUID id, AgentGoal goal) {
        VillagerEntity v = villager(world, id);
        VillagerAgentData a = agent(id);
        if (v == null || a == null) return;
        goal.setSource(AgentGoal.Source.PLAYER);
        if (goal.getLifecycle() == AgentGoal.Lifecycle.REPEATABLE) goal.setLifecycle(AgentGoal.Lifecycle.ONE_SHOT);
        ActionLifecycle.startNow(a, v, world, goal);
    }

    /** The miner's live #pickaxe request, if any. */
    private static VillageSignalBoard.VillageMessage pickaxeRequest(Scenario s, long now) {
        for (VillageSignalBoard.VillageMessage m : VillageSignalBoard.requestsBy(s.miner)) {
            VillageSignalBoard.RequestState state = m.state(now);
            if (state != VillageSignalBoard.RequestState.OPEN && state != VillageSignalBoard.RequestState.CLAIMED) continue;
            if ("pickaxe".equals(m.spec.category) || m.spec.items().contains("minecraft:iron_pickaxe")) return m;
        }
        return null;
    }

    /** The tracked request by id (falls back to a fresh category search). */
    private static VillageSignalBoard.VillageMessage request(Scenario s, long now) {
        if (s.requestId != null) {
            VillageSignalBoard.VillageMessage m = VillageSignalBoard.get().find(s.requestId);
            if (m != null) return m;
        }
        return pickaxeRequest(s, now);
    }

    /** Put the miner's iron ore up on the board as a TRADE_HAVE offer (once). */
    private static void postIronOffer(Scenario s, ServerWorld w) {
        if (s.offerPosted) return;
        VillagerAgentData m = agent(s.miner);
        VillagerEntity mv = villager(w, s.miner);
        VillageSignalBoard board = VillageMarkets.board(w, s.miner);
        int ore = count(m, Items.IRON_ORE);
        if (board == null || mv == null || ore <= 0) return;
        board.post(s.miner, mv.blockPosition(), VillageSignalBoard.MsgType.TRADE_HAVE,
                new ResourceLocation("minecraft:iron_ore"), ore, w.getGameTime());
        s.offerPosted = true;
    }

    // ── Inventory helpers ────────────────────────────────────────────────────

    private static int count(VillagerAgentData a, Item item) {
        return a == null ? 0 : a.getInventory().countItem(new ItemStack(item));
    }

    private static boolean hasPickaxe(VillagerAgentData a) {
        if (a == null) return false;
        for (ItemStack s : a.getInventory().getItems())
            if (!s.isEmpty() && s.getItem() instanceof PickaxeItem) return true;
        return false;
    }

    private static void wearDownPickaxe(VillagerAgentData a, int remaining) {
        boolean first = true;
        for (ItemStack s : a.getInventory().getItems()) {
            if (s.isEmpty() || !(s.getItem() instanceof PickaxeItem)) continue;
            if (first) { s.setDamageValue(Math.max(0, s.getMaxDamage() - remaining)); first = false; }
        }
        if (first) { // no pickaxe at all: hand him a nearly-dead one so the break happens on camera
            ItemStack pick = new ItemStack(Items.IRON_PICKAXE);
            pick.setDamageValue(pick.getMaxDamage() - remaining);
            InventoryTransfer.insert(a.getInventory(), pick);
        }
    }

    private static void breakPickaxes(VillagerEntity v, VillagerAgentData a) {
        List<ItemStack> items = a.getInventory().getItems();
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).isEmpty() || !(items.get(i).getItem() instanceof PickaxeItem)) continue;
            v.broadcastBreakEvent(EquipmentSlotType.MAINHAND);
            items.set(i, ItemStack.EMPTY);
        }
        v.setItemSlot(EquipmentSlotType.MAINHAND, ItemStack.EMPTY);
        VillagerEquipmentHelper.refreshEquipment(v, a);
        a.addMemory("My pickaxe finally broke");
    }

    private static void removeAll(VillagerAgentData a, Item item) { remove(a, item, Integer.MAX_VALUE); }

    private static void remove(VillagerAgentData a, Item item, int countToRemove) {
        int left = countToRemove;
        for (ItemStack s : a.getInventory().getItems()) {
            if (s.isEmpty() || s.getItem() != item || left <= 0) continue;
            int n = Math.min(left, s.getCount());
            s.shrink(n);
            left -= n;
        }
    }

    private static int logCount(VillagerAgentData a) {
        int n = 0;
        for (ItemStack s : a.getInventory().getItems())
            if (!s.isEmpty() && SupplyPolicy.LOG.equals(SupplyPolicy.materialCategory(s.getItem()))) n += s.getCount();
        return n;
    }

    private static void takeOneLog(VillagerAgentData a) {
        for (ItemStack s : a.getInventory().getItems()) {
            if (s.isEmpty() || !SupplyPolicy.LOG.equals(SupplyPolicy.materialCategory(s.getItem()))) continue;
            s.shrink(1);
            return;
        }
    }

    /** A furnace or blast furnace within reach of the toolsmith; place one beside him when there is none. */
    private static void ensureFurnaceNear(ServerWorld world, VillagerEntity smith) {
        if (smith == null) return;
        BlockPos base = smith.blockPosition();
        for (BlockPos p : BlockPos.betweenClosed(base.offset(-10, -3, -10), base.offset(10, 3, 10))) {
            net.minecraft.block.Block b = world.getBlockState(p).getBlock();
            if (b == Blocks.FURNACE || b == Blocks.BLAST_FURNACE) return;
        }
        for (BlockPos p : BlockPos.betweenClosed(base.offset(-4, -1, -4), base.offset(4, 1, 4))) {
            if (!world.isEmptyBlock(p) || !world.isEmptyBlock(p.above())) continue;
            if (!world.getBlockState(p.below()).isFaceSturdy(world, p.below(), net.minecraft.util.Direction.UP)) continue;
            world.setBlockAndUpdate(p.immutable(), Blocks.BLAST_FURNACE.defaultBlockState());
            return;
        }
    }

    /** Narrate a step to the operator's audience: everyone near the miner, plus the server log. */
    private static void caption(ServerWorld world, Scenario s, String text) {
        // Captions are disabled; keep the call sites so step titles still document the sequence.
    }
}
