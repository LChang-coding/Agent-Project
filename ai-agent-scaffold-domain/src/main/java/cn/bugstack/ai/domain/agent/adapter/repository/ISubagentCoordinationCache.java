package cn.bugstack.ai.domain.agent.adapter.repository;

import cn.bugstack.ai.domain.agent.model.entity.SubagentTaskEntity;

import java.time.Duration;

/** Redis 只保存可重建的临时实例、Lease 镜像与 Parent Inbox 索引。 */
public interface ISubagentCoordinationCache {
    void putInstance(SubagentTaskEntity task, Duration ttl);
    void heartbeat(String tenantId, String taskId, Duration ttl);
    void addInbox(String tenantId, String parentRunId, String taskId, Duration ttl);
    void removeInstance(String tenantId, String taskId);
    void removeInbox(String tenantId, String parentRunId, String taskId);
}
