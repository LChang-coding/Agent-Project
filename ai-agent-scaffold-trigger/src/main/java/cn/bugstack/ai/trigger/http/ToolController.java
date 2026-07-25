package cn.bugstack.ai.trigger.http;

import cn.bugstack.ai.api.IToolApiService;
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
import cn.bugstack.ai.domain.tool.service.IToolPublishService;
import cn.bugstack.ai.types.context.TenantContextHolder;
import cn.bugstack.ai.types.enums.ResponseCode;
import cn.bugstack.ai.types.exception.AppException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.Resource;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Skill/MCP 管理面、工具目录和调用日志入口。
 * <p>发布管理交给领域服务；Agent 运行时的真实工具调用仍必须经过 GatewayToolset 和 ToolGateway。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/tools")
@CrossOrigin(origins = "*")
public class ToolController implements IToolApiService {

    @Resource
    private IToolPublishService toolPublishService;

    /**
     * 上传 Skill 包；参数是 zip 文件；返回对象存储资产信息。
     */
    @Override
    @PostMapping("/skills/packages")
    public Response<SkillPackageUploadResponseDTO> uploadSkillPackage(@RequestParam("file") MultipartFile file) {
        try {
            // 上传内容先作为受控资产登记，Skill 解析和发布不会直接信任文件名或 MIME 类型。
            SkillPackageUploadResultEntity result = toolPublishService.uploadSkillPackage(SkillPackageUploadCommandEntity.builder()
                    .context(currentContext())
                    .fileName(file.getOriginalFilename())
                    .contentType(file.getContentType())
                    .bytes(file.getBytes())
                    .build());
            return success(toSkillPackageResponse(result));
        } catch (AppException e) {
            log.error("上传 Skill 包失败 fileName:{}", file == null ? null : file.getOriginalFilename(), e);
            return fail(e);
        } catch (Exception e) {
            log.error("上传 Skill 包异常 fileName:{}", file == null ? null : file.getOriginalFilename(), e);
            return fail();
        }
    }

    /**
     * 创建 Skill 草稿；参数是 Skill 创建请求；返回 Skill 定义。
     */
    @Override
    @PostMapping("/skills")
    public Response<SkillResponseDTO> createSkill(@RequestBody SkillCreateRequestDTO requestDTO) {
        try {
            SkillDefinitionEntity result = toolPublishService.createSkill(SkillCreateCommandEntity.builder()
                    .context(currentContext())
                    .skillName(requestDTO.getSkillName())
                    .skillCode(requestDTO.getSkillCode())
                    .description(requestDTO.getDescription())
                    .visibility(requestDTO.getVisibility())
                    .version(requestDTO.getVersion())
                    .assetId(requestDTO.getAssetId())
                    .build());
            return success(toSkillResponse(result));
        } catch (AppException e) {
            log.error("创建 Skill 失败 skillName:{}", requestDTO == null ? null : requestDTO.getSkillName(), e);
            return fail(e);
        } catch (Exception e) {
            log.error("创建 Skill 异常 skillName:{}", requestDTO == null ? null : requestDTO.getSkillName(), e);
            return fail();
        }
    }

    /**
     * 查询 Skill 列表；参数是查询范围；返回 Skill 列表。
     */
    @Override
    @GetMapping("/skills")
    public Response<List<SkillResponseDTO>> querySkills(@RequestParam(value = "scope", required = false) String scope) {
        try {
            return success(toolPublishService.querySkills(currentContext(), scope).stream()
                    .map(this::toSkillResponse)
                    .collect(Collectors.toList()));
        } catch (AppException e) {
            log.error("查询 Skill 列表失败 scope:{}", scope, e);
            return fail(e);
        } catch (Exception e) {
            log.error("查询 Skill 列表异常 scope:{}", scope, e);
            return fail();
        }
    }

