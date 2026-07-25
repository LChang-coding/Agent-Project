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
     * 创建上下文压缩消费者。
     *
     * @param objectMapper Kafka JSON 反序列化器
     * @param taskRepository 压缩任务账本；负责认领和状态推进
     * @param conversationMemoryService 实际执行压缩的领域服务
     * @param properties 压缩重试和批处理策略
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
     * 消费一条压缩命令。
     * <p>先回查数据库并通过 CAS 认领任务；重复消息或已被其他实例认领的任务直接跳过。</p>
     *
     * @param payload 序列化后的压缩命令
     * @throws Exception 处理失败时抛出，由 RetryableTopic 转入重试主题或死信主题
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
        // Kafka 消息只携带任务身份；真实状态仍以 MySQL 任务账本为准。
        ContextCompactionCommand command = objectMapper.readValue(payload, ContextCompactionCommand.class);
        // 消费线程没有 HTTP 过滤器，必须显式恢复租户与 Trace 上下文。
        bindTenantContext(command);
        try {
            // claim 失败表示任务已被处理、取消或被另一消费者持有，本次投递不得重复压缩。
            if (!taskRepository.claim(command.taskId())) {
                log.info("上下文压缩任务跳过 taskId:{} sessionId:{} reason:claim_failed", command.taskId(), command.sessionId());
                return;
            }
            // 领域服务会再次校验消息有效性和压缩范围，消费者不复制这些业务规则。
            conversationMemoryService.compactTask(command.taskId());
        } catch (Exception e) {
            // 先把失败原因写回任务账本，再抛出触发 Kafka 重试链。
            handleFailure(command.taskId(), e);
            throw e;
        } finally {
            // Kafka 线程会被复用，必须清理 ThreadLocal 和 MDC，防止跨租户污染。
            clearContext();
        }
    }

    /**
     * 处理超过 Kafka 重试次数的死信消息。
     *
     * @param payload 原始压缩命令
     * @throws Exception 消息无法解析或账本更新失败时抛出，让容器记录消费异常
     */
    @DltHandler
    public void dlt(String payload) throws Exception {
        ContextCompactionCommand command = objectMapper.readValue(payload, ContextCompactionCommand.class);
        bindTenantContext(command);
        try {
            // 死信意味着自动重试链已经耗尽，任务进入人工可见的不可执行终态。
            taskRepository.dead(command.taskId(), "上下文压缩进入 DLT");
            log.warn("上下文压缩任务进入 DLT taskId:{} sessionId:{}", command.taskId(), command.sessionId());
        } finally {
            clearContext();
        }
    }

    /**
     * 按数据库累计尝试次数决定继续重试还是终止任务。
     *
     * @param taskId 压缩任务ID
     * @param e 本轮失败原因
     */
    private void handleFailure(String taskId, Exception e) {
        // 消息可能对应已被取消或清理的任务，此时不再凭消息重建状态。
        ContextCompactionTaskEntity task = taskRepository.queryByTaskId(taskId);
        if (task == null) {
            return;
        }
        int attempts = task.getAttemptCount() == null ? 0 : task.getAttemptCount();
        // 领域配置是最终重试上限，防止 Kafka 重试策略和任务账本产生无限循环。
        if (attempts >= properties.getCompactionMaxAttempts()) {
            taskRepository.dead(taskId, e.getMessage());
            log.warn("上下文压缩任务已标记 dead taskId:{} attempts:{}", taskId, attempts, e);
            return;
        }
        taskRepository.retry(taskId, e.getMessage());
        log.warn("上下文压缩任务待重试 taskId:{} attempts:{}", taskId, attempts, e);
    }

    /**
     * 将消息中的可信任务身份绑定到当前消费线程。
     *
     * @param command 已完成 JSON 校验的压缩命令
     */
    private void bindTenantContext(ContextCompactionCommand command) {
        // 沿用生产者 Trace；旧消息缺少 Trace 时生成新值，保证日志仍可关联。
        TraceContext.setTraceId(command.traceId() == null || command.traceId().isBlank()
                ? TraceContext.newTraceId()
                : command.traceId());
        TenantContextHolder.set(TenantContext.builder()
                .tenantId(command.tenantId())
                .userId(command.userId())
                .build());
    }

    /**
     * 清理消费线程上的全部请求级上下文。
     */
    private void clearContext() {
        TenantContextHolder.clear();
        TraceContext.clear();
        MDC.clear();
    }
}
