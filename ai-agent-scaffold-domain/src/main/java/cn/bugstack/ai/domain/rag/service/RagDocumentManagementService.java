package cn.bugstack.ai.domain.rag.service;

import cn.bugstack.ai.domain.rag.adapter.repository.IRagRepository;
import cn.bugstack.ai.domain.rag.model.entity.RagDocumentEntity;
import cn.bugstack.ai.domain.rag.model.entity.RagDocumentVersionEntity;
import cn.bugstack.ai.domain.rag.model.entity.RagIngestJobEntity;
import cn.bugstack.ai.domain.rag.model.entity.RagKnowledgeBaseEntity;
import cn.bugstack.ai.domain.rag.model.valobj.RagIngestJobStatus;
import cn.bugstack.ai.domain.rag.model.valobj.RagIngestOperation;
import cn.bugstack.ai.domain.rag.model.valobj.RagDocumentStatus;
import cn.bugstack.ai.domain.rag.model.valobj.RagDocumentVersionStatus;
import cn.bugstack.ai.types.exception.AppException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/** RAG 文档和摄取任务的管理员查询、取消用例。 */
@Service
public class RagDocumentManagementService {

    /** 统一读取和 CAS 更新 RAG 聚合。 */
    private final IRagRepository repository;
    /** 管理入口要求当前用户具备租户管理员权限。 */
    private final RagKnowledgeBaseAuthorizationService authorizationService;
    /** 可注入时钟保证取消状态测试可复现。 */
    private final Clock clock;

    /** 生产环境使用 UTC 时钟。 */
    @Autowired
    public RagDocumentManagementService(IRagRepository repository,
                                        RagKnowledgeBaseAuthorizationService authorizationService) {
        this(repository, authorizationService, Clock.systemUTC());
    }

    /** 测试入口允许注入固定时钟。 */
    RagDocumentManagementService(IRagRepository repository,
                                 RagKnowledgeBaseAuthorizationService authorizationService,
                                 Clock clock) {
        this.repository = repository;
        this.authorizationService = authorizationService;
        this.clock = clock;
    }

    /** 查询管理员可维护的知识库文档。 */
    public List<RagDocumentEntity> listDocuments(String tenantId, String userId, String roleCode,
                                                  String knowledgeBaseId) {
        requireManageable(tenantId, userId, roleCode, knowledgeBaseId);
        return repository.listDocuments(tenantId, knowledgeBaseId);
    }

    /** 查询摄取任务，响应层必须继续隐藏租约和 fencing。 */
    public RagIngestJobEntity requireTask(String tenantId, String userId, String roleCode, String taskId) {
        RagIngestJobEntity task = repository.findIngestJob(requireText(tenantId, "tenantId"),
                        requireText(taskId, "taskId"))
                .orElseThrow(() -> new AppException("RAG_INGEST_TASK_NOT_FOUND", "摄取任务不存在或无权访问"));
        requireManageable(tenantId, userId, roleCode, task.knowledgeBaseId());
        return task;
    }

    /** 查询知识库最新摄取任务，用于页面刷新后恢复进度。 */
    public List<RagIngestJobEntity> listTasks(String tenantId, String userId, String roleCode,
                                              String knowledgeBaseId, int limit) {
        requireManageable(tenantId, userId, roleCode, knowledgeBaseId);
        if (limit < 1 || limit > 200) {
            throw new AppException("RAG_TASK_LIMIT_INVALID", "任务查询数量必须在1到200之间");
        }
        return repository.listIngestJobs(tenantId, knowledgeBaseId, limit);
    }

    /**
     * 请求取消任务。未持有 Worker 租约的任务同步关闭；运行中任务只建立取消屏障，由 Worker 清理副作用。
     */
    @Transactional(rollbackFor = Exception.class)
    public RagIngestJobEntity cancelTask(String tenantId, String userId, String roleCode,
                                         String taskId, String reason) {
        RagIngestJobEntity current = requireTask(tenantId, userId, roleCode, taskId);
        if (current.status() == RagIngestJobStatus.CANCELLED) {
            return reconcileUnclaimedCancellation(tenantId, current);
        }
        if (current.status() != RagIngestJobStatus.CANCEL_REQUESTED) {
            RagIngestJobEntity requested = current.requestCancel(reason);
            requireUpdated(repository.updateIngestJob(tenantId, requested, current.revision()));
            current = repository.findIngestJob(tenantId, taskId)
                    .orElseThrow(() -> new AppException("RAG_INGEST_TASK_NOT_FOUND", "取消后的任务不可见"));
        }
        if (current.lease() != null) return current;
        Instant now = clock.instant();
        RagIngestJobEntity cancelled = current.markCancelled(null, current.fencingToken(), now);
        RagDocumentVersionEntity version = requireVersion(tenantId, current.versionId());
        RagDocumentEntity document = requireDocument(tenantId, current.documentId());
        repository.cancelUnclaimedIngestJob(tenantId, cancelled, current.revision(),
                version.revision(), document.revision());
        return repository.findIngestJob(tenantId, taskId).orElse(cancelled);
    }

