package cn.bugstack.ai.domain.agent.service.armory.node;

import cn.bugstack.ai.domain.agent.model.entity.ArmoryCommandEntity;
import cn.bugstack.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import cn.bugstack.ai.domain.agent.model.valobj.AiAgentRegisterVO;
import cn.bugstack.ai.domain.agent.service.armory.AbstractArmorySupport;
import cn.bugstack.ai.domain.agent.service.armory.factory.DefaultArmoryFactory;
import cn.bugstack.ai.domain.agent.service.armory.matter.model.reasoning.ReasoningAdapterFactory;
import cn.bugstack.ai.domain.agent.service.armory.matter.model.reasoning.ReasoningAwareOpenAiApi;
import cn.bugstack.ai.domain.agent.service.armory.matter.model.reasoning.ReasoningMode;
import cn.bugstack.ai.domain.agent.service.armory.matter.model.reasoning.ReasoningModelAdapter;
import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

import javax.annotation.Resource;

/**
 * 装配第一层：把配置里的模型服务接入信息造成一个所有模型共用的 HTTP 客户端。
 *
 * <p>解决什么问题：一张配置表里可能有多个模型和多个 Agent，但它们连的是同一个模型服务。
 * 把客户端建成一份共享的，连接池就只有一套，不会因为 Agent 多而把连接数打满。</p>
 *
 * <p>所属层次：领域层的装配节点（责任链第一个实际节点）。</p>
 *
 * <p>谁会调用它：{@code RootNode} 固定转给它。</p>
 *
 * <p>它向下调用什么：装配完把客户端写进共享上下文，然后转给 {@code ChatModelNode} 建默认模型。</p>
 *
 * <p>它不负责什么：不校验地址能不能连通、不校验密钥是否有效。配置写错的表现是对话时报连接或鉴权失败，
 * 而不是启动就失败。</p>
 */
@Slf4j
@Service
public class  AiApiNode extends AbstractArmorySupport {
    /**
     * 下一个装配节点：用本节点造好的客户端构造默认聊天模型。
     */
    @Resource
    private ChatModelNode chatModelNode;

    @Value("${ai.agent.thinking.mode:medium}")
    private String thinkingMode;

    @Value("${ai.agent.thinking.fallback-disabled:true}")
    private boolean fallbackDisabled;
    /**
     * 构造 OpenAI 兼容的 API 客户端，并写入本次装配的共享上下文。
     *
     * <p>各层职责：
     * 第一层：从配置表里取出这张表唯一的模型服务接入信息。
     * 第二层：给 HTTP 客户端装一个请求拦截器，专门处理「思考字段」这个兼容性问题。
     * 第三层：用地址、密钥和路径构造客户端，路径缺失时回退到 OpenAI 标准路径。
     * 第四层：把客户端放进共享上下文，后续节点只能从这里取，不会各自新建。</p>
     *
     * <p>数据流：
     * 配置表
     * → 取出模型服务接入信息
     * → 构造带拦截器的 HTTP 客户端构建器
     * → 构造 OpenAI 兼容客户端
     * → 写入共享上下文
     * → 转给下一个装配节点</p>
     *
     * <p>不写数据库、不发起任何真实请求。配置里缺 module 或 aiApi 会在这里抛空指针，
     * 装配随之失败，该配置表的 Agent 都不会被注册。</p>
     */
    @Override
    protected AiAgentRegisterVO doApply(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        // 打点标明进入哪个装配环节，启动日志里能顺序看到整条链走到了哪一步。
        log.info("Ai Agent 装配操作 - AiApiNode");
        // 第一层：取出本次要装配的整棵配置树。
        AiAgentConfigTableVO aiAgentConfigTableVO = requestParameter.getAiAgentConfigTableVO();
        // 每张配置表只声明一个基础 API，节点级模型可共享连接配置。
        AiAgentConfigTableVO.Module.AiApi aiApiConfig = aiAgentConfigTableVO.getModule().getAiApi();

        // 第二层：由独立模型适配层统一处理思考协议，不再在 HTTP 拦截器里硬编码关闭。
        ObjectMapper objectMapper = new ObjectMapper();
        RestClient.Builder restClientBuilder = RestClient.builder();
        WebClient.Builder webClientBuilder = WebClient.builder();

        // 第三层：用配置里的地址、密钥和路径构造客户端。
        // 未配置路径时使用 OpenAI 兼容默认值。
        String model = aiAgentConfigTableVO.getModule().getChatModel().getModel();
        ReasoningMode mode = ReasoningMode.resolve(thinkingMode);
        ReasoningModelAdapter adapter = ReasoningAdapterFactory.resolve(aiApiConfig.getBaseUrl(), model, mode);
        String completionsPath = StringUtils.isNotBlank(aiApiConfig.getCompletionsPath())
                ? aiApiConfig.getCompletionsPath() : "v1/chat/completions";
        String embeddingsPath = StringUtils.isNotBlank(aiApiConfig.getEmbeddingsPath())
                ? aiApiConfig.getEmbeddingsPath() : "v1/embeddings";
        OpenAiApi openAiApi = new ReasoningAwareOpenAiApi(aiApiConfig.getBaseUrl(), aiApiConfig.getApiKey(),
                completionsPath, embeddingsPath, restClientBuilder, webClientBuilder, objectMapper,
                adapter, mode, fallbackDisabled);
        log.info("Ai Agent 思考适配器已装配 provider:{} mode:{} model:{}", adapter.provider(), mode, model);

        // 第四层：后续节点只能从本次 DynamicContext 取得该客户端，保证一张表只有一份连接池。
        dynamicContext.setOpenAiApi(openAiApi);

        // 交给路由进入下一个装配节点。
        return router(requestParameter, dynamicContext);
    }


    /**
     * 指定下一个装配节点：默认聊天模型。
     *
     * <p>顺序固定：客户端已经就绪，接下来必须先有模型，Agent 才能引用它。</p>
     */
    @Override
    public StrategyHandler<ArmoryCommandEntity, DefaultArmoryFactory.DynamicContext, AiAgentRegisterVO> get(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {

        // API 配置完成后必须创建默认 ChatModel，后续 Agent 才有模型可用。
        return chatModelNode;
    }

}
