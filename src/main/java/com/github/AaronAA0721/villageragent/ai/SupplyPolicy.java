package com.github.AaronAA0721.villageragent.ai;

import net.minecraft.item.*;
import net.minecraft.potion.EffectInstance;
import net.minecraft.potion.EffectType;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.registries.ForgeRegistries;
import java.util.*;

/**
 * Central request policies for cooperative village supply: exact/category matching,
 * request units, generic food units, and tool ranking. Task execution never hard-codes these rules.
 */
public final class SupplyPolicy {
    public enum Unit { ITEMS, FOOD_POINTS }
    /** Category keys are written as {@code #food}, {@code #pickaxe}; exact keys are registry ids. */
    public static final String CATEGORY_PREFIX = "#";
    public static final String FOOD = "food";
    /** Material categories, matched by item tag: {@code #log} is any log or wood, {@code #planks} any planks. */
    public static final String LOG = "log", PLANKS = "planks";
    private static final List<String> TOOL_CATEGORIES =
            Arrays.asList("hoe", "pickaxe", "axe", "shovel", "sword", "fishing_rod", "shears", "bow");
    /** Concrete outputs a responder may produce for a category, in preference order. */
    private static final Map<String, List<String>> CATEGORY_OUTPUTS = new HashMap<>();
    static {
        CATEGORY_OUTPUTS.put(FOOD, Arrays.asList("minecraft:bread", "minecraft:baked_potato", "minecraft:carrot",
                "minecraft:potato", "minecraft:beetroot", "minecraft:apple", "minecraft:cooked_beef",
                "minecraft:cooked_porkchop", "minecraft:cooked_chicken", "minecraft:cooked_mutton",
                "minecraft:cooked_cod", "minecraft:cooked_salmon"));
        for (String tool : new String[]{"hoe", "pickaxe", "axe", "shovel", "sword"})
            CATEGORY_OUTPUTS.put(tool, Arrays.asList("minecraft:iron_" + tool, "minecraft:stone_" + tool,
                    "minecraft:wooden_" + tool));
        CATEGORY_OUTPUTS.put("fishing_rod", Collections.singletonList("minecraft:fishing_rod"));
        CATEGORY_OUTPUTS.put("shears", Collections.singletonList("minecraft:shears"));
        CATEGORY_OUTPUTS.put("bow", Collections.singletonList("minecraft:bow"));
        // Material categories: any wood species serves the same recipes (planks, sticks, tables, tools).
        List<String> logs = new ArrayList<>(), planks = new ArrayList<>();
        for (String wood : new String[]{"oak", "spruce", "birch", "jungle", "acacia", "dark_oak"}) {
            logs.add("minecraft:" + wood + "_log"); planks.add("minecraft:" + wood + "_planks");
        }
        CATEGORY_OUTPUTS.put(LOG, logs);
        CATEGORY_OUTPUTS.put(PLANKS, planks);
    }
    private static final Map<String, net.minecraft.tags.ITag<Item>> MATERIAL_TAGS = new HashMap<>();
    static {
        MATERIAL_TAGS.put(LOG, net.minecraft.tags.ItemTags.LOGS);
        MATERIAL_TAGS.put(PLANKS, net.minecraft.tags.ItemTags.PLANKS);
    }
    /** The material category ({@code log}/{@code planks}) an item belongs to, or null. */
    public static String materialCategory(Item item) {
        if (item == null) return null;
        for (Map.Entry<String, net.minecraft.tags.ITag<Item>> e : MATERIAL_TAGS.entrySet())
            if (item.is(e.getValue())) return e.getKey();
        return null;
    }
    /** Whether {@code item} satisfies {@code category} (food, a tool kind, or a material tag). */
    public static boolean categoryMatches(String category, Item item) {
        if (item == null || category == null) return false;
        if (FOOD.equals(category)) return edible(item);
        net.minecraft.tags.ITag<Item> tag = MATERIAL_TAGS.get(category);
        if (tag != null) return item.is(tag);
        return category.equals(toolCategory(item));
    }
    /** An alternative names either an exact item or, prefixed with {@code #}, a category. */
    static boolean altMatches(Alt alt, Item item) {
        if (item == null || item.getRegistryName() == null) return false;
        return alt.item.startsWith(CATEGORY_PREFIX) ? categoryMatches(alt.item.substring(1), item)
                : alt.item.equals(item.getRegistryName().toString());
    }

    /** One acceptable item of an "or" request and the quantity of it that fully covers the request. */
    public static final class Alt {
        public final String item;
        public final int qty;
        public Alt(String item, int qty) { this.item = item; this.qty = Math.max(1, qty); }
    }

