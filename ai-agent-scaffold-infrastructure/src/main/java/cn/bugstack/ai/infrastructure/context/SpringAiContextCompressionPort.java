package cn.bugstack.ai.infrastructure.context;

import cn.bugstack.ai.domain.context.adapter.port.ContextCompressionPort;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Spring AI 上下文压缩端口。
 */
@Component
public class SpringAiContextCompressionPort implements ContextCompressionPort {

    /**
     * 生成结构化摘要；参数是模型和压缩提示；返回 JSON 摘要文本。
     */
    @Override
    public String compress(ChatModel chatModel, String prompt) {
        if (chatModel == null) {
            throw new IllegalStateException("上下文压缩缺少 ChatModel");
        }
        ChatResponse response = chatModel.call(new Prompt(List.of(new UserMessage(prompt))));
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            throw new IllegalStateException("上下文压缩模型返回为空");
        }
        return response.getResult().getOutput().getText();
    }
}
