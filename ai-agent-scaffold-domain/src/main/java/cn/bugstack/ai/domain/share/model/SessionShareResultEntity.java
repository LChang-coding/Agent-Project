package cn.bugstack.ai.domain.share.model;

import cn.bugstack.ai.domain.session.model.entity.ChatMessageEntity;
import cn.bugstack.ai.domain.session.model.entity.ChatSessionEntity;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 会话分享操作结果。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class SessionShareResultEntity {
    private SessionShareEntity share;
    private String token;
    private ChatSessionEntity session;
    private List<ChatMessageEntity> messages;
    private byte[] exportBytes;
    private String sourceType;
    private String workflowId;
    private String sourceAgentId;
    private String sourceAgentName;
    private String sourceAppName;
    private List<SessionToolDependencyEntity> toolDependencies;
    private SessionToolPrecheckEntity toolPrecheck;
    private Boolean legacySnapshot;
}
