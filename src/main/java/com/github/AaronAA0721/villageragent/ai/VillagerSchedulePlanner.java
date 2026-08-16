package com.github.AaronAA0721.villageragent.ai;

import com.github.AaronAA0721.villageragent.ai.harness.DecisionJournal;
import com.github.AaronAA0721.villageragent.ai.harness.HarnessDecisionPlanner;
import com.github.AaronAA0721.villageragent.ai.harness.LLMGuard;
import net.minecraft.entity.merchant.villager.VillagerEntity;
import net.minecraft.world.server.ServerWorld;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
// VillagerNeedsSystem is in the same package — no explicit import needed, but referenced directly.

/**
 * Manages the daily planning lifecycle for each villager:
 * <ol>
 *   <li>At dawn (dayTime 0–2000) generates a new {@link DailySchedule} via LLM (once per day).</li>
 *   <li>Every slow tick: applies the current scheduled task to guide the agent's activity.</li>
 *   <li>At nightfall (dayTime 13000–15000) generates a brief evening reflection (once per day).</li>
 * </ol>
 */
public class VillagerSchedulePlanner {

    private static final Logger LOGGER = LogManager.getLogger();

    private static final long MINECRAFT_DAY     = 24_000L;
    private static final long PLAN_WINDOW_END   =  2_000L; // plan fires in first 2000 ticks of day
    private static final long REFLECT_START     = 13_000L;
    private static final long REFLECT_END       = 15_000L;

    // ── Public entry point ───────────────────────────────────────────────────

    public static void tick(ServerWorld world, VillagerEntity villager, VillagerAgentData agent) {
        long gameTime = world.getGameTime();
        long dayTime  = world.getDayTime() % MINECRAFT_DAY;
        long today    = gameTime / MINECRAFT_DAY;

        // 1. Daily planning at dawn
        if (agent.getLastDayPlanned() < today && dayTime < PLAN_WINDOW_END) {
            agent.setLastDayPlanned(today); // prevent LLM spam before async response
            generateDailyPlan(agent, today, world);
            // P1: let the harness decide one structured daily goal (gated by harness_drive_daily_goal).
            HarnessDecisionPlanner.decideDailyGoal(agent, villager, world, gameTime);
        }

        // 2. Apply current task to guide agent behaviour
        DailySchedule schedule = agent.getDailySchedule();
        if (schedule != null) {
            DailySchedule.ScheduledTask task = schedule.getCurrentTask(dayTime);
            if (task != null) {
                applyTaskToAgent(agent, task);
            }
        }

        // 3. Evening reflection — once per day, when villager is not busy
        if (dayTime >= REFLECT_START && dayTime < REFLECT_END
                && agent.getLastDayReflected() < today
                && agent.getCurrentAction() == null) {
            agent.setLastDayReflected(today);
            generateReflection(agent, world);
        }
    }

    // ── Plan generation ──────────────────────────────────────────────────────

    private static void generateDailyPlan(VillagerAgentData agent, long dayNumber, ServerWorld world) {
        String env = agent.getEnvironmentSummary();
        String envText = (env != null && !env.isEmpty()) ? env : "a Minecraft village";

        List<String> memories = agent.getMemories();
        StringBuilder memSb = new StringBuilder();
        int memStart = Math.max(0, memories.size() - 3);
        for (int i = memStart; i < memories.size(); i++) {
            memSb.append("- ").append(memories.get(i)).append("\n");
        }

        String systemPrompt = "You are a Minecraft villager creating your daily schedule. "
                + "Be practical and stay in character. Respond ONLY with the schedule, no extra text.";

        String needsMood = VillagerNeedsSystem.buildNeedsDescription(agent);
        String userPrompt = "Name: " + agent.getName() + "\n"
                + "Profession: " + agent.getProfession() + "\n"
                + "Personality: " + agent.getPersonality() + "\n"
                + "Current mood/feelings: " + (needsMood.isEmpty() ? "feeling normal" : needsMood) + "\n"
                + "Environment: " + envText + "\n"
                + "Recent memories:\n" + memSb + "\n"
                + "Create a daily schedule that reflects your mood and needs. Format EACH line EXACTLY as:\n"
                + "morning: [activity] - [description]\n"
                + "afternoon: [activity] - [description]\n"
                + "evening: [activity] - [description]\n"
                + "night: [activity] - [description]\n"
                + "Activities must be ONE of: farming, socializing, crafting, exploring, resting";

        long tick = world.getGameTime();
        UUID id = agent.getVillagerId();
        LLMGuard.query(id, tick, systemPrompt, userPrompt).thenAccept(response -> {
            DecisionJournal.record(id, DecisionJournal.Kind.SCHEDULE, tick, userPrompt,
                    response, DecisionJournal.outcome(response));
            DailySchedule schedule = parseDailyPlan(response, dayNumber);
            if (schedule.getTasks().isEmpty()) {
                schedule = createFallbackPlan(agent.getProfession(), dayNumber);
            }
            agent.setDailySchedule(schedule);
            agent.addMemory("Today's plan: " + schedule.toSummaryString());
            LOGGER.info(agent.getName() + " planned their day: " + schedule.toSummaryString());
        }).exceptionally(e -> {
            LOGGER.warn(agent.getName() + " plan failed, using fallback: " + e.getMessage());
            agent.setDailySchedule(createFallbackPlan(agent.getProfession(), dayNumber));
            return null;
        });
    }

