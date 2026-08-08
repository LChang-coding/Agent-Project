package cn.bugstack.ai.domain.tool.service;

import cn.bugstack.ai.domain.storage.model.entity.ObjectStorageCommandEntity;
import cn.bugstack.ai.domain.storage.model.entity.ObjectStorageResultEntity;
import cn.bugstack.ai.domain.storage.service.ObjectStorageService;
import cn.bugstack.ai.domain.tool.adapter.repository.IToolRepository;
import cn.bugstack.ai.domain.tool.model.entity.McpConnectionConfigEntity;
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
import cn.bugstack.ai.domain.tool.service.mcp.McpProtocolClientSupport;
import cn.bugstack.ai.domain.tool.service.support.SkillPackageReader;
import cn.bugstack.ai.types.exception.AppException;
import cn.bugstack.ai.types.observability.AiLog;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 管理 Skill/MCP 的草稿、不可变版本、测试、发布和停用生命周期。 */
@Service
public class ToolPublishService implements IToolPublishService {

    /** 上传入口总包大小上限；包内条目另由 SkillPackageReader 限制。 */
    private static final int MAX_SKILL_PACKAGE_BYTES = 20 * 1024 * 1024;
    /** 未显式指定时的首版版本号。 */
    private static final String DEFAULT_VERSION = "1.0.0";
    /** Skill 包固定存放对象存储。 */
    private static final String SOURCE_TYPE_OSS = "oss";

    /** 持久化定义、版本、资产和调用目录。 */
    private final IToolRepository toolRepository;
    /** 保存和读取原始 Skill ZIP。 */
    private final ObjectStorageService objectStorageService;
    /** 验证 MCP 配置并发现远程工具 schema。 */
    private final McpProtocolClientSupport mcpProtocolClientSupport;
    /** 在发布前验证 ZIP 和 SKILL.md。 */
    private final SkillPackageReader skillPackageReader;
    /** 校验并规范化 Skill 与 MCP 版本配置 JSON。 */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 创建工具发布服务。
     */
    public ToolPublishService(IToolRepository toolRepository, ObjectStorageService objectStorageService,
                              McpProtocolClientSupport mcpProtocolClientSupport,
                              SkillPackageReader skillPackageReader) {
        this.toolRepository = toolRepository;
        this.objectStorageService = objectStorageService;
        this.mcpProtocolClientSupport = mcpProtocolClientSupport;
        this.skillPackageReader = skillPackageReader;
    }

    /** 校验 ZIP 后写对象存储并登记资产；尚不创建 Skill 定义。 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public SkillPackageUploadResultEntity uploadSkillPackage(SkillPackageUploadCommandEntity command) {
        ToolUserContextEntity context = requireContext(command == null ? null : command.getContext());
        checkSkillPackage(command);
        // 对象键包含租户和随机目录，阻止不同上传同名覆盖。
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

    /** 从已登记资产同时创建定义和首个草稿版本。 */
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
        // 再次读取并解析资产，防止仅凭上传登记创建无效版本。
        byte[] skillBytes = objectStorageService.getObject(asset.getBucket(), asset.getObjectKey());
        fillSha256IfMissing(asset, skillBytes);
        String skillMd = skillPackageReader.readSkillMd(skillBytes);
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

    /** 创建不可变草稿版本并只推进 currentVersion，不影响 publishedVersion。 */
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
        String skillMd = skillPackageReader.readSkillMd(skillBytes);
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

    /** 激活指定版本并原子推进定义的发布指针。 */
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

    /** 停用定义使其退出运行目录；历史版本和调用审计保留。 */
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
     * 查询 Skill 列表。
     */
    @Override
    public List<SkillDefinitionEntity> querySkills(ToolUserContextEntity context, String scope) {
        context = requireContext(context);
        return toolRepository.querySkillDefinitions(context.getTenantId(), context.getUserId(), defaultString(scope, "available"));
    }

