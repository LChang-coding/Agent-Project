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
 * <p>只调用模型并返回原始摘要；任务状态、范围校验和取消屏障由领域层负责。</p>
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
        // 压缩提示词已由领域层按 Token 预算封装，此处保持单轮调用。
        ChatResponse response = chatModel.call(new Prompt(List.of(new UserMessage(prompt))));
        // 空响应不能当作有效摘要写入上下文修订。
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            throw new IllegalStateException("上下文压缩模型返回为空");
        }
        return response.getResult().getOutput().getText();
    }
}
