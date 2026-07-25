package cn.bugstack.ai.domain.agent.service;

import cn.bugstack.ai.domain.agent.model.valobj.AiAgentConfigTableVO;

import java.util.List;

/** 将配置表装配为可执行 Agent、工作流和 Runner。 */
public interface IArmoryService {

    /** 按依赖拓扑装配全部配置表；任一必需节点失败则抛出异常。 */
    void acceptArmoryAgents(List<AiAgentConfigTableVO> tables) throws Exception;

}
