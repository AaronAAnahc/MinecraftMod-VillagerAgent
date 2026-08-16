package com.github.AaronAA0721.villageragent.ai.harness;

import com.github.AaronAA0721.villageragent.ai.LLMService;
import com.github.AaronAA0721.villageragent.config.ModConfig;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Resilience layer for every LLM call (plan: 技术力提升计划书 §1.3 — {@code LLMGuard}).
 *
 * <p>Wraps {@link LLMService#queryLLM(String, String)} so that a flaky / slow / down API
 * can never stall or crash the game thread, and an API outage cannot cascade into a
 * village-wide freeze:
 * <ul>
 *   <li><b>Timeout</b> — a manual watchdog (Java 8 has no {@code CompletableFuture.orTimeout})
 *       fails the call after {@code harness_timeout_ms}, so the villager falls back to
 *       rule-based behaviour instead of waiting on a hung HTTP connection.</li>
 *   <li><b>Circuit breaker</b> — after {@code harness_circuit_failures} consecutive failures a
 *       villager is switched to rule-only mode for {@code harness_circuit_cooldown_ticks}, so a
 *       single broken endpoint does not spam the network or block decisions.</li>
 *   <li><b>Semantic failure</b> — failures are returned as {@link LLMService#fail(String)}
 *       markers (never as villager speech), so downstream can fall back cleanly.</li>
 * </ul>
 *
 * <p>All state is per-villager and thread-safe (concurrent hash maps + atomics). The guard
 * never throws into the calling code — it only ever resolves the future with a real response
 * or a failure marker.
 */
public final class LLMGuard {

    private static final Logger LOGGER = LogManager.getLogger();

    /** Daemon thread that runs the timeout watchdogs. */
    private static final ScheduledExecutorService WATCHDOG = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "villager-harness-guard");
        t.setDaemon(true);
        return t;
    });

    /** Per-villager consecutive failure counter. */
    private static final Map<UUID, AtomicInteger> FAILURES = new ConcurrentHashMap<>();
    /** Per-villager game tick until which the circuit is open (rule-only mode). */
    private static final Map<UUID, AtomicLong> BLOCKED_UNTIL = new ConcurrentHashMap<>();

    private LLMGuard() {}

    // ── Public API ───────────────────────────────────────────────────────────

    /** True while the villager's circuit breaker is open (decisions must use rule-only mode). */
    public static boolean isCircuitOpen(UUID villagerId, long gameTick) {
        AtomicLong until = BLOCKED_UNTIL.get(villagerId);
        return until != null && until.get() > gameTick;
    }

    /**
     * Guarded LLM call.
     *
     * @param villagerId identity of the calling villager (for per-villager circuit state)
     * @param gameTick   current world game tick (for cooldown bookkeeping)
     * @param system     system prompt
     * @param user       user prompt
     * @return future resolving to real content, or an {@link LLMService#fail(String)} marker on
     *         timeout / error / circuit-open.
     */
    public static CompletableFuture<String> query(UUID villagerId, long gameTick, String system, String user) {
        if (!ModConfig.HARNESS_ENABLED.get()) {
            // Harness disabled — behave exactly as before, but still log the call.
            LOGGER.debug("[LLMGuard] harness disabled; calling LLM directly for {}", villagerId);
            return LLMService.queryLLM(system, user);
        }

        if (isCircuitOpen(villagerId, gameTick)) {
            LOGGER.info("[LLMGuard] circuit OPEN for {} — skipping LLM call (rule-only mode)", villagerId);
            return CompletableFuture.completedFuture(LLMService.fail("circuit-open"));
        }

        CompletableFuture<String> inner = LLMService.queryLLM(system, user);

        // Manual timeout watchdog (Java 8 compatible).
        long timeoutMs = ModConfig.HARNESS_TIMEOUT_MS.get();
        LOGGER.debug("[LLMGuard] calling LLM for {} (timeout={}ms, tick={})", villagerId, timeoutMs, gameTick);
        ScheduledFuture<?> watchdog = WATCHDOG.schedule(() -> {
            if (!inner.isDone()) {
                inner.completeExceptionally(new TimeoutException("llm-timeout:" + timeoutMs + "ms"));
            }
        }, timeoutMs, TimeUnit.MILLISECONDS);
        inner.whenComplete((r, e) -> watchdog.cancel(false));

        // Translate exceptions / semantic failures into a failure marker and
        // maintain the circuit-breaker counters.
        return inner.handle((result, error) -> {
            if (error != null) {
                recordFailure(villagerId, gameTick);
                String reason = error.getClass().getSimpleName()
                        + (error.getMessage() != null ? (": " + error.getMessage()) : "");
                LOGGER.warn("[LLMGuard] LLM call FAILED for {} — {} (tick {})", villagerId, reason, gameTick);
                return LLMService.fail("guard:" + reason);
            }
            if (LLMService.isFailure(result)) {
                // Preserve the inner semantic failure reason (http-401, no-api-key, etc.)
                // instead of collapsing it into an opaque guard:llm-error.
                recordFailure(villagerId, gameTick);
                String innerReason = LLMService.failureReason(result);
                LOGGER.warn("[LLMGuard] LLM returned failure marker for {} — {} (tick {})",
                        villagerId, innerReason, gameTick);
                return result; // keep the original reason intact
            }
            resetFailures(villagerId);
            LOGGER.debug("[LLMGuard] LLM OK for {} — {} chars", villagerId, result == null ? 0 : result.length());
            return result;
        });
    }

    // ── Circuit bookkeeping ──────────────────────────────────────────────────

    private static void recordFailure(UUID villagerId, long gameTick) {
        AtomicInteger counter = FAILURES.computeIfAbsent(villagerId, k -> new AtomicInteger(0));
        int n = counter.incrementAndGet();
        if (n >= ModConfig.HARNESS_CIRCUIT_FAILURES.get()) {
            long cooldownTicks = ModConfig.HARNESS_CIRCUIT_COOLDOWN_TICKS.get();
            BLOCKED_UNTIL.put(villagerId, new AtomicLong(gameTick + cooldownTicks));
            counter.set(0);
            LOGGER.warn("Circuit breaker tripped for villager {} (rule-only for {} ticks)",
                    villagerId, cooldownTicks);
        }
    }

    private static void resetFailures(UUID villagerId) {
        AtomicInteger counter = FAILURES.get(villagerId);
        if (counter != null) counter.set(0);
    }
}
