package cn.bugstack.ai.domain.context.service;

import cn.bugstack.ai.domain.context.adapter.repository.IContextCacheRepository;
import cn.bugstack.ai.domain.context.adapter.repository.IContextCompactionTaskRepository;
import cn.bugstack.ai.domain.context.adapter.repository.IConversationMemoryRepository;
import cn.bugstack.ai.domain.session.model.entity.ChatMessageEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Comparator;
import java.util.List;

/**
 * 上下文失效服务。
 * <p>负责取消或引导时废弃压缩任务、恢复安全摘要并清理缓存。</p>
 */
@Service
public class ContextInvalidationService {

    /** 废弃未完成或重叠压缩任务。 */
    private final IContextCompactionTaskRepository taskRepository;
    /** 作废污染摘要并恢复安全祖先。 */
    private final IConversationMemoryRepository memoryRepository;
    /** 提交后清除可重建上下文缓存。 */
    private final IContextCacheRepository cacheRepository;

    /**
     * 创建上下文失效服务；参数是压缩、记忆和缓存仓储；返回服务实例。
     */
    public ContextInvalidationService(IContextCompactionTaskRepository taskRepository,
                                      IConversationMemoryRepository memoryRepository,
                                      IContextCacheRepository cacheRepository) {
        this.taskRepository = taskRepository;
        this.memoryRepository = memoryRepository;
        this.cacheRepository = cacheRepository;
    }

    /**
     * 失效运行上下文；参数是可信身份、运行消息和原因；无返回值。
     */
    @Transactional(rollbackFor = Exception.class)
    public void invalidateRun(String tenantId, String userId, String sessionId, String runId,
                              List<ChatMessageEntity> runMessages, String reason) {
        if (runMessages == null || runMessages.isEmpty()) {
            // 即使尚未落消息，也按 runId 废弃其未完成任务并清缓存。
            taskRepository.staleOverlapping(tenantId, userId, sessionId, runId,
                    Integer.MAX_VALUE, Integer.MIN_VALUE, reason);
            evictAfterCommit(tenantId, userId, sessionId);
            return;
        }
        int minSequence = runMessages.stream().map(ChatMessageEntity::getSequenceNo).filter(value -> value != null)
                .min(Comparator.naturalOrder()).orElse(Integer.MAX_VALUE);
        int maxSequence = runMessages.stream().map(ChatMessageEntity::getSequenceNo).filter(value -> value != null)
                .max(Comparator.naturalOrder()).orElse(Integer.MIN_VALUE);
        if (minSequence != Integer.MAX_VALUE) {
            // 已完成摘要只要覆盖任一失效消息就必须作废，并恢复不越过该序号的祖先摘要。
            taskRepository.staleOverlapping(tenantId, userId, sessionId, runId, minSequence, maxSequence, reason);
            memoryRepository.invalidateCoveringAndRestore(tenantId, userId, sessionId, minSequence);
        }
        evictAfterCommit(tenantId, userId, sessionId);
    }

    /**
     * 失效整个会话派生上下文；参数是可信身份、会话和原因；无返回值。
     */
    @Transactional(rollbackFor = Exception.class)
    public void invalidateSession(String tenantId, String userId, String sessionId, String reason) {
        taskRepository.staleOverlapping(tenantId, userId, sessionId, null, 1, Integer.MAX_VALUE, reason);
        memoryRepository.invalidateCoveringAndRestore(tenantId, userId, sessionId, 1);
        evictAfterCommit(tenantId, userId, sessionId);
    }

    /** 在事务提交后清理可重建缓存，回滚时保留原可见事实。 */
    private void evictAfterCommit(String tenantId, String userId, String sessionId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            cacheRepository.evictSession(tenantId, userId, sessionId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                cacheRepository.evictSession(tenantId, userId, sessionId);
            }
        });
    }
}
