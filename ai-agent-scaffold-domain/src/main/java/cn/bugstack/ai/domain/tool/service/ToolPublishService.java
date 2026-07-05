package cn.bugstack.ai.domain.tool.service;

import cn.bugstack.ai.domain.storage.model.entity.ObjectStorageCommandEntity;
import cn.bugstack.ai.domain.storage.model.entity.ObjectStorageResultEntity;
import cn.bugstack.ai.domain.storage.service.ObjectStorageService;
import cn.bugstack.ai.domain.tool.adapter.repository.IToolRepository;
import cn.bugstack.ai.domain.tool.model.entity.McpCreateCommandEntity;
import cn.bugstack.ai.domain.tool.model.entity.McpDefinitionEntity;
import cn.bugstack.ai.domain.tool.model.entity.McpVersionEntity;
import cn.bugstack.ai.domain.tool.model.entity.SkillCreateCommandEntity;
import cn.bugstack.ai.domain.tool.model.entity.SkillDefinitionEntity;
import cn.bugstack.ai.domain.tool.model.entity.SkillPackageUploadCommandEntity;
import cn.bugstack.ai.domain.tool.model.entity.SkillPackageUploadResultEntity;
import cn.bugstack.ai.domain.tool.model.entity.SkillVersionCreateCommandEntity;
import cn.bugstack.ai.domain.tool.model.entity.SkillVersionEntity;
import cn.bugstack.ai.domain.tool.model.entity.ToolCallLogEntity;
import cn.bugstack.ai.domain.tool.model.entity.ToolCatalogEntity;
import cn.bugstack.ai.domain.tool.model.entity.ToolUserContextEntity;
import cn.bugstack.ai.domain.tool.model.valobj.ToolStatus;
import cn.bugstack.ai.domain.tool.model.valobj.ToolVisibility;
import cn.bugstack.ai.types.exception.AppException;
import cn.bugstack.ai.types.observability.AiLog;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 工具发布领域服务。
 */
@Service
public class ToolPublishService implements IToolPublishService {

    private static final int MAX_SKILL_PACKAGE_BYTES = 20 * 1024 * 1024;
    private static final String DEFAULT_VERSION = "1.0.0";
    private static final String SOURCE_TYPE_OSS = "oss";

    private final IToolRepository toolRepository;
    private final ObjectStorageService objectStorageService;
    private final McpSseClientSupport mcpSseClientSupport;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 创建工具发布服务；参数是工具仓储和对象存储服务；返回服务实例。
     */
    public ToolPublishService(IToolRepository toolRepository, ObjectStorageService objectStorageService, McpSseClientSupport mcpSseClientSupport) {
        this.toolRepository = toolRepository;
        this.objectStorageService = objectStorageService;
        this.mcpSseClientSupport = mcpSseClientSupport;
    }

