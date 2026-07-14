package cn.bugstack.ai.domain.run.service;

import cn.bugstack.ai.domain.context.service.ConversationMemoryService;
import cn.bugstack.ai.domain.run.model.ChatRunEntity;
import cn.bugstack.ai.domain.run.model.ToolGateDecision;
import cn.bugstack.ai.domain.tool.model.entity.ToolInvokeContextEntity;
import org.springframework.stereotype.Service;

/**
 * 运行执行闸门。
 * <p>统一承担工具调用前压缩、取消和上下文版本检查。</p>
 */
@Service
public class RunExecutionGate {

    private final RunControlService runControlService;
    private final ConversationMemoryService conversationMemoryService;

    /**
     * 创建执行闸门；参数是运行与上下文服务；返回闸门实例。
     */
    public RunExecutionGate(RunControlService runControlService, ConversationMemoryService conversationMemoryService) {
        this.runControlService = runControlService;
        this.conversationMemoryService = conversationMemoryService;
    }

    /**
     * 模型工具回调前检查；参数是运行上下文和历史切面；返回是否需要重新推理。
     */
    public ToolGateDecision beforeTool(ToolInvokeContextEntity context, Integer visibleThroughSequence) {
        if (context == null || blank(context.getRunId())) {
            return ToolGateDecision.ALLOW;
        }
        runControlService.requireExecutable(context.getTenantId(), context.getUserId(), context.getRunId(),
                context.getContextRevision());
        boolean compacted = conversationMemoryService.compactBeforeTool(context.getTenantId(), context.getUserId(),
                context.getSessionId(), context.getRunId(), visibleThroughSequence, context.getTraceId());
        if (compacted) {
            runControlService.refreshContextRevision(context.getTenantId(), context.getUserId(), context.getRunId());
            return ToolGateDecision.RETRY_MODEL;
        }
        runControlService.requireExecutable(context.getTenantId(), context.getUserId(), context.getRunId(),
                context.getContextRevision());
        return ToolGateDecision.ALLOW;
    }

    /**
     * 外部调用提交前最终检查；参数是工具调用上下文；无返回值。
     */
    public void beforeDispatch(ToolInvokeContextEntity context) {
        if (context != null && !blank(context.getRunId())) {
            runControlService.authorizeToolDispatch(context.getTenantId(), context.getUserId(), context.getRunId(),
                    context.getContextRevision());
        }
    }

    /**
     * 查询最新运行；参数是工具上下文；返回运行实体。
     */
    public ChatRunEntity currentRun(ToolInvokeContextEntity context) {
        return runControlService.require(context.getTenantId(), context.getUserId(), context.getRunId());
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
