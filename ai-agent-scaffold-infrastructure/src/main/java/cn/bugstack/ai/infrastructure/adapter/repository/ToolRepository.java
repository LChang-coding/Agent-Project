package cn.bugstack.ai.infrastructure.adapter.repository;

import cn.bugstack.ai.domain.tool.adapter.repository.IToolRepository;
import cn.bugstack.ai.domain.tool.model.entity.McpDefinitionEntity;
import cn.bugstack.ai.domain.tool.model.entity.McpVersionEntity;
import cn.bugstack.ai.domain.tool.model.entity.SkillDefinitionEntity;
import cn.bugstack.ai.domain.tool.model.entity.SkillPackageUploadResultEntity;
import cn.bugstack.ai.domain.tool.model.entity.SkillVersionEntity;
import cn.bugstack.ai.domain.tool.model.entity.ToolCallLogEntity;
import cn.bugstack.ai.domain.tool.model.entity.ToolCallStatisticsEntity;
import cn.bugstack.ai.domain.tool.model.entity.ToolCatalogEntity;
import cn.bugstack.ai.domain.share.model.SessionToolDependencyEntity;
import cn.bugstack.ai.domain.tool.model.valobj.ToolStatus;
import cn.bugstack.ai.domain.tool.model.valobj.ToolType;
import cn.bugstack.ai.domain.tool.model.valobj.ToolVisibility;
import cn.bugstack.ai.infrastructure.dao.IArtifactAssetDao;
import cn.bugstack.ai.infrastructure.dao.IMcpConfigVersionDao;
import cn.bugstack.ai.infrastructure.dao.IMcpServerConfigDao;
import cn.bugstack.ai.infrastructure.dao.ISkillDefinitionDao;
import cn.bugstack.ai.infrastructure.dao.ISkillVersionDao;
import cn.bugstack.ai.infrastructure.dao.IToolCallLogDao;
import cn.bugstack.ai.infrastructure.dao.po.ArtifactAssetPO;
import cn.bugstack.ai.infrastructure.dao.po.McpConfigVersionPO;
import cn.bugstack.ai.infrastructure.dao.po.McpServerConfigPO;
import cn.bugstack.ai.infrastructure.dao.po.SkillDefinitionPO;
import cn.bugstack.ai.infrastructure.dao.po.SkillVersionPO;
import cn.bugstack.ai.infrastructure.dao.po.ToolCallLogPO;
import cn.bugstack.ai.infrastructure.dao.po.ToolCallStatisticsPO;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 工具领域仓储实现。
 */
@Repository
public class ToolRepository implements IToolRepository {

    /** 解析和生成工具版本配置、资产元数据及调用结果 JSON。 */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Skill 包等工具资产的持久化入口。 */
    private final IArtifactAssetDao artifactAssetDao;
    /** Skill 定义及其发布指针的持久化入口。 */
    private final ISkillDefinitionDao skillDefinitionDao;
    /** Skill 不可变版本配置的持久化入口。 */
    private final ISkillVersionDao skillVersionDao;
    /** MCP 定义及其发布指针的持久化入口。 */
    private final IMcpServerConfigDao mcpServerConfigDao;
    /** MCP 不可变版本配置的持久化入口。 */
    private final IMcpConfigVersionDao mcpConfigVersionDao;
    /** 工具调用幂等账本、结果和会话统计的持久化入口。 */
    private final IToolCallLogDao toolCallLogDao;

    /**
     * 创建工具仓储；参数是相关 DAO；返回仓储实例。
     */
    public ToolRepository(IArtifactAssetDao artifactAssetDao,
                          ISkillDefinitionDao skillDefinitionDao,
                          ISkillVersionDao skillVersionDao,
                          IMcpServerConfigDao mcpServerConfigDao,
                          IMcpConfigVersionDao mcpConfigVersionDao,
                          IToolCallLogDao toolCallLogDao) {
        this.artifactAssetDao = artifactAssetDao;
        this.skillDefinitionDao = skillDefinitionDao;
        this.skillVersionDao = skillVersionDao;
        this.mcpServerConfigDao = mcpServerConfigDao;
        this.mcpConfigVersionDao = mcpConfigVersionDao;
        this.toolCallLogDao = toolCallLogDao;
    }

