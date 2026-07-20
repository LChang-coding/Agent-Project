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
    private static final int MAX_ATTEMPTS = 5;

    private final IRagRepository repository;
    private final RagKnowledgeBaseDeletionRepository deletionRepository;
    private final RagKnowledgeBaseAuthorizationService authorizationService;

    public RagKnowledgeBaseDeletionService(IRagRepository repository,
                                            RagKnowledgeBaseDeletionRepository deletionRepository,
                                            RagKnowledgeBaseAuthorizationService authorizationService) {
        this.repository = repository;
        this.deletionRepository = deletionRepository;
        this.authorizationService = authorizationService;
    }

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

    public RagKnowledgeBaseDeleteTaskEntity requireTask(String tenantId, String userId,
                                                          String roleCode, String taskId) {
        RagKnowledgeBaseDeleteTaskEntity task = deletionRepository.findByTaskId(
                        requireText(tenantId, "tenantId"), requireText(taskId, "taskId"))
                .orElseThrow(() -> new AppException("RAG_KB_DELETE_TASK_NOT_FOUND", "知识库删除任务不存在"));
        requireManageable(tenantId, userId, roleCode, task.knowledgeBaseId());
        return task;
    }

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

    private RagKnowledgeBaseEntity requireManageable(String tenantId, String userId,
                                                       String roleCode, String knowledgeBaseId) {
        RagKnowledgeBaseEntity knowledgeBase = repository.findKnowledgeBase(
                        requireText(tenantId, "tenantId"), requireText(knowledgeBaseId, "knowledgeBaseId"))
                .orElseThrow(() -> new AppException("RAG_KNOWLEDGE_BASE_NOT_FOUND", "知识库不存在或无权访问"));
        authorizationService.requireManageable(tenantId, userId, roleCode, knowledgeBase);
        return knowledgeBase;
    }

    private String id(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().replace("-", "");
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM不支持SHA-256", e);
        }
    }

    private String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new AppException("RAG_PARAM_INVALID", field + "不能为空");
        }
        return value;
    }
}
