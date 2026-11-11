package com.github.AaronAA0721.villageragent.ai;

import net.minecraft.world.server.ServerWorld;

import java.util.*;

/**
 * Villager full names ("first last"). 64 first names x 64 surnames; no two residents of one village share a full
 * name. A villager still carrying a legacy single-word fallback name gets a full name on its next slow update.
 */
public final class VillagerNames {
    private static final String[] FIRST = {
            "Aldric", "Beatrice", "Cedric", "Diana", "Edmund", "Fiona", "Gregory", "Helena",
            "Isaac", "Juliana", "Kenneth", "Lydia", "Marcus", "Natalia", "Oliver", "Penelope",
            "Quentin", "Rosalind", "Silas", "Theodora", "Ulric", "Vivian", "Walter", "Yvette",
            "Ambrose", "Bridget", "Conrad", "Delphine", "Elias", "Florence", "Gideon", "Harriet",
            "Ivo", "Josephine", "Kasimir", "Leonora", "Matthias", "Nora", "Osric", "Prudence",
            "Reginald", "Sabine", "Tobias", "Ursula", "Victor", "Wilhelmina", "Agnes", "Bertram",
            "Clara", "Dorian", "Eleanor", "Felix", "Greta", "Hugo", "Imogen", "Jasper",
            "Katrin", "Lucian", "Maud", "Nikolas", "Odette", "Percival", "Rowena", "Stellan"
    };
    private static final String[] LAST = {
            "Ashford", "Blackwood", "Carter", "Dunmore", "Ellery", "Fairbairn", "Greenway", "Hawthorne",
            "Ironside", "Jessop", "Kingsley", "Larkin", "Millward", "Northcott", "Oakley", "Pemberton",
            "Quarry", "Redfern", "Stonebridge", "Thatcher", "Underhill", "Vance", "Whitlock", "Yardley",
            "Archer", "Bramble", "Cooper", "Dale", "Eastwood", "Fletcher", "Gardener", "Hayward",
            "Ingram", "Joiner", "Kemp", "Lockwood", "Mason", "Nettle", "Orchard", "Potter",
            "Rook", "Sawyer", "Tanner", "Upton", "Weaver", "Wheatley", "Abbot", "Barley",
            "Chandler", "Dyer", "Elmsworth", "Forester", "Grange", "Holloway", "Ivory", "Kettle",
            "Lowell", "Marsh", "Newbold", "Oldham", "Pickering", "Roper", "Shepherd", "Thornbury"
    };
    private static final Set<String> LEGACY = new HashSet<>(Arrays.asList(
            "Aldric", "Beatrice", "Cedric", "Diana", "Edmund", "Fiona", "Gregory", "Helena", "Isaac",
            "Juliana", "Kenneth", "Lydia", "Marcus", "Natalia", "Oliver", "Penelope", "Quentin", "Rosalind"));
    private static final Random RANDOM = new Random();

    private VillagerNames() {}

    /** A random full name not in {@code taken}. */
    public static String random(Set<String> taken) {
        for (int i = 0; i < 200; i++) {
            String name = FIRST[RANDOM.nextInt(FIRST.length)] + " " + LAST[RANDOM.nextInt(LAST.length)];
            if (!taken.contains(name)) return name;
        }
        for (String f : FIRST) for (String l : LAST) if (!taken.contains(f + " " + l)) return f + " " + l;
        return FIRST[RANDOM.nextInt(FIRST.length)] + " " + LAST[RANDOM.nextInt(LAST.length)] + " " + (taken.size() + 1);
    }

    /** A random full name not used by any loaded managed villager. */
    public static String random() {
        Set<String> taken = new HashSet<>();
        for (VillagerAgentData a : VillagerAgentManager.getAllAgents()) if (a != null && a.getName() != null) taken.add(a.getName());
        return random(taken);
    }

    /**
     * Give a legacy single-word name a surname, and rename this villager when another resident of its village
     * already has the same full name (the villager with the larger UUID is renamed, so exactly one keeps it).
     */
    public static void ensureUnique(ServerWorld world, net.minecraft.entity.merchant.villager.VillagerEntity villager,
                                    VillagerAgentData agent) {
        ensureUniqueName(world, agent);
        // Keep the name tag in step (also picks up a late LLM-generated name).
        String shown = villager.getCustomName() == null ? null : villager.getCustomName().getString();
        if (agent.getName() != null && !agent.getName().equals(shown))
            villager.setCustomName(new net.minecraft.util.text.StringTextComponent(agent.getName()));
    }

    private static void ensureUniqueName(ServerWorld world, VillagerAgentData agent) {
        String name = agent.getName();
        List<VillagerAgentData> residents = VillageMarkets.residents(world, agent.getVillagerId());
        Set<String> taken = new HashSet<>();
        boolean clash = false;
        for (VillagerAgentData other : residents) {
            if (other == null || other == agent || other.getVillagerId().equals(agent.getVillagerId())) continue;
            if (other.getName() == null) continue;
            taken.add(other.getName());
            if (other.getName().equals(name) && other.getVillagerId().compareTo(agent.getVillagerId()) < 0) clash = true;
        }
        boolean legacy = name == null || name.trim().isEmpty() || LEGACY.contains(name.trim());
        if (!legacy && !clash) return;
        String renamed = residents.isEmpty() ? random() : random(taken);
        BrainLog.diagnostic(agent, "name-assigned", "from=" + name + " to=" + renamed + (clash ? " reason=duplicate" : " reason=surname"));
        agent.setName(renamed);
    }
}
