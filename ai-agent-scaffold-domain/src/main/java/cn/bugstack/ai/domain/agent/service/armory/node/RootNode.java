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
 * 根节点
 *
 */
@Slf4j
@Service
public class RootNode extends AbstractArmorySupport {

    @Resource
    private AiApiNode aiApiNode;

    @Override
    protected AiAgentRegisterVO doApply(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {

        // 路由到下一个节点
        return router(requestParameter, dynamicContext);
    }

    @Override
    public StrategyHandler<ArmoryCommandEntity, DefaultArmoryFactory.DynamicContext, AiAgentRegisterVO> get(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        // 配置了下一个节点
        return aiApiNode;
    }

}
