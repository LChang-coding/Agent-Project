package cn.bugstack.ai.domain.agent.service.armory.node;

import cn.bugstack.ai.domain.agent.model.entity.ArmoryCommandEntity;
import cn.bugstack.ai.domain.agent.model.valobj.AiAgentRegisterVO;
import cn.bugstack.ai.domain.agent.service.armory.AbstractArmorySupport;
import cn.bugstack.ai.domain.agent.service.armory.factory.DefaultArmoryFactory;
import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

/**
 * 装配责任链的起点，本身不造任何对象，只负责把请求交给第一个真正干活的节点。
 *
 * <p>解决什么问题：给整条装配链一个固定的入口。所有装配都从这里进，
 * 装配顺序在代码里一眼可见，也方便以后在入口处统一加校验或埋点。</p>
 *
 * <p>所属层次：领域层的装配节点（责任链节点）。</p>
 *
 * <p>谁会调用它：{@code DefaultArmoryFactory#armoryStrategyHandler} 把它作为链头返回。</p>
 *
 * <p>它向下调用什么：固定转给 {@code AiApiNode}，因为模型和 Agent 都依赖 API 客户端，
 * 它必须最先建好。</p>
 *
 * <p>它不负责什么：不读配置、不写上下文、不做默认值填充——刻意保持上下文原样，
 * 这样配置缺失会在真正用到它的节点处直接暴露，而不是被一个隐式默认值掩盖。</p>
 */
@Slf4j
@Service
public class RootNode extends AbstractArmorySupport {

    /**
     * 装配链的第一个实际节点：构造模型服务的 API 客户端。
     *
     * <p>它必须先于模型和 Agent 执行，否则后面拿不到客户端，模型建不出来。</p>
     */
    @Resource
    private AiApiNode aiApiNode;

    /**
     * 起点节点的处理动作：什么都不做，直接把请求交给路由继续往下走。
     *
     * <p>不返回运行体，最终的运行体由链尾的 RunnerNode 产出并沿调用栈返回。</p>
     */
    @Override
    protected AiAgentRegisterVO doApply(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {

        // 根节点保持上下文原样，避免隐式默认值掩盖配置缺失；直接交给路由进入下一节点。
        return router(requestParameter, dynamicContext);
    }

    /**
     * 指定下一个装配节点。
     *
     * <p>这里是硬编码而不是按配置选择：装配的依赖顺序是固定的，
     * 允许配置改变顺序只会带来「模型先于客户端装配」这类必然失败的组合。</p>
     */
    @Override
    public StrategyHandler<ArmoryCommandEntity, DefaultArmoryFactory.DynamicContext, AiAgentRegisterVO> get(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        // 装配顺序固定从外部 API 客户端开始，后续模型和 Agent 都依赖它。
        return aiApiNode;
    }

}
