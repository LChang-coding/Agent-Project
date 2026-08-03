# 智能工作流路由与节点流式输出交付文档

## 1. 文档目的

本文档是“智能工作流”后续开发、联调、测试和上线验收的唯一交付基线，固化两项已确认的能力：

1. **所有节点可参与智能路由**：任意 Agent、RAG、Tool、条件、人工、子工作流等节点执行后，都可以按规则、节点建议或 AI 判断选择下一节点。
2. **所有中间节点输出可流式查看**：聊天页在最终回答上方展示节点执行面板，当前节点显示运行动画，展开后持续看到该节点允许展示的流式内容。

`traceId` 完整性是本能力的上线硬门禁，不是可选的日志优化。

> **当前交付边界（2026-08-04）**：已经真实闭环的是“单活动路径智能路由 + LLM 节点执行 + 显式 END + 中间节点事件面板 + 刷新后事件重放 + 完成/取消终态 + 根 Trace 反查”。本文第 2～12 章同时包含最终目标和上线前测试基线；其中 Human、SubWorkflow、并行 fan-out/Join、多实例接管、完整故障注入与性能稳定性尚未交付，不能把设计条目理解为已经实现。

## 2. 范围与非目标

### 2.1 本期范围

- 在现有系统工作流和 RAG 工作流之外新增“智能工作流”Tab，标明“更智能，但消耗更多 Token”。
- 在不改变现有两类工作流语义的前提下，复用现有 Agent、RAG、ToolGateway、Context Manager、Run Control 和工作流有界执行器。
- 支持固定路由、受限表达式路由、节点自主建议、独立 AI Router、默认兜底和异常路由。
- 支持分支、回退、有限循环、暂停、取消、恢复和后端 API/定时/事件调度。
- 建立通用的节点流式事件协议，后续可让现有系统工作流和 RAG 工作流逐步接入，不新建三套 SSE 协议。
- 事件持久化、顺序化、去重和断线续传。
- 每个节点执行实例、每次路由和每个下游调用全链路携带同一工作流 `traceId`。

### 2.2 本期非目标

- 不允许 Agent 绕过工作流定义直接调用任意 Agent。
- 不向前端暴露模型隐藏思维链、系统提示词、密钥、完整工具认证参数或未脱敏异常。
- 不用智能工作流重写已有 Agent、RAG、工具、会话或附件能力。
- 不用单次 HTTP 请求的内存状态作为工作流唯一真相。
- 不以“容器还在运行”或“页面能打开”代替业务链路验收。

## 3. 核心架构决策

### 3.1 三权分离

| 职责 | 拥有者 | 允许做什么 | 禁止做什么 |
|---|---|---|---|
| 路由建议权 | 当前节点/Agent | 输出决策、建议目标、理由和置信度 | 直接执行目标 Agent |
| 路由裁决权 | 统一路由引擎 | 按图定义、权限、预算、循环和取消状态校验路由 | 跳过已发布的工作流版本 |
| 节点调度权 | 工作流运行时 | 事务化地保存决策并创建下一节点任务 | 在取消、超限或版本不一致时继续执行 |

### 3.2 每个节点都经过路由阶段

```text
取消/预算前置检查
        ↓
构造节点输入与上下文
        ↓
执行 Agent / RAG / Tool / Human / SubWorkflow
        ↓
保存 NodeExecutionResult
        ↓
依次执行：异常 → 表达式 → 节点建议 → AI Router → 默认兜底
        ↓
校验目标节点、租户权限、循环、Token、次数、时间和取消状态
        ↓
保存 RouteDecision 并调度下一节点
```

显式 `AI_ROUTER` 节点只是复用复杂路由逻辑的可选能力，不是所有节点必须经过的中央审核节点。

### 3.3 分层落位

| 层 | 交付物 |
|---|---|
| Trigger | 定义、发布、校验、启动、暂停、恢复、取消、人工决策、历史事件和 SSE 续传接口 |
| Domain | 图编译器、路由引擎、运行时、预算/循环/取消守卫、节点执行器接口、事件模型和仓储抽象 |
| Infrastructure | MySQL 仓储、Outbox/Kafka、Agent/RAG/Tool/模型适配、租约领取、Trace 传播、日志与指标 |
| App | Bean 装配、线程池和各环境配置 |
| Web | 智能工作流 Tab、画布路由配置、发布校验、节点流式面板、断线恢复与 Trace 复制 |

## 4. 工作流定义与发布

### 4.1 节点定义最小契约

```json
{
  "nodeId": "review",
  "nodeType": "AGENT",
  "name": "合同审核",
  "execution": {
    "agentId": "agent_review"
  },
  "outputSchema": {
    "type": "object",
    "required": ["status", "reason"]
  },
  "routingPolicy": {
    "mode": "HYBRID",
    "allowedTargetNodeIds": ["publish", "regenerate", "manual_review"],
    "enabledStrategies": ["EXCEPTION", "EXPRESSION", "NODE_SUGGESTION", "AI_ROUTER", "DEFAULT"],
    "minimumConfidence": 0.8,
    "defaultTargetNodeId": "manual_review"
  },
  "contextPolicy": {
    "maxContextTokens": 16000,
    "includeNodeIds": ["generate"],
    "maxHistoryExecutionsPerNode": 2
  }
}
```

### 4.2 边定义最小契约

```json
{
  "edgeId": "review-rejected",
  "sourceNodeId": "review",
  "targetNodeId": "regenerate",
  "routeType": "EXPRESSION",
  "expression": "output.status == 'REJECTED'",
  "priority": 100,
  "maxTransitions": 3,
  "onLimitTargetNodeId": "manual_review"
}
```

### 4.3 发布前图编译门禁

- 节点、边 ID 唯一，引用对象全部存在。
- 开始节点唯一，至少有一个可达的结束或人工兜底节点。
- 表达式仅能使用白名单操作符和可见运行时字段，禁止反射、类加载、文件、网络和任意方法调用。
- AI 路由只能返回 `allowedTargetNodeIds` 内的目标。
- 运行强连通分量检测；每个循环必须有最大通过次数和超限出口。
- Agent、工具、知识库和子工作流必须属于当前租户且当前用户有权使用。
- 发布版本不可变；修改必须生成新版本和新 `definitionHash`。

### 4.4 路由结果契约

