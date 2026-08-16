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

    public static CompletableFuture<String> queryLLM(String systemPrompt, String userPrompt) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String apiType = ModConfig.LLM_API_TYPE.get();

                if ("openai".equalsIgnoreCase(apiType)) {
                    return queryOpenAI(systemPrompt, userPrompt);
                } else if ("anthropic".equalsIgnoreCase(apiType)) {
                    return queryAnthropic(systemPrompt, userPrompt);
                } else if ("ollama".equalsIgnoreCase(apiType)) {
                    return queryOllama(systemPrompt, userPrompt);
                } else if ("gemini".equalsIgnoreCase(apiType)) {
                    return queryGemini(systemPrompt, userPrompt);
                } else {
                    LOGGER.warn("Unknown LLM API type: " + apiType);
                    return fail("unknown-api:" + apiType);
                }
            } catch (Exception e) {
                LOGGER.error("Error querying LLM: ", e);
                return fail("exception:" + e.getClass().getSimpleName());
            }
        }, executor);
    }
    
    private static String queryOpenAI(String systemPrompt, String userPrompt) throws Exception {
        String apiKey = ModConfig.LLM_API_KEY.get();
        String model = ModConfig.LLM_MODEL.get();
        String apiUrl = ModConfig.LLM_API_URL.get();

        LOGGER.debug("=== OpenAI API Request ===");
        LOGGER.debug("URL: " + apiUrl);
        LOGGER.debug("Model: " + model);
        LOGGER.debug("API Key: " + (apiKey.isEmpty() ? "NOT SET" : apiKey.substring(0, Math.min(8, apiKey.length())) + "..."));
        LOGGER.debug("System Prompt: " + systemPrompt);
        LOGGER.debug("User Prompt: " + userPrompt);

        if (apiKey.isEmpty()) {
            LOGGER.warn("OpenAI API key is empty!");
            return fail("no-api-key");
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
        requestBody.addProperty("max_tokens", ModConfig.LLM_MAX_TOKENS.get());

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

        LOGGER.debug("Request Body: " + requestBody.toString());

        try (OutputStream os = conn.getOutputStream()) {
            byte[] input = requestBody.toString().getBytes(StandardCharsets.UTF_8);
            os.write(input, 0, input.length);
        }

        int responseCode = conn.getResponseCode();
        String responseMessage = conn.getResponseMessage();
        LOGGER.debug("=== OpenAI API Response ===");
        LOGGER.debug("Response Code: " + responseCode + " " + responseMessage);
        LOGGER.debug("Response Headers: " + conn.getHeaderFields());

        if (responseCode == 200) {
            BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
            StringBuilder response = new StringBuilder();
            String responseLine;
            while ((responseLine = br.readLine()) != null) {
                response.append(responseLine.trim());
            }

            LOGGER.debug("Raw Response Body: " + response.toString());

            JsonParser parser = new JsonParser();
            JsonElement element = parser.parse(response.toString());
            JsonObject jsonResponse = element.getAsJsonObject();
            String content = jsonResponse.getAsJsonArray("choices")
                    .get(0).getAsJsonObject()
                    .getAsJsonObject("message")
                    .get("content").getAsString();
            LOGGER.debug("Parsed Content: " + content);
            LOGGER.info("OpenAI response received successfully");
            return content;
        } else {
            // Read error response
            BufferedReader br = new BufferedReader(new InputStreamReader(
                    conn.getErrorStream() != null ? conn.getErrorStream() : conn.getInputStream(),
                    StandardCharsets.UTF_8));
            StringBuilder errorResponse = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                errorResponse.append(line);
            }
            LOGGER.debug("Error Response Body: " + errorResponse.toString());
            LOGGER.error("OpenAI API error " + responseCode + ": " + errorResponse.toString());
            return fail("http-" + responseCode);
        }
    }
    
    private static String queryAnthropic(String systemPrompt, String userPrompt) throws Exception {
        String apiKey = ModConfig.LLM_API_KEY.get();
        String model = ModConfig.LLM_MODEL.get();
        String apiUrl = ModConfig.LLM_API_URL.get();

        LOGGER.debug("=== Anthropic API Request ===");
        LOGGER.debug("URL: " + apiUrl);
        LOGGER.debug("Model: " + model);
        LOGGER.debug("API Key: " + (apiKey.isEmpty() ? "NOT SET" : apiKey.substring(0, Math.min(8, apiKey.length())) + "..."));
        LOGGER.debug("System Prompt: " + systemPrompt);
        LOGGER.debug("User Prompt: " + userPrompt);

        if (apiKey.isEmpty()) {
            LOGGER.warn("Anthropic API key is empty!");
            return fail("no-api-key");
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
        requestBody.addProperty("max_tokens", ModConfig.LLM_MAX_TOKENS.get());
        requestBody.addProperty("system", systemPrompt);

        JsonArray messages = new JsonArray();
        JsonObject userMessage = new JsonObject();
        userMessage.addProperty("role", "user");
        userMessage.addProperty("content", userPrompt);
        messages.add(userMessage);

        requestBody.add("messages", messages);

        LOGGER.debug("Request Body: " + requestBody.toString());

        try (OutputStream os = conn.getOutputStream()) {
            byte[] input = requestBody.toString().getBytes(StandardCharsets.UTF_8);
            os.write(input, 0, input.length);
        }

        int responseCode = conn.getResponseCode();
        String responseMessage = conn.getResponseMessage();
        LOGGER.debug("=== Anthropic API Response ===");
        LOGGER.debug("Response Code: " + responseCode + " " + responseMessage);
        LOGGER.debug("Response Headers: " + conn.getHeaderFields());

        if (responseCode == 200) {
            BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
            StringBuilder response = new StringBuilder();
            String responseLine;
            while ((responseLine = br.readLine()) != null) {
                response.append(responseLine.trim());
            }

            LOGGER.debug("Raw Response Body: " + response.toString());

            JsonParser parser = new JsonParser();
            JsonElement element = parser.parse(response.toString());
            JsonObject jsonResponse = element.getAsJsonObject();
            String content = jsonResponse.getAsJsonArray("content")
                    .get(0).getAsJsonObject()
                    .get("text").getAsString();
            LOGGER.debug("Parsed Content: " + content);
            LOGGER.info("Anthropic response received successfully");
            return content;
        } else {
            BufferedReader br = new BufferedReader(new InputStreamReader(
                    conn.getErrorStream() != null ? conn.getErrorStream() : conn.getInputStream(),
                    StandardCharsets.UTF_8));
            StringBuilder errorResponse = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                errorResponse.append(line);
            }
            LOGGER.debug("Error Response Body: " + errorResponse.toString());
            LOGGER.error("Anthropic API error " + responseCode + ": " + errorResponse.toString());
            return fail("http-" + responseCode);
        }
    }

    private static String queryOllama(String systemPrompt, String userPrompt) throws Exception {
        String model = ModConfig.LLM_MODEL.get();
        String apiUrl = ModConfig.LLM_API_URL.get();

        // Default Ollama URL if not set
        if (apiUrl.isEmpty() || apiUrl.contains("openai.com") || apiUrl.contains("anthropic.com")) {
            apiUrl = "http://localhost:11434/api/generate";
        }

        LOGGER.debug("=== Ollama API Request ===");
        LOGGER.debug("URL: " + apiUrl);
        LOGGER.debug("Model: " + model);
        LOGGER.debug("System Prompt: " + systemPrompt);
        LOGGER.debug("User Prompt: " + userPrompt);

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

        LOGGER.debug("Request Body: " + requestBody.toString());

        try (OutputStream os = conn.getOutputStream()) {
            byte[] input = requestBody.toString().getBytes(StandardCharsets.UTF_8);
            os.write(input, 0, input.length);
        }

        int responseCode = conn.getResponseCode();
        String responseMessage = conn.getResponseMessage();
        LOGGER.debug("=== Ollama API Response ===");
        LOGGER.debug("Response Code: " + responseCode + " " + responseMessage);
        LOGGER.debug("Response Headers: " + conn.getHeaderFields());

        if (responseCode == 200) {
            BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
            StringBuilder response = new StringBuilder();
            String responseLine;
            while ((responseLine = br.readLine()) != null) {
                response.append(responseLine.trim());
            }

            LOGGER.debug("Raw Response Body: " + response.toString());

            JsonParser parser = new JsonParser();
            JsonElement element = parser.parse(response.toString());
            JsonObject jsonResponse = element.getAsJsonObject();
            String content = jsonResponse.get("response").getAsString();
            LOGGER.debug("Parsed Content: " + content);
            LOGGER.info("Ollama response received successfully");
            return content;
        } else {
            BufferedReader br = new BufferedReader(new InputStreamReader(
                    conn.getErrorStream() != null ? conn.getErrorStream() : conn.getInputStream(),
                    StandardCharsets.UTF_8));
            StringBuilder errorResponse = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                errorResponse.append(line);
            }
            LOGGER.debug("Error Response Body: " + errorResponse.toString());
            LOGGER.error("Ollama API error " + responseCode + ": " + errorResponse.toString());
            return fail("http-" + responseCode);
        }
    }

    private static String queryGemini(String systemPrompt, String userPrompt) throws Exception {
        String apiKey = ModConfig.LLM_API_KEY.get();
        String model = ModConfig.LLM_MODEL.get();
        String apiUrl = ModConfig.LLM_API_URL.get();

        // Default Gemini URL if not set or using other provider URLs
        if (apiUrl.isEmpty() || apiUrl.contains("openai.com") || apiUrl.contains("anthropic.com")) {
            apiUrl = "https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent";
        }

        LOGGER.debug("=== Gemini API Request ===");
        LOGGER.debug("URL: " + apiUrl);
        LOGGER.debug("Model: " + model);
        LOGGER.debug("API Key: " + (apiKey.isEmpty() ? "NOT SET" : apiKey.substring(0, Math.min(8, apiKey.length())) + "..."));
        LOGGER.debug("System Prompt: " + systemPrompt);
        LOGGER.debug("User Prompt: " + userPrompt);

        if (apiKey.isEmpty()) {
            LOGGER.warn("Gemini API key is empty!");
            return fail("no-api-key");
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

        // Generation config
        JsonObject generationConfig = new JsonObject();
        generationConfig.addProperty("maxOutputTokens", ModConfig.LLM_MAX_TOKENS.get());
        generationConfig.addProperty("temperature", ModConfig.LLM_TEMPERATURE.get());
        requestBody.add("generationConfig", generationConfig);

        LOGGER.debug("Request Body: " + requestBody.toString());

        try (OutputStream os = conn.getOutputStream()) {
            byte[] input = requestBody.toString().getBytes(StandardCharsets.UTF_8);
            os.write(input, 0, input.length);
        }

        int responseCode = conn.getResponseCode();
        String responseMessage = conn.getResponseMessage();
        LOGGER.debug("=== Gemini API Response ===");
        LOGGER.debug("Response Code: " + responseCode + " " + responseMessage);

        if (responseCode == 200) {
            BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
            StringBuilder response = new StringBuilder();
            String responseLine;
            while ((responseLine = br.readLine()) != null) {
                response.append(responseLine.trim());
            }

            LOGGER.debug("Raw Response Body: " + response.toString());

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
            LOGGER.debug("Parsed Content: " + content);
            LOGGER.info("Gemini response received successfully");
            return content;
        } else {
            BufferedReader br = new BufferedReader(new InputStreamReader(
                    conn.getErrorStream() != null ? conn.getErrorStream() : conn.getInputStream(),
                    StandardCharsets.UTF_8));
            StringBuilder errorResponse = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                errorResponse.append(line);
            }
            LOGGER.debug("Error Response Body: " + errorResponse.toString());
            LOGGER.error("Gemini API error " + responseCode + ": " + errorResponse.toString());
            return fail("http-" + responseCode);
        }
    }
}

