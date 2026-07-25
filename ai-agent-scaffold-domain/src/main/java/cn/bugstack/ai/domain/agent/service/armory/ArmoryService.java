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

/** 逐张配置表启动完整装配链；任一表失败时异常直接上抛。 */
@Slf4j
@Service
public class ArmoryService implements IArmoryService {

    /** 为每张表提供新的装配上下文和同一条责任链入口。 */
    @Resource
    private DefaultArmoryFactory defaultArmoryFactory;

    @Override
    public void acceptArmoryAgents(List<AiAgentConfigTableVO> tables) throws Exception {
        for (AiAgentConfigTableVO table : tables) {
            // 配置表之间不共享 DynamicContext，防止模型、工具和 Agent 相互串用。
            StrategyHandler<ArmoryCommandEntity, DefaultArmoryFactory.DynamicContext, AiAgentRegisterVO> handler = defaultArmoryFactory.armoryStrategyHandler();
            handler.apply(
                    ArmoryCommandEntity.builder()
                            .aiAgentConfigTableVO(table)
                            .build(),
                    new DefaultArmoryFactory.DynamicContext());
        }
    }

}
