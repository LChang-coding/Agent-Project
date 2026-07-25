package cn.bugstack.ai.domain.agent.model.valobj;

import com.google.adk.runner.InMemoryRunner;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.ai.chat.model.ChatModel;

/**
 * Ai Agent 智能体注册值对象
 */
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AiAgentRegisterVO {

    /** Runner 应用名。 */
    private String appName;

    /**
     * 智能体ID
     */
    private String agentId;

    /**
     * 智能体名称
     */
    private String agentName;

    /**
     * 智能体描述
     */
    private String agentDesc;

    /** 已完成装配的 ADK 执行器。 */
    private InMemoryRunner runner;

    /**
     * 当前 Agent 使用的模型；上下文压缩复用该模型。
     */
    private ChatModel chatModel;

}
