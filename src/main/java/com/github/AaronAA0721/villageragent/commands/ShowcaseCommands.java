package com.github.AaronAA0721.villageragent.commands;

import com.github.AaronAA0721.villageragent.ai.VillageJobGuarantee;
import com.github.AaronAA0721.villageragent.ai.VillageMarkets;
import com.github.AaronAA0721.villageragent.ai.VillageRegistry;
import com.github.AaronAA0721.villageragent.ai.VillagerAgentData;
import com.github.AaronAA0721.villageragent.ai.VillagerAgentManager;
import com.github.AaronAA0721.villageragent.ai.behavior.SleepSkill;
import com.github.AaronAA0721.villageragent.config.ModConfig;
import com.github.AaronAA0721.villageragent.debug.ShowcaseSync;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.command.CommandSource;
import net.minecraft.command.Commands;
import net.minecraft.command.arguments.EntityArgument;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.merchant.villager.VillagerEntity;
import net.minecraft.entity.monster.ZombieEntity;
import net.minecraft.inventory.EquipmentSlotType;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.Direction;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MutableBoundingBox;
import net.minecraft.util.text.StringTextComponent;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.gen.Heightmap;
import net.minecraft.world.server.ServerWorld;

import java.util.List;
import java.util.Random;

/**
 * {@code /va showcase ...} — switches and scene triggers for recording the promotional video.
 * <ul>
 *   <li>{@code labels on|off} — fly-over villager cards (job, task, step, goal weights)</li>
 *   <li>{@code board on|off} — top-right notice board + request tags above villagers</li>
 *   <li>{@code jobs} — give the nearest village a farmer, lumberjack and miner right now, and teach them
 *       the village fields / nearby exposed ore</li>
 *   <li>{@code quarry} — build a small stone+ore outcrop near the village for the miner</li>
 *   <li>{@code breaktools [villager]} — break every tool the nearest (or given) villager carries</li>
 *   <li>{@code field} — plant a 9x9 irrigated wheat/carrot/potato field near the village for the farmer</li>
 *   <li>{@code monster day|night [villager]} — send a zombie after a villager (day: helmeted, no burning)</li>
 * </ul>
 */
public final class ShowcaseCommands {
    private static final Random RANDOM = new Random();

    private ShowcaseCommands() {}

    public static LiteralArgumentBuilder<CommandSource> build() {
        return Commands.literal("showcase")
            .then(Commands.literal("labels")
                .then(Commands.argument("on", BoolArgumentType.bool())
                    .executes(c -> toggle(c, "Showcase labels", true))))
            .then(Commands.literal("board")
                .then(Commands.argument("on", BoolArgumentType.bool())
                    .executes(c -> toggle(c, "Showcase board", false))))
            .then(Commands.literal("jobs").executes(ShowcaseCommands::jobs))
            .then(Commands.literal("quarry").executes(ShowcaseCommands::quarry))
            .then(Commands.literal("field").executes(ShowcaseCommands::field))
            .then(Commands.literal("breaktools")
                .executes(c -> breakTools(c, null))
                .then(Commands.argument("villager", EntityArgument.entity())
                    .executes(c -> breakTools(c, EntityArgument.getEntity(c, "villager")))))
            .then(Commands.literal("hunger")
                .then(Commands.argument("amount", FloatArgumentType.floatArg(0f, 100f))
                    .executes(c -> hunger(c, FloatArgumentType.getFloat(c, "amount"), null))
                    .then(Commands.argument("villager", EntityArgument.entity())
                        .executes(c -> hunger(c, FloatArgumentType.getFloat(c, "amount"),
                                EntityArgument.getEntity(c, "villager"))))))
            .then(Commands.literal("fatigue")
                .then(Commands.argument("amount", FloatArgumentType.floatArg(0f, 100f))
                    .executes(c -> fatigue(c, FloatArgumentType.getFloat(c, "amount"), null))
                    .then(Commands.argument("villager", EntityArgument.entity())
                        .executes(c -> fatigue(c, FloatArgumentType.getFloat(c, "amount"),
                                EntityArgument.getEntity(c, "villager"))))))
            .then(Commands.literal("clearfatigue").executes(ShowcaseCommands::clearFatigue))
            .then(Commands.literal("monster")
                .then(Commands.literal("day")
                    .executes(c -> monster(c, true, null))
                    .then(Commands.argument("villager", EntityArgument.entity())
                        .executes(c -> monster(c, true, EntityArgument.getEntity(c, "villager")))))
                .then(Commands.literal("night")
                    .executes(c -> monster(c, false, null))
                    .then(Commands.argument("villager", EntityArgument.entity())
                        .executes(c -> monster(c, false, EntityArgument.getEntity(c, "villager"))))));
    }

