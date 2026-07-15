package cn.bugstack.ai.domain.share.service;

import cn.bugstack.ai.domain.session.model.entity.ChatMessageEntity;
import cn.bugstack.ai.domain.session.model.entity.ChatSessionEntity;
import cn.bugstack.ai.domain.session.model.entity.CreateSessionCommandEntity;
import cn.bugstack.ai.domain.session.service.SessionDomain;
import cn.bugstack.ai.domain.share.adapter.ISessionShareRepository;
import cn.bugstack.ai.domain.share.model.SessionExportDocument;
import cn.bugstack.ai.domain.share.model.SessionImportEntity;
import cn.bugstack.ai.domain.share.model.SessionShareEntity;
import cn.bugstack.ai.domain.share.model.SessionShareResultEntity;
import cn.bugstack.ai.domain.share.model.SessionToolAccessEntity;
import cn.bugstack.ai.domain.share.model.SessionToolDependencyEntity;
import cn.bugstack.ai.domain.share.model.SessionToolPrecheckEntity;
import cn.bugstack.ai.domain.storage.model.entity.ObjectStorageCommandEntity;
import cn.bugstack.ai.domain.storage.service.ObjectStorageService;
import cn.bugstack.ai.domain.tool.adapter.repository.IToolRepository;
import cn.bugstack.ai.domain.tool.model.entity.ToolCatalogEntity;
import cn.bugstack.ai.types.exception.AppException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.UUID;

/**
 * 会话安全分享与复制导入服务。
 */
@Service
public class SessionShareService {

    public static final String SCHEMA_VERSION = "chat-session-export/v2";
    public static final String LEGACY_SCHEMA_VERSION = "chat-session-export/v1";
    public static final String CONTENT_TYPE = "application/vnd.ai-agent.chat-session+json";
    private static final long MAX_EXPORT_BYTES = 8L * 1024 * 1024;
    private static final int MAX_MESSAGES = 10_000;
    private static final int MAX_MESSAGE_CHARS = 256 * 1024;
    private static final int MAX_TOOL_DEPENDENCIES = 1_000;

    private final SessionDomain sessionDomain;
    private final ISessionShareRepository shareRepository;
    private final ObjectStorageService storageService;
    private final ObjectMapper objectMapper;
    private final IToolRepository toolRepository;
    private final TransactionTemplate transactionTemplate;
    private final SecureRandom secureRandom = new SecureRandom();

    public SessionShareService(SessionDomain sessionDomain, ISessionShareRepository shareRepository,
                               ObjectStorageService storageService, ObjectMapper objectMapper,
                               ObjectProvider<PlatformTransactionManager> transactionManagerProvider,
                               IToolRepository toolRepository) {
        this.sessionDomain = sessionDomain;
        this.shareRepository = shareRepository;
        this.storageService = storageService;
        this.objectMapper = objectMapper;
        this.toolRepository = toolRepository;
        PlatformTransactionManager manager = transactionManagerProvider.getIfAvailable();
        this.transactionTemplate = manager == null ? null : new TransactionTemplate(manager);
    }

