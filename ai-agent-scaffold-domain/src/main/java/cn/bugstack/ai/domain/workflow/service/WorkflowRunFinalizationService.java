package cn.bugstack.ai.domain.workflow.service;

import cn.bugstack.ai.domain.run.model.ChatRunEntity;
import cn.bugstack.ai.domain.run.model.RunStatus;
import cn.bugstack.ai.domain.run.service.RunControlService;
import cn.bugstack.ai.domain.session.model.entity.ChatMessageEntity;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/** 原子收口工作流 Run、最终助手消息和唯一终态事件。 */
@Service
public class WorkflowRunFinalizationService {

    private final RunControlService runControlService;
    private final WorkflowEventStreamService eventStreamService;
    private final ObjectMapper objectMapper;

    public WorkflowRunFinalizationService(RunControlService runControlService,
                                          WorkflowEventStreamService eventStreamService,
                                          ObjectMapper objectMapper) {
        this.runControlService = runControlService;
        this.eventStreamService = eventStreamService;
        this.objectMapper = objectMapper;
    }

    /** 在同一事务中保存最终消息、完成 Run 并追加最终回答及完成事件。 */
    @Transactional(rollbackFor = Exception.class)
    public ChatMessageEntity complete(ChatRunEntity run, String content, String metadata, int executedNodes) {
        ChatMessageEntity message = runControlService.completeWithAssistantMessage(run.getTenantId(), run.getUserId(),
                run.getRunId(), content, run.getTraceId(), metadata);
        ChatRunEntity settled = runControlService.require(run.getTenantId(), run.getUserId(), run.getRunId());
        if (settled.getStatus() == RunStatus.CANCELLED) {
            publish(settled, "WORKFLOW_CANCELLED", Map.of("reason", safe(settled.getTerminalReason())));
            return null;
        }
        if (settled.getStatus() != RunStatus.COMPLETED) return message;
        if (!safe(content).isEmpty()) publish(settled, "FINAL_ANSWER_DELTA", Map.of("delta", content));
        publish(settled, "FINAL_ANSWER_COMPLETED", Map.of("content", safe(content)));
        publish(settled, "WORKFLOW_COMPLETED", Map.of("executedNodes", executedNodes));
        return message;
    }

    /** 在同一事务中保存安全错误消息、失败 Run 并追加失败终态。 */
    @Transactional(rollbackFor = Exception.class)
    public ChatMessageEntity fail(ChatRunEntity run, String errorContent, String reason, String errorCode) {
        ChatMessageEntity message = runControlService.failWithAssistantMessage(run.getTenantId(), run.getUserId(),
                run.getRunId(), errorContent, run.getTraceId(), reason);
        ChatRunEntity settled = runControlService.require(run.getTenantId(), run.getUserId(), run.getRunId());
        if (settled.getStatus() == RunStatus.CANCELLED) {
            publish(settled, "WORKFLOW_CANCELLED", Map.of("reason", safe(settled.getTerminalReason())));
        } else if (settled.getStatus() == RunStatus.FAILED) {
            publish(settled, "WORKFLOW_FAILED", Map.of("errorCode", safe(errorCode), "message", safe(reason)));
        }
        return message;
    }

    /** 取消接口和后台执行均可重复调用；数据库唯一终态槽位只保留第一次结果。 */
    @Transactional(rollbackFor = Exception.class)
    public void reconcileCancellation(ChatRunEntity run) {
        if (run == null || !"workflow".equals(run.getSourceType()) || run.getStatus() != RunStatus.CANCELLED) return;
        publish(run, "WORKFLOW_CANCELLED", Map.of("reason", safe(run.getTerminalReason())));
    }

    private void publish(ChatRunEntity run, String eventType, Map<String, ?> payload) {
        eventStreamService.publish(run.getTenantId(), run.getUserId(), run.getRunId(), run.getTraceId(),
                eventType, null, null, json(payload));
    }

    private String json(Map<String, ?> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("工作流终态事件编码失败", exception);
        }
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