    /**
     * 上传 Skill 包；参数是上传命令；返回包资产信息。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public SkillPackageUploadResultEntity uploadSkillPackage(SkillPackageUploadCommandEntity command) {
        ToolUserContextEntity context = requireContext(command == null ? null : command.getContext());
        checkSkillPackage(command);
        String objectKey = "tenants/" + context.getTenantId() + "/skills/packages/" + UUID.randomUUID() + "/" + safeFileName(command.getFileName());
        ObjectStorageResultEntity storageResult = objectStorageService.putObject(ObjectStorageCommandEntity.builder()
                .bucket(objectStorageService.skillBucket())
                .objectKey(objectKey)
                .contentType(defaultString(command.getContentType(), "application/zip"))
                .bytes(command.getBytes())
                .build());
        SkillPackageUploadResultEntity result = SkillPackageUploadResultEntity.builder()
                .assetId("asset_" + UUID.randomUUID())
                .bucket(storageResult.getBucket())
                .objectKey(storageResult.getObjectKey())
                .fileName(safeFileName(command.getFileName()))
                .sha256(storageResult.getSha256())
                .sizeBytes(storageResult.getSizeBytes())
                .build();
        toolRepository.saveSkillAsset(context.getTenantId(), context.getUserId(), result);
        return result;
    }

    /**
     * 创建 Skill 草稿；参数是创建命令；返回 Skill 定义。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public SkillDefinitionEntity createSkill(SkillCreateCommandEntity command) {
        ToolUserContextEntity context = requireContext(command == null ? null : command.getContext());
        String visibility = normalizeVisibility(command.getVisibility(), context);
        SkillPackageUploadResultEntity asset = requireAsset(command.getAssetId());
        String skillId = "skill_" + UUID.randomUUID();
        String version = defaultString(command.getVersion(), DEFAULT_VERSION);
        String versionId = "skill_ver_" + UUID.randomUUID();
        String skillCode = safeToolName(defaultString(command.getSkillCode(), command.getSkillName()));
        byte[] skillBytes = objectStorageService.getObject(asset.getBucket(), asset.getObjectKey());
        fillSha256IfMissing(asset, skillBytes);
        String skillMd = loadSkillMd(skillBytes);
        SkillVersionEntity skillVersion = SkillVersionEntity.builder()
                .tenantId(context.getTenantId())
                .ownerUserId(context.getUserId())
                .skillId(skillId)
                .versionId(versionId)
                .version(version)
                .assetId(asset.getAssetId())
                .bucket(asset.getBucket())
                .objectKey(asset.getObjectKey())
                .fileName(asset.getFileName())
                .sha256(asset.getSha256())
                .sizeBytes(asset.getSizeBytes())
                .manifestJson(toJson(skillManifest(skillMd)))
                .status(ToolStatus.DRAFT)
                .build();
        SkillDefinitionEntity skill = SkillDefinitionEntity.builder()
                .tenantId(context.getTenantId())
                .ownerUserId(context.getUserId())
                .visibility(visibility)
                .skillId(skillId)
                .skillName(defaultString(command.getSkillName(), skillCode))
                .skillCode(skillCode)
                .description(command.getDescription())
                .sourceType(SOURCE_TYPE_OSS)
                .sourceUri(asset.getBucket() + "/" + asset.getObjectKey())
                .version(version)
                .currentVersion(version)
                .status(ToolStatus.DRAFT)
                .build();
        toolRepository.saveSkillDefinition(skill);
        toolRepository.saveSkillVersion(skillVersion);
        return toolRepository.querySkillDefinition(skillId);
    }

    /**
     * 创建 Skill 新版本；参数是版本命令；返回 Skill 定义。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public SkillDefinitionEntity createSkillVersion(SkillVersionCreateCommandEntity command) {
        ToolUserContextEntity context = requireContext(command == null ? null : command.getContext());
        SkillDefinitionEntity skill = requireSkill(command.getSkillId());
        assertOwnerOrTenantAdmin(context, skill.getOwnerUserId(), skill.getVisibility());
        SkillPackageUploadResultEntity asset = requireAsset(command.getAssetId());
        String version = defaultString(command.getVersion(), nextPatchVersion(skill.getCurrentVersion()));
        if (toolRepository.querySkillVersion(skill.getSkillId(), version) != null) {
            throw new AppException("TOOL_SKILL_VERSION_EXISTS", "Skill 版本已存在");
        }
        byte[] skillBytes = objectStorageService.getObject(asset.getBucket(), asset.getObjectKey());
        fillSha256IfMissing(asset, skillBytes);
        String skillMd = loadSkillMd(skillBytes);
        toolRepository.saveSkillVersion(SkillVersionEntity.builder()
                .tenantId(context.getTenantId())
                .ownerUserId(context.getUserId())
                .skillId(skill.getSkillId())
                .versionId("skill_ver_" + UUID.randomUUID())
                .version(version)
                .assetId(asset.getAssetId())
                .bucket(asset.getBucket())
                .objectKey(asset.getObjectKey())
                .fileName(asset.getFileName())
                .sha256(asset.getSha256())
                .sizeBytes(asset.getSizeBytes())
                .manifestJson(toJson(skillManifest(skillMd)))
                .status(ToolStatus.DRAFT)
                .build());
        skill.setCurrentVersion(version);
        skill.setVersion(version);
        toolRepository.updateSkillDefinition(skill);
        return toolRepository.querySkillDefinition(skill.getSkillId());
    }

    /**
     * 发布 Skill；参数是用户上下文、Skill ID 和版本；返回 Skill 定义。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public SkillDefinitionEntity publishSkill(ToolUserContextEntity context, String skillId, String version) {
        context = requireContext(context);
        SkillDefinitionEntity skill = requireSkill(skillId);
        assertOwnerOrTenantAdmin(context, skill.getOwnerUserId(), skill.getVisibility());
        SkillVersionEntity skillVersion = requireSkillVersion(skillId, defaultString(version, skill.getCurrentVersion()));
        skillVersion.setStatus(ToolStatus.ACTIVE);
        skill.setStatus(ToolStatus.ACTIVE);
        skill.setVersion(skillVersion.getVersion());
        skill.setCurrentVersion(skillVersion.getVersion());
        skill.setPublishedVersion(skillVersion.getVersion());
        skill.setActiveVersionId(skillVersion.getVersionId());
        toolRepository.updateSkillVersion(skillVersion);
        toolRepository.updateSkillDefinition(skill);
        AiLog.info(AiLog.tool().skillPublished(context.getTenantId(), context.getUserId(), skillId, skillVersion.getVersion(), skill.getVisibility()));
        return toolRepository.querySkillDefinition(skillId);
    }

    /**
     * 禁用 Skill；参数是用户上下文和 Skill ID；返回 Skill 定义。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public SkillDefinitionEntity disableSkill(ToolUserContextEntity context, String skillId) {
        context = requireContext(context);
        SkillDefinitionEntity skill = requireSkill(skillId);
        assertOwnerOrTenantAdmin(context, skill.getOwnerUserId(), skill.getVisibility());
        skill.setStatus(ToolStatus.DISABLED);
        toolRepository.updateSkillDefinition(skill);
        return toolRepository.querySkillDefinition(skillId);
    }

    /**
     * 查询 Skill 列表；参数是用户上下文和范围；返回 Skill 列表。
     */
    @Override
    public List<SkillDefinitionEntity> querySkills(ToolUserContextEntity context, String scope) {
        context = requireContext(context);
        return toolRepository.querySkillDefinitions(context.getTenantId(), context.getUserId(), defaultString(scope, "available"));
    }