    /**
     * 创建 Skill 新版本；参数是 Skill ID 和版本请求；返回 Skill 定义。
     */
    @Override
    @PostMapping("/skills/{skillId}/versions")
    public Response<SkillResponseDTO> createSkillVersion(@PathVariable String skillId, @RequestBody SkillVersionCreateRequestDTO requestDTO) {
        try {
            SkillDefinitionEntity result = toolPublishService.createSkillVersion(SkillVersionCreateCommandEntity.builder()
                    .context(currentContext())
                    .skillId(skillId)
                    .version(requestDTO.getVersion())
                    .assetId(requestDTO.getAssetId())
                    .build());
            return success(toSkillResponse(result));
        } catch (AppException e) {
            log.error("创建 Skill 版本失败 skillId:{}", skillId, e);
            return fail(e);
        } catch (Exception e) {
            log.error("创建 Skill 版本异常 skillId:{}", skillId, e);
            return fail();
        }
    }

    /**
     * 发布 Skill；参数是 Skill ID 和发布请求；返回 Skill 定义。
     */
    @Override
    @PostMapping("/skills/{skillId}/publish")
    public Response<SkillResponseDTO> publishSkill(@PathVariable String skillId, @RequestBody ToolPublishRequestDTO requestDTO) {
        try {
            return success(toSkillResponse(toolPublishService.publishSkill(currentContext(), skillId, requestDTO == null ? null : requestDTO.getVersion())));
        } catch (AppException e) {
            log.error("发布 Skill 失败 skillId:{}", skillId, e);
            return fail(e);
        } catch (Exception e) {
            log.error("发布 Skill 异常 skillId:{}", skillId, e);
            return fail();
        }
    }

    /**
     * 禁用 Skill；参数是 Skill ID；返回 Skill 定义。
     */
    @Override
    @PostMapping("/skills/{skillId}/disable")
    public Response<SkillResponseDTO> disableSkill(@PathVariable String skillId) {
        try {
            return success(toSkillResponse(toolPublishService.disableSkill(currentContext(), skillId)));
        } catch (AppException e) {
            log.error("禁用 Skill 失败 skillId:{}", skillId, e);
            return fail(e);
        } catch (Exception e) {
            log.error("禁用 Skill 异常 skillId:{}", skillId, e);
            return fail();
        }
    }

    /**
     * 创建 MCP 草稿；参数是 MCP 创建请求；返回 MCP 定义。
     */
    @Override
    @PostMapping("/mcps")
    public Response<McpResponseDTO> createMcp(@RequestBody McpCreateRequestDTO requestDTO) {
        try {
            McpDefinitionEntity result = toolPublishService.createMcp(McpCreateCommandEntity.builder()
                    .context(currentContext())
                    .mcpName(requestDTO.getMcpName())
                    .description(requestDTO.getDescription())
                    .visibility(requestDTO.getVisibility())
                    .version(requestDTO.getVersion())
                    .transportType(requestDTO.getTransportType())
                    .endpoint(requestDTO.getEndpoint())
                    .command(requestDTO.getCommand())
                    .args(requestDTO.getArgs())
                    .env(requestDTO.getEnv())
                    .build());
            return success(toMcpResponse(result));
        } catch (AppException e) {
            log.error("创建 MCP 失败 mcpName:{}", requestDTO == null ? null : requestDTO.getMcpName(), e);
            return fail(e);
        } catch (Exception e) {
            log.error("创建 MCP 异常 mcpName:{}", requestDTO == null ? null : requestDTO.getMcpName(), e);
            return fail();
        }
    }

    /**
     * 查询 MCP 列表；参数是查询范围；返回 MCP 列表。
     */
    @Override
    @GetMapping("/mcps")
    public Response<List<McpResponseDTO>> queryMcps(@RequestParam(value = "scope", required = false) String scope) {
        try {
            return success(toolPublishService.queryMcps(currentContext(), scope).stream()
                    .map(this::toMcpResponse)
                    .collect(Collectors.toList()));
        } catch (AppException e) {
            log.error("查询 MCP 列表失败 scope:{}", scope, e);
            return fail(e);
        } catch (Exception e) {
            log.error("查询 MCP 列表异常 scope:{}", scope, e);
            return fail();
        }
    }

