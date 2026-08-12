package cn.bugstack.ai.trigger.job;

import cn.bugstack.ai.domain.agent.adapter.repository.ISubagentTaskRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;

/** 为已经没有 Kafka 唤醒消息的过期执行 Lease 和回调 Lease 重建 Outbox。 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "ai.agent.orchestration", name = "enabled", havingValue = "true")
public class SubagentRecoveryJob {
    private final ISubagentTaskRepository repository;

    public SubagentRecoveryJob(ISubagentTaskRepository repository) {
        this.repository = repository;
    }

    @Scheduled(fixedDelayString = "${ai.agent.orchestration.recovery-poll-ms:10000}")
    public void recover() {
        int recovered = repository.recoverExpired(LocalDateTime.now(), Duration.ofMinutes(5), 100);
        if (recovered > 0) log.warn("已恢复过期子 Agent 执行或回调数量:{}", recovered);
    }
}