    /**
     * 创建 MCP 草稿；参数是创建命令；返回 MCP 定义。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public McpDefinitionEntity createMcp(McpCreateCommandEntity command) {
        ToolUserContextEntity context = requireContext(command == null ? null : command.getContext());
        String visibility = normalizeVisibility(command.getVisibility(), context);
        checkMcpTransport(context, command.getTransportType());
        String args = normalizeJsonText(command.getArgs(), "args");
        String env = normalizeJsonText(command.getEnv(), "env");
        String mcpId = "mcp_" + UUID.randomUUID();
        String version = defaultString(command.getVersion(), DEFAULT_VERSION);
        String versionId = "mcp_ver_" + UUID.randomUUID();
        McpDefinitionEntity mcp = McpDefinitionEntity.builder()
                .tenantId(context.getTenantId())
                .ownerUserId(context.getUserId())
                .visibility(visibility)
                .mcpId(mcpId)
                .mcpName(command.getMcpName())
                .transportType(command.getTransportType())
                .endpoint(command.getEndpoint())
                .command(command.getCommand())
                .args(args)
                .env(env)
                .description(command.getDescription())
                .currentVersion(version)
                .testStatus(ToolStatus.UNTESTED)
                .status(ToolStatus.DRAFT)
                .build();
        McpVersionEntity mcpVersion = McpVersionEntity.builder()
                .tenantId(context.getTenantId())
                .ownerUserId(context.getUserId())
                .mcpId(mcpId)
                .versionId(versionId)
                .version(version)
                .transportType(command.getTransportType())
                .endpoint(command.getEndpoint())
                .command(command.getCommand())
                .args(args)
                .env(env)
                .testStatus(ToolStatus.UNTESTED)
                .status(ToolStatus.DRAFT)
                .build();
        toolRepository.saveMcpDefinition(mcp);
        toolRepository.saveMcpVersion(mcpVersion);
        return toolRepository.queryMcpDefinition(mcpId);
    }

    /**
     * 测试 MCP；参数是用户上下文和 MCP ID；返回 MCP 定义。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public McpDefinitionEntity testMcp(ToolUserContextEntity context, String mcpId) {
        context = requireContext(context);
        McpDefinitionEntity mcp = requireMcp(mcpId);
        assertOwnerOrTenantAdmin(context, mcp.getOwnerUserId(), mcp.getVisibility());
        McpVersionEntity version = requireMcpVersion(mcpId, mcp.getCurrentVersion());
        try {
            String schema = testRemoteMcp(version);
            String testMessage = mcpTestMessage(version, schema);
            version.setToolSchemaJson(schema);
            version.setTestStatus(ToolStatus.SUCCESS);
            version.setTestMessage(testMessage);
            mcp.setTestStatus(ToolStatus.SUCCESS);
            mcp.setTestMessage(testMessage);
        } catch (Exception e) {
            version.setTestStatus(ToolStatus.FAILED);
            version.setTestMessage(e.getMessage());
            mcp.setTestStatus(ToolStatus.FAILED);
            mcp.setTestMessage(e.getMessage());
        }
        mcp.setLastTestTime(LocalDateTime.now());
        toolRepository.updateMcpVersion(version);
        toolRepository.updateMcpDefinition(mcp);
        return toolRepository.queryMcpDefinition(mcpId);
    }

    /**
     * 构建 MCP 测试结果文案；参数是 MCP 版本和 Schema；返回测试说明。
     */
    private String mcpTestMessage(McpVersionEntity version, String schema) {
        if ("sse".equalsIgnoreCase(version.getTransportType())) {
            return "连接成功，发现 " + mcpSseClientSupport.toolNames(schema).size() + " 个远程工具";
        }
        return "连接成功";
    }

