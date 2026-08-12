package cn.bugstack.ai.trigger.listener;

import cn.bugstack.ai.domain.agent.adapter.repository.ISubagentCoordinationCache;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Parent ACK 后清理 Redis 中的临时运行实例和 Inbox 索引。 */
@Component
public class SubagentInstanceCleanupConsumer {
    private final ObjectMapper objectMapper;
    private final ISubagentCoordinationCache cache;

    public SubagentInstanceCleanupConsumer(ObjectMapper objectMapper, ISubagentCoordinationCache cache) {
        this.objectMapper = objectMapper; this.cache = cache;
    }

    @KafkaListener(topics = "${ai.agent.orchestration.cleanup-topic:agent.subagent.cleanup.v1}",
            groupId = "${ai.agent.orchestration.cleanup-group:ai-agent-instance-cleanup}",
            autoStartup = "${ai.agent.orchestration.enabled:false}")
    public void consume(String payload) throws Exception {
        JsonNode event = objectMapper.readTree(payload);
        if (event.path("schemaVersion").asInt(-1) != 1) throw new IllegalArgumentException("SUBAGENT_EVENT_VERSION_UNSUPPORTED");
        String tenantId = required(event, "tenantId"); String taskId = required(event, "taskId");
        String parentRunId = required(event, "parentRunId");
        cache.removeInstance(tenantId, taskId); cache.removeInbox(tenantId, parentRunId, taskId);
    }

    private String required(JsonNode node, String field) {
        String value = node.path(field).asText(null);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("SUBAGENT_EVENT_INVALID");
        return value;
    }
}
