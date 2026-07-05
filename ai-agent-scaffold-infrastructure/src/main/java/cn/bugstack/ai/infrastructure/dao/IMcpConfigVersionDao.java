package cn.bugstack.ai.infrastructure.dao;

import cn.bugstack.ai.infrastructure.dao.po.McpConfigVersionPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * MCP 配置版本 DAO。
 * <p>负责 `mcp_config_version` 表的基础持久化操作。</p>
 */
@Mapper
public interface IMcpConfigVersionDao {

    /**
     * 新增 MCP 配置版本；参数是版本持久化对象；返回影响行数。
     */
    int insert(McpConfigVersionPO mcpConfigVersion);

    /**
     * 按主键更新 MCP 配置版本；参数是版本持久化对象；返回影响行数。
     */
    int updateById(McpConfigVersionPO mcpConfigVersion);

    /**
     * 按版本业务ID查询 MCP 配置版本；参数是版本业务ID；返回版本持久化对象。
     */
    McpConfigVersionPO queryByVersionId(@Param("versionId") String versionId);

    /**
     * 按 MCP 和版本号查询版本；参数是 MCP ID 和版本号；返回版本持久化对象。
     */
    McpConfigVersionPO queryByMcpIdAndVersion(@Param("mcpId") String mcpId, @Param("version") String version);

    /**
     * 查询 MCP 的版本列表；参数是 MCP ID；返回版本列表。
     */
    List<McpConfigVersionPO> queryListByMcpId(@Param("mcpId") String mcpId);

    /**
     * 查询 MCP 当前生效版本；参数是 MCP ID；返回 active 版本。
     */
    McpConfigVersionPO queryActiveByMcpId(@Param("mcpId") String mcpId);
}
