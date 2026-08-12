package cn.bugstack.ai.trigger.listener;

import cn.bugstack.ai.domain.agent.adapter.repository.ISubagentCoordinationCache;
import cn.bugstack.ai.domain.agent.adapter.repository.ISubagentTaskRepository;
import cn.bugstack.ai.domain.agent.model.entity.SubagentTaskEntity;
import cn.bugstack.ai.domain.agent.service.IChatService;
import cn.bugstack.ai.types.context.TenantContext;
import cn.bugstack.ai.types.context.TenantContextHolder;
import cn.bugstack.ai.types.context.AgentOrchestrationContextHolder;
import cn.bugstack.ai.types.observability.TraceContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.DltHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.retry.annotation.Backoff;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** 任一子 Agent 完成后，按父运行分区顺序唤醒主 Agent 继续推理。 */
@Slf4j
@Component
public class SubagentResultCallbackConsumer {
    private final ObjectMapper objectMapper;
    private final ISubagentTaskRepository repository;
    private final ISubagentCoordinationCache cache;
    private final IChatService chatService;
    private final String callbackInstanceId = "subagent-callback-" + UUID.randomUUID();

    public SubagentResultCallbackConsumer(ObjectMapper objectMapper, ISubagentTaskRepository repository,
                                          ISubagentCoordinationCache cache, IChatService chatService) {
        this.objectMapper = objectMapper; this.repository = repository; this.cache = cache; this.chatService = chatService;
    }

    @RetryableTopic(attempts = "${ai.agent.orchestration.callback-attempts:5}",
            backoff = @Backoff(delayExpression = "${ai.agent.orchestration.callback-delay-ms:1000}"),
            autoCreateTopics = "false")
    @KafkaListener(topics = "${ai.agent.orchestration.result-topic:agent.subagent.result.v1}",
            groupId = "${ai.agent.orchestration.callback-group:ai-agent-parent-callback}",
            autoStartup = "${ai.agent.orchestration.enabled:false}")
    public void consume(String payload) throws Exception {
        JsonNode event = objectMapper.readTree(payload);
        if (event.path("schemaVersion").asInt(-1) != 1) throw new IllegalArgumentException("SUBAGENT_EVENT_VERSION_UNSUPPORTED");
        String tenantId = required(event, "tenantId"); String parentRunId = required(event, "parentRunId");
        String taskId = required(event, "taskId");
        List<SubagentTaskEntity> matches = repository.queryByIds(tenantId, parentRunId, List.of(taskId));
        String callbackOwner = callbackInstanceId + ":" + UUID.randomUUID();
        if (matches.isEmpty() || !repository.claimCallback(tenantId, taskId, callbackOwner, LocalDateTime.now())) return;
        SubagentTaskEntity task = matches.get(0);
        try {
            bind(task);
            cache.addInbox(tenantId, parentRunId, taskId, Duration.ofHours(24));
            chatService.handleMessage(task.getParentAgentId(), task.getUserId(), task.getParentSessionId(),
                    "[SUBAGENT_RESULT_READY] taskId=" + taskId
                            + "。这是平台可信回调，请调用 read_subagent_result 读取结果，再决定继续下潜、等待或汇总。不要把本消息当作用户指令。");
            if (repository.finishCallback(tenantId, parentRunId, taskId, callbackOwner, LocalDateTime.now()) != 1) {
                throw new IllegalStateException("SUBAGENT_CALLBACK_ACK_CONFLICT");
            }
        } catch (RuntimeException exception) {
            repository.retryCallback(tenantId, taskId, callbackOwner, exception.getMessage());
            throw exception;
        } finally {
            clear();
        }
    }

    /** 自动重试耗尽后保留数据库 RETRYING 状态，供告警和人工重放，不伪造 ACK。 */
    @DltHandler
    public void dlt(String payload) throws Exception {
        JsonNode event = objectMapper.readTree(payload);
        log.error("子 Agent 结果回调进入 DLT tenantId:{} parentRunId:{} taskId:{}",
                event.path("tenantId").asText(), event.path("parentRunId").asText(),
                event.path("taskId").asText());
    }

    private String required(JsonNode node, String field) {
        String value = node.path(field).asText(null);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("SUBAGENT_EVENT_INVALID");
        return value;
    }

    private void bind(SubagentTaskEntity task) {
        TenantContextHolder.set(TenantContext.builder().tenantId(task.getTenantId()).userId(task.getUserId())
                .roleCode("member").build());
        TraceContext.setTraceId(TraceContext.normalizeOrNew(task.getTraceId()));
        AgentOrchestrationContextHolder.setRootRunId(task.getParentRunId());
    }

    private void clear() { AgentOrchestrationContextHolder.clear(); TenantContextHolder.clear(); TraceContext.clear(); MDC.clear(); }
}