    /**
     * 保存 Skill 包资产；参数是租户、用户和上传结果；返回资产业务ID。
     */
    @Override
    public String saveSkillAsset(String tenantId, String userId, SkillPackageUploadResultEntity result) {
        ArtifactAssetPO po = ArtifactAssetPO.builder()
                .tenantId(tenantId)
                .ownerUserId(userId)
                .visibility(ToolVisibility.PRIVATE)
                .assetId(result.getAssetId())
                .assetKind("skill_package")
                .assetType("skill_package")
                .bucket(result.getBucket())
                .objectKey(result.getObjectKey())
                .fileName(result.getFileName())
                .mimeType("application/zip")
                .sizeBytes(result.getSizeBytes())
                .sha256(result.getSha256())
                .status(ToolStatus.ACTIVE)
                .parseStatus("unsupported")
                .metadata(skillAssetMetadata(result.getSha256()))
                .build();
        artifactAssetDao.insert(po);
        return result.getAssetId();
    }

    /**
     * 查询资产；参数是资产业务ID；返回上传结果。
     */
    @Override
    public SkillPackageUploadResultEntity querySkillAsset(String assetId) {
        ArtifactAssetPO po = artifactAssetDao.queryByAssetId(assetId);
        if (po == null) {
            return null;
        }
        return SkillPackageUploadResultEntity.builder()
                .assetId(po.getAssetId())
                .bucket(po.getBucket())
                .objectKey(po.getObjectKey())
                .fileName(po.getFileName())
                .sizeBytes(po.getSizeBytes())
                .sha256(extractSha256(po.getMetadata()))
                .build();
    }

    /**
     * 保存 Skill 定义；参数是 Skill 定义；返回影响行数。
     */
    @Override
    public int saveSkillDefinition(SkillDefinitionEntity entity) {
        return skillDefinitionDao.insert(toSkillPO(entity));
    }

    /**
     * 更新 Skill 定义；参数是 Skill 定义；返回影响行数。
     */
    @Override
    public int updateSkillDefinition(SkillDefinitionEntity entity) {
        return skillDefinitionDao.updateById(toSkillPO(entity));
    }

    /**
     * 查询 Skill 定义；参数是 Skill ID；返回 Skill 定义。
     */
    @Override
    public SkillDefinitionEntity querySkillDefinition(String skillId) {
        return toSkillEntity(skillDefinitionDao.queryBySkillId(skillId));
    }

    /**
     * 保存 Skill 版本；参数是 Skill 版本；返回影响行数。
     */
    @Override
    public int saveSkillVersion(SkillVersionEntity entity) {
        return skillVersionDao.insert(toSkillVersionPO(entity));
    }

    /**
     * 更新 Skill 版本；参数是 Skill 版本；返回影响行数。
     */
    @Override
    public int updateSkillVersion(SkillVersionEntity entity) {
        return skillVersionDao.updateById(toSkillVersionPO(entity));
    }

    /**
     * 查询 Skill 版本；参数是 Skill ID 和版本号；返回 Skill 版本。
     */
    @Override
    public SkillVersionEntity querySkillVersion(String skillId, String version) {
        return toSkillVersionEntity(skillVersionDao.queryBySkillIdAndVersion(skillId, version));
    }

    /**
     * 查询 Skill 版本列表；参数是 Skill ID；返回版本列表。
     */
    @Override
    public List<SkillVersionEntity> querySkillVersions(String skillId) {
        return skillVersionDao.queryListBySkillId(skillId).stream().map(this::toSkillVersionEntity).collect(Collectors.toList());
    }

