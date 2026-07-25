package cn.bugstack.ai.domain.context.model;

import cn.bugstack.ai.domain.rag.model.valobj.RagBindingTargetType;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 上下文组装请求。
 * <p>描述一次模型调用前需要读取的业务会话切面。</p>
 */
@Data
@Builder
public class ContextAssembleRequest {

    /** 会话所属租户。 */
    private String tenantId;
    /** 会话所属用户。 */
    private String userId;
    /** 待读取的会话。 */
    private String sessionId;
    /** 本次模型可见的最大有效消息序号。 */
    private Integer visibleThroughSequence;
    /** 附件引用可见的最大消息序号。 */
    private Integer attachmentVisibleThroughSequence;
    /** 长期摘要已覆盖的最大消息序号；附件贡献只读取该序号之后的引用。 */
    private Integer coveredToSequence;
    /** 工作流上一节点输出。 */
    private String upstreamOutput;
    /** 本次模型调用链路标识。 */
    private String traceId;
    /** 当前模型调用对应的可信 RAG 绑定目标。 */
    private RagBindingTargetType ragTargetType;
    /** 当前 Agent 或工作流标识。 */
    private String ragTargetId;
    /** 本轮Run已冻结的有效RAG绑定ID。 */
    private List<String> ragBindingIds;
    /** 本轮真实用户问题；RAG 不使用历史摘要或浏览器自报字段代替。 */
    private String ragQuery;
    /** 本次运行标识，用于关联取消和检索证据。 */
    private String runId;
}
