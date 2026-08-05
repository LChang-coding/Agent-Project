package cn.bugstack.ai.domain.agent.service.armory.node.workflow;

import cn.bugstack.ai.domain.agent.model.entity.ArmoryCommandEntity;
import cn.bugstack.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import cn.bugstack.ai.domain.agent.model.valobj.AiAgentRegisterVO;
import cn.bugstack.ai.domain.agent.service.armory.AbstractArmorySupport;
import cn.bugstack.ai.domain.agent.service.armory.factory.DefaultArmoryFactory;
import cn.bugstack.ai.domain.agent.service.armory.node.RunnerNode;
import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.ParallelAgent;
import com.google.adk.agents.SequentialAgent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;

/**
 * 把一条「串行编排」配置构造成 ADK 的 SequentialAgent。
 *
 * <p>解决什么问题：多数任务是有先后的，比如先检索资料、再写初稿、最后润色。串行编排让子 Agent
 * 按配置声明的顺序依次执行，前一个的产出可以通过会话状态传给后一个。</p>
 *
 * <p>所属层次：领域层的装配节点（组合工作流构造节点）。</p>
 *
 * <p>谁会调用它：{@code AgentWorkflowNode} 在当前配置类型是 sequential 时转给它。</p>
 *
 * <p>它向下调用什么：从共享上下文按名字取子 Agent（严格保持声明顺序），构造完写回名称索引，
 * 然后回到调度节点。</p>
 *
 * <p>它不负责什么：不检查上下游之间的 outputKey 是否对得上。前一个 Agent 没写 outputKey，
 * 后一个就读不到它的结果，但装配阶段不会报错。</p>
 */
@Slf4j
@Service("sequentialAgentNode")
public class SequentialAgentNode extends AbstractArmorySupport {

    /**
     * 历史遗留的依赖，当前没有被使用。
     *
     * <p>本节点装配完统一回到调度节点，不会直接跳到 Runner；保留这个字段只是没有清理，
     * 它会让 Spring 在启动时多解析一次 RunnerNode，但不影响装配结果。</p>
     */
    @Resource
    private RunnerNode runnerNode;

    /**
     * 构造串行组合 Agent，并登记回名称索引。
     *
     * <p>数据流：共享上下文里的当前配置 → 取子 Agent 名字列表 → 按声明顺序解析成实例
     * → 构造 SequentialAgent（名称/描述/子 Agent）→ 写回名称索引 → 回到调度节点。</p>
     *
     * <p>不写数据库、不调用模型。这里的顺序是有语义的：解析时保持配置顺序，
     * 顺序变了整条流水线的执行次序就变了。</p>
     */
    @Override
    protected AiAgentRegisterVO doApply(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        // 打点标明正在装配哪种组合类型。
        log.info("Ai Agent 装配操作 - SequentialAgentNode");

        // 取出调度节点放进来的当前配置。
        AiAgentConfigTableVO.Module.AgentWorkflow currentAgentWorkflow = dynamicContext.getCurrentAgentWorkflow();

        // queryAgentList 保持配置顺序，顺序 Agent 据此决定执行次序。
        List<String> subAgentNames = currentAgentWorkflow.getSubAgents();
        // 按声明顺序解析成真实实例；解析不到的名字会被静默跳过，表现为少跑一个环节。
        List<BaseAgent> subAgents = dynamicContext.queryAgentList(subAgentNames);

        // 构造顺序 Agent，子 Agent 按列表顺序依次执行。
        SequentialAgent sequentialAgent =
                SequentialAgent.builder()
                        .name(currentAgentWorkflow.getName())
                        .description(currentAgentWorkflow.getDescription())
                        .subAgents(subAgents)
                        .build();

        // 保存组合结果，供更高层组合或 Runner 继续引用。
        dynamicContext.getAgentGroup().put(currentAgentWorkflow.getName(), sequentialAgent);

        // 交给路由，回到调度节点继续消费下一条组合配置。
        return router(requestParameter, dynamicContext);
    }

    /**
     * 指定下一个节点：回到组合工作流调度节点。
     *
     * <p>按名取 Bean 是为了避开与调度节点的循环依赖；注意这里不使用上面那个遗留的 runnerNode 字段。</p>
     */
    @Override
    public StrategyHandler<ArmoryCommandEntity, DefaultArmoryFactory.DynamicContext, AiAgentRegisterVO> get(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        // 回环继续装配下一条组合配置。
        return getBean("agentWorkflowNode");
    }

}
