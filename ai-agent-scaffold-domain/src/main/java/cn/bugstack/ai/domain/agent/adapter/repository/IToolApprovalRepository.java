package cn.bugstack.ai.domain.agent.adapter.repository;

import cn.bugstack.ai.domain.agent.model.entity.ToolApprovalRequestEntity;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public interface IToolApprovalRepository {
    ToolApprovalRequestEntity createOrReplay(ToolApprovalRequestEntity request);
    ToolApprovalRequestEntity query(String tenantId, String userId, String approvalId);
    List<ToolApprovalRequestEntity> queryAfter(String tenantId, String userId, long afterSequence, int limit);
    int decide(String tenantId, String userId, String approvalId, String decision, String comment,
               Map<String, Object> amendedInput, String decidedBy, long expectedRevision, LocalDateTime decidedAt);
    int decideExpired(LocalDateTime now, int limit);
    int decideTimeout(String tenantId, String approvalId, long revision, String decision, LocalDateTime decidedAt);
}
