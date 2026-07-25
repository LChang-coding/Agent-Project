package cn.bugstack.ai.domain.agent.service.armory.node;

import cn.bugstack.ai.domain.agent.model.entity.ArmoryCommandEntity;
import cn.bugstack.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import cn.bugstack.ai.domain.agent.model.valobj.AiAgentRegisterVO;
import cn.bugstack.ai.domain.agent.service.armory.AbstractArmorySupport;
import cn.bugstack.ai.domain.agent.service.armory.factory.DefaultArmoryFactory;
import cn.bugstack.ai.domain.agent.service.armory.matter.model.ObservabilitySpringAI;
import cn.bugstack.ai.domain.tool.service.GatewayToolset;
import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import com.google.adk.agents.LlmAgent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;

/** 把原子 Agent 配置转换为 ADK LlmAgent，并放入本次装配的名称索引。 */
@Slf4j
@Service
public class AgentNode extends AbstractArmorySupport {
    /** 原子 Agent 全部构造后进入组合 Agent 装配循环。 */
    @Resource
    private AgentWorkflowNode agentWorkflowNode;
    /** 所有 LlmAgent 共享受运行身份保护的工具入口。 */
    @Resource
    private GatewayToolset gatewayToolset;

    @Override
    protected AiAgentRegisterVO doApply(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        log.info("Ai Agent 装配操作 - AgentNode");

        AiAgentConfigTableVO aiAgentConfigTableVO = requestParameter.getAiAgentConfigTableVO();
        List<AiAgentConfigTableVO.Module.Agent> agents = aiAgentConfigTableVO.getModule().getAgents();

        for (AiAgentConfigTableVO.Module.Agent agentConfig : agents) {
            // 节点未声明模型时复用默认模型；显式模型只替换模型代码，不替换 API 端点。
            String agentModelName = isBlank(agentConfig.getModel()) ? dynamicContext.getChatModelName() : agentConfig.getModel();
            ChatModel chatModel = buildAgentChatModel(dynamicContext, agentConfig, agentModelName);
            LlmAgent llmAgent = LlmAgent.builder()
                    .name(agentConfig.getName())
                    .description(agentConfig.getDescription())
                    .model(new ObservabilitySpringAI(chatModel, agentModelName))
                    .instruction(agentConfig.getInstruction())
                    .outputKey(agentConfig.getOutputKey())
                    .tools(gatewayToolset)
                    .build();

            // 配置名称是后续工作流 subAgents 和 Runner agentName 的唯一引用键。
            dynamicContext.getAgentGroup().put(agentConfig.getName(), llmAgent);
        }

        return router(requestParameter, dynamicContext);
    }

    @Override
    public StrategyHandler<ArmoryCommandEntity, DefaultArmoryFactory.DynamicContext, AiAgentRegisterVO> get(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        return agentWorkflowNode;
    }

    /** 显式模型创建独立 ChatModel；未显式配置时复用默认实例和连接池。 */
    private ChatModel buildAgentChatModel(DefaultArmoryFactory.DynamicContext dynamicContext,
                                          AiAgentConfigTableVO.Module.Agent agentConfig,
                                          String agentModelName) throws Exception {
        if (isBlank(agentConfig.getModel())) {
            return dynamicContext.getChatModel();
        }

        OpenAiApi openAiApi = dynamicContext.getOpenAiApi();
        return OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(OpenAiChatOptions.builder()
                        .model(agentModelName)
                        .build())
                .build();
    }

    /** 将 null 与纯空白统一视为未配置。 */
    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

}
