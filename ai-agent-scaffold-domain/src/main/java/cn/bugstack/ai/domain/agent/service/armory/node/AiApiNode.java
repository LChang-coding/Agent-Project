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

@Slf4j
@Service
public class  AiApiNode extends AbstractArmorySupport {
    @Resource
    private ChatModelNode chatModelNode;
    @Override
    protected AiAgentRegisterVO doApply(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        log.info("Ai Agent 装配操作 - AiApiNode");
        //总的来说，这个节点的职责就是根据智能体配置表中的AI API配置，构建一个OpenAiApi对象，并将其放入上下文中，以供后续节点使用。最后通过router方法继续路由到下一个节点。
        AiAgentConfigTableVO aiAgentConfigTableVO = requestParameter.getAiAgentConfigTableVO();
        AiAgentConfigTableVO.Module.AiApi aiApiConfig = aiAgentConfigTableVO.getModule().getAiApi();

        // DeepSeek 默认启用思考模式，需禁用避免 reasoning_content 错误
        ObjectMapper objectMapper = new ObjectMapper();
        RestClient.Builder restClientBuilder = RestClient.builder()
            .requestInterceptor((request, body, execution) -> {
                String path = request.getURI().getPath();
                if (body != null && body.length > 0 && path != null && path.contains("/chat/completions")) {
                    try {
                        JsonNode rootNode = objectMapper.readTree(body);
                        ObjectNode thinking = objectMapper.createObjectNode();
                        thinking.put("type", "disabled");
                        ((ObjectNode) rootNode).set("thinking", thinking);
                        body = objectMapper.writeValueAsBytes(rootNode);
                    } catch (Exception e) {
                        log.warn("请求体添加 thinking 参数失败", e);
                    }
                }
                return execution.execute(request, body);
            });

        OpenAiApi openAiApi = OpenAiApi.builder()
                .baseUrl(aiApiConfig.getBaseUrl())
                .apiKey(aiApiConfig.getApiKey())
                .completionsPath(StringUtils.isNotBlank(aiApiConfig.getCompletionsPath()) ? aiApiConfig.getCompletionsPath() : "v1/chat/completions")
                .embeddingsPath(StringUtils.isNotBlank(aiApiConfig.getEmbeddingsPath()) ? aiApiConfig.getEmbeddingsPath() : "v1/embeddings")
                .restClientBuilder(restClientBuilder)
                .build();

        dynamicContext.setOpenAiApi(openAiApi);

        return router(requestParameter, dynamicContext);
    }


    @Override
    public StrategyHandler<ArmoryCommandEntity, DefaultArmoryFactory.DynamicContext, AiAgentRegisterVO> get(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {

        // 如果不需要下一个节点了，可以配置 defaultStrategyHandler
        return chatModelNode;
    }

}
