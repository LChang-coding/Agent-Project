package cn.bugstack.ai.domain.rag.model.valobj;

/**
 * 知识库绑定目标类型。
 * <p>支持普通 Agent、整个工作流和单个工作流节点三个授权粒度。</p>
 */
public enum RagBindingTargetType {
    AGENT,
    WORKFLOW,
    WORKFLOW_NODE
}