    /**
     * 保存 MCP 定义；参数是 MCP 定义；返回影响行数。
     */
    @Override
    public int saveMcpDefinition(McpDefinitionEntity entity) {
        return mcpServerConfigDao.insert(toMcpPO(entity));
    }

    /**
     * 更新 MCP 定义；参数是 MCP 定义；返回影响行数。
     */
    @Override
    public int updateMcpDefinition(McpDefinitionEntity entity) {
        return mcpServerConfigDao.updateById(toMcpPO(entity));
    }

    /**
     * 查询 MCP 定义；参数是 MCP ID；返回 MCP 定义。
     */
    @Override
    public McpDefinitionEntity queryMcpDefinition(String mcpId) {
        return toMcpEntity(mcpServerConfigDao.queryByMcpId(mcpId));
    }

    /**
     * 保存 MCP 版本；参数是 MCP 版本；返回影响行数。
     */
    @Override
    public int saveMcpVersion(McpVersionEntity entity) {
        return mcpConfigVersionDao.insert(toMcpVersionPO(entity));
    }

    /**
     * 更新 MCP 版本；参数是 MCP 版本；返回影响行数。
     */
    @Override
    public int updateMcpVersion(McpVersionEntity entity) {
        return mcpConfigVersionDao.updateById(toMcpVersionPO(entity));
    }

    /**
     * 查询 MCP 版本；参数是 MCP ID 和版本号；返回 MCP 版本。
     */
    @Override
    public McpVersionEntity queryMcpVersion(String mcpId, String version) {
        return toMcpVersionEntity(mcpConfigVersionDao.queryByMcpIdAndVersion(mcpId, version));
    }

    /**
     * 查询 MCP 版本列表；参数是 MCP ID；返回版本列表。
     */
    @Override
    public List<McpVersionEntity> queryMcpVersions(String mcpId) {
        return mcpConfigVersionDao.queryListByMcpId(mcpId).stream().map(this::toMcpVersionEntity).collect(Collectors.toList());
    }

    /**
     * 查询用户可管理的 Skill；参数是租户、用户和范围；返回 Skill 列表。
     */
    @Override
    public List<SkillDefinitionEntity> querySkillDefinitions(String tenantId, String userId, String scope) {
        if ("mine".equalsIgnoreCase(scope)) {
            return skillDefinitionDao.queryListByTenantIdAndOwnerUserId(tenantId, userId).stream()
                    .map(this::toSkillEntity).collect(Collectors.toList());
        }
        if ("tenant".equalsIgnoreCase(scope)) {
            return skillDefinitionDao.queryListByTenantIdAndVisibility(tenantId, ToolVisibility.TENANT_PUBLIC).stream().map(this::toSkillEntity).collect(Collectors.toList());
        }
        return availableSkills(tenantId, userId);
    }

    /**
     * 查询用户可管理的 MCP；参数是租户、用户和范围；返回 MCP 列表。
     */
    @Override
    public List<McpDefinitionEntity> queryMcpDefinitions(String tenantId, String userId, String scope) {
        if ("mine".equalsIgnoreCase(scope)) {
            return mcpServerConfigDao.queryListByTenantIdAndOwnerUserId(tenantId, userId).stream()
                    .map(this::toMcpEntity).collect(Collectors.toList());
        }
        if ("tenant".equalsIgnoreCase(scope)) {
            return mcpServerConfigDao.queryListByTenantIdAndVisibility(tenantId, ToolVisibility.TENANT_PUBLIC).stream().map(this::toMcpEntity).collect(Collectors.toList());
        }
        return availableMcps(tenantId, userId);
    }