    /**
     * 发布 MCP；参数是用户上下文、MCP ID 和版本；返回 MCP 定义。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public McpDefinitionEntity publishMcp(ToolUserContextEntity context, String mcpId, String version) {
        context = requireContext(context);
        McpDefinitionEntity mcp = requireMcp(mcpId);
        assertOwnerOrTenantAdmin(context, mcp.getOwnerUserId(), mcp.getVisibility());
        McpVersionEntity mcpVersion = requireMcpVersion(mcpId, defaultString(version, mcp.getCurrentVersion()));
        mcpVersion.setStatus(ToolStatus.ACTIVE);
        mcp.setStatus(ToolStatus.ACTIVE);
        mcp.setCurrentVersion(mcpVersion.getVersion());
        mcp.setPublishedVersion(mcpVersion.getVersion());
        mcp.setActiveVersionId(mcpVersion.getVersionId());
        toolRepository.updateMcpVersion(mcpVersion);
        toolRepository.updateMcpDefinition(mcp);
        AiLog.info(AiLog.tool().mcpPublished(context.getTenantId(), context.getUserId(), mcpId, mcpVersion.getVersion(), mcp.getVisibility()));
        return toolRepository.queryMcpDefinition(mcpId);
    }

    /**
     * 禁用 MCP；参数是用户上下文和 MCP ID；返回 MCP 定义。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public McpDefinitionEntity disableMcp(ToolUserContextEntity context, String mcpId) {
        context = requireContext(context);
        McpDefinitionEntity mcp = requireMcp(mcpId);
        assertOwnerOrTenantAdmin(context, mcp.getOwnerUserId(), mcp.getVisibility());
        mcp.setStatus(ToolStatus.DISABLED);
        toolRepository.updateMcpDefinition(mcp);
        return toolRepository.queryMcpDefinition(mcpId);
    }

    /**
     * 查询 MCP 列表；参数是用户上下文和范围；返回 MCP 列表。
     */
    @Override
    public List<McpDefinitionEntity> queryMcps(ToolUserContextEntity context, String scope) {
        context = requireContext(context);
        return toolRepository.queryMcpDefinitions(context.getTenantId(), context.getUserId(), defaultString(scope, "available"));
    }