    // ── Parse LLM response ───────────────────────────────────────────────────

    private static DailySchedule parseDailyPlan(String response, long dayNumber) {
        DailySchedule schedule = new DailySchedule(dayNumber);
        for (String raw : response.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            int colonIdx = line.indexOf(":");
            if (colonIdx <= 0) continue;
            String slot = line.substring(0, colonIdx).trim().toLowerCase();
            if (!isValidSlot(slot)) continue;
            String rest    = line.substring(colonIdx + 1).trim();
            int dashIdx    = rest.indexOf(" - ");
            String activity, description;
            if (dashIdx > 0) {
                activity    = rest.substring(0, dashIdx).trim().toLowerCase();
                description = rest.substring(dashIdx + 3).trim();
            } else {
                activity    = rest.split(" ")[0].toLowerCase();
                description = rest;
            }
            if (isValidActivity(activity)) {
                schedule.addTask(new DailySchedule.ScheduledTask(slot, activity, description));
            }
        }
        return schedule;
    }

    private static boolean isValidSlot(String s) {
        return s.equals("morning") || s.equals("afternoon") || s.equals("evening") || s.equals("night");
    }

    private static boolean isValidActivity(String a) {
        return a.equals("farming") || a.equals("socializing") || a.equals("crafting")
                || a.equals("exploring") || a.equals("resting");
    }

    // ── Fallback plan (no LLM) ───────────────────────────────────────────────

    private static DailySchedule createFallbackPlan(String profession, long dayNumber) {
        DailySchedule s = new DailySchedule(dayNumber);
        if ("farmer".equalsIgnoreCase(profession)) {
            s.addTask(new DailySchedule.ScheduledTask("morning",   "farming",    "Check and harvest mature crops"));
            s.addTask(new DailySchedule.ScheduledTask("afternoon", "farming",    "Plant seeds in empty farmland"));
            s.addTask(new DailySchedule.ScheduledTask("evening",   "socializing","Chat with neighbours"));
            s.addTask(new DailySchedule.ScheduledTask("night",     "resting",    "Sleep until dawn"));
        } else {
            s.addTask(new DailySchedule.ScheduledTask("morning",   "crafting",   "Work at the workshop"));
            s.addTask(new DailySchedule.ScheduledTask("afternoon", "socializing","Visit the village centre"));
            s.addTask(new DailySchedule.ScheduledTask("evening",   "resting",    "Wind down for the evening"));
            s.addTask(new DailySchedule.ScheduledTask("night",     "resting",    "Sleep peacefully"));
        }
        return s;
    }

    // ── Task execution (guide agent activity) ────────────────────────────────

    /**
     * Translates the scheduled task into {@code agent.scheduledActivity} without
     * overriding actions already in progress (farming, combat, etc.).
     */
    private static void applyTaskToAgent(VillagerAgentData agent, DailySchedule.ScheduledTask task) {
        if (agent.getCurrentAction() != null) return; // busy — don't interfere
        if (agent.isInFarmingState()) return;

        String activity = task.getActivity();
        String current  = agent.getCurrentActivity();
        // Don't override active farming/fighting labels
        if (("farming".equals(activity) || "farming".equals(current))
                && ("harvesting".equals(current) || "planting".equals(current))) return;
        if ("fighting".equals(current)) return;

        agent.setScheduledActivity(activity);
    }

