package cn.bugstack.ai.domain.run.adapter.repository;

import cn.bugstack.ai.domain.run.model.ChatRunEntity;
import cn.bugstack.ai.domain.run.model.RunStatus;

import java.time.LocalDateTime;

/**
 * 会话运行仓储。
 */
public interface IChatRunRepository {

    /**
     * 新增运行；参数是运行实体；返回影响行数。
     */
    int insert(ChatRunEntity run);

    /**
     * 查询运行；参数是可信租户、用户和运行ID；返回运行实体。
     */
    ChatRunEntity query(String tenantId, String userId, String runId);

    /**
     * 锁定运行；参数是可信租户、用户和运行ID；返回运行实体。
     */
    ChatRunEntity lock(String tenantId, String userId, String runId);

    /**
     * 按版本迁移状态；参数是运行、原状态、目标状态和终态信息；返回影响行数。
     */
    int transition(String tenantId, String userId, String runId, RunStatus expectedStatus, RunStatus targetStatus,
                   int expectedVersion, String reason, LocalDateTime cancelRequestedAt, LocalDateTime finishedAt);

    /**
     * 绑定用户消息；参数是运行和消息ID；返回影响行数。
     */
    int bindUserMessage(String tenantId, String userId, String runId, String messageId, int expectedVersion);

    /**
     * 建立后继关系；参数是旧运行、新运行和引导指令；返回影响行数。
     */
    int bindSuccessor(String tenantId, String userId, String runId, String successorRunId,
                      String steerInstruction, int expectedVersion);

    /**
     * 更新运行上下文版本；参数是运行和新版本；返回影响行数。
     */
    int updateContextRevision(String tenantId, String userId, String runId, long contextRevision, int expectedVersion);
}