    /**
     * 查询当前用户可用工具目录；参数是租户和用户；返回工具目录。
     */
    @Override
    public List<ToolCatalogEntity> queryAvailableTools(String tenantId, String userId) {
        List<ToolCatalogEntity> tools = new ArrayList<>();
        availableSkills(tenantId, userId).forEach(skill -> {
            SkillVersionEntity activeVersion = activeSkillVersion(skill);
            if (activeVersion != null) {
                tools.add(toSkillCatalog(skill, activeVersion));
            }
        });
        availableMcps(tenantId, userId).forEach(mcp -> {
            McpVersionEntity activeVersion = activeMcpVersion(mcp);
            if (activeVersion != null) {
                tools.add(toMcpCatalog(mcp, activeVersion));
            }
        });
        return tools;
    }

    /**
     * 写入工具调用日志；参数是工具调用日志；返回影响行数。
     */
    @Override
    public int saveToolCallLog(ToolCallLogEntity entity) {
        return toolCallLogDao.insert(toToolCallLogPO(entity));
    }

    /**
     * 幂等写入工具开始日志；参数是日志；返回是否首次写入。
     */
    @Override
    public int claimToolCallLog(ToolCallLogEntity entity) {
        ToolCallLogPO po = toToolCallLogPO(entity);
        int affected = toolCallLogDao.insertIgnore(po);
        entity.setId(po.getId());
        return affected;
    }

    /**
     * 按幂等键查询工具日志；参数是幂等键；返回日志或空。
     */
    @Override
    public ToolCallLogEntity queryToolCallLogByIdempotencyKey(String idempotencyKey) {
        ToolCallLogPO po = toolCallLogDao.queryByIdempotencyKey(idempotencyKey);
        return po == null ? null : toToolCallLogEntity(po);
    }

    /**
     * 完成工具日志；参数是幂等键、状态和结果；返回影响行数。
     */
    @Override
    public int finishToolCallLog(String idempotencyKey, String outputJson, String status,
                                 String errorType, String errorMessage, Long costMs) {
        return toolCallLogDao.finishByIdempotencyKey(idempotencyKey, outputJson, status,
                errorType, errorMessage, costMs);
    }

    /**
     * 查询会话工具调用日志；参数是租户、用户和会话；返回调用日志。
     */
    @Override
    public List<ToolCallLogEntity> queryToolCallLogs(String tenantId, String userId, String sessionId) {
        return toolCallLogDao.queryListBySessionId(tenantId, userId, sessionId).stream().map(this::toToolCallLogEntity).collect(Collectors.toList());
    }

    /**
     * 汇总会话工具调用；参数是租户、用户和会话；返回总调用数和去重工具数。
     */
    @Override
    public ToolCallStatisticsEntity summarizeToolCalls(String tenantId, String userId, String sessionId) {
        ToolCallStatisticsPO summary = toolCallLogDao.summarizeBySessionId(tenantId, userId, sessionId);
        return summary == null ? ToolCallStatisticsEntity.builder().callCount(0L).toolCount(0L).build()
                : ToolCallStatisticsEntity.builder().callCount(summary.getCallCount())
                .toolCount(summary.getToolCount()).build();
    }

    @Override
    /** 从已完成调用中提取会话分享所需的工具类型、版本和名称快照。 */
    public List<SessionToolDependencyEntity> queryShareToolDependencies(String tenantId, String userId,
                                                                        String sessionId) {
        return toolCallLogDao.queryShareDependencies(tenantId, userId, sessionId).stream()
                .map(item -> SessionToolDependencyEntity.builder().toolType(item.getToolType())
                        .toolId(item.getToolId()).toolName(item.getToolName()).version(item.getVersion())
                        .source("tool_call_log").build())
                .toList();
    }