每个可执行节点都返回统一结果：

```json
{
  "nodeExecutionId": "node_exec_xxx",
  "status": "SUCCEEDED",
  "internalOutput": {},
  "displayOutput": {
    "contentType": "MARKDOWN",
    "content": "已完成审核"
  },
  "routeSuggestion": {
    "decision": "REGENERATE",
    "suggestedTargetNodeId": "regenerate",
    "reason": "缺少签署日期",
    "confidence": 0.93
  },
  "usage": {
    "promptTokens": 120,
    "candidateTokens": 35,
    "totalTokens": 155
  },
  "error": null
}
```

路由引擎产生的裁决契约：

```json
{
  "routeDecisionId": "route_xxx",
  "strategy": "NODE_SUGGESTION",
  "sourceNodeId": "review",
  "targetNodeId": "regenerate",
  "matchedEdgeId": "review-rejected",
  "reason": "节点建议合法且置信度达标",
  "confidence": 0.93,
  "terminalStatus": null
}
```

路由策略顺序固定为：`CANCEL_GUARD → BUDGET_GUARD → EXCEPTION → FIXED/SUCCESS → EXPRESSION → NODE_SUGGESTION → AI_ROUTER → DEFAULT`。前端只能提交 `enabledStrategies`，不能重排全局顺序；编译器按固定顺序过滤出已启用子集，传入旧 `strategyOrder` 或企图乱序时直接拒绝发布。同一策略多条边同时命中时，依次按 `priority` 降序、`edgeId` 字典序升序裁决，确保永远得到相同结果。

无候选边、默认边也不可用或全部路由策略失败时，Run 进入 `FAILED_NO_ROUTE`；END 节点执行后直接进入 `COMPLETED`，不再运行后置路由。`AI_ROUTER` 节点自身不得再启用 `AI_ROUTER` 后置策略，防止递归路由。

### 4.5 首期图执行语义

首期智能工作流是**单活动路径状态机**：每次路由裁决只选择一个后继节点。“分支”表示在多个候选节点中择一，不表示并行 fan-out。首期不交付并行分叉、Join 屏障和部分分支失败语义；如后续需要，必须单独设计执行 token、Join 条件、并发 sequence 和取消传播，不在本期隐式实现。

节点状态语义：

| 节点类型 | 完成条件 | 后置行为 |
|---|---|---|
| Agent/RAG/Tool/Condition | 产生统一结果或明确错误 | 进入统一后置路由 |
| Human | 首次执行进入 `WAITING_HUMAN`；有权用户提交决策后完成 | 从原 Run/traceId 进入后置路由 |
| SubWorkflow | child Run 进入 COMPLETED/FAILED/CANCELLED | 将 child 摘要映射为节点结果后路由 |
| AI_ROUTER | 产生结构化路由选择 | 仍由统一路由引擎校验并生成/持久化 `RouteDecision`，再由运行时调度；只是禁止再次调用 AI Router |
| END | 产生完整的 SCHEDULED/STARTED/COMPLETED 生命周期，无业务输出 | 进入 Run 终态，免后置路由 |

定时、事件和 API 触发都在 Run 创建时写入 `executionPrincipal` 授权快照。每次真实执行前仍重新检查租户边界和资源启用状态；资源被删除、禁用或权限被撤销时，Run 进入 `WAITING_AUTHORIZATION` 或明确失败，不继续使用旧权限。

## 5. 运行时与持久化

### 5.1 核心身份

| 字段 | 作用 | 不变量 |
|---|---|---|
| `workflowId + version` | 唯一确定已发布的图 | 运行中不跟随草稿变化 |
| `runId` | 一次工作流运行 | 启动后不变 |
| `nodeId` | 图中的逻辑节点 | 循环重进时不变 |
| `nodeExecutionId` | 一次逻辑节点执行 | 工作流回退/循环重进时新建；同一逻辑执行的技术重试保持不变并递增 `attempt` |
| `routeDecisionId` | 一次路由裁决 | 每次节点执行后独立留痕 |
| `eventId` | 一条流式事件 | 全局唯一，用于去重 |
| `sequence` | 某个 `runId` 的事件序号 | 严格递增，用于续传 |
| `traceId` | 一次工作流的根链路 | 同一 `runId` 内不变 |

### 5.2 必须持久化的数据

- 已发布工作流定义和编译图。
- Run 状态、输入、全局变量、版本、预算使用量、取消/暂停状态和根 `traceId`。
- 每个节点执行实例的输入快照、内部输出、可展示输出、错误、Token、工具/RAG 摘要和时间。
- 每次路由的候选边、命中策略、目标、理由、置信度和超限结果。
- 所有可补发的节点生命周期和显示事件。
- 待执行任务、租约持有者、租约过期时间、幂等键和 Outbox 状态。

### 5.3 执行原则

- 在同一事务中保存节点结果、路由决策、下一任务和 Outbox，防止“结果已保存但下一节点丢失”。
- 节点任务使用 `runId + nodeExecutionId + attempt` 幂等；已确认成功并入账的调用不得因重复消息再执行。
- 节点执行前、模型调用前、工具调用前、RAG 调用前、路由前和创建下一任务前都要重新检查取消。
- 运行时必须复用现有 coordinator/node 有界线程池，不回到 `Schedulers.io()` 或 common pool。

### 5.4 外部副作用与崩溃窗口

本系统不伪造“任意外部调用 exactly-once”承诺。工具在远端已成功、但本地结果未入库时进程崩溃，单凭本地事务无法判定远端是否已产生副作用。因此必须按能力分类：

| 外部能力 | 必须策略 | 崩溃后处理 |
|---|---|---|
| 支持幂等键 | 传递 `nodeExecutionId` 派生的稳定 idempotency key，记录请求/远端返回标识 | 使用同一键查询或重试 |
| 支持结果查询/对账 | 先记录 invocation ledger，远端返回业务 ID | 先查询远端，确认未执行才重试 |
| 不支持幂等且有副作用 | 发布时标记 `NON_REPLAYABLE` 并配置人工对账 | 进入 `WAITING_MANUAL_RECONCILIATION`，禁止自动重试 |
| 无副作用的模型/检索 | 记录 invocation 与用量 | 可按有界策略重试；可能产生重复计费，必须单独记账，只接受一份最终结果 |

