package cn.bugstack.ai.domain.agent.service.armory.factory;

import cn.bugstack.ai.domain.agent.model.entity.ArmoryCommandEntity;
import cn.bugstack.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import cn.bugstack.ai.domain.agent.model.valobj.AiAgentRegisterVO;
import cn.bugstack.ai.domain.agent.service.armory.node.RootNode;
import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import com.google.adk.agents.BaseAgent;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 默认的装配工厂
 *
 * @author xiaofuge bugstack.cn @小傅哥
 * 2025/12/17 08:16
 */
@Service
public class DefaultArmoryFactory {

    @Resource
    private RootNode rootNode;

    public StrategyHandler<ArmoryCommandEntity, DynamicContext, AiAgentRegisterVO> armoryStrategyHandler() {
        return rootNode;
    }

    /**
     * 定义一个上下文对象，用于各个节点串联的时候，写入数据和使用数据
     */
    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class DynamicContext {

        /**
         * LLM API
         */
        private OpenAiApi openAiApi;

        /**
         * LLM ChatModel
         */
        private ChatModel chatModel;

        /**
         * 智能体配置组
         */
        private Map<String, BaseAgent> agentGroup = new HashMap<>();

        private List<AiAgentConfigTableVO.Module.AgentWorkflow> agentWorkflows = new ArrayList<>();

        private Map<String, Object> dataObjects = new HashMap<>();

        public <T> void setValue(String key, T value) {
            dataObjects.put(key, value);
        }

        public <T> T getValue(String key) {
            return (T) dataObjects.get(key);
        }

    }

}
