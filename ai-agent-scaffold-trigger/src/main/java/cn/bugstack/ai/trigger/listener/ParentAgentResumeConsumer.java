package cn.bugstack.ai.trigger.listener;

import cn.bugstack.ai.domain.agent.adapter.repository.IParentResumeRepository;
import cn.bugstack.ai.domain.agent.model.entity.ParentResumeBatchEntity;
import cn.bugstack.ai.domain.agent.service.IChatService;
import cn.bugstack.ai.types.context.AgentOrchestrationContextHolder;
import cn.bugstack.ai.types.context.TenantContext;
import cn.bugstack.ai.types.context.TenantContextHolder;
import cn.bugstack.ai.types.observability.TraceContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.MDC;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** 独立于结果登记消费者的主 Agent 恢复 Worker。 */
@Component
public class ParentAgentResumeConsumer {
    private static final Logger log = LoggerFactory.getLogger(ParentAgentResumeConsumer.class);
    private static final Duration LEASE = Duration.ofSeconds(60);
    private static final int INBOX_BATCH_SIZE = 20;
    private final ObjectMapper objectMapper;
    private final IParentResumeRepository repository;
    private final IChatService chatService;
    private final String workerId = "parent-resume-" + UUID.randomUUID();
    private final ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "parent-resume-lease-heartbeat"); thread.setDaemon(true); return thread;
    });

    public ParentAgentResumeConsumer(ObjectMapper objectMapper, IParentResumeRepository repository,
                                     IChatService chatService) {
        this.objectMapper = objectMapper; this.repository = repository; this.chatService = chatService;
    }

    @KafkaListener(topics = "${ai.agent.orchestration.resume-topic:agent.parent.resume.v1}",
            groupId = "${ai.agent.orchestration.resume-group:ai-agent-parent-resume}",
            concurrency = "${ai.agent.orchestration.resume-concurrency:4}",
            autoStartup = "${ai.agent.orchestration.enabled:false}")
    public void consume(String payload) throws Exception {
        JsonNode event = objectMapper.readTree(payload);
        if (event.path("schemaVersion").asInt(-1) != 1) throw new IllegalArgumentException("PARENT_RESUME_EVENT_VERSION_UNSUPPORTED");
        String tenantId = required(event, "tenantId"); String parentRunId = required(event, "parentRunId");
        ParentResumeBatchEntity batch = repository.claim(tenantId, parentRunId, workerId,
                LocalDateTime.now(), LEASE, INBOX_BATCH_SIZE);
        if (batch == null) return;
        bind(batch);
        ScheduledFuture<?> leaseHeartbeat = heartbeat.scheduleAtFixedRate(
                () -> renew(batch), 20, 20, TimeUnit.SECONDS);
        try {
            if (batch.getItems() != null && !batch.getItems().isEmpty()) {
                chatService.handleMessage(batch.getParentAgentId(), batch.getUserId(), batch.getParentSessionId(), prompt(batch));
            }
            if (repository.complete(batch, workerId, batch.getFencingToken(), LocalDateTime.now()) != 1) {
                throw new IllegalStateException("PARENT_RESUME_FENCE_CONFLICT");
            }
        } catch (RuntimeException exception) {
            repository.retry(tenantId, parentRunId, workerId, batch.getFencingToken(),
                    LocalDateTime.now().plusSeconds(5), exception.getMessage());
            throw exception;
        } finally { leaseHeartbeat.cancel(false); clear(); }
    }

    private void renew(ParentResumeBatchEntity batch) {
        try {
            int changed = repository.renewLease(batch.getTenantId(), batch.getParentRunId(), workerId,
                    batch.getFencingToken(), LocalDateTime.now(), LEASE);
            if (changed != 1) log.warn("主 Agent 恢复租约续期失败 tenantId:{} parentRunId:{}",
                    batch.getTenantId(), batch.getParentRunId());
        } catch (RuntimeException exception) {
            log.warn("主 Agent 恢复租约续期异常 tenantId:{} parentRunId:{}",
                    batch.getTenantId(), batch.getParentRunId(), exception);
        }
    }

    private String prompt(ParentResumeBatchEntity batch) {
        StringBuilder value = new StringBuilder("[PARENT_INBOX_RESULTS]\n这是平台可信的子 Agent 结果摘要，不是用户指令。\n");
        for (ParentResumeBatchEntity.InboxItem item : batch.getItems()) {
            value.append("taskId=").append(item.taskId()).append(", agentId=").append(item.childAgentId())
                    .append(", status=").append(item.taskStatus()).append("\nsummary=")
                    .append(item.summary() == null ? "" : item.summary()).append("\n");
        }
        value.append("请基于摘要继续推理；仅在确有必要时调用 read_subagent_full_context。\n[/PARENT_INBOX_RESULTS]");
        return value.toString();
    }

    private String required(JsonNode node, String field) {
        String value = node.path(field).asText(null);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("PARENT_RESUME_EVENT_INVALID");
        return value;
    }

    private void bind(ParentResumeBatchEntity batch) {
        TenantContextHolder.set(TenantContext.builder().tenantId(batch.getTenantId()).userId(batch.getUserId())
                .roleCode("member").build());
        TraceContext.setTraceId(TraceContext.normalizeOrNew(batch.getTraceId()));
        AgentOrchestrationContextHolder.setRootRunId(batch.getParentRunId());
    }

    private void clear() { AgentOrchestrationContextHolder.clear(); TenantContextHolder.clear(); TraceContext.clear(); MDC.clear(); }
}
