package cn.bugstack.ai.domain.agent.adapter.repository;

import cn.bugstack.ai.domain.agent.model.entity.ParentResumeBatchEntity;
import cn.bugstack.ai.domain.agent.model.entity.SubagentTaskEntity;

import java.time.Duration;
import java.time.LocalDateTime;

/** Parent Inbox 与主 Agent 恢复请求的权威持久化端口。 */
public interface IParentResumeRepository {
    /** 子任务创建后立即准备父运行等待记录。 */
    void prepareWait(SubagentTaskEntity task, LocalDateTime now);

    /** 首次委派时原子占有父运行；同一父运行只允许创建一批子任务。 */
    boolean tryPrepareWait(SubagentTaskEntity task, LocalDateTime now);

    /** 保存主 Agent 自行完成的草稿，并标记父侧屏障已就绪。 */
    boolean markParentReady(String tenantId, String parentRunId, String parentDraft, LocalDateTime now);

    /** 判断父运行是否仍在等待唯一汇总。 */
    boolean isAwaitingSummary(String tenantId, String parentRunId);

    /** 查询父恢复账本状态；不存在时返回 null。 */
    String queryStatus(String tenantId, String parentRunId);

    /** 任务终态变化后尝试闭合双屏障，CAS 失败时不重复通知。 */
    boolean tryActivate(String tenantId, String parentRunId, LocalDateTime now);

    /** 原子登记一条子 Agent 结果；只有双屏障齐备时才唯一申请恢复。 */
    boolean registerResult(SubagentTaskEntity task, String callbackOwner, LocalDateTime now);

    /** 单飞领取一个父运行，并读取该运行的全部终态结果。 */
    ParentResumeBatchEntity claim(String tenantId, String parentRunId, String workerId,
                                  LocalDateTime now, Duration leaseDuration, int limit);

    int renewLease(String tenantId, String parentRunId, String workerId, long fencingToken,
                   LocalDateTime now, Duration leaseDuration);

    /** 在当前事务中锁定并校验恢复租约，防止失去 fencing 的 Worker 提交最终消息。 */
    boolean lockOwnedLease(String tenantId, String parentRunId, String workerId,
                           long fencingToken, LocalDateTime now);

    /** 确认本批已交付；ACK 子任务、推进 cursor，并为竞态新增结果安排下一轮恢复。 */
    int complete(ParentResumeBatchEntity batch, String workerId, long fencingToken, LocalDateTime deliveredAt);

    /** 当前 Worker 失败时释放租约，保留恢复请求供 Kafka 重试或恢复扫描。 */
    int retry(String tenantId, String parentRunId, String workerId, long fencingToken,
              LocalDateTime nextAttemptAt, String error);

    /** 定时为丢失 Kafka 唤醒的到期请求重建 Outbox；不直接执行主 Agent。 */
    int recoverDue(LocalDateTime now, int limit);
}