    /**
     * Immutable matching specification: one exact item, one configured category, or an "or" list of
     * alternatives ({@code 2 sticks | 2 planks | 1 log}). Any single alternative delivered in full covers the
     * request; alternatives are never mixed. Request units are units of the first (primary) alternative.
     */
    public static final class Spec {
        public final String item;
        public final String category;
        /** "Or" alternatives in preference order; empty for plain exact and category specs. */
        public final List<Alt> alternatives;
        private Spec(String item, String category, List<Alt> alternatives) {
            this.item = item; this.category = category; this.alternatives = alternatives;
        }
        public static Spec exact(String item) { return new Spec(item, null, Collections.emptyList()); }
        public static Spec category(String category) {
            if (!SupplyPolicy.isCategory(category)) throw new IllegalArgumentException("Unknown supply category " + category);
            return new Spec(null, category, Collections.emptyList());
        }
        /** "Or" request; a single alternative is just an exact request. */
        public static Spec anyOf(List<Alt> alternatives) {
            if (alternatives == null || alternatives.isEmpty()) throw new IllegalArgumentException("No alternatives");
            if (alternatives.size() == 1) {
                String only = alternatives.get(0).item;
                return only.startsWith(CATEGORY_PREFIX) ? category(only.substring(1)) : exact(only);
            }
            return new Spec(alternatives.get(0).item, null, Collections.unmodifiableList(new ArrayList<>(alternatives)));
        }
        /** Parses {@code #category}, {@code a*2|b*2|c*1} or an exact registry id; null when invalid. */
        public static Spec parse(String key) {
            if (key == null || key.isEmpty()) return null;
            if (key.startsWith(CATEGORY_PREFIX)) {
                String c = key.substring(CATEGORY_PREFIX.length());
                return SupplyPolicy.isCategory(c) ? category(c) : null;
            }
            if (key.indexOf(ALT_QTY) >= 0) {
                List<Alt> alts = new ArrayList<>();
                for (String part : key.split("\\" + ALT_SEPARATOR)) {
                    int star = part.lastIndexOf(ALT_QTY);
                    if (star <= 0) return null;
                    String id = part.substring(0, star);
                    int qty;
                    try { qty = Integer.parseInt(part.substring(star + 1)); } catch (NumberFormatException e) { return null; }
                    boolean category = id.startsWith(CATEGORY_PREFIX) && SupplyPolicy.isCategory(id.substring(1));
                    if ((!category && ResourceLocation.tryParse(id) == null) || qty <= 0) return null;
                    alts.add(new Alt(id, qty));
                }
                return alts.isEmpty() ? null : anyOf(alts);
            }
            return ResourceLocation.tryParse(key) == null ? null : exact(key);
        }
        public boolean isCategory() { return category != null; }
        public boolean hasAlternatives() { return !alternatives.isEmpty(); }
        public Unit unit() { return FOOD.equals(category) ? Unit.FOOD_POINTS : Unit.ITEMS; }
        public String key() {
            if (isCategory()) return CATEGORY_PREFIX + category;
            if (!hasAlternatives()) return item;
            StringBuilder sb = new StringBuilder();
            for (Alt a : alternatives) {
                if (sb.length() > 0) sb.append(ALT_SEPARATOR);
                sb.append(a.item).append(ALT_QTY).append(a.qty);
            }
            return sb.toString();
        }
        /** Every item id that can satisfy this spec (exact item, or each alternative); empty for categories. */
        public List<String> items() {
            if (isCategory()) return Collections.emptyList();
            if (!hasAlternatives()) return Collections.singletonList(item);
            List<String> ids = new ArrayList<>();
            for (Alt a : alternatives) {
                if (a.item.startsWith(CATEGORY_PREFIX))
                    ids.addAll(CATEGORY_OUTPUTS.getOrDefault(a.item.substring(1), Collections.emptyList()));
                else ids.add(a.item);
            }
            return ids;
        }
        Alt alternativeFor(Item item) {
            for (Alt a : alternatives) if (altMatches(a, item)) return a;
            return null;
        }
        /** An exact request never silently accepts a different item, including a lower-tier variant. */
        public boolean matches(ItemStack stack) {
            if (stack == null || stack.isEmpty()) return false;
            if (!isCategory()) {
                ResourceLocation id = stack.getItem().getRegistryName();
                if (id == null) return false;
                return hasAlternatives() ? alternativeFor(stack.getItem()) != null : id.toString().equals(item);
            }
            return categoryMatches(category, stack.getItem());
        }
        public int units(ItemStack stack, int count) {
            if (!matches(stack) || count <= 0) return 0;
            if (hasAlternatives()) {
                // Convert to primary units: the alternative's full quantity is worth the primary's full quantity.
                Alt alt = alternativeFor(stack.getItem());
                return (int) ((long) count * alternatives.get(0).qty / alt.qty);
            }
            return unit() == Unit.FOOD_POINTS ? nutrition(stack.getItem()) * count : count;
        }
        @Override public boolean equals(Object o) { return o instanceof Spec && key().equals(((Spec) o).key()); }
        @Override public int hashCode() { return key().hashCode(); }
        @Override public String toString() { return key(); }
    }

