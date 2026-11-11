package com.github.AaronAA0721.villageragent.commands;

import com.github.AaronAA0721.villageragent.ai.ChatReplyProcessor;
import com.github.AaronAA0721.villageragent.ai.GameClock;
import com.github.AaronAA0721.villageragent.ai.GoalText;
import com.github.AaronAA0721.villageragent.ai.KnownVillagers;
import com.github.AaronAA0721.villageragent.ai.LLMSetupReminder;
import com.github.AaronAA0721.villageragent.ai.LlmGoalIntake;
import com.github.AaronAA0721.villageragent.ai.VillagerAgentData;
import com.github.AaronAA0721.villageragent.ai.VillagerAgentManager;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.command.CommandSource;
import net.minecraft.command.Commands;
import net.minecraft.command.arguments.EntityArgument;
import net.minecraft.entity.Entity;
import net.minecraft.entity.merchant.villager.VillagerEntity;
import net.minecraft.util.Util;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.StringTextComponent;
import net.minecraft.world.server.ServerWorld;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Talk to a villager's LLM from a command and see exactly what comes back.
 *
 * <pre>
 * /va ask     [villager] [message]   send it as player chat and apply the reply (goals are added)
 * /va preview [villager] [message]   same prompt, but only report what would be added
 * </pre>
 * Without a villager the nearest one within 16 blocks of the caller is used. Without a message a
 * built-in test asks for a timed group meal: invite the nearest known villager to walk to this
 * villager's spot at one in-game hour from now and eat there (an {@code invite} whose activity is a
 * timed {@code sequence} of {@code move} → {@code eat}).
 *
 * <p>The prompt is the real chat prompt ({@link VillagerAgentData#generateChatResponse}) and the reply
 * goes through the same {@link ChatReplyProcessor} as the chat panel; the report shows the raw reply,
 * whether it was JSON, and the fate of every goal (grounded steps, or the rejection reason).
 */
public final class LlmPlanCommands {
    private static final double NEAREST_RANGE = 16.0;
    private static final int RAW_PREVIEW_CHARS = 600;

    private LlmPlanCommands() {}

    public static LiteralArgumentBuilder<CommandSource> ask() { return build("ask", true); }
    public static LiteralArgumentBuilder<CommandSource> preview() { return build("preview", false); }

    private static LiteralArgumentBuilder<CommandSource> build(String name, boolean apply) {
        return Commands.literal(name)
                .executes(c -> run(c, nearestVillager(c), null, apply))
                .then(Commands.argument("villager", EntityArgument.entity())
                        .executes(c -> run(c, villagerArg(c), null, apply))
                        .then(Commands.argument("message", StringArgumentType.greedyString())
                                .executes(c -> run(c, villagerArg(c), StringArgumentType.getString(c, "message"), apply))));
    }

    private static int run(CommandContext<CommandSource> c, VillagerEntity villager, String message, boolean apply) {
        CommandSource src = c.getSource();
        if (villager == null) return fail(src, "No villager: look at one within " + (int) NEAREST_RANGE
                + " blocks, or pass a selector such as @e[type=minecraft:villager,sort=nearest,limit=1].");
        if (!LLMSetupReminder.isConfigured()) return fail(src, "The LLM is not configured (see /va llm status).");
        VillagerAgentData agent = VillagerAgentManager.getAgent(villager.getUUID());
        if (agent == null) return fail(src, "That villager has no agent data yet.");

        ServerWorld world = (ServerWorld) villager.level;
        String text = message != null && !message.trim().isEmpty() ? message.trim() : defaultTest(agent, villager, world);
        Entity caller = src.getEntity();
        String speaker = caller != null ? caller.getName().getString() : "Traveler";
        UUID speakerId = caller != null ? caller.getUUID() : Util.NIL_UUID;
        long tick = world.getGameTime();

        say(src, "§b[" + (apply ? "ask" : "preview") + "] §7" + speaker + " → " + agent.getName()
                + " (" + GameClock.format(world.getDayTime()) + "): §f" + text);
        agent.generateChatResponse(speaker, text, tick, villager, speakerId)
                .thenAccept(raw -> world.getServer().execute(() -> report(src, agent, villager, world, raw, apply)));
        return 1;
    }

    /** Server thread: process the reply exactly like chat (or as a dry run) and print the outcome. */
    private static void report(CommandSource src, VillagerAgentData agent, VillagerEntity villager,
                               ServerWorld world, String raw, boolean apply) {
        ChatReplyProcessor.Result r = ChatReplyProcessor.process(agent, raw, world.getGameTime(), villager, world,
                apply ? "command:ask" : "command:preview", apply);
        say(src, "§e" + agent.getName() + ": §f" + r.parsed.reply);
        if (!r.parsed.thought.isEmpty()) say(src, "§8thought: " + r.parsed.thought);
        say(src, "§8raw (" + (r.parsed.jsonParsed ? "JSON" : "not JSON: TODO:-line fallback") + "): "
                + abbreviate(raw == null ? "" : raw.replaceAll("\\s+", " ")));

        if (r.goals.isEmpty()) {
            say(src, "§6No goals in the reply.");
            return;
        }
        long now = world.getGameTime();
        for (LlmGoalIntake.Outcome o : r.goals) {
            if (o.accepted) {
                say(src, "§a✔ " + (apply ? "added " : "would add ") + "§f[" + o.type + "] "
                        + GoalText.label(o.goal, agent.getVillagerId())
                        + (o.goal.getTitle() != null ? " \u00A78(" + o.goal.getDescription() + ")" : "")
                        + timing(o.goal, now));
            } else {
                say(src, "§c✘ rejected [" + o.type + "] §7" + o.reason
                        + (o.goal != null ? " §8(" + o.goal.getDescription() + ")" : ""));
            }
            if (o.goal != null) for (String line : GoalText.planLines(o.goal, "   ")) say(src, line);
        }
        if (apply) say(src, "§7Open " + agent.getName() + "'s chat panel → Goals to watch it run.");
    }

    /** The built-in test: a timed shared meal at this villager's spot. */
    private static String defaultTest(VillagerAgentData agent, VillagerEntity villager, ServerWorld world) {
        BlockPos spot = villager.blockPosition();
        String at = GameClock.format(world.getDayTime() + 1000); // one in-game hour (~50 s) from now
        String where = spot.getX() + " " + spot.getY() + " " + spot.getZ();
        List<KnownVillagers.Entry> known = KnownVillagers.near(agent, villager, world);
        if (known.isEmpty()) {
            return "At " + at + ", walk to " + where + " and then eat your lunch there. Plan it as one sequence.";
        }
        return "Please invite " + known.get(0).agent.getName() + " to have lunch with you at " + where + " at " + at
                + ". Both of you should walk there first and then eat.";
    }

    private static String timing(com.github.AaronAA0721.villageragent.ai.AgentGoal g, long now) {
        if (!g.isHoldUntilScheduled() || g.getScheduledTick() == Long.MAX_VALUE) return "";
        return " §d(starts in " + GameClock.duration(g.getScheduledTick() - now)
                + (g.hasEndTime() ? ", lasts " + GameClock.duration(g.getEndTick() - g.getScheduledTick()) : "") + ")";
    }

    private static VillagerEntity villagerArg(CommandContext<CommandSource> c) throws CommandSyntaxException {
        Entity e = EntityArgument.getEntity(c, "villager");
        return e instanceof VillagerEntity && e.isAlive() ? (VillagerEntity) e : null;
    }

    private static VillagerEntity nearestVillager(CommandContext<CommandSource> c) {
        Entity caller = c.getSource().getEntity();
        if (caller == null) return null;
        AxisAlignedBB box = caller.getBoundingBox().inflate(NEAREST_RANGE);
        return c.getSource().getLevel().getEntitiesOfClass(VillagerEntity.class, box, v -> v.isAlive() && !v.isBaby())
                .stream().min(Comparator.comparingDouble(caller::distanceToSqr)).orElse(null);
    }

    private static String abbreviate(String s) {
        return s.length() <= RAW_PREVIEW_CHARS ? s : s.substring(0, RAW_PREVIEW_CHARS) + "…";
    }

    private static void say(CommandSource src, String line) {
        src.sendSuccess(new StringTextComponent(line), false);
    }

    private static int fail(CommandSource src, String line) {
        src.sendFailure(new StringTextComponent(line));
        return 0;
    }
}
