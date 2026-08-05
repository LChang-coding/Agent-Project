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
/**
 * 装配第二层：用上一层造好的 HTTP 客户端构造这张配置表的默认聊天模型。
 *
 * <p>解决什么问题：大部分 Agent 都用同一个模型，没必要各建一份。这里先建一个默认模型放进上下文，
 * 只有显式指定了别的模型代码的 Agent 才会单独建。</p>
 *
 * <p>所属层次：领域层的装配节点。</p>
 *
 * <p>谁会调用它：{@code AiApiNode} 装配完客户端后转给它。</p>
 *
 * <p>它向下调用什么：把模型实例和模型代码写进共享上下文，然后转给 {@code AgentNode} 建原子 Agent。</p>
 *
 * <p>它不负责什么：不给模型挂任何工具。工具统一由网关工具集分发，模型自己挂工具就会绕过
 * 运行取消门禁，导致运行被取消后仍在调用工具。</p>
 */
@Slf4j
@Service
public class ChatModelNode extends AbstractArmorySupport {

    /**
     * 下一个装配节点：把配置里的原子 Agent 逐个建出来。
     */
    @Resource
    private AgentNode agentNode;

    /**
     * 构造默认聊天模型并写入共享上下文。
     *
     * <p>各层职责：
     * 第一层：从上下文取出上一层造好的 HTTP 客户端，它是建模型的必要前提。
     * 第二层：从配置里取出模型代码，它既下发给服务端也用作观测标签。
     * 第三层：构造模型实例，刻意不挂任何工具回调。
     * 第四层：把模型实例、模型代码写进上下文，并清空历史遗留的工具字段。</p>
     *
     * <p>数据流：
     * 共享上下文（HTTP 客户端）+ 配置表（模型代码）
     * → 构造 OpenAI 聊天模型
     * → 写入上下文（模型实例 + 模型代码）
     * → 清空工具回调字段
     * → 转给原子 Agent 装配节点</p>
     *
     * <p>不写数据库、不发起真实模型调用。上一层没跑成功时这里取到空客户端，构造模型会立即失败。</p>
     */
    @Override
    protected AiAgentRegisterVO doApply(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        // 打点标明进入模型装配环节。
        log.info("Ai Agent 装配操作 - ChatModelNode");

        // 第一层：API 节点必须先执行；缺失会在模型构造时立即失败。
        OpenAiApi openAiApi = dynamicContext.getOpenAiApi();

        // 第二层：取出配置树，从里面读模型段。
        // 默认模型代码同时用于供应商请求和观测标签。
        AiAgentConfigTableVO aiAgentConfigTableVO = requestParameter.getAiAgentConfigTableVO();
        // 拿到模型配置：模型代码以及这个模型允许使用的工具清单。
        AiAgentConfigTableVO.Module.ChatModel chatModelConfig = aiAgentConfigTableVO.getModule().getChatModel();
        // 第三层：工具由 GatewayToolset 唯一分发；模型本身不挂 ToolCallback，防止绕过运行闸门。
        ChatModel chatModel = OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(OpenAiChatOptions.builder()
                        .model(chatModelConfig.getModel())
                        .build())
                .build();

        // 第四层：把模型实例放进上下文，未指定模型的 Agent 会直接复用它。
        dynamicContext.setChatModel(chatModel);
        // 同时记下模型代码，供 Agent 回退取值和可观测性打点使用。
        dynamicContext.setChatModelName(chatModelConfig.getModel());
        // 清空兼容字段，明确本配置不允许旧式模型直连工具。
        dynamicContext.getToolCallbacks().clear();
        // 再往扩展表里放一份模型代码，供不方便直接访问上下文字段的地方读取。
        dynamicContext.setValue("chatModelName", chatModelConfig.getModel());

        // 交给路由进入原子 Agent 装配。
        return router(requestParameter, dynamicContext);
    }

    /**
     * 指定下一个装配节点：原子 Agent。
     *
     * <p>顺序固定：模型已就绪，接下来建 Agent；Agent 建完才能被工作流和 Runner 引用。</p>
     */
    @Override
    public StrategyHandler<ArmoryCommandEntity, DefaultArmoryFactory.DynamicContext, AiAgentRegisterVO> get(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        // 模型就绪后开始构造原子 Agent。
        return agentNode;
    }

}
