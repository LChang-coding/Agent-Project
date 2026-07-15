package cn.bugstack.ai.api.dto.session;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 会话游标分页响应。
 */
@Data
@Builder
public class SessionListResponseDTO {
    private List<SessionSummaryResponseDTO> items;
    private String nextCursor;
    private boolean hasMore;
}