    /**
     * 测试 MCP；参数是 MCP ID；返回 MCP 定义。
     */
    @Override
    @PostMapping("/mcps/{mcpId}/test")
    public Response<McpResponseDTO> testMcp(@PathVariable String mcpId) {
        try {
            return success(toMcpResponse(toolPublishService.testMcp(currentContext(), mcpId)));
        } catch (AppException e) {
            log.error("测试 MCP 失败 mcpId:{}", mcpId, e);
            return fail(e);
        } catch (Exception e) {
            log.error("测试 MCP 异常 mcpId:{}", mcpId, e);
            return fail();
        }
    }

    /**
     * 发布 MCP；参数是 MCP ID 和发布请求；返回 MCP 定义。
     */
    @Override
    @PostMapping("/mcps/{mcpId}/publish")
    public Response<McpResponseDTO> publishMcp(@PathVariable String mcpId, @RequestBody ToolPublishRequestDTO requestDTO) {
        try {
            return success(toMcpResponse(toolPublishService.publishMcp(currentContext(), mcpId, requestDTO == null ? null : requestDTO.getVersion())));
        } catch (AppException e) {
            log.error("发布 MCP 失败 mcpId:{}", mcpId, e);
            return fail(e);
        } catch (Exception e) {
            log.error("发布 MCP 异常 mcpId:{}", mcpId, e);
            return fail();
        }
    }

    /**
     * 禁用 MCP；参数是 MCP ID；返回 MCP 定义。
     */
    @Override
    @PostMapping("/mcps/{mcpId}/disable")
    public Response<McpResponseDTO> disableMcp(@PathVariable String mcpId) {
        try {
            return success(toMcpResponse(toolPublishService.disableMcp(currentContext(), mcpId)));
        } catch (AppException e) {
            log.error("禁用 MCP 失败 mcpId:{}", mcpId, e);
            return fail(e);
        } catch (Exception e) {
            log.error("禁用 MCP 异常 mcpId:{}", mcpId, e);
            return fail();
        }
    }

    /**
     * 查询工具目录；无参数；返回当前用户可被 Agent 加载的工具。
     */
    @Override
    @GetMapping("/catalog")
    public Response<List<ToolCatalogResponseDTO>> queryCatalog() {
        try {
            return success(toolPublishService.queryCatalog(currentContext()).stream()
                    .map(this::toCatalogResponse)
                    .collect(Collectors.toList()));
        } catch (AppException e) {
            log.error("查询工具目录失败", e);
            return fail(e);
        } catch (Exception e) {
            log.error("查询工具目录异常", e);
            return fail();
        }
    }

    /**
     * 查询工具调用日志；参数是会话ID；返回调用日志列表。
     */
    @Override
    @GetMapping("/calls")
    public Response<List<ToolCallLogResponseDTO>> queryCalls(@RequestParam("sessionId") String sessionId) {
        try {
            return success(toolPublishService.queryCallLogs(currentContext(), sessionId).stream()
                    .map(this::toCallLogResponse)
                    .collect(Collectors.toList()));
        } catch (AppException e) {
            log.error("查询工具调用日志失败 sessionId:{}", sessionId, e);
            return fail(e);
        } catch (Exception e) {
            log.error("查询工具调用日志异常 sessionId:{}", sessionId, e);
            return fail();
        }
    }

    /**
     * 获取当前身份上下文；无参数；返回工具用户上下文。
     */
    private ToolUserContextEntity currentContext() {
        return ToolUserContextEntity.builder()
                .tenantId(TenantContextHolder.getTenantId())
                .userId(TenantContextHolder.getUserId())
                .roleCode(TenantContextHolder.getRoleCode())
                .build();
    }

    /**
     * 转换上传响应；参数是上传结果；返回响应 DTO。
     */
    private SkillPackageUploadResponseDTO toSkillPackageResponse(SkillPackageUploadResultEntity result) {
        SkillPackageUploadResponseDTO responseDTO = new SkillPackageUploadResponseDTO();
        responseDTO.setAssetId(result.getAssetId());
        responseDTO.setBucket(result.getBucket());
        responseDTO.setObjectKey(result.getObjectKey());
        responseDTO.setFileName(result.getFileName());
        responseDTO.setSha256(result.getSha256());
        responseDTO.setSizeBytes(result.getSizeBytes());
        return responseDTO;
    }

