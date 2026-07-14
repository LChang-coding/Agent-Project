package cn.bugstack.ai.domain.tool.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 工具分发权实体。
 * <p>记录当前请求是否首次取得外部工具执行权，以及对应的持久化审计。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolDispatchClaimEntity {

    private boolean claimed;
    private ToolCallLogEntity callLog;
}
