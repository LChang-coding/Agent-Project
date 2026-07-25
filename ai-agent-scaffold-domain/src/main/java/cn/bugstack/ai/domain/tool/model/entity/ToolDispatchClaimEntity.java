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

    /** true 表示当前请求首次取得外部执行权。 */
    private boolean claimed;
    /** 已存在或刚创建的权威调用日志。 */
    private ToolCallLogEntity callLog;
}
