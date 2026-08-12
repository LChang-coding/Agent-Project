package cn.bugstack.ai.infrastructure.agent;

import cn.bugstack.ai.domain.agent.adapter.repository.ISubagentCoordinationCache;
import cn.bugstack.ai.domain.agent.model.entity.SubagentTaskEntity;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/** Redis 加速层；任何键丢失都可由 MySQL 任务账本重建。 */
@Repository
public class RedisSubagentCoordinationCache implements ISubagentCoordinationCache {
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public RedisSubagentCoordinationCache(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis; this.objectMapper = objectMapper;
    }

    @Override
    public void putInstance(SubagentTaskEntity task, Duration ttl) {
        redis.opsForValue().set(instanceKey(task.getTenantId(), task.getTaskId()), json(Map.of(
                "taskId", task.getTaskId(), "childAgentId", task.getChildAgentId(),
                "parentRunId", task.getParentRunId(), "fencingToken", task.getFencingToken())), ttl);
    }

    @Override
    public void heartbeat(String tenantId, String taskId, Duration ttl) { redis.expire(instanceKey(tenantId, taskId), ttl); }

    @Override
    public void addInbox(String tenantId, String parentRunId, String taskId, Duration ttl) {
        String key = inboxKey(tenantId, parentRunId);
        redis.opsForZSet().add(key, taskId, Instant.now().toEpochMilli()); redis.expire(key, ttl);
    }

    @Override
    public void removeInstance(String tenantId, String taskId) { redis.delete(instanceKey(tenantId, taskId)); }

    @Override
    public void removeInbox(String tenantId, String parentRunId, String taskId) {
        redis.opsForZSet().remove(inboxKey(tenantId, parentRunId), taskId);
    }

    private String instanceKey(String tenantId, String taskId) { return "agent:instance:" + tenantId + ":" + taskId; }
    private String inboxKey(String tenantId, String parentRunId) { return "agent:inbox:" + tenantId + ":" + parentRunId; }
    private String json(Map<String, Object> value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (JsonProcessingException exception) { throw new IllegalArgumentException("SUBAGENT_CACHE_SERIALIZE_FAILED", exception); }
    }
}
