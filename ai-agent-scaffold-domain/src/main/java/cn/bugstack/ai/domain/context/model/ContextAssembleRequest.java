package cn.bugstack.ai.domain.context.model;

import cn.bugstack.ai.domain.rag.model.valobj.RagBindingTargetType;
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
    private Integer attachmentVisibleThroughSequence;
    /** 长期摘要已覆盖的最大消息序号；附件贡献只读取该序号之后的引用。 */
    private Integer coveredToSequence;
    private String upstreamOutput;
    private String traceId;
    /** 当前模型调用对应的可信 RAG 绑定目标。 */
    private RagBindingTargetType ragTargetType;
    private String ragTargetId;
    /** 本轮真实用户问题；RAG 不使用历史摘要或浏览器自报字段代替。 */
    private String ragQuery;
    private String runId;
}
