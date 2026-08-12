# RAG 与智能路由全栈交付团队

## 目标

完成 `docs/superpowers/plans/2026-08-06-rag-route-platform-tools-fullstack-execution-plan.md` 中尚未闭环的工作，使 `rag_retrieve` 与 `select_workflow_route` 通过统一 `PLATFORM` 工具通道进入真实运行链，并完成后端、前端、迁移、事件和测试验证。

## 团队角色

1. **主控集成者**：拥有 `ChatService`、`IntelligentWorkflowRuntimeService`、跨模块构造器接线、最终冲突处理、全量测试和执行记录。其他成员不得修改这些热点文件。
2. **工具网关工程师**：拥有 `GatewayAdkTool`、`GatewayToolset`、`PlatformToolResolver`、平台工具配置及对应测试；负责可信上下文完整传递和 JSON Schema 边界。
3. **RAG 工程师**：拥有 RAG handler、展示、预算、证据闭环及对应测试；必须复用 `RagRetrievalService`，不得新建检索链路。
4. **路由工具工程师**：拥有 `WorkflowRoutePlatformToolHandler`、route intent 领域服务及对应测试；工具只登记意图，不推进节点。
5. **工作流协议工程师**：拥有工作流 graph/plan/compiler 的协议冻结字段及对应测试；旧定义缺字段时保持 `MARKER_V1`，新智能定义允许 `TOOL_V2`。
6. **迁移与审查工程师**：拥有本任务升级/回滚 SQL 和迁移契约测试，或执行只读审查。

## 工作规则

1. 开始前完整阅读 `codex.md` 和权威执行计划，只以当前主工作树为准，忽略 `.claude/worktrees/**`。
2. 按测试先行工作：先增加能证明缺口的测试并确认 RED，再写最小实现并确认 GREEN。不得用无关编译错误冒充 RED。
3. 严格遵守 `trigger -> domain <- infrastructure`；身份、运行、节点和定义信息只能来自服务端可信上下文，不能从模型参数读取。
4. `rag_retrieve` 只接受 `query`、`maxContextTokens`；`select_workflow_route` 只接受 `routeKey`、`reason`。
5. 每次工具调用继续经过取消、运行状态、context revision、幂等、审计和 Trace 闸门。
6. 路由 handler 只能 claim route intent。节点推进、DEFAULT、FAILURE、repair、预算和终态由运行时负责。
7. 发现其他成员或用户的无关修改时保留并绕开；若与自己的文件所有权直接冲突，停止写入并报告主控。
8. 禁止提交日志/对象存储/评测产物、运行破坏性 Git 命令、创建提交或 push。提交只由主控在用户明确要求后执行。
9. 每个成员结束时报告：修改文件、关键语义、执行命令、真实测试结果、剩余风险。不得把未运行的验证写成通过。

## 文件所有权

1. 多个成员不得并行修改同一文件。
2. `ChatService.java`、`IntelligentWorkflowRuntimeService.java`、计划文档和最终配置由主控独占。
3. 前端当前协议文件默认只由主控调整；除非主控在任务提示中明确授予具体文件。
4. 日志、`.claude/**`、`.cw_skill/**`、`data/**`、对象存储和 RAG 评测目录永远不属于本任务成员。

## 完成标准

1. `AGENT_TOOL` 禁止自动 RAG，但保留历史、附件和上游上下文；模型可真实发现并调用 `rag_retrieve`。
2. `TOOL_V2` 非终点智能节点只看到当前合法 route key；intent 被原子消费并形成权威 route decision。
3. 正常缺路由最多 repair 一次，然后走 DEFAULT 或 `WORKFLOW_ROUTE_REQUIRED`；技术异常走 FAILURE；取消不走 FAILURE。
4. 后端产生可持久重放的工具和路由事件，前端不从正文猜测权威路由。
5. SQL 升级与回滚满足仓库迁移门禁；后端定向测试、全模块编译/测试、前端单测与构建均有真实结果。
