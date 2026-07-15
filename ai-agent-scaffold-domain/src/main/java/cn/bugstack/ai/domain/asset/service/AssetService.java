package cn.bugstack.ai.domain.asset.service;

import cn.bugstack.ai.domain.asset.adapter.AssetTextExtractor;
import cn.bugstack.ai.domain.asset.adapter.IAssetRepository;
import cn.bugstack.ai.domain.asset.model.AssetEntity;
import cn.bugstack.ai.domain.asset.model.AssetParseResultEntity;
import cn.bugstack.ai.domain.asset.model.AssetUploadCommandEntity;
import cn.bugstack.ai.domain.session.service.SessionDomain;
import cn.bugstack.ai.domain.storage.model.entity.ObjectStorageCommandEntity;
import cn.bugstack.ai.domain.storage.model.entity.ObjectStorageResultEntity;
import cn.bugstack.ai.domain.storage.service.ObjectStorageService;
import cn.bugstack.ai.types.exception.AppException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 资产领域服务。
 * <p>负责附件上传、可信绑定、访问控制、下载和软删除。</p>
 */
@Service
public class AssetService {

    public static final long MAX_FILE_BYTES = 20L * 1024 * 1024;
    public static final int MAX_ATTACHMENTS_PER_MESSAGE = 10;
    private static final int MAX_FILE_NAME_LENGTH = 255;

    private final IAssetRepository repository;
    private final AssetTextExtractor textExtractor;
    private final ObjectStorageService storageService;
    private final SessionDomain sessionDomain;

    /** 创建资产服务；参数是资产依赖端口；返回服务实例。 */
    public AssetService(IAssetRepository repository, AssetTextExtractor textExtractor,
                        ObjectStorageService storageService, SessionDomain sessionDomain) {
        this.repository = repository;
        this.textExtractor = textExtractor;
        this.storageService = storageService;
        this.sessionDomain = sessionDomain;
    }

    /** 上传聊天附件；参数是可信上传命令；返回资产元数据。 */
    public AssetEntity uploadChatAttachment(AssetUploadCommandEntity command) {
        validateUpload(command);
        String tenantId = blankToNull(command.getTenantId());
        if (!isBlank(command.getSessionId())) {
            sessionDomain.assertSessionAccess(tenantId, command.getOwnerUserId(), command.getSessionId(), null);
        }
        String hash = sha256(command.getBytes());
        AssetEntity reusable = repository.queryReusableByHash(tenantId, command.getOwnerUserId(), hash);
        boolean newlyStored = reusable == null;
        String bucket = newlyStored ? storageService.assetBucket() : reusable.getBucket();
        String objectKey = newlyStored ? objectKey(tenantId, command.getOwnerUserId(), hash, command.getFileName()) : reusable.getObjectKey();
        if (newlyStored) {
            ObjectStorageResultEntity stored = storageService.putObject(ObjectStorageCommandEntity.builder()
                    .bucket(bucket).objectKey(objectKey).bytes(command.getBytes())
                    .contentType(defaultMime(command.getMimeType())).build());
            hash = stored.getSha256();
        }
        AssetParseResultEntity parsed = reusable == null
                ? textExtractor.extract(command.getFileName(), command.getMimeType(), command.getBytes())
                : AssetParseResultEntity.builder().parseStatus(reusable.getParseStatus())
                .extractedText(reusable.getExtractedText()).errorSummary(reusable.getParseError()).build();
        try {
            return repository.insert(AssetEntity.builder()
                    .tenantId(tenantId).ownerUserId(command.getOwnerUserId()).visibility("private")
                    .sessionId(blankToNull(command.getSessionId())).assetId("asset_" + UUID.randomUUID())
                    .assetKind("chat_attachment").assetType(assetType(command.getFileName(), command.getMimeType()))
                    .bucket(bucket).objectKey(objectKey).fileName(safeFileName(command.getFileName()))
                    .mimeType(defaultMime(command.getMimeType())).sizeBytes((long) command.getBytes().length)
                    .sha256(hash).status("active").parseStatus(parsed.getParseStatus())
                    .extractedText(parsed.getExtractedText()).parseError(parsed.getErrorSummary()).build());
        } catch (RuntimeException e) {
            if (newlyStored) {
                try {
                    storageService.deleteObject(bucket, objectKey);
                } catch (RuntimeException ignored) {
                    // 数据库失败优先返回原始异常，孤儿对象后续可由清理任务回收。
                }
            }
            throw e;
        }
    }

    /** 查询当前用户资产；参数是可信身份和分页条件；返回资产列表。 */
    public List<AssetEntity> queryAssets(String tenantId, String ownerUserId, Long cursor, Integer limit,
                                         String sessionId, String assetKind) {
        requireIdentity(ownerUserId);
        // 控制器多取一条判断下一页，因此领域层允许 101 条的内部窗口。
        int safeLimit = limit == null ? 50 : Math.max(1, Math.min(limit, 101));
        if (!isBlank(sessionId)) {
            sessionDomain.assertSessionAccess(blankToNull(tenantId), ownerUserId, sessionId, null);
        }
        return repository.queryOwnedList(blankToNull(tenantId), ownerUserId, cursor, safeLimit,
                blankToNull(sessionId), isBlank(assetKind) ? null : assetKind);
    }