    /**
     * 查询当前用户工具目录；参数是用户上下文；返回可用工具目录。
     */
    @Override
    public List<ToolCatalogEntity> queryCatalog(ToolUserContextEntity context) {
        context = requireContext(context);
        return toolRepository.queryAvailableTools(context.getTenantId(), context.getUserId());
    }

    /**
     * 查询会话工具调用日志；参数是用户上下文和会话ID；返回调用日志。
     */
    @Override
    public List<ToolCallLogEntity> queryCallLogs(ToolUserContextEntity context, String sessionId) {
        context = requireContext(context);
        return toolRepository.queryToolCallLogs(context.getTenantId(), context.getUserId(), sessionId);
    }

    /**
     * 校验 Skill 包；参数是上传命令；无返回值。
     */
    private void checkSkillPackage(SkillPackageUploadCommandEntity command) {
        if (command == null || command.getBytes() == null || command.getBytes().length == 0) {
            throw new AppException("TOOL_SKILL_PACKAGE_EMPTY", "Skill 包不能为空");
        }
        if (command.getBytes().length > MAX_SKILL_PACKAGE_BYTES) {
            throw new AppException("TOOL_SKILL_PACKAGE_TOO_LARGE", "Skill 包不能超过 20MB");
        }
        loadSkillMd(command.getBytes());
    }

    /**
     * 查询并校验资产；参数是资产ID；返回资产信息。
     */
    private SkillPackageUploadResultEntity requireAsset(String assetId) {
        SkillPackageUploadResultEntity asset = toolRepository.querySkillAsset(assetId);
        if (asset == null) {
            throw new AppException("TOOL_ASSET_NOT_FOUND", "上传包不存在");
        }
        return asset;
    }

    /**
     * 查询并校验 Skill；参数是 Skill ID；返回 Skill 定义。
     */
    private SkillDefinitionEntity requireSkill(String skillId) {
        SkillDefinitionEntity skill = toolRepository.querySkillDefinition(skillId);
        if (skill == null) {
            throw new AppException("TOOL_SKILL_NOT_FOUND", "Skill 不存在");
        }
        return skill;
    }

    /**
     * 查询并校验 Skill 版本；参数是 Skill ID 和版本；返回 Skill 版本。
     */
    private SkillVersionEntity requireSkillVersion(String skillId, String version) {
        SkillVersionEntity skillVersion = toolRepository.querySkillVersion(skillId, version);
        if (skillVersion == null) {
            throw new AppException("TOOL_SKILL_VERSION_NOT_FOUND", "Skill 版本不存在");
        }
        return skillVersion;
    }

    /**
     * 查询并校验 MCP；参数是 MCP ID；返回 MCP 定义。
     */
    private McpDefinitionEntity requireMcp(String mcpId) {
        McpDefinitionEntity mcp = toolRepository.queryMcpDefinition(mcpId);
        if (mcp == null) {
            throw new AppException("TOOL_MCP_NOT_FOUND", "MCP 不存在");
        }
        return mcp;
    }

    /**
     * 查询并校验 MCP 版本；参数是 MCP ID 和版本；返回 MCP 版本。
     */
    private McpVersionEntity requireMcpVersion(String mcpId, String version) {
        McpVersionEntity mcpVersion = toolRepository.queryMcpVersion(mcpId, version);
        if (mcpVersion == null) {
            throw new AppException("TOOL_MCP_VERSION_NOT_FOUND", "MCP 版本不存在");
        }
        return mcpVersion;
    }

