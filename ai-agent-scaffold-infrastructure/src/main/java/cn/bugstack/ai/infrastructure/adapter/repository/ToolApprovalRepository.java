package cn.bugstack.ai.infrastructure.adapter.repository;

import cn.bugstack.ai.domain.agent.adapter.repository.IToolApprovalRepository;
import cn.bugstack.ai.domain.agent.model.entity.ToolApprovalRequestEntity;
import cn.bugstack.ai.infrastructure.dao.IToolApprovalDao;
import cn.bugstack.ai.infrastructure.dao.po.ToolApprovalRequestPO;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Repository
public class ToolApprovalRepository implements IToolApprovalRepository {
    private final IToolApprovalDao dao; private final ObjectMapper objectMapper;
    public ToolApprovalRepository(IToolApprovalDao dao, ObjectMapper objectMapper) { this.dao = dao; this.objectMapper = objectMapper; }

    @Override public ToolApprovalRequestEntity createOrReplay(ToolApprovalRequestEntity request) {
        try { dao.insert(po(request)); return request; }
        catch (DuplicateKeyException duplicate) {
            return entity(dao.queryByFunctionCall(request.getTenantId(), request.getSourceRunId(), request.getFunctionCallId()));
        }
    }
    @Override public List<ToolApprovalRequestEntity> queryAfter(String tenantId, String userId, long afterSequence, int limit) {
        return dao.queryAfter(tenantId, userId, afterSequence, limit).stream().map(this::entity).toList();
    }
    @Override public ToolApprovalRequestEntity query(String tenantId, String userId, String approvalId) {
        return entity(dao.query(tenantId, userId, approvalId));
    }
    @Override @Transactional(rollbackFor = Exception.class)
    public int decide(String tenantId, String userId, String approvalId, String decision, String comment,
                      Map<String, Object> amendedInput, String decidedBy, long expectedRevision, LocalDateTime decidedAt) {
        int changed = dao.decide(tenantId, userId, approvalId, decision, comment, json(amendedInput), decidedBy,
                expectedRevision, decidedAt);
        return changed;
    }
    @Override @Transactional(rollbackFor = Exception.class)
    public int decideExpired(LocalDateTime now, int limit) {
        int changed = 0;
        for (ToolApprovalRequestPO value : dao.queryExpired(now, limit)) {
            if (dao.decideTimeout(value.getTenantId(), value.getApprovalId(), value.getRevision(),
                    value.getTimeoutDecision(), now) == 1) {
                changed++;
            }
        }
        return changed;
    }
    @Override public int decideTimeout(String tenantId, String approvalId, long revision, String decision,
                                       LocalDateTime decidedAt) {
        return dao.decideTimeout(tenantId, approvalId, revision, decision, decidedAt);
    }
    private ToolApprovalRequestPO po(ToolApprovalRequestEntity value) {
        ToolApprovalRequestPO po = new ToolApprovalRequestPO(); po.setSequence(value.getSequence()); po.setApprovalId(value.getApprovalId());
        po.setTenantId(value.getTenantId()); po.setUserId(value.getUserId()); po.setParentRunId(value.getParentRunId());
        po.setSourceRunId(value.getSourceRunId()); po.setParentSessionId(value.getParentSessionId()); po.setParentAgentId(value.getParentAgentId());
        po.setFunctionCallId(value.getFunctionCallId()); po.setToolCode(value.getToolCode()); po.setRequestedInputJson(json(value.getRequestedInput()));
        po.setAmendedInputJson(json(value.getAmendedInput())); po.setAllowedSubAgentIdsJson(json(value.getAllowedSubAgentIds()));
        po.setSuggestionsJson(json(value.getSuggestions())); po.setStatus(value.getStatus()); po.setTimeoutDecision(value.getTimeoutDecision());
        po.setExpiresAt(value.getExpiresAt()); po.setDecision(value.getDecision()); po.setComment(value.getComment());
        po.setRevision(value.getRevision()); po.setTraceId(value.getTraceId()); return po;
    }
    private ToolApprovalRequestEntity entity(ToolApprovalRequestPO value) {
        if (value == null) return null;
        return ToolApprovalRequestEntity.builder().sequence(value.getSequence()).approvalId(value.getApprovalId())
                .tenantId(value.getTenantId()).userId(value.getUserId()).parentRunId(value.getParentRunId())
                .sourceRunId(value.getSourceRunId()).parentSessionId(value.getParentSessionId()).parentAgentId(value.getParentAgentId())
                .functionCallId(value.getFunctionCallId()).toolCode(value.getToolCode())
                .requestedInput(map(value.getRequestedInputJson())).amendedInput(map(value.getAmendedInputJson()))
                .allowedSubAgentIds(list(value.getAllowedSubAgentIdsJson())).suggestions(list(value.getSuggestionsJson()))
                .status(value.getStatus()).timeoutDecision(value.getTimeoutDecision()).expiresAt(value.getExpiresAt())
                .decision(value.getDecision()).comment(value.getComment()).revision(value.getRevision())
                .traceId(value.getTraceId()).build();
    }
    private String json(Object value) { try { return objectMapper.writeValueAsString(value == null ? Map.of() : value); }
        catch (Exception e) { throw new IllegalArgumentException("TOOL_APPROVAL_JSON_INVALID", e); } }
    private Map<String,Object> map(String value) { try { return value == null ? Map.of() : objectMapper.readValue(value, new TypeReference<>(){}); }
        catch (Exception e) { throw new IllegalStateException("TOOL_APPROVAL_DATA_INVALID", e); } }
    private List<String> list(String value) { try { return value == null ? List.of() : objectMapper.readValue(value, new TypeReference<>(){}); }
        catch (Exception e) { throw new IllegalStateException("TOOL_APPROVAL_DATA_INVALID", e); } }
}