    // ── Evening reflection ───────────────────────────────────────────────────

    private static void generateReflection(VillagerAgentData agent, ServerWorld world) {
        List<String> memories = agent.getMemories();
        if (memories.isEmpty()) return;

        int start = Math.max(0, memories.size() - 8);
        StringBuilder memSb = new StringBuilder();
        for (int i = start; i < memories.size(); i++) {
            memSb.append("- ").append(memories.get(i)).append("\n");
        }

        // Outstanding long-term commitments — the thing reflection should actually audit,
        // so the villager re-expresses persistent intentions as short-term todos.
        List<LongTermAgenda> activeAgendas = new ArrayList<>();
        for (LongTermAgenda a : agent.getAgendas()) if (!a.isResolved()) activeAgendas.add(a);

        String systemPrompt = "You are a Minecraft villager reflecting on your day. Stay in character. "
                + "Write one reflective sentence first, then append task lines only if instructed.";

        StringBuilder userPrompt = new StringBuilder();
        userPrompt.append(agent.getName()).append(" is a ").append(agent.getProfession()).append(".\n");
        userPrompt.append("Today's events:\n").append(memSb).append("\n");

        if (!activeAgendas.isEmpty()) {
            userPrompt.append("Your outstanding long-term commitments (persistent, not yet finished):\n");
            for (LongTermAgenda a : activeAgendas) {
                userPrompt.append("- ").append(a.describe()).append("\n");
            }
            userPrompt.append("\nWhile reflecting, if any commitment is actionable now, write it as a ")
                    .append("TODO line (each on its own line) using EXACTLY one of: ")
                    .append("TODO: craft minecraft:<item> x<n>  |  TODO: gather minecraft:<item> x<n>  |  TODO: move <x> <y> <z>  |  ")
                    .append("TODO: goto house  |  TODO: goto cave_house  |  TODO: goto building  |  ")
                    .append("TODO: trade villager <profession-or-any>  |  TODO: socialize  |  ")
                    .append("TODO: converse player <uuid>  |  TODO: converse villager <profession-or-any> . ")
                    .append("If a commitment is an unpaid debt, go collect it: write TODO: converse player <the debtor's uuid> . ")
                    .append("If nothing is actionable, write no TODO line. ")
                    .append("Write your reflective sentence FIRST, then the TODO lines (if any).\n");
        } else {
            userPrompt.append("Write one reflective sentence about today.");
        }

        String prompt = userPrompt.toString();
        long tick = world.getGameTime();
        UUID id = agent.getVillagerId();
        LLMGuard.query(id, tick, systemPrompt, prompt).thenAccept(reflection -> {
            DecisionJournal.record(id, DecisionJournal.Kind.REFLECTION, tick, prompt,
                    reflection, DecisionJournal.outcome(reflection));
            if (reflection == null || LLMService.isFailure(reflection) || reflection.trim().isEmpty()) return;

            // Derive short-term todos from the reflection (advancing long-term agendas).
            List<AgentGoal> derived = TodoParser.parse(reflection, agent);
            for (AgentGoal g : derived) {
                if (agent.getGoals().size() < VillagerAgentManager.getMaxGoals()) {
                    agent.getGoals().add(g);
                    agent.addMemory("Reflection goal: " + g.getDescription());
                }
            }
            if (!derived.isEmpty()) {
                LOGGER.info(agent.getName() + " derived {} goal(s) from reflection", derived.size());
            }

            // Keep only the spoken sentence as the reflection memory — TODO lines stay hidden.
            String visible = TodoParser.stripTodoLines(reflection);
            if (!visible.trim().isEmpty()) {
                agent.addMemory("[Reflection] " + visible.trim());
                LOGGER.info(agent.getName() + " reflected: " + visible.trim());
            }
        }).exceptionally(e -> {
            LOGGER.debug(agent.getName() + " reflection failed: " + e.getMessage());
            return null;
        });
    }
}