    /**
     * 转换 Skill 响应；参数是 Skill 实体；返回响应 DTO。
     */
    private SkillResponseDTO toSkillResponse(SkillDefinitionEntity entity) {
        SkillResponseDTO responseDTO = new SkillResponseDTO();
        responseDTO.setSkillId(entity.getSkillId());
        responseDTO.setSkillName(entity.getSkillName());
        responseDTO.setSkillCode(entity.getSkillCode());
        responseDTO.setDescription(entity.getDescription());
        responseDTO.setVisibility(entity.getVisibility());
        responseDTO.setCurrentVersion(entity.getCurrentVersion());
        responseDTO.setPublishedVersion(entity.getPublishedVersion());
        responseDTO.setStatus(entity.getStatus());
        return responseDTO;
    }

    /**
     * 转换 MCP 响应；参数是 MCP 实体；返回响应 DTO。
     */
    private McpResponseDTO toMcpResponse(McpDefinitionEntity entity) {
        McpResponseDTO responseDTO = new McpResponseDTO();
        responseDTO.setMcpId(entity.getMcpId());
        responseDTO.setMcpName(entity.getMcpName());
        responseDTO.setDescription(entity.getDescription());
        responseDTO.setVisibility(entity.getVisibility());
        responseDTO.setTransportType(entity.getTransportType());
        responseDTO.setEndpoint(entity.getEndpoint());
        responseDTO.setCurrentVersion(entity.getCurrentVersion());
        responseDTO.setPublishedVersion(entity.getPublishedVersion());
        responseDTO.setTestStatus(entity.getTestStatus());
        responseDTO.setTestMessage(entity.getTestMessage());
        responseDTO.setLastTestTime(entity.getLastTestTime());
        responseDTO.setStatus(entity.getStatus());
        return responseDTO;
    }

    /**
     * 转换目录响应；参数是工具目录；返回响应 DTO。
     */
    private ToolCatalogResponseDTO toCatalogResponse(ToolCatalogEntity entity) {
        ToolCatalogResponseDTO responseDTO = new ToolCatalogResponseDTO();
        responseDTO.setToolType(entity.getToolType());
        responseDTO.setToolId(entity.getToolId());
        responseDTO.setToolName(entity.getToolName());
        responseDTO.setToolCode(entity.getToolCode());
        responseDTO.setDescription(entity.getDescription());
        responseDTO.setVersion(entity.getVersion());
        responseDTO.setVisibility(entity.getVisibility());
        return responseDTO;
    }

    /**
     * 转换调用日志响应；参数是调用日志；返回响应 DTO。
     */
    private ToolCallLogResponseDTO toCallLogResponse(ToolCallLogEntity entity) {
        ToolCallLogResponseDTO responseDTO = new ToolCallLogResponseDTO();
        responseDTO.setToolType(entity.getToolType());
        responseDTO.setToolId(entity.getToolId());
        responseDTO.setToolName(entity.getToolName());
        responseDTO.setVersion(entity.getVersion());
        responseDTO.setInvocationId(entity.getInvocationId());
        responseDTO.setTraceId(entity.getTraceId());
        responseDTO.setStatus(entity.getStatus());
        responseDTO.setErrorType(entity.getErrorType());
        responseDTO.setErrorMessage(entity.getErrorMessage());
        responseDTO.setCostMs(entity.getCostMs());
        responseDTO.setCreateTime(entity.getCreateTime());
        return responseDTO;
    }

    /**
     * 成功响应；参数是数据；返回统一响应。
     */
    private <T> Response<T> success(T data) {
        return Response.<T>builder().code(ResponseCode.SUCCESS.getCode()).info(ResponseCode.SUCCESS.getInfo()).data(data).build();
    }

    /**
     * 业务失败响应；参数是异常；返回统一响应。
     */
    private <T> Response<T> fail(AppException e) {
        return Response.<T>builder().code(e.getCode()).info(e.getInfo()).build();
    }

    /**
     * 系统失败响应；无参数；返回统一响应。
     */
    private <T> Response<T> fail() {
        return Response.<T>builder().code(ResponseCode.UN_ERROR.getCode()).info(ResponseCode.UN_ERROR.getInfo()).build();
    }
}
