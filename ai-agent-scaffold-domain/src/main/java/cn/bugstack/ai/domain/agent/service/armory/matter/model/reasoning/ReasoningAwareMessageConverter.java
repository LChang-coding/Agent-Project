package cn.bugstack.ai.domain.agent.service.armory.matter.model.reasoning;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.adk.models.springai.MessageConverter;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/** ADK 与 Spring AI 之间的 thought-aware 转换器。 */
public final class ReasoningAwareMessageConverter {

    private final MessageConverter delegate;

    public ReasoningAwareMessageConverter(ObjectMapper objectMapper) {
        this.delegate = new MessageConverter(objectMapper);
    }

    public Prompt toLlmPrompt(LlmRequest request) {
        Prompt prompt = delegate.toLlmPrompt(request);
        Deque<ReasoningEnvelope.Frame> assistantFrames = new ArrayDeque<>();
        for (Content content : request.contents()) {
            String role = content.role().orElse("");
            if ("assistant".equalsIgnoreCase(role) || "model".equalsIgnoreCase(role)) {
                assistantFrames.add(frame(content));
            }
        }
        if (assistantFrames.isEmpty()) return prompt;
        List<Message> messages = new ArrayList<>(prompt.getInstructions().size());
        for (Message message : prompt.getInstructions()) {
            if (!(message instanceof AssistantMessage assistant) || assistantFrames.isEmpty()) {
                messages.add(message);
                continue;
            }
            ReasoningEnvelope.Frame frame = assistantFrames.removeFirst();
            messages.add(AssistantMessage.builder()
                    .content(ReasoningEnvelope.wire(frame.reasoning(), frame.signature(), frame.answer()))
                    .properties(assistant.getMetadata()).toolCalls(assistant.getToolCalls()).media(assistant.getMedia()).build());
        }
        return new Prompt(messages, prompt.getOptions());
    }

    public LlmResponse toLlmResponse(ChatResponse response) {
        return normalize(delegate.toLlmResponse(response));
    }

    public LlmResponse toLlmResponse(ChatResponse response, boolean streaming) {
        return normalize(delegate.toLlmResponse(response, streaming));
    }

    private LlmResponse normalize(LlmResponse response) {
        if (response.content().isEmpty() || response.content().get().parts().isEmpty()) return response;
        Content source = response.content().get();
        List<Part> parts = new ArrayList<>();
        for (Part part : source.parts().orElse(List.of())) {
            if (part.text().isEmpty()) {
                parts.add(part);
                continue;
            }
            ReasoningEnvelope.Frame frame = ReasoningEnvelope.splitWire(part.text().orElse(""));
            if (!frame.reasoning().isEmpty() || !frame.signature().isEmpty()) {
                parts.add(Part.builder().text(ReasoningEnvelope.thought(frame.reasoning(), frame.signature()))
                        .thought(true).build());
            }
            if (!frame.answer().isEmpty()) parts.add(Part.fromText(frame.answer()));
        }
        return response.toBuilder().content(source.toBuilder().parts(parts).build()).build();
    }

    private ReasoningEnvelope.Frame frame(Content content) {
        StringBuilder reasoning = new StringBuilder();
        StringBuilder answer = new StringBuilder();
        String signature = "";
        for (Part part : content.parts().orElse(List.of())) {
            if (part.text().isEmpty()) continue;
            String text = part.text().orElse("");
            if (part.thought().orElse(false)) {
                ReasoningEnvelope.Frame thought = ReasoningEnvelope.splitThought(text);
                reasoning.append(thought.reasoning());
                if (!thought.signature().isEmpty()) signature = thought.signature();
            } else {
                answer.append(text);
            }
        }
        return new ReasoningEnvelope.Frame(reasoning.toString(), signature, answer.toString());
    }
}
