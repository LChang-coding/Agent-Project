package cn.bugstack.ai.infrastructure.adapter.repository;

import cn.bugstack.ai.domain.context.adapter.repository.IContextHistoryRepository;
import cn.bugstack.ai.domain.session.model.entity.ChatMessageEntity;
import cn.bugstack.ai.infrastructure.dao.IChatMessageDao;
import cn.bugstack.ai.infrastructure.dao.po.ChatMessagePO;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 会话上下文历史 MySQL 仓储。
 */
@Repository
public class ContextHistoryRepository implements IContextHistoryRepository {

    private final IChatMessageDao chatMessageDao;

    /**
     * 创建历史仓储；参数是消息 DAO；返回仓储实例。
     */
    public ContextHistoryRepository(IChatMessageDao chatMessageDao) {
        this.chatMessageDao = chatMessageDao;
    }

    /**
     * 查询会话消息范围；参数是会话身份和序号范围；返回按序排列的消息。
     */
    @Override
    public List<ChatMessageEntity> queryMessages(String tenantId, String userId, String sessionId, Integer fromSequenceExclusive, Integer toSequenceInclusive) {
        return chatMessageDao.queryContextRange(blankToNull(tenantId), userId, sessionId, fromSequenceExclusive, toSequenceInclusive).stream()
                .map(this::toEntity)
                .toList();
    }

    /**
     * 汇总会话消息 token；参数是会话身份和序号范围；返回预估 token 总数。
     */
    @Override
    public int sumEstimatedTokens(String tenantId, String userId, String sessionId, Integer fromSequenceExclusive, Integer toSequenceInclusive) {
        Integer tokens = chatMessageDao.sumContextTokens(blankToNull(tenantId), userId, sessionId, fromSequenceExclusive, toSequenceInclusive);
        return tokens == null ? 0 : tokens;
    }

    private ChatMessageEntity toEntity(ChatMessagePO po) {
        return ChatMessageEntity.builder()
                .tenantId(po.getTenantId())
                .userId(po.getUserId())
                .sessionId(po.getSessionId())
                .messageId(po.getMessageId())
                .role(po.getRole())
                .contentType(po.getContentType())
                .content(po.getContent())
                .estimatedTokenCount(po.getEstimatedTokenCount())
                .sequenceNo(po.getSequenceNo())
                .parentMessageId(po.getParentMessageId())
                .traceId(po.getTraceId())
                .build();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