暂停不强行中断已在途的不可取消调用；在途结果可入账，但不创建下一节点。取消以 `cancel_requested_at` 事务提交为线性化点：提交后禁止新的模型、Tool、RAG invocation 和下一节点任务登记；已在途调用尽力取消，无法取消的结果可记账但不再驱动路由。

为消除“取消检查后、网络调用前”的竞态，每次模型、Tool 或 RAG 调用必须先在数据库事务内登记 invocation ledger。登记操作通过 Run 行锁或等价的条件更新，只有 `cancel_requested_at IS NULL` 时才能成功；取消事务与调用登记事务必须对同一 Run 串行化。只有已成功登记的 invocation 才能发出网络请求。门禁统计以 invocation 登记提交时刻为准：取消提交前登记、但之后才发出的请求属于已在途调用，必须尽力取消且不得驱动新路由。

## 6. 节点流式事件契约

### 6.1 持久化业务事件类型

```text
WORKFLOW_STARTED
NODE_SCHEDULED
NODE_STARTED
NODE_OUTPUT_DELTA
NODE_OUTPUT_COMPLETED
FINAL_ANSWER_DELTA
FINAL_ANSWER_COMPLETED
TOOL_CALL_STARTED
TOOL_CALL_COMPLETED
RAG_RETRIEVAL_STARTED
RAG_RETRIEVAL_COMPLETED
ROUTE_DECIDED
NODE_COMPLETED
NODE_FAILED
NODE_SKIPPED
NODE_CANCELLED
WORKFLOW_WAITING
WORKFLOW_COMPLETED
WORKFLOW_FAILED
WORKFLOW_CANCELLED
```

`STREAM_METADATA` 和 SSE heartbeat 是传输控制帧，不是持久化业务事件：

- `STREAM_METADATA` 是每次 SSE 连接的首个可消费事件，包含 `traceId/runId/schemaVersion/replayFromSequence/latestPersistedSequence`，不分配业务 `sequence`，不进入节点 Reducer。
- heartbeat 使用 SSE comment（`: heartbeat`），不携带业务数据、不持久化、不分配 `eventId/sequence`。

### 6.2 公共事件信封

```json
{
  "schemaVersion": 1,
  "eventId": "evt_xxx",
  "eventType": "NODE_OUTPUT_DELTA",
  "sequence": 18,
  "traceId": "trace_xxx",
  "runId": "run_xxx",
  "workflowId": "wf_xxx",
  "workflowVersion": 3,
  "nodeId": "rag_search",
  "nodeExecutionId": "node_exec_xxx",
  "executionIndex": 2,
  "occurredAt": "2026-08-03T10:30:00+08:00",
  "displayScope": "NODE_PANEL",
  "payload": {}
}
```

契约规则：

- `traceId/runId/eventId/sequence/eventType` 为所有事件必填字段。
- 节点相关事件必须携带 `nodeId + nodeExecutionId + executionIndex`。
- `displayScope` 只允许 `NODE_PANEL` 和 `FINAL_ANSWER`。`NODE_OUTPUT_DELTA/COMPLETED` 必须使用 `NODE_PANEL`；`FINAL_ANSWER_DELTA/COMPLETED` 必须使用 `FINAL_ANSWER`。
- `NODE_OUTPUT_DELTA` 只追加 `displayOutput`，不得发送 `internalOutput`。不支持内容流式的节点仍必须产生生命周期事件，并在 `NODE_OUTPUT_COMPLETED` 中携带一次性可展示快照。
- 当前首期最终回答由普通 Agent 节点产生，END 是路由终点而非独立执行节点。真实顺序为 `NODE_STARTED → NODE_OUTPUT_DELTA → NODE_COMPLETED → ROUTE_DECIDED(指向 END) → FINAL_ANSWER_DELTA → FINAL_ANSWER_COMPLETED → WORKFLOW_COMPLETED`；最终回答不复制到节点面板。如后续把 END 升级为可执行节点，必须显式升级事件契约并补齐 END 生命周期测试。
- 同一 Run 的 `sequence` 严格递增；客户端以 `eventId` 幂等去重。
- 服务端在回放历史后才接入实时流，必须消除回放与实时事件之间的窗口丢失。

### 6.3 断线续传

```http
GET /api/v1/intelligent-workflow-runs/{runId}/stream?afterSequence=25
```

项目前端使用可设置 JWT/Header 且可读取响应 Header 的 fetch-SSE 实现，不直接使用受限的原生 `EventSource`。续传只以 `afterSequence` 为真相；如客户端额外发送 `Last-Event-ID`，服务端将其映射到 sequence 并要求两者一致，冲突时返回 409，不猜测客户端意图。

服务端处理顺序：

1. 校验租户、用户和 Run 权限。
2. 返回 `X-Trace-Id`，SSE 首个可消费元数据事件再次携带同一 `traceId`。
3. 补发 `sequence > afterSequence` 的持久化事件。
4. 以同一水位接入实时事件，重复事件由 `eventId` 去重。
5. 已终止 Run 在补发终止事件后正常关闭连接。

持久化事件默认在 Run 终止后保留 30 天，活动/WAITING Run 不清理。超出保留期且无可用快照时，续传接口返回 410 `WORKFLOW_EVENT_HISTORY_EXPIRED`，前端改为加载 Run 最终快照，不显示永久旋转。

## 7. 前端交付

### 7.1 智能工作流配置

- 新建独立 Tab，不改动现有两个 Tab 的交互。
- 节点属性面板配置路由方式、允许目标、表达式、优先级、置信度、默认目标、最大通过次数和超限目标。
- 连线使用固定、成功、异常、AI、回退、人工和默认等可区分样式；不只依赖颜色，同时使用文字/图标。
- 循环边在保存前必须设置次数上限和超限出口。
- 发布时展示后端图编译结果，错误定位到节点或边，不只显示“发布失败”。

### 7.2 聊天节点执行面板

