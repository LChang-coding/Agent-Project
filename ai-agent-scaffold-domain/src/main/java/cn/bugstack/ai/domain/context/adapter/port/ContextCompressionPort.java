package cn.bugstack.ai.domain.context.adapter.port;

import org.springframework.ai.chat.model.ChatModel;

/**
 * 上下文压缩模型端口。
 */
public interface ContextCompressionPort {

    /**
     * 生成结构化摘要；参数是模型和压缩提示；返回 JSON 摘要文本。
     */
    String compress(ChatModel chatModel, String prompt);
}
