package cn.bugstack.ai.domain.agent.service;

import cn.bugstack.ai.domain.agent.adapter.repository.IToolApprovalRepository;
import cn.bugstack.ai.domain.agent.model.entity.AgentToolPermissionEntity;
import cn.bugstack.ai.domain.agent.model.entity.ToolApprovalRequestEntity;
import cn.bugstack.ai.domain.tool.model.entity.ToolInvokeContextEntity;
import cn.bugstack.ai.domain.run.service.RunControlService;
import cn.bugstack.ai.types.exception.AppException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class ToolApprovalService {
    private static final long POLL_INTERVAL_MILLIS = 1000L;
    private static final Set<String> DECISIONS = Set.of("APPROVE", "REJECT", "APPROVE_WITH_CHANGES", "REPLAN");
    private final IToolApprovalRepository repository;
    private final RunControlService runControlService;
    public ToolApprovalService(IToolApprovalRepository repository, RunControlService runControlService) {
        this.repository = repository; this.runControlService = runControlService;
    }

    public ToolApprovalRequestEntity request(ToolInvokeContextEntity context, String toolCode,
                                              Map<String, Object> input, AgentToolPermissionEntity policy) {
        requireContext(context); int timeout = policy.getTimeoutSeconds() == null ? 600 : policy.getTimeoutSeconds();
        String parentRunId = text(context.getOrchestrationRootRunId())
                ? context.getOrchestrationRootRunId() : context.getRunId();
        ToolApprovalRequestEntity request = ToolApprovalRequestEntity.builder()
                .approvalId(UUID.randomUUID().toString()).tenantId(context.getTenantId()).userId(context.getUserId())
                .parentRunId(parentRunId).sourceRunId(context.getRunId()).parentSessionId(context.getSessionId())
                .parentAgentId(context.getAgentId()).functionCallId(context.getFunctionCallId()).toolCode(toolCode)
                .requestedInput(input == null ? Map.of() : Map.copyOf(input))
                .allowedSubAgentIds(context.getAllowedSubAgentIds() == null ? List.of() : List.copyOf(context.getAllowedSubAgentIds()))
                .suggestions(policy.getSuggestions() == null ? List.of() : List.copyOf(policy.getSuggestions()))
                .status("PENDING").timeoutDecision(policy.getTimeoutDecision()).expiresAt(LocalDateTime.now().plusSeconds(timeout))
                .revision(0L).traceId(context.getTraceId()).build();
        return repository.createOrReplay(request);
    }

    public List<ToolApprovalRequestEntity> streamPage(String tenantId, String userId, long afterSequence) {
        requireText(tenantId); requireText(userId);
        return repository.queryAfter(tenantId, userId, Math.max(0, afterSequence), 100);
    }

    public void decide(String tenantId, String userId, String approvalId, String decision, String comment,
                       Map<String, Object> amendedInput, long expectedRevision) {
        requireText(tenantId); requireText(userId); requireText(approvalId);
        String normalized = decision == null ? "" : decision.trim().toUpperCase();
        if (!DECISIONS.contains(normalized)) throw error("TOOL_APPROVAL_DECISION_INVALID");
        if ("APPROVE_WITH_CHANGES".equals(normalized) && (amendedInput == null || amendedInput.isEmpty())) {
            throw error("TOOL_APPROVAL_AMENDED_INPUT_REQUIRED");
        }
        if (repository.decide(tenantId, userId, approvalId, normalized, safe(comment), amendedInput,
                userId, expectedRevision, LocalDateTime.now()) != 1) throw error("TOOL_APPROVAL_CONFLICT");
    }

    /** 当前工具调用同步等待数据库中的审批决定；不保存 Java continuation。 */
    public ToolApprovalRequestEntity awaitDecision(ToolApprovalRequestEntity request,
                                                    ToolInvokeContextEntity context) {
        requireContext(context);
        if (request == null) throw error("TOOL_APPROVAL_NOT_FOUND");
        while (true) {
            runControlService.requireExecutable(context.getTenantId(), context.getUserId(),
                    context.getRunId(), context.getContextRevision());
            ToolApprovalRequestEntity current = repository.query(context.getTenantId(), context.getUserId(),
                    request.getApprovalId());
            if (current == null) throw error("TOOL_APPROVAL_NOT_FOUND");
            if ("DECIDED".equals(current.getStatus())) {
                runControlService.authorizeToolDispatch(context.getTenantId(), context.getUserId(),
                        context.getRunId(), context.getContextRevision());
                return current;
            }
            if (!"PENDING".equals(current.getStatus())) throw error("TOOL_APPROVAL_STATE_INVALID");
            LocalDateTime now = LocalDateTime.now();
            if (!now.isBefore(current.getExpiresAt())) {
                repository.decideTimeout(current.getTenantId(), current.getApprovalId(), current.getRevision(),
                        current.getTimeoutDecision(), now);
                continue;
            }
            sleep(Math.min(POLL_INTERVAL_MILLIS,
                    Math.max(1L, java.time.Duration.between(now, current.getExpiresAt()).toMillis())));
        }
    }

    private void requireContext(ToolInvokeContextEntity value) {
        if (value == null) throw error("TOOL_APPROVAL_CONTEXT_INVALID");
        requireText(value.getTenantId()); requireText(value.getUserId()); requireText(value.getRunId());
        requireText(value.getSessionId()); requireText(value.getAgentId()); requireText(value.getFunctionCallId());
    }
    private void requireText(String value) { if (!text(value)) throw error("TOOL_APPROVAL_CONTEXT_INVALID"); }
    private boolean text(String value) { return value != null && !value.isBlank(); }
    private String safe(String value) { return value == null ? null : value.substring(0, Math.min(500, value.length())); }
    private void sleep(long millis) {
        try { Thread.sleep(millis); }
        catch (InterruptedException exception) {
            Thread.currentThread().interrupt(); throw error("TOOL_APPROVAL_WAIT_INTERRUPTED");
        }
    }
    private AppException error(String code) { return new AppException(code, code); }
}
