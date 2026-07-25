package cn.bugstack.ai.domain.agent.service.armory.node;

import cn.bugstack.ai.domain.agent.model.entity.ArmoryCommandEntity;
import cn.bugstack.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import cn.bugstack.ai.domain.agent.model.valobj.AiAgentRegisterVO;
import cn.bugstack.ai.domain.agent.service.armory.AbstractArmorySupport;
import cn.bugstack.ai.domain.agent.service.armory.factory.DefaultArmoryFactory;
import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
/** 基于共享 API 客户端构造配置表的默认聊天模型。 */
@Slf4j
@Service
public class ChatModelNode extends AbstractArmorySupport {

    /** 默认模型完成后开始构造原子 Agent。 */
    @Resource
    private AgentNode agentNode;

    @Override
    protected AiAgentRegisterVO doApply(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        log.info("Ai Agent 装配操作 - ChatModelNode");

        // API 节点必须先执行；缺失会在模型构造时立即失败。
        OpenAiApi openAiApi = dynamicContext.getOpenAiApi();

        // 默认模型代码同时用于供应商请求和观测标签。
        AiAgentConfigTableVO aiAgentConfigTableVO = requestParameter.getAiAgentConfigTableVO();
        AiAgentConfigTableVO.Module.ChatModel chatModelConfig = aiAgentConfigTableVO.getModule().getChatModel();
        // 工具由 GatewayToolset 唯一分发；模型本身不挂 ToolCallback，防止绕过运行闸门。
        ChatModel chatModel = OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(OpenAiChatOptions.builder()
                        .model(chatModelConfig.getModel())
                        .build())
                .build();

        dynamicContext.setChatModel(chatModel);
        dynamicContext.setChatModelName(chatModelConfig.getModel());
        // 清空兼容字段，明确本配置不允许旧式模型直连工具。
        dynamicContext.getToolCallbacks().clear();
        dynamicContext.setValue("chatModelName", chatModelConfig.getModel());

        return router(requestParameter, dynamicContext);
    }

    @Override
    public StrategyHandler<ArmoryCommandEntity, DefaultArmoryFactory.DynamicContext, AiAgentRegisterVO> get(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        return agentNode;
    }

}
