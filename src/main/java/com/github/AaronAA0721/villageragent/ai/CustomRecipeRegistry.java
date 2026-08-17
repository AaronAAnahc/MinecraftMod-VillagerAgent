package com.github.AaronAA0721.villageragent.ai;

import com.github.AaronAA0721.villageragent.ai.config.VillagerAgentConfigDir;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.util.ResourceLocation;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads custom (non-vanilla) crafting recipes from {@code config/villageragent/custom_recipes.json}
 * (extracted to the game config folder on first run; editable without recompiling). These cover
 * items a villager may craft per {@link ProfessionCraftCatalog} but which have no vanilla
 * crafting/smelting recipe (e.g. chainmail_*, saddle, ender_pearl).
 *
 * <p>Edit the JSON to add or retune custom recipes; a missing or malformed file is logged and
 * simply yields no custom recipes (the mod still runs).
 */
public final class CustomRecipeRegistry {

    private static final Logger LOGGER = LogManager.getLogger(CustomRecipeRegistry.class);
    private static final String FILE = "custom_recipes.json";

    private static final Map<ResourceLocation, CustomRecipe> REGISTRY = new HashMap<>();
    private static boolean loaded = false;

    private CustomRecipeRegistry() {}

    private static void ensureLoaded() {
        if (loaded) return;
        loaded = true;
        load();
    }

    private static void load() {
        try (InputStream in = VillagerAgentConfigDir.open(FILE)) {
            JsonObject root = new Gson().fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), JsonObject.class);
            JsonArray recipes = root.getAsJsonArray("recipes");
            int ok = 0;
            for (JsonElement e : recipes) {
                CustomRecipe r = parse(e.getAsJsonObject());
                if (r != null) {
                    REGISTRY.put(r.output, r);
                    ok++;
                }
            }
            LOGGER.info("[VillagerAgent] Loaded {} custom crafting recipe(s) from config/villageragent/{}", ok, FILE);
        } catch (Exception ex) {
            LOGGER.error("[VillagerAgent] Failed to load config/villageragent/{}: {}", FILE, ex.getMessage());
        }
    }

    private static CustomRecipe parse(JsonObject o) {
        try {
            String outId = o.get("output").getAsString();
            int count = o.has("count") ? o.get("count").getAsInt() : 1;
            List<CustomRecipe.IngredientStack> inputs = new ArrayList<>();
            for (JsonElement ie : o.getAsJsonArray("inputs")) {
                JsonObject io = ie.getAsJsonObject();
                inputs.add(new CustomRecipe.IngredientStack(
                        new ResourceLocation(io.get("item").getAsString()),
                        io.get("count").getAsInt()));
            }
            return new CustomRecipe(new ResourceLocation(outId), count, inputs);
        } catch (Exception ex) {
            LOGGER.error("[VillagerAgent] Skipping malformed custom recipe entry: {}", ex.getMessage());
            return null;
        }
    }

    /** Look up the custom recipe for an item id, or null if none is registered. */
    public static CustomRecipe get(String itemId) {
        ensureLoaded();
        return REGISTRY.get(new ResourceLocation(itemId));
    }
}