    private static int toggle(CommandContext<CommandSource> c, String name, boolean labels) {
        boolean on = BoolArgumentType.getBool(c, "on");
        if (labels) ModConfig.SHOWCASE_LABELS.set(on); else ModConfig.SHOWCASE_BOARD.set(on);
        ModConfig.SPEC.save();
        c.getSource().sendSuccess(new StringTextComponent(TextFormatting.GREEN + name + ": "
                + (on ? "ON" : "OFF")), true);
        return 1;
    }

    private static VillageMarkets.Market nearestMarket(CommandContext<CommandSource> c) {
        ServerWorld world = c.getSource().getLevel();
        BlockPos at = new BlockPos(c.getSource().getPosition());
        VillageMarkets.Market target = null;
        double best = Double.MAX_VALUE;
        for (VillageMarkets.Market market : VillageMarkets.loaded(world)) {
            double d = market.center.distSqr(at);
            if (d < best) { best = d; target = market; }
        }
        if (target == null)
            c.getSource().sendFailure(new StringTextComponent("No loaded village with managed villagers nearby."));
        return target;
    }

    private static int quarry(CommandContext<CommandSource> c) {
        ServerWorld world = c.getSource().getLevel();
        VillageMarkets.Market target = nearestMarket(c);
        if (target == null) return 0;
        BlockPos at = VillageJobGuarantee.buildOutcrop(world, target);
        if (at == null) {
            c.getSource().sendFailure(new StringTextComponent("No flat 5x4 spot 18-40 blocks from the village centre."));
            return 0;
        }
        c.getSource().sendSuccess(new StringTextComponent(TextFormatting.GOLD + "Ore outcrop built at "
                + at.getX() + "," + at.getY() + "," + at.getZ()), true);
        for (String line : VillageJobGuarantee.shareWorkKnowledge(world, target, true))
            c.getSource().sendSuccess(new StringTextComponent(TextFormatting.YELLOW + line), false);
        return 1;
    }

    private static int field(CommandContext<CommandSource> c) {
        ServerWorld world = c.getSource().getLevel();
        VillageMarkets.Market target = nearestMarket(c);
        if (target == null) return 0;
        BlockPos at = VillageJobGuarantee.buildField(world, target);
        if (at == null) {
            c.getSource().sendFailure(new StringTextComponent("No flat 11x11 grass/dirt spot 10-30 blocks from the village centre."));
            return 0;
        }
        c.getSource().sendSuccess(new StringTextComponent(TextFormatting.GOLD + "Field planted at "
                + at.getX() + "," + at.getY() + "," + at.getZ()), true);
        for (String line : VillageJobGuarantee.shareWorkKnowledge(world, target, true))
            c.getSource().sendSuccess(new StringTextComponent(TextFormatting.YELLOW + line), false);
        return 1;
    }

