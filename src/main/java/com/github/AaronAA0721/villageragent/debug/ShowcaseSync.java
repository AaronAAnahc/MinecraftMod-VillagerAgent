package com.github.AaronAA0721.villageragent.debug;

import com.github.AaronAA0721.villageragent.ai.*;
import com.github.AaronAA0721.villageragent.config.ModConfig;
import com.github.AaronAA0721.villageragent.network.ModNetworking;
import com.github.AaronAA0721.villageragent.network.ShowcasePacket;
import net.minecraft.entity.Entity;
import net.minecraft.entity.merchant.villager.VillagerEntity;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MutableBoundingBox;
import net.minecraft.world.server.ServerWorld;
import net.minecraftforge.fml.network.PacketDistributor;

import java.util.*;

/**
 * Server-side producer of the promotional overlay ({@code showcase_labels} / {@code showcase_board}).
 * Twice a second each player receives cards for villagers around them and the notice board of the
 * village they are in. Read-only: nothing here changes villager state.
 */
public final class ShowcaseSync {
    private static final int INTERVAL = 10;

    /** Tag colours (ARGB) shared with the client legend. */
    public static final int TAG_REQUEST = 0xFFED93B1, TAG_PRODUCE = 0xFFFAC775, TAG_DELIVER = 0xFF5DCAA5,
            TAG_RECEIVE = 0xFF85B7EB, TAG_BOARD = 0xFFDDDDDD, TAG_DANGER = 0xFFF09595;

    private ShowcaseSync() {}