    public static final char ALT_SEPARATOR = '|';
    public static final char ALT_QTY = '*';

    /** Human text for a spec key: {@code stick (or 2 oak planks / 1 oak log)}; plain keys pass through. */
    public static String describe(String key, java.util.function.Function<String, String> name) {
        if (key == null || key.indexOf(ALT_QTY) < 0) return name.apply(key);
        String[] parts = key.split("\\" + ALT_SEPARATOR);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            int star = parts[i].lastIndexOf(ALT_QTY);
            String id = star > 0 ? parts[i].substring(0, star) : parts[i];
            String qty = star > 0 ? parts[i].substring(star + 1) : "";
            if (i == 0) { sb.append(name.apply(id)); if (parts.length > 1) sb.append(" (or "); continue; }
            if (i > 1) sb.append(" / ");
            sb.append(qty).append(' ').append(name.apply(id));
        }
        if (parts.length > 1) sb.append(')');
        return sb.toString();
    }

    public static boolean isCategory(String category) {
        return FOOD.equals(category) || TOOL_CATEGORIES.contains(category) || MATERIAL_TAGS.containsKey(category);
    }
    /** Work-tool categories only (not food or materials). */
    public static boolean isToolCategory(String category) {
        return TOOL_CATEGORIES.contains(category);
    }

    /** Edible-item policy: an item with food properties and no harmful effect (rotten flesh, spider eye...). */
    public static boolean edible(Item item) {
        if (item == null || !item.isEdible() || item.getFoodProperties() == null) return false;
        if (nutrition(item) <= 0) return false;
        for (com.mojang.datafixers.util.Pair<EffectInstance, Float> effect : item.getFoodProperties().getEffects())
            if (effect.getFirst() != null && effect.getFirst().getEffect().getCategory() == EffectType.HARMFUL) return false;
        return true;
    }

    /** Hunger-restoration points: vanilla nutrition of one item. */
    public static int nutrition(Item item) {
        Food food = item == null ? null : item.getFoodProperties();
        return food == null ? 0 : Math.max(0, food.getNutrition());
    }

    public static String toolCategory(Item item) {
        if (item instanceof HoeItem) return "hoe";
        if (item instanceof PickaxeItem) return "pickaxe";
        if (item instanceof AxeItem) return "axe";
        if (item instanceof ShovelItem) return "shovel";
        if (item instanceof SwordItem) return "sword";
        if (item instanceof FishingRodItem) return "fishing_rod";
        if (item instanceof ShearsItem) return "shears";
        if (item instanceof BowItem) return "bow";
        return null;
    }

    /** Explicit material rank; enchantment valuation is deliberately deferred. */
    public static int tierRank(Item item) {
        if (!(item instanceof TieredItem)) return 1;
        IItemTier tier = ((TieredItem) item).getTier();
        if (tier == ItemTier.WOOD || tier == ItemTier.GOLD) return 1;
        if (tier == ItemTier.STONE) return 2;
        if (tier == ItemTier.IRON) return 3;
        if (tier == ItemTier.DIAMOND) return 4;
        if (tier == ItemTier.NETHERITE) return 5;
        return 1 + Math.max(0, tier.getLevel());
    }

    /** Positive when {@code a} is the better tool: tier, then remaining durability, then lower slot. */
    public static int compareTools(ItemStack a, int slotA, ItemStack b, int slotB) {
        int byTier = Integer.compare(tierRank(a.getItem()), tierRank(b.getItem()));
        if (byTier != 0) return byTier;
        int byDurability = Integer.compare(a.getMaxDamage() - a.getDamageValue(), b.getMaxDamage() - b.getDamageValue());
        if (byDurability != 0) return byDurability;
        return Integer.compare(slotB, slotA);
    }

    public static int units(Spec spec, List<ItemStack> stacks) {
        if (spec.hasAlternatives()) {
            // Alternatives are never mixed: the best single alternative counts.
            int best = 0;
            for (Alt alt : spec.alternatives) {
                int count = 0;
                for (ItemStack stack : stacks)
                    if (!stack.isEmpty() && altMatches(alt, stack.getItem())) count += stack.getCount();
                best = Math.max(best, (int) ((long) count * spec.alternatives.get(0).qty / alt.qty));
            }
            return best;
        }
        int total = 0;
        for (ItemStack stack : stacks) total += spec.units(stack, stack.getCount());
        return total;
    }

    /** Whole items of {@code item} needed to cover {@code units} request units. */
    public static int itemsToCover(Spec spec, Item item, int units) {
        if (units <= 0) return 0;
        if (spec.hasAlternatives()) {
            Alt alt = item == null || item.getRegistryName() == null ? null : spec.alternativeFor(item);
            if (alt == null) return 0;
            int primary = spec.alternatives.get(0).qty;
            return (int) (((long) units * alt.qty + primary - 1) / primary);
        }
        if (spec.unit() != Unit.FOOD_POINTS) return units;
        int each = nutrition(item);
        return each <= 0 ? 0 : (units + each - 1) / each;
    }

    public static List<Item> candidateOutputs(Spec spec) {
        List<Item> result = new ArrayList<>();
        List<String> ids = spec.isCategory() ? CATEGORY_OUTPUTS.getOrDefault(spec.category, Collections.emptyList())
                : spec.items();
        for (String id : ids) {
            ResourceLocation rl = ResourceLocation.tryParse(id);
            Item item = rl == null ? null : ForgeRegistries.ITEMS.getValue(rl);
            if (item != null && item != Items.AIR) result.add(item);
        }
        return result;
    }

    /**
     * Deterministic delivery selection covering {@code units}. Item units are filled exactly; food may
     * exceed only on the final item, choosing the smallest item that finishes the remaining requirement.
     */
    public static List<ItemStack> select(Spec spec, List<ItemStack> available, int units, int maxStacks) {
        if (spec.hasAlternatives()) return selectAlternative(spec, available, units, maxStacks);
        List<ItemStack> pool = new ArrayList<>();
        for (ItemStack stack : available) if (spec.matches(stack)) pool.add(stack.copy());
        List<ItemStack> chosen = new ArrayList<>();
        int remaining = units;
        while (remaining > 0 && chosen.size() < maxStacks) {
            ItemStack pick = null;
            if (spec.unit() == Unit.FOOD_POINTS) {
                ItemStack finishing = null, largest = null;
                for (ItemStack s : pool) {
                    if (s.isEmpty()) continue;
                    int n = nutrition(s.getItem());
                    if (n >= remaining && (finishing == null || n < nutrition(finishing.getItem()))) finishing = s;
                    if (largest == null || n > nutrition(largest.getItem())) largest = s;
                }
                pick = finishing != null ? finishing : largest;
            } else {
                for (ItemStack s : pool) if (!s.isEmpty()) { pick = s; break; }
            }
            if (pick == null) break;
            int each = Math.max(1, spec.units(pick, 1));
            int count = Math.min(pick.getCount(), Math.max(1, spec.unit() == Unit.FOOD_POINTS
                    ? (remaining >= each ? remaining / each : 1) : remaining));
            ItemStack part = pick.copy(); part.setCount(count);
            merge(chosen, part);
            pick.shrink(count);
            remaining -= spec.units(part, count);
        }
        return chosen;
    }

    /** The first alternative, in request order, that the stock covers in full; empty when none does. */
    private static List<ItemStack> selectAlternative(Spec spec, List<ItemStack> available, int units, int maxStacks) {
        int primary = spec.alternatives.get(0).qty;
        for (Alt alt : spec.alternatives) {
            int needed = (int) (((long) units * alt.qty + primary - 1) / primary);
            List<ItemStack> chosen = new ArrayList<>();
            int left = needed;
            for (ItemStack stack : available) {
                if (left <= 0 || chosen.size() >= maxStacks) break;
                if (stack.isEmpty() || !altMatches(alt, stack.getItem())) continue;
                ItemStack part = stack.copy(); part.setCount(Math.min(left, stack.getCount()));
                merge(chosen, part);
                left -= part.getCount();
            }
            if (left <= 0) return chosen;
        }
        return new ArrayList<>();
    }

    private static void merge(List<ItemStack> chosen, ItemStack part) {
        for (ItemStack s : chosen)
            if (TradeInventory.matches(s, part) && s.getCount() + part.getCount() <= s.getMaxStackSize()) {
                s.grow(part.getCount()); return;
            }
        chosen.add(part);
    }
    private SupplyPolicy() {}
}
