package cn.bugstack.ai.domain.agent.service.armory.matter.model.reasoning;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 在 Spring AI 1.1.0-M3 不认识 reasoning_content 的情况下，承载思考正文与供应商签名的内部信封。
 * 信封只存在于模型适配层，进入 ADK 后会恢复成 thought Part，绝不会直接展示给用户。
 */
public final class ReasoningEnvelope {

    private static final String WIRE_PREFIX = "\uE000reasoning:";
    private static final String WIRE_SUFFIX = "\uE001";
    private static final String THOUGHT_PREFIX = "\uE000thought:";
    private static final String THOUGHT_SUFFIX = "\uE001";

    private ReasoningEnvelope() {
    }

    public static String wire(String reasoning, String signature, String answer) {
        String safeAnswer = answer == null ? "" : answer;
        if (blank(reasoning) && blank(signature)) return safeAnswer;
        return WIRE_PREFIX + encode(reasoning) + ':' + encode(signature) + WIRE_SUFFIX + safeAnswer;
    }

    public static Frame splitWire(String value) {
        if (value == null || !value.startsWith(WIRE_PREFIX)) return new Frame("", "", value == null ? "" : value);
        int end = value.indexOf(WIRE_SUFFIX, WIRE_PREFIX.length());
        if (end < 0) return new Frame("", "", value);
        String payload = value.substring(WIRE_PREFIX.length(), end);
        int separator = payload.indexOf(':');
        if (separator < 0) return new Frame("", "", value);
        return new Frame(decode(payload.substring(0, separator)), decode(payload.substring(separator + 1)),
                value.substring(end + WIRE_SUFFIX.length()));
    }

    public static String thought(String reasoning, String signature) {
        return THOUGHT_PREFIX + encode(reasoning) + ':' + encode(signature) + THOUGHT_SUFFIX;
    }

    public static Frame splitThought(String value) {
        if (value == null || !value.startsWith(THOUGHT_PREFIX) || !value.endsWith(THOUGHT_SUFFIX)) {
            return new Frame(value == null ? "" : value, "", "");
        }
        String payload = value.substring(THOUGHT_PREFIX.length(), value.length() - THOUGHT_SUFFIX.length());
        int separator = payload.indexOf(':');
        if (separator < 0) return new Frame(value, "", "");
        return new Frame(decode(payload.substring(0, separator)), decode(payload.substring(separator + 1)), "");
    }

    private static String encode(String value) {
        if (value == null || value.isEmpty()) return "";
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String decode(String value) {
        if (value == null || value.isEmpty()) return "";
        try {
            return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ignored) {
            return "";
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    public record Frame(String reasoning, String signature, String answer) {
    }
}
