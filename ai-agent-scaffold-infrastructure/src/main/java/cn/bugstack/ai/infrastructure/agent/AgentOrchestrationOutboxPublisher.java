package cn.bugstack.ai.infrastructure.agent;

import cn.bugstack.ai.infrastructure.dao.ISubagentTaskDao;
import cn.bugstack.ai.infrastructure.dao.po.AgentOrchestrationOutboxPO;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** 通过租约和 fencing CAS 可靠发布 Agent 编排 Outbox。 */
@Component
@ConditionalOnProperty(prefix = "ai.agent.orchestration", name = "enabled", havingValue = "true")
public class AgentOrchestrationOutboxPublisher {
    private final ISubagentTaskDao dao;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final String owner = "agent-outbox-" + UUID.randomUUID();
    private final String taskTopic;
    private final String resultTopic;
    private final String cleanupTopic;
    private final String resumeTopic;

    @org.springframework.beans.factory.annotation.Autowired
    public AgentOrchestrationOutboxPublisher(ISubagentTaskDao dao, KafkaTemplate<String, String> kafkaTemplate,
            @Value("${ai.agent.orchestration.task-topic:agent.subagent.task.v1}") String taskTopic,
            @Value("${ai.agent.orchestration.result-topic:agent.subagent.result.v1}") String resultTopic,
            @Value("${ai.agent.orchestration.cleanup-topic:agent.subagent.cleanup.v1}") String cleanupTopic,
            @Value("${ai.agent.orchestration.resume-topic:agent.parent.resume.v1}") String resumeTopic) {
        this.dao = dao;
        this.kafkaTemplate = kafkaTemplate;
        this.taskTopic = taskTopic; this.resultTopic = resultTopic; this.cleanupTopic = cleanupTopic;
        this.resumeTopic = resumeTopic;
    }

    @Scheduled(fixedDelayString = "${ai.agent.orchestration.outbox-poll-ms:1000}")
    public void publishDue() {
        LocalDateTime now = LocalDateTime.now();
        for (AgentOrchestrationOutboxPO candidate : dao.queryDueOutbox(now, 100)) publish(candidate, now);
    }

    protected AgentOrchestrationOutboxPO claim(AgentOrchestrationOutboxPO candidate, LocalDateTime now) {
        if (dao.claimOutbox(candidate.getTenantId(), candidate.getEventId(), owner, now, now.plusSeconds(30)) != 1) return null;
        return dao.queryOwnedOutbox(candidate.getTenantId(), candidate.getEventId(), owner);
    }

    private void publish(AgentOrchestrationOutboxPO candidate, LocalDateTime now) {
        AgentOrchestrationOutboxPO event = claim(candidate, now);
        if (event == null) return;
        try {
            kafkaTemplate.send(topic(event.getEventType()), event.getPartitionKey(), event.getPayload())
                    .get(10, TimeUnit.SECONDS);
            dao.markOutboxPublished(event.getTenantId(), event.getEventId(), owner,
                    event.getFencingToken(), LocalDateTime.now());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            retry(event, exception);
        } catch (Exception exception) {
            retry(event, exception);
        }
    }

    private void retry(AgentOrchestrationOutboxPO event, Exception exception) {
        long delay = Math.min(300, 1L << Math.min(8, event.getAttemptCount() == null ? 0 : event.getAttemptCount()));
        String message = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
        dao.markOutboxRetry(event.getTenantId(), event.getEventId(), owner, event.getFencingToken(),
                LocalDateTime.now().plusSeconds(delay), message.substring(0, Math.min(1000, message.length())));
    }

    private String topic(String eventType) {
        return switch (eventType) {
            case "SUBAGENT_TASK_READY" -> taskTopic;
            case "SUBAGENT_RESULT_READY" -> resultTopic;
            case "SUBAGENT_INSTANCE_CLEANUP" -> cleanupTopic;
            case "PARENT_RESUME_REQUESTED" -> resumeTopic;
            default -> throw new IllegalArgumentException("UNKNOWN_AGENT_EVENT_TYPE:" + eventType);
        };
    }
}
