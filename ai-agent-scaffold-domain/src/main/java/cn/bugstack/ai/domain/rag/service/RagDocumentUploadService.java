package cn.bugstack.ai.domain.rag.service;

import cn.bugstack.ai.domain.rag.adapter.repository.IRagRepository;
import cn.bugstack.ai.domain.rag.adapter.repository.RagUploadRegistrationPort;
import cn.bugstack.ai.domain.rag.model.entity.RagDocumentEntity;
import cn.bugstack.ai.domain.rag.model.entity.RagDocumentUploadCommand;
import cn.bugstack.ai.domain.rag.model.entity.RagDocumentUploadResult;
import cn.bugstack.ai.domain.rag.model.entity.RagDocumentVersionEntity;
import cn.bugstack.ai.domain.rag.model.entity.RagIngestJobEntity;
import cn.bugstack.ai.domain.rag.model.entity.RagKnowledgeBaseEntity;
import cn.bugstack.ai.domain.rag.model.entity.RagUploadRegistration;
import cn.bugstack.ai.domain.rag.model.valobj.RagDocumentStatus;
import cn.bugstack.ai.domain.rag.model.valobj.RagDocumentVersionStatus;
import cn.bugstack.ai.domain.rag.model.valobj.RagIngestOperation;
import cn.bugstack.ai.domain.rag.model.valobj.RagObjectStorageScope;
import cn.bugstack.ai.domain.rag.model.valobj.RagValidatedUploadFile;
import cn.bugstack.ai.domain.rag.model.valobj.RagVisibility;
import cn.bugstack.ai.domain.storage.model.entity.ObjectStorageFileCommandEntity;
import cn.bugstack.ai.domain.storage.model.entity.ObjectStorageResultEntity;
import cn.bugstack.ai.domain.storage.service.ObjectStorageService;
import cn.bugstack.ai.types.exception.AppException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/** RAG 文档上传用例：安全校验、流式存储、幂等登记和失败补偿。 */
@Service
public class RagDocumentUploadService {

    /** 摄取失败默认最多自动尝试三次。 */
    private static final int DEFAULT_MAX_ATTEMPTS = 3;
    /** 查询知识库和既有幂等任务。 */
    private final IRagRepository repository;
    /** 原子登记文档、版本、任务与 Outbox。 */
    private final RagUploadRegistrationPort registrationPort;
    /** 原文件先落对象存储，再登记数据库状态。 */
    private final ObjectStorageService objectStorageService;
    /** 上传文件的格式与安全边界策略。 */
    private final RagUploadFilePolicy filePolicy = new RagUploadFilePolicy();
    /** 上传仅允许知识库所属租户管理员发起。 */
    private final RagKnowledgeBaseAuthorizationService authorizationService =
            new RagKnowledgeBaseAuthorizationService();

    /** 注入仓储、原子登记端口和对象存储。 */
    public RagDocumentUploadService(IRagRepository repository,
                                    RagUploadRegistrationPort registrationPort,
                                    ObjectStorageService objectStorageService) {
        this.repository = repository;
        this.registrationPort = registrationPort;
        this.objectStorageService = objectStorageService;
    }

