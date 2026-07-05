package cn.bugstack.ai.domain.agent.service.armory.node;

import cn.bugstack.ai.domain.agent.model.entity.ArmoryCommandEntity;
import cn.bugstack.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import cn.bugstack.ai.domain.agent.model.valobj.AiAgentRegisterVO;
import cn.bugstack.ai.domain.agent.service.armory.AbstractArmorySupport;
import cn.bugstack.ai.domain.agent.service.armory.factory.DefaultArmoryFactory;
import cn.bugstack.ai.domain.agent.service.armory.matter.mcp.client.TooMcpCreateService;
import cn.bugstack.ai.domain.agent.service.armory.matter.mcp.client.factory.DefaultMcpClientFactory;
import cn.bugstack.ai.domain.agent.service.armory.matter.model.ObservabilitySpringAI;
import cn.bugstack.ai.domain.agent.service.armory.matter.skills.ToolSkillsCreateService;
import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import com.google.adk.agents.LlmAgent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class AgentNode extends AbstractArmorySupport {
    @Resource
    private AgentWorkflowNode agentWorkflowNode;
    @Resource
    private DefaultMcpClientFactory defaultMcpClientFactory;
    @Resource
    private ToolSkillsCreateService toolSkillsCreateService;

    @Override
    protected AiAgentRegisterVO doApply(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        log.info("Ai Agent 装配操作 - AgentNode");

        AiAgentConfigTableVO aiAgentConfigTableVO = requestParameter.getAiAgentConfigTableVO();
        List<AiAgentConfigTableVO.Module.Agent> agents = aiAgentConfigTableVO.getModule().getAgents();

        for (AiAgentConfigTableVO.Module.Agent agentConfig : agents) {
            String agentModelName = isBlank(agentConfig.getModel()) ? dynamicContext.getChatModelName() : agentConfig.getModel();
            ChatModel chatModel = buildAgentChatModel(dynamicContext, agentConfig, agentModelName);
            LlmAgent llmAgent = LlmAgent.builder()
                    .name(agentConfig.getName())
                    .description(agentConfig.getDescription())
                    .model(new ObservabilitySpringAI(chatModel, agentModelName))
                    .instruction(agentConfig.getInstruction())
                    .outputKey(agentConfig.getOutputKey())
                    .build();

            dynamicContext.getAgentGroup().put(agentConfig.getName(), llmAgent);
        }

        return router(requestParameter, dynamicContext);
    }

    @Override
    public StrategyHandler<ArmoryCommandEntity, DefaultArmoryFactory.DynamicContext, AiAgentRegisterVO> get(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        return agentWorkflowNode;
    }

    /**
     * 构建节点模型；参数是装配上下文、节点配置和模型名称；返回节点专属 ChatModel。
     */
    private ChatModel buildAgentChatModel(DefaultArmoryFactory.DynamicContext dynamicContext,
                                          AiAgentConfigTableVO.Module.Agent agentConfig,
                                          String agentModelName) throws Exception {
        if (isBlank(agentConfig.getModel()) && empty(agentConfig.getToolMcpList()) && empty(agentConfig.getToolSkillsList())) {
            return dynamicContext.getChatModel();
        }

        List<ToolCallback> callbacks = new ArrayList<>();
        if (empty(agentConfig.getToolMcpList()) && empty(agentConfig.getToolSkillsList())) {
            callbacks.addAll(dynamicContext.getToolCallbacks());
        } else {
            callbacks.addAll(buildMcpCallbacks(agentConfig.getToolMcpList()));
            callbacks.addAll(buildSkillCallbacks(agentConfig.getToolSkillsList()));
        }

        OpenAiApi openAiApi = dynamicContext.getOpenAiApi();
        return OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(OpenAiChatOptions.builder()
                        .model(agentModelName)
                        .toolCallbacks(callbacks)
                        .build())
                .build();
    }

    /**
     * 构建 MCP 工具回调；参数是 MCP 配置列表；返回工具回调列表。
     */
    private List<ToolCallback> buildMcpCallbacks(List<AiAgentConfigTableVO.Module.ChatModel.ToolMcp> toolMcpList) throws Exception {
        List<ToolCallback> callbacks = new ArrayList<>();
        if (empty(toolMcpList)) {
            return callbacks;
        }
        for (AiAgentConfigTableVO.Module.ChatModel.ToolMcp toolMcp : toolMcpList) {
            TooMcpCreateService tooMcpCreateService = defaultMcpClientFactory.getTooMcpCreateService(toolMcp);
            callbacks.addAll(List.of(tooMcpCreateService.buildToolCallback(toolMcp)));
        }
        return callbacks;
    }

    /**
     * 构建 Skill 工具回调；参数是 Skill 配置列表；返回工具回调列表。
     */
    private List<ToolCallback> buildSkillCallbacks(List<AiAgentConfigTableVO.Module.ChatModel.ToolSkills> toolSkillsList) throws Exception {
        List<ToolCallback> callbacks = new ArrayList<>();
        if (empty(toolSkillsList)) {
            return callbacks;
        }
        for (AiAgentConfigTableVO.Module.ChatModel.ToolSkills toolSkills : toolSkillsList) {
            callbacks.addAll(List.of(toolSkillsCreateService.buildToolCallback(toolSkills)));
        }
        return callbacks;
    }

    /**
     * 判断列表为空；参数是列表；返回是否为空。
     */
    private boolean empty(List<?> list) {
        return list == null || list.isEmpty();
    }

    /**
     * 判断字符串为空；参数是字符串；返回是否为空。
     */
    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

}
