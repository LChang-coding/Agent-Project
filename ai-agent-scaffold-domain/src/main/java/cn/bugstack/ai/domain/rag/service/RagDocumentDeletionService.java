package cn.bugstack.ai.domain.rag.service;

import cn.bugstack.ai.domain.rag.adapter.repository.IRagRepository;
import cn.bugstack.ai.domain.rag.adapter.repository.RagDocumentDeletionRegistrationPort;
import cn.bugstack.ai.domain.rag.model.entity.RagDocumentDeletionRegistration;
import cn.bugstack.ai.domain.rag.model.entity.RagDocumentEntity;
import cn.bugstack.ai.domain.rag.model.entity.RagDocumentVersionEntity;
import cn.bugstack.ai.domain.rag.model.entity.RagIngestJobEntity;
import cn.bugstack.ai.domain.rag.model.entity.RagKnowledgeBaseEntity;
import cn.bugstack.ai.domain.rag.model.valobj.RagDocumentStatus;
import cn.bugstack.ai.domain.rag.model.valobj.RagIngestJobStatus;
import cn.bugstack.ai.domain.rag.model.valobj.RagIngestOperation;
import cn.bugstack.ai.types.exception.AppException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/** 受理可恢复、不可取消且覆盖逻辑文档全部版本的删除任务。 */
@Service
public class RagDocumentDeletionService {

    private static final int DEFAULT_MAX_ATTEMPTS = 3;

    private final IRagRepository repository;
    private final RagDocumentDeletionRegistrationPort registrationPort;
    private final RagKnowledgeBaseAuthorizationService authorizationService;

    public RagDocumentDeletionService(IRagRepository repository,
                                      RagDocumentDeletionRegistrationPort registrationPort,
                                      RagKnowledgeBaseAuthorizationService authorizationService) {
        this.repository = repository;
        this.registrationPort = registrationPort;
        this.authorizationService = authorizationService;
    }

    /** 以文档revision为CAS门禁受理删除；重复请求返回同一个删除任务。 */
    public RagIngestJobEntity deleteDocument(String tenantId, String userId, String roleCode,
                                              String knowledgeBaseId, String documentId,
                                              long expectedRevision) {
        if (expectedRevision < 0) {
            throw new AppException("RAG_DOCUMENT_REVISION_INVALID", "expectedRevision不能小于零");
        }
        RagKnowledgeBaseEntity knowledgeBase = repository.findKnowledgeBase(requireText(tenantId, "tenantId"),
                        requireText(knowledgeBaseId, "knowledgeBaseId"))
                .orElseThrow(() -> new AppException("RAG_KNOWLEDGE_BASE_NOT_FOUND", "知识库不存在或无权访问"));
        authorizationService.requireManageable(tenantId, userId, roleCode, knowledgeBase);
        RagDocumentEntity document = repository.findDocument(tenantId, requireText(documentId, "documentId"))
                .filter(value -> knowledgeBaseId.equals(value.knowledgeBaseId()))
                .orElseThrow(() -> new AppException("RAG_DOCUMENT_NOT_FOUND", "文档不存在或无权访问"));

        String taskKey = deletionTaskKey(tenantId, documentId);
        RagIngestJobEntity existing = repository.findIngestJobByIdempotencyKey(tenantId, taskKey).orElse(null);
        if (existing != null) return resumeOrReturn(tenantId, document, existing);
        if (document.status() == RagDocumentStatus.DELETING || document.status() == RagDocumentStatus.DELETED) {
            throw new AppException("RAG_DELETE_TASK_MISSING", "文档处于删除态但删除任务不可见，需要运维核查");
        }
        if (document.revision() != expectedRevision) {
            throw new AppException("RAG_DOCUMENT_REVISION_CONFLICT", "文档已变化，请刷新后重试");
        }

        List<RagDocumentVersionEntity> versions = repository.listDocumentVersions(tenantId, documentId);
        if (versions.isEmpty()) {
            throw new AppException("RAG_DOCUMENT_VERSION_NOT_FOUND", "文档没有可删除的版本记录");
        }
        RagDocumentEntity deletingDocument = document.requestDeletion();
        List<RagDocumentVersionEntity> deletingVersions = versions.stream()
                .map(RagDocumentVersionEntity::requestDeletion).toList();
        RagDocumentVersionEntity taskVersion = resolveTaskVersion(document, versions);
        RagIngestJobEntity task = RagIngestJobEntity.pending(tenantId, knowledgeBaseId, documentId,
                taskVersion.versionId(), id("ragtask"), taskKey, RagIngestOperation.DELETE,
                taskVersion.generation(), DEFAULT_MAX_ATTEMPTS);
        boolean inserted = registrationPort.register(tenantId,
                new RagDocumentDeletionRegistration(deletingDocument, deletingVersions, task, id("ragevt")));
        if (!inserted) {
            return repository.findIngestJobByIdempotencyKey(tenantId, taskKey)
                    .orElseThrow(() -> new AppException("RAG_DELETE_CONCURRENT_RESULT_MISSING",
                            "并发删除已受理但任务暂不可见，请刷新后重试"));
        }
        return repository.findIngestJob(tenantId, task.jobId()).orElse(task);
    }

    private RagIngestJobEntity resumeOrReturn(String tenantId, RagDocumentEntity document,
                                               RagIngestJobEntity existing) {
        if (existing.operation() != RagIngestOperation.DELETE
                || !document.documentId().equals(existing.documentId())
                || !document.knowledgeBaseId().equals(existing.knowledgeBaseId())) {
            throw new AppException("RAG_DELETE_IDEMPOTENCY_CONFLICT", "删除任务幂等键与文档范围不一致");
        }
        if (existing.status() == RagIngestJobStatus.COMPLETED) {
            if (document.status() != RagDocumentStatus.DELETED) {
                throw new AppException("RAG_DELETE_STATE_MISMATCH", "删除任务与文档墓碑终态不一致");
            }
            return existing;
        }
        if (document.status() != RagDocumentStatus.DELETING) {
            throw new AppException("RAG_DELETE_STATE_MISMATCH", "删除任务与文档墓碑状态不一致");
        }
        if (existing.status() != RagIngestJobStatus.FAILED && existing.status() != RagIngestJobStatus.DEAD) {
            return existing;
        }
        RagIngestJobEntity requeued = existing.requeueDeletion();
        int changed = repository.updateIngestJob(tenantId, requeued, existing.revision());
        if (changed != 1) {
            return repository.findIngestJob(tenantId, existing.jobId())
                    .orElseThrow(() -> new AppException("RAG_DELETE_CONCURRENT_RESULT_MISSING",
                            "删除任务已变化但当前不可见，请刷新后重试"));
        }
        return repository.findIngestJob(tenantId, existing.jobId()).orElse(requeued);
    }

    private RagDocumentVersionEntity resolveTaskVersion(RagDocumentEntity document,
                                                         List<RagDocumentVersionEntity> versions) {
        if (document.activeVersionId() != null) {
            return versions.stream().filter(value -> document.activeVersionId().equals(value.versionId()))
                    .findFirst().orElseThrow(() -> new AppException("RAG_DOCUMENT_ACTIVE_VERSION_MISSING",
                            "文档活动版本记录不存在"));
        }
        return versions.get(0);
    }

    private String deletionTaskKey(String tenantId, String documentId) {
        return sha256("delete\n" + tenantId + "\n" + documentId);
    }

    private String id(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().replace("-", "");
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 不支持 SHA-256", e);
        }
    }

    private String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new AppException("RAG_PARAM_INVALID", field + "不能为空");
        }
        return value;
    }
}