    /**
     * 规范化可见范围；参数是可见范围和用户上下文；返回可用范围。
     */
    private String normalizeVisibility(String visibility, ToolUserContextEntity context) {
        String value = defaultString(visibility, ToolVisibility.PRIVATE);
        if (ToolVisibility.TENANT_PUBLIC.equals(value) && !isTenantAdmin(context)) {
            throw new AppException("TOOL_PUBLIC_PERMISSION_DENIED", "只有 owner/admin 可以发布企业公共工具");
        }
        if (!ToolVisibility.PRIVATE.equals(value) && !ToolVisibility.TENANT_PUBLIC.equals(value)) {
            throw new AppException("TOOL_VISIBILITY_INVALID", "工具可见范围不合法");
        }
        return value;
    }

    /**
     * 校验 MCP 传输类型；参数是用户上下文和传输类型；无返回值。
     */
    private void checkMcpTransport(ToolUserContextEntity context, String transportType) {
        if (transportType == null || transportType.isBlank()) {
            throw new AppException("TOOL_MCP_TRANSPORT_INVALID", "MCP 传输类型不能为空");
        }
        String type = transportType.toLowerCase();
        boolean remote = "sse".equals(type) || "http".equals(type);
        boolean adminOnly = "stdio".equals(type) || "local".equals(type);
        if (!remote && !adminOnly) {
            throw new AppException("TOOL_MCP_TRANSPORT_INVALID", "MCP 传输类型不支持");
        }
        if (adminOnly && !isTenantAdmin(context)) {
            throw new AppException("TOOL_MCP_LOCAL_DENIED", "stdio/local MCP 只允许 owner/admin 配置");
        }
    }

    /**
     * 规范化 JSON 文本；参数是原文本和字段名；返回可入库 JSON 或空值。
     */
    private String normalizeJsonText(String jsonText, String fieldName) {
        if (jsonText == null || jsonText.isBlank()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(objectMapper.readTree(jsonText));
        } catch (Exception e) {
            throw new AppException("TOOL_MCP_JSON_INVALID", "MCP " + fieldName + " 必须是合法 JSON");
        }
    }

    /**
     * 校验操作权限；参数是上下文、拥有者和可见范围；无返回值。
     */
    private void assertOwnerOrTenantAdmin(ToolUserContextEntity context, String ownerUserId, String visibility) {
        boolean owner = context.getUserId() != null && context.getUserId().equals(ownerUserId);
        if (!owner && !isTenantAdmin(context)) {
            throw new AppException("TOOL_PERMISSION_DENIED", "无权操作该工具");
        }
        if (ToolVisibility.TENANT_PUBLIC.equals(visibility) && !isTenantAdmin(context)) {
            throw new AppException("TOOL_PUBLIC_PERMISSION_DENIED", "只有 owner/admin 可以操作企业公共工具");
        }
    }

    /**
     * 判断是否租户管理员；参数是用户上下文；返回是否 owner/admin。
     */
    private boolean isTenantAdmin(ToolUserContextEntity context) {
        return "owner".equalsIgnoreCase(context.getRoleCode()) || "admin".equalsIgnoreCase(context.getRoleCode());
    }

    /**
     * 校验上下文；参数是用户上下文；返回用户上下文。
     */
    private ToolUserContextEntity requireContext(ToolUserContextEntity context) {
        if (context == null || context.getTenantId() == null || context.getTenantId().isBlank()
                || context.getUserId() == null || context.getUserId().isBlank()) {
            throw new AppException("TOOL_CONTEXT_INVALID", "工具操作身份不完整");
        }
        return context;
    }

    /**
     * 补齐上传包摘要；参数是上传资产和文件字节；无返回值。
     */
    private void fillSha256IfMissing(SkillPackageUploadResultEntity asset, byte[] bytes) {
        if (asset.getSha256() == null || asset.getSha256().isBlank()) {
            asset.setSha256(sha256(bytes));
        }
    }