    /**
     * 查询可用 Skill；参数是租户和用户；返回去重后的 Skill。
     */
    private List<SkillDefinitionEntity> availableSkills(String tenantId, String userId) {
        Map<String, SkillDefinitionEntity> map = new LinkedHashMap<>();
        skillDefinitionDao.queryListByTenantIdAndOwnerUserId(tenantId, userId).stream()
                .filter(item -> ToolStatus.ACTIVE.equals(item.getStatus()))
                .map(this::toSkillEntity)
                .forEach(item -> map.put(item.getSkillId(), item));
        skillDefinitionDao.queryListByTenantIdAndVisibility(tenantId, ToolVisibility.TENANT_PUBLIC).stream()
                .filter(item -> ToolStatus.ACTIVE.equals(item.getStatus()))
                .map(this::toSkillEntity)
                .forEach(item -> map.put(item.getSkillId(), item));
        return new ArrayList<>(map.values());
    }

    /**
     * 查询可用 MCP；参数是租户和用户；返回去重后的 MCP。
     */
    private List<McpDefinitionEntity> availableMcps(String tenantId, String userId) {
        Map<String, McpDefinitionEntity> map = new LinkedHashMap<>();
        mcpServerConfigDao.queryListByTenantIdAndOwnerUserId(tenantId, userId).stream()
                .filter(item -> ToolStatus.ACTIVE.equals(item.getStatus()))
                .map(this::toMcpEntity)
                .forEach(item -> map.put(item.getMcpId(), item));
        mcpServerConfigDao.queryListByTenantIdAndVisibility(tenantId, ToolVisibility.TENANT_PUBLIC).stream()
                .filter(item -> ToolStatus.ACTIVE.equals(item.getStatus()))
                .map(this::toMcpEntity)
                .forEach(item -> map.put(item.getMcpId(), item));
        return new ArrayList<>(map.values());
    }

    /**
     * 查询 Skill 生效版本；参数是 Skill 定义；返回生效版本。
     */
    private SkillVersionEntity activeSkillVersion(SkillDefinitionEntity skill) {
        if (skill.getActiveVersionId() != null && !skill.getActiveVersionId().isBlank()) {
            return toSkillVersionEntity(skillVersionDao.queryByVersionId(skill.getActiveVersionId()));
        }
        return toSkillVersionEntity(skillVersionDao.queryActiveBySkillId(skill.getSkillId()));
    }

    /**
     * 查询 MCP 生效版本；参数是 MCP 定义；返回生效版本。
     */
    private McpVersionEntity activeMcpVersion(McpDefinitionEntity mcp) {
        if (mcp.getActiveVersionId() != null && !mcp.getActiveVersionId().isBlank()) {
            return toMcpVersionEntity(mcpConfigVersionDao.queryByVersionId(mcp.getActiveVersionId()));
        }
        return toMcpVersionEntity(mcpConfigVersionDao.queryActiveByMcpId(mcp.getMcpId()));
    }

    /**
     * 转换 Skill 目录；参数是定义和版本；返回工具目录。
     */
    private ToolCatalogEntity toSkillCatalog(SkillDefinitionEntity skill, SkillVersionEntity version) {
        return ToolCatalogEntity.builder()
                .toolType(ToolType.SKILL)
                .toolId(skill.getSkillId())
                .toolName(skill.getSkillName())
                .toolCode(skill.getSkillCode())
                .description(skill.getDescription())
                .version(version.getVersion())
                .visibility(skill.getVisibility())
                .ownerUserId(skill.getOwnerUserId())
                .sourceType(skill.getSourceType())
                .bucket(version.getBucket())
                .objectKey(version.getObjectKey())
                .schemaJson(version.getManifestJson())
                .build();
    }

    /**
     * 转换 MCP 目录；参数是定义和版本；返回工具目录。
     */
    private ToolCatalogEntity toMcpCatalog(McpDefinitionEntity mcp, McpVersionEntity version) {
        return ToolCatalogEntity.builder()
                .toolType(ToolType.MCP)
                .toolId(mcp.getMcpId())
                .toolName(mcp.getMcpName())
                .toolCode(mcp.getMcpId())
                .description(mcp.getDescription())
                .version(version.getVersion())
                .visibility(mcp.getVisibility())
                .ownerUserId(mcp.getOwnerUserId())
                .transportType(version.getTransportType())
                .endpoint(version.getEndpoint())
                .command(version.getCommand())
                .args(version.getArgs())
                .env(version.getEnv())
                .schemaJson(version.getToolSchemaJson())
                .build();
    }

