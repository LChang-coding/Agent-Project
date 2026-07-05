package cn.bugstack.ai.api;

import cn.bugstack.ai.api.dto.tool.McpCreateRequestDTO;
import cn.bugstack.ai.api.dto.tool.McpResponseDTO;
import cn.bugstack.ai.api.dto.tool.SkillCreateRequestDTO;
import cn.bugstack.ai.api.dto.tool.SkillPackageUploadResponseDTO;
import cn.bugstack.ai.api.dto.tool.SkillResponseDTO;
import cn.bugstack.ai.api.dto.tool.SkillVersionCreateRequestDTO;
import cn.bugstack.ai.api.dto.tool.ToolCallLogResponseDTO;
import cn.bugstack.ai.api.dto.tool.ToolCatalogResponseDTO;
import cn.bugstack.ai.api.dto.tool.ToolPublishRequestDTO;
import cn.bugstack.ai.api.response.Response;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 工具发布 API 接口。
 */
public interface IToolApiService {

    /**
     * 上传 Skill 包；参数是 zip 文件；返回对象存储资产信息。
     */
    Response<SkillPackageUploadResponseDTO> uploadSkillPackage(MultipartFile file);

    /**
     * 创建 Skill 草稿；参数是 Skill 创建请求；返回 Skill 定义。
     */
    Response<SkillResponseDTO> createSkill(SkillCreateRequestDTO requestDTO);

    /**
     * 查询 Skill 列表；参数是查询范围；返回 Skill 列表。
     */
    Response<List<SkillResponseDTO>> querySkills(String scope);

    /**
     * 创建 Skill 新版本；参数是 Skill ID 和版本请求；返回 Skill 定义。
     */
    Response<SkillResponseDTO> createSkillVersion(String skillId, SkillVersionCreateRequestDTO requestDTO);

    /**
     * 发布 Skill；参数是 Skill ID 和发布请求；返回 Skill 定义。
     */
    Response<SkillResponseDTO> publishSkill(String skillId, ToolPublishRequestDTO requestDTO);

    /**
     * 禁用 Skill；参数是 Skill ID；返回 Skill 定义。
     */
    Response<SkillResponseDTO> disableSkill(String skillId);

    /**
     * 创建 MCP 草稿；参数是 MCP 创建请求；返回 MCP 定义。
     */
    Response<McpResponseDTO> createMcp(McpCreateRequestDTO requestDTO);

    /**
     * 查询 MCP 列表；参数是查询范围；返回 MCP 列表。
     */
    Response<List<McpResponseDTO>> queryMcps(String scope);

    /**
     * 测试 MCP；参数是 MCP ID；返回 MCP 定义。
     */
    Response<McpResponseDTO> testMcp(String mcpId);

    /**
     * 发布 MCP；参数是 MCP ID 和发布请求；返回 MCP 定义。
     */
    Response<McpResponseDTO> publishMcp(String mcpId, ToolPublishRequestDTO requestDTO);

    /**
     * 禁用 MCP；参数是 MCP ID；返回 MCP 定义。
     */
    Response<McpResponseDTO> disableMcp(String mcpId);

    /**
     * 查询工具目录；无参数；返回当前用户可被 Agent 加载的工具。
     */
    Response<List<ToolCatalogResponseDTO>> queryCatalog();

    /**
     * 查询工具调用日志；参数是会话ID；返回调用日志列表。
     */
    Response<List<ToolCallLogResponseDTO>> queryCalls(String sessionId);
}
