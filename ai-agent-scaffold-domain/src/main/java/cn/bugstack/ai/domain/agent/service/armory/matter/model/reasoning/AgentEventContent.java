package cn.bugstack.ai.domain.agent.service.armory.matter.model.reasoning;

import com.google.adk.events.Event;
import com.google.genai.types.Content;
import com.google.genai.types.Part;

import java.util.List;

/** 从 ADK 事件中分别读取可展示思考与最终回答，不使用会混合两者的 stringifyContent。 */
public final class AgentEventContent {
    private AgentEventContent() {
    }

    public static Snapshot snapshot(Event event) {
        StringBuilder thinking = new StringBuilder();
        StringBuilder answer = new StringBuilder();
        event.content().flatMap(Content::parts).orElse(List.of()).forEach(part -> append(part, thinking, answer));
        return new Snapshot(thinking.toString(), answer.toString());
    }

    private static void append(Part part, StringBuilder thinking, StringBuilder answer) {
        if (part.text().isEmpty()) return;
        if (part.thought().orElse(false)) {
            thinking.append(ReasoningEnvelope.splitThought(part.text().orElse("")).reasoning());
        } else {
            answer.append(part.text().orElse(""));
        }
    }

    public record Snapshot(String thinking, String answer) {
    }
}
