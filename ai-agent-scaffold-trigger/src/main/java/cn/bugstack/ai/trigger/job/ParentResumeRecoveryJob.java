package cn.bugstack.ai.trigger.job;

import cn.bugstack.ai.domain.agent.adapter.repository.IParentResumeRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/** 恢复丢失 Kafka 消息或 Worker 宕机后过期的主 Agent 恢复请求。 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "ai.agent.orchestration", name = "enabled", havingValue = "true")
public class ParentResumeRecoveryJob {
    private final IParentResumeRepository repository;

    public ParentResumeRecoveryJob(IParentResumeRepository repository) { this.repository = repository; }

    @Scheduled(fixedDelayString = "${ai.agent.orchestration.resume-recovery-poll-ms:10000}")
    public void recover() {
        int recovered = repository.recoverDue(LocalDateTime.now(), 100);
        if (recovered > 0) log.warn("已重建主 Agent 恢复 Outbox 数量:{}", recovered);
    }
}
