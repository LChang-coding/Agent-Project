package cn.bugstack.ai.domain.context.model;

import lombok.Builder;
import lombok.Data;

/**
 * 上下文组装请求。
 * <p>描述一次模型调用前需要读取的业务会话切面。</p>
 */
@Data
@Builder
public class ContextAssembleRequest {

    private String tenantId;
    private String userId;
    private String sessionId;
    private Integer visibleThroughSequence;
    private String upstreamOutput;
    private String traceId;
}