    /**
     * 创建分享；参数是可信身份、会话、有效小时和读取上限；返回分享与一次性原令牌。
     */
    public SessionShareResultEntity create(String tenantId, String userId, String sessionId,
                                           Integer validHours, Integer maxDownloads) {
        ChatSessionEntity session = sessionDomain.assertSessionAccess(tenantId, userId, sessionId, null);
        List<ChatMessageEntity> messages = sessionDomain.queryValidMessages(tenantId, userId, sessionId);
        List<SessionToolDependencyEntity> dependencies = toolRepository.queryShareToolDependencies(
                session.getTenantId(), session.getUserId(), session.getSessionId());
        SessionExportDocument document = buildDocument(session, messages, dependencies);
        byte[] bytes = serialize(document);
        checkDocument(document, bytes);
        String shareId = "share_" + UUID.randomUUID();
        String token = randomToken();
        String bucket = storageService.assetBucket();
        String month = DateTimeFormatter.ofPattern("yyyy/MM").format(LocalDateTime.now(ZoneOffset.UTC));
        String objectKey = "chat-shares/" + month + "/" + shareId + ".json";
        storageService.putObject(ObjectStorageCommandEntity.builder().bucket(bucket).objectKey(objectKey)
                .contentType(CONTENT_TYPE).bytes(bytes).build());
        SessionShareEntity share = SessionShareEntity.builder().shareId(shareId).ownerTenantId(tenantId)
                .ownerUserId(userId).sourceSessionId(sessionId).tokenHash(sha256(token.getBytes(StandardCharsets.UTF_8)))
                .bucket(bucket).objectKey(objectKey).schemaVersion(SCHEMA_VERSION).contentSha256(sha256(bytes))
                .sizeBytes((long) bytes.length).messageCount(messages.size()).title(session.getTitle())
                .status("active").expiresAt(LocalDateTime.now().plusHours(normalizeHours(validHours)))
                .maxDownloads(normalizeDownloads(maxDownloads)).downloadCount(0).build();
        try {
            if (shareRepository.insertShare(share) != 1) {
                throw new AppException("CHAT_SHARE_SAVE_FAILED", "分享授权保存失败");
            }
        } catch (RuntimeException e) {
            deleteQuietly(bucket, objectKey);
            throw e;
        }
        return result(share, session, messages, document).toBuilder().token(token).build();
    }

    /**
     * 查询分享预览；参数是原分享令牌；返回不含敏感字段的元数据。
     */
    public SessionShareResultEntity preview(String tenantId, String userId, String token) {
        SessionShareEntity share = resolveActive(token);
        SessionExportDocument document = readDocument(share);
        return result(share, null, null, document).toBuilder()
                .toolPrecheck(precheck(tenantId, userId, document)).build();
    }

    /**
     * 下载分享导出；参数是原分享令牌；返回通过摘要验证的导出字节。
     */
    public SessionShareResultEntity download(String token) {
        SessionShareEntity share = resolveActive(token);
        byte[] bytes = readVerified(share);
        if (shareRepository.consumeAccess(share.getShareId()) != 1) {
            throw new AppException("CHAT_SHARE_LIMIT_REACHED", "分享已失效或读取次数已用完");
        }
        return SessionShareResultEntity.builder().share(share).exportBytes(bytes).build();
    }

    /**
     * 复制导入分享；参数是接收者可信身份和分享令牌；返回独立会话副本。
     */
    public SessionShareResultEntity importCopy(String tenantId, String userId, String token) {
        return importCopy(tenantId, userId, token, false);
    }

    /** 复制导入分享；参数是接收者身份、令牌和风险确认；返回独立会话副本。 */
    public SessionShareResultEntity importCopy(String tenantId, String userId, String token,
                                               boolean confirmToolAccessRisk) {
        // 导入重试必须先定位分享；已完成的接收方导入不应被后续额度耗尽阻断。
        SessionShareEntity share = resolve(token);
        SessionExportDocument document = readDocument(share);
        return inTransaction(() -> importTransactional(tenantId, userId, share, document, confirmToolAccessRisk));
    }

    /**
     * 撤销分享；参数是创建者可信身份和分享ID；无返回值。
     */
    public void revoke(String tenantId, String userId, String shareId) {
        SessionShareEntity share = shareRepository.queryOwnerShare(tenantId, userId, shareId);
        if (share == null) {
            throw new AppException("CHAT_SHARE_NOT_FOUND", "分享不存在或无权访问");
        }
        if (shareRepository.revoke(tenantId, userId, shareId) == 1) {
            deleteQuietly(share.getBucket(), share.getObjectKey());
        }
    }

