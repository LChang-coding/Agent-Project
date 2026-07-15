package cn.bugstack.ai.api.dto.session;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 会话消息游标分页响应。
 */
@Data
@Builder
public class SessionMessagePageResponseDTO {
    private String sessionId;
    private List<SessionMessageResponseDTO> items;
    private Integer nextBeforeSequence;
    private boolean hasMore;
}