    /**
     * Break every tool (hoe, pickaxe, axe, shovel, sword, fishing rod, shears) in the nearest managed villager's
     * inventory, with the vanilla break sound and particles. The villager then has to replace them (restock or
     * a notice-board request), which is a quick way to show the request system on camera.
     */
    private static int breakTools(CommandContext<CommandSource> c, Entity chosen) {
        ServerWorld world = c.getSource().getLevel();
        VillagerEntity target;
        if (chosen != null) {
            if (!(chosen instanceof VillagerEntity) || VillagerAgentManager.getAgent(chosen.getUUID()) == null) {
                c.getSource().sendFailure(new StringTextComponent("Target must be a managed villager."));
                return 0;
            }
            target = (VillagerEntity) chosen;
        } else {
            target = ShowcaseSync.nearestManagedVillager(world, new BlockPos(c.getSource().getPosition()), 160);
        }
        if (target == null) {
            c.getSource().sendFailure(new StringTextComponent("No managed villager within 160 blocks."));
            return 0;
        }
        VillagerAgentData agent = VillagerAgentManager.getAgent(target.getUUID());
        List<ItemStack> items = agent.getInventory().getItems();
        java.util.List<String> broken = new java.util.ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            ItemStack stack = items.get(i);
            if (stack.isEmpty() || com.github.AaronAA0721.villageragent.ai.SupplyPolicy.toolCategory(stack.getItem()) == null) continue;
            broken.add(stack.getHoverName().getString() + (stack.getCount() > 1 ? " x" + stack.getCount() : ""));
            // Vanilla break effect (sound + item particles) at the villager.
            target.broadcastBreakEvent(EquipmentSlotType.MAINHAND);
            items.set(i, ItemStack.EMPTY);
        }
        // Clear any held/visual copies, then let the equipment helper re-pick from what is left.
        target.setItemSlot(EquipmentSlotType.MAINHAND, ItemStack.EMPTY);
        target.setItemSlot(EquipmentSlotType.OFFHAND, ItemStack.EMPTY);
        com.github.AaronAA0721.villageragent.ai.VillagerEquipmentHelper.refreshEquipment(target, agent);
        String who = target.getName().getString();
        if (broken.isEmpty()) {
            c.getSource().sendSuccess(new StringTextComponent(TextFormatting.YELLOW + who + " has no tools to break."), false);
            return 0;
        }
        agent.addMemory("All my tools broke");
        c.getSource().sendSuccess(new StringTextComponent(TextFormatting.GOLD + "Broke " + who + "'s tools: "
                + String.join(", ", broken)), true);
        return broken.size();
    }

    /**
     * Lower the nearest (or given) managed villager's hunger by {@code amount} points (0-100 scale).
     * Dropping below the eat threshold makes the villager seek food, useful for filming the hunger loop.
     */
    private static int hunger(CommandContext<CommandSource> c, float amount, Entity chosen) {
        VillagerEntity target = managedTarget(c, chosen);
        if (target == null) return 0;
        VillagerAgentData agent = VillagerAgentManager.getAgent(target.getUUID());
        float before = agent.getHunger();
        agent.setHunger(before - amount);
        c.getSource().sendSuccess(new StringTextComponent(TextFormatting.GOLD + target.getName().getString()
                + " hunger: " + (int) before + " -> " + (int) agent.getHunger() + " (-" + amount + ")"), true);
        return 1;
    }

    /**
     * Raise the nearest (or given) managed villager's fatigue by {@code amount} points (0-100 scale).
     * High fatigue pushes the villager toward a rest/sleep goal, useful for filming the rest loop.
     */
    private static int fatigue(CommandContext<CommandSource> c, float amount, Entity chosen) {
        VillagerEntity target = managedTarget(c, chosen);
        if (target == null) return 0;
        VillagerAgentData agent = VillagerAgentManager.getAgent(target.getUUID());
        float before = agent.getFatigue();
        agent.setFatigue(before + amount);
        c.getSource().sendSuccess(new StringTextComponent(TextFormatting.GOLD + target.getName().getString()
                + " fatigue: " + (int) before + " -> " + (int) agent.getFatigue() + " (+" + amount + ")"), true);
        return 1;
    }

    /** Reset every managed villager's fatigue to 0 (fully rested). */
    private static int clearFatigue(CommandContext<CommandSource> c) {
        int cleared = 0;
        for (VillagerAgentData agent : VillagerAgentManager.getAllAgents()) {
            if (agent.getFatigue() > 0) { agent.setFatigue(0f); cleared++; }
        }
        c.getSource().sendSuccess(new StringTextComponent(TextFormatting.GREEN + "Cleared fatigue on "
                + cleared + " villager(s)."), true);
        return cleared;
    }

    /** Resolve the target villager: the explicitly chosen entity, else the nearest managed villager. */
    private static VillagerEntity managedTarget(CommandContext<CommandSource> c, Entity chosen) {
        ServerWorld world = c.getSource().getLevel();
        if (chosen != null) {
            if (!(chosen instanceof VillagerEntity) || VillagerAgentManager.getAgent(chosen.getUUID()) == null) {
                c.getSource().sendFailure(new StringTextComponent("Target must be a managed villager."));
                return null;
            }
            return (VillagerEntity) chosen;
        }
        VillagerEntity target = ShowcaseSync.nearestManagedVillager(world, new BlockPos(c.getSource().getPosition()), 160);
        if (target == null) c.getSource().sendFailure(new StringTextComponent("No managed villager within 160 blocks."));
        return target;
    }

    private static int jobs(CommandContext<CommandSource> c) {
        ServerWorld world = c.getSource().getLevel();
        VillageRegistry registry = VillageRegistry.get(world);
        VillageMarkets.Market target = nearestMarket(c);
        if (target == null) return 0;
        List<String> report = VillageJobGuarantee.ensure(world, target);
        boolean allPresent = report.isEmpty();
        report.addAll(VillageJobGuarantee.shareWorkKnowledge(world, target, true));
        MutableBoundingBox box = registry.bounds(target.id);
        c.getSource().sendSuccess(new StringTextComponent(TextFormatting.GOLD + "Village at "
                + target.center.getX() + "," + target.center.getZ()
                + (box == null ? "" : " (" + (box.x1 - box.x0 + 1) + "x" + (box.z1 - box.z0 + 1) + ")")
                + ": " + target.members().size() + " villagers"), false);
        if (allPresent)
            c.getSource().sendSuccess(new StringTextComponent(TextFormatting.GREEN
                    + "Farmer, lumberjack and miner are all present."), false);
        for (String line : report) c.getSource().sendSuccess(new StringTextComponent(TextFormatting.YELLOW + line), false);
        return 1;
    }

    private static int monster(CommandContext<CommandSource> c, boolean day, Entity chosen) throws CommandSyntaxException {
        ServerWorld world = c.getSource().getLevel();
        VillagerEntity target;
        if (chosen != null) {
            if (!(chosen instanceof VillagerEntity) || VillagerAgentManager.getAgent(chosen.getUUID()) == null) {
                c.getSource().sendFailure(new StringTextComponent("Target must be a managed villager."));
                return 0;
            }
            target = (VillagerEntity) chosen;
        } else {
            target = ShowcaseSync.nearestManagedVillager(world, new BlockPos(c.getSource().getPosition()), 160);
        }
        if (target == null) {
            c.getSource().sendFailure(new StringTextComponent("No managed villager within 160 blocks."));
            return 0;
        }
        BlockPos spawn = spawnPoint(world, target.blockPosition());
        if (spawn == null) {
            c.getSource().sendFailure(new StringTextComponent("No open ground 10-14 blocks from the villager."));
            return 0;
        }
        ZombieEntity zombie = EntityType.ZOMBIE.create(world);
        if (zombie == null) return 0;
        zombie.moveTo(spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5, RANDOM.nextFloat() * 360F, 0F);
        zombie.setBaby(false);
        if (day) {
            // A helmet keeps a zombie from burning in daylight.
            zombie.setItemSlot(EquipmentSlotType.HEAD, new ItemStack(Items.IRON_HELMET));
            zombie.setDropChance(EquipmentSlotType.HEAD, 0F);
        }
        zombie.setPersistenceRequired();
        world.addFreshEntity(zombie);
        zombie.setTarget(target);

        VillagerAgentData agent = VillagerAgentManager.getAgent(target.getUUID());
        String name = agent != null ? agent.getName() : "villager";
        boolean night = SleepSkill.isNightTime(world.getDayTime());
        c.getSource().sendSuccess(new StringTextComponent(TextFormatting.GOLD + "Zombie sent after " + name
                + " from " + spawn.getX() + "," + spawn.getY() + "," + spawn.getZ()), true);
        if (day && night)
            c.getSource().sendSuccess(new StringTextComponent(TextFormatting.YELLOW
                    + "Tip: it is night. Use /time set day for the daytime gathering scene."), false);
        if (!day && !night)
            c.getSource().sendSuccess(new StringTextComponent(TextFormatting.YELLOW
                    + "Tip: it is day. Use /time set night and wait for everyone to be in bed first."), false);
        if (!day && !ModConfig.NIGHT_CURFEW.get())
            c.getSource().sendSuccess(new StringTextComponent(TextFormatting.YELLOW
                    + "Tip: night_curfew is off; enable it with /va config set nightcurfew true."), false);
        return 1;
    }

    /** Open, dry, standable surface 10-14 blocks from {@code origin}. */
    private static BlockPos spawnPoint(ServerWorld world, BlockPos origin) {
        for (int attempt = 0; attempt < 48; attempt++) {
            double angle = RANDOM.nextDouble() * Math.PI * 2;
            double distance = 10 + RANDOM.nextDouble() * 4;
            int x = origin.getX() + (int) Math.round(Math.cos(angle) * distance);
            int z = origin.getZ() + (int) Math.round(Math.sin(angle) * distance);
            if (!world.hasChunk(x >> 4, z >> 4)) continue;
            BlockPos pos = world.getHeightmapPos(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, new BlockPos(x, 0, z));
            if (Math.abs(pos.getY() - origin.getY()) > 6) continue;
            if (!world.isEmptyBlock(pos) || !world.isEmptyBlock(pos.above())
                    || !world.getFluidState(pos.below()).isEmpty()
                    || !world.getBlockState(pos.below()).isFaceSturdy(world, pos.below(), Direction.UP)) continue;
            return pos;
        }
        return null;
    }
}
