package cn.bugstack.ai.api.dto.session;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 会话删除响应。
 */
@Data
@AllArgsConstructor
public class SessionDeleteResponseDTO {
    private String sessionId;
    private long contextRevision;
}
