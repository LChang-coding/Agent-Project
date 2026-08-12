package cn.bugstack.ai.trigger.listener;

import cn.bugstack.ai.domain.agent.adapter.repository.ISubagentCoordinationCache;
import cn.bugstack.ai.domain.agent.adapter.repository.ISubagentTaskRepository;
import cn.bugstack.ai.domain.agent.model.entity.SubagentTaskEntity;
import cn.bugstack.ai.domain.agent.service.IChatService;
import cn.bugstack.ai.domain.session.service.SessionLifecycleService;
import cn.bugstack.ai.types.enums.ResponseCode;
import cn.bugstack.ai.types.exception.AppException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.List;

/** Parent ACK 后清理平台/ADK 临时会话、Redis 运行实例和 Inbox 索引。 */
@Component
public class SubagentInstanceCleanupConsumer {
    private final ObjectMapper objectMapper;
    private final ISubagentCoordinationCache cache;
    private final ISubagentTaskRepository repository;
    private final SessionLifecycleService sessionLifecycleService;
    private final IChatService chatService;

    public SubagentInstanceCleanupConsumer(ObjectMapper objectMapper, ISubagentCoordinationCache cache,
                                           ISubagentTaskRepository repository,
                                           SessionLifecycleService sessionLifecycleService, IChatService chatService) {
        this.objectMapper = objectMapper; this.cache = cache; this.repository = repository;
        this.sessionLifecycleService = sessionLifecycleService; this.chatService = chatService;
    }

    @KafkaListener(topics = "${ai.agent.orchestration.cleanup-topic:agent.subagent.cleanup.v1}",
            groupId = "${ai.agent.orchestration.cleanup-group:ai-agent-instance-cleanup}",
            autoStartup = "${ai.agent.orchestration.enabled:false}")
    public void consume(String payload) throws Exception {
        JsonNode event = objectMapper.readTree(payload);
        if (event.path("schemaVersion").asInt(-1) != 1) throw new IllegalArgumentException("SUBAGENT_EVENT_VERSION_UNSUPPORTED");
        String tenantId = required(event, "tenantId"); String taskId = required(event, "taskId");
        String parentRunId = required(event, "parentRunId");
        List<SubagentTaskEntity> tasks = repository.queryByIds(tenantId, parentRunId, List.of(taskId));
        if (!tasks.isEmpty()) deleteSession(tasks.get(0));
        try { cache.removeInstance(tenantId, taskId); cache.removeInbox(tenantId, parentRunId, taskId); }
        catch (RuntimeException ignored) { /* Redis 是可重建缓存，不能阻断权威清理。 */ }
    }

    private void deleteSession(SubagentTaskEntity task) {
        if (task.getChildSessionId() == null || task.getChildSessionId().isBlank()) return;
        try { sessionLifecycleService.delete(task.getTenantId(), task.getUserId(), task.getChildSessionId()); }
        catch (AppException exception) {
            if (!ResponseCode.SESSION_NOT_FOUND.getCode().equals(exception.getCode())) throw exception;
        }
        chatService.deleteSubagentRuntimeSession(task.getChildAgentId(), task.getUserId(), task.getChildSessionId());
    }

    private String required(JsonNode node, String field) {
        String value = node.path(field).asText(null);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("SUBAGENT_EVENT_INVALID");
        return value;
    }
}
