package com.github.AaronAA0721.villageragent.ai;

import com.github.AaronAA0721.villageragent.config.ModConfig;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Service for communicating with LLM APIs
 */
public class LLMService {
    private static final Logger LOGGER = LogManager.getLogger();
    private static final ExecutorService executor = Executors.newFixedThreadPool(4);

    /**
     * Sentinel prefix marking a SEMANTIC failure (API error / timeout / circuit open / no key).
     * Downstream code MUST treat any string starting with this prefix as a failure and never
     * broadcast it as villager speech — this is the P0 fix for "API down -> villager reads the
     * error text to the player".
     */
    public static final String FAILURE_PREFIX = "\u0000LLM_FAIL\u0000";

    /** Wrap a failure reason in the sentinel marker. */
    public static String fail(String reason) {
        return FAILURE_PREFIX + (reason == null ? "unknown" : reason);
    }

    /** True if the LLM response is a semantic failure marker rather than real content. */
    public static boolean isFailure(String response) {
        return response != null && response.startsWith(FAILURE_PREFIX);
    }

    /** Extract the failure reason (without the prefix), or null if not a failure. */
    public static String failureReason(String response) {
        if (!isFailure(response)) return null;
        return response.substring(FAILURE_PREFIX.length());
    }

    /**
     * Diagnostic log. Prints at INFO when {@code llm_debug} is enabled, otherwise at DEBUG
     * (suppressed by Forge's default INFO log level). Use for verbose request/response dumps.
     */
    private static void debug(String msg) {
        if (ModConfig.LLM_DEBUG.get()) LOGGER.info("[LLM-DEBUG] " + msg);
        else LOGGER.debug(msg);
    }

    /** Wrap a failure with an always-visible INFO line so the reason never gets swallowed. */
    private static String failWithLog(String reason) {
        LOGGER.info("[LLM] returning failure marker: " + reason);
        return fail(reason);
    }

    // ── JSON output capability ──────────────────────────────────────────────
    //
    // Structured calls (chat, thoughts, harness decisions, name generation) ask the provider for
    // native JSON output. If the provider/model can't do it — the API rejects the JSON-mode
    // parameter, or the model keeps answering with non-JSON text — we switch to the plain-text
    // TODO:/REMEMBER: line protocol (see PromptTemplates#responseFormatInstruction). The verdict
    // is per provider+URL+model and resets when the config changes.

    /** JSON replies (reply + thought + goals + memories) get cut off by a tiny token budget. */
    private static final int JSON_MIN_TOKENS = 400;
    /** Consecutive unparseable "JSON" replies before auto mode gives up on JSON for this model. */
    private static final int JSON_PARSE_FAILURE_LIMIT = 3;
    private static volatile String jsonProviderKey = "";
    private static volatile boolean jsonUnsupported = false;
    private static final java.util.concurrent.atomic.AtomicInteger jsonParseFailures =
            new java.util.concurrent.atomic.AtomicInteger();

    private static String providerKey() {
        return ModConfig.LLM_API_TYPE.get() + "|" + ModConfig.LLM_API_URL.get() + "|" + ModConfig.LLM_MODEL.get();
    }

    private static void syncProviderKey() {
        String key = providerKey();
        if (!key.equals(jsonProviderKey)) {
            jsonProviderKey = key;
            jsonUnsupported = false;
            jsonParseFailures.set(0);
        }
    }

