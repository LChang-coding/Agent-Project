package cn.bugstack.ai.trigger.job;

import cn.bugstack.ai.domain.agent.adapter.repository.IToolApprovalRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.LocalDateTime;

@Component
@ConditionalOnProperty(prefix="ai.agent.orchestration",name="enabled",havingValue="true")
public class ToolApprovalRecoveryJob {
    private final IToolApprovalRepository repository;
    public ToolApprovalRecoveryJob(IToolApprovalRepository repository){this.repository=repository;}
    @Scheduled(fixedDelayString="${ai.agent.orchestration.approval-timeout-poll-ms:1000}")
    public void expire(){repository.decideExpired(LocalDateTime.now(),100);}
}
