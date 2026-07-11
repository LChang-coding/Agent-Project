package cn.bugstack.ai.infrastructure.adapter.repository;

import cn.bugstack.ai.domain.context.adapter.repository.IContextCacheRepository;
import cn.bugstack.ai.domain.context.model.ConversationMemorySnapshotEntity;
import cn.bugstack.ai.domain.session.model.entity.ChatMessageEntity;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 上下文 Redis 缓存仓储。
 * <p>摘要使用字符串缓存，短期消息使用按 sequenceNo 排序的 Redis ZSet；缓存不可用时回源 MySQL。</p>
 */
@Slf4j
@Repository
public class ContextCacheRepository implements IContextCacheRepository {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    /**
     * 创建缓存仓储；参数是 Redis 模板和 JSON 工具；返回仓储实例。
     */
    public ContextCacheRepository(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 查询缓存中的有效摘要；参数是会话身份；返回摘要或空。
     */
    @Override
    public ConversationMemorySnapshotEntity queryActiveSnapshot(String tenantId, String userId, String sessionId) {
        try {
            String value = redisTemplate.opsForValue().get(snapshotKey(tenantId, userId, sessionId));
            return value == null || value.isBlank() ? null : objectMapper.readValue(value, ConversationMemorySnapshotEntity.class);
        } catch (Exception e) {
            log.debug("上下文摘要缓存读取失败 tenantId:{} userId:{} sessionId:{}", tenantId, userId, sessionId, e);
            return null;
        }
    }

    /**
     * 缓存有效摘要；参数是摘要和过期时间；无返回值。
     */
    @Override
    public void cacheActiveSnapshot(ConversationMemorySnapshotEntity snapshot, Duration ttl) {
        if (snapshot == null) {
            return;
        }
        try {
            redisTemplate.opsForValue().set(snapshotKey(snapshot.getTenantId(), snapshot.getUserId(), snapshot.getSessionId()),
                    objectMapper.writeValueAsString(snapshot), ttl);
        } catch (JsonProcessingException e) {
            log.debug("上下文摘要缓存序列化失败 tenantId:{} userId:{} sessionId:{}",
                    snapshot.getTenantId(), snapshot.getUserId(), snapshot.getSessionId(), e);
        } catch (Exception e) {
            log.debug("上下文摘要缓存写入失败 tenantId:{} userId:{} sessionId:{}",
                    snapshot.getTenantId(), snapshot.getUserId(), snapshot.getSessionId(), e);
        }
    }

    /**
     * 将已落库消息追加到会话短期窗口；参数是消息、窗口条数和过期时间；无返回值。
     */
    @Override
    public void appendRecentMessage(ChatMessageEntity message, int maxMessages, Duration ttl) {
        if (message == null || message.getSequenceNo() == null) {
            return;
        }
        try {
            String key = recentMessagesKey(message.getTenantId(), message.getUserId(), message.getSessionId());
            double sequenceNo = message.getSequenceNo();
            redisTemplate.opsForZSet().removeRangeByScore(key, sequenceNo, sequenceNo);
            redisTemplate.opsForZSet().add(key, objectMapper.writeValueAsString(message), sequenceNo);
            trimRecentMessages(key, maxMessages);
            redisTemplate.expire(key, ttl);
        } catch (Exception e) {
            log.debug("上下文短期消息追加失败 tenantId:{} userId:{} sessionId:{}",
                    message.getTenantId(), message.getUserId(), message.getSessionId(), e);
        }
    }

    /**
     * 查询会话短期窗口中的指定序号切面；参数是会话身份和序号范围；返回消息列表，缓存未命中时返回空值。
     */
    @Override
    public List<ChatMessageEntity> queryRecentMessages(String tenantId, String userId, String sessionId,
                                                        Integer fromSequenceExclusive, Integer toSequenceInclusive) {
        try {
            String key = recentMessagesKey(tenantId, userId, sessionId);
            if (!Boolean.TRUE.equals(redisTemplate.hasKey(key))) {
                return null;
            }
            double lowerBound = fromSequenceExclusive == null ? 0D : fromSequenceExclusive + 0.1D;
            double upperBound = toSequenceInclusive == null ? Double.MAX_VALUE : toSequenceInclusive;
            Set<String> values = redisTemplate.opsForZSet().rangeByScore(key, lowerBound, upperBound);
            if (values == null || values.isEmpty()) {
                return List.of();
            }
            List<ChatMessageEntity> messages = new ArrayList<>();
            for (String value : values) {
                messages.add(objectMapper.readValue(value, new TypeReference<>() {
                }));
            }
            return messages;
        } catch (Exception e) {
            log.debug("上下文短期消息读取失败 tenantId:{} userId:{} sessionId:{}", tenantId, userId, sessionId, e);
            return null;
        }
    }

    /**
     * 用原始历史预热会话短期窗口；参数是会话身份、消息、窗口条数和过期时间；无返回值。
     */
    @Override
    public void warmRecentMessages(String tenantId, String userId, String sessionId, List<ChatMessageEntity> messages,
                                   int maxMessages, Duration ttl) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        for (ChatMessageEntity message : messages) {
            appendRecentMessage(message, maxMessages, ttl);
        }
    }

    /**
     * 移除已进入长期摘要的短期消息；参数是会话身份和已覆盖序号；无返回值。
     */
    @Override
    public void removeRecentMessagesThrough(String tenantId, String userId, String sessionId, Integer coveredToSequence) {
        if (coveredToSequence == null || coveredToSequence <= 0) {
            return;
        }
        try {
            redisTemplate.opsForZSet().removeRangeByScore(recentMessagesKey(tenantId, userId, sessionId), 0D, coveredToSequence);
        } catch (Exception e) {
            log.debug("上下文短期消息裁剪失败 tenantId:{} userId:{} sessionId:{}", tenantId, userId, sessionId, e);
        }
    }

    /**
     * 失效会话缓存；参数是会话身份；无返回值。
     */
    @Override
    public void evictSession(String tenantId, String userId, String sessionId) {
        try {
            redisTemplate.delete(snapshotKey(tenantId, userId, sessionId));
            redisTemplate.delete(recentMessagesKey(tenantId, userId, sessionId));
        } catch (Exception e) {
            log.debug("上下文缓存失效失败 tenantId:{} userId:{} sessionId:{}", tenantId, userId, sessionId, e);
        }
    }

    private String snapshotKey(String tenantId, String userId, String sessionId) {
        return "ctx:snapshot:" + blank(tenantId) + ':' + blank(userId) + ':' + blank(sessionId);
    }

    private void trimRecentMessages(String key, int maxMessages) {
        if (maxMessages <= 0) {
            return;
        }
        Long size = redisTemplate.opsForZSet().zCard(key);
        if (size != null && size > maxMessages) {
            redisTemplate.opsForZSet().removeRange(key, 0, size - maxMessages - 1);
        }
    }

    private String recentMessagesKey(String tenantId, String userId, String sessionId) {
        return "ctx:recent:" + blank(tenantId) + ':' + blank(userId) + ':' + blank(sessionId);
    }

    private String blank(String value) {
        return value == null ? "" : value;
    }
}