    private SessionShareResultEntity importTransactional(String tenantId, String userId, SessionShareEntity initial,
                                                          SessionExportDocument document,
                                                          boolean confirmToolAccessRisk) {
        SessionShareEntity share = shareRepository.lockByShareId(initial.getShareId());
        String scopeKey = sha256(((tenantId == null ? "" : tenantId) + ':' + userId).getBytes(StandardCharsets.UTF_8));
        SessionImportEntity existing = shareRepository.queryImport(share.getShareId(), scopeKey);
        if (existing != null) {
            return loadImported(tenantId, userId, existing.getNewSessionId(), share, document);
        }
        assertActive(share);
        SessionToolPrecheckEntity precheck = precheck(tenantId, userId, document);
        if (Boolean.TRUE.equals(precheck.getHasRisk()) && !confirmToolAccessRisk) {
            throw new AppException("SHARE_TOOL_CONFIRM_REQUIRED", "接收方缺少部分会话工具权限，请确认风险后再导入");
        }
        if (shareRepository.consumeAccess(share.getShareId()) != 1) {
            throw new AppException("CHAT_SHARE_LIMIT_REACHED", "分享已失效或读取次数已用完");
        }
        String sessionId = "import_" + UUID.randomUUID();
        CreateSessionCommandEntity command = new CreateSessionCommandEntity();
        command.setTenantId(tenantId); command.setUserId(userId); command.setSessionId(sessionId);
        command.setAgentId(document.getSession().getAgentId()); command.setAgentName(document.getSession().getAgentName());
        command.setSourceType(normalizeSourceType(document.getSession().getSourceType()));
        command.setWorkflowVersion(document.getSession().getWorkflowVersion());
        command.setModelCode(document.getSession().getModelCode());
        command.setAppName(document.getSession().getAppName()); command.setTitle(document.getSession().getTitle());
        ChatSessionEntity session = sessionDomain.createSession(command);
        for (SessionExportDocument.Message message : document.getMessages()) {
            if (SessionDomain.ROLE_USER.equals(message.getRole())) {
                sessionDomain.appendUserMessage(tenantId, userId, sessionId, message.getContent(), null);
            } else if (SessionDomain.ROLE_ASSISTANT.equals(message.getRole())) {
                sessionDomain.appendAssistantMessage(tenantId, userId, sessionId, message.getContent(), null);
            }
        }
        SessionImportEntity sessionImport = SessionImportEntity.builder().importId("import_record_" + UUID.randomUUID())
                .shareId(share.getShareId()).recipientScopeKey(scopeKey).tenantId(tenantId).userId(userId)
                .sourceSha256(share.getContentSha256()).newSessionId(sessionId).status("completed").build();
        if (shareRepository.insertImport(sessionImport) != 1) {
            throw new AppException("CHAT_SHARE_IMPORT_SAVE_FAILED", "会话导入记录保存失败");
        }
        return result(share, session, sessionDomain.queryValidMessages(tenantId, userId, sessionId), document)
                .toBuilder().toolPrecheck(precheck(tenantId, userId, document)).build();
    }

    private SessionShareResultEntity loadImported(String tenantId, String userId, String sessionId,
                                                  SessionShareEntity share, SessionExportDocument document) {
        ChatSessionEntity session = sessionDomain.assertSessionAccess(tenantId, userId, sessionId, null);
        return result(share, session, sessionDomain.queryValidMessages(tenantId, userId, sessionId), document)
                .toBuilder().toolPrecheck(precheck(tenantId, userId, document)).build();
    }

    private SessionExportDocument buildDocument(ChatSessionEntity session, List<ChatMessageEntity> messages,
                                                List<SessionToolDependencyEntity> dependencies) {
        List<SessionExportDocument.Message> exports = messages.stream().map(message -> SessionExportDocument.Message.builder()
                .sequenceNo(message.getSequenceNo()).role(message.getRole()).contentType(message.getContentType())
                .content(message.getContent()).createdAt(message.getCreateTime()).build()).toList();
        String sourceType = normalizeSourceType(session.getSourceType());
        return SessionExportDocument.builder().schemaVersion(SCHEMA_VERSION).exportedAt(LocalDateTime.now())
                .session(SessionExportDocument.Session.builder().title(session.getTitle()).agentId(session.getAgentId())
                        .agentName(session.getAgentName()).appName(session.getAppName()).sourceType(sourceType)
                        .workflowId("workflow".equals(sourceType) ? session.getAgentId() : null)
                        .workflowVersion(session.getWorkflowVersion()).modelCode(session.getModelCode()).build())
                .messages(exports).toolDependencies(dependencies == null ? List.of() : dependencies).build();
    }

