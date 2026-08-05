package cn.bugstack.ai.domain.agent.service.armory;

import cn.bugstack.ai.domain.agent.model.entity.ArmoryCommandEntity;
import cn.bugstack.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import cn.bugstack.ai.domain.agent.model.valobj.AiAgentRegisterVO;
import cn.bugstack.ai.domain.agent.service.IArmoryService;
import cn.bugstack.ai.domain.agent.service.armory.factory.DefaultArmoryFactory;
import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;

/**
 * 装配服务的实现：逐张配置表跑一遍完整装配链。
 *
 * <p>所属层次：领域层的领域服务，是装配能力的对外门面。</p>
 *
 * <p>谁会调用它：应用启动时的自动装配入口，以及需要重新加载 Agent 配置的管理操作。</p>
 *
 * <p>它向下调用什么：{@code DefaultArmoryFactory} 取责任链入口，链上依次是
 * RootNode → AiApiNode → ChatModelNode → AgentNode → AgentWorkflowNode → RunnerNode。</p>
 *
 * <p>它不负责什么：不判断配置是否合法、不创建任何具体对象、不捕获异常。
 * 某张表装配失败时异常直接上抛，让启动阶段就暴露问题，而不是留下一个「能取到却跑不通」的 Agent。</p>
 */
@Slf4j
@Service
public class ArmoryService implements IArmoryService {

    /**
     * 装配责任链的入口工厂。
     *
     * <p>每次装配都从它取链头节点，并为该次装配单独创建一份共享上下文。</p>
     */
    @Resource
    private DefaultArmoryFactory defaultArmoryFactory;

    /**
     * 把每张配置表都装配成一套可执行的 Agent 运行体。
     *
     * <p>各层职责：
     * 第一层：按表逐个处理，一张表就是一个独立的智能体应用，彼此不共享任何中间产物。
     * 第二层：为当次装配取链头节点，并新建一份只服务这张表的共享上下文。
     * 第三层：把配置表包成命令对象交给责任链，链上节点依次把 API 客户端、模型、Agent、工作流、Runner 建出来。</p>
     *
     * <p>数据流：
     * 配置表列表
     * → 取出一张表
     * → 取责任链入口 + 新建装配上下文
     * → 包装成装配命令
     * → 责任链逐节点创建对象并写入上下文
     * → RunnerNode 按 agentId 注册运行体到 Spring 容器
     * → 处理下一张表</p>
     *
     * <p>不返回值，成果全部以 Spring Bean 形式注册。异常不捕获直接上抛：装配失败必须让启动失败，
     * 否则用户对话时才会发现取不到 Runner。已经成功注册的前几张表不会回滚，需要修好配置重新装配。</p>
     */
    @Override
    public void acceptArmoryAgents(List<AiAgentConfigTableVO> tables) throws Exception {
        // 第一层：一张表一套独立的智能体应用，按顺序逐个装配。
        for (AiAgentConfigTableVO table : tables) {
            // 第二层：每张表都重新取一次链头，并在下面单独给它一份上下文；
            // 配置表之间不共享 DynamicContext，防止模型、工具和 Agent 相互串用。
            StrategyHandler<ArmoryCommandEntity, DefaultArmoryFactory.DynamicContext, AiAgentRegisterVO> handler = defaultArmoryFactory.armoryStrategyHandler();
            // 第三层：把这张表包成装配命令，配上全新的空上下文交给责任链执行；
            // 链上每个节点把自己产出的对象写进上下文，供后面的节点取用。
            handler.apply(
                    ArmoryCommandEntity.builder()
                            .aiAgentConfigTableVO(table)
                            .build(),
                    new DefaultArmoryFactory.DynamicContext());
        }
    }

}
