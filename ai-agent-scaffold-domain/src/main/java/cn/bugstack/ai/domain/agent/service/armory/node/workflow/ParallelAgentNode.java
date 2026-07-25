package cn.bugstack.ai.domain.agent.service.armory.node.workflow;

import cn.bugstack.ai.domain.agent.model.entity.ArmoryCommandEntity;
import cn.bugstack.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import cn.bugstack.ai.domain.agent.model.valobj.AiAgentRegisterVO;
import cn.bugstack.ai.domain.agent.model.valobj.enums.AgentTypeEnum;
import cn.bugstack.ai.domain.agent.service.armory.AbstractArmorySupport;
import cn.bugstack.ai.domain.agent.service.armory.factory.DefaultArmoryFactory;
import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.ParallelAgent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/** 将当前组合配置构造成 ADK 并行 Agent。 */
@Slf4j
@Service("parallelAgentNode")
public class ParallelAgentNode extends AbstractArmorySupport {

    @Override
    protected AiAgentRegisterVO doApply(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        log.info("Ai Agent 装配操作 - ParallelAgentNode");

        AiAgentConfigTableVO.Module.AgentWorkflow currentAgentWorkflow = dynamicContext.getCurrentAgentWorkflow();

        // 配置顺序只决定声明顺序，运行时由 ADK 并发调度子 Agent。
        List<String> subAgentNames = currentAgentWorkflow.getSubAgents();
        List<BaseAgent> subAgents = dynamicContext.queryAgentList(subAgentNames);

        ParallelAgent parallelAgent =
                ParallelAgent.builder()
                        .name(currentAgentWorkflow.getName())
                        .description(currentAgentWorkflow.getDescription())
                        .subAgents(subAgents)
                        .build();

        // 保存组合结果，允许后续组合 Agent 或 Runner 按名称引用。
        dynamicContext.getAgentGroup().put(currentAgentWorkflow.getName(), parallelAgent);

        return router(requestParameter, dynamicContext);
    }

    @Override
    public StrategyHandler<ArmoryCommandEntity, DefaultArmoryFactory.DynamicContext, AiAgentRegisterVO> get(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        // 回环继续消费剩余组合配置。
        return getBean("agentWorkflowNode");
    }
}
