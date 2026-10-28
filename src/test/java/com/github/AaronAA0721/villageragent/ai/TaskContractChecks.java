package com.github.AaronAA0721.villageragent.ai;

import com.github.AaronAA0721.villageragent.ai.proficiency.*;
import java.util.*;

/** Contract, skill migration and recursion invariants without a Minecraft server. */
public final class TaskContractChecks {
    private static int checks;
    private static void check(boolean value, String message) {
        checks++; if (!value) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        CapabilityPool pool = new CapabilityPool();
        pool.setLevel(CapabilityId.GATHERING, 5);
        check(pool.levelOf(CapabilityId.WOODCUTTING) == 0, "gathering does not enable logging");
        check(pool.levelOf(CapabilityId.MINING) == 0 && pool.levelOf(CapabilityId.QUARRYING) == 0,
                "mining and quarrying start independently at zero");
        pool.setLevel(CapabilityId.MINING, 2);
        CapabilityPool restored = new CapabilityPool(); restored.deserializeNBT(pool.serializeNBT());
        check(restored.levelOf(CapabilityId.GATHERING) == 5 && restored.levelOf(CapabilityId.MINING) == 2,
                "old and new skill IDs round trip without renaming");
        check(restored.levelOf(CapabilityId.WOODCUTTING) == 0 && restored.levelOf(CapabilityId.QUARRYING) == 0,
                "missing NBT entries are not copied from gathering/mining");
        check(CapabilityId.mainForProfession("Lumberjack") == CapabilityId.WOODCUTTING, "lumberjack baseline binding");
        check(CapabilityId.mainForProfession("Miner") == CapabilityId.MINING, "miner baseline binding");
        Map<String, Integer> outputs = new HashMap<>(); outputs.put("minecraft:oak_log", 4);
        TaskContract logging = new TaskContract(Collections.singletonList(new TaskContract.ItemNeed("minecraft:wooden_axe", 1, false)),
                Collections.singletonMap(CapabilityId.WOODCUTTING, 1), outputs, Collections.singletonMap(CapabilityId.WOODCUTTING, 1));
        check(!logging.levelsMet(restored::levelOf), "wrong skill cannot satisfy logging gate");
        restored.setLevel(CapabilityId.WOODCUTTING, 1);
        check(logging.levelsMet(restored::levelOf), "logging level one opens logging only");
        check(logging.produces("minecraft:oak_log") && !logging.produces("minecraft:iron_ore"), "parameterized output matching");
        check(!logging.items.get(0).consumed, "tool is retained rather than charged per log");
        outputs.clear(); check(logging.produces("minecraft:oak_log"), "registered contract is immutable snapshot");
        check(logging.practice.containsKey(CapabilityId.WOODCUTTING) && !logging.practice.containsKey(CapabilityId.GATHERING),
                "practice trains the performed activity");
        DependencyPath<String> path = new DependencyPath<>(3, 6);
        check(path.enter("log") && path.enter("axe"), "nested requirements admitted");
        check(!path.enter("log") && path.contains("log"), "cycle rejected without replacing ancestor");
        check(path.enter("planks") && !path.enter("stick"), "depth bound enforced");
        path.leave("planks"); check(path.enter("stick"), "siblings are not mistaken for cycles");
        path.leave("stick"); check(!path.enter("stone"), "shared node budget bounds broad search");
        path.leave("axe"); path.leave("log"); check(path.enter("log"), "next root receives a fresh budget");
        path.leave("log");
        try {
            check(path.enter("log"), "exception test enters");
            throw new IllegalStateException("simulated failed recipe");
        } catch (IllegalStateException expected) { }
        finally { path.leave("log"); }
        check(path.enter("log"), "finally releases failed branch");
        System.out.println("TaskContractChecks: " + checks + " assertions passed");
    }
}