    /**
     * 计算 SHA-256；参数是文件字节；返回十六进制摘要。
     */
    private String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (Exception e) {
            throw new AppException("TOOL_SKILL_PACKAGE_INVALID", "Skill 包摘要计算失败：" + e.getMessage(), e);
        }
    }

    /**
     * 从 zip 内容读取 SKILL.md；参数是 zip 字节；返回 Skill 文本。
     */
    private String loadSkillMd(byte[] bytes) {
        try (ZipInputStream zipInputStream = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zipInputStream.getNextEntry()) != null) {
                if (!entry.isDirectory() && entry.getName().endsWith("SKILL.md")) {
                    return new String(zipInputStream.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        } catch (Exception e) {
            throw new AppException("TOOL_SKILL_PACKAGE_INVALID", "Skill 包读取失败：" + e.getMessage());
        }
        throw new AppException("TOOL_SKILL_PACKAGE_INVALID", "Skill 包必须包含 SKILL.md");
    }

    /**
     * 解析 Skill 元信息；参数是 SKILL.md 文本；返回元信息。
     */
    private Map<String, Object> skillManifest(String skillMd) {
        Map<String, Object> manifest = new LinkedHashMap<>();
        if (skillMd == null || !skillMd.startsWith("---")) {
            return manifest;
        }
        String[] parts = skillMd.split("---", 3);
        if (parts.length < 3) {
            return manifest;
        }
        for (String line : parts[1].split("\\R")) {
            int index = line.indexOf(':');
            if (index > 0) {
                manifest.put(line.substring(0, index).trim(), line.substring(index + 1).trim());
            }
        }
        return manifest;
    }

    /**
     * 测试远程 MCP；参数是 MCP 版本；返回工具 Schema 快照。
     */
    private String testRemoteMcp(McpVersionEntity version) throws Exception {
        if (version.getEndpoint() == null || version.getEndpoint().isBlank()) {
            throw new IllegalArgumentException("MCP endpoint 不能为空");
        }
        if ("sse".equalsIgnoreCase(version.getTransportType())) {
            return mcpSseClientSupport.listToolsSchema(version);
        }
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(version.getEndpoint()))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 500) {
            throw new IllegalStateException("MCP 服务返回 " + response.statusCode());
        }
        return toJson(Map.of("endpoint", version.getEndpoint(), "statusCode", response.statusCode(), "bodySample", truncate(response.body(), 512)));
    }

    /**
     * 推进补丁版本；参数是当前版本；返回下一个版本号。
     */
    private String nextPatchVersion(String currentVersion) {
        if (currentVersion == null || currentVersion.isBlank()) {
            return DEFAULT_VERSION;
        }
        String[] parts = currentVersion.split("\\.");
        if (parts.length != 3) {
            return currentVersion + ".1";
        }
        try {
            int patch = Integer.parseInt(parts[2]) + 1;
            return parts[0] + "." + parts[1] + "." + patch;
        } catch (NumberFormatException e) {
            return currentVersion + ".1";
        }
    }

    /**
     * 安全文件名；参数是原始文件名；返回安全文件名。
     */
    private String safeFileName(String fileName) {
        return defaultString(fileName, "skill.zip").replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    /**
     * 安全工具名；参数是原始名称；返回函数名可用编码。
     */
    private String safeToolName(String name) {
        String value = defaultString(name, "skill").replaceAll("[^a-zA-Z0-9_]", "_");
        if (!value.matches("^[a-zA-Z_].*")) {
            value = "skill_" + value;
        }
        return value.length() > 48 ? value.substring(0, 48) : value;
    }

    /**
     * 默认字符串；参数是候选值和默认值；返回非空值。
     */
    private String defaultString(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

    /**
     * 转 JSON；参数是对象；返回 JSON 文本。
     */
    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new AppException("TOOL_JSON_FAILED", "JSON 序列化失败：" + e.getMessage());
        }
    }

    /**
     * 截断文本；参数是文本和最大长度；返回截断后文本。
     */
    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
