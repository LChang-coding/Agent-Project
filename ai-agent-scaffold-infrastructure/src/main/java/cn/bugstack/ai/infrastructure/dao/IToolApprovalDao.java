package cn.bugstack.ai.infrastructure.dao;

import cn.bugstack.ai.infrastructure.dao.po.ToolApprovalRequestPO;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface IToolApprovalDao {
    int insert(ToolApprovalRequestPO value);
    ToolApprovalRequestPO queryByFunctionCall(@Param("tenantId") String tenantId, @Param("sourceRunId") String sourceRunId,
                                              @Param("functionCallId") String functionCallId);
    ToolApprovalRequestPO query(@Param("tenantId") String tenantId, @Param("userId") String userId,
                                @Param("approvalId") String approvalId);
    List<ToolApprovalRequestPO> queryAfter(@Param("tenantId") String tenantId, @Param("userId") String userId,
                                           @Param("afterSequence") long afterSequence, @Param("limit") int limit);
    List<ToolApprovalRequestPO> queryPendingBySession(@Param("tenantId") String tenantId,
                                                      @Param("userId") String userId,
                                                      @Param("parentSessionId") String parentSessionId,
                                                      @Param("limit") int limit);
    int decide(@Param("tenantId") String tenantId, @Param("userId") String userId, @Param("approvalId") String approvalId,
               @Param("decision") String decision, @Param("comment") String comment,
               @Param("amendedInputJson") String amendedInputJson, @Param("decidedBy") String decidedBy,
               @Param("expectedRevision") long expectedRevision, @Param("decidedAt") LocalDateTime decidedAt);
    List<ToolApprovalRequestPO> queryExpired(@Param("now") LocalDateTime now, @Param("limit") int limit);
    int decideTimeout(@Param("tenantId") String tenantId, @Param("approvalId") String approvalId,
                      @Param("revision") long revision, @Param("decision") String decision,
                      @Param("decidedAt") LocalDateTime decidedAt);
}
