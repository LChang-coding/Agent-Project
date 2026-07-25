package cn.bugstack.ai.domain.rag.service;

import cn.bugstack.ai.domain.rag.adapter.repository.IRagRepository;
import cn.bugstack.ai.domain.rag.adapter.repository.RagKnowledgeBaseDeletionRepository;
import cn.bugstack.ai.domain.rag.model.entity.RagKnowledgeBaseDeleteRegistration;
import cn.bugstack.ai.domain.rag.model.entity.RagKnowledgeBaseDeleteTaskEntity;
import cn.bugstack.ai.domain.rag.model.entity.RagKnowledgeBaseEntity;
import cn.bugstack.ai.domain.rag.model.valobj.RagKnowledgeBaseStatus;
import cn.bugstack.ai.types.exception.AppException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/** 管理员受理、查询和恢复不可取消的知识库级联删除。 */
@Service
public class RagKnowledgeBaseDeletionService {
    /** 知识库级联删除比单文档删除允许更多恢复次数。 */
    private static final int MAX_ATTEMPTS = 5;

    /** 查询知识库和待级联文档。 */
    private final IRagRepository repository;
    /** 原子登记知识库删除屏障与任务账本。 */
    private final RagKnowledgeBaseDeletionRepository deletionRepository;
    /** 所有请求和查询都要求租户管理员权限。 */
    private final RagKnowledgeBaseAuthorizationService authorizationService;

    /** 注入 RAG 仓储、删除任务仓储和授权服务。 */
    public RagKnowledgeBaseDeletionService(IRagRepository repository,
                                            RagKnowledgeBaseDeletionRepository deletionRepository,
                                            RagKnowledgeBaseAuthorizationService authorizationService) {
        this.repository = repository;
        this.deletionRepository = deletionRepository;
        this.authorizationService = authorizationService;
    }

    /** 以知识库 revision 为 CAS 门禁受理不可取消的级联删除。 */
    public RagKnowledgeBaseDeleteTaskEntity requestDeletion(String tenantId, String userId,
                                                              String roleCode, String knowledgeBaseId,
                                                              long expectedRevision) {
        if (expectedRevision < 0) {
            throw new AppException("RAG_KNOWLEDGE_BASE_REVISION_INVALID", "expectedRevision不能小于零");
        }
        RagKnowledgeBaseEntity knowledgeBase = requireManageable(
                tenantId, userId, roleCode, knowledgeBaseId);
        RagKnowledgeBaseDeleteTaskEntity existing = deletionRepository.findByKnowledgeBaseId(
                tenantId, knowledgeBaseId).orElse(null);
        if (existing != null) return existing;
        if (knowledgeBase.status() == RagKnowledgeBaseStatus.DELETING
                || knowledgeBase.status() == RagKnowledgeBaseStatus.DELETED) {
            throw new AppException("RAG_KB_DELETE_TASK_MISSING", "知识库处于删除态但任务账本不存在");
        }
        if (knowledgeBase.revision() != expectedRevision) {
            throw new AppException("RAG_KNOWLEDGE_BASE_REVISION_CONFLICT", "知识库已变化，请刷新后重试");
        }
        int documentCount = repository.listDocuments(tenantId, knowledgeBaseId).size();
        RagKnowledgeBaseDeleteTaskEntity task = RagKnowledgeBaseDeleteTaskEntity.pending(
                tenantId, knowledgeBaseId, requireText(userId, "userId"), id("ragkbdel"),
                sha256("kb-delete\n" + tenantId + "\n" + knowledgeBaseId),
                documentCount, MAX_ATTEMPTS);
        boolean inserted = deletionRepository.register(tenantId,
                new RagKnowledgeBaseDeleteRegistration(
                        knowledgeBase.requestDeletion(), expectedRevision, task));
        if (!inserted) {
            return deletionRepository.findByKnowledgeBaseId(tenantId, knowledgeBaseId)
                    .orElseThrow(() -> new AppException("RAG_KB_DELETE_CONCURRENT_RESULT_MISSING",
                            "并发删除已受理但任务暂不可见"));
        }
        return deletionRepository.findByTaskId(tenantId, task.taskId()).orElse(task);
    }