    /** 安全恢复失败或死信任务；数据库恢复扫描负责唤醒，不依赖Kafka投递成功。 */
    @Transactional(rollbackFor = Exception.class)
    public RagIngestJobEntity retryTask(String tenantId, String userId, String roleCode, String taskId) {
        RagIngestJobEntity current = requireTask(tenantId, userId, roleCode, taskId);
        if (current.status() != RagIngestJobStatus.FAILED && current.status() != RagIngestJobStatus.DEAD) {
            throw new AppException("RAG_INGEST_RETRY_STATE_INVALID", "只有失败或死信任务可以重新执行");
        }
        if (current.operation() == RagIngestOperation.REBUILD) {
            throw new AppException("RAG_REBUILD_NOT_IMPLEMENTED", "知识库重建链路尚未实现");
        }
        if (current.operation() == RagIngestOperation.DELETE) {
            return retryDelete(tenantId, current);
        }
        RagKnowledgeBaseEntity knowledgeBase = requireManageable(
                tenantId, userId, roleCode, current.knowledgeBaseId());
        if (!knowledgeBase.status().searchable()) {
            throw new AppException("RAG_KNOWLEDGE_BASE_UNAVAILABLE", "当前知识库不能恢复摄取任务");
        }
        RagDocumentVersionEntity version = requireVersion(tenantId, current.versionId());
        RagDocumentEntity document = requireDocument(tenantId, current.documentId());
        requireRetryScope(current, version, document);
        RagIngestJobEntity requeued = current.requeueIngest();
        RagDocumentVersionEntity queuedVersion = version.retryQueued();
        RagDocumentEntity processingDocument = document.retryProcessing(current.generation());
        repository.requeueFailedIngestJob(tenantId, requeued, current.revision(), queuedVersion,
                version.revision(), processingDocument, document.revision(), knowledgeBase.revision());
        return repository.findIngestJob(tenantId, taskId).orElse(requeued);
    }

    /** 删除任务只在文档墓碑仍为 DELETING 时重新排队。 */
    private RagIngestJobEntity retryDelete(String tenantId, RagIngestJobEntity current) {
        RagDocumentEntity document = requireDocument(tenantId, current.documentId());
        if (!current.knowledgeBaseId().equals(document.knowledgeBaseId())
                || document.status() != RagDocumentStatus.DELETING) {
            throw new AppException("RAG_DELETE_STATE_MISMATCH", "删除任务与文档墓碑状态不一致");
        }
        RagIngestJobEntity requeued = current.requeueDeletion();
        requireUpdated(repository.updateIngestJob(tenantId, requeued, current.revision()));
        return repository.findIngestJob(tenantId, current.jobId()).orElse(requeued);
    }

    /** 重试前核对任务、文档和版本属于同一代际与资源范围。 */
    private void requireRetryScope(RagIngestJobEntity task, RagDocumentVersionEntity version,
                                   RagDocumentEntity document) {
        if (!task.knowledgeBaseId().equals(version.knowledgeBaseId())
                || !task.knowledgeBaseId().equals(document.knowledgeBaseId())
                || !task.documentId().equals(version.documentId())
                || !task.documentId().equals(document.documentId())
                || !task.versionId().equals(version.versionId())
                || task.generation() != version.generation()) {
            throw new AppException("RAG_INGEST_RETRY_SCOPE_MISMATCH", "任务、文档和版本范围不一致");
        }
    }

    /** 修复任务已取消但文档或版本尚未同步终态的历史中间态。 */
    private RagIngestJobEntity reconcileUnclaimedCancellation(String tenantId, RagIngestJobEntity current) {
        if (current.lease() != null) return current;
        RagDocumentVersionEntity version = requireVersion(tenantId, current.versionId());
        RagDocumentEntity document = requireDocument(tenantId, current.documentId());
        if (version.status() == RagDocumentVersionStatus.CANCELLED && document.targetGeneration() == null) {
            return current;
        }
        repository.cancelUnclaimedIngestJob(tenantId, current, current.revision(),
                version.revision(), document.revision());
        return repository.findIngestJob(tenantId, current.jobId()).orElse(current);
    }

    /** 在可信租户范围内读取文档版本。 */
    private RagDocumentVersionEntity requireVersion(String tenantId, String versionId) {
        return repository.findDocumentVersion(tenantId, versionId)
                .orElseThrow(() -> new AppException("RAG_DOCUMENT_VERSION_NOT_FOUND", "文档版本不存在或无权访问"));
    }

    /** 在可信租户范围内读取逻辑文档。 */
    private RagDocumentEntity requireDocument(String tenantId, String documentId) {
        return repository.findDocument(tenantId, documentId)
                .orElseThrow(() -> new AppException("RAG_DOCUMENT_NOT_FOUND", "文档不存在或无权访问"));
    }

    /** 读取知识库后执行管理员和租户归属校验。 */
    private RagKnowledgeBaseEntity requireManageable(String tenantId, String userId, String roleCode,
                                                       String knowledgeBaseId) {
        RagKnowledgeBaseEntity knowledgeBase = repository.findKnowledgeBase(requireText(tenantId, "tenantId"),
                        requireText(knowledgeBaseId, "knowledgeBaseId"))
                .orElseThrow(() -> new AppException("RAG_KNOWLEDGE_BASE_NOT_FOUND", "知识库不存在或无权访问"));
        authorizationService.requireManageable(tenantId, userId, roleCode, knowledgeBase);
        return knowledgeBase;
    }

    /** CAS 必须且只能更新一行，否则要求调用方刷新状态。 */
    private void requireUpdated(int changed) {
        if (changed != 1) {
            throw new AppException("RAG_INGEST_CONCURRENT_UPDATE", "摄取任务已变化，请刷新后重试");
        }
    }

    /** 在查询前拒绝空标识，避免仓储层出现宽范围条件。 */
    private String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new AppException("RAG_PARAM_INVALID", field + "不能为空");
        return value;
    }
}
