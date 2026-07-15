package cn.bugstack.ai.infrastructure.dao;

import cn.bugstack.ai.infrastructure.dao.po.ChatRunPO;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 会话运行 DAO。
 */
public interface IChatRunDao {

    /**
     * 新增运行；参数是持久化对象；返回影响行数。
     */
    int insert(ChatRunPO run);

    /**
     * 查询运行；参数是可信身份和运行ID；返回持久化对象。
     */
    ChatRunPO query(@Param("tenantId") String tenantId, @Param("userId") String userId, @Param("runId") String runId);

    /**
     * 锁定运行；参数是可信身份和运行ID；返回持久化对象。
     */
    ChatRunPO lock(@Param("tenantId") String tenantId, @Param("userId") String userId, @Param("runId") String runId);

    /**
     * 查询会话可执行运行；参数是可信身份和会话；返回活动运行。
     */
    List<ChatRunPO> queryExecutableBySession(@Param("tenantId") String tenantId,
                                             @Param("userId") String userId,
                                             @Param("sessionId") String sessionId);

    /**
     * 按版本迁移状态；参数是状态迁移字段；返回影响行数。
     */
    int transition(@Param("tenantId") String tenantId, @Param("userId") String userId, @Param("runId") String runId,
                   @Param("expectedStatus") String expectedStatus, @Param("targetStatus") String targetStatus,
                   @Param("expectedVersion") int expectedVersion, @Param("reason") String reason,
                   @Param("cancelRequestedAt") LocalDateTime cancelRequestedAt, @Param("finishedAt") LocalDateTime finishedAt);

    /**
     * 绑定用户消息；参数是运行、消息和版本；返回影响行数。
     */
    int bindUserMessage(@Param("tenantId") String tenantId, @Param("userId") String userId,
                        @Param("runId") String runId, @Param("messageId") String messageId,
                        @Param("expectedVersion") int expectedVersion);

    /**
     * 绑定后继运行；参数是运行关系和引导指令；返回影响行数。
     */
    int bindSuccessor(@Param("tenantId") String tenantId, @Param("userId") String userId,
                      @Param("runId") String runId, @Param("successorRunId") String successorRunId,
                      @Param("steerInstruction") String steerInstruction, @Param("expectedVersion") int expectedVersion);

    /**
     * 更新上下文版本；参数是运行、新版本和乐观锁版本；返回影响行数。
     */
    int updateContextRevision(@Param("tenantId") String tenantId, @Param("userId") String userId,
                              @Param("runId") String runId, @Param("contextRevision") long contextRevision,
                              @Param("expectedVersion") int expectedVersion);
}
