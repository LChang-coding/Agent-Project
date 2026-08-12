package cn.bugstack.ai.domain.tool.service;

import cn.bugstack.ai.domain.run.service.RunControlService;
import cn.bugstack.ai.domain.run.model.ChatRunEntity;
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
import java.util.List;
import java.util.UUID;

/**
 * 工具真正执行前的统一检查服务。
 *
 * <p>{@link ToolGateway} 先在这里锁住数据库运行记录，确认运行没有取消、上下文和 Trace ID
 * 仍然一致，再用唯一调用标识创建一条“已开始”记录。只有成功创建记录的请求才能继续执行工具，
 * 从而避免取消后仍产生外部操作，也避免同一次模型函数调用被执行两遍。</p>
 */
@Service
public class ToolDispatchAuthorizationService {

    private static final int ERROR_MESSAGE_MAX_LENGTH = 1024;

    /** 锁住数据库运行记录并检查取消、上下文版本和 Trace ID。 */
    private final RunControlService runControlService;
    /** 创建和更新数据库工具调用记录，并用唯一索引识别重复调用。 */
    private final IToolRepository toolRepository;

    /** 创建无状态的工具执行检查服务；并发控制由数据库行锁和唯一索引完成。 */
    public ToolDispatchAuthorizationService(RunControlService runControlService, IToolRepository toolRepository) {
        this.runControlService = runControlService;
        this.toolRepository = toolRepository;
    }

    /**
     * 尝试取得一次工具调用的执行权。
     *
     * <p>方法先检查节点工具范围，再锁住运行记录；随后根据运行编号和模型函数调用编号生成稳定的
     * 唯一标识，并创建“已开始”记录。创建成功表示本请求可以执行；唯一标识冲突则返回原记录，
     * 由网关复用已有结果或报告“结果仍未知”。</p>
     *
     * @param tool 本轮工具清单中的工具
     * @param context 服务端可信运行上下文
     * @param inputJson 已做大小限制的工具输入
     * @return 是否取得执行权以及对应的数据库调用记录
     */
    @Transactional(rollbackFor = Exception.class)
    public ToolDispatchClaimEntity claim(ToolCatalogEntity tool, ToolInvokeContextEntity context, String inputJson) {
        validateWorkflowToolScope(tool, context);
        // 绑定运行的工具必须直接锁数据库，缓存中的运行状态可能晚于用户的取消操作。
        if (!blank(context.getRunId())) {
            ChatRunEntity lockedRun = runControlService.authorizeToolDispatch(context.getTenantId(),
                    context.getUserId(), context.getRunId(), context.getContextRevision());
            // Trace ID 必须来自这条数据库运行记录；运行时内存字段不一致时禁止调用任何工具。
            if (lockedRun == null || blank(lockedRun.getTraceId())
                    || !lockedRun.getTraceId().equals(context.getTraceId())) {
                throw new AppException("TOOL_TRACE_SCOPE_MISMATCH", "工具调用与当前运行的 Trace ID 不一致");
            }
        }
        // 稳定标识决定模型再次发送同一函数调用时，是否能找到第一次的数据库记录。
        String idempotencyKey = buildIdempotencyKey(tool, context);
        // 先准备“已开始”记录；数据库唯一索引会保证同一调用只有一个请求能成功插入。
        ToolCallLogEntity log = ToolCallLogEntity.builder()
                .tenantId(context.getTenantId()).userId(context.getUserId()).sessionId(context.getSessionId())
                .runId(context.getRunId()).workflowId(context.getWorkflowId()).toolType(tool.getToolType())
                .toolId(tool.getToolId()).toolName(tool.getToolName()).version(tool.getVersion())
                .invocationId(defaultString(context.getInvocationId(), "tool_inv_" + UUID.randomUUID()))
                .functionCallId(context.getFunctionCallId()).idempotencyKey(idempotencyKey)
                .traceId(context.getTraceId()).inputJson(inputJson).status(ToolStatus.STARTED)
                .startedAt(LocalDateTime.now()).build();
        // 影响行数为 1 表示这是该唯一调用标识的第一次执行。
        if (toolRepository.claimToolCallLog(log) == 1) {
            return ToolDispatchClaimEntity.builder().claimed(true).callLog(log).build();
        }
        // 插入冲突表示这次调用此前已经开始，从数据库读取原记录供网关复用结果。
        ToolCallLogEntity existing = toolRepository.queryToolCallLogByIdempotencyKey(idempotencyKey);
        // 冲突后却找不到原记录时不能再次执行，否则可能重复产生外部操作。
        if (existing == null) {
            throw new AppException("TOOL_CALL_CLAIM_FAILED", "工具调用权领取失败，请重新推理");
        }
        // 返回原记录，由网关根据其成功、失败或仍在执行的状态决定怎样响应。
        return ToolDispatchClaimEntity.builder().claimed(false).callLog(existing).build();
    }

