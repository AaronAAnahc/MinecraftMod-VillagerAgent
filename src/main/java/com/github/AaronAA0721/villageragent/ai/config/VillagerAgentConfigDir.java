package com.github.AaronAA0721.villageragent.ai.config;

import com.github.AaronAA0721.villageragent.Villageragent;
import net.minecraftforge.fml.loading.FMLPaths;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Central helper for all VillagerAgent data config files.
 *
 * <p>Every editable config (custom recipes, proficiency level table, per-level unlock
 * overrides) lives under a single dedicated subfolder of the game config directory:
 * <pre>  &lt;game&gt;/config/villageragent/  </pre>
 * so that other mods / modpacks have one predictable place to tweak this mod's behaviour.
 *
 * <p>Bundled defaults live in {@code src/main/resources/villageragent/*.json} (on the jar
 * classpath). On first run we copy each default into the subfolder <em>only if it does not
 * already exist</em> — this is what makes the files user-editable without a recompile. After
 * that, {@link #open} always prefers the on-disk copy, falling back to the classpath default
 * only if the extracted file is somehow missing.
 */
public final class VillagerAgentConfigDir {

    private static final Logger LOGGER = LogManager.getLogger(VillagerAgentConfigDir.class);
    private static final String RESOURCE_ROOT = "/villageragent/";

    private VillagerAgentConfigDir() {}

    /** The dedicated subfolder: {@code <config>/villageragent/}. */
    public static Path dir() {
        return FMLPaths.CONFIGDIR.get().resolve(Villageragent.MOD_ID);
    }

    /** Create the subfolder if missing. Safe to call repeatedly. */
    public static void ensureDir() {
        try {
            Files.createDirectories(dir());
        } catch (IOException e) {
            LOGGER.error("[VillagerAgent] Failed to create config dir {}: {}", dir(), e.getMessage());
        }
    }

    /** Absolute path to an editable config file (the file may not exist yet). */
    public static Path path(String fileName) {
        return dir().resolve(fileName);
    }

    /**
     * Copy a bundled default from {@code /villageragent/<fileName>} (classpath) into the config
     * subfolder if it is not already present. Called once during mod setup for every shipped JSON.
     */
    public static void extractDefault(String fileName) {
        Path target = path(fileName);
        if (Files.exists(target)) return;
        try (InputStream in = Villageragent.class.getResourceAsStream(RESOURCE_ROOT + fileName)) {
            if (in == null) {
                LOGGER.warn("[VillagerAgent] Bundled default {} not found on classpath — skipping extraction",
                        RESOURCE_ROOT + fileName);
                return;
            }
            Files.createDirectories(dir());
            Files.copy(in, target);
            LOGGER.info("[VillagerAgent] Extracted default config {} -> {}", RESOURCE_ROOT + fileName, target);
        } catch (IOException e) {
            LOGGER.error("[VillagerAgent] Failed to extract default {}: {}", fileName, e.getMessage());
        }
    }

    /**
     * Open an editable config file for reading, preferring the on-disk copy in the config
     * subfolder and falling back to the classpath default. Throws if neither exists.
     */
    public static InputStream open(String fileName) throws IOException {
        Path target = path(fileName);
        if (Files.exists(target)) return Files.newInputStream(target);
        InputStream in = Villageragent.class.getResourceAsStream(RESOURCE_ROOT + fileName);
        if (in == null) throw new IOException("No config file and no classpath default for " + fileName);
        return in;
    }
}
