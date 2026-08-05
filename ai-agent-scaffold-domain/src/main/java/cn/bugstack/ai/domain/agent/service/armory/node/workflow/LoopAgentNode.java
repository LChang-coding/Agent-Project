package cn.bugstack.ai.domain.agent.service.armory.node.workflow;

import cn.bugstack.ai.domain.agent.model.entity.ArmoryCommandEntity;
import cn.bugstack.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import cn.bugstack.ai.domain.agent.model.valobj.AiAgentRegisterVO;
import cn.bugstack.ai.domain.agent.model.valobj.enums.AgentTypeEnum;
import cn.bugstack.ai.domain.agent.service.armory.AbstractArmorySupport;
import cn.bugstack.ai.domain.agent.service.armory.factory.DefaultArmoryFactory;
import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.LoopAgent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 把一条「循环编排」配置构造成 ADK 的 LoopAgent。
 *
 * <p>解决什么问题：有些任务需要反复打磨，比如先写一版再自我评审修改。循环编排让同一批子 Agent
 * 按配置的次数重复执行，每轮都能看到上一轮的产出。</p>
 *
 * <p>所属层次：领域层的装配节点（组合工作流构造节点）。</p>
 *
 * <p>谁会调用它：{@code AgentWorkflowNode} 在当前配置类型是 loop 时转给它。</p>
 *
 * <p>它向下调用什么：从共享上下文按名字取子 Agent，构造完再把自己放回同一个名称索引，
 * 然后回到调度节点继续处理下一条配置。</p>
 *
 * <p>它不负责什么：不校验子 Agent 是否都解析到了（少了会静默少跑一个环节），
 * 也不给 maxIterations 兜底——次数由配置决定，配置里默认是 3 次。</p>
 */
@Slf4j
@Service("loopAgentNode")
public class LoopAgentNode extends AbstractArmorySupport {

    /**
     * 构造循环组合 Agent，并登记回名称索引。
     *
     * <p>数据流：共享上下文里的当前配置 → 取子 Agent 名字列表 → 在名称索引里解析成实例
     * → 构造 LoopAgent（名称/描述/子 Agent/最大迭代次数）→ 以自己的名字写回名称索引
     * → 回到调度节点处理下一条配置。</p>
     *
     * <p>不写数据库、不调用模型。构造出来的组合 Agent 也放进同一个索引，
     * 因此它可以再被更上层的工作流或 Runner 按名字引用，形成嵌套编排。</p>
     */
    @Override
    protected AiAgentRegisterVO doApply(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        // 打点标明正在装配哪种组合类型。
        log.info("Ai Agent 装配操作 - LoopAgentNode");

        // 取出调度节点放进来的当前配置，本节点只处理这一条。
        AiAgentConfigTableVO.Module.AgentWorkflow currentAgentWorkflow = dynamicContext.getCurrentAgentWorkflow();

        // 子 Agent 只能引用本配置表前面已经装配的名称。
        List<String> subAgentNames = currentAgentWorkflow.getSubAgents();
        // 按名字解析成真实实例；解析不到的名字会被静默跳过。
        List<BaseAgent> subAgents = dynamicContext.queryAgentList(subAgentNames);

        // 构造循环 Agent；maxIterations 是防止无限循环烧掉模型额度的唯一闸门。
        LoopAgent loopAgent =
                LoopAgent.builder()
                        .name(currentAgentWorkflow.getName())
                        .description(currentAgentWorkflow.getDescription())
                        .subAgents(subAgents)
                        .maxIterations(currentAgentWorkflow.getMaxIterations())
                        .build();

        // 组合 Agent 回写同一名称索引，可继续作为更高层工作流的子 Agent。
        dynamicContext.getAgentGroup().put(currentAgentWorkflow.getName(), loopAgent);

        // 交给路由，回到调度节点继续消费下一条组合配置。
        return router(requestParameter, dynamicContext);
    }

    /**
     * 指定下一个节点：回到组合工作流调度节点。
     *
     * <p>为什么按名字取而不是字段注入：调度节点和本节点互相引用，字段注入会形成循环依赖，
     * 运行期按名取 Bean 可以绕开这个问题。</p>
     */
    @Override
    public StrategyHandler<ArmoryCommandEntity, DefaultArmoryFactory.DynamicContext, AiAgentRegisterVO> get(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        // 回到调度节点读取下一条组合配置，而不是直接结束装配。
        return getBean("agentWorkflowNode");
    }

}
