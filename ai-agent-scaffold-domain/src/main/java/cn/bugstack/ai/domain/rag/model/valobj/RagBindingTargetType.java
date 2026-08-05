package cn.bugstack.ai.domain.rag.model.valobj;

/**
 * 一条知识库绑定挂在「哪种东西」上的类型标记。
 *
 * <p>属于哪一层：领域层值对象。它和 targetId 一起构成绑定的定位坐标，
 * 单独一个 targetId 是不够的——不同类型下的 ID 可能重复，必须类型加 ID 一起查。</p>
 *
 * <p>谁会读它：仓储按 (tenantId, targetType, targetId) 查这次运行能用哪些知识库；
 * 会话 RAG 设置服务用它决定「当前这轮对话的授权范围」；手动选择绑定时也用它防止把
 * A 工作流的绑定选进 B Agent 的会话里。</p>
 *
 * <p>它不负责什么：不表示绑定是否启用、是否 required、优先级多少，那些在 RagAgentBindingEntity 上。</p>
 */
public enum RagBindingTargetType {

    /**
     * 绑定挂在单个智能体上：这个 Agent 的每轮对话都按它的绑定列表检索。
     *
     * <p>进入方式：管理员为某个 agentId 配置知识库。</p>
     *
     * <p>该类型下 targetId 存的是 agentId；查询时不会命中工作流的绑定。</p>
     */
    AGENT,

    /**
     * 绑定挂在整个工作流上：工作流内所有节点共享这份授权。
     *
     * <p>进入方式：管理员为某个 workflowId 配置知识库。</p>
     *
     * <p>该类型下 targetId 存的是 workflowId，粒度最粗，适合整条流程都要查同一套资料的场景。</p>
     */
    WORKFLOW,

    /**
     * 绑定挂在工作流里的某一个节点上：只有那个节点执行时才能用这份资料。
     *
     * <p>进入方式：管理员为具体节点单独配置知识库，用来收紧授权范围。</p>
     *
     * <p>该类型下 targetId 存的是节点标识；同一工作流的其他节点查不到它，
     * 这样敏感知识库就不会被整条流程里所有节点顺带读到。</p>
     */
    WORKFLOW_NODE
}