    private static String jsonMode() {
        String m = ModConfig.LLM_JSON_MODE.get();
        return m == null ? "auto" : m.trim().toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * Whether prompts should ask for the JSON response format. {@code llm_json_mode}: "on" always,
     * "off" never (plain-text TODO: protocol), "auto" (default) until this provider/model is found
     * not to support JSON output.
     */
    public static boolean jsonOutputSupported() {
        String mode = jsonMode();
        if ("off".equals(mode)) return false;
        if ("on".equals(mode)) return true;
        syncProviderKey();
        return !jsonUnsupported;
    }

    /** Parser feedback: did a reply that was requested as JSON actually parse as JSON? */
    public static void reportJsonParse(boolean ok) {
        if (!"auto".equals(jsonMode())) return;
        syncProviderKey();
        if (ok) { jsonParseFailures.set(0); return; }
        int n = jsonParseFailures.incrementAndGet();
        if (n >= JSON_PARSE_FAILURE_LIMIT) markJsonUnsupported(n + " consecutive non-JSON replies");
    }

    private static void markJsonUnsupported(String why) {
        if (!"auto".equals(jsonMode()) || jsonUnsupported) return;
        jsonUnsupported = true;
        LOGGER.warn("[LLM] {} does not seem to support JSON output ({}). Switching to the plain-text "
                + "TODO:/REMEMBER: protocol. Set llm_json_mode=on to force JSON.", providerKey(), why);
    }

    /** A 4xx whose error body points at the JSON-mode parameter we sent. */
    private static boolean rejectsJsonMode(int code, String errorBody) {
        if (code != 400 && code != 422 && code != 501) return false;
        String b = errorBody == null ? "" : errorBody.toLowerCase(java.util.Locale.ROOT);
        return b.contains("response_format") || b.contains("json_object") || b.contains("responsemimetype")
                || b.contains("response_mime_type") || b.contains("json mode") || b.contains("format: json");
    }

    private static int maxTokens(boolean json) {
        int configured = ModConfig.LLM_MAX_TOKENS.get();
        return json ? Math.max(configured, JSON_MIN_TOKENS) : configured;
    }

    /** Plain-text completion (no JSON mode), e.g. memory summaries. */
    public static CompletableFuture<String> queryLLM(String systemPrompt, String userPrompt) {
        return query(systemPrompt, userPrompt, false);
    }

    /**
     * Structured completion: requests native JSON output when the provider supports it (see
     * {@link #jsonOutputSupported()}); otherwise a plain request whose prompt uses the text protocol.
     */
    public static CompletableFuture<String> queryLLMJson(String systemPrompt, String userPrompt) {
        return query(systemPrompt, userPrompt, jsonOutputSupported());
    }

    private static CompletableFuture<String> query(String systemPrompt, String userPrompt, boolean json) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String apiType = ModConfig.LLM_API_TYPE.get();

                if ("openai".equalsIgnoreCase(apiType)) {
                    return queryOpenAI(systemPrompt, userPrompt, json);
                } else if ("anthropic".equalsIgnoreCase(apiType)) {
                    return queryAnthropic(systemPrompt, userPrompt, json);
                } else if ("ollama".equalsIgnoreCase(apiType)) {
                    return queryOllama(systemPrompt, userPrompt, json);
                } else if ("gemini".equalsIgnoreCase(apiType)) {
                    return queryGemini(systemPrompt, userPrompt, json);
                } else {
                    LOGGER.warn("Unknown LLM API type: " + apiType);
                    return failWithLog("unknown-api:" + apiType);
                }
            } catch (Exception e) {
                LOGGER.error("Error querying LLM: ", e);
                return failWithLog("exception:" + e.getClass().getSimpleName());
            }
        }, executor).thenApply(result -> {
            // Auto-detect models that ignore the JSON instruction: judge real model output only
            // (API failures / timeouts say nothing about JSON support).
            if (json && result != null && !isFailure(result)) {
                reportJsonParse(LLMResponseParser.extractJsonObject(result) != null);
            }
            return result;
        });
    }
    
    private static String queryOpenAI(String systemPrompt, String userPrompt, boolean json) throws Exception {
        String apiKey = ModConfig.LLM_API_KEY.get();
        String model = ModConfig.LLM_MODEL.get();
        String apiUrl = ModConfig.LLM_API_URL.get();

        debug("=== OpenAI API Request ===");
        debug("URL: " + apiUrl);
        debug("Model: " + model);
        debug("API Key: " + (apiKey.isEmpty() ? "NOT SET" : apiKey.substring(0, Math.min(8, apiKey.length())) + "..."));
        debug("System Prompt: " + systemPrompt);
        debug("User Prompt: " + userPrompt);

        if (apiKey.isEmpty()) {
            LOGGER.warn("OpenAI API key is empty!");
            return failWithLog("no-api-key");
        }

        URL url = new URL(apiUrl);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Authorization", "Bearer " + apiKey);
        conn.setDoOutput(true);
        conn.setConnectTimeout(30000); // 30 second timeout
        conn.setReadTimeout(60000); // 60 second read timeout

        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("model", model);
        requestBody.addProperty("max_tokens", maxTokens(json));

        JsonArray messages = new JsonArray();
        JsonObject systemMessage = new JsonObject();
        systemMessage.addProperty("role", "system");
        systemMessage.addProperty("content", systemPrompt);
        messages.add(systemMessage);

        JsonObject userMessage = new JsonObject();
        userMessage.addProperty("role", "user");
        userMessage.addProperty("content", userPrompt);
        messages.add(userMessage);

        requestBody.add("messages", messages);

        // Request structured JSON output — supported by OpenAI, DeepSeek, and most
        // OpenAI-compatible providers. Forces the model to emit valid JSON, preventing
        // markdown fences or stray text that would break LLMResponseParser.
        // Only for structured calls: plain-text calls (memory summaries) must not be forced into JSON.
        if (json) {
            JsonObject responseFormat = new JsonObject();
            responseFormat.addProperty("type", "json_object");
            requestBody.add("response_format", responseFormat);
        }
        // Hybrid reasoning models (SiliconFlow default: thinking ON) spend thousands of tokens and 15-60 s
        // "thinking" before a one-line villager reply; ask for a direct answer instead.
        boolean noThinking = sendNoThinking(apiUrl);
        if (noThinking) requestBody.addProperty("enable_thinking", false);

        debug("Request Body: " + requestBody.toString());
        long started = System.currentTimeMillis();

        try (OutputStream os = conn.getOutputStream()) {
            byte[] input = requestBody.toString().getBytes(StandardCharsets.UTF_8);
            os.write(input, 0, input.length);
        }

        int responseCode = conn.getResponseCode();
        String responseMessage = conn.getResponseMessage();
        debug("=== OpenAI API Response ===");
        debug("Response Code: " + responseCode + " " + responseMessage);
        debug("Response Headers: " + conn.getHeaderFields());

        if (responseCode == 200) {
            StringBuilder response = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String responseLine;
                while ((responseLine = br.readLine()) != null) {
                    response.append(responseLine.trim());
                }
            }

            debug("Raw Response Body: " + response.toString());

            JsonParser parser = new JsonParser();
            JsonElement element = parser.parse(response.toString());
            JsonObject jsonResponse = element.getAsJsonObject();
            JsonObject choice = jsonResponse.getAsJsonArray("choices").get(0).getAsJsonObject();
            String content = choice.getAsJsonObject("message").get("content").getAsString();
            if (choice.has("finish_reason") && !choice.get("finish_reason").isJsonNull()
                    && "length".equals(choice.get("finish_reason").getAsString())) {
                LOGGER.warn("[LLM] reply was cut off at the token limit ({} tokens); raise llm_max_tokens", maxTokens(json));
            }
            debug("Parsed Content: " + content);
            LOGGER.info("OpenAI response received successfully in {} ms ({})",
                    System.currentTimeMillis() - started, usageSummary(jsonResponse));
            return content;
        } else {
            // Read error response
            StringBuilder errorResponse = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(
                    conn.getErrorStream() != null ? conn.getErrorStream() : conn.getInputStream(),
                    StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    errorResponse.append(line);
                }
            }
            debug("Error Response Body: " + errorResponse.toString());
            if (noThinking && responseCode == 400 && errorResponse.toString().contains("enable_thinking")) {
                thinkingParamRejected = true;
                LOGGER.warn("[LLM] provider rejected enable_thinking; retrying without it (not sent again this session)");
                return queryOpenAI(systemPrompt, userPrompt, json);
            }
            if (json && rejectsJsonMode(responseCode, errorResponse.toString())) {
                markJsonUnsupported("HTTP " + responseCode + " rejected the JSON-mode parameter");
                LOGGER.warn("[LLM] OpenAI rejected JSON mode; retrying as plain text");
                return queryOpenAI(systemPrompt, userPrompt, false);
            }
            LOGGER.error("OpenAI API error " + responseCode + ": " + errorResponse.toString());
            return failWithLog("http-" + responseCode);
        }
    }
    
    /** Set when the provider answered HTTP 400 naming enable_thinking; it is then never sent again this session. */
    private static volatile boolean thinkingParamRejected = false;

    private static boolean sendNoThinking(String apiUrl) {
        return ModConfig.LLM_DISABLE_THINKING.get() && !thinkingParamRejected
                && (apiUrl == null || !apiUrl.contains("api.openai.com"));
    }

    /** "prompt=2908 completion=73 reasoning=0" from an OpenAI-style usage block, for latency diagnosis. */
    private static String usageSummary(JsonObject response) {
        try {
            if (!response.has("usage") || !response.get("usage").isJsonObject()) return "no usage";
            JsonObject u = response.getAsJsonObject("usage");
            int reasoning = 0;
            if (u.has("completion_tokens_details") && u.get("completion_tokens_details").isJsonObject()) {
                JsonObject d = u.getAsJsonObject("completion_tokens_details");
                if (d.has("reasoning_tokens") && !d.get("reasoning_tokens").isJsonNull()) reasoning = d.get("reasoning_tokens").getAsInt();
            }
            return "prompt=" + (u.has("prompt_tokens") ? u.get("prompt_tokens").getAsInt() : -1)
                    + " completion=" + (u.has("completion_tokens") ? u.get("completion_tokens").getAsInt() : -1)
                    + " reasoning=" + reasoning;
        } catch (RuntimeException e) {
            return "usage unreadable";
        }
    }

    private static String queryAnthropic(String systemPrompt, String userPrompt, boolean json) throws Exception {
        String apiKey = ModConfig.LLM_API_KEY.get();
        String model = ModConfig.LLM_MODEL.get();
        String apiUrl = ModConfig.LLM_API_URL.get();

        debug("=== Anthropic API Request ===");
        debug("URL: " + apiUrl);
        debug("Model: " + model);
        debug("API Key: " + (apiKey.isEmpty() ? "NOT SET" : apiKey.substring(0, Math.min(8, apiKey.length())) + "..."));
        debug("System Prompt: " + systemPrompt);
        debug("User Prompt: " + userPrompt);

        if (apiKey.isEmpty()) {
            LOGGER.warn("Anthropic API key is empty!");
            return failWithLog("no-api-key");
        }

        URL url = new URL(apiUrl);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("x-api-key", apiKey);
        conn.setRequestProperty("anthropic-version", "2023-06-01");
        conn.setDoOutput(true);
        conn.setConnectTimeout(30000);
        conn.setReadTimeout(60000);

        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("model", model);
        requestBody.addProperty("max_tokens", maxTokens(json));
        requestBody.addProperty("system", systemPrompt);

        JsonArray messages = new JsonArray();
        JsonObject userMessage = new JsonObject();
        userMessage.addProperty("role", "user");
        userMessage.addProperty("content", userPrompt);
        messages.add(userMessage);

        requestBody.add("messages", messages);

        debug("Request Body: " + requestBody.toString());

        try (OutputStream os = conn.getOutputStream()) {
            byte[] input = requestBody.toString().getBytes(StandardCharsets.UTF_8);
            os.write(input, 0, input.length);
        }

        int responseCode = conn.getResponseCode();
        String responseMessage = conn.getResponseMessage();
        debug("=== Anthropic API Response ===");
        debug("Response Code: " + responseCode + " " + responseMessage);
        debug("Response Headers: " + conn.getHeaderFields());

        if (responseCode == 200) {
            StringBuilder response = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String responseLine;
                while ((responseLine = br.readLine()) != null) {
                    response.append(responseLine.trim());
                }
            }

            debug("Raw Response Body: " + response.toString());

            JsonParser parser = new JsonParser();
            JsonElement element = parser.parse(response.toString());
            JsonObject jsonResponse = element.getAsJsonObject();
            String content = jsonResponse.getAsJsonArray("content")
                    .get(0).getAsJsonObject()
                    .get("text").getAsString();
            debug("Parsed Content: " + content);
            LOGGER.info("Anthropic response received successfully");
            return content;
        } else {
            StringBuilder errorResponse = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(
                    conn.getErrorStream() != null ? conn.getErrorStream() : conn.getInputStream(),
                    StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    errorResponse.append(line);
                }
            }
            debug("Error Response Body: " + errorResponse.toString());
            if (json && rejectsJsonMode(responseCode, errorResponse.toString())) {
                markJsonUnsupported("HTTP " + responseCode + " rejected the JSON-mode parameter");
                LOGGER.warn("[LLM] Anthropic rejected JSON mode; retrying as plain text");
                return queryAnthropic(systemPrompt, userPrompt, false);
            }
            LOGGER.error("Anthropic API error " + responseCode + ": " + errorResponse.toString());
            return failWithLog("http-" + responseCode);
        }
    }

    private static String queryOllama(String systemPrompt, String userPrompt, boolean json) throws Exception {
        String model = ModConfig.LLM_MODEL.get();
        String apiUrl = ModConfig.LLM_API_URL.get();

        // Default Ollama URL if not set
        if (apiUrl.isEmpty() || apiUrl.contains("openai.com") || apiUrl.contains("anthropic.com")) {
            apiUrl = "http://localhost:11434/api/generate";
        }

        debug("=== Ollama API Request ===");
        debug("URL: " + apiUrl);
        debug("Model: " + model);
        debug("System Prompt: " + systemPrompt);
        debug("User Prompt: " + userPrompt);

        URL url = new URL(apiUrl);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);
        conn.setConnectTimeout(30000);
        conn.setReadTimeout(120000); // Ollama can be slow, 2 min timeout

        // Combine system and user prompts for Ollama
        String combinedPrompt = systemPrompt + "\n\nUser: " + userPrompt + "\nAssistant:";

        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("model", model);
        requestBody.addProperty("prompt", combinedPrompt);
        requestBody.addProperty("stream", false);
        if (json) requestBody.addProperty("format", "json");
        JsonObject ollamaOptions = new JsonObject();
        ollamaOptions.addProperty("num_predict", maxTokens(json));
        ollamaOptions.addProperty("temperature", ModConfig.LLM_TEMPERATURE.get());
        requestBody.add("options", ollamaOptions);

        debug("Request Body: " + requestBody.toString());

        try (OutputStream os = conn.getOutputStream()) {
            byte[] input = requestBody.toString().getBytes(StandardCharsets.UTF_8);
            os.write(input, 0, input.length);
        }

        int responseCode = conn.getResponseCode();
        String responseMessage = conn.getResponseMessage();
        debug("=== Ollama API Response ===");
        debug("Response Code: " + responseCode + " " + responseMessage);
        debug("Response Headers: " + conn.getHeaderFields());

        if (responseCode == 200) {
            StringBuilder response = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String responseLine;
                while ((responseLine = br.readLine()) != null) {
                    response.append(responseLine.trim());
                }
            }

            debug("Raw Response Body: " + response.toString());

            JsonParser parser = new JsonParser();
            JsonElement element = parser.parse(response.toString());
            JsonObject jsonResponse = element.getAsJsonObject();
            String content = jsonResponse.get("response").getAsString();
            debug("Parsed Content: " + content);
            LOGGER.info("Ollama response received successfully");
            return content;
        } else {
            StringBuilder errorResponse = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(
                    conn.getErrorStream() != null ? conn.getErrorStream() : conn.getInputStream(),
                    StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    errorResponse.append(line);
                }
            }
            debug("Error Response Body: " + errorResponse.toString());
            if (json && rejectsJsonMode(responseCode, errorResponse.toString())) {
                markJsonUnsupported("HTTP " + responseCode + " rejected the JSON-mode parameter");
                LOGGER.warn("[LLM] Ollama rejected JSON mode; retrying as plain text");
                return queryOllama(systemPrompt, userPrompt, false);
            }
            LOGGER.error("Ollama API error " + responseCode + ": " + errorResponse.toString());
            return failWithLog("http-" + responseCode);
        }
    }

    private static String queryGemini(String systemPrompt, String userPrompt, boolean json) throws Exception {
        String apiKey = ModConfig.LLM_API_KEY.get();
        String model = ModConfig.LLM_MODEL.get();
        String apiUrl = ModConfig.LLM_API_URL.get();

        // Default Gemini URL if not set or using other provider URLs
        if (apiUrl.isEmpty() || apiUrl.contains("openai.com") || apiUrl.contains("anthropic.com")) {
            apiUrl = "https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent";
        }

        debug("=== Gemini API Request ===");
        debug("URL: " + apiUrl);
        debug("Model: " + model);
        debug("API Key: " + (apiKey.isEmpty() ? "NOT SET" : apiKey.substring(0, Math.min(8, apiKey.length())) + "..."));
        debug("System Prompt: " + systemPrompt);
        debug("User Prompt: " + userPrompt);

        if (apiKey.isEmpty()) {
            LOGGER.warn("Gemini API key is empty!");
            return failWithLog("no-api-key");
        }

        // Append API key to URL
        String fullUrl = apiUrl + "?key=" + apiKey;
        URL url = new URL(fullUrl);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);
        conn.setConnectTimeout(30000);
        conn.setReadTimeout(60000);

        // Build Gemini request format
        JsonObject requestBody = new JsonObject();

        // System instruction
        JsonObject systemInstruction = new JsonObject();
        JsonArray systemParts = new JsonArray();
        JsonObject systemTextPart = new JsonObject();
        systemTextPart.addProperty("text", systemPrompt);
        systemParts.add(systemTextPart);
        systemInstruction.add("parts", systemParts);
        requestBody.add("system_instruction", systemInstruction);

        // Contents (user message)
        JsonArray contents = new JsonArray();
        JsonObject userContent = new JsonObject();
        userContent.addProperty("role", "user");
        JsonArray userParts = new JsonArray();
        JsonObject userTextPart = new JsonObject();
        userTextPart.addProperty("text", userPrompt);
        userParts.add(userTextPart);
        userContent.add("parts", userParts);
        contents.add(userContent);
        requestBody.add("contents", contents);

        // Generation config — responseMimeType forces Gemini to emit valid JSON.
        JsonObject generationConfig = new JsonObject();
        generationConfig.addProperty("maxOutputTokens", maxTokens(json));
        generationConfig.addProperty("temperature", ModConfig.LLM_TEMPERATURE.get());
        if (json) generationConfig.addProperty("responseMimeType", "application/json");
        requestBody.add("generationConfig", generationConfig);

        debug("Request Body: " + requestBody.toString());

        try (OutputStream os = conn.getOutputStream()) {
            byte[] input = requestBody.toString().getBytes(StandardCharsets.UTF_8);
            os.write(input, 0, input.length);
        }

        int responseCode = conn.getResponseCode();
        String responseMessage = conn.getResponseMessage();
        debug("=== Gemini API Response ===");
        debug("Response Code: " + responseCode + " " + responseMessage);

        if (responseCode == 200) {
            StringBuilder response = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String responseLine;
                while ((responseLine = br.readLine()) != null) {
                    response.append(responseLine.trim());
                }
            }

            debug("Raw Response Body: " + response.toString());

            JsonParser parser = new JsonParser();
            JsonElement element = parser.parse(response.toString());
            JsonObject jsonResponse = element.getAsJsonObject();

            // Parse Gemini response format: candidates[0].content.parts[0].text
            String content = jsonResponse.getAsJsonArray("candidates")
                    .get(0).getAsJsonObject()
                    .getAsJsonObject("content")
                    .getAsJsonArray("parts")
                    .get(0).getAsJsonObject()
                    .get("text").getAsString();
            debug("Parsed Content: " + content);
            LOGGER.info("Gemini response received successfully");
            return content;
        } else {
            StringBuilder errorResponse = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(
                    conn.getErrorStream() != null ? conn.getErrorStream() : conn.getInputStream(),
                    StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    errorResponse.append(line);
                }
            }
            debug("Error Response Body: " + errorResponse.toString());
            if (json && rejectsJsonMode(responseCode, errorResponse.toString())) {
                markJsonUnsupported("HTTP " + responseCode + " rejected the JSON-mode parameter");
                LOGGER.warn("[LLM] Gemini rejected JSON mode; retrying as plain text");
                return queryGemini(systemPrompt, userPrompt, false);
            }
            LOGGER.error("Gemini API error " + responseCode + ": " + errorResponse.toString());
            return failWithLog("http-" + responseCode);
        }
    }
}