    /**
     * 转换 Skill PO；参数是实体；返回 PO。
     */
    private SkillDefinitionPO toSkillPO(SkillDefinitionEntity entity) {
        SkillDefinitionPO po = SkillDefinitionPO.builder()
                .tenantId(entity.getTenantId())
                .ownerUserId(entity.getOwnerUserId())
                .visibility(entity.getVisibility())
                .skillId(entity.getSkillId())
                .skillName(entity.getSkillName())
                .skillCode(entity.getSkillCode())
                .description(entity.getDescription())
                .sourceType(entity.getSourceType())
                .sourceUri(entity.getSourceUri())
                .version(entity.getVersion())
                .currentVersion(entity.getCurrentVersion())
                .publishedVersion(entity.getPublishedVersion())
                .activeVersionId(entity.getActiveVersionId())
                .status(entity.getStatus())
                .metadata(entity.getMetadata())
                .build();
        po.setId(entity.getId());
        return po;
    }

    /**
     * 转换 Skill 实体；参数是 PO；返回实体。
     */
    private SkillDefinitionEntity toSkillEntity(SkillDefinitionPO po) {
        if (po == null) {
            return null;
        }
        SkillDefinitionEntity entity = SkillDefinitionEntity.builder()
                .id(po.getId())
                .tenantId(po.getTenantId())
                .ownerUserId(po.getOwnerUserId())
                .visibility(po.getVisibility())
                .skillId(po.getSkillId())
                .skillName(po.getSkillName())
                .skillCode(po.getSkillCode())
                .description(po.getDescription())
                .sourceType(po.getSourceType())
                .sourceUri(po.getSourceUri())
                .version(po.getVersion())
                .currentVersion(po.getCurrentVersion())
                .publishedVersion(po.getPublishedVersion())
                .activeVersionId(po.getActiveVersionId())
                .status(po.getStatus())
                .metadata(po.getMetadata())
                .build();
        return entity;
    }

    /**
     * 转换 Skill 版本 PO；参数是实体；返回 PO。
     */
    private SkillVersionPO toSkillVersionPO(SkillVersionEntity entity) {
        SkillVersionPO po = SkillVersionPO.builder()
                .tenantId(entity.getTenantId())
                .ownerUserId(entity.getOwnerUserId())
                .skillId(entity.getSkillId())
                .versionId(entity.getVersionId())
                .version(entity.getVersion())
                .assetId(entity.getAssetId())
                .bucket(entity.getBucket())
                .objectKey(entity.getObjectKey())
                .fileName(entity.getFileName())
                .sha256(entity.getSha256())
                .sizeBytes(entity.getSizeBytes())
                .manifestJson(entity.getManifestJson())
                .status(entity.getStatus())
                .metadata(entity.getMetadata())
                .build();
        po.setId(entity.getId());
        return po;
    }

    /**
     * 转换 Skill 版本实体；参数是 PO；返回实体。
     */
    private SkillVersionEntity toSkillVersionEntity(SkillVersionPO po) {
        if (po == null) {
            return null;
        }
        return SkillVersionEntity.builder()
                .id(po.getId())
                .tenantId(po.getTenantId())
                .ownerUserId(po.getOwnerUserId())
                .skillId(po.getSkillId())
                .versionId(po.getVersionId())
                .version(po.getVersion())
                .assetId(po.getAssetId())
                .bucket(po.getBucket())
                .objectKey(po.getObjectKey())
                .fileName(po.getFileName())
                .sha256(po.getSha256())
                .sizeBytes(po.getSizeBytes())
                .manifestJson(po.getManifestJson())
                .status(po.getStatus())
                .metadata(po.getMetadata())
                .build();
    }

