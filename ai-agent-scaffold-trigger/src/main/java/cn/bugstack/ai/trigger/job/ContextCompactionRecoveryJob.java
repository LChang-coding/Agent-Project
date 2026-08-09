package cn.bugstack.ai.trigger.job;

import cn.bugstack.ai.domain.context.adapter.port.ContextCompactionPublisher;
import cn.bugstack.ai.domain.context.adapter.repository.IContextCompactionTaskRepository;
import cn.bugstack.ai.domain.context.model.ContextCompactionTaskEntity;
import cn.bugstack.ai.domain.context.model.ContextPolicyProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 从 MySQL 重新通知尚未完成的上下文整理任务。
 *
 * <p>Kafka 用于尽快唤醒消费者，任务的最终状态仍保存在 MySQL。通知发送失败、应用重启或消息丢失时，
 * 这个扫描会再次找到 pending、retrying 和过期 processing 任务；任务领取仍由消费者和数据库
 * 的条件更新保证同一任务只会被一个实例真正处理。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "ai.context.kafka.enabled", havingValue = "true")
public class ContextCompactionRecoveryJob {

    private final IContextCompactionTaskRepository taskRepository;
    private final ContextCompactionPublisher publisher;
    private final ContextPolicyProperties properties;
    private final int batchSize;

    public ContextCompactionRecoveryJob(IContextCompactionTaskRepository taskRepository,
                                        ContextCompactionPublisher publisher,
                                        ContextPolicyProperties properties,
                                        @Value("${ai.context.kafka.recovery-batch-size:50}") int batchSize) {
        this.taskRepository = taskRepository;
        this.publisher = publisher;
        this.properties = properties;
        // 限制单次扫描量，避免积压任务一次性占满 Kafka 发送线程。
        this.batchSize = Math.max(1, Math.min(batchSize, 200));
    }

    /** 定期从数据库找回没有收到 Kafka 通知的任务。 */
    @Scheduled(fixedDelayString = "${ai.context.kafka.recovery-delay-ms:30000}")
    public void republishRecoverableTasks() {
        if (!properties.isEnabled()) return;
        List<ContextCompactionTaskEntity> tasks = taskRepository.queryRecoverable(batchSize,
                properties.getCompactionMaxAttempts());
        for (ContextCompactionTaskEntity task : tasks) {
            try {
                publisher.publish(task.toCommand());
            } catch (RuntimeException exception) {
                // 任务仍留在数据库等待下一轮；单个发送失败不能阻止其他任务被补发。
                log.warn("上下文整理任务补发失败 taskId:{}", task.getTaskId(), exception);
            }
        }
        if (!tasks.isEmpty()) {
            log.info("上下文整理任务补发扫描完成 count:{}", tasks.size());
        }
    }
}