    /** 校验传输配置后同时创建 MCP 定义与首个未测试版本。 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public McpDefinitionEntity createMcp(McpCreateCommandEntity command) {
        ToolUserContextEntity context = requireContext(command == null ? null : command.getContext());
        String visibility = normalizeVisibility(command.getVisibility(), context);
        checkMcpTransport(context, command.getTransportType());
        String args = normalizeJsonText(command.getArgs(), "args");
        String env = normalizeJsonText(command.getEnv(), "env");
        validateMcpConnection(command.getTransportType(), command.getEndpoint(), command.getCommand(), args, env);
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

    /** 对 currentVersion 建连并保存工具 schema；失败状态同样持久化。 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public McpDefinitionEntity testMcp(ToolUserContextEntity context, String mcpId) {
        context = requireContext(context);
        McpDefinitionEntity mcp = requireMcp(mcpId);
        assertOwnerOrTenantAdmin(context, mcp.getOwnerUserId(), mcp.getVisibility());
        McpVersionEntity version = requireMcpVersion(mcpId, mcp.getCurrentVersion());
        try {
            // 标准 MCP 返回完整工具 schema；旧 HTTP 只保存连通性快照。
            String schema = testRemoteMcp(version);
            String testMessage = mcpTestMessage(version, schema);
            version.setToolSchemaJson(schema);
            version.setTestStatus(ToolStatus.SUCCESS);
            version.setTestMessage(testMessage);
            mcp.setTestStatus(ToolStatus.SUCCESS);
            mcp.setTestMessage(testMessage);
        } catch (Exception e) {
            // 测试失败是领域结果，不回滚为“未测试”。
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
     * 构建 MCP 测试结果文案。
     */
    private String mcpTestMessage(McpVersionEntity version, String schema) {
        if ("sse".equalsIgnoreCase(version.getTransportType()) || "stdio".equalsIgnoreCase(version.getTransportType())) {
            return "连接成功，发现 " + mcpProtocolClientSupport.toolNames(schema).size() + " 个远程工具";
        }
        return "连接成功";
    }

    /** 只有测试成功的版本可激活并进入运行目录。 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public McpDefinitionEntity publishMcp(ToolUserContextEntity context, String mcpId, String version) {
        context = requireContext(context);
        McpDefinitionEntity mcp = requireMcp(mcpId);
        assertOwnerOrTenantAdmin(context, mcp.getOwnerUserId(), mcp.getVisibility());
        McpVersionEntity mcpVersion = requireMcpVersion(mcpId, defaultString(version, mcp.getCurrentVersion()));
        if (!ToolStatus.SUCCESS.equals(mcpVersion.getTestStatus())) {
            throw new AppException("TOOL_MCP_TEST_NOT_PASSED", "MCP 必须测试通过后才能发布");
        }
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
     * 禁用 MCP。
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
     * 查询 MCP 列表。
     */
    @Override
    public List<McpDefinitionEntity> queryMcps(ToolUserContextEntity context, String scope) {
        context = requireContext(context);
        return toolRepository.queryMcpDefinitions(context.getTenantId(), context.getUserId(), defaultString(scope, "available"));
    }

    /**
     * 查询当前用户工具目录。
     */
    @Override
    public List<ToolCatalogEntity> queryCatalog(ToolUserContextEntity context) {
        context = requireContext(context);
        return toolRepository.queryAvailableTools(context.getTenantId(), context.getUserId());
    }

    /**
     * 查询会话工具调用日志。
     */
    @Override
    public List<ToolCallLogEntity> queryCallLogs(ToolUserContextEntity context, String sessionId) {
        context = requireContext(context);
        return toolRepository.queryToolCallLogs(context.getTenantId(), context.getUserId(), sessionId);
    }

    /** 先检查总大小，再完整解析 SKILL.md 验证包结构。 */
    private void checkSkillPackage(SkillPackageUploadCommandEntity command) {
        if (command == null || command.getBytes() == null || command.getBytes().length == 0) {
            throw new AppException("TOOL_SKILL_PACKAGE_EMPTY", "Skill 包不能为空");
        }
        if (command.getBytes().length > MAX_SKILL_PACKAGE_BYTES) {
            throw new AppException("TOOL_SKILL_PACKAGE_TOO_LARGE", "Skill 包不能超过 20MB");
        }
        skillPackageReader.readSkillMd(command.getBytes());
    }

    /**
     * 查询并校验资产。
     */
    private SkillPackageUploadResultEntity requireAsset(String assetId) {
        SkillPackageUploadResultEntity asset = toolRepository.querySkillAsset(assetId);
        if (asset == null) {
            throw new AppException("TOOL_ASSET_NOT_FOUND", "上传包不存在");
        }
        return asset;
    }

    /**
     * 查询并校验 Skill。
     */
    private SkillDefinitionEntity requireSkill(String skillId) {
        SkillDefinitionEntity skill = toolRepository.querySkillDefinition(skillId);
        if (skill == null) {
            throw new AppException("TOOL_SKILL_NOT_FOUND", "Skill 不存在");
        }
        return skill;
    }