- 面板位于最终聊天回答上方，每个 `nodeExecutionId` 对应一个独立下拉栏。
- 节点状态至少包含：等待、运行、完成、重试/重进、等待人工、跳过、失败和取消。
- 当前执行节点显示运行动画并默认展开；新 delta 追加到对应节点，不串到其他节点。
- 同一 `nodeId` 循环重进时新建栏目，标明“第 N 次”，不覆盖历史。
- 节点完成后显示耗时、Token、工具/RAG 摘要、路由去向和错误摘要。
- 最终节点的 `FINAL_ANSWER_DELTA/COMPLETED` 只进入聊天主回答；节点面板只显示状态和不重复的摘要。
- 页面刷新、网络中断或 SSE 重连后使用 `afterSequence` 恢复，界面结果必须与从未断线时一致。
- 面板和最终回答都显示可复制 `traceId`，错误状态不得只显示模糊文案。
- “所有节点可见”指所有节点都实时展示生命周期；只有支持内容流的 Agent/模型节点持续产生 delta，Condition、Human 和一次性 Tool 在完成时显示结构化快照。
- `executionIndex` 是同一 Run 内某个 `nodeId` 的逻辑进入次数，从 1 开始；技术重试不新建栏目，在同一栏目显示 `attempt N`；循环/回退重进新建 `nodeExecutionId` 和栏目。
- Domain 负责生成字段级白名单 `displayOutput`并脱敏，Trigger 负责大小限制，Web 负责 Markdown/HTML 清洗和 XSS 防护。单个 delta UTF-8 不超过 16 KiB，单个节点累计展示内容不超过 256 KiB；超限时产生明确截断标记并保留完整内部结果的受权查询路径。

### 7.3 前端状态不变量

- 节点状态键：`runId + nodeExecutionId`。
- 事件去重键：`eventId`。
- 最后已处理水位：`runId + sequence`。
- 流式文本只能追加到当前事件指定的 `nodeExecutionId`。
- 事件重放多次的最终 UI 必须与重放一次相同。

## 8. TraceId 全局链路规范

### 8.1 根规则

1. 初次启动工作流时，复用通过校验的入站 `X-Trace-Id`，否则生成新值。
2. 根 `traceId` 与 `runId` 一起入库，不能只放在 MDC、Reactor Context 或线程本地变量。
3. 同一 Run 内所有节点、循环重进、重试、子工作流、模型、RAG、工具、Outbox、Kafka、Worker、SSE 和持久化事件均携带该根 `traceId`。
4. 后台扫描、租约恢复或服务重启继续执行时，必须从 Run/任务账本恢复根 `traceId`，不得生成新的无关链路号。
5. 人工恢复、暂停、取消等后续 HTTP 操作可以有自己的 `operationTraceId`，但工作流日志和事件必须同时保留原根 `traceId`，以保证使用一个根链路号能查到整个 Run。
6. `traceId` 仅用于可观测性，不参与认证、授权、租户判定或业务幂等。

本期 `traceId` 是项目现有的全链路 correlation ID，不假装为 W3C span；入站只接受匹配 `[A-Za-z0-9][A-Za-z0-9._-]{7,63}` 的值，其余值丢弃并生成新 UUID。如后续接入 OpenTelemetry，同一 Run 仍保持该 correlation `traceId`，每个边界另新建 `spanId`/`traceparent`，不用一个 span 伪装整个长运行工作流。

`operationTraceId` 用于暂停、恢复、取消、人工决策等后续请求，必须写入操作审计和关键日志；这些日志同时携带 Run 根 `traceId`。下游不支持 `X-Trace-Id` 时，本方必须记录 `traceId + invocationId + downstreamRequestId`映射，不宣称已实现下游内部链路追踪。

### 8.2 边界传播矩阵

| 边界 | 携带方式 | 消费端行为 | 必测断言 |
|---|---|---|---|
| HTTP 入站 | `X-Trace-Id` + 请求属性 | 校验后复用，无效时重新生成 | Header、JSON/SSE 与入库值相同 |
| HTTP 响应 | `X-Trace-Id`；JSON `traceId` | 前端绑定 Run/消息 | 成功、业务异常、未捕获异常均存在 |
| SSE | Header + 首个 trace 元数据 + 每个事件信封 | 与 `runId` 绑定 | 首个可消费事件就能获取 traceId |
| 有界线程池 | `TraceableThreadPoolExecutor`/TaskDecorator | 任务前恢复，任务后清理 | 线程复用不串链、不串租户 |
| Reactor/RxJava | 订阅前捕获的不变 Trace 快照 | 回调时恢复 | 线程切换后不丢失 |
| Outbox/DB Task | 任务字段 `trace_id` | 领取任务后恢复 | 延迟执行/重启后值不变 |
| Kafka | Header + 命令体兼容字段 | Consumer 处理前恢复，finally 清理 | 主 Topic、Retry、DLT 不断链 |
| 模型 HTTP | `X-Trace-Id` | 下游日志可相关 | 请求、重试和错误日志值一致 |
| ToolGateway/MCP | 调用上下文 + 可传播 Header | 统一网关记录 | 工具选择、调用、返回可关联 |
| RAG | 调用上下文 + `retrievalId` | 检索、Dense/Sparse、融合、重排、引用均记录 | 一个 traceId 能查到全部阶段 |
| 子工作流 | 继承 `traceId`，新建 child `runId` | 保留 `parentRunId` | 父子运行可用同一 traceId 查询 |
| 前端 | Header/JSON/SSE 提取 | 面板显示、复制和错误反馈 | 刷新和续传后不变 |

### 8.3 日志最小字段

所有工作流关键日志必须包含：

```text
traceId operationTraceId tenantId userId workflowId workflowVersion runId
nodeId nodeExecutionId routeDecisionId eventId eventName stage success costMs
```

字段不适用时使用空值，不得为了“补齐”而生成伪标识。人类可读的 `eventName` 和 `message` 必须说清当前阶段、输入摘要、结果摘要和下一步，同时不记录密钥、完整文档、隐藏思维链或未脱敏附件。

## 9. API 交付面

```text
POST   /api/v1/intelligent-workflows
PUT    /api/v1/intelligent-workflows/{workflowId}/draft
POST   /api/v1/intelligent-workflows/{workflowId}/validate
POST   /api/v1/intelligent-workflows/{workflowId}/publish
GET    /api/v1/intelligent-workflows/{workflowId}/versions

POST   /api/v1/intelligent-workflows/{workflowId}/runs
GET    /api/v1/intelligent-workflow-runs/{runId}
GET    /api/v1/intelligent-workflow-runs/{runId}/timeline
GET    /api/v1/intelligent-workflow-runs/{runId}/stream
POST   /api/v1/intelligent-workflow-runs/{runId}/cancel
POST   /api/v1/intelligent-workflow-runs/{runId}/pause
POST   /api/v1/intelligent-workflow-runs/{runId}/resume
POST   /api/v1/intelligent-workflow-runs/{runId}/human-decisions
```