    /** 查询任务前先由任务反查知识库并复核管理权限。 */
    public RagKnowledgeBaseDeleteTaskEntity requireTask(String tenantId, String userId,
                                                          String roleCode, String taskId) {
        RagKnowledgeBaseDeleteTaskEntity task = deletionRepository.findByTaskId(
                        requireText(tenantId, "tenantId"), requireText(taskId, "taskId"))
                .orElseThrow(() -> new AppException("RAG_KB_DELETE_TASK_NOT_FOUND", "知识库删除任务不存在"));
        requireManageable(tenantId, userId, roleCode, task.knowledgeBaseId());
        return task;
    }

    /** 按知识库恢复删除进度，供页面刷新后重新挂载轮询。 */
    public RagKnowledgeBaseDeleteTaskEntity requireTaskByKnowledgeBase(String tenantId, String userId,
                                                                        String roleCode, String knowledgeBaseId) {
        RagKnowledgeBaseEntity knowledgeBase = requireManageable(
                tenantId, userId, roleCode, knowledgeBaseId);
        return deletionRepository.findByKnowledgeBaseId(tenantId, knowledgeBase.knowledgeBaseId())
                .orElseThrow(() -> new AppException("RAG_KB_DELETE_TASK_NOT_FOUND", "知识库删除任务不存在"));
    }

    /** 只有知识库仍处于删除屏障内才允许任务重新排队。 */
    public RagKnowledgeBaseDeleteTaskEntity retry(String tenantId, String userId,
                                                    String roleCode, String taskId) {
        RagKnowledgeBaseDeleteTaskEntity current = requireTask(
                tenantId, userId, roleCode, taskId);
        RagKnowledgeBaseEntity knowledgeBase = repository.findKnowledgeBase(tenantId,
                        current.knowledgeBaseId()).orElseThrow(() ->
                new AppException("RAG_KNOWLEDGE_BASE_NOT_FOUND", "知识库不存在"));
        if (knowledgeBase.status() != RagKnowledgeBaseStatus.DELETING) {
            throw new AppException("RAG_KB_DELETE_STATE_MISMATCH", "知识库不在可恢复的删除状态");
        }
        RagKnowledgeBaseDeleteTaskEntity requeued = current.requeue();
        if (deletionRepository.update(tenantId, requeued, current.revision()) != 1) {
            throw new AppException("RAG_KB_DELETE_CONCURRENT_UPDATE", "知识库删除任务已变化");
        }
        return deletionRepository.findByTaskId(tenantId, taskId).orElse(requeued);
    }

    /** 在可信租户内读取知识库并执行管理员授权。 */
    private RagKnowledgeBaseEntity requireManageable(String tenantId, String userId,
                                                       String roleCode, String knowledgeBaseId) {
        RagKnowledgeBaseEntity knowledgeBase = repository.findKnowledgeBase(
                        requireText(tenantId, "tenantId"), requireText(knowledgeBaseId, "knowledgeBaseId"))
                .orElseThrow(() -> new AppException("RAG_KNOWLEDGE_BASE_NOT_FOUND", "知识库不存在或无权访问"));
        authorizationService.requireManageable(tenantId, userId, roleCode, knowledgeBase);
        return knowledgeBase;
    }

    /** 生成带业务前缀的不可猜测任务 ID。 */
    private String id(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().replace("-", "");
    }

    /** 生成同一租户知识库唯一的删除幂等键。 */
    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM不支持SHA-256", e);
        }
    }

    /** 在仓储访问前拒绝空业务标识。 */
    private String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new AppException("RAG_PARAM_INVALID", field + "不能为空");
        }
        return value;
    }
}