    /**
     * 转换 MCP PO；参数是实体；返回 PO。
     */
    private McpServerConfigPO toMcpPO(McpDefinitionEntity entity) {
        McpServerConfigPO po = McpServerConfigPO.builder()
                .tenantId(entity.getTenantId())
                .ownerUserId(entity.getOwnerUserId())
                .visibility(entity.getVisibility())
                .mcpId(entity.getMcpId())
                .mcpName(entity.getMcpName())
                .transportType(entity.getTransportType())
                .endpoint(entity.getEndpoint())
                .command(entity.getCommand())
                .args(entity.getArgs())
                .env(entity.getEnv())
                .description(entity.getDescription())
                .currentVersion(entity.getCurrentVersion())
                .publishedVersion(entity.getPublishedVersion())
                .activeVersionId(entity.getActiveVersionId())
                .testStatus(entity.getTestStatus())
                .testMessage(entity.getTestMessage())
                .lastTestTime(entity.getLastTestTime())
                .status(entity.getStatus())
                .metadata(entity.getMetadata())
                .build();
        po.setId(entity.getId());
        return po;
    }

    /**
     * 转换 MCP 实体；参数是 PO；返回实体。
     */
    private McpDefinitionEntity toMcpEntity(McpServerConfigPO po) {
        if (po == null) {
            return null;
        }
        return McpDefinitionEntity.builder()
                .id(po.getId())
                .tenantId(po.getTenantId())
                .ownerUserId(po.getOwnerUserId())
                .visibility(po.getVisibility())
                .mcpId(po.getMcpId())
                .mcpName(po.getMcpName())
                .transportType(po.getTransportType())
                .endpoint(po.getEndpoint())
                .command(po.getCommand())
                .args(po.getArgs())
                .env(po.getEnv())
                .description(po.getDescription())
                .currentVersion(po.getCurrentVersion())
                .publishedVersion(po.getPublishedVersion())
                .activeVersionId(po.getActiveVersionId())
                .testStatus(po.getTestStatus())
                .testMessage(po.getTestMessage())
                .lastTestTime(po.getLastTestTime())
                .status(po.getStatus())
                .metadata(po.getMetadata())
                .build();
    }

    /**
     * 转换 MCP 版本 PO；参数是实体；返回 PO。
     */
    private McpConfigVersionPO toMcpVersionPO(McpVersionEntity entity) {
        McpConfigVersionPO po = McpConfigVersionPO.builder()
                .tenantId(entity.getTenantId())
                .ownerUserId(entity.getOwnerUserId())
                .mcpId(entity.getMcpId())
                .versionId(entity.getVersionId())
                .version(entity.getVersion())
                .transportType(entity.getTransportType())
                .endpoint(entity.getEndpoint())
                .command(entity.getCommand())
                .args(entity.getArgs())
                .env(entity.getEnv())
                .toolSchemaJson(entity.getToolSchemaJson())
                .testStatus(entity.getTestStatus())
                .testMessage(entity.getTestMessage())
                .status(entity.getStatus())
                .metadata(entity.getMetadata())
                .build();
        po.setId(entity.getId());
        return po;
    }

    /**
     * 转换 MCP 版本实体；参数是 PO；返回实体。
     */
    private McpVersionEntity toMcpVersionEntity(McpConfigVersionPO po) {
        if (po == null) {
            return null;
        }
        return McpVersionEntity.builder()
                .id(po.getId())
                .tenantId(po.getTenantId())
                .ownerUserId(po.getOwnerUserId())
                .mcpId(po.getMcpId())
                .versionId(po.getVersionId())
                .version(po.getVersion())
                .transportType(po.getTransportType())
                .endpoint(po.getEndpoint())
                .command(po.getCommand())
                .args(po.getArgs())
                .env(po.getEnv())
                .toolSchemaJson(po.getToolSchemaJson())
                .testStatus(po.getTestStatus())
                .testMessage(po.getTestMessage())
                .status(po.getStatus())
                .metadata(po.getMetadata())
                .build();
    }

