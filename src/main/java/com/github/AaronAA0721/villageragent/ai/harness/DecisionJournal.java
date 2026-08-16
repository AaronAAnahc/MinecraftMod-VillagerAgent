package com.github.AaronAA0721.villageragent.ai.harness;

import com.github.AaronAA0721.villageragent.ai.LLMService;
import com.github.AaronAA0721.villageragent.config.ModConfig;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedWriter;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Append-only decision journal (plan: 技术力提升计划书 §1.4 — {@code DecisionJournal}).
 *
 * <p>Every harness decision appends one JSONL line:
 * <pre>{@code {"t":<tick>,"villager":<uuid>,"kind":<SCHEDULE|...>,"promptHash":<int>,
 *         "rawLLM":<text>,"parsed":<text>,"validation":<text>,"outcome":<text>}}</pre>
 *
 * <p>Uses: live debugging, trajectory replay, and — later — an eval harness. The file is written
 * to {@code villageragent_decision_journal.jsonl} in the process working directory (the server
 * run dir). Journaling is best-effort and fully guarded: any I/O problem is swallowed so it can
 * never affect gameplay. Disabled via {@code harness_journal_enabled}.
 */
public final class DecisionJournal {

    private static final Logger LOGGER = LogManager.getLogger();
    private static final Gson GSON = new Gson();
    private static final Object LOCK = new Object();
    private static final String FILE_NAME = "villageragent_decision_journal.jsonl";
    private static PrintWriter writer; // lazily opened on first write

    /** What kind of decision produced this journal entry. */
    public enum Kind { SCHEDULE, REFLECTION, CHAT, SOCIAL, GREETING, DAILY_GOAL }

    private DecisionJournal() {}

    /** Convenience outcome label from a raw LLM string. */
    public static String outcome(String rawLLM) {
        return LLMService.isFailure(rawLLM) ? "failure" : "ok";
    }

    /** Record a decision with only the raw LLM text and an outcome. */
    public static void record(UUID villagerId, Kind kind, long tick, String prompt,
                              String rawLLM, String outcome) {
        record(villagerId, kind, tick, prompt, rawLLM, null, null, outcome);
    }

    /**
     * Record a full decision entry.
     *
     * @param parsed      human-readable parsed decision (e.g. the DecisionSchema toString)
     * @param validation  validation/grounding result (e.g. "ok" or "rejected: ...")
     * @param outcome     short label: "ok" / "failure" / "accepted" / "rejected:..." / "parse-failed"
     */
    public static void record(UUID villagerId, Kind kind, long tick, String prompt,
                              String rawLLM, String parsed, String validation, String outcome) {
        if (!ModConfig.HARNESS_JOURNAL_ENABLED.get()) return;
        try {
            JsonObject o = new JsonObject();
            o.addProperty("t", tick);
            o.addProperty("villager", villagerId == null ? null : villagerId.toString());
            o.addProperty("kind", kind == null ? "unknown" : kind.name());
            o.addProperty("promptHash", prompt == null ? 0 : (long) prompt.hashCode());
            o.addProperty("rawLLM", rawLLM == null ? "" : rawLLM);
            o.addProperty("parsed", parsed == null ? "" : parsed);
            o.addProperty("validation", validation == null ? "" : validation);
            o.addProperty("outcome", outcome == null ? "" : outcome);

            String line = GSON.toJson(o);
            synchronized (LOCK) {
                getWriter().println(line);
                getWriter().flush();
            }
        } catch (Throwable t) {
            // Best-effort only — never let journaling affect the game.
            LOGGER.debug("DecisionJournal write skipped: {}", t.getMessage());
        }
    }

    private static PrintWriter getWriter() throws Exception {
        if (writer == null) {
            writer = new PrintWriter(new BufferedWriter(new OutputStreamWriter(
                    new FileOutputStream(FILE_NAME, true), StandardCharsets.UTF_8)));
        }
        return writer;
    }
}
