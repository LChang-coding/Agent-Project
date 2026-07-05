package cn.bugstack.ai.domain.tool.service;

import cn.bugstack.ai.domain.tool.model.entity.McpCreateCommandEntity;
import cn.bugstack.ai.domain.tool.model.entity.McpDefinitionEntity;
import cn.bugstack.ai.domain.tool.model.entity.SkillCreateCommandEntity;
import cn.bugstack.ai.domain.tool.model.entity.SkillDefinitionEntity;
import cn.bugstack.ai.domain.tool.model.entity.SkillPackageUploadCommandEntity;
import cn.bugstack.ai.domain.tool.model.entity.SkillPackageUploadResultEntity;
import cn.bugstack.ai.domain.tool.model.entity.SkillVersionCreateCommandEntity;
import cn.bugstack.ai.domain.tool.model.entity.ToolCallLogEntity;
import cn.bugstack.ai.domain.tool.model.entity.ToolCatalogEntity;
import cn.bugstack.ai.domain.tool.model.entity.ToolUserContextEntity;

import java.util.List;

/**
 * 工具发布服务接口。
 */
public interface IToolPublishService {

    /**
     * 上传 Skill 包；参数是上传命令；返回包资产信息。
     */
    SkillPackageUploadResultEntity uploadSkillPackage(SkillPackageUploadCommandEntity command);

    /**
     * 创建 Skill 草稿；参数是创建命令；返回 Skill 定义。
     */
    SkillDefinitionEntity createSkill(SkillCreateCommandEntity command);

    /**
     * 创建 Skill 新版本；参数是版本命令；返回 Skill 定义。
     */
    SkillDefinitionEntity createSkillVersion(SkillVersionCreateCommandEntity command);

    /**
     * 发布 Skill；参数是用户上下文、Skill ID 和版本；返回 Skill 定义。
     */
    SkillDefinitionEntity publishSkill(ToolUserContextEntity context, String skillId, String version);

    /**
     * 禁用 Skill；参数是用户上下文和 Skill ID；返回 Skill 定义。
     */
    SkillDefinitionEntity disableSkill(ToolUserContextEntity context, String skillId);

    /**
     * 查询 Skill 列表；参数是用户上下文和范围；返回 Skill 列表。
     */
    List<SkillDefinitionEntity> querySkills(ToolUserContextEntity context, String scope);

    /**
     * 创建 MCP 草稿；参数是创建命令；返回 MCP 定义。
     */
    McpDefinitionEntity createMcp(McpCreateCommandEntity command);

    /**
     * 测试 MCP；参数是用户上下文和 MCP ID；返回 MCP 定义。
     */
    McpDefinitionEntity testMcp(ToolUserContextEntity context, String mcpId);

    /**
     * 发布 MCP；参数是用户上下文、MCP ID 和版本；返回 MCP 定义。
     */
    McpDefinitionEntity publishMcp(ToolUserContextEntity context, String mcpId, String version);

    /**
     * 禁用 MCP；参数是用户上下文和 MCP ID；返回 MCP 定义。
     */
    McpDefinitionEntity disableMcp(ToolUserContextEntity context, String mcpId);

    /**
     * 查询 MCP 列表；参数是用户上下文和范围；返回 MCP 列表。
     */
    List<McpDefinitionEntity> queryMcps(ToolUserContextEntity context, String scope);

    /**
     * 查询当前用户工具目录；参数是用户上下文；返回可用工具目录。
     */
    List<ToolCatalogEntity> queryCatalog(ToolUserContextEntity context);

    /**
     * 查询会话工具调用日志；参数是用户上下文和会话ID；返回调用日志。
     */
    List<ToolCallLogEntity> queryCallLogs(ToolUserContextEntity context, String sessionId);
}
