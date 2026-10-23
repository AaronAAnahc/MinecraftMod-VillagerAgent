package com.github.AaronAA0721.villageragent.ai;

import com.github.AaronAA0721.villageragent.config.ModConfig;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.StringTextComponent;
import net.minecraft.util.text.TextFormatting;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Central "is the LLM configured?" check + the player-facing setup hint.
 *
 * <p>All three consumers (the {@code /va llm status} command, the {@code /va info}
 * command, and the runtime "LLM not configured" reminder sent to the player's chat
 * bar) pull from here so the wording never drifts between them.
 *
 * <p>Definition of "configured": a valid API type plus the fields that
 * {@link LLMService} actually requires for that type —
 * <ul>
 *   <li>openai / anthropic / gemini: non-empty API key + non-empty model</li>
 *   <li>ollama: non-empty model (no key needed)</li>
 * </ul>
 */
public final class LLMSetupReminder {
    private LLMSetupReminder() {}

    /** API types understood by {@link LLMService#queryLLM} (kept in sync with it). */
    private static final List<String> VALID_TYPES = Arrays.asList("openai", "anthropic", "ollama", "gemini");

    /** True when the LLM can actually be called. */
    public static boolean isConfigured() {
        return getMissingItems().isEmpty();
    }

    /**
     * Human-readable list of what is still missing for a valid config.
     * Empty list => the LLM is configured.
     */
    public static List<String> getMissingItems() {
        List<String> missing = new ArrayList<>();
        String type = ModConfig.LLM_API_TYPE.get();
        if (type == null || !VALID_TYPES.contains(type.toLowerCase())) {
            missing.add("API 类型 (运行: /va llm apitype openai|anthropic|ollama|gemini)");
        }
        boolean needsKey = type != null && (type.equalsIgnoreCase("openai")
                || type.equalsIgnoreCase("anthropic") || type.equalsIgnoreCase("gemini"));
        if (needsKey && (ModConfig.LLM_API_KEY.get() == null || ModConfig.LLM_API_KEY.get().isEmpty())) {
            missing.add("API 密钥 (运行: /va llm apikey <你的Key>)");
        }
        if (ModConfig.LLM_MODEL.get() == null || ModConfig.LLM_MODEL.get().isEmpty()) {
            missing.add("模型名 (运行: /va llm model <模型名, 如 deepseek-chat>)");
        }
        return missing;
    }

    /** Build the multi-line reminder sent to the player's chat bar. */
    public static List<ITextComponent> buildReminder() {
        List<ITextComponent> lines = new ArrayList<>();
        lines.add(new StringTextComponent(TextFormatting.RED
                + "[VillagerAgent] LLM 尚未配置；村民规则大脑与基础社交正常运行，智能对话扩展暂不可用。"));
        for (String item : getMissingItems()) {
            lines.add(new StringTextComponent(TextFormatting.YELLOW + "  \u2717 缺少: " + item));
        }
        lines.add(new StringTextComponent(TextFormatting.GOLD + "配置示例 (OpenAI 兼容, 以 DeepSeek 为例):"));
        lines.add(new StringTextComponent(TextFormatting.GREEN + "  /va llm apitype openai"));
        lines.add(new StringTextComponent(TextFormatting.GREEN + "  /va llm apikey <你的API Key>"));
        lines.add(new StringTextComponent(TextFormatting.GREEN + "  /va llm model deepseek-chat"));
        lines.add(new StringTextComponent(TextFormatting.GREEN
                + "  /va llm apiurl https://api.deepseek.com/v1/chat/completions"));
        lines.add(new StringTextComponent(TextFormatting.YELLOW
                + "配置完成后可用 /va llm status 检查，或 /va info 查看当前设置。"));
        return lines;
    }
}
