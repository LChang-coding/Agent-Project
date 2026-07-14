package cn.bugstack.ai.domain.context.service;

import cn.bugstack.ai.domain.context.adapter.repository.IContextCacheRepository;
import cn.bugstack.ai.domain.context.adapter.repository.IContextCompactionTaskRepository;
import cn.bugstack.ai.domain.context.adapter.repository.IConversationMemoryRepository;
import cn.bugstack.ai.domain.session.model.entity.ChatMessageEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;

/**
 * 上下文失效服务。
 * <p>负责取消或引导时废弃压缩任务、恢复安全摘要并清理缓存。</p>
 */
@Service
public class ContextInvalidationService {

    private final IContextCompactionTaskRepository taskRepository;
    private final IConversationMemoryRepository memoryRepository;
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
            taskRepository.staleOverlapping(tenantId, userId, sessionId, runId,
                    Integer.MAX_VALUE, Integer.MIN_VALUE, reason);
            cacheRepository.evictSession(tenantId, userId, sessionId);
            return;
        }
        int minSequence = runMessages.stream().map(ChatMessageEntity::getSequenceNo).filter(value -> value != null)
                .min(Comparator.naturalOrder()).orElse(Integer.MAX_VALUE);
        int maxSequence = runMessages.stream().map(ChatMessageEntity::getSequenceNo).filter(value -> value != null)
                .max(Comparator.naturalOrder()).orElse(Integer.MIN_VALUE);
        if (minSequence != Integer.MAX_VALUE) {
            taskRepository.staleOverlapping(tenantId, userId, sessionId, runId, minSequence, maxSequence, reason);
            memoryRepository.invalidateCoveringAndRestore(tenantId, userId, sessionId, minSequence);
        }
        cacheRepository.evictSession(tenantId, userId, sessionId);
    }
}
