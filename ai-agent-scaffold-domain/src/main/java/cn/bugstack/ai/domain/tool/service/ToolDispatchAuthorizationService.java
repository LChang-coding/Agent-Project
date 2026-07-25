package cn.bugstack.ai.domain.tool.service;

import cn.bugstack.ai.domain.run.service.RunControlService;
import cn.bugstack.ai.domain.tool.adapter.repository.IToolRepository;
import cn.bugstack.ai.domain.tool.model.entity.ToolCallLogEntity;
import cn.bugstack.ai.domain.tool.model.entity.ToolCatalogEntity;
import cn.bugstack.ai.domain.tool.model.entity.ToolDispatchClaimEntity;
import cn.bugstack.ai.domain.tool.model.entity.ToolInvokeContextEntity;
import cn.bugstack.ai.domain.tool.model.valobj.ToolStatus;
import cn.bugstack.ai.types.exception.AppException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

/**
 * 工具外部分发授权服务。
 * <p>在同一短事务中锁定运行并持久化 started 审计，确定取消与副作用开始的顺序。</p>
 */
@Service
public class ToolDispatchAuthorizationService {

    /** 在工具副作用前锁定并校验权威运行。 */
    private final RunControlService runControlService;
    /** 原子领取幂等键并完成调用日志。 */
    private final IToolRepository toolRepository;

    /** 注入运行控制和工具审计仓储。 */
    public ToolDispatchAuthorizationService(RunControlService runControlService, IToolRepository toolRepository) {
        this.runControlService = runControlService;
        this.toolRepository = toolRepository;
    }

    /** 同一短事务内先授权运行，再以幂等键领取唯一外部执行权。 */
    @Transactional(rollbackFor = Exception.class)
    public ToolDispatchClaimEntity claim(ToolCatalogEntity tool, ToolInvokeContextEntity context, String inputJson) {
        if (!blank(context.getRunId())) {
            // 行锁确定取消与工具副作用的先后顺序，并校验上下文 revision。
            runControlService.authorizeToolDispatch(context.getTenantId(), context.getUserId(), context.getRunId(),
                    context.getContextRevision());
        }
        String idempotencyKey = buildIdempotencyKey(tool, context);
        ToolCallLogEntity log = ToolCallLogEntity.builder()
                .tenantId(context.getTenantId()).userId(context.getUserId()).sessionId(context.getSessionId())
                .runId(context.getRunId()).workflowId(context.getWorkflowId()).toolType(tool.getToolType())
                .toolId(tool.getToolId()).toolName(tool.getToolName()).version(tool.getVersion())
                .invocationId(defaultString(context.getInvocationId(), "tool_inv_" + UUID.randomUUID()))
                .functionCallId(context.getFunctionCallId()).idempotencyKey(idempotencyKey)
                .traceId(context.getTraceId()).inputJson(inputJson).status(ToolStatus.STARTED)
                .startedAt(LocalDateTime.now()).build();
        if (toolRepository.claimToolCallLog(log) == 1) {
            // 唯一索引插入成功者才允许继续外部调用。
            return ToolDispatchClaimEntity.builder().claimed(true).callLog(log).build();
        }
        ToolCallLogEntity existing = toolRepository.queryToolCallLogByIdempotencyKey(idempotencyKey);
        if (existing == null) {
            throw new AppException("TOOL_CALL_CLAIM_FAILED", "工具调用权领取失败，请重新推理");
        }
        return ToolDispatchClaimEntity.builder().claimed(false).callLog(existing).build();
    }

    /**
     * 完成工具调用审计；参数是领取记录和结果；无返回值。
     */
    @Transactional(rollbackFor = Exception.class)
    public void finish(ToolCallLogEntity log, String outputJson, String status, String errorType,
                       String errorMessage, Long costMs) {
        if (toolRepository.finishToolCallLog(log.getIdempotencyKey(), outputJson, status,
                errorType, errorMessage, costMs) != 1) {
            throw new AppException("TOOL_CALL_LOG_FINISH_FAILED", "工具调用结果审计更新失败");
        }
    }

    /** 优先使用运行+函数调用生成稳定键；缺少二者的独立调用退化为随机键。 */
    private String buildIdempotencyKey(ToolCatalogEntity tool, ToolInvokeContextEntity context) {
        if (!blank(context.getRunId()) && !blank(context.getFunctionCallId())) {
            String source = context.getTenantId() + ':' + context.getUserId() + ':' + context.getRunId() + ':'
                    + context.getFunctionCallId() + ':' + tool.getToolId();
            return "tool_call_sha256_" + sha256(source);
        }
        return "tool_call_" + UUID.randomUUID();
    }

    private String sha256(String source) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new AppException("TOOL_CALL_KEY_FAILED", "工具调用幂等键生成失败", e);
        }
    }

    private String defaultString(String value, String defaultValue) {
        return blank(value) ? defaultValue : value;
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
