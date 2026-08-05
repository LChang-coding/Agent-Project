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

/**
 * 装配第四层的调度器：一条一条地把组合工作流配置分发给对应的构造节点。
 *
 * <p>解决什么问题：组合工作流可能有多条，而且类型不同（循环/并行/串行）。这里用「取一条 → 交给具体节点 →
 * 具体节点装完回到这里 → 再取下一条」的绕圈方式逐条消费，直到配置用完才转向 Runner。
 * 好处是不用在一个节点里写三种构造逻辑，新增编排类型只要加一个节点和一个枚举值。</p>
 *
 * <p>所属层次：领域层的装配节点（调度型节点）。</p>
 *
 * <p>谁会调用它：{@code AgentNode} 装配完原子 Agent 后转给它；三个具体的工作流节点装配完也会回到它。</p>
 *
 * <p>它向下调用什么：按类型转给 LoopAgentNode / ParallelAgentNode / SequentialAgentNode，
 * 配置消费完则转给 {@code RunnerNode}。</p>
 *
 * <p>它不负责什么：自己不构造任何组合 Agent，也不校验子 Agent 名字是否存在。</p>
 */
@Slf4j
@Service
public class AgentWorkflowNode extends AbstractArmorySupport {

    /**
     * 构造「反复执行同一批子 Agent」的组合节点，迭代次数受配置和硬上限约束。
     */
    @Resource
    private LoopAgentNode loopAgentNode;
    /**
     * 构造「多个子 Agent 同时跑」的组合节点。
     */
    @Resource
    private ParallelAgentNode parallelAgentNode;
    /**
     * 构造「子 Agent 按声明顺序依次跑」的组合节点。
     */
    @Resource
    private SequentialAgentNode sequentialAgentNode;
    /**
     * 装配链终点：所有组合配置消费完后由它建 Runner 并注册运行体。
     */
    @Resource
    private RunnerNode runnerNode;

    /**
     * 从配置里取出「本轮要装配的那一条」组合工作流，并把进度往前推一格。
     *
     * <p>各层职责：
     * 第一层：判断组合配置是否已经耗尽（没有配置、空列表，或进度已追上总数）。
     * 第二层：耗尽时把当前配置置空——这是给路由看的哨兵，表示可以去建 Runner 了。
     * 第三层：没耗尽就按进度取出一条放进上下文，供具体类型节点读取。
     * 第四层：立刻把进度加一，这样具体节点装完回到这里时读到的是下一条，不会死循环。</p>
     *
     * <p>数据流：
     * 配置表 + 当前进度
     * → 判断是否耗尽
     * → 耗尽：当前配置置空 → 路由转向 Runner
     * → 未耗尽：取出当前配置 → 进度加一 → 路由按类型转向具体构造节点</p>
     *
     * <p>不写数据库、不创建对象，只改共享上下文里的两个字段。
     * 「先推进度再交给具体节点」的顺序是关键：反过来写会导致同一条配置被反复装配。</p>
     */
    @Override
    protected AiAgentRegisterVO doApply(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        // 打点标明进入组合工作流调度环节；一张表有几条组合配置就会打印几次。
        log.info("Ai Agent 装配操作 - AgentWorkflowNode");

        // 取出整棵配置树。
        AiAgentConfigTableVO aiAgentConfigTableVO = requestParameter.getAiAgentConfigTableVO();
        // 取出组合工作流配置列表；很多配置表没有编排需求，这里可能是空的。
        List<AiAgentConfigTableVO.Module.AgentWorkflow> agentWorkflows = aiAgentConfigTableVO.getModule().getAgentWorkflows();

        // 第一层：没有配置、空列表，或进度已经追上总数，三种情况都说明组合配置已经消费完。
        if (null == agentWorkflows || agentWorkflows.isEmpty() || dynamicContext.getCurrentStepIndex() >= agentWorkflows.size()) {
            // 第二层：null 是"组合配置耗尽"的显式哨兵，get() 据此转向 Runner。
            dynamicContext.setCurrentAgentWorkflow(null);
            // 交给路由，此时会走到 Runner 节点结束装配。
            return router(requestParameter, dynamicContext);
        }

        // 第三层：本轮固定一条配置；具体节点完成后会回到本节点继续下一条。
        dynamicContext.setCurrentAgentWorkflow(agentWorkflows.get(dynamicContext.getCurrentStepIndex()));

        // 第四层：先推进下标，回环时直接读取下一条配置；顺序反了会反复装配同一条。
        dynamicContext.addCurrentStepIndex();

        // 交给路由，按当前配置的类型转到对应的构造节点。
        return router(requestParameter, dynamicContext);
    }

    /**
     * 按当前配置的编排类型选出下一个装配节点。
     *
     * <p>各层职责：
     * 第一层：当前配置为空说明组合配置已耗尽，转向 Runner 结束装配。
     * 第二层：把配置里的类型字符串解析成枚举，无法识别就直接抛异常。
     * 第三层：按枚举里记录的 Bean 名选出具体的构造节点。</p>
     *
     * <p>数据流：共享上下文里的当前配置 → 判空 → 类型字符串 → 枚举 → 装配节点 Bean 名 → 具体构造节点。</p>
     *
     * <p>为什么未知类型要抛异常而不是退化成 Runner：静默退化会让配置里写错类型的工作流「消失」，
     * 系统照常启动但行为和预期完全不同，这种问题极难排查。宁可启动失败。</p>
     */
    @Override
    public StrategyHandler<ArmoryCommandEntity, DefaultArmoryFactory.DynamicContext, AiAgentRegisterVO> get(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {

        // 读出调度节点刚放进来的那条配置；为空表示已经没有配置要处理。
        AiAgentConfigTableVO.Module.AgentWorkflow currentAgentWorkflow = dynamicContext.getCurrentAgentWorkflow();

        // 第一层：哨兵命中，组合装配结束。
        if (null == currentAgentWorkflow){
            // 所有原子和组合 Agent 均已进入 agentGroup，开始绑定 Runner。
            return runnerNode;
        }

        // 第二层：取出配置里写的编排类型字符串。
        String type = currentAgentWorkflow.getType();
        // 解析成枚举，忽略大小写；无法识别会返回空。
        AgentTypeEnum agentTypeEnum = AgentTypeEnum.formType(type);

        // 类型无法识别，必须让装配失败而不是继续往下走。
        if (null == agentTypeEnum){
            // 未知类型不能静默退化为普通 Runner，否则会改变工作流语义。
            throw new RuntimeException("agentWorkflow type is error!");
        }

        // 第三层：从枚举里取出负责装配这种类型的 Bean 名。
        String node = agentTypeEnum.getNode();

        // 枚举中的节点名是配置类型到 Spring 装配节点的唯一映射。
        return switch (node){
            // 循环编排交给循环节点。
            case "loopAgentNode" -> loopAgentNode;
            // 并行编排交给并行节点。
            case "parallelAgentNode" -> parallelAgentNode;
            // 串行编排交给顺序节点。
            case "sequentialAgentNode" -> sequentialAgentNode;
            // 枚举里出现了没有对应节点的 Bean 名（属于代码与枚举不同步），兜底走 Runner 结束装配。
            default -> runnerNode;
        };
    }

}