所有接口继续使用项目现有 JWT、租户隔离、统一 `Response<T>`、`X-Trace-Id` 和错误码体系。

## 10. 分阶段交付物

### 阶段 A：定义与编译

- 智能工作流定义、版本和发布状态。
- 节点/边/路由策略/运行预算契约。
- 允许循环的图编译与发布校验。
- 前端新 Tab、画布配置和错误定位。

### 阶段 B：运行与路由闭环

- Run、NodeExecution、RouteDecision、Task/Lease/Outbox 持久化。
- Agent/RAG/Tool 节点执行器适配。
- 规则、节点建议、AI Router、异常与默认路由。
- 有限循环、超限兜底、预算和取消门禁。
- API、定时与事件触发复用同一启动命令。

### 阶段 C：节点流式可视化

- 结构化事件信封、序号、持久化和实时发布。
- SSE 历史回放与实时流无缝衔接。
- 聊天节点执行面板、循环实例、节点输出和最终回答分流。
- Trace 展示、复制和 Grafana 检索入口。

### 阶段 D：恢复、性能和上线

- 断线续传、进程重启、租约过期接管和重复消息幂等。
- 过载背压、内存边界、事件保留/清理策略和指标看板。
- 全量回归、真实 E2E、故障注入、性能基线和发布/回滚记录。

## 11. 上线前必须跑过的测试

每个测试运行都必须保留：代码提交、环境、配置、测试数据、命令、开始/结束时间、原始输出、汇总结果、失败用例和对应 `traceId/runId`。

### 11.1 图编译与领域单元测试

| 用例 | 核心断言 |
|---|---|
| 顺序图 | 编译成功，起点和可达终点正确 |
| 多分支 | 边优先级和默认边确定，没有歧义 |
| 合法有环图 | 强连通分量被识别，有上限与超限出口 |
| 无界循环 | 发布失败并精确指向节点/边 |
| 不可达节点 | 编译失败并阻止发布 |
| 非法目标 | 节点建议/AI 返回未允许节点时被拒绝 |
| 表达式安全 | 反射、文件、网络、任意方法调用全部被拒绝 |
| 跨租户引用 | 其他租户的 Agent/Tool/KB/Workflow 无法保存或发布 |
| 版本不可变 | 草稿修改不影响运行中的已发布版本 |

### 11.2 路由引擎单元测试

- 异常路由优先于普通成功路由。
- 表达式能判定时不调用 AI Router，Token 使用为 0。
- 节点建议在允许集合内且置信度达标时被接受。
- 节点建议低置信、未允许目标、结构错误时进入 AI 或默认兜底。
- AI Router 返回非法 JSON、超时、空结果、低置信或非法目标时安全兜底。
- 多条规则同时命中时按优先级和确定性 tie-breaker 选择。
- 边转移次数达上限时不再进入原目标，严格进入超限节点。
- Token、模型次数、工具次数、节点次数或时间任一预算超限时停止新调用。
- 取消状态在路由裁决前或创建下一任务前出现时，不产生新任务。

### 11.3 节点执行器契约测试

对 Agent、RAG、Tool、Condition、Human、SubWorkflow 每类执行器使用同一组契约测试：

- 输入映射正确，不能读取未授权的节点输出。
- 返回统一 `NodeExecutionResult`，业务输出、显示输出、路由建议、用量和错误分离。
- 超时、取消、重试和幂等语义一致。
- `internalOutput` 不进入 SSE，密钥和隐藏提示词不进入日志。
- 循环/回退重进新建 `nodeExecutionId`，原执行记录不被覆盖；同一执行的技术重试保持 `nodeExecutionId` 并递增 `attempt`。

### 11.4 事件序列与前端 Reducer 单元测试

- 普通节点标准序列：`WORKFLOW_STARTED → NODE_SCHEDULED → NODE_STARTED → NODE_OUTPUT_DELTA* → NODE_OUTPUT_COMPLETED → ROUTE_DECIDED → NODE_COMPLETED`。
- 最终回答节点序列：`NODE_STARTED → FINAL_ANSWER_DELTA* → FINAL_ANSWER_COMPLETED → ROUTE_DECIDED(END) → NODE_COMPLETED → NODE_SCHEDULED(END) → NODE_STARTED(END) → NODE_COMPLETED(END) → WORKFLOW_COMPLETED`，主回答不进节点面板。
- 空 delta、Unicode、Markdown、超长单词、代码块和不完整 UTF-8 分片不得破坏渲染。
- 重复 `eventId` 被幂等忽略。
- 事件乱序时按 `sequence` 缓冲/重排；缺口超时触发历史补拉，不静默跳过。
- 循环中相同 `nodeId` 的两个 `nodeExecutionId` 渲染两个独立栏目。
- `NODE_FAILED/CANCELLED/SKIPPED` 停止动画并显示对应状态。
- `FINAL_ANSWER_DELTA/COMPLETED` 只进聊天主回答，不与节点面板重复。
- 完整事件重放一次和重放两次得到完全相同的 UI 状态。
- 每个节点状态、Trace 复制、键盘展开/收起和屏幕阅读器语义通过前端测试。

### 11.5 Repository、事务和租约集成测试

- 启动 Run 时，Run、首任务和 Outbox 原子落库。
- 节点完成时，NodeExecution、RouteDecision、下一任务和 Outbox 原子落库。
- 在任意一步注入 SQL 异常后整个事务回滚，不留半状态。
- 两个 Worker 竞争同一任务时只有一个获得租约。
- Worker 崩溃后租约过期，另一 Worker 接管；已入账成功的外部副作用不重复，未知结果按第 5.4 节的幂等查询或人工对账处理。
- 重复 Kafka/Outbox 消息只产生一次逻辑节点执行。
- 事件 `sequence` 在并发发布时仍对同一 Run 唯一且严格递增。
- 事件保留/归档后，在承诺的续传窗口内仍能完整回放。

### 11.6 TraceId 专项集成测试

下列用例要使用可控的入站 `X-Trace-Id`，然后逐层断言值完全一致：