    /** 上传并受理一个新逻辑文档。 */
    public RagDocumentUploadResult upload(RagDocumentUploadCommand command) {
        if (command == null) throw new AppException("RAG_UPLOAD_INVALID", "上传命令不能为空");
        RagKnowledgeBaseEntity knowledgeBase = repository.findKnowledgeBase(command.tenantId(),
                        command.knowledgeBaseId())
                .orElseThrow(() -> new AppException("RAG_KNOWLEDGE_BASE_NOT_FOUND", "知识库不存在或无权访问"));
        authorizationService.requireManageable(command.tenantId(), command.userId(), command.roleCode(), knowledgeBase);
        if (!knowledgeBase.status().searchable()) {
            throw new AppException("RAG_KNOWLEDGE_BASE_UNAVAILABLE", "知识库当前不能接收新文档");
        }
        RagValidatedUploadFile file = filePolicy.validate(command.file());

        String documentId = id("doc");
        String versionId = id("ragver");
        String taskId = id("ragtask");
        String eventId = id("ragevt");
        long generation = Math.max(1L, knowledgeBase.currentGeneration());
        String objectKey = RagObjectStorageScope.sourceObjectKey(command.tenantId(), command.knowledgeBaseId(), documentId,
                versionId, file.safeFileName());
        ObjectStorageResultEntity stored = objectStorageService.putFile(ObjectStorageFileCommandEntity.builder()
                .bucket(objectStorageService.ragBucket()).objectKey(objectKey).sourcePath(file.path())
                .sizeBytes(file.sizeBytes()).contentType(file.mimeType()).build());
        String idempotencyKey = sha256("ingest\n" + command.tenantId() + "\n"
                + command.knowledgeBaseId() + "\n" + stored.getSha256());
        try {
            var existing = repository.findIngestJobByIdempotencyKey(command.tenantId(), idempotencyKey);
            if (existing.isPresent()) {
                compensate(stored);
                return existingResult(existing.get(), file, true);
            }
            RagDocumentEntity document = new RagDocumentEntity(command.tenantId(), command.userId(),
                    RagVisibility.TENANT, command.knowledgeBaseId(), documentId, file.safeFileName(),
                    null, 0L, generation, RagDocumentStatus.PROCESSING, 0L);
            RagDocumentVersionEntity version = new RagDocumentVersionEntity(command.tenantId(),
                    command.knowledgeBaseId(), documentId, versionId, 1, generation, stored.getBucket(),
                    stored.getObjectKey(), null, null, file.safeFileName(), stored.getSha256(), file.mimeType(),
                    stored.getSizeBytes(), RagDocumentVersionStatus.QUEUED, null, null, null, 0L);
            RagIngestJobEntity job = RagIngestJobEntity.pending(command.tenantId(), command.knowledgeBaseId(),
                    documentId, versionId, taskId, idempotencyKey, RagIngestOperation.INGEST,
                    generation, DEFAULT_MAX_ATTEMPTS);
            boolean inserted = registrationPort.register(command.tenantId(),
                    new RagUploadRegistration(document, version, job, eventId));
            if (!inserted) {
                compensate(stored);
                RagIngestJobEntity winner = repository.findIngestJobByIdempotencyKey(command.tenantId(),
                                idempotencyKey)
                        .orElseThrow(() -> new AppException("RAG_UPLOAD_CONCURRENT_RESULT_MISSING",
                                "并发上传已受理但任务暂不可见，请稍后查询"));
                return existingResult(winner, file, true);
            }
            return new RagDocumentUploadResult(documentId, versionId, taskId, file.safeFileName(),
                    file.sizeBytes(), "queued", false);
        } catch (RuntimeException error) {
            compensate(stored);
            throw error;
        }
    }

    /** 将已有幂等任务映射成上传响应，不重复创建文档。 */
    private RagDocumentUploadResult existingResult(RagIngestJobEntity job, RagValidatedUploadFile file,
                                                    boolean deduplicated) {
        return new RagDocumentUploadResult(job.documentId(), job.versionId(), job.jobId(), file.safeFileName(),
                file.sizeBytes(), job.status().name().toLowerCase(java.util.Locale.ROOT), deduplicated);
    }

    /** 数据库未受理时删除先上传的对象；清理失败必须显式告警。 */
    private void compensate(ObjectStorageResultEntity stored) {
        try {
            objectStorageService.deleteObject(stored.getBucket(), stored.getObjectKey());
        } catch (RuntimeException cleanupError) {
            throw new AppException("RAG_UPLOAD_COMPENSATION_FAILED",
                    "上传登记失败且对象清理失败，需要后台清理", cleanupError);
        }
    }

    /** 租户、知识库和内容哈希共同生成摄取幂等键。 */
    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 不支持 SHA-256", e);
        }
    }

    /** 生成带业务前缀的不可猜测标识。 */
    private String id(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().replace("-", "");
    }
}
