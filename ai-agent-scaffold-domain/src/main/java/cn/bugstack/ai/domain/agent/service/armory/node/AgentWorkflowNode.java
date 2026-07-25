package cn.bugstack.ai.domain.agent.service.armory.node;

import cn.bugstack.ai.domain.agent.model.entity.ArmoryCommandEntity;
import cn.bugstack.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import cn.bugstack.ai.domain.agent.model.valobj.AiAgentRegisterVO;
import cn.bugstack.ai.domain.agent.model.valobj.enums.AgentTypeEnum;
import cn.bugstack.ai.domain.agent.service.armory.AbstractArmorySupport;
import cn.bugstack.ai.domain.agent.service.armory.factory.DefaultArmoryFactory;
import cn.bugstack.ai.domain.agent.service.armory.node.workflow.LoopAgentNode;
import cn.bugstack.ai.domain.agent.service.armory.node.workflow.ParallelAgentNode;
import cn.bugstack.ai.domain.agent.service.armory.node.workflow.SequentialAgentNode;
import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;

/** 逐条读取组合 Agent 配置，并按类型路由到对应构造节点。 */
@Slf4j
@Service
public class AgentWorkflowNode extends AbstractArmorySupport {

    /** 构造有限循环组合 Agent。 */
    @Resource
    private LoopAgentNode loopAgentNode;
    /** 构造并行组合 Agent。 */
    @Resource
    private ParallelAgentNode parallelAgentNode;
    /** 构造顺序组合 Agent。 */
    @Resource
    private SequentialAgentNode sequentialAgentNode;
    /** 所有组合 Agent 完成后构造最终 Runner。 */
    @Resource
    private RunnerNode runnerNode;

    @Override
    protected AiAgentRegisterVO doApply(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        log.info("Ai Agent 装配操作 - AgentWorkflowNode");

        AiAgentConfigTableVO aiAgentConfigTableVO = requestParameter.getAiAgentConfigTableVO();
        List<AiAgentConfigTableVO.Module.AgentWorkflow> agentWorkflows = aiAgentConfigTableVO.getModule().getAgentWorkflows();

        if (null == agentWorkflows || agentWorkflows.isEmpty() || dynamicContext.getCurrentStepIndex() >= agentWorkflows.size()) {
            // null 是“组合配置耗尽”的显式哨兵，get() 据此转向 Runner。
            dynamicContext.setCurrentAgentWorkflow(null);
            return router(requestParameter, dynamicContext);
        }

        // 本轮固定一条配置；具体节点完成后会回到本节点继续下一条。
        dynamicContext.setCurrentAgentWorkflow(agentWorkflows.get(dynamicContext.getCurrentStepIndex()));

        // 先推进下标，回环时直接读取下一条配置。
        dynamicContext.addCurrentStepIndex();

        return router(requestParameter, dynamicContext);
    }

    @Override
    public StrategyHandler<ArmoryCommandEntity, DefaultArmoryFactory.DynamicContext, AiAgentRegisterVO> get(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {

        AiAgentConfigTableVO.Module.AgentWorkflow currentAgentWorkflow = dynamicContext.getCurrentAgentWorkflow();

        if (null == currentAgentWorkflow){
            // 所有原子和组合 Agent 均已进入 agentGroup，开始绑定 Runner。
            return runnerNode;
        }

        String type = currentAgentWorkflow.getType();
        AgentTypeEnum agentTypeEnum = AgentTypeEnum.formType(type);

        if (null == agentTypeEnum){
            // 未知类型不能静默退化为普通 Runner，否则会改变工作流语义。
            throw new RuntimeException("agentWorkflow type is error!");
        }

        String node = agentTypeEnum.getNode();

        // 枚举中的节点名是配置类型到 Spring 装配节点的唯一映射。
        return switch (node){
            case "loopAgentNode" -> loopAgentNode;
            case "parallelAgentNode" -> parallelAgentNode;
            case "sequentialAgentNode" -> sequentialAgentNode;
            default -> runnerNode;
        };
    }

}
