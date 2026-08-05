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

/**
 * 装配第三层：把配置里的每个原子 Agent 建成 ADK 的 LlmAgent，并按名字存进上下文索引。
 *
 * <p>解决什么问题：配置里写的是「角色名 + 提示词 + 用哪个模型」，要变成真正能跑的 Agent 对象。
 * 建好后必须以配置名为键存起来，因为工作流的 subAgents 和 Runner 的 agentName 都是按名字引用的。</p>
 *
 * <p>所属层次：领域层的装配节点。</p>
 *
 * <p>谁会调用它：{@code ChatModelNode} 装配完默认模型后转给它。</p>
 *
 * <p>它向下调用什么：为每个 Agent 挂上网关工具集和可观测性模型包装，
 * 完成后转给 {@code AgentWorkflowNode} 处理组合工作流。</p>
 *
 * <p>它不负责什么：不校验提示词内容、不检查 outputKey 是否被下游用到、不校验模型代码是否真实存在。
 * 模型代码写错会在对话时才由模型服务报错。</p>
 */
@Slf4j
@Service
public class AgentNode extends AbstractArmorySupport {
    /**
     * 下一个装配节点：组合工作流调度节点。
     *
     * <p>必须等所有原子 Agent 都进了名称索引再走，否则工作流按名字找子 Agent 会找不到。</p>
     */
    @Resource
    private AgentWorkflowNode agentWorkflowNode;
    /**
     * 全局唯一的工具入口，所有 Agent 共用它来调用工具。
     *
     * <p>为什么必须统一走它：工具调用前需要校验运行身份、检查运行是否已被取消。
     * 如果让模型直接挂工具回调，这些闸门就被绕过了，取消运行后工具仍会继续执行并产生费用。</p>
     */
    @Resource
    private GatewayToolset gatewayToolset;

    /**
     * 逐个把原子 Agent 配置建成 LlmAgent 并登记进名称索引。
     *
     * <p>各层职责：
     * 第一层：取出配置表里的原子 Agent 列表。
     * 第二层：对每个 Agent 决定用哪个模型代码——没写就沿用配置表的默认模型代码。
     * 第三层：按模型代码决定复用默认模型实例还是单独建一个，然后包一层可观测性外壳。
     * 第四层：组装 LlmAgent，挂上统一工具入口，并以配置名为键放进上下文索引。</p>
     *
     * <p>数据流：
     * 配置表
     * → 原子 Agent 配置列表
     * → 逐个决定模型代码
     * → 复用或新建 ChatModel
     * → 包一层可观测性模型
     * → 构造 LlmAgent（名称/描述/指令/输出键/工具）
     * → 写入名称索引
     * → 转给组合工作流节点</p>
     *
     * <p>不写数据库、不发起真实模型调用。配置里没有 agents 段会在这里抛空指针导致装配失败。</p>
     */
    @Override
    protected AiAgentRegisterVO doApply(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        // 打点标明进入原子 Agent 装配环节。
        log.info("Ai Agent 装配操作 - AgentNode");

        // 第一层：取出整棵配置树。
        AiAgentConfigTableVO aiAgentConfigTableVO = requestParameter.getAiAgentConfigTableVO();
        // 取出这张表声明的所有原子 Agent；它们是工作流和 Runner 的可引用素材。
        List<AiAgentConfigTableVO.Module.Agent> agents = aiAgentConfigTableVO.getModule().getAgents();

        // 逐个 Agent 装配，任何一个失败都会让整张表装配失败。
        for (AiAgentConfigTableVO.Module.Agent agentConfig : agents) {
            // 第二层：节点未声明模型时复用默认模型；显式模型只替换模型代码，不替换 API 端点。
            String agentModelName = isBlank(agentConfig.getModel()) ? dynamicContext.getChatModelName() : agentConfig.getModel();
            // 第三层：按是否显式指定模型，决定复用默认实例还是新建一个专属实例。
            ChatModel chatModel = buildAgentChatModel(dynamicContext, agentConfig, agentModelName);
            // 第四层：组装真正可执行的 LlmAgent；模型外面包一层可观测性壳，用来记录耗时和用量。
            LlmAgent llmAgent = LlmAgent.builder()
                    .name(agentConfig.getName())
                    .description(agentConfig.getDescription())
                    .model(new ObservabilitySpringAI(chatModel, agentModelName))
                    .instruction(agentConfig.getInstruction())
                    .outputKey(agentConfig.getOutputKey())
                    .tools(gatewayToolset)
                    .build();

            // 配置名称是后续工作流 subAgents 和 Runner agentName 的唯一引用键；重名会互相覆盖。
            dynamicContext.getAgentGroup().put(agentConfig.getName(), llmAgent);
        }

        // 全部原子 Agent 就绪后交给路由，进入组合工作流装配。
        return router(requestParameter, dynamicContext);
    }

    /**
     * 指定下一个装配节点：组合工作流调度节点。
     */
    @Override
    public StrategyHandler<ArmoryCommandEntity, DefaultArmoryFactory.DynamicContext, AiAgentRegisterVO> get(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        // 原子 Agent 全部就绪，进入组合工作流装配循环。
        return agentWorkflowNode;
    }

    /**
     * 决定这个 Agent 用哪个模型实例：复用默认的，还是单独建一个。
     *
     * <p>没显式配置模型时直接复用默认实例，好处是共享连接池和内部缓存，开销最小。
     * 显式配置了模型代码就必须新建实例，因为模型代码是绑定在实例的默认选项上的；
     * 但底层 HTTP 客户端仍然共用，所以只是多一个轻量包装，不会多开一套连接。</p>
     */
    private ChatModel buildAgentChatModel(DefaultArmoryFactory.DynamicContext dynamicContext,
                                          AiAgentConfigTableVO.Module.Agent agentConfig,
                                          String agentModelName) throws Exception {
        // 没写模型就复用配置表默认模型，连实例都不新建。
        if (isBlank(agentConfig.getModel())) {
            // 直接返回默认实例，共享它的连接池和内部状态。
            return dynamicContext.getChatModel();
        }

        // 需要专属模型，仍然复用同一个底层 HTTP 客户端，避免重复建立连接。
        OpenAiApi openAiApi = dynamicContext.getOpenAiApi();
        // 新建一个只改了模型代码的模型实例。
        return OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(OpenAiChatOptions.builder()
                        .model(agentModelName)
                        .build())
                .build();
    }

    /**
     * 把 null 和纯空白统一当作「没配置」。
     *
     * <p>配置文件里很容易出现 {@code model: ""} 这种写法，如果只判 null，
     * 就会拿着空字符串当模型代码去建实例，最终在对话时报模型不存在。</p>
     */
    private boolean isBlank(String value) {
        // 空值和纯空白都算未配置，让调用方走复用默认模型的分支。
        return value == null || value.isBlank();
    }

}
