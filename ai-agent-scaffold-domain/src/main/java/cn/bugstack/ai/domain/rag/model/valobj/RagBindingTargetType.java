package cn.bugstack.ai.domain.rag.model.valobj;

/**
 * 知识库绑定的运行目标类型。
 * <p>租户、目标类型与目标标识共同限定绑定查询范围，避免不同目标类型下的相同标识发生混用。</p>
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
