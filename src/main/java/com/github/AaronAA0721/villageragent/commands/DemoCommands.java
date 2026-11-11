package com.github.AaronAA0721.villageragent.commands;

import com.github.AaronAA0721.villageragent.ai.ActionLifecycle;
import com.github.AaronAA0721.villageragent.ai.AgentGoal;
import com.github.AaronAA0721.villageragent.ai.GoalDraft;
import com.github.AaronAA0721.villageragent.ai.GoalText;
import com.github.AaronAA0721.villageragent.ai.LlmGoalCatalog;
import com.github.AaronAA0721.villageragent.ai.LlmGoalIntake;
import com.github.AaronAA0721.villageragent.ai.ShowcaseScenario;
import com.github.AaronAA0721.villageragent.ai.VillageMarkets;
import com.github.AaronAA0721.villageragent.ai.VillagerAgentData;
import com.github.AaronAA0721.villageragent.ai.VillagerAgentManager;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.command.CommandSource;
import net.minecraft.command.Commands;
import net.minecraft.command.arguments.EntityArgument;
import net.minecraft.command.arguments.ItemArgument;
import net.minecraft.entity.Entity;
import net.minecraft.entity.merchant.villager.VillagerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.StringTextComponent;
import net.minecraft.world.server.ServerWorld;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * {@code /va demo ...} — one-line scene starters for filming the mod. Everything here goes through the same
 * machinery the villagers use on their own: goals are parsed by {@link LlmGoalCatalog} (exactly as if the LLM
 * had written them), admitted by {@link LlmGoalIntake}, and started at player priority.
 *
 * <pre>
 * /va demo invite <host> <guest> [delay_seconds]   host invites guest to a shared meal at the host's spot
 * /va demo gift <from> <to> <item> [count]         from walks to to and hands the item over (if spareable)
 * /va demo goal <villager> <json>                  inject any LLM-style goal JSON (same syntax the LLM uses)
 * /va demo pickaxe [miner] [toolsmith] [lumberjack]  staged supply-chain film: broken pickaxe → board →
 *                                                    iron+sticks → smelt → craft → delivery (auto-casts
 *                                                    by profession from the nearest village when omitted)
 * /va demo stop                                    stop the running pickaxe scenario
 * </pre>
 * Two-villager item exchanges already have a command: {@code /va trade <a> <b> <itemA> <countA> <itemB> <countB>}.
 */
public final class DemoCommands {
    private DemoCommands() {}

    public static LiteralArgumentBuilder<CommandSource> build() {
        return Commands.literal("demo")
            .then(Commands.literal("invite")
                .then(Commands.argument("host", EntityArgument.entity())
                    .then(Commands.argument("guest", EntityArgument.entity())
                        .executes(c -> invite(c, 30))
                        .then(Commands.argument("delay_seconds", IntegerArgumentType.integer(5, 1200))
                            .executes(c -> invite(c, IntegerArgumentType.getInteger(c, "delay_seconds")))))))
            .then(Commands.literal("gift")
                .then(Commands.argument("from", EntityArgument.entity())
                    .then(Commands.argument("to", EntityArgument.entity())
                        .then(Commands.argument("item", ItemArgument.item())
                            .executes(c -> gift(c, 1))
                            .then(Commands.argument("count", IntegerArgumentType.integer(1, 64))
                                .executes(c -> gift(c, IntegerArgumentType.getInteger(c, "count"))))))))
            .then(Commands.literal("goal")
                .then(Commands.argument("villager", EntityArgument.entity())
                    .then(Commands.argument("json", StringArgumentType.greedyString())
                        .executes(DemoCommands::goal))))
            .then(Commands.literal("pickaxe")
                .executes(DemoCommands::pickaxeAuto)
                .then(Commands.argument("miner", EntityArgument.entity())
                    .then(Commands.argument("toolsmith", EntityArgument.entity())
                        .then(Commands.argument("lumberjack", EntityArgument.entity())
                            .executes(DemoCommands::pickaxePicked)))))
            .then(Commands.literal("stop").executes(c -> ShowcaseScenario.stop(c.getSource())));
    }

    // ── invite: a preset shared meal, through the LLM goal pipeline ──────────

    private static int invite(CommandContext<CommandSource> c, int delaySeconds) throws CommandSyntaxException {
        VillagerEntity host = managed(c, "host"), guest = managed(c, "guest");
        if (host == null || guest == null || host == guest)
            return fail(c, "Pick two different managed adult villagers.");
        VillagerAgentData hostAgent = VillagerAgentManager.getAgent(host.getUUID());
        VillagerAgentData guestAgent = VillagerAgentManager.getAgent(guest.getUUID());
        BlockPos spot = host.blockPosition();

        JsonObject move = new JsonObject();
        move.addProperty("type", "move");
        move.addProperty("x", spot.getX()); move.addProperty("y", spot.getY()); move.addProperty("z", spot.getZ());
        JsonObject eat = new JsonObject(); eat.addProperty("type", "eat");
        JsonObject chat = new JsonObject(); chat.addProperty("type", "socialize");
        JsonArray steps = new JsonArray(); steps.add(move); steps.add(eat); steps.add(chat);
        JsonObject activity = new JsonObject();
        activity.addProperty("type", "sequence");
        activity.addProperty("in_seconds", delaySeconds);
        activity.add("steps", steps);
        JsonArray guests = new JsonArray(); guests.add(guestAgent.getName());
        JsonObject root = new JsonObject();
        root.addProperty("type", "invite");
        root.add("villagers", guests);
        root.addProperty("title", "Shared meal");
        root.addProperty("message", "Join me for a bite to eat at my spot?");
        root.add("activity", activity);
        return inject(c, host, hostAgent, root);
    }

