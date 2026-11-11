package com.github.AaronAA0721.villageragent.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Display lines (with § colour codes) for the inner structure of a goal: the steps of a
 * {@code sequence} and the invitees/activity of an {@code invite}. Shared by the chat-panel goal
 * list and the {@code /va ask|preview} report. Server side (names are looked up by id).
 */
public final class GoalText {
    private GoalText() {}

    /**
     * Human-readable title of {@code g} as seen by villager {@code self}, or null when the goal has none:
     * "Dinner with Bram" (shared plan; names of the other participants), "Cook meat (asked by Rowena)"
     * (an ask, seen by the one asked), "Invite Bram: Dinner" / "Ask Ann: Cook meat" (the invite goal itself).
     */
    public static String title(AgentGoal g, UUID self) {
        if ("invite".equals(g.getGoalType()) && g.getInvitePlan() != null) {
            AgentGoal plan = g.getInvitePlan();
            String t = plan.getTitle() != null ? plan.getTitle() : g.getTitle();
            if (t == null) return null;
            List<String> names = new ArrayList<>();
            for (UUID id : g.getInvitees()) names.add(KnownVillagers.nameOf(id));
            return (plan.isShared() ? "Invite " : "Ask ") + String.join(", ", names) + ": " + t;
        }
        String t = g.getTitle();
        if (t == null || g.getGroupId() == null) return t;
        if (g.isShared()) {
            List<String> others = GroupActivities.participantNames(g.getGroupId(), self);
            return others.isEmpty() ? t : t + " with " + String.join(", ", others);
        }
        if (g.getLeaderId() != null && !g.getLeaderId().equals(self)) {
            return t + " (asked by " + KnownVillagers.nameOf(g.getLeaderId()) + ")";
        }
        return t;
    }

    /** Title if any, otherwise the description: the one-line label for prompts and reports. */
    public static String label(AgentGoal g, UUID self) {
        String t = title(g, self);
        return t != null ? t : g.getDescription();
    }

    /** Lines describing {@code g}'s steps and invitation, each prefixed by {@code indent}; empty for simple goals. */
    public static List<String> planLines(AgentGoal g, String indent) {
        List<String> out = new ArrayList<>();
        steps(out, g, indent);
        if (!g.getInvitees().isEmpty()) {
            StringBuilder inv = new StringBuilder("§8" + indent + "invitees: ");
            for (int k = 0; k < g.getInvitees().size(); k++) {
                if (k > 0) inv.append(", ");
                inv.append(k < g.getInviteIndex() ? "§a✔" : "§7")
                   .append(KnownVillagers.nameOf(g.getInvitees().get(k))).append("§8");
            }
            out.add(inv.toString());
            if (g.getInvitePlan() != null) {
                out.add("§8" + indent + "activity: §7[" + g.getInvitePlan().getGoalType() + "] "
                        + safe(g.getInvitePlan().getDescription()));
                steps(out, g.getInvitePlan(), indent + "  ");
            }
        }
        return out;
    }

    /** One line per step: done ✔, current ▶, pending ·. */
    private static void steps(List<String> out, AgentGoal g, String indent) {
        if (!g.hasSteps()) return;
        for (int k = 0; k < g.getSteps().size(); k++) {
            String mark = k < g.getStepIndex() ? "§a✔ " : k == g.getStepIndex() ? "§e▶ " : "§8· ";
            AgentGoal step = g.getSteps().get(k);
            out.add("§8" + indent + (k + 1) + ". " + mark + "§7[" + step.getGoalType() + "] " + safe(step.getDescription()));
        }
    }

    private static String safe(String s) { return s == null ? "" : s; }
}