1. HTTP Header、JSON 响应、SSE 首个 trace 元数据事件和后续每个节点事件。
2. Run、NodeExecution、RouteDecision、Task、Outbox 和 Event 数据库记录。
3. coordinator/node 线程池任务、Reactor/RxJava 线程切换和延迟回调。
4. Agent 模型请求、AI Router 请求、RAG 检索全阶段和 ToolGateway/MCP 调用。
5. Kafka 主 Topic、重试 Topic、DLT 和消费者恢复。
6. 循环重进、节点重试、暂停后恢复、进程重启后接管。
7. 子工作流的 child `runId` 和 `parentRunId`。
8. 前端首次连接、断线重连、页面刷新和历史回放。

通过标准：

- 测试链路中 `traceId` 缺失数为 **0**。
- 同一 Run 内出现无关新 `traceId` 的次数为 **0**。
- 不同 Run 在线程复用后串用 `traceId/tenantId/userId` 的次数为 **0**。
- 使用根 `traceId` 查询 Grafana/Loki，能看到从入口到终止的每个预期阶段；测试报告保留查询命令和事件对照表。

### 11.7 真实端到端测试

至少保留以下固定工作流 fixture，每个 fixture 保存工作流 JSON、输入、预期路径、真实事件流、数据库快照、页面截图和 Grafana Trace 导出：

| 场景 | 预期路径与断言 |
|---|---|
| 规则直达 | Agent A → 表达式路由 → Agent B → 结束；不调用 AI Router |
| Agent 自主路由 | Agent 返回合法建议，引擎校验后进入目标节点 |
| AI Router 兜底 | 规则和建议无法判定，额外调用一次 Router 并记录 Token |
| 审核回退 | 生成 → 审核拒绝 → 第二次生成 → 审核通过；两次生成各有独立栏目 |
| 循环超限 | 连续拒绝到上限后进入人工/失败兜底，不再调用生成 Agent |
| RAG 路由 | 低召回 → 查询改写 → 再检索 → 回答；显示检索/重排摘要但不暴露未授权原文 |
| Tool 异常路由 | 参数错误 → 参数修复 Agent → 重试 Tool；只产生预期外部副作用 |
| 人工节点 | 运行进入 WAITING，提交决策后从原 traceId 恢复 |
| 中途取消 | 流式输出中取消，已显示内容保留，不再调用新模型/工具/RAG/下一节点 |
| 网络中断 | 中断 SSE 后工作流继续，重连无缺口、无重复 UI |
| 进程重启 | 在节点之间和租约执行中分别重启，能接管并保留 traceId |
| 跨租户负测试 | 无法查看工作流、Run、节点输出、SSE 或事件回放 |

最终 E2E 必须在真实浏览器中验证旋转、展开、内容追加、循环新栏目、最终回答不重复、Trace 复制和刷新恢复，不能只调接口。

路由路径、循环上限、取消和恢复使用 stub 下游作为硬门禁。真实模型 E2E 固定模型代码、版本、温度和 seed（供应商支持时），断言 JSON Schema、允许目标、权限、终态和 Trace 等不变量，不对自然语言字面完全一致做脆弱断言。真实下游瞬态故障只能按预先声明的有界重试策略重试，测试报告必须同时保留首次失败，不得用重跑后成功覆盖原始失败。

### 11.8 故障注入与恢复测试

- 模型超时、500、429、返回非法结构和流中断。
- RAG 空结果、Embedding/Rerank 瞬态错误和 Qdrant 不可达。
- Tool 超时、非幂等副作用、参数错误和权限拒绝。
- MySQL 写入失败、死锁、连接短断；Kafka 不可达、重复、延迟和乱序。
- Worker 在外部调用前、调用后未入库、路由后未派发三个窗口分别崩溃。
- SSE 连接频繁断开/重连，浏览器切换网络。

通过条件：运行最终进入明确终态或可恢复 WAITING；无无界重试；已入账副作用不重复，不可判定的非幂等调用进入人工对账而不是自动重试；无 Trace 断链；前端能显示人类可理解的错误阶段和链路号。

### 11.9 性能、背压与稳定性测试

不预先编造绝对性能数字。正式验收先固定机器、JVM、线程池、模型、工作流 fixture 和并发档位，然后留存原始数据。至少覆盖：

- 单 Run 长流：大量 delta 事件下的事件持久化、SSE 传输、前端渲染和内存增长。
- 正常容量：按当前 coordinator/node 池额定并发运行，测量节点排队、首事件、节点耗时和整体耗时。
- 2 倍与 5 倍过载：验证明确背压/拒绝，不转移到 HTTP 线程，不使用无界队列或新建无界线程。
- 慢消费者：前端或网络读取变慢时，不阻塞工作流核心执行线程。
- 60 分钟稳定性：持续创建、取消、重连和循环，观察堆、线程、连接池、事件积压和租约。
- 对比改造前“只输出最终节点”与改造后的 CPU、内存、DB 写入、网络、前端渲染和 Token 开销。

必须产出：各阶段 mean/p50/p95/p99/max，吞吐、错误率、拒绝率、重试率、队列水位、堆/线程峰值和瓶颈定位。使用本地 stub 与固定 fixture 的可判定门禁为：

- 额定并发下，持久化事件到 SSE flush 的 p95 不超过 250 ms，p99 不超过 500 ms。
- 新 SSE 连接从鉴权成功到 `STREAM_METADATA` 的 p95 不超过 500 ms。
- 改造后同 fixture 的 p95 整体运行时间相对改造前 stub 基线回归不超过 15%，峰值堆使用回归不超过 20%。
- 前端在 1000 个 delta、50 个节点栏目的 fixture 下无长任务持续超过 200 ms，事件处理 p95 不超过 50 ms。
- 2 倍/5 倍过载可以产生明确拒绝或排队，但线程数不超过配置上限、队列不超过有界容量、事件丢失数为 0。
- 60 分钟稳定性期间无 OOM、无线程持续增长、无租约永久占用、无 Trace 断链。

真实模型/RAG/Tool 测试另行报告下游延迟和抖动，不用其随机性替代上述 stub 硬门禁。

### 11.10 安全、租户与数据暴露测试