    /**
     * 查询并校验 Skill 版本。
     */
    private SkillVersionEntity requireSkillVersion(String skillId, String version) {
        SkillVersionEntity skillVersion = toolRepository.querySkillVersion(skillId, version);
        if (skillVersion == null) {
            throw new AppException("TOOL_SKILL_VERSION_NOT_FOUND", "Skill 版本不存在");
        }
        return skillVersion;
    }

    /**
     * 查询并校验 MCP。
     */
    private McpDefinitionEntity requireMcp(String mcpId) {
        McpDefinitionEntity mcp = toolRepository.queryMcpDefinition(mcpId);
        if (mcp == null) {
            throw new AppException("TOOL_MCP_NOT_FOUND", "MCP 不存在");
        }
        return mcp;
    }

    /**
     * 查询并校验 MCP 版本。
     */
    private McpVersionEntity requireMcpVersion(String mcpId, String version) {
        McpVersionEntity mcpVersion = toolRepository.queryMcpVersion(mcpId, version);
        if (mcpVersion == null) {
            throw new AppException("TOOL_MCP_VERSION_NOT_FOUND", "MCP 版本不存在");
        }
        return mcpVersion;
    }

    /** 默认私有；租户公开只能由 owner/admin 创建。 */
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

    /** SSE/HTTP 可远程配置；能启动本机进程的 Stdio/local 只允许管理员。 */
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
     * 规范化 JSON 文本。
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

    /** 私有工具允许所有者或管理员；租户公共工具只允许管理员。 */
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
     * 判断是否租户管理员。
     */
    private boolean isTenantAdmin(ToolUserContextEntity context) {
        return "owner".equalsIgnoreCase(context.getRoleCode()) || "admin".equalsIgnoreCase(context.getRoleCode());
    }

    /**
     * 校验上下文。
     */
    private ToolUserContextEntity requireContext(ToolUserContextEntity context) {
        if (context == null || context.getTenantId() == null || context.getTenantId().isBlank()
                || context.getUserId() == null || context.getUserId().isBlank()) {
            throw new AppException("TOOL_CONTEXT_INVALID", "工具操作身份不完整");
        }
        return context;
    }

    /**
     * 补齐上传包摘要。
     */
    private void fillSha256IfMissing(SkillPackageUploadResultEntity asset, byte[] bytes) {
        if (asset.getSha256() == null || asset.getSha256().isBlank()) {
            asset.setSha256(sha256(bytes));
        }
    }

    /**
     * 计算 SHA-256。
     */
    private String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (Exception e) {
            throw new AppException("TOOL_SKILL_PACKAGE_INVALID", "Skill 包摘要计算失败：" + e.getMessage(), e);
        }
    }

    /** 只提取 Markdown front matter 的一级键值，不解释正文指令。 */
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

    /** SSE/Stdio 执行标准 initialize/listTools；旧 HTTP 只做 GET 探活。 */
    private String testRemoteMcp(McpVersionEntity version) throws Exception {
        if (!"stdio".equalsIgnoreCase(version.getTransportType())
                && (version.getEndpoint() == null || version.getEndpoint().isBlank())) {
            throw new IllegalArgumentException("MCP endpoint 不能为空");
        }
        if ("sse".equalsIgnoreCase(version.getTransportType()) || "stdio".equalsIgnoreCase(version.getTransportType())) {
            return mcpProtocolClientSupport.listToolsSchema(version);
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
     * 校验 MCP 运行连接配置。
     */
    private void validateMcpConnection(String transportType, String endpoint, String command, String args, String env) {
        if (!"stdio".equalsIgnoreCase(transportType)) {
            return;
        }
        mcpProtocolClientSupport.validate(McpConnectionConfigEntity.builder()
                .transportType(transportType)
                .endpoint(endpoint)
                .command(command)
                .args(args)
                .env(env)
                .build());
    }

    /**
     * 推进补丁版本。
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
     * 安全文件名。
     */
    private String safeFileName(String fileName) {
        return defaultString(fileName, "skill.zip").replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    /**
     * 安全工具名。
     */
    private String safeToolName(String name) {
        String value = defaultString(name, "skill").replaceAll("[^a-zA-Z0-9_]", "_");
        if (!value.matches("^[a-zA-Z_].*")) {
            value = "skill_" + value;
        }
        return value.length() > 48 ? value.substring(0, 48) : value;
    }

    /**
     * 默认字符串。
     */
    private String defaultString(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

    /**
     * 转 JSON。
     */
    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new AppException("TOOL_JSON_FAILED", "JSON 序列化失败：" + e.getMessage());
        }
    }

    /**
     * 截断文本。
     */
    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
