package com.github.AaronAA0721.villageragent.ai;

import com.github.AaronAA0721.villageragent.ai.config.VillagerAgentConfigDir;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Loads the no-LLM default chat lines from {@code config/villageragent/default_chat_lines.json}
 * (extracted on first run; editable without recompiling). Used by {@code SocializeSkill} when the
 * LLM is off so villager-to-villager socialising still produces a bit of flavour text instead of
 * dead air.
 */
public final class DefaultChatLines {

    private static final Logger LOGGER = LogManager.getLogger(DefaultChatLines.class);
    private static final Random RANDOM = new Random();

    private static List<String> LINES;

    private DefaultChatLines() {}

    private static List<String> load() {
        try (InputStream in = VillagerAgentConfigDir.open("default_chat_lines.json")) {
            JsonObject root = new Gson().fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), JsonObject.class);
            JsonArray arr = root.getAsJsonArray("lines");
            List<String> out = new ArrayList<>();
            if (arr != null) {
                for (int i = 0; i < arr.size(); i++) out.add(arr.get(i).getAsString());
            }
            return out;
        } catch (Exception ex) {
            LOGGER.warn("[VillagerAgent] default_chat_lines.json load failed: {}", ex.getMessage());
            return new ArrayList<>();
        }
    }

    private static List<String> lines() {
        if (LINES == null) LINES = load();
        return LINES;
    }

    /** A random default line, or null if the file is empty/missing. */
    public static String random() {
        List<String> l = lines();
        if (l.isEmpty()) return null;
        return l.get(RANDOM.nextInt(l.size()));
    }
}
