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

    /** 保存最终助手消息并更新通用运行终态。 */
    private final RunControlService runControlService;
    /** 持久化最终回答、完成、失败或取消事件。 */
    private final WorkflowEventStreamService eventStreamService;
    /** 将终态事件载荷编码为 JSON。 */
    private final ObjectMapper objectMapper;

    /**
     * 创建工作流终态处理服务。
     *
     * @param runControlService 保存助手消息并推进运行状态的服务
     * @param eventStreamService 持久化最终回答和终态事件的服务
     * @param objectMapper 编码事件载荷的 JSON 组件
     */
    public WorkflowRunFinalizationService(RunControlService runControlService,
                                          WorkflowEventStreamService eventStreamService,
                                          ObjectMapper objectMapper) {
        this.runControlService = runControlService;
        this.eventStreamService = eventStreamService;
        this.objectMapper = objectMapper;
    }

    /**
     * 在同一事务中保存最终消息、完成运行并追加最终回答及完成事件。
     *
     * @param run 待完成的通用工作流运行
     * @param content 最终助手回答
     * @param metadata 最终消息的服务端元数据
     * @param executedNodes 本次工作流实际完成的节点数量
     * @return 已保存的助手消息；并发取消已经生效时返回空
     */
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

    /**
     * 在同一事务中保存错误消息、更新运行并追加失败或取消终态。
     *
     * @param run 待处理的通用工作流运行
     * @param errorContent 提供给会话用户的安全错误消息
     * @param reason 保存到运行记录的失败原因
     * @param errorCode 提供给事件消费者的稳定错误码
     * @return 已保存的助手错误消息
     */
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

    /**
     * 为已经取消的工作流补充唯一取消事件；取消接口和后台执行均可重复调用。
     *
     * @param run 已读取的通用运行；非工作流或未取消时不执行操作
     */
    @Transactional(rollbackFor = Exception.class)
    public void reconcileCancellation(ChatRunEntity run) {
        if (run == null || !"workflow".equals(run.getSourceType()) || run.getStatus() != RunStatus.CANCELLED) return;
        publish(run, "WORKFLOW_CANCELLED", Map.of("reason", safe(run.getTerminalReason())));
    }

    /** 发布运行终态事件，并沿用 Chat Run 的可信身份和 traceId。 */
    private void publish(ChatRunEntity run, String eventType, Map<String, ?> payload) {
        eventStreamService.publish(run.getTenantId(), run.getUserId(), run.getRunId(), run.getTraceId(),
                eventType, null, null, json(payload));
    }

    /** 将终态事件负载编码为 JSON，失败时显式终止收口事务。 */
    private String json(Map<String, ?> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("工作流终态事件编码失败", exception);
        }
    }

    /** 将可选终态说明归一为空串，避免事件编码出现 null。 */
    private String safe(String value) {
        return value == null ? "" : value;
    }
}
