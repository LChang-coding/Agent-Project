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

    /** 删除任务默认最多执行三次，超过后进入死信等待人工恢复。 */
    private static final int DEFAULT_MAX_ATTEMPTS = 3;

    /** 查询并推进文档、版本与摄取任务状态。 */
    private final IRagRepository repository;
    /** 原子登记文档墓碑、版本墓碑、删除任务和 Outbox。 */
    private final RagDocumentDeletionRegistrationPort registrationPort;
    /** 所有外部删除入口必须经过租户管理员授权。 */
    private final RagKnowledgeBaseAuthorizationService authorizationService;

    /** 注入仓储、原子登记端口和授权服务。 */
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

        return ensureDeletion(tenantId, knowledgeBaseId, document, expectedRevision);
    }

    /** 级联删除协调器内部入口；只允许已建立DELETING屏障的同租户知识库。 */
    public RagIngestJobEntity ensureCascadeDeletion(String tenantId, String knowledgeBaseId,
                                                      String documentId) {
        RagKnowledgeBaseEntity knowledgeBase = repository.findKnowledgeBase(requireText(tenantId, "tenantId"),
                        requireText(knowledgeBaseId, "knowledgeBaseId"))
                .orElseThrow(() -> new AppException("RAG_KNOWLEDGE_BASE_NOT_FOUND", "知识库不存在"));
        if (knowledgeBase.status() != cn.bugstack.ai.domain.rag.model.valobj.RagKnowledgeBaseStatus.DELETING) {
            throw new AppException("RAG_KB_DELETE_STATE_MISMATCH", "知识库尚未建立级联删除屏障");
        }
        RagDocumentEntity document = repository.findDocument(tenantId, requireText(documentId, "documentId"))
                .filter(value -> knowledgeBaseId.equals(value.knowledgeBaseId()))
                .orElseThrow(() -> new AppException("RAG_DOCUMENT_NOT_FOUND", "知识库文档不存在"));
        return ensureDeletion(tenantId, knowledgeBaseId, document, document.revision());
    }

    /** 以稳定幂等键登记删除屏障，禁止文档进入删除态却没有任务。 */
    private RagIngestJobEntity ensureDeletion(String tenantId, String knowledgeBaseId,
                                               RagDocumentEntity document, long expectedRevision) {

        String documentId = document.documentId();
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

    /** 返回进行中或已完成任务；失败和死信任务以 CAS 方式重新排队。 */
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

    /** 优先使用活动版本承载删除任务，无活动版本时使用首个历史版本。 */
    private RagDocumentVersionEntity resolveTaskVersion(RagDocumentEntity document,
                                                         List<RagDocumentVersionEntity> versions) {
        if (document.activeVersionId() != null) {
            return versions.stream().filter(value -> document.activeVersionId().equals(value.versionId()))
                    .findFirst().orElseThrow(() -> new AppException("RAG_DOCUMENT_ACTIVE_VERSION_MISSING",
                            "文档活动版本记录不存在"));
        }
        return versions.get(0);
    }

    /** 删除幂等键只绑定租户和逻辑文档，覆盖该文档全部版本。 */
    private String deletionTaskKey(String tenantId, String documentId) {
        return sha256("delete\n" + tenantId + "\n" + documentId);
    }

    /** 生成带业务前缀的不可猜测标识。 */
    private String id(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().replace("-", "");
    }

    /** 生成跨进程稳定的删除任务键。 */
    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 不支持 SHA-256", e);
        }
    }

    /** 在仓储查询前拒绝空业务标识。 */
    private String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new AppException("RAG_PARAM_INVALID", field + "不能为空");
        }
        return value;
    }
}
