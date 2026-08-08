package cn.bugstack.ai.domain.context.adapter.repository;

import cn.bugstack.ai.domain.context.model.ContextCompactionTaskEntity;
import cn.bugstack.ai.domain.context.model.ContextTaskCreateCommand;

import java.util.List;

/**
 * 上下文压缩任务仓储。
 */
public interface IContextCompactionTaskRepository {

    /**
     * 创建幂等压缩任务。
     */
    ContextCompactionTaskEntity createIfAbsent(ContextTaskCreateCommand command);

    /**
     * 查询压缩任务。
     */
    ContextCompactionTaskEntity queryByTaskId(String taskId);

    /**
     * 查询会话未完成任务。
     */
    List<ContextCompactionTaskEntity> queryUnfinished(String tenantId, String userId, String sessionId);

    /**
     * 查询会话最近一次压缩任务。
     */
    ContextCompactionTaskEntity queryLatest(String tenantId, String userId, String sessionId);

    /**
     * 领取任务；成功返回 true。
     */
    boolean claim(String taskId);

    /**
     * 完成任务。
     */
    int complete(String taskId);

    /**
     * 标记任务重试。
     */
    int retry(String taskId, String errorMessage);

    /**
     * 标记任务进入死信。
     */
    int dead(String taskId, String errorMessage);

    /**
     * 废弃覆盖失效消息的任务。
     */
    int staleOverlapping(String tenantId, String userId, String sessionId, String runId,
                         Integer minSequence, Integer maxSequence, String reason);
}
