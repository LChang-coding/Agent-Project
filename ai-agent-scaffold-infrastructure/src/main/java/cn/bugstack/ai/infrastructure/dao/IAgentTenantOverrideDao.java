package cn.bugstack.ai.infrastructure.dao;

import cn.bugstack.ai.infrastructure.dao.po.AgentTenantOverridePO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** 租户 Agent 状态覆盖 DAO。 */
@Mapper
public interface IAgentTenantOverrideDao {
    /** 查询覆盖；参数是租户和 Agent；返回覆盖记录。 */
    AgentTenantOverridePO query(@Param("tenantId") String tenantId, @Param("agentId") String agentId);
    /** 查询租户覆盖；参数是租户；返回覆盖列表。 */
    List<AgentTenantOverridePO> queryList(@Param("tenantId") String tenantId);
    /** 新增覆盖；参数是覆盖记录；返回影响行数。 */
    int insert(AgentTenantOverridePO value);
    /** 乐观锁更新；参数是覆盖记录和预期版本；返回影响行数。 */
    int update(@Param("value") AgentTenantOverridePO value, @Param("expectedRevision") long expectedRevision);
}
