package cn.bugstack.ai.domain.agent.service;

import cn.bugstack.ai.domain.agent.model.valobj.AiAgentConfigTableVO;

import java.util.List;

/**
 * 装配接口
 *
 * @author xiaofuge bugstack.cn @小傅哥
 * 2025/12/17 08:13
 */
public interface IArmoryService {

    void acceptArmoryAgents(List<AiAgentConfigTableVO> tables) throws Exception;

}