    private SessionShareEntity resolveActive(String token) {
        SessionShareEntity share = resolve(token);
        assertActive(share);
        return share;
    }

    private SessionShareEntity resolve(String token) {
        if (token == null || token.length() < 32 || token.length() > 256) {
            throw new AppException("CHAT_SHARE_TOKEN_INVALID", "分享令牌不合法");
        }
        SessionShareEntity share = shareRepository.queryByTokenHash(sha256(token.getBytes(StandardCharsets.UTF_8)));
        if (share == null) {
            throw new AppException("CHAT_SHARE_NOT_FOUND", "分享不存在");
        }
        return share;
    }

    private void assertActive(SessionShareEntity share) {
        if (share == null) throw new AppException("CHAT_SHARE_NOT_FOUND", "分享不存在");
        if (!"active".equals(share.getStatus()) || !share.getExpiresAt().isAfter(LocalDateTime.now()))
            throw new AppException("CHAT_SHARE_EXPIRED", "分享已撤销或过期");
        if (share.getDownloadCount() >= share.getMaxDownloads())
            throw new AppException("CHAT_SHARE_LIMIT_REACHED", "分享读取次数已用完");
    }

    private byte[] readVerified(SessionShareEntity share) {
        byte[] bytes = storageService.getObject(share.getBucket(), share.getObjectKey(), MAX_EXPORT_BYTES);
        if (!share.getContentSha256().equals(sha256(bytes))) {
            throw new AppException("CHAT_SHARE_TAMPERED", "分享文件校验失败");
        }
        return bytes;
    }

    private void checkDocument(SessionExportDocument document, byte[] bytes) {
        if (document == null || (!SCHEMA_VERSION.equals(document.getSchemaVersion())
                && !LEGACY_SCHEMA_VERSION.equals(document.getSchemaVersion())) || document.getSession() == null
                || document.getMessages() == null) throw new AppException("CHAT_SHARE_SCHEMA_INVALID", "分享文件协议不兼容");
        if (bytes.length > MAX_EXPORT_BYTES || document.getMessages().size() > MAX_MESSAGES)
            throw new AppException("CHAT_SHARE_TOO_LARGE", "分享文件超过限制");
        for (SessionExportDocument.Message message : document.getMessages()) {
            if (message == null || (!SessionDomain.ROLE_USER.equals(message.getRole())
                    && !SessionDomain.ROLE_ASSISTANT.equals(message.getRole()))
                    || !"text".equals(message.getContentType()) || message.getContent() == null
                    || message.getContent().length() > MAX_MESSAGE_CHARS)
                throw new AppException("CHAT_SHARE_MESSAGE_INVALID", "分享文件包含不支持的消息");
        }
        List<SessionToolDependencyEntity> dependencies = dependencies(document);
        if (dependencies.size() > MAX_TOOL_DEPENDENCIES) {
            throw new AppException("CHAT_SHARE_TOOL_LIMIT", "分享文件工具依赖超过限制");
        }
        for (SessionToolDependencyEntity dependency : dependencies) {
            if (dependency == null || blank(dependency.getToolType()) || blank(dependency.getToolId())
                    || dependency.getToolId().length() > 128 || dependency.getToolType().length() > 32) {
                throw new AppException("CHAT_SHARE_TOOL_INVALID", "分享文件包含不合法工具依赖");
            }
        }
    }

    private SessionExportDocument readDocument(SessionShareEntity share) {
        byte[] bytes = readVerified(share);
        SessionExportDocument document = deserialize(bytes);
        checkDocument(document, bytes);
        return document;
    }

