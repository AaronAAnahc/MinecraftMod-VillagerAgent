package com.github.AaronAA0721.villageragent.ai.harness;

import com.github.AaronAA0721.villageragent.ai.NativeRecipeResolver;
import com.github.AaronAA0721.villageragent.ai.ProfessionCraftCatalog;
import com.github.AaronAA0721.villageragent.ai.VillagerAgentData;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.entity.merchant.villager.VillagerEntity;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.server.ServerWorld;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * Validates and grounds a {@link DecisionSchema} before it is allowed to drive the villager
 * (plan: 技术力提升计划书 §1.2 — {@code ActionValidator}).
 *
 * <p>Three gates, in order:
 * <ol>
 *   <li><b>Schema</b> — is the action a known decision? are required fields present?</li>
 *   <li><b>World grounding</b> — does the target actually exist / make sense?
 *       <ul>
 *         <li>{@code MOVE} coords must be loaded, in-world, not lava, not absurdly far;</li>
 *         <li>{@code CRAFT} item must be in the profession catalog AND have a real recipe;</li>
 *         <li>{@code GATHER} item must be a real registered item.</li>
 *       </ul></li>
 *   <li><b>Safety</b> — the harness never emits {@code ATTACK} (the combat system owns that);
 *       a villager cannot be talked into hurting innocents.</li>
 * </ol>
 *
 * <p>Any rejection returns a {@link ValidationResult#reason} that is also logged to the
 * {@link DecisionJournal}, so a "crazy" LLM decision is recorded but never executed — this is
 * exactly the "LLM 抽风也不会让村民发疯" guarantee.
 */
public final class ActionValidator {

    private ActionValidator() {}

    public static class ValidationResult {
        public final boolean ok;
        public final String reason;
        public final DecisionSchema schema;

        private ValidationResult(boolean ok, String reason, DecisionSchema schema) {
            this.ok = ok;
            this.reason = reason;
            this.schema = schema;
        }

        public static ValidationResult accept(DecisionSchema s) {
            return new ValidationResult(true, "ok", s);
        }

        public static ValidationResult reject(String why, DecisionSchema s) {
            return new ValidationResult(false, why, s);
        }
    }

    public static ValidationResult validate(DecisionSchema schema, VillagerAgentData agent,
                                            VillagerEntity villager, ServerWorld world) {
        if (schema == null) return ValidationResult.reject("null-schema", null);
        if (schema.action == null || schema.action.trim().isEmpty()) {
            return ValidationResult.reject("missing-action", schema);
        }
        if (!schema.isKnown()) {
            return ValidationResult.reject("unknown-action:" + schema.normalizedAction(), schema);
        }

        switch (schema.decisionAction()) {
            case MOVE:        return validateMove(schema, villager, world);
            case CRAFT:       return validateCraft(schema, agent, villager, world);
            case GATHER:      return validateGather(schema);
            case REST:
            case IDLE:
            case SOCIALIZE:
            case TRADE:
            case FLEE:
            case DEFEND:
            case HARVEST:
            case GROW:
            case BUILD:
                // Non-grounded high-level decisions: accepted; the state machine executes them.
                return ValidationResult.accept(schema);
            case UNKNOWN:
            default:
                return ValidationResult.reject("unknown-action:" + schema.normalizedAction(), schema);
        }
    }

    // ── Per-action grounding ─────────────────────────────────────────────────

    private static ValidationResult validateMove(DecisionSchema schema, VillagerEntity villager, ServerWorld world) {
        BlockPos pos = parseCoords(schema.target);
        if (pos == null) return ValidationResult.reject("move:bad-coords:" + schema.target, schema);
        if (!world.isAreaLoaded(pos, 1)) return ValidationResult.reject("move:chunk-not-loaded", schema);
        Block b = world.getBlockState(pos).getBlock();
        if (b == Blocks.LAVA) {
            return ValidationResult.reject("move:target-is-lava", schema);
        }
        if (pos.getY() < 0 || pos.getY() > 255) {
            return ValidationResult.reject("move:out-of-world", schema);
        }
        if (villager != null && villager.blockPosition().distSqr(pos) > 256L * 256L) {
            return ValidationResult.reject("move:too-far", schema);
        }
        return ValidationResult.accept(schema);
    }

    private static ValidationResult validateCraft(DecisionSchema schema, VillagerAgentData agent,
                                                  VillagerEntity villager, ServerWorld world) {
        String item = normalizeItemId(schema.target);
        if (item == null) return ValidationResult.reject("craft:missing-item", schema);
        int level = (villager != null) ? villager.getVillagerData().getLevel() : 1;
        if (!ProfessionCraftCatalog.isCraftable(agent.getProfession(), level, item)) {
            return ValidationResult.reject("craft:not-in-catalog:" + item, schema);
        }
        if (world != null && NativeRecipeResolver.findRecipe(world, item) == null) {
            return ValidationResult.reject("craft:no-recipe:" + item, schema);
        }
        return ValidationResult.accept(schema);
    }

    private static ValidationResult validateGather(DecisionSchema schema) {
        String item = normalizeItemId(schema.target);
        if (item == null) return ValidationResult.reject("gather:missing-item", schema);
        if (ForgeRegistries.ITEMS.getValue(new ResourceLocation(item)) == null) {
            return ValidationResult.reject("gather:unknown-item:" + item, schema);
        }
        return ValidationResult.accept(schema);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static BlockPos parseCoords(String s) {
        if (s == null) return null;
        String[] parts = s.trim().split("\\s+");
        if (parts.length < 3) return null;
        try {
            int x = Integer.parseInt(parts[0]);
            int y = Integer.parseInt(parts[1]);
            int z = Integer.parseInt(parts[2]);
            return new BlockPos(x, y, z);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String normalizeItemId(String s) {
        if (s == null) return null;
        String t = s.trim();
        if (t.isEmpty()) return null;
        if (!t.contains(":")) t = "minecraft:" + t;
        return t;
    }
}
