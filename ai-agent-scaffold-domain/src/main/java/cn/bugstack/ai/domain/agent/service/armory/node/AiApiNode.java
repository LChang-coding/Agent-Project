package cn.bugstack.ai.domain.agent.service.armory.node;

import cn.bugstack.ai.domain.agent.model.entity.ArmoryCommandEntity;
import cn.bugstack.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import cn.bugstack.ai.domain.agent.model.valobj.AiAgentRegisterVO;
import cn.bugstack.ai.domain.agent.service.armory.AbstractArmorySupport;
import cn.bugstack.ai.domain.agent.service.armory.factory.DefaultArmoryFactory;
import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

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

        // 第二层：准备 JSON 工具，用于在拦截器里改写请求体。
        // 对聊天补全请求显式关闭思考字段，避免兼容端点返回框架无法消费的 reasoning_content。
        ObjectMapper objectMapper = new ObjectMapper();
        // 给客户端装一个请求拦截器；所有走这个客户端的请求都会经过它。
        RestClient.Builder restClientBuilder = RestClient.builder()
            .requestInterceptor((request, body, execution) -> {
                // 取出请求路径，用来判断这是不是一次聊天补全调用。
                String path = request.getURI().getPath();
                // 只改写有请求体的聊天补全请求；向量化请求和空请求体都不能碰。
                if (body != null && body.length > 0 && path != null && path.contains("/chat/completions")) {
                    // 只重写 JSON 聊天请求；嵌入请求和空请求体保持原样。
                    try {
                        // 把请求体解析成 JSON 树，准备往里加字段。
                        JsonNode rootNode = objectMapper.readTree(body);
                        // 构造 thinking 节点，用来告诉服务端不要返回思考过程。
                        ObjectNode thinking = objectMapper.createObjectNode();
                        // 明确写 disabled：框架无法消费 reasoning_content，返回了反而会解析失败。
                        thinking.put("type", "disabled");
                        // 把 thinking 挂到请求体根节点上，已有同名字段会被覆盖。
                        ((ObjectNode) rootNode).set("thinking", thinking);
                        // 序列化回字节数组，作为真正发出去的请求体。
                        body = objectMapper.writeValueAsBytes(rootNode);
                    } catch (Exception e) {
                        // 重写失败不阻断原始请求，兼容不接受该扩展字段的供应商。
                        log.warn("请求体添加 thinking 参数失败", e);
                    }
                }
                // 无论有没有改写，都把请求交给后续执行链真正发出去。
                return execution.execute(request, body);
            });

        // 第三层：用配置里的地址、密钥和路径构造客户端。
        // 未配置路径时使用 OpenAI 兼容默认值。
        OpenAiApi openAiApi = OpenAiApi.builder()
                .baseUrl(aiApiConfig.getBaseUrl())
                .apiKey(aiApiConfig.getApiKey())
                .completionsPath(StringUtils.isNotBlank(aiApiConfig.getCompletionsPath()) ? aiApiConfig.getCompletionsPath() : "v1/chat/completions")
                .embeddingsPath(StringUtils.isNotBlank(aiApiConfig.getEmbeddingsPath()) ? aiApiConfig.getEmbeddingsPath() : "v1/embeddings")
                .restClientBuilder(restClientBuilder)
                .build();

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