    private SessionShareResultEntity result(SessionShareEntity share, ChatSessionEntity session,
                                            List<ChatMessageEntity> messages, SessionExportDocument document) {
        SessionExportDocument.Session source = document.getSession();
        String sourceType = normalizeSourceType(source.getSourceType());
        return SessionShareResultEntity.builder().share(share).session(session).messages(messages)
                .sourceType(sourceType).workflowId(blank(source.getWorkflowId()) && "workflow".equals(sourceType)
                        ? source.getAgentId() : source.getWorkflowId())
                .workflowVersion(source.getWorkflowVersion()).modelCode(source.getModelCode())
                .sourceAgentId(source.getAgentId()).sourceAgentName(source.getAgentName())
                .sourceAppName(source.getAppName())
                .toolDependencies(dependencies(document))
                .legacySnapshot(LEGACY_SCHEMA_VERSION.equals(document.getSchemaVersion())).build();
    }

    private SessionToolPrecheckEntity precheck(String tenantId, String userId, SessionExportDocument document) {
        List<SessionToolDependencyEntity> dependencies = dependencies(document);
        Map<String, ToolCatalogEntity> available = new LinkedHashMap<>();
        if (!blank(tenantId) && !blank(userId)) {
            for (ToolCatalogEntity item : toolRepository.queryAvailableTools(tenantId, userId)) {
                available.put(toolKey(item.getToolType(), item.getToolId()), item);
            }
        }
        List<SessionToolAccessEntity> items = dependencies.stream().map(dependency -> {
            ToolCatalogEntity catalog = available.get(toolKey(dependency.getToolType(), dependency.getToolId()));
            boolean versionMatch = catalog != null && (blank(dependency.getVersion())
                    || dependency.getVersion().equals(catalog.getVersion()));
            String access = versionMatch ? "available" : "missing";
            String reason = catalog == null ? "接收方无此工具权限或工具未发布"
                    : (versionMatch ? null : "接收方缺少分享所用工具版本");
            return SessionToolAccessEntity.builder().toolType(dependency.getToolType()).toolId(dependency.getToolId())
                    .toolName(dependency.getToolName()).version(dependency.getVersion()).source(dependency.getSource())
                    .access(access).reason(reason).build();
        }).toList();
        int availableCount = (int) items.stream().filter(item -> "available".equals(item.getAccess())).count();
        int missingCount = items.size() - availableCount;
        return SessionToolPrecheckEntity.builder().hasRisk(missingCount > 0).availableCount(availableCount)
                .missingCount(missingCount).deniedCount(0).items(items).build();
    }

    private List<SessionToolDependencyEntity> dependencies(SessionExportDocument document) {
        return document.getToolDependencies() == null ? List.of() : List.copyOf(document.getToolDependencies());
    }

    private String toolKey(String toolType, String toolId) {
        return (toolType == null ? "" : toolType.toLowerCase()) + ':' + toolId;
    }

    private boolean blank(String value) { return value == null || value.isBlank(); }

    private String normalizeSourceType(String sourceType) {
        return "workflow".equals(sourceType) ? "workflow" : "agent";
    }

    private byte[] serialize(SessionExportDocument document) {
        try { return objectMapper.writeValueAsBytes(document); }
        catch (Exception e) { throw new AppException("CHAT_SHARE_SERIALIZE_FAILED", "会话导出失败", e); }
    }

    private SessionExportDocument deserialize(byte[] bytes) {
        try { return objectMapper.readValue(bytes, SessionExportDocument.class); }
        catch (Exception e) { throw new AppException("CHAT_SHARE_SCHEMA_INVALID", "分享文件无法解析", e); }
    }

    private int normalizeHours(Integer hours) { return hours == null ? 72 : Math.max(1, Math.min(hours, 720)); }
    private int normalizeDownloads(Integer count) { return count == null ? 20 : Math.max(1, Math.min(count, 1000)); }
    private String randomToken() { byte[] bytes=new byte[32]; secureRandom.nextBytes(bytes); return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    private String sha256(byte[] bytes) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); } catch(Exception e) { throw new AppException("CHAT_SHARE_HASH_FAILED", "摘要计算失败", e); } }
    private <T> T inTransaction(java.util.function.Supplier<T> action) { return transactionTemplate == null ? action.get() : transactionTemplate.execute(status -> action.get()); }
    private void deleteQuietly(String bucket,String objectKey) { try { storageService.deleteObject(bucket,objectKey); } catch(Exception ignored) { } }
}
