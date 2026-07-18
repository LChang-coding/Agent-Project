package cn.bugstack.ai.domain.rag.service;

import cn.bugstack.ai.domain.rag.adapter.repository.IRagRepository;
import cn.bugstack.ai.domain.rag.model.entity.RagDocumentEntity;
import cn.bugstack.ai.domain.rag.model.entity.RagIngestJobEntity;
import cn.bugstack.ai.domain.rag.model.entity.RagKnowledgeBaseEntity;
import cn.bugstack.ai.domain.rag.model.valobj.RagIngestJobStatus;
import cn.bugstack.ai.types.exception.AppException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/** RAG 文档和摄取任务的管理员查询、取消用例。 */
@Service
public class RagDocumentManagementService {

    private final IRagRepository repository;
    private final RagKnowledgeBaseAuthorizationService authorizationService;
    private final Clock clock;

    public RagDocumentManagementService(IRagRepository repository,
                                        RagKnowledgeBaseAuthorizationService authorizationService) {
        this(repository, authorizationService, Clock.systemUTC());
    }

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

    /**
     * 请求取消任务。未持有 Worker 租约的任务同步关闭；运行中任务只建立取消屏障，由 Worker 清理副作用。
     */
    @Transactional(rollbackFor = Exception.class)
    public RagIngestJobEntity cancelTask(String tenantId, String userId, String roleCode,
                                         String taskId, String reason) {
        RagIngestJobEntity current = requireTask(tenantId, userId, roleCode, taskId);
        if (current.status() == RagIngestJobStatus.CANCELLED) return current;
        if (current.status() != RagIngestJobStatus.CANCEL_REQUESTED) {
            RagIngestJobEntity requested = current.requestCancel(reason);
            requireUpdated(repository.updateIngestJob(tenantId, requested, current.revision()));
            current = repository.findIngestJob(tenantId, taskId)
                    .orElseThrow(() -> new AppException("RAG_INGEST_TASK_NOT_FOUND", "取消后的任务不可见"));
        }
        if (current.lease() != null) return current;
        Instant now = clock.instant();
        RagIngestJobEntity cancelled = current.markCancelled(null, current.fencingToken(), now);
        requireUpdated(repository.updateIngestJob(tenantId, cancelled, current.revision()));
        return repository.findIngestJob(tenantId, taskId).orElse(cancelled);
    }

    private RagKnowledgeBaseEntity requireManageable(String tenantId, String userId, String roleCode,
                                                       String knowledgeBaseId) {
        RagKnowledgeBaseEntity knowledgeBase = repository.findKnowledgeBase(requireText(tenantId, "tenantId"),
                        requireText(knowledgeBaseId, "knowledgeBaseId"))
                .orElseThrow(() -> new AppException("RAG_KNOWLEDGE_BASE_NOT_FOUND", "知识库不存在或无权访问"));
        authorizationService.requireManageable(tenantId, userId, roleCode, knowledgeBase);
        return knowledgeBase;
    }

    private void requireUpdated(int changed) {
        if (changed != 1) {
            throw new AppException("RAG_INGEST_CONCURRENT_UPDATE", "摄取任务已变化，请刷新后重试");
        }
    }

    private String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new AppException("RAG_PARAM_INVALID", field + "不能为空");
        return value;
    }
}
