package cn.bugstack.ai.trigger.listener;

import cn.bugstack.ai.domain.agent.adapter.repository.ISubagentCoordinationCache;
import cn.bugstack.ai.domain.agent.adapter.repository.ISubagentTaskRepository;
import cn.bugstack.ai.domain.agent.model.entity.SubagentTaskEntity;
import cn.bugstack.ai.domain.agent.model.valobj.SubagentTaskStatus;
import cn.bugstack.ai.domain.agent.service.IChatService;
import cn.bugstack.ai.types.context.TenantContext;
import cn.bugstack.ai.types.context.TenantContextHolder;
import cn.bugstack.ai.types.observability.TraceContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** 回查 MySQL、持有 Lease 并执行临时子 Agent 的 Kafka Worker。 */
@Slf4j
@Component
public class SubagentTaskConsumer {
    private static final int SUMMARY_LIMIT = 1000;
    private static final Duration LEASE = Duration.ofSeconds(60);
    private static final Duration CACHE_TTL = Duration.ofHours(2);
    private final ObjectMapper objectMapper;
    private final ISubagentTaskRepository repository;
    private final ISubagentCoordinationCache cache;
    private final IChatService chatService;
    private final String workerId = "subagent-worker-" + UUID.randomUUID();
    private final ScheduledExecutorService heartbeat = Executors.newScheduledThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "subagent-lease-heartbeat"); thread.setDaemon(true); return thread;
    });

    public SubagentTaskConsumer(ObjectMapper objectMapper, ISubagentTaskRepository repository,
                                ISubagentCoordinationCache cache, IChatService chatService) {
        this.objectMapper = objectMapper; this.repository = repository; this.cache = cache; this.chatService = chatService;
    }

    @KafkaListener(topics = "${ai.agent.orchestration.task-topic:agent.subagent.task.v1}",
            groupId = "${ai.agent.orchestration.task-group:ai-agent-subagent-worker}",
            concurrency = "${ai.agent.orchestration.task-concurrency:4}",
            autoStartup = "${ai.agent.orchestration.enabled:false}")
    public void consume(String payload) throws Exception {
        JsonNode event = event(payload);
        String tenantId = required(event, "tenantId"); String taskId = required(event, "taskId");
        SubagentTaskEntity task = repository.claim(tenantId, taskId, workerId, LocalDateTime.now(), LEASE);
        if (task == null) return;
        bind(task);
        ScheduledFuture<?> leaseHeartbeat = heartbeat.scheduleAtFixedRate(
                () -> renew(task), 20, 20, TimeUnit.SECONDS);
        try {
            try {
                String childSessionId = task.getChildSessionId();
                if (childSessionId == null || childSessionId.isBlank()) {
                    childSessionId = chatService.createSubagentSession(task.getChildAgentId(), task.getUserId());
                    if (repository.bindChildSession(task.getTenantId(), task.getTaskId(), workerId,
                            task.getFencingToken(), childSessionId) != 1) {
                        throw new IllegalStateException("SUBAGENT_LEASE_LOST");
                    }
                    task.setChildSessionId(childSessionId);
                }
                cache(() -> cache.putInstance(task, CACHE_TTL), "put-instance", task);
                // Kafka worker has no browser downstream. Use the internal entry so ADK does not
                // retain an unconsumed token stream while several child Agents run concurrently.
                List<String> output = chatService.handleInternalMessage(task.getChildAgentId(), task.getUserId(),
                        childSessionId, task.getInstruction());
                String result = finalOutput(output);
                String fullContext = fullContext(output);
                task.setStatus(SubagentTaskStatus.SUCCEEDED); task.setResultText(result);
                task.setResultSummary(summary(result)); task.setFullContext(fullContext);
                task.setSummaryTruncated(result.length() > SUMMARY_LIMIT);
                task.setCompletedAt(LocalDateTime.now());
            } catch (RuntimeException exception) {
                task.setStatus(SubagentTaskStatus.FAILED); task.setErrorCode(exception.getClass().getSimpleName());
                task.setResultText(null); task.setResultSummary(null); task.setFullContext(null);
                task.setSummaryTruncated(false); task.setCompletedAt(LocalDateTime.now());
                log.warn("子 Agent 任务执行失败 tenantId:{} taskId:{} childAgentId:{} errorType:{}",
                        task.getTenantId(), task.getTaskId(), task.getChildAgentId(),
                        exception.getClass().getSimpleName());
            }
            if (repository.complete(task, workerId, task.getFencingToken()) != 1) {
                throw new IllegalStateException("SUBAGENT_LEASE_LOST");
            }
        } finally {
            leaseHeartbeat.cancel(false); clear();
        }
    }

    private void renew(SubagentTaskEntity task) {
        try {
            int changed = repository.renewLease(task.getTenantId(), task.getTaskId(), workerId,
                    task.getFencingToken(), LocalDateTime.now(), LEASE);
            if (changed == 1) cache(() -> cache.heartbeat(task.getTenantId(), task.getTaskId(), CACHE_TTL),
                    "heartbeat", task);
        } catch (RuntimeException exception) {
            log.warn("子 Agent Lease 心跳失败 tenantId:{} taskId:{}", task.getTenantId(), task.getTaskId(), exception);
        }
    }

    private void cache(Runnable action, String operation, SubagentTaskEntity task) {
        try { action.run(); }
        catch (RuntimeException exception) {
            log.warn("Redis 缓存降级 operation:{} tenantId:{} taskId:{}", operation,
                    task.getTenantId(), task.getTaskId(), exception);
        }
    }

    private JsonNode event(String payload) throws Exception {
        JsonNode value = objectMapper.readTree(payload);
        if (value.path("schemaVersion").asInt(-1) != 1) throw new IllegalArgumentException("SUBAGENT_EVENT_VERSION_UNSUPPORTED");
        return value;
    }

    private String finalOutput(List<String> outputs) {
        if (outputs == null) return "";
        for (int index = outputs.size() - 1; index >= 0; index--) {
            String output = outputs.get(index); if (output != null && !output.isBlank()) return output;
        }
        return "";
    }

    private String fullContext(List<String> outputs) {
        // IChatService 可能返回累计快照，最后一个非空值已是完整回答；拼接会重复放大正文。
        return finalOutput(outputs);
    }

    private String summary(String value) {
        if (value == null || value.length() <= SUMMARY_LIMIT) return value == null ? "" : value;
        return value.substring(0, SUMMARY_LIMIT);
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
    }

    private void clear() { TenantContextHolder.clear(); TraceContext.clear(); MDC.clear(); }
}