    /**
     * 转换工具调用日志 PO；参数是实体；返回 PO。
     */
    private ToolCallLogPO toToolCallLogPO(ToolCallLogEntity entity) {
        return ToolCallLogPO.builder()
                .tenantId(entity.getTenantId())
                .userId(entity.getUserId())
                .sessionId(entity.getSessionId())
                .runId(entity.getRunId())
                .workflowId(entity.getWorkflowId())
                .toolType(entity.getToolType())
                .toolId(entity.getToolId())
                .toolName(entity.getToolName())
                .version(entity.getVersion())
                .invocationId(entity.getInvocationId())
                .functionCallId(entity.getFunctionCallId())
                .idempotencyKey(entity.getIdempotencyKey())
                .traceId(entity.getTraceId())
                .inputJson(entity.getInputJson())
                .outputJson(entity.getOutputJson())
                .status(entity.getStatus())
                .startedAt(entity.getStartedAt())
                .errorType(entity.getErrorType())
                .errorMessage(entity.getErrorMessage())
                .costMs(entity.getCostMs())
                .metadata(entity.getMetadata())
                .build();
    }

    /**
     * 转换工具调用日志实体；参数是 PO；返回实体。
     */
    private ToolCallLogEntity toToolCallLogEntity(ToolCallLogPO po) {
        return ToolCallLogEntity.builder()
                .id(po.getId())
                .tenantId(po.getTenantId())
                .userId(po.getUserId())
                .sessionId(po.getSessionId())
                .runId(po.getRunId())
                .workflowId(po.getWorkflowId())
                .toolType(po.getToolType())
                .toolId(po.getToolId())
                .toolName(po.getToolName())
                .version(po.getVersion())
                .invocationId(po.getInvocationId())
                .functionCallId(po.getFunctionCallId())
                .idempotencyKey(po.getIdempotencyKey())
                .traceId(po.getTraceId())
                .inputJson(po.getInputJson())
                .outputJson(po.getOutputJson())
                .status(po.getStatus())
                .startedAt(po.getStartedAt())
                .errorType(po.getErrorType())
                .errorMessage(po.getErrorMessage())
                .costMs(po.getCostMs())
                .metadata(po.getMetadata())
                .createTime(po.getCreateTime())
                .build();
    }

    /**
     * 从 metadata 中提取 sha256；参数是 metadata JSON；返回摘要。
     */
    private String extractSha256(String metadata) {
        if (metadata == null || metadata.isBlank() || !metadata.contains("sha256")) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(metadata);
            JsonNode sha256Node = node.get("sha256");
            if (sha256Node != null && !sha256Node.asText().isBlank()) {
                return sha256Node.asText();
            }
        } catch (Exception ignored) {
            // 兼容早期手写 JSON 或非标准 metadata，下面做一次宽松字符串提取。
        }
        int key = metadata.indexOf("sha256");
        int colon = metadata.indexOf(':', key);
        int start = metadata.indexOf('"', colon);
        int end = metadata.indexOf('"', start + 1);
        if (start < 0 || end <= start + 2) {
            return null;
        }
        return metadata.substring(start + 1, end);
    }

    /**
     * 构建 Skill 包资产 metadata；参数是文件摘要；返回 JSON 文本。
     */
    private String skillAssetMetadata(String sha256) {
        try {
            return objectMapper.writeValueAsString(Map.of("sha256", sha256 == null ? "" : sha256));
        } catch (Exception e) {
            return "{\"sha256\":\"" + (sha256 == null ? "" : sha256) + "\"}";
        }
    }
}
