package cn.bugstack.ai.domain.agent.adapter.repository;

import cn.bugstack.ai.domain.agent.model.entity.SubagentTaskEntity;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/** 子 Agent 任务、Parent Inbox 与 Outbox 的持久化端口。 */
public interface ISubagentTaskRepository {
    /** 在一个数据库事务中写任务、父运行等待状态和 TASK_READY outbox。 */
    int createBatchAndEnqueue(List<SubagentTaskEntity> tasks);

    List<SubagentTaskEntity> queryByFunctionCall(String tenantId, String parentRunId, String functionCallId);

    List<SubagentTaskEntity> queryByIds(String tenantId, String parentRunId, List<String> taskIds);

    /** 查询当前用户某个父会话下最近的编排任务，供可恢复的运行面板使用。 */
    List<SubagentTaskEntity> queryBySession(String tenantId, String userId, String parentSessionId, int limit);

    /** 原子领取 READY 或租约已过期的 RUNNING 任务，并递增 fencing token。 */
    SubagentTaskEntity claim(String tenantId, String taskId, String workerId, LocalDateTime now, Duration leaseDuration);

    /** 以当前执行 Lease 绑定实际创建的临时会话。 */
    int bindChildSession(String tenantId, String taskId, String workerId, long fencingToken,
                         String childSessionId);

    /** 只有当前 lease owner + fencing token 能续租。 */
    int renewLease(String tenantId, String taskId, String workerId, long fencingToken,
                   LocalDateTime now, Duration leaseDuration);

    /** 原子写终态结果、Parent Inbox 与 RESULT_READY outbox。 */
    int complete(SubagentTaskEntity task, String workerId, long fencingToken);

    int cancel(String tenantId, String parentRunId, List<String> taskIds, LocalDateTime cancelledAt);

    /** 领取一次结果回调权，防止 Kafka 重投重复续跑主 Agent。 */
    boolean claimCallback(String tenantId, String taskId, String callbackOwner, LocalDateTime now);

    int retryCallback(String tenantId, String taskId, String callbackOwner, String error);

    /** 原子完成回调 ACK，并写临时实例清理 Outbox。 */
    int finishCallback(String tenantId, String parentRunId, String taskId,
                       String callbackOwner, LocalDateTime deliveredAt);

    /** 扫描并重新投递已经失去执行者的任务或父回调。 */
    int recoverExpired(LocalDateTime now, Duration callbackLease, int limit);

    /** DLT 后有界地重新生成结果通知，超过最大次数进入 DEAD。 */
    boolean requeueCallback(String tenantId, String parentRunId, String taskId);
}
