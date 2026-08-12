package cn.bugstack.ai.domain.agent.model.entity;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/** 独立 Resume Worker 原子领取的一批父运行恢复数据。 */
@Data
@Builder
public class ParentResumeBatchEntity {
    private String tenantId;
    private String userId;
    private String parentRunId;
    private String parentSessionId;
    private String parentAgentId;
    private String traceId;
    private Long requestedVersion;
    private Long fencingToken;
    private List<InboxItem> items;

    public long lastSequence() {
        return items == null || items.isEmpty() ? 0L : items.get(items.size() - 1).sequence();
    }

    /** 按数据库自增序列有序合并的一条子 Agent 摘要。 */
    public record InboxItem(long sequence, String taskId, String childAgentId,
                            String summary, String taskStatus) { }
}
