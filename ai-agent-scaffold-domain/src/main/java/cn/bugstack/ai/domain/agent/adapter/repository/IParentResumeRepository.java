package cn.bugstack.ai.domain.agent.adapter.repository;

import cn.bugstack.ai.domain.agent.model.entity.ParentResumeBatchEntity;
import cn.bugstack.ai.domain.agent.model.entity.SubagentTaskEntity;

import java.time.Duration;
import java.time.LocalDateTime;

/** Parent Inbox 与主 Agent 恢复请求的权威持久化端口。 */
public interface IParentResumeRepository {
    /** 原子登记结果、更新恢复版本并写 PARENT_RESUME_REQUESTED Outbox。 */
    boolean registerResult(SubagentTaskEntity task, String callbackOwner, LocalDateTime now);

    /** 单飞领取一个父运行，并读取当前 cursor 之后的有序摘要。 */
    ParentResumeBatchEntity claim(String tenantId, String parentRunId, String workerId,
                                  LocalDateTime now, Duration leaseDuration, int limit);

    int renewLease(String tenantId, String parentRunId, String workerId, long fencingToken,
                   LocalDateTime now, Duration leaseDuration);

    /** 确认本批已交付；ACK 子任务、推进 cursor，并为竞态新增结果安排下一轮恢复。 */
    int complete(ParentResumeBatchEntity batch, String workerId, long fencingToken, LocalDateTime deliveredAt);

    /** 当前 Worker 失败时释放租约，保留恢复请求供 Kafka 重试或恢复扫描。 */
    int retry(String tenantId, String parentRunId, String workerId, long fencingToken,
              LocalDateTime nextAttemptAt, String error);

    /** 定时为丢失 Kafka 唤醒的到期请求重建 Outbox；不直接执行主 Agent。 */
    int recoverDue(LocalDateTime now, int limit);
}
