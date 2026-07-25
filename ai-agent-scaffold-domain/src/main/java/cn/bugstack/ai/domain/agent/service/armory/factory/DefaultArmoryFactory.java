package cn.bugstack.ai.domain.agent.service.armory.factory;

import cn.bugstack.ai.domain.agent.model.entity.ArmoryCommandEntity;
import cn.bugstack.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import cn.bugstack.ai.domain.agent.model.valobj.AiAgentRegisterVO;
import cn.bugstack.ai.domain.agent.service.armory.node.RootNode;
import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.SequentialAgent;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** 暴露 Agent 装配责任链入口，并保存链路内共享的中间产物。 */
@Service
public class DefaultArmoryFactory {

    /** Spring 容器是最终 AiAgentRegisterVO 的运行时注册表。 */
    @Resource
    private ApplicationContext applicationContext;

    /** 所有装配从固定根节点开始。 */
    @Resource
    private RootNode rootNode;

    /** 返回无状态责任链入口；每次调用必须传入新的 DynamicContext。 */
    public StrategyHandler<ArmoryCommandEntity, DynamicContext, AiAgentRegisterVO> armoryStrategyHandler() {
        return rootNode;
    }

    /** 按 agentId 读取已经完成装配并注册的运行体。 */
    public AiAgentRegisterVO getAiAgentRegisterVO(String agentId) {
        return applicationContext.getBean(agentId, AiAgentRegisterVO.class);
    }

    /** 单张配置表的装配上下文；只在一次责任链执行期间共享。 */
    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class DynamicContext {

        /** 由 API 节点构造，供模型节点复用的底层客户端。 */
        private OpenAiApi openAiApi;

        /** 配置表默认模型；未显式指定模型的原子 Agent 复用它。 */
        private ChatModel chatModel;

        /** 默认模型代码，用于观测记录和节点模型回退。 */
        private String chatModelName;

        /** 兼容字段；工具统一由 GatewayToolset 分发，因此装配时保持为空。 */
        @Builder.Default
        private List<ToolCallback> toolCallbacks = new ArrayList<>();

        /** 按配置名称保存原子 Agent 和组合 Agent，供工作流及 Runner 引用。 */
        /** 当前待装配的组合 Agent 下标。 */
        @Builder.Default
        private Map<String, BaseAgent> agentGroup = new HashMap<>();

        @Builder.Default
        private AtomicInteger currentStepIndex = new AtomicInteger(0);

        /** 当前工作流配置；为空表示组合 Agent 已全部装配。 */
        private AiAgentConfigTableVO.Module.AgentWorkflow currentAgentWorkflow;

        /** 少量跨节点扩展数据；键必须由装配链双方约定。 */
        @Builder.Default
        private Map<String, Object> dataObjects = new HashMap<>();

        /** 写入责任链扩展值。 */
        public <T> void setValue(String key, T value) {
            dataObjects.put(key, value);
        }

        /** 读取责任链扩展值；调用方负责匹配写入类型。 */
        public <T> T getValue(String key) {
            return (T) dataObjects.get(key);
        }

        /** 按声明顺序解析已装配子 Agent；缺失名称不会生成空占位。 */
        public List<BaseAgent> queryAgentList(List<String> agentNames) {
            if (agentNames == null || agentNames.isEmpty() || agentGroup == null) {
                return Collections.emptyList();
            }

            List<BaseAgent> agents = new ArrayList<>();
            for (String name : agentNames) {
                BaseAgent agent = agentGroup.get(name);
                if (agent != null) {
                    agents.add(agent);
                }
            }

            return agents;
        }

        /** 推进到下一条组合 Agent 配置。 */
        public void addCurrentStepIndex() {
            currentStepIndex.incrementAndGet();
        }

        /** 返回当前组合 Agent 配置下标。 */
        public int getCurrentStepIndex() {
            return currentStepIndex.get();
        }

    }

}
