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

/** 将配置表中的 OpenAI 兼容端点构造成后续模型共享的 API 客户端。 */
@Slf4j
@Service
public class  AiApiNode extends AbstractArmorySupport {
    /** API 客户端完成后进入默认聊天模型装配。 */
    @Resource
    private ChatModelNode chatModelNode;
    @Override
    protected AiAgentRegisterVO doApply(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        log.info("Ai Agent 装配操作 - AiApiNode");
        // 每张配置表只声明一个基础 API，节点级模型可共享连接配置。
        AiAgentConfigTableVO aiAgentConfigTableVO = requestParameter.getAiAgentConfigTableVO();
        AiAgentConfigTableVO.Module.AiApi aiApiConfig = aiAgentConfigTableVO.getModule().getAiApi();

        // 对聊天补全请求显式关闭思考字段，避免兼容端点返回框架无法消费的 reasoning_content。
        ObjectMapper objectMapper = new ObjectMapper();
        RestClient.Builder restClientBuilder = RestClient.builder()
            .requestInterceptor((request, body, execution) -> {
                String path = request.getURI().getPath();
                if (body != null && body.length > 0 && path != null && path.contains("/chat/completions")) {
                    // 只重写 JSON 聊天请求；嵌入请求和空请求体保持原样。
                    try {
                        JsonNode rootNode = objectMapper.readTree(body);
                        ObjectNode thinking = objectMapper.createObjectNode();
                        thinking.put("type", "disabled");
                        ((ObjectNode) rootNode).set("thinking", thinking);
                        body = objectMapper.writeValueAsBytes(rootNode);
                    } catch (Exception e) {
                        // 重写失败不阻断原始请求，兼容不接受该扩展字段的供应商。
                        log.warn("请求体添加 thinking 参数失败", e);
                    }
                }
                return execution.execute(request, body);
            });

        // 未配置路径时使用 OpenAI 兼容默认值。
        OpenAiApi openAiApi = OpenAiApi.builder()
                .baseUrl(aiApiConfig.getBaseUrl())
                .apiKey(aiApiConfig.getApiKey())
                .completionsPath(StringUtils.isNotBlank(aiApiConfig.getCompletionsPath()) ? aiApiConfig.getCompletionsPath() : "v1/chat/completions")
                .embeddingsPath(StringUtils.isNotBlank(aiApiConfig.getEmbeddingsPath()) ? aiApiConfig.getEmbeddingsPath() : "v1/embeddings")
                .restClientBuilder(restClientBuilder)
                .build();

        // 后续节点只能从本次 DynamicContext 取得该客户端。
        dynamicContext.setOpenAiApi(openAiApi);

        return router(requestParameter, dynamicContext);
    }


    @Override
    public StrategyHandler<ArmoryCommandEntity, DefaultArmoryFactory.DynamicContext, AiAgentRegisterVO> get(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {

        // API 配置完成后必须创建默认 ChatModel。
        return chatModelNode;
    }

}