- 工作流定义、发布版本、Run、节点输出、事件历史和 SSE 都做租户/用户鉴权。
- 用户无权的 Agent、Tool、KB 和子工作流不得被编译、执行或通过 AI 路由命中。
- 伪造 `workflowId/runId/nodeExecutionId/afterSequence/Last-Event-ID` 无法读取他人数据。
- 表达式注入、提示词诱导越界路由、Markdown/XSS、超大 delta、事件伪造和重放攻击均有负测试。
- `internalOutput`、系统提示词、密钥、完整未授权 RAG 原文和内部堆栈不得进入 SSE、前端状态或普通日志。
- `traceId` 格式、长度和字符集受限，不能用于注入日志，也不能代替权限验证。

### 11.11 兼容性与回归测试

- 现有系统工作流创建、编辑、发布、运行和最终输出不受影响。
- 现有 RAG 工作流的绑定、检索、重排、引用、Token 预算和取消不受影响。
- 现有会话、消息、附件、历史加载、分享、取消、上下文压缩和 Token 统计回归通过。
- 智能工作流使用独立 `workflow-event-v1` 协议和新端点；现有工作流原文本流端点保持不变。后续让现有工作流接入新事件时必须显式协议升级，不向旧客户端偷渡新事件。
- 前端类型检查、单元测试和生产构建通过；Java 17 相关 reactor 模块 clean test/package 通过。

## 12. 测试执行顺序与上线门禁

### 12.1 建议执行顺序

1. Java 领域单元测试和安全表达式测试。
2. 前端 Reducer、组件、无障碍和类型测试。
3. API/SSE 契约测试。
4. MySQL/Outbox/Kafka/租约集成测试。
5. TraceId 专项集成测试。
6. 使用 stub 模型/RAG/Tool 的确定性 E2E。
7. 使用真实模型、RAG 和工具的黑盒 E2E。
8. 故障注入、断线恢复和进程重启。
9. 容量、过载、慢消费者和稳定性测试。
10. 全量旧功能回归、敏感信息扫描和最终构建。

### 12.2 上线硬门禁

- 所有必测用例 0 failure、0 error；跳过项必须逐项说明原因和风险，不得以“环境问题”笼统带过。
- Trace 完整性三项指标均为 0：缺失数、同 Run 无关新 Trace 数、线程复用串链数。
- 以 `cancel_requested_at` 事务提交时刻为界，其后成功登记的模型、Tool、RAG invocation 和下一节点任务数为 0；提交前已登记的在途调用单独标记为已取消、不可取消已记账或待对账，不驱动新路由。
- 每个 Run 最终都是明确终态或可恢复 WAITING，不存在无主租约、无界循环或永久旋转 UI。
- 断线前后的事件集合、顺序、节点栏目和最终回答与从未断线的对照运行一致。
- 无跨租户读取/调度，无未授权 Agent/Tool/KB 访问，无敏感内容进入 SSE 或日志。
- 无 OOM、无无界线程/队列/重试，过载时有可观测的背压或拒绝。
- 真实浏览器 E2E、真实下游 E2E 和 Grafana `traceId` 反查均完成，原始证据已归档。

## 13. 2026-08-04 实际交付证据与剩余门禁

本节记录真实执行结果；第 11、12 节仍是最终上线前必须跑完的完整测试清单，不能用本节的单路径成功替代。

### 13.1 已通过

| 层级 | 实际结果 |
|---|---|
| Java 定向回归 | 最终收口组 27 tests，0 failure、0 error、0 skipped；覆盖 TraceContext、中文结构化日志、智能图编译、显式 END、运行时取消收口、事件仓储/终态关闭、SQL/Mapper 合同、控制接口双 Trace 响应契约 |
| 前端单元与构建 | reducer/SSE parser/历史恢复目标提取 4 tests 全通过；`vue-tsc --noEmit` 和 Vite production build 通过，1924 modules transformed |
| 真实模型 API/SSE | 单节点智能工作流真实调用模型并完成；事件 1..8 连续，节点输出与最终回答为独立事件，DEFAULT 正确路由到 END |
| MySQL 账本 | Run/Node/Route/Invocation/Event/Message=`1/1/1/1/8/2`；六类记录各自只有一个根 `traceId`，且全部等于 Run 根 Trace |
| 断线重放 | `afterSequence=4` 只返回 5..8，类型、顺序和根 Trace 与首次流一致 |
| 真实浏览器 | 登录、选择智能工作流、发送、主回答、展开节点面板均通过；面板可见节点状态、输出、路由、Token 和完整 Trace；控制台错误 0 |
| Grafana/Loki | 用一个根 Trace 查询到 15 条完整中文链路日志；失败 0、降级 0、取消 0、未闭合阶段 0 |
| 完成态 SSE | 已终止 Run 的历史回放发送终态后由服务端主动关闭；真实 `curl` 无需等待客户端超时，单元测试同时覆盖历史终态和实时终态 |
| 刷新恢复 | 浏览器硬刷新后重新选择“数据库工作流”和原会话，最终消息与节点栏目均由数据库历史恢复；节点显示状态、输出、路由、Token 和原根 Trace，控制台错误 0 |
| 真实立即取消 | ChatRun/智能 Run/运行中节点=`cancelled/CANCELLED/CANCELLED`，节点错误码 `RUN_CANCELLED`；取消后 Invocation/Route=`0/0`，事件为连续 1..3 且同一根 Trace |
| 取消态 SSE/Loki | 取消历史 SSE 0.27 秒内返回并关闭；Loki 根 Trace 17 条业务日志、1 个 traceId、1 个 runId，含中文 `chat_run_cancelled` 和 `workflow_node_cancelled`，未闭合阶段 0 |

真实通过样本：

- API Run：`run_67471a3d-570c-49f2-ad68-b479d15daed9`
- API 根 Trace：`419639a5-5dc6-4925-9b89-c69aeff29722`
- 浏览器根 Trace：`5e229942-213e-4088-80cd-ca8f8e365b1b`
- 浏览器面板：`审核节点 / 第 1 次 / 已完成 / 审核通过 / DEFAULT → END / 163 Token`
- 刷新恢复 Run：`run_39933228-dd7f-4bb4-8807-29eba6ff518c`
- 刷新恢复根 Trace：`3e31430f-9ef4-4da1-8701-eff12788c9a8`
- 最终取消 Run：`run_9dcbfc73-b65e-46f4-80d8-da8e3f2ebe4e`
- 最终取消根 Trace：`787200c2-8f6a-47cd-a7d1-5392ab974359`

