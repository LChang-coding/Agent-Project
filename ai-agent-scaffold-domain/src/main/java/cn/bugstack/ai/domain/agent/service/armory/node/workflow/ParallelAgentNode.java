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

/**
 * 把一条「并行编排」配置构造成 ADK 的 ParallelAgent。
 *
 * <p>解决什么问题：有些子任务彼此不依赖，比如同时从三个角度分析同一份材料。并行编排让它们一起跑，
 * 总耗时取决于最慢的那个，而不是几个耗时相加。</p>
 *
 * <p>所属层次：领域层的装配节点（组合工作流构造节点）。</p>
 *
 * <p>谁会调用它：{@code AgentWorkflowNode} 在当前配置类型是 parallel 时转给它。</p>
 *
 * <p>它向下调用什么：从共享上下文按名字取子 Agent，构造完写回名称索引，然后回到调度节点。</p>
 *
 * <p>它不负责什么：不控制并发度、不处理子 Agent 之间的数据传递。
 * 并行的子 Agent 互相看不到对方的输出，需要串起来请用顺序编排。</p>
 */
@Slf4j
@Service("parallelAgentNode")
public class ParallelAgentNode extends AbstractArmorySupport {

    /**
     * 构造并行组合 Agent，并登记回名称索引。
     *
     * <p>数据流：共享上下文里的当前配置 → 取子 Agent 名字列表 → 解析成实例
     * → 构造 ParallelAgent（名称/描述/子 Agent）→ 写回名称索引 → 回到调度节点。</p>
     *
     * <p>不写数据库、不调用模型。注意配置里的列表顺序在这里只是声明顺序，
     * 运行时由 ADK 决定实际调度，不能依赖它来表达先后关系。</p>
     */
    @Override
    protected AiAgentRegisterVO doApply(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        // 打点标明正在装配哪种组合类型。
        log.info("Ai Agent 装配操作 - ParallelAgentNode");

        // 取出调度节点放进来的当前配置。
        AiAgentConfigTableVO.Module.AgentWorkflow currentAgentWorkflow = dynamicContext.getCurrentAgentWorkflow();

        // 配置顺序只决定声明顺序，运行时由 ADK 并发调度子 Agent。
        List<String> subAgentNames = currentAgentWorkflow.getSubAgents();
        // 按名字解析成真实实例，解析不到的名字会被静默跳过。
        List<BaseAgent> subAgents = dynamicContext.queryAgentList(subAgentNames);

        // 构造并行 Agent；没有迭代次数概念，每个子 Agent 各跑一次。
        ParallelAgent parallelAgent =
                ParallelAgent.builder()
                        .name(currentAgentWorkflow.getName())
                        .description(currentAgentWorkflow.getDescription())
                        .subAgents(subAgents)
                        .build();

        // 保存组合结果，允许后续组合 Agent 或 Runner 按名称引用。
        dynamicContext.getAgentGroup().put(currentAgentWorkflow.getName(), parallelAgent);

        // 交给路由，回到调度节点继续消费下一条组合配置。
        return router(requestParameter, dynamicContext);
    }

    /**
     * 指定下一个节点：回到组合工作流调度节点。
     *
     * <p>按名取 Bean 是为了避开与调度节点的循环依赖。</p>
     */
    @Override
    public StrategyHandler<ArmoryCommandEntity, DefaultArmoryFactory.DynamicContext, AiAgentRegisterVO> get(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        // 回环继续消费剩余组合配置。
        return getBean("agentWorkflowNode");
    }
}