    // ── gift: hand an item over, spare-stock rules included ──────────────────

    private static int gift(CommandContext<CommandSource> c, int count) throws CommandSyntaxException {
        VillagerEntity from = managed(c, "from"), to = managed(c, "to");
        if (from == null || to == null || from == to)
            return fail(c, "Pick two different managed adult villagers.");
        VillagerAgentData fromAgent = VillagerAgentManager.getAgent(from.getUUID());
        JsonObject root = new JsonObject();
        root.addProperty("type", "gift");
        root.addProperty("villager", VillagerAgentManager.getAgent(to.getUUID()).getName());
        root.addProperty("item", ItemArgument.getItem(c, "item").getItem().getRegistryName().toString());
        root.addProperty("qty", count);
        return inject(c, from, fromAgent, root);
    }

    // ── goal: any LLM-style goal JSON ────────────────────────────────────────

    private static int goal(CommandContext<CommandSource> c) throws CommandSyntaxException {
        VillagerEntity v = managed(c, "villager");
        if (v == null) return fail(c, "Pick a managed adult villager.");
        VillagerAgentData agent = VillagerAgentManager.getAgent(v.getUUID());
        JsonObject root;
        try {
            root = new JsonParser().parse(StringArgumentType.getString(c, "json")).getAsJsonObject();
        } catch (RuntimeException bad) {
            return fail(c, "Not a JSON object. Same syntax the LLM uses, e.g. {\"type\":\"eat\"}.");
        }
        return inject(c, v, agent, root);
    }

    /** Parse like an LLM reply, admit through the intake, start the first accepted goal right away. */
    private static int inject(CommandContext<CommandSource> c, VillagerEntity v, VillagerAgentData agent,
                              JsonObject root) {
        GoalDraft draft = LlmGoalCatalog.parse(root);
        if (draft == null) return fail(c, "Unknown or malformed goal type (see the LLM goal list in the docs).");
        ServerWorld world = c.getSource().getLevel();
        List<LlmGoalIntake.Outcome> outcomes = LlmGoalIntake.process(agent, Collections.singletonList(draft),
                v, world, "command:demo", true);
        int accepted = 0;
        for (LlmGoalIntake.Outcome o : outcomes) {
            if (o.accepted) {
                accepted++;
                say(c, "§a✔ added §f[" + o.type + "] " + GoalText.label(o.goal, agent.getVillagerId()));
                if (o.goal != null && !o.goal.isHoldUntilScheduled()) ActionLifecycle.startNow(agent, v, world, o.goal);
                else say(c, "§7(waits for its scheduled time)");
            } else {
                say(c, "§c✘ rejected [" + o.type + "] §7" + o.reason);
            }
        }
        return accepted;
    }

    // ── pickaxe scenario ─────────────────────────────────────────────────────

    private static int pickaxePicked(CommandContext<CommandSource> c) throws CommandSyntaxException {
        return ShowcaseScenario.start(c.getSource(), asVillager(EntityArgument.getEntity(c, "miner")),
                asVillager(EntityArgument.getEntity(c, "toolsmith")),
                asVillager(EntityArgument.getEntity(c, "lumberjack")));
    }

    private static int pickaxeAuto(CommandContext<CommandSource> c) {
        ServerWorld world = c.getSource().getLevel();
        BlockPos at = new BlockPos(c.getSource().getPosition());
        VillageMarkets.Market market = null;
        double best = Double.MAX_VALUE;
        for (VillageMarkets.Market m : VillageMarkets.loaded(world)) {
            double d = m.center.distSqr(at);
            if (d < best) { best = d; market = m; }
        }
        if (market == null) return fail(c, "No loaded village market nearby.");
        VillagerEntity miner = byProfession(world, market, "miner");
        VillagerEntity toolsmith = byProfession(world, market, "toolsmith");
        VillagerEntity lumberjack = byProfession(world, market, "lumberjack");
        if (miner == null || toolsmith == null || lumberjack == null)
            return fail(c, "The nearest village needs a miner, a toolsmith and a lumberjack (see /va showcase jobs).");
        return ShowcaseScenario.start(c.getSource(), miner, toolsmith, lumberjack);
    }

    private static VillagerEntity byProfession(ServerWorld world, VillageMarkets.Market market, String profession) {
        for (VillagerAgentData a : market.members()) {
            if (a == null || !profession.equalsIgnoreCase(a.getProfession())) continue;
            Entity e = world.getEntity(a.getVillagerId());
            if (e instanceof VillagerEntity && e.isAlive()) return (VillagerEntity) e;
        }
        return null;
    }

    // ── Shared bits ──────────────────────────────────────────────────────────

    private static VillagerEntity managed(CommandContext<CommandSource> c, String arg) throws CommandSyntaxException {
        VillagerEntity v = asVillager(EntityArgument.getEntity(c, arg));
        return v != null && VillagerAgentManager.getAgent(v.getUUID()) != null ? v : null;
    }

    private static VillagerEntity asVillager(Entity e) {
        return e instanceof VillagerEntity && e.isAlive() && !((VillagerEntity) e).isBaby()
                && !((VillagerEntity) e).isNoAi() ? (VillagerEntity) e : null;
    }

    private static void say(CommandContext<CommandSource> c, String line) {
        c.getSource().sendSuccess(new StringTextComponent(line), false);
    }

    private static int fail(CommandContext<CommandSource> c, String line) {
        c.getSource().sendFailure(new StringTextComponent(line));
        return 0;
    }
}
