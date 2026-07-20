package cn.bugstack.ai.domain.rag.model.entity;

import cn.bugstack.ai.domain.rag.model.valobj.RagKnowledgeBaseDeleteCheckpoint;
import cn.bugstack.ai.domain.rag.model.valobj.RagKnowledgeBaseDeleteStage;
import cn.bugstack.ai.domain.rag.model.valobj.RagKnowledgeBaseDeleteStatus;
import cn.bugstack.ai.domain.rag.model.valobj.RagLease;
import cn.bugstack.ai.types.exception.AppException;

import java.time.Duration;
import java.time.Instant;

/** 可租约接管、可从文档检查点恢复的知识库级联删除任务。 */
public record RagKnowledgeBaseDeleteTaskEntity(String tenantId,
                                               String knowledgeBaseId,
                                               String taskId,
                                               String taskKey,
                                               RagKnowledgeBaseDeleteStatus status,
                                               RagKnowledgeBaseDeleteCheckpoint checkpoint,
                                               int attemptCount,
                                               int maxAttempts,
                                               Instant nextRetryAt,
                                               RagLease lease,
                                               long fencingToken,
                                               long revision,
                                               String errorCode,
                                               String errorMessage) {

    public RagKnowledgeBaseDeleteTaskEntity {
        requireText(tenantId, "租户ID");
        requireText(knowledgeBaseId, "知识库ID");
        requireText(taskId, "任务ID");
        requireText(taskKey, "任务幂等键");
        if (status == null || checkpoint == null || attemptCount < 0 || maxAttempts < 1
                || attemptCount > maxAttempts || fencingToken < 0 || revision < 0) {
            throw new IllegalArgumentException("知识库删除任务参数非法");
        }
        if (status == RagKnowledgeBaseDeleteStatus.RUNNING && lease == null
                || status != RagKnowledgeBaseDeleteStatus.RUNNING && lease != null) {
            throw new IllegalArgumentException("知识库删除任务状态与租约不一致");
        }
        boolean scheduled = status == RagKnowledgeBaseDeleteStatus.RETRYING
                || status == RagKnowledgeBaseDeleteStatus.WAITING;
        if (scheduled && nextRetryAt == null || !scheduled && nextRetryAt != null) {
            throw new IllegalArgumentException("知识库删除任务状态与重试时间不一致");
        }
        if (status == RagKnowledgeBaseDeleteStatus.COMPLETED
                != (checkpoint.stage() == RagKnowledgeBaseDeleteStage.COMPLETED)) {
            throw new IllegalArgumentException("知识库删除任务状态与完成检查点不一致");
        }
    }

    public static RagKnowledgeBaseDeleteTaskEntity pending(String tenantId, String knowledgeBaseId,
                                                            String taskId, String taskKey,
                                                            int totalDocuments, int maxAttempts) {
        return new RagKnowledgeBaseDeleteTaskEntity(tenantId, knowledgeBaseId, taskId, taskKey,
                RagKnowledgeBaseDeleteStatus.PENDING,
                RagKnowledgeBaseDeleteCheckpoint.initial(totalDocuments), 0, maxAttempts,
                null, null, 0, 0, null, null);
    }

    public RagKnowledgeBaseDeleteTaskEntity claim(String owner, long newFence, Instant now,
                                                   Duration leaseDuration) {
        if (now == null || leaseDuration == null || leaseDuration.isZero() || leaseDuration.isNegative()) {
            throw new IllegalArgumentException("知识库删除租约时间非法");
        }
        if (!status.claimable() || nextRetryAt != null && nextRetryAt.isAfter(now)) {
            throw error("RAG_KB_DELETE_NOT_CLAIMABLE", "知识库删除任务当前不可领取");
        }
        if (status == RagKnowledgeBaseDeleteStatus.RUNNING && lease != null && !lease.expiredAt(now)) {
            throw error("RAG_KB_DELETE_LEASE_ACTIVE", "知识库删除任务租约仍有效");
        }
        if (status != RagKnowledgeBaseDeleteStatus.WAITING && attemptCount >= maxAttempts
                || newFence <= fencingToken) {
            throw error("RAG_KB_DELETE_CLAIM_REJECTED", "知识库删除任务尝试次数或栅栏非法");
        }
        int claimedAttempts = status == RagKnowledgeBaseDeleteStatus.WAITING
                ? attemptCount : attemptCount + 1;
        return copy(RagKnowledgeBaseDeleteStatus.RUNNING, checkpoint, claimedAttempts,
                null, new RagLease(requireText(owner, "租约持有者"), now.plus(leaseDuration)),
                newFence, null, null);
    }

    public RagKnowledgeBaseDeleteTaskEntity advance(String owner, long expectedFence, Instant now,
                                                     RagKnowledgeBaseDeleteCheckpoint target) {
        assertClaim(owner, expectedFence, now);
        if (target == null || target.stage().ordinal() < checkpoint.stage().ordinal()
                || target.totalDocuments() != checkpoint.totalDocuments()
                || target.completedDocuments() < checkpoint.completedDocuments()
                || target.stage() == RagKnowledgeBaseDeleteStage.COMPLETED) {
            throw error("RAG_KB_DELETE_CHECKPOINT_REGRESSION", "知识库删除检查点非法推进");
        }
        return copy(status, target, attemptCount, null, lease, fencingToken, null, null);
    }

    public RagKnowledgeBaseDeleteTaskEntity complete(String owner, long expectedFence, Instant now) {
        assertClaim(owner, expectedFence, now);
        if (checkpoint.stage() != RagKnowledgeBaseDeleteStage.VERIFYING
                || checkpoint.completedDocuments() != checkpoint.totalDocuments()) {
            throw error("RAG_KB_DELETE_NOT_VERIFIED", "知识库删除尚未完成一致性验证");
        }
        return copy(RagKnowledgeBaseDeleteStatus.COMPLETED,
                new RagKnowledgeBaseDeleteCheckpoint(RagKnowledgeBaseDeleteStage.COMPLETED,
                        checkpoint.totalDocuments(), checkpoint.completedDocuments(), null),
                attemptCount, null, null, fencingToken, null, null);
    }

    /** 子文档删除仍在进行时释放租约，不消耗失败尝试次数。 */
    public RagKnowledgeBaseDeleteTaskEntity waitForChild(String owner, long expectedFence, Instant now,
                                                          Instant nextPollAt,
                                                          RagKnowledgeBaseDeleteCheckpoint target) {
        assertClaim(owner, expectedFence, now);
        if (nextPollAt == null || !nextPollAt.isAfter(now) || target == null
                || target.stage() != RagKnowledgeBaseDeleteStage.DELETING_DOCUMENTS
                || target.totalDocuments() != checkpoint.totalDocuments()
                || target.completedDocuments() < checkpoint.completedDocuments()) {
            throw new IllegalArgumentException("知识库删除等待检查点非法");
        }
        return copy(RagKnowledgeBaseDeleteStatus.WAITING, target, attemptCount,
                nextPollAt, null, fencingToken, null, null);
    }

    public RagKnowledgeBaseDeleteTaskEntity fail(String owner, long expectedFence, Instant now,
                                                 boolean retryable, Instant retryAt,
                                                 String code, String message) {
        assertClaim(owner, expectedFence, now);
        RagKnowledgeBaseDeleteStatus target = retryable && attemptCount < maxAttempts
                ? RagKnowledgeBaseDeleteStatus.RETRYING
                : retryable ? RagKnowledgeBaseDeleteStatus.DEAD : RagKnowledgeBaseDeleteStatus.FAILED;
        if (target == RagKnowledgeBaseDeleteStatus.RETRYING
                && (retryAt == null || retryAt.isBefore(now))) {
            throw new IllegalArgumentException("知识库删除重试时间非法");
        }
        return copy(target, checkpoint, attemptCount,
                target == RagKnowledgeBaseDeleteStatus.RETRYING ? retryAt : null,
                null, fencingToken, normalize(code, 64), normalize(message, 1000));
    }

    public RagKnowledgeBaseDeleteTaskEntity requeue() {
        if (status != RagKnowledgeBaseDeleteStatus.FAILED && status != RagKnowledgeBaseDeleteStatus.DEAD) {
            throw error("RAG_KB_DELETE_REQUEUE_STATE_INVALID", "知识库删除任务当前不能重新排队");
        }
        return copy(RagKnowledgeBaseDeleteStatus.PENDING, checkpoint, 0,
                null, null, fencingToken, null, null);
    }

    public void assertClaim(String owner, long expectedFence, Instant now) {
        if (status != RagKnowledgeBaseDeleteStatus.RUNNING || lease == null
                || !lease.owner().equals(owner) || fencingToken != expectedFence
                || now == null || lease.expiredAt(now)) {
            throw error("RAG_KB_DELETE_FENCE_LOST", "知识库删除任务租约或栅栏已失效");
        }
    }

    private RagKnowledgeBaseDeleteTaskEntity copy(RagKnowledgeBaseDeleteStatus targetStatus,
                                                   RagKnowledgeBaseDeleteCheckpoint targetCheckpoint,
                                                   int targetAttempts, Instant targetRetryAt,
                                                   RagLease targetLease, long targetFence,
                                                   String targetErrorCode, String targetErrorMessage) {
        return new RagKnowledgeBaseDeleteTaskEntity(tenantId, knowledgeBaseId, taskId, taskKey,
                targetStatus, targetCheckpoint, targetAttempts, maxAttempts, targetRetryAt,
                targetLease, targetFence, revision + 1, targetErrorCode, targetErrorMessage);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + "不能为空");
        return value;
    }

    private String normalize(String value, int max) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim().replaceAll("[\\r\\n\\t ]+", " ");
        return normalized.length() <= max ? normalized : normalized.substring(0, max);
    }

    private AppException error(String code, String message) {
        return new AppException(code, message);
    }
}
