package cn.bugstack.ai.domain.agent.service.armory.matter.model.reasoning;

import com.google.adk.models.LlmResponse;
import com.google.genai.types.Content;
import com.google.genai.types.Part;

import java.util.ArrayList;
import java.util.List;

/** 分开聚合思考与答案，避免 ADK 原聚合器抹掉 Part.thought 标记。 */
public final class ReasoningStreamingResponseAggregator {

    private final StringBuilder reasoning = new StringBuilder();
    private final StringBuilder answer = new StringBuilder();
    private final List<Part> toolCalls = new ArrayList<>();
    private String signature = "";

    public synchronized LlmResponse process(LlmResponse response) {
        response.content().flatMap(Content::parts).orElse(List.of()).forEach(part -> {
            if (part.text().isPresent()) {
                if (part.thought().orElse(false)) {
                    ReasoningEnvelope.Frame frame = ReasoningEnvelope.splitThought(part.text().orElse(""));
                    reasoning.append(frame.reasoning());
                    if (!frame.signature().isEmpty()) signature = frame.signature();
                } else {
                    answer.append(part.text().orElse(""));
                }
            } else if (part.functionCall().isPresent()) {
                toolCalls.add(part);
            }
        });
        return response.toBuilder().content(content()).build();
    }

    public synchronized LlmResponse finish() {
        LlmResponse response = LlmResponse.builder().content(content()).partial(false).turnComplete(true).build();
        reasoning.setLength(0);
        answer.setLength(0);
        toolCalls.clear();
        signature = "";
        return response;
    }

    public synchronized boolean isEmpty() {
        return reasoning.isEmpty() && answer.isEmpty() && toolCalls.isEmpty();
    }

    private Content content() {
        List<Part> parts = new ArrayList<>();
        if (!reasoning.isEmpty()) parts.add(Part.builder()
                .text(ReasoningEnvelope.thought(reasoning.toString(), signature)).thought(true).build());
        if (!answer.isEmpty()) parts.add(Part.fromText(answer.toString()));
        parts.addAll(toolCalls);
        return Content.builder().role("model").parts(parts).build();
    }
}
