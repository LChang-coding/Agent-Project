package cn.bugstack.ai.infrastructure.dao;

import cn.bugstack.ai.infrastructure.dao.po.AgentToolPermissionPO;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface IAgentToolPermissionDao {
    AgentToolPermissionPO query(@Param("tenantId") String tenantId, @Param("agentId") String agentId,
                                @Param("toolCode") String toolCode);
    List<AgentToolPermissionPO> queryByAgent(@Param("tenantId") String tenantId, @Param("agentId") String agentId);
    int insert(AgentToolPermissionPO value);
    int update(@Param("value") AgentToolPermissionPO value, @Param("expectedRevision") long expectedRevision);
}
