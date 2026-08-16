package com.github.AaronAA0721.villageragent.ai;

import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Parses structured TODO directives out of an LLM reply so that a villager can turn
 * conversation into executable goals. The same parser is used for both player-driven chat
 * and the villager's own autonomous thinking.
 *
 * <p>Directive format (one per line, own line, case-insensitive type):
 * <pre>
 *   TODO: craft minecraft:iron_pickaxe x2
 *   TODO: move 120 64 200
 *   TODO: gather minecraft:wheat x10
 *   TODO: goto house
 *   TODO: socialize
 *   TODO: trade
 * </pre>
 * These lines are stripped from the player-visible reply (see {@link #stripTodoLines}).
 */
public final class TodoParser {

    private TodoParser() {}

    public static List<AgentGoal> parse(String text, VillagerAgentData agent) {
        List<AgentGoal> out = new ArrayList<>();
        if (text == null || text.isEmpty()) return out;

        for (String raw : text.split("\n")) {
            String line = raw.trim();
            if (!line.toUpperCase().startsWith("TODO:")) continue;
            String body = line.substring(5).trim();
            String[] tok = body.split("\\s+");
            if (tok.length == 0) continue;
            String type = tok[0].toLowerCase();

            switch (type) {
                case "craft":
                case "gather": {
                    if (tok.length < 2) break;
                    String item = tok[1];
                    int qty = 1;
                    if (tok.length >= 3 && tok[2].startsWith("x")) {
                        try { qty = Integer.parseInt(tok[2].substring(1)); } catch (NumberFormatException ignored) {}
                    }
                    AgentGoal g = new AgentGoal(type, "TODO: " + type + " " + item, 5);
                    g.setTargetItem(item);
                    g.setTargetQuantity(qty);
                    g.setImportance(6);
                    out.add(g);
                    break;
                }
                case "move": {
                    if (tok.length < 4) break;
                    try {
                        int x = Integer.parseInt(tok[1]);
                        int y = Integer.parseInt(tok[2]);
                        int z = Integer.parseInt(tok[3]);
                        AgentGoal g = new AgentGoal("move", "TODO: move to " + x + "," + y + "," + z, 5);
                        g.setTargetLocation(new BlockPos(x, y, z));
                        g.setImportance(6);
                        out.add(g);
                    } catch (NumberFormatException ignored) {}
                    break;
                }
                case "goto": {
                    // Navigate to the nearest building of a given type (or any building).
                    String btype = tok.length >= 2 ? tok[1].toLowerCase() : "any";
                    AgentGoal g = new AgentGoal("goto", "TODO: goto " + btype, 5);
                    g.setTargetBuildingType(btype);
                    g.setImportance(6);
                    out.add(g);
                    break;
                }
                case "converse": {
                    if (tok.length < 2) break;
                    String kind = tok[1].toLowerCase();
                    if (!kind.equals("player") && !kind.equals("villager")) break;
                    AgentGoal g = new AgentGoal("converse", "TODO: converse " + kind, 5);
                    g.setTargetKind(kind);
                    g.setImportance(6);
                    if (tok.length >= 3 && !tok[2].equalsIgnoreCase("any")) {
                        try {
                            g.setTargetEntityId(UUID.fromString(tok[2]));
                        } catch (IllegalArgumentException e) {
                            g.setTargetProfession(tok[2]);
                        }
                    }
                    out.add(g);
                    break;
                }
                case "socialize": {
                    AgentGoal g = new AgentGoal(type, "TODO: " + type, 5);
                    g.setImportance(5);
                    out.add(g);
                    break;
                }
                case "trade": {
                    // Optional target: TODO: trade villager <profession-or-any>
                    AgentGoal g = new AgentGoal("trade", "TODO: trade", 5);
                    g.setImportance(5);
                    if (tok.length >= 2) {
                        String kind = tok[1].toLowerCase();
                        if (kind.equals("villager")) {
                            g.setTargetKind("villager");
                            if (tok.length >= 3 && !tok[2].equalsIgnoreCase("any")) {
                                g.setTargetProfession(tok[2]);
                            }
                        }
                    }
                    out.add(g);
                    break;
                }
                default:
                    break; // unknown directive type — ignore
            }
        }
        return out;
    }

    /** Remove TODO: lines so the player only sees the spoken reply. */
    public static String stripTodoLines(String text) {
        if (text == null || text.isEmpty()) return text;
        StringBuilder sb = new StringBuilder();
        for (String line : text.split("\n")) {
            if (line.trim().toUpperCase().startsWith("TODO:")) continue;
            if (sb.length() > 0) sb.append("\n");
            sb.append(line);
        }
        return sb.toString().trim();
    }
}