    /** 读取当前用户资产内容；参数是可信身份和资产ID；返回下载内容。 */
    public byte[] download(String tenantId, String ownerUserId, String assetId) {
        AssetEntity asset = requireOwned(tenantId, ownerUserId, assetId);
        return storageService.getObject(asset.getBucket(), asset.getObjectKey(), MAX_FILE_BYTES);
    }

    /** 查询当前用户资产；参数是可信身份和资产ID；返回资产。 */
    public AssetEntity requireOwned(String tenantId, String ownerUserId, String assetId) {
        requireIdentity(ownerUserId);
        AssetEntity asset = repository.queryOwned(blankToNull(tenantId), ownerUserId, assetId);
        if (asset == null || !"active".equals(asset.getStatus())) {
            throw new AppException("ASSET_NOT_FOUND", "资产不存在或无权访问");
        }
        return asset;
    }

    /** 软删除当前用户资产；参数是可信身份和资产ID；无返回值。 */
    public void delete(String tenantId, String ownerUserId, String assetId) {
        requireOwned(tenantId, ownerUserId, assetId);
        if (repository.softDelete(blankToNull(tenantId), ownerUserId, assetId) != 1) {
            throw new AppException("ASSET_DELETE_CONFLICT", "资产删除冲突，请刷新后重试");
        }
    }

    /** 原子绑定本次用户消息附件；参数是可信消息身份和附件ID；无返回值。 */
    @Transactional(rollbackFor = Exception.class)
    public void bindToMessage(String tenantId, String ownerUserId, String sessionId, String messageId,
                              List<String> attachmentIds) {
        List<String> ids = normalizeIds(attachmentIds);
        if (ids.isEmpty()) {
            return;
        }
        if (ids.size() > MAX_ATTACHMENTS_PER_MESSAGE) {
            throw new AppException("ASSET_ATTACHMENT_LIMIT", "单次最多引用 10 个附件");
        }
        sessionDomain.assertSessionAccess(blankToNull(tenantId), ownerUserId, sessionId, null);
        int updated = repository.bindReadyAssets(blankToNull(tenantId), ownerUserId, sessionId, messageId, ids);
        if (updated != ids.size()) {
            throw new AppException("ASSET_BIND_DENIED", "附件不存在、尚未解析完成或不属于当前会话");
        }
    }

    private List<String> normalizeIds(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return ids.stream().filter(id -> id != null && !id.isBlank()).map(String::trim)
                .collect(java.util.stream.Collectors.collectingAndThen(
                        java.util.stream.Collectors.toCollection(LinkedHashSet::new), List::copyOf));
    }

    private void validateUpload(AssetUploadCommandEntity command) {
        if (command == null || isBlank(command.getOwnerUserId()) || command.getBytes() == null
                || command.getBytes().length == 0 || isBlank(command.getFileName())) {
            throw new AppException("ASSET_UPLOAD_INVALID", "附件参数不完整");
        }
        if (command.getBytes().length > MAX_FILE_BYTES) {
            throw new AppException("ASSET_FILE_TOO_LARGE", "单个附件不能超过 20 MiB");
        }
    }

    private void requireIdentity(String ownerUserId) {
        if (isBlank(ownerUserId)) {
            throw new AppException("AUTH_CONTEXT_MISSING", "缺少可信用户身份");
        }
    }

    private String safeFileName(String fileName) {
        String normalized = fileName.replace('\0', '_').replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        String value = (slash < 0 ? normalized : normalized.substring(slash + 1)).trim();
        if (value.isBlank()) value = "attachment";
        return value.length() <= MAX_FILE_NAME_LENGTH ? value : value.substring(value.length() - MAX_FILE_NAME_LENGTH);
    }

    private String objectKey(String tenantId, String userId, String hash, String fileName) {
        String tenantSegment = isBlank(tenantId) ? "personal" : tenantId.replaceAll("[^a-zA-Z0-9_-]", "_");
        String userSegment = userId.replaceAll("[^a-zA-Z0-9_-]", "_");
        String extension = extension(fileName);
        return "assets/" + tenantSegment + "/" + userSegment + "/" + hash.substring(0, 2) + "/" + hash
                + (extension.isEmpty() ? "" : "." + extension);
    }

    private String assetType(String fileName, String mimeType) {
        String mime = defaultMime(mimeType).toLowerCase(Locale.ROOT);
        if (mime.startsWith("image/")) return "image";
        if (mime.equals("application/pdf")) return "pdf";
        if (mime.contains("word") || extension(fileName).equals("docx")) return "word";
        if (mime.startsWith("text/") || extension(fileName).equals("md")) return "text";
        return "file";
    }

    private String extension(String fileName) {
        String safe = safeFileName(fileName);
        int dot = safe.lastIndexOf('.');
        return dot < 0 || dot == safe.length() - 1 ? "" : safe.substring(dot + 1).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private String defaultMime(String mimeType) {
        return isBlank(mimeType) ? "application/octet-stream" : mimeType.trim();
    }

    private String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new AppException("ASSET_HASH_FAILED", "附件摘要计算失败");
        }
    }

    private String blankToNull(String value) {
        return isBlank(value) ? null : value;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
