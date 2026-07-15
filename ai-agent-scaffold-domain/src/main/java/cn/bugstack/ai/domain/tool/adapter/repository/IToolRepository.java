package cn.bugstack.ai.domain.tool.adapter.repository;

import cn.bugstack.ai.domain.tool.model.entity.McpDefinitionEntity;
import cn.bugstack.ai.domain.tool.model.entity.McpVersionEntity;
import cn.bugstack.ai.domain.tool.model.entity.SkillDefinitionEntity;
import cn.bugstack.ai.domain.tool.model.entity.SkillPackageUploadResultEntity;
import cn.bugstack.ai.domain.tool.model.entity.SkillVersionEntity;
import cn.bugstack.ai.domain.tool.model.entity.ToolCallLogEntity;
import cn.bugstack.ai.domain.tool.model.entity.ToolCatalogEntity;
import cn.bugstack.ai.domain.share.model.SessionToolDependencyEntity;

import java.util.List;

/**
 * 工具领域仓储接口。
 */
public interface IToolRepository {

    /**
     * 保存 Skill 包资产；参数是租户、用户和上传结果；返回资产业务ID。
     */
    String saveSkillAsset(String tenantId, String userId, SkillPackageUploadResultEntity result);

    /**
     * 查询资产；参数是资产业务ID；返回上传结果。
     */
    SkillPackageUploadResultEntity querySkillAsset(String assetId);

    /**
     * 保存 Skill 定义；参数是 Skill 定义；返回影响行数。
     */
    int saveSkillDefinition(SkillDefinitionEntity entity);

    /**
     * 更新 Skill 定义；参数是 Skill 定义；返回影响行数。
     */
    int updateSkillDefinition(SkillDefinitionEntity entity);

    /**
     * 查询 Skill 定义；参数是 Skill ID；返回 Skill 定义。
     */
    SkillDefinitionEntity querySkillDefinition(String skillId);

    /**
     * 保存 Skill 版本；参数是 Skill 版本；返回影响行数。
     */
    int saveSkillVersion(SkillVersionEntity entity);

    /**
     * 更新 Skill 版本；参数是 Skill 版本；返回影响行数。
     */
    int updateSkillVersion(SkillVersionEntity entity);

    /**
     * 查询 Skill 版本；参数是 Skill ID 和版本号；返回 Skill 版本。
     */
    SkillVersionEntity querySkillVersion(String skillId, String version);

    /**
     * 查询 Skill 版本列表；参数是 Skill ID；返回版本列表。
     */
    List<SkillVersionEntity> querySkillVersions(String skillId);

    /**
     * 保存 MCP 定义；参数是 MCP 定义；返回影响行数。
     */
    int saveMcpDefinition(McpDefinitionEntity entity);

    /**
     * 更新 MCP 定义；参数是 MCP 定义；返回影响行数。
     */
    int updateMcpDefinition(McpDefinitionEntity entity);

    /**
     * 查询 MCP 定义；参数是 MCP ID；返回 MCP 定义。
     */
    McpDefinitionEntity queryMcpDefinition(String mcpId);

    /**
     * 保存 MCP 版本；参数是 MCP 版本；返回影响行数。
     */
    int saveMcpVersion(McpVersionEntity entity);

    /**
     * 更新 MCP 版本；参数是 MCP 版本；返回影响行数。
     */
    int updateMcpVersion(McpVersionEntity entity);

    /**
     * 查询 MCP 版本；参数是 MCP ID 和版本号；返回 MCP 版本。
     */
    McpVersionEntity queryMcpVersion(String mcpId, String version);

    /**
     * 查询 MCP 版本列表；参数是 MCP ID；返回版本列表。
     */
    List<McpVersionEntity> queryMcpVersions(String mcpId);

    /**
     * 查询用户可管理的 Skill；参数是租户、用户和范围；返回 Skill 列表。
     */
    List<SkillDefinitionEntity> querySkillDefinitions(String tenantId, String userId, String scope);

    /**
     * 查询用户可管理的 MCP；参数是租户、用户和范围；返回 MCP 列表。
     */
    List<McpDefinitionEntity> queryMcpDefinitions(String tenantId, String userId, String scope);

    /**
     * 查询当前用户可用工具目录；参数是租户和用户；返回工具目录。
     */
    List<ToolCatalogEntity> queryAvailableTools(String tenantId, String userId);

    /**
     * 写入工具调用日志；参数是工具调用日志；返回影响行数。
     */
    int saveToolCallLog(ToolCallLogEntity entity);

    /**
     * 幂等写入工具开始日志；参数是 started 日志；返回是否首次写入。
     */
    int claimToolCallLog(ToolCallLogEntity entity);

    /**
     * 按幂等键查询工具日志；参数是幂等键；返回日志或空。
     */
    ToolCallLogEntity queryToolCallLogByIdempotencyKey(String idempotencyKey);

    /**
     * 完成工具日志；参数是幂等键、状态和执行结果；返回影响行数。
     */
    int finishToolCallLog(String idempotencyKey, String outputJson, String status,
                          String errorType, String errorMessage, Long costMs);

    /**
     * 查询会话工具调用日志；参数是租户、用户和会话；返回调用日志。
     */
    List<ToolCallLogEntity> queryToolCallLogs(String tenantId, String userId, String sessionId);

    /** 查询会话有效成功调用形成的分享工具依赖；参数是可信会话范围；返回去重依赖。 */
    List<SessionToolDependencyEntity> queryShareToolDependencies(String tenantId, String userId, String sessionId);
}