    public static void tick(ServerWorld world) {
        boolean labels = ModConfig.SHOWCASE_LABELS.get(), board = ModConfig.SHOWCASE_BOARD.get();
        if (!labels && !board) return;
        if (world.getGameTime() % INTERVAL != 0) return;
        int range = ModConfig.SHOWCASE_LABEL_RANGE.get();
        for (ServerPlayerEntity player : world.getServer().getPlayerList().getPlayers()) {
            if (player.level != world) continue;
            ModNetworking.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), build(world, player, range, labels, board));
        }
    }

    private static ShowcasePacket build(ServerWorld world, ServerPlayerEntity player, int range, boolean labels, boolean board) {
        long now = world.getGameTime();
        List<ShowcasePacket.Card> cards = new ArrayList<>();
        AxisAlignedBB area = player.getBoundingBox().inflate(range, Math.max(range, 128), range);
        List<VillagerEntity> villagers = world.getEntitiesOfClass(VillagerEntity.class, area);
        villagers.sort(Comparator.comparingDouble(player::distanceToSqr));
        for (VillagerEntity v : villagers) {
            if (cards.size() >= ShowcasePacket.MAX_CARDS) break;
            VillagerAgentData agent = VillagerAgentManager.getAgent(v.getUUID());
            if (agent == null || v.isBaby()) continue;
            String[] tag = board ? requestTag(world, v, agent, now) : new String[]{"", "0"};
            cards.add(new ShowcasePacket.Card(v.getId(), agent.getName(), job(agent), labels ? task(v, agent) : "",
                    labels ? step(agent) : "", tag[0], (int) Long.parseLong(tag[1]),
                    labels ? weights(agent, now) : Collections.emptyList()));
        }

        List<ShowcasePacket.Row> rows = new ArrayList<>();
        String title = "";
        int open = 0, claimed = 0, delivered = 0;
        boolean hasVillage = false;
        if (board) {
            VillageMarkets.Market market = marketAt(world, player.blockPosition());
            if (market != null) {
                hasVillage = true;
                BlockPos pos = VillageRegistry.get(world).noticeBoard(market.id);
                title = "Village Notice Board" + (pos != null ? "  (" + pos.getX() + ", " + pos.getZ() + ")" : "")
                        + "  · " + market.members().size() + " villagers";
                List<VillageSignalBoard.VillageMessage> requests = new ArrayList<>();
                for (VillageSignalBoard.VillageMessage m : market.board.messagesOfType(VillageSignalBoard.MsgType.TRADE_WANT)) {
                    if (!m.isRequest()) continue;
                    VillageSignalBoard.RequestState state = m.state(now);
                    if (state == VillageSignalBoard.RequestState.EXPIRED) continue;
                    if (state == VillageSignalBoard.RequestState.OPEN) open++;
                    else if (state == VillageSignalBoard.RequestState.CLAIMED) claimed++;
                    else delivered++;
                    requests.add(m);
                }
                requests.sort(Comparator.comparingInt((VillageSignalBoard.VillageMessage m) -> m.state(now).ordinal())
                        .thenComparing(Comparator.comparingLong((VillageSignalBoard.VillageMessage m) -> m.postedTick).reversed()));
                for (VillageSignalBoard.VillageMessage m : requests) {
                    if (rows.size() >= ShowcasePacket.MAX_ROWS) break;
                    VillageSignalBoard.RequestState state = m.state(now);
                    String status; int color;
                    if (state == VillageSignalBoard.RequestState.OPEN) {
                        status = "OPEN"; color = TAG_REQUEST;
                    } else if (state == VillageSignalBoard.RequestState.CLAIMED) {
                        status = "CLAIMED → " + name(m.claim() == null ? null : m.claim().provider); color = TAG_DELIVER;
                    } else {
                        status = "DELIVERED by " + name(m.claim() == null ? null : m.claim().provider); color = 0xFF97C459;
                    }
                    rows.add(new ShowcasePacket.Row(prettyItem(m.spec.key()), m.quantity, name(m.author), status, color));
                }
            }
        }
        return new ShowcasePacket(labels, board, cards, hasVillage, title, open, claimed, delivered, rows);
    }

    // ── Card fields ──────────────────────────────────────────────────────

    private static String job(VillagerAgentData agent) {
        String p = agent.getProfession();
        if (p == null || p.isEmpty() || p.toLowerCase(Locale.ROOT).contains("unemployed") || "none".equalsIgnoreCase(p))
            return "Unemployed";
        return Character.toUpperCase(p.charAt(0)) + p.substring(1);
    }

    private static String task(VillagerEntity v, VillagerAgentData agent) {
        VillagerAction action = agent.getCurrentAction();
        if (action != null && !action.isGoalDriven()) {
            switch (action.getActionType()) {
                case ATTACK: return "Fighting off a monster";
                case FLEE: return "Fleeing & calling for help";
                case RALLY: return "Answering the alarm";
                case PLACE: case BREAK: case BUILD: return "Building";
                default: break;
            }
        }
        if (v.isSleeping()) return "Sleeping";
        AgentGoal running = agent.getRunningGoal();
        if (running == null) return "Idle";
        return describe(running);
    }

    private static String step(VillagerAgentData agent) {
        StringBuilder chain = new StringBuilder();
        TaskExecution.Frame frame = agent.brain().tasks.root();
        if (frame != null) frame = frame.child();
        for (; frame != null; frame = frame.child()) {
            if (chain.length() > 0) chain.append(" › ");
            chain.append(pretty(frame.goal.getGoalType()));
        }
        VillagerAction action = agent.getCurrentAction();
        String detail = action == null ? "" : action.getDescription();
        if (chain.length() == 0) return detail.isEmpty() ? "-" : detail;
        return detail.isEmpty() ? chain.toString() : chain + ": " + detail;
    }

    private static List<ShowcasePacket.Weight> weights(VillagerAgentData agent, long now) {
        Map<String, Double> byType = new LinkedHashMap<>();
        for (AgentGoal g : agent.getGoals()) {
            double u = GoalScheduler.utility(agent, g, now);
            if (u > 0) byType.merge(g.getGoalType(), u, Double::sum);
        }
        List<ShowcasePacket.Weight> list = new ArrayList<>();
        for (Map.Entry<String, Double> e : byType.entrySet())
            list.add(new ShowcasePacket.Weight(e.getKey(), e.getValue().floatValue()));
        list.sort((a, b) -> Float.compare(b.score, a.score));
        return list;
    }

    /** Request-system state as {text, colour}. Empty text when the villager is not part of it right now. */
    private static String[] requestTag(ServerWorld world, VillagerEntity v, VillagerAgentData agent, long now) {
        VillageSignalBoard board = VillageMarkets.board(world, v.getUUID());
        // Combat is the most important thing to show.
        VillagerAction action = agent.getCurrentAction();
        if (action != null && action.getActionType() == VillagerAction.ActionType.FLEE)
            return tag("! Being chased - calling for help", TAG_DANGER);
        // Provider side: a supply response anywhere in the running task tree.
        for (TaskExecution.Frame f = agent.brain().tasks.root(); f != null; f = f.child()) {
            AgentGoal g = f.goal;
            if (CooperativeSupply.RESPONSE_GOAL.equals(g.getGoalType())) return supplyTag(board, agent, g);
        }
        AgentGoal running = agent.getRunningGoal();
        if (running != null && CooperativeSupply.RESPONSE_GOAL.equals(running.getGoalType()))
            return supplyTag(board, agent, running);
        if (running != null && ("meet_trade".equals(running.getGoalType()) || "deliver_trade".equals(running.getGoalType())))
            return tag("Meeting to hand over goods", TAG_DELIVER);
        // Requester side: its own live request on the board.
        if (board != null) {
            for (VillageSignalBoard.VillageMessage m : board.messagesOfType(VillageSignalBoard.MsgType.TRADE_WANT)) {
                if (!m.isRequest() || !v.getUUID().equals(m.author)) continue;
                VillageSignalBoard.RequestState state = m.state(now);
                if (state == VillageSignalBoard.RequestState.OPEN)
                    return tag("Requested " + prettyItem(m.spec.key()) + " x" + m.quantity, TAG_REQUEST);
                if (state == VillageSignalBoard.RequestState.CLAIMED)
                    return tag("Receiving " + prettyItem(m.spec.key()) + " x" + m.quantity + " ← "
                            + name(m.claim() == null ? null : m.claim().provider), TAG_RECEIVE);
            }
        }
        if (VillageNoticeBoards.atBoard(world, v)) return tag("Reading the notice board", TAG_BOARD);
        return tag("", 0);
    }

    private static String[] supplyTag(VillageSignalBoard board, VillagerAgentData agent, AgentGoal g) {
        VillageSignalBoard.VillageMessage m = board == null || g.getTradeMessageId() == null ? null : board.find(g.getTradeMessageId());
        String spec = g.getRequestSpec() != null ? prettyItem(g.getRequestSpec())
                : m != null && m.spec != null ? prettyItem(m.spec.key()) : "goods";
        int units = g.getRequestUnits() > 0 ? g.getRequestUnits() : m != null ? m.quantity : 0;
        String requester = name(m == null ? null : m.author);
        boolean delivering = g.getClaimId() != null || (m != null && m.claim() != null
                && agent.getVillagerId().equals(m.claim().provider));
        String what = spec + (units > 0 ? " x" + units : "");
        return delivering ? tag("Delivering " + what + " → " + requester, TAG_DELIVER)
                : tag("Making " + what + " for " + requester, TAG_PRODUCE);
    }

    private static String[] tag(String text, int color) { return new String[]{text, Long.toString(color & 0xFFFFFFFFL)}; }

    // ── Helpers ──────────────────────────────────────────────────────────

    private static VillageMarkets.Market marketAt(ServerWorld world, BlockPos pos) {
        VillageRegistry registry = VillageRegistry.get(world);
        VillageMarkets.Market nearest = null;
        double best = 160 * 160;
        for (VillageMarkets.Market market : VillageMarkets.loaded(world)) {
            MutableBoundingBox box = registry.bounds(market.id);
            if (box != null && pos.getX() >= box.x0 - 16 && pos.getX() <= box.x1 + 16
                    && pos.getZ() >= box.z0 - 16 && pos.getZ() <= box.z1 + 16) return market;
            double dx = market.center.getX() - pos.getX(), dz = market.center.getZ() - pos.getZ();
            if (dx * dx + dz * dz < best) { best = dx * dx + dz * dz; nearest = market; }
        }
        return nearest;
    }

    private static String name(UUID id) {
        if (id == null) return "?";
        VillagerAgentData agent = VillagerAgentManager.getAgent(id);
        return agent != null ? agent.getName() : "someone";
    }

    static String describe(AgentGoal goal) {
        String base = pretty(goal.getGoalType());
        String item = goal.getTargetItem();
        if (CooperativeSupply.RESPONSE_GOAL.equals(goal.getGoalType()) && goal.getRequestSpec() != null)
            return "Supply request: " + prettyItem(goal.getRequestSpec())
                    + (goal.getRequestUnits() > 0 ? " x" + goal.getRequestUnits() : "");
        if (item != null && !item.isEmpty())
            return base + ": " + prettyItem(item) + (goal.getTargetQuantity() > 0 ? " x" + goal.getTargetQuantity() : "");
        return base;
    }

    static String pretty(String type) {
        if (type == null || type.isEmpty()) return "-";
        switch (type) {
            case "lumberjack_work": return "Logging";
            case "miner_work": return "Mining";
            case "farm": return "Farming";
            case "rest": case "sleep": return "Going to bed";
            case "acquire_item": return "Acquire";
            case "supply_request": return "Supply request";
            case "find_job_site": return "Finding a workstation";
            case "collect_crops": return "Collecting crops";
            case "meet_trade": return "Trade meeting";
            default:
                String s = type.replace('_', ' ');
                return Character.toUpperCase(s.charAt(0)) + s.substring(1);
        }
    }

    static String prettyItem(String key) {
        if (key == null) return "?";
        if (key.indexOf(SupplyPolicy.ALT_QTY) >= 0) return SupplyPolicy.describe(key, ShowcaseSync::prettyItem);
        String s = key.startsWith("minecraft:") ? key.substring("minecraft:".length()) : key;
        int colon = s.indexOf(':');
        if (colon >= 0 && !s.startsWith("#")) s = s.substring(colon + 1);
        return s.replace('_', ' ');
    }

    /** Entity lookup helper for commands. */
    public static VillagerEntity nearestManagedVillager(ServerWorld world, BlockPos pos, double radius) {
        VillagerEntity best = null;
        double bestD = radius * radius;
        for (VillagerAgentData agent : VillagerAgentManager.getAllAgents()) {
            Entity e = world.getEntity(agent.getVillagerId());
            if (!(e instanceof VillagerEntity) || !e.isAlive() || ((VillagerEntity) e).isBaby()) continue;
            double d = e.blockPosition().distSqr(pos);
            if (d < bestD) { bestD = d; best = (VillagerEntity) e; }
        }
        return best;
    }
}
