package cn.bugstack.ai.domain.agent.service.armory.matter.model.reasoning;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.HashMap;
import java.util.Map;

/** 将供应商可能返回的累计文本快照归一化为真正增量。 */
final class StreamingTextDeltaNormalizer {
    private final Map<Integer, StringBuilder> reasoningByChoice = new HashMap<>();
    private final Map<Integer, StringBuilder> answerByChoice = new HashMap<>();

    ObjectNode normalize(ObjectNode response) {
        JsonNode choices = response.get("choices");
        if (!(choices instanceof ArrayNode array)) return response;
        for (int position = 0; position < array.size(); position++) {
            JsonNode value = array.get(position);
            if (!(value instanceof ObjectNode choice) || !(choice.get("delta") instanceof ObjectNode delta)) continue;
            int index = choice.path("index").asInt(position);
            normalizeFirst(delta, reasoningByChoice.computeIfAbsent(index, ignored -> new StringBuilder()),
                    "reasoning_content", "reasoning", "analysis");
            normalizeFirst(delta, answerByChoice.computeIfAbsent(index, ignored -> new StringBuilder()), "content");
        }
        return response;
    }

    private void normalizeFirst(ObjectNode delta, StringBuilder accumulated, String... fields) {
        for (String field : fields) {
            JsonNode value = delta.get(field);
            if (value == null || !value.isTextual()) continue;
            delta.put(field, toDelta(accumulated, value.asText()));
            return;
        }
    }

    private String toDelta(StringBuilder accumulated, String incoming) {
        if (incoming == null || incoming.isEmpty()) return "";
        String previous = accumulated.toString();
        if (incoming.startsWith(previous)) {
            String delta = incoming.substring(previous.length());
            accumulated.setLength(0);
            accumulated.append(incoming);
            return delta;
        }
        accumulated.append(incoming);
        return incoming;
    }
}