### 13.2 本轮发现并闭环的缺陷

真实首轮 Run 在模型成功输出后以 `WORKFLOW_ROUTE_NOT_FOUND` 失败。原因是编译器把目标不是 LLM 节点的边全部过滤，合法 `END` 边也被误删。修复后仅智能工作流保留显式 END 边，并新增单节点 `DEFAULT → END` 回归测试；重跑真实链路进入 `COMPLETED`。

阶段 6 的前三个取消样本又暴露了第二个问题：会话 Run 已取消，但异步执行线程不保证继续运行到节点/智能 Run 收口，导致数据库残留 `RUNNING`，Loki 也只有开始态。修复方式是在取消 HTTP 事务完成后同步协调智能运行和所有 `RUNNING` 节点，再发布唯一 `WORKFLOW_CANCELLED` 终态；异步执行线程仍保留兜底收口。最终样本证明数据库、SSE 和 Loki 三方一致，失败诊断样本没有被删除或覆盖。

补充双 Trace 响应时，真实样本 `run_e4e4cc8b-5619-4cd5-850b-92fe859ae2e0` 暴露取消协调与后台节点推进竞争 `revision`，接口返回 `WORKFLOW_RUN_CONCURRENT_MODIFICATION`。根因是同步协调依赖乐观锁 revision，而事务内重复查询在 MySQL `REPEATABLE READ` 下不能可靠获得竞争方的新 revision。最终修复改为一条带“仅非终态”条件的原子 `UPDATE` 作为取消线性化点，不再依赖旧 revision；其他后台更新因旧 revision 自动失败，终态重复取消影响行数为 0。重跑真实样本后接口、数据库、SSE 和 Loki 全部通过。

### 13.3 尚未通过，禁止据此直接生产上线

- 公网 MySQL TLS 稳定性：`103.205.240.84:3306` TCP 可达，但真实连接仍随机出现 `wrong version number` 和 `read timed out`。本轮业务 E2E 使用同一服务器的 SSH 转发隔离验证；公网直连稳定性仍未通过。
- 真实立即取消已验证取消后模型 Invocation 和 Route 均为 0；仍需补真实“调用已登记且正在途”的模型、Tool、RAG 取消分类，确认不可取消副作用只记账、不驱动后续路由。
- 重启接管与分布式实时流：任务租约、Outbox、Worker 接管、多实例实时订阅尚未形成完整生产 E2E。
- 首期运行时当前只闭环单活动路径 LLM 节点；Human、SubWorkflow、并行 fan-out/Join 不应声明已交付。
- 前端刷新后会从数据库恢复消息和智能节点事件，但当前数据源类型/工作流选择未持久化；用户需要重新选择“数据库工作流”和原会话后才会触发节点面板重放。这是可用边界，不应描述成零操作恢复。
- 仍需跑第 11.7 节其余规则、自主路由、AI Router、回退循环、RAG、Tool、人工、取消、跨租户 fixture，以及第 11.8–11.10 节故障注入、容量、稳定性和安全测试。

### 13.4 当前结论

“智能工作流单活动路径 + 每节点路由 + 聊天节点事件面板 + 根 Trace 全链路”核心纵向切片已真实闭环；它可以进入下一轮扩展与回归测试，但在 13.3 的生产门禁完成前，结论是“功能验收通过，生产上线门禁未全部通过”。

### 13.5 本轮 Trace 不变量结论

- Run 根 Trace 在 `chat_run`、`intelligent_workflow_run`、`workflow_node_execution`、`workflow_route_decision`、`workflow_invocation`、`workflow_run_event`、节点事件和结构化日志中保持不变。
- 启动响应数据体返回根 `traceId` 与启动 `operationTraceId`；取消/引导响应数据体同样返回根 `traceId` 与本次控制请求 `operationTraceId`。外层统一响应 Trace 仍代表当前 HTTP 操作，不能覆盖根 Trace。
- SSE 的 `STREAM_METADATA` 和每条业务事件都携带根 Trace；前端发现 Run、metadata、事件任一换号时拒绝合并，避免把别的链路串入当前面板。
- 当前真实完成样本与取消样本均满足：Trace 缺失数 0、同 Run 换号数 0、Loki 查询根 Trace 只出现一个 traceId。线程复用、Kafka Retry/DLT、进程重启接管和子工作流传播仍须按第 11.6 节补测后，才能对生产全局链路作无条件承诺。

## 14. 交付清单

### 14.1 代码与数据

- 前端智能工作流 Tab、画布、路由配置和节点执行面板。
- 后端定义、编译、版本、运行、路由、调度、事件、取消和 Trace 闭环。
- 数据库迁移、索引、回滚脚本和数据保留策略。
- 新旧 SSE/API 契约及兼容说明。

### 14.2 测试与证据

- 自动化测试源码、fixture、测试数据和真实浏览器脚本。
- 所有测试命令、环境清单和原始输出。
- 每个 E2E 的工作流 JSON、期望路径、实际路径、事件流、截图、数据库快照和 `traceId`。
- 性能原始数据、统计汇总、瓶颈、对比基线和优化建议。
- 失败用例的输入、工作流版本、节点输出、路由判断、错误阶段、日志和因果分析。

### 14.3 运维与上线

- 配置项、默认值、环境差异和容量建议。
- Grafana 看板：Run 数、节点数、路由类型、循环次数、Token、事件延迟、队列、拒绝、取消和错误。
- 以 `traceId/runId/nodeExecutionId` 检索的日志查询示例。
- 发布前检查表、数据库迁移顺序、灰度方案、回滚方案和故障手册。

## 15. 完成定义

只有同时满足以下条件，才能声称本能力完成：

1. 用户能在新 Tab 中完成节点级智能路由配置、校验、发布和调试。
2. 后端能独立调度已发布版本，每个节点都按统一路由阶段裁决，循环和失败有界。
3. 聊天页实时展示全部节点执行实例，循环不覆盖，最终回答不重复，断线可完整恢复。
4. 从入口到异步恢复和前端的 `traceId` 缺失、换号和串链均为 0，且 Grafana 反查能还原整个 Run。
5. 第 11 章全部必测项和第 12 章硬门禁通过，原始证据可复查，无编造数据。
