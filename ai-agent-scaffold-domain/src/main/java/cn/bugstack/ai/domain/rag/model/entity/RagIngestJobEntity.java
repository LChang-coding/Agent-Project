package cn.bugstack.ai.domain.rag.model.entity;

import cn.bugstack.ai.domain.rag.model.valobj.RagIngestCheckpoint;
import cn.bugstack.ai.domain.rag.model.valobj.RagIngestJobStatus;
import cn.bugstack.ai.domain.rag.model.valobj.RagIngestOperation;
import cn.bugstack.ai.domain.rag.model.valobj.RagIngestStage;
import cn.bugstack.ai.domain.rag.model.valobj.RagLease;
import cn.bugstack.ai.types.exception.AppException;

import java.time.Duration;
import java.time.Instant;

/**
 * 可租约领取、可取消并受 fencing token 保护的摄取任务。
 */
public record RagIngestJobEntity(String tenantId,
                                 String knowledgeBaseId,
                                 String documentId,
                                 String versionId,
                                 String jobId,
                                 String idempotencyKey,
                                 RagIngestOperation operation,
                                 long generation,
                                 RagIngestJobStatus status,
                                 RagIngestCheckpoint checkpoint,
                                 int attemptCount,
                                 int maxAttempts,
                                 Instant nextRetryAt,
                                 RagLease lease,
                                 long fencingToken,
                                 long revision,
                                 String cancelReason,
                                 String errorCode,
                                 String errorMessage) {

    public static final String FAILURE_CLEANUP_FAILED = "SYSTEM_FAILURE_CLEANUP:FAILED";
    public static final String FAILURE_CLEANUP_DEAD = "SYSTEM_FAILURE_CLEANUP:DEAD";

    public RagIngestJobEntity {
        requireText(tenantId, "租户ID");
        requireText(knowledgeBaseId, "知识库ID");
        requireText(documentId, "文档ID");
        requireText(versionId, "文档版本ID");
        requireText(jobId, "摄取任务ID");
        requireText(idempotencyKey, "幂等键");
        if (operation == null || generation < 1 || status == null || checkpoint == null
                || attemptCount < 0 || maxAttempts < 1
                || attemptCount > maxAttempts || fencingToken < 0 || revision < 0) {
            throw new IllegalArgumentException("摄取任务参数非法");
        }
        if (status == RagIngestJobStatus.RUNNING && lease == null) {
            throw new IllegalArgumentException("运行中的摄取任务必须持有租约");
        }
        if (status != RagIngestJobStatus.RUNNING && status != RagIngestJobStatus.CANCEL_REQUESTED && lease != null) {
            throw new IllegalArgumentException("非运行态摄取任务不能持有租约");
        }
        if (status == RagIngestJobStatus.RETRYING && nextRetryAt == null
                || status != RagIngestJobStatus.RETRYING && nextRetryAt != null) {
            throw new IllegalArgumentException("摄取任务状态与重试时间不一致");
        }
        if (status == RagIngestJobStatus.COMPLETED && checkpoint.stage() != RagIngestStage.COMPLETED
                || status != RagIngestJobStatus.COMPLETED && checkpoint.stage() == RagIngestStage.COMPLETED) {
            throw new IllegalArgumentException("摄取任务状态与完成检查点不一致");
        }
        if (status == RagIngestJobStatus.CANCEL_REQUESTED && (cancelReason == null || cancelReason.isBlank())) {
            throw new IllegalArgumentException("取消中的摄取任务必须记录原因");
        }
    }

    /** 创建尚未领取的摄取任务。 */
    public static RagIngestJobEntity pending(String tenantId, String knowledgeBaseId, String documentId,
                                             String versionId, String jobId, String idempotencyKey,
                                             RagIngestOperation operation, long generation, int maxAttempts) {
        return new RagIngestJobEntity(tenantId, knowledgeBaseId, documentId, versionId, jobId, idempotencyKey,
                operation, generation, RagIngestJobStatus.PENDING, RagIngestCheckpoint.initial(), 0,
                maxAttempts, null, null,
                0L, 0L, null, null, null);
    }

    /** 使用单调递增 fencing token 领取任务或接管过期租约。 */
    public RagIngestJobEntity claim(String leaseOwner, long newFencingToken, Instant now, Duration leaseDuration) {
        requireTime(now, leaseDuration);
        if (!status.claimable() || nextRetryAt != null && nextRetryAt.isAfter(now)) {
            throw domainError("RAG_INGEST_NOT_CLAIMABLE", "摄取任务当前不可领取");
        }
        if (status == RagIngestJobStatus.RUNNING && lease != null && !lease.expiredAt(now)) {
            throw domainError("RAG_INGEST_LEASE_ACTIVE", "摄取任务租约仍然有效");
        }
        if (newFencingToken <= fencingToken) {
            throw domainError("RAG_INGEST_FENCE_STALE", "摄取任务 fencing token 已过期");
        }
        if (attemptCount >= maxAttempts) {
            throw domainError("RAG_INGEST_ATTEMPTS_EXHAUSTED", "摄取任务已达到最大尝试次数");
        }
        return copy(RagIngestJobStatus.RUNNING, checkpoint, attemptCount + 1, null,
                new RagLease(requireText(leaseOwner, "租约持有者"), now.plus(leaseDuration)), newFencingToken,
                null, null, null);
    }

    /** 由当前 Worker 续租，过期或旧 fencing token 均拒绝。 */
    public RagIngestJobEntity renewLease(String leaseOwner, long expectedFencingToken,
                                         Instant now, Duration leaseDuration) {
        assertExternalCallAllowed(leaseOwner, expectedFencingToken, now);
        requireTime(now, leaseDuration);
        return copy(status, checkpoint, attemptCount, nextRetryAt,
                new RagLease(leaseOwner, now.plus(leaseDuration)), fencingToken,
                cancelReason, errorCode, errorMessage);
    }

    /** 推进持久化检查点，禁止阶段或处理进度倒退。 */
    public RagIngestJobEntity advance(String leaseOwner, long expectedFencingToken, Instant now,
                                      RagIngestCheckpoint target) {
        assertExternalCallAllowed(leaseOwner, expectedFencingToken, now);
        if (operation == RagIngestOperation.DELETE) {
            throw domainError("RAG_DELETE_CHECKPOINT_INVALID", "删除任务不能使用摄取阶段推进方法");
        }
        if (target == null || target.stage() == RagIngestStage.COMPLETED || !checkpoint.canAdvanceTo(target)) {
            throw domainError("RAG_INGEST_CHECKPOINT_REGRESSION", "摄取检查点不能倒退、跳级或直接完成");
        }
        return copy(status, target, attemptCount, nextRetryAt, lease, fencingToken,
                cancelReason, errorCode, errorMessage);
    }

    /** 幂等请求取消；终态任务不会被重新打开。 */
    public RagIngestJobEntity requestCancel(String reason) {
        if (operation == RagIngestOperation.DELETE) {
            throw domainError("RAG_DELETE_NOT_CANCELLABLE", "删除已经开始后不能取消");
        }
        if (status == RagIngestJobStatus.CANCEL_REQUESTED || status == RagIngestJobStatus.CANCELLED) {
            return this;
        }
        if (status.terminal()) {
            throw domainError("RAG_INGEST_ALREADY_TERMINAL", "终态摄取任务不能取消");
        }
        return copy(RagIngestJobStatus.CANCEL_REQUESTED, checkpoint, attemptCount, null, lease,
                fencingToken, normalizeReason(reason), errorCode, errorMessage);
    }

    /** 终止失败的副作用清理未完成，转入可接管清理态。 */
    public RagIngestJobEntity requestFailureCleanup(boolean dead, String code, String message) {
        if (status != RagIngestJobStatus.RUNNING) {
            throw domainError("RAG_INGEST_CLEANUP_STATE_INVALID", "只有运行中任务可转入失败清理");
        }
        return copy(RagIngestJobStatus.CANCEL_REQUESTED, checkpoint, attemptCount, null, lease,
                fencingToken, dead ? FAILURE_CLEANUP_DEAD : FAILURE_CLEANUP_FAILED,
                normalizeCode(code), normalizeMessage(message));
    }

    /** 在取消屏障生效后将任务推进为已取消。 */
    public RagIngestJobEntity markCancelled(String leaseOwner, long expectedFencingToken, Instant now) {
        if (status == RagIngestJobStatus.CANCELLED) {
            return this;
        }
        if (status != RagIngestJobStatus.CANCEL_REQUESTED) {
            throw domainError("RAG_INGEST_CANCEL_NOT_REQUESTED", "摄取任务尚未请求取消");
        }
        if (lease != null) {
            assertFence(leaseOwner, expectedFencingToken, now, false);
        }
        return copy(RagIngestJobStatus.CANCELLED, checkpoint, attemptCount, null, null,
                fencingToken, cancelReason, null, null);
    }

    /** 失败副作用已清理，按预定目标关闭为 FAILED/DEAD。 */
    public RagIngestJobEntity markFailedAfterCleanup(String leaseOwner, long expectedFencingToken, Instant now) {
        if (status != RagIngestJobStatus.CANCEL_REQUESTED
                || !FAILURE_CLEANUP_FAILED.equals(cancelReason) && !FAILURE_CLEANUP_DEAD.equals(cancelReason)) {
            throw domainError("RAG_INGEST_CLEANUP_STATE_INVALID", "摄取任务不在失败清理状态");
        }
        assertFence(leaseOwner, expectedFencingToken, now, true);
        RagIngestJobStatus target = FAILURE_CLEANUP_DEAD.equals(cancelReason)
                ? RagIngestJobStatus.DEAD : RagIngestJobStatus.FAILED;
        return copy(target, checkpoint, attemptCount, null, null, fencingToken,
                null, errorCode, errorMessage);
    }

    /** 完成已验证的任务，完成前不允许跳过 VERIFYING。 */
    public RagIngestJobEntity complete(String leaseOwner, long expectedFencingToken, Instant now) {
        assertExternalCallAllowed(leaseOwner, expectedFencingToken, now);
        if (operation == RagIngestOperation.DELETE) {
            throw domainError("RAG_DELETE_COMPLETE_METHOD_INVALID", "删除任务必须使用删除完成方法");
        }
        if (checkpoint.stage() != RagIngestStage.VERIFYING) {
            throw domainError("RAG_INGEST_NOT_VERIFIED", "摄取任务完成前必须验证索引");
        }
        if (checkpoint.totalChunks() < 1
                || checkpoint.processedChunks() != checkpoint.totalChunks()
                || checkpoint.vectorUpsertIndex() != checkpoint.totalChunks()) {
            throw domainError("RAG_INGEST_INDEX_INCOMPLETE", "摄取任务的分块或向量索引尚未完整");
        }
        RagIngestCheckpoint completed = new RagIngestCheckpoint(RagIngestStage.COMPLETED,
                checkpoint.processedChunks(), checkpoint.totalChunks(), checkpoint.embeddingBatchIndex(),
                checkpoint.vectorUpsertIndex());
        return copy(RagIngestJobStatus.COMPLETED, completed, attemptCount, null, null,
                fencingToken, null, null, null);
    }

    /** 推进独立删除阶段，禁止跨阶段、倒退或混入摄取检查点。 */
    public RagIngestJobEntity advanceDeletion(String leaseOwner, long expectedFencingToken, Instant now,
                                               RagIngestStage targetStage) {
        assertExternalCallAllowed(leaseOwner, expectedFencingToken, now);
        if (operation != RagIngestOperation.DELETE || !validDeleteTransition(checkpoint.stage(), targetStage)) {
            throw domainError("RAG_DELETE_CHECKPOINT_REGRESSION", "删除检查点不能倒退或跳级");
        }
        RagIngestCheckpoint target = new RagIngestCheckpoint(targetStage, 0, 0, 0, 0);
        return copy(status, target, attemptCount, nextRetryAt, lease, fencingToken,
                cancelReason, errorCode, errorMessage);
    }

    /** 在全部删除副作用已验证后以零分块检查点完成任务。 */
    public RagIngestJobEntity completeDeletion(String leaseOwner, long expectedFencingToken, Instant now) {
        assertExternalCallAllowed(leaseOwner, expectedFencingToken, now);
        if (operation != RagIngestOperation.DELETE || checkpoint.stage() != RagIngestStage.DELETING_SOURCE) {
            throw domainError("RAG_DELETE_NOT_VERIFIED", "删除任务尚未完成全部清理阶段");
        }
        return copy(RagIngestJobStatus.COMPLETED,
                new RagIngestCheckpoint(RagIngestStage.COMPLETED, 0, 0, 0, 0),
                attemptCount, null, null, fencingToken, null, null, null);
    }

    /** 将失败或耗尽的删除任务重新排队，保留幂等检查点并清除旧错误。 */
    public RagIngestJobEntity requeueDeletion() {
        if (operation != RagIngestOperation.DELETE) {
            throw domainError("RAG_DELETE_REQUEUE_OPERATION_INVALID", "只有删除任务可以重新排队");
        }
        if (status == RagIngestJobStatus.COMPLETED || status == RagIngestJobStatus.PENDING
                || status == RagIngestJobStatus.RUNNING || status == RagIngestJobStatus.RETRYING) return this;
        if (status != RagIngestJobStatus.FAILED && status != RagIngestJobStatus.DEAD) {
            throw domainError("RAG_DELETE_REQUEUE_STATE_INVALID", "删除任务当前不能重新排队");
        }
        return copy(RagIngestJobStatus.PENDING, checkpoint, 0, null, null,
                fencingToken, null, null, null);
    }

    /** 记录可重试故障；达到最大尝试次数后进入 DEAD。 */
    public RagIngestJobEntity failRetryable(String leaseOwner, long expectedFencingToken, Instant now,
                                            Instant retryAt, String code, String message) {
        assertExternalCallAllowed(leaseOwner, expectedFencingToken, now);
        if (retryAt == null || retryAt.isBefore(now)) {
            throw new IllegalArgumentException("重试时间不能早于当前时间");
        }
        RagIngestJobStatus target = attemptCount >= maxAttempts ? RagIngestJobStatus.DEAD : RagIngestJobStatus.RETRYING;
        return copy(target, checkpoint, attemptCount, target == RagIngestJobStatus.DEAD ? null : retryAt,
                null, fencingToken, null, normalizeCode(code), normalizeMessage(message));
    }

    /** 记录不可重试故障并关闭当前租约。 */
    public RagIngestJobEntity failTerminal(String leaseOwner, long expectedFencingToken, Instant now,
                                           String code, String message) {
        assertExternalCallAllowed(leaseOwner, expectedFencingToken, now);
        return copy(RagIngestJobStatus.FAILED, checkpoint, attemptCount, null, null,
                fencingToken, null, normalizeCode(code), normalizeMessage(message));
    }

    /** 在每次解析、Embedding、向量写入等外部副作用前执行取消和 fencing 屏障。 */
    public void assertExternalCallAllowed(String leaseOwner, long expectedFencingToken, Instant now) {
        if (status != RagIngestJobStatus.RUNNING) {
            throw domainError("RAG_INGEST_SIDE_EFFECT_BLOCKED", "摄取任务状态禁止继续调用外部服务");
        }
        assertFence(leaseOwner, expectedFencingToken, now, true);
    }

    private void assertFence(String leaseOwner, long expectedFencingToken, Instant now, boolean requireActiveLease) {
        if (now == null) {
            throw new IllegalArgumentException("租约校验时间不能为空");
        }
        if (lease == null || !lease.owner().equals(leaseOwner) || fencingToken != expectedFencingToken) {
            throw domainError("RAG_INGEST_FENCE_STALE", "摄取任务租约或 fencing token 已过期");
        }
        if (requireActiveLease && lease.expiredAt(now)) {
            throw domainError("RAG_INGEST_LEASE_EXPIRED", "摄取任务租约已过期");
        }
    }

    private RagIngestJobEntity copy(RagIngestJobStatus targetStatus, RagIngestCheckpoint targetCheckpoint,
                                    int targetAttempts, Instant targetRetryAt, RagLease targetLease,
                                    long targetFencingToken, String targetCancelReason,
                                    String targetErrorCode, String targetErrorMessage) {
        return new RagIngestJobEntity(tenantId, knowledgeBaseId, documentId, versionId, jobId, idempotencyKey,
                operation, generation, targetStatus, targetCheckpoint, targetAttempts, maxAttempts,
                targetRetryAt, targetLease,
                targetFencingToken, revision + 1, targetCancelReason, targetErrorCode, targetErrorMessage);
    }

    private boolean validDeleteTransition(RagIngestStage current, RagIngestStage target) {
        return current == target
                || current == RagIngestStage.RECEIVED && target == RagIngestStage.DELETING_VECTORS
                || current == RagIngestStage.DELETING_VECTORS && target == RagIngestStage.DELETING_CHUNKS
                || current == RagIngestStage.DELETING_CHUNKS && target == RagIngestStage.DELETING_SOURCE;
    }

    private static void requireTime(Instant now, Duration duration) {
        if (now == null || duration == null || duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException("租约时间参数非法");
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + "不能为空");
        }
        return value;
    }

    private static String normalizeReason(String value) {
        return value == null || value.isBlank() ? "管理员取消摄取任务" : trim(value, 512);
    }

    private static String normalizeCode(String value) {
        return value == null || value.isBlank() ? "RAG_INGEST_FAILED" : trim(value, 128);
    }

    private static String normalizeMessage(String value) {
        return value == null || value.isBlank() ? "摄取任务执行失败" : trim(value, 512);
    }

    private static String trim(String value, int maxLength) {
        String normalized = value.trim();
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength);
    }

    private static AppException domainError(String code, String message) {
        return new AppException(code, message);
    }
}