    /**
     * 工作流工具执行前再次核对节点白名单。工具清单过滤负责“不让模型看到”，这里负责
     * “即使旧包装器或异常上下文发起调用也不能执行”，两层使用同一份服务端运行状态。
     */
    private void validateWorkflowToolScope(ToolCatalogEntity tool, ToolInvokeContextEntity context) {
        if (tool == null || context == null || blank(context.getWorkflowKind())) return;
        List<String> allowed;
        if (cn.bugstack.ai.domain.tool.model.valobj.ToolType.MCP.equals(tool.getToolType())) {
            allowed = context.getWorkflowMcpIds();
        } else if (cn.bugstack.ai.domain.tool.model.valobj.ToolType.SKILL.equals(tool.getToolType())) {
            allowed = context.getWorkflowSkillIds();
        } else {
            return;
        }
        if (allowed == null || allowed.stream().noneMatch(tool.getToolId()::equals)) {
            throw new AppException("WORKFLOW_TOOL_SCOPE_DENIED", "当前工作流节点未配置此工具");
        }
    }

    /**
     * 把“已开始”的调用记录更新为最终结果。
     *
     * <p>影响行数不是 1，说明原记录不存在或已被并发修改。此时必须报错，否则记录会一直停在
     * “已开始”，后续相同调用只能得到“结果未知”。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public void finish(ToolCallLogEntity log, String outputJson, String status, String errorType,
                       String errorMessage, Long costMs) {
        if (toolRepository.finishToolCallLog(log.getIdempotencyKey(), outputJson, status,
                errorType, truncate(errorMessage, ERROR_MESSAGE_MAX_LENGTH), costMs) != 1) {
            throw new AppException("TOOL_CALL_LOG_FINISH_FAILED", "工具调用结果审计更新失败");
        }
    }

    private String truncate(String value, int maxLength) {
        return value == null || value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    /**
     * 生成识别重复工具调用的稳定键。
     *
     * <p>运行编号和模型函数调用编号都存在时，相同调用会得到相同哈希值，并由数据库唯一索引拦住
     * 第二次执行。缺少任一编号时无法可靠判断是否重复，只能生成随机键并保留调用记录。</p>
     */
    private String buildIdempotencyKey(ToolCatalogEntity tool, ToolInvokeContextEntity context) {
        if (!blank(context.getRunId()) && !blank(context.getFunctionCallId())) {
            String source = context.getTenantId() + ':' + context.getUserId() + ':' + context.getRunId() + ':'
                    + context.getFunctionCallId() + ':' + tool.getToolId();
            return "tool_call_sha256_" + sha256(source);
        }
        return "tool_call_" + UUID.randomUUID();
    }

    /** 使用固定编码生成定长键值，不在数据库中暴露原始身份字段。 */
    private String sha256(String source) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new AppException("TOOL_CALL_KEY_FAILED", "工具调用幂等键生成失败", e);
        }
    }

    /** 字段为空时使用默认值。 */
    private String defaultString(String value, String defaultValue) {
        return blank(value) ? defaultValue : value;
    }

    /** 空引用和只包含空白的字符串都按“未提供”处理。 */
    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
