package cn.bugstack.ai.infrastructure.dao;

import cn.bugstack.ai.infrastructure.dao.po.McpServerConfigPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * MCP 服务配置 DAO。
 * <p>负责 `mcp_server_config` 表的基础持久化操作。</p>
 */
@Mapper
public interface IMcpServerConfigDao {

    /**
     * 新增MCP 服务配置记录。
     *
     * @param mcpServerConfig MCP 服务配置持久化对象
     * @return 影响行数
     */
    int insert(McpServerConfigPO mcpServerConfig);

    /**
     * 按主键更新MCP 服务配置记录。
     *
     * @param mcpServerConfig MCP 服务配置持久化对象
     * @return 影响行数
     */
    int updateById(McpServerConfigPO mcpServerConfig);

    /**
     * 按主键查询MCP 服务配置记录。
     *
     * @param id 主键ID
     * @return MCP 服务配置持久化对象
     */
    McpServerConfigPO queryById(@Param("id") Long id);

    /**
     * 按MCP 配置业务ID查询MCP 服务配置记录。
     *
     * @param mcpId MCP 配置业务ID
     * @return MCP 服务配置持久化对象
     */
    McpServerConfigPO queryByMcpId(@Param("mcpId") String mcpId);

    /**
     * 按租户业务ID查询MCP 服务配置列表。
     *
     * @param tenantId 租户业务ID
     * @return MCP 服务配置持久化对象列表
     */
    List<McpServerConfigPO> queryListByTenantId(@Param("tenantId") String tenantId);

    /**
     * 按拥有者用户ID查询MCP 服务配置列表。
     *
     * @param ownerUserId 拥有者用户ID
     * @return MCP 服务配置持久化对象列表
     */
    List<McpServerConfigPO> queryListByOwnerUserId(@Param("ownerUserId") String ownerUserId);

    /** 在租户边界内查询所有者的 MCP 定义。 */
    List<McpServerConfigPO> queryListByTenantIdAndOwnerUserId(@Param("tenantId") String tenantId,
                                                               @Param("ownerUserId") String ownerUserId);

    /**
     * 按租户和可见范围查询MCP 服务配置列表。
     *
     * @param tenantId 租户业务ID
     * @param visibility 可见范围：private/tenant_public
     * @return MCP 服务配置持久化对象列表
     */
    List<McpServerConfigPO> queryListByTenantIdAndVisibility(@Param("tenantId") String tenantId, @Param("visibility") String visibility);
}
