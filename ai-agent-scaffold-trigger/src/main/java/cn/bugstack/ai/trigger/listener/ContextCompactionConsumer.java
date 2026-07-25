package cn.bugstack.ai.trigger.listener;

import cn.bugstack.ai.domain.context.adapter.repository.IContextCompactionTaskRepository;
import cn.bugstack.ai.domain.context.model.ContextCompactionCommand;
import cn.bugstack.ai.domain.context.model.ContextCompactionTaskEntity;
import cn.bugstack.ai.domain.context.model.ContextPolicyProperties;
import cn.bugstack.ai.domain.context.service.ConversationMemoryService;
import cn.bugstack.ai.types.context.TenantContext;
import cn.bugstack.ai.types.context.TenantContextHolder;
import cn.bugstack.ai.types.observability.TraceContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.DltHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.retrytopic.TopicSuffixingStrategy;
import org.springframework.retry.annotation.Backoff;
import org.springframework.stereotype.Component;

/**
 * Kafka 上下文压缩任务消费者。
 * <p>订阅 Kafka 事件后回查 MySQL 任务账本，使用 CAS 领取保证重复投递安全。</p>
 */
@Slf4j
@Component
public class ContextCompactionConsumer {

    private final ObjectMapper objectMapper;
    private final IContextCompactionTaskRepository taskRepository;
    private final ConversationMemoryService conversationMemoryService;
    private final ContextPolicyProperties properties;

    /**
     * 创建 Kafka 消费者；参数是 JSON 工具、任务仓储和压缩服务；返回消费者实例。
     */
    public ContextCompactionConsumer(ObjectMapper objectMapper,
                                     IContextCompactionTaskRepository taskRepository,
                                     ConversationMemoryService conversationMemoryService,
                                     ContextPolicyProperties properties) {
        this.objectMapper = objectMapper;
        this.taskRepository = taskRepository;
        this.conversationMemoryService = conversationMemoryService;
        this.properties = properties;
    }

    /**
     * 消费压缩命令；参数是消息正文；领取失败时安全忽略重复投递。
     */
    @RetryableTopic(
            attempts = "${ai.context.kafka.retry-attempts:3}",
            backoff = @Backoff(delayExpression = "${ai.context.kafka.retry-delay-ms:1000}"),
            retryTopicSuffix = "-retry-1000",
            dltTopicSuffix = "-dlt",
            autoCreateTopics = "false",
            topicSuffixingStrategy = TopicSuffixingStrategy.SUFFIX_WITH_INDEX_VALUE)
    @KafkaListener(
            topics = "${ai.context.kafka.topic:context.compaction.request.v1}",
            groupId = "${ai.context.kafka.group-id:ai-agent-context-compaction}",
            autoStartup = "${ai.context.kafka.enabled:false}")
    public void consume(String payload) throws Exception {
        ContextCompactionCommand command = objectMapper.readValue(payload, ContextCompactionCommand.class);
        bindTenantContext(command);
        try {
            if (!taskRepository.claim(command.taskId())) {
                log.info("上下文压缩任务跳过 taskId:{} sessionId:{} reason:claim_failed", command.taskId(), command.sessionId());
                return;
            }
            conversationMemoryService.compactTask(command.taskId());
        } catch (Exception e) {
            handleFailure(command.taskId(), e);
            throw e;
        } finally {
            clearContext();
        }
    }

    /**
     * 处理死信消息；参数是消息正文；将任务标记为 dead。
     */
    @DltHandler
    public void dlt(String payload) throws Exception {
        ContextCompactionCommand command = objectMapper.readValue(payload, ContextCompactionCommand.class);
        bindTenantContext(command);
        try {
            taskRepository.dead(command.taskId(), "上下文压缩进入 DLT");
            log.warn("上下文压缩任务进入 DLT taskId:{} sessionId:{}", command.taskId(), command.sessionId());
        } finally {
            clearContext();
        }
    }

    private void handleFailure(String taskId, Exception e) {
        ContextCompactionTaskEntity task = taskRepository.queryByTaskId(taskId);
        if (task == null) {
            return;
        }
        int attempts = task.getAttemptCount() == null ? 0 : task.getAttemptCount();
        if (attempts >= properties.getCompactionMaxAttempts()) {
            taskRepository.dead(taskId, e.getMessage());
            log.warn("上下文压缩任务已标记 dead taskId:{} attempts:{}", taskId, attempts, e);
            return;
        }
        taskRepository.retry(taskId, e.getMessage());
        log.warn("上下文压缩任务待重试 taskId:{} attempts:{}", taskId, attempts, e);
    }

    private void bindTenantContext(ContextCompactionCommand command) {
        TraceContext.setTraceId(command.traceId() == null || command.traceId().isBlank()
                ? TraceContext.newTraceId()
                : command.traceId());
        TenantContextHolder.set(TenantContext.builder()
                .tenantId(command.tenantId())
                .userId(command.userId())
                .build());
    }

    private void clearContext() {
        TenantContextHolder.clear();
        TraceContext.clear();
        MDC.clear();
    }
}
