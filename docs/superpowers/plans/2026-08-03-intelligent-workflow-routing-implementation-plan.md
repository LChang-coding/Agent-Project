# 智能工作流路由与节点流式输出实施计划

## 目标

以 `docs/workflow/intelligent-workflow-routing-and-node-streaming-delivery.md` 为唯一验收基线，在现有系统工作流和 RAG 工作流之上闭环交付：

1. 独立的智能工作流定义、发布、版本、后端调度、节点级智能路由、有限循环、预算、取消和恢复。
2. 工作流节点生命周期与显示内容的结构化流式事件，包含持久化、序号、去重、断线续传和最终回答分流。
3. 前端智能工作流 Tab、路由配置交互和聊天节点执行下拉面板。
4. `traceId` 从 HTTP 入口贯穿 Run、NodeExecution、RouteDecision、线程池、Outbox/Kafka、模型、RAG、Tool、SSE、重试、循环、重启恢复和前端展示。

## 强制约束

- 不改变现有系统工作流和 RAG 工作流语义，新功能通过独立类型和协议接入。
- 严格遵循 `trigger -> domain <- infrastructure`，`app` 只负责装配，不在 Controller/DAO 中堆积领域逻辑。
- 复用现有 Agent、RAG、ToolGateway、Context Manager、Run Control、TraceContext 和 coordinator/node 有界线程池。
- 不上传本地项目，不提交日志、对象存储、评测产物或无关改动。
- 每个实施阶段先在本文档写入当次计划，完成后追加真实改动、测试命令和结果；重大闭环使用中文本地提交。

## 执行阶段

### 阶段 0：现状审计与差距映射

1. 盘点现有工作流定义、DAG 执行器、Agent/RAG/Tool 调用、Run Control、SSE 事件、Trace 传播、数据库表和前端页面。
2. 建立“交付文档要求 -> 现有复用点 -> 缺口 -> 目标文件/表/接口 -> 验收证据”矩阵。
3. 确定数据库迁移方式、新旧 SSE 兼容边界和首个可运行纵向切片。

### 阶段 1：定义、编译与持久化基座

1. 新增智能工作流、版本、节点、边、路由策略、预算和编译结果领域模型。
2. 实现单活动路径图编译器：可达性、强连通分量、循环上限、表达式安全、资源/租户权限和版本不可变。
3. 新增 Run、NodeExecution、RouteDecision、Event、Invocation、Task/Lease/Outbox 持久化及必要索引和回滚脚本。
4. 先以单元和 Repository 集成测试闭环。

### 阶段 2：路由运行时与 Trace 闭环

1. 实现统一 NodeExecutionResult、RouteSuggestion、RouteDecision 和节点执行器注册表。
2. 实现固定顺序的路由引擎：取消、预算、异常、固定/成功、表达式、节点建议、AI Router、默认兜底。
3. 实现有限循环、END 终态、人工等待、预算、暂停/恢复、取消线性化和 invocation ledger。
4. 适配现有 Agent/RAG/Tool 能力，确保调用前重新校验取消和权限。
5. 在 HTTP、数据库、线程池、Outbox/Kafka、模型、RAG、Tool、重试、恢复和子工作流中持久化并恢复同一 Run 根 `traceId`。

### 阶段 3：节点流式事件与续传

1. 实现 `workflow-event-v1` 持久化业务事件和传输控制帧，包含同 Run 严格递增 sequence 和 eventId 幂等。
2. 实现节点生命周期、节点 display delta/snapshot、最终回答 delta/completed、路由决策和终态事件。
3. 实现 fetch-SSE 历史回放与实时流水位衔接、`afterSequence`、410 过期和快照回退。
4. 完成 API/SSE 契约、乱序/重放/断线与 Trace 专项集成测试。

### 阶段 4：前端配置与聊天可视化

1. 新增智能工作流 Tab，复用现有画布和资源选择能力，补充节点路由模式、允许目标、表达式、优先级、循环上限和兜底配置。
2. 实现类型安全的工作流事件 Reducer，以 `runId + nodeExecutionId` 建模，以 `eventId/sequence` 去重与续传。
3. 在聊天最终回答上方实现节点下拉面板，支持运行动画、流式内容、循环新栏目、路由去向、Token、错误、Trace 复制和无障碍。
4. 保证 `FINAL_ANSWER_*` 只进入主回答，节点面板不重复；完成前端单元、类型、构建和真实浏览器测试。

### 阶段 5：全量验收与交付报告

1. 按交付文档第 11、12 章运行确定性 E2E、真实下游 E2E、取消、循环、断线、重启、故障注入、安全租户、性能背压、60 分钟稳定性和旧功能回归。
2. 对每个显式要求列出证据，不用窄测试代替广泛完成声明。
3. 将真实命令、原始结果、失败用例、性能数据、瓶颈、Grafana Trace 反查和已知边界追加到交付文档及本计划。
4. 只在全部硬门禁有权威证据通过后才宣布完成。

## 验收门禁

- 交付文档第 12.2 节的全部硬门禁通过。
- Trace 缺失数、同 Run 无关换号数、线程复用串链/串租户数均为 0，一个根 `traceId` 能在 Grafana/Loki 还原整个 Run。
- 取消事务提交后新 invocation 和下一节点任务登记数为 0。
- 断线重连后事件集合、顺序、节点栏目和最终回答与未断线对照运行一致。
- 无跨租户读取/调度，无隐藏思维链、系统提示词、密钥或未授权 RAG 原文进入 SSE/普通日志。
- 无 OOM、无无界线程/队列/重试，性能和稳定性达到交付文档量化标准。

## 回滚策略

- 数据库使用增量新表/新字段，优先保持旧流程可读；迁移脚本提供可审计的回滚路径，不在回滚中删除业务数据。
- 智能工作流使用独立类型、接口和协议，可通过功能开关停止新 Run，不影响现有工作流。
- 流式面板失效时仍可回退到 Run 历史快照和最终回答，不丢失后端运行记录。

## 实际执行记录

> 每个阶段完成后追加真实改动、测试和提交信息。

### 2026-08-03 阶段 0 执行计划

1. 只读检查 `codex.md`、现有工作流 Graph/Compiler/ChatService、RunControl、SSE Controller、MyBatis 与 Vue 页面。
2. 先确认用户已有未提交改动，任何后续改造不得覆盖 `ChatService`、`RunControlService` 的现存变化。
3. 用交付基线逐项建立复用点与缺口，确定首个纵向切片和新旧协议兼容边界。

### 2026-08-03 阶段 0 审计结果

| 交付要求 | 现有复用点 | 主要缺口 | 目标改造位置 | 首轮验收证据 |
|---|---|---|---|---|
| 智能工作流独立配置 | 工作流版本以 `graph_json` 整体持久化，天然支持向后兼容扩展 | 无工作流类型、节点路由模式、允许目标、边条件、优先级和预算 | `WorkflowGraphEntity/DTO`、前端 `WorkflowGraph` 类型与画布 | JSON 新旧图反序列化、保存/回读单测 |
| 任意节点动态路由 | 每个 LLM 节点已有独立 Runner，节点输出可作为路由事实 | 当前 `executeDagPlan` 是 Kahn 拓扑层并行，只能静态推进；非自循环环路被拒绝 | 新增纯领域 `IntelligentWorkflowRouter` 与单活动路径执行器；旧 DAG 保持不变 | 路由优先级、默认分支、有限回路、非法目标单测 |
| 调用前取消与预算 | `RunControlService.requireExecutable` 已在节点和模型调用前复核；Run 持久化根 trace | 尚无每次节点/路由/外部调用账本，取消与新 invocation 注册未形成同事务线性化 | 新增运行事件/节点执行/路由决策/调用账本仓储，接入调用前门禁 | 取消后下一节点与 invocation 均为 0 的并发测试 |
| 节点级流式输出 | 旧 SSE 已先发 `trace/session/run`，最终文本用 `message` 事件 | 节点生命周期与中间内容不对外；无 sequence、eventId、回放和水位衔接 | `workflow-event-v1` 领域事件、持久化账本、fetch-SSE 回放接口 | 乱序/重复/断线续传后状态一致测试 |
| 最终回答与节点面板分流 | 现有聊天 Store 已绑定 `traceId`，消息记录可展示链路号 | 只有最终回答文本，节点输出与最终回答会混淆 | 前端 typed reducer；`FINAL_ANSWER_*` 只写主回答，`NODE_*` 只写节点执行面板 | reducer 单测与浏览器断线恢复 E2E |
| 全局 traceId | `TraceIdFilter`、响应体/响应头、`TraceContext.wrap`、Run `trace_id`、RAG/Tool state 已存在 | 工作流内部事件、节点执行、路由决定、恢复和回放记录没有强制保存 root trace | 所有新增表和事件必填 `trace_id`；异步入口从 Run 恢复上下文 | 同 Run 全表唯一 trace、线程复用不串链、Loki 反查证据 |

首个纵向切片确定为：智能图 JSON 扩展与编译校验 → 确定性路由引擎 → 节点事件账本 → 新 SSE 事件输出 → Vue reducer/节点面板。旧 `message` 事件和旧静态 DAG 执行路径保留，只有 `workflowKind=INTELLIGENT` 的发布版本进入新运行时。

已确认当前工作区存在用户/既有未提交日志、RAG 文档与 `ChatService`、`RunControlService` 改动；实施时只做最小上下文补丁并逐文件复核 diff，不纳入无关提交。

### 2026-08-03 阶段 1 本次执行计划

1. 扩展 Graph/DTO/前端类型，定义智能工作流路由合同并保持旧 JSON 默认行为。
2. 新增无基础设施依赖的路由值对象和确定性路由引擎，固定执行取消、预算、异常、固定、表达式、节点建议、AI 路由和默认兜底顺序。
3. 编译时校验路由目标、默认出口、优先级、表达式白名单和有限循环；旧静态 DAG 编译规则保持不变。
4. 先运行领域编译/路由单测，再进入数据库账本改造。

### 2026-08-03 阶段 1 第一闭环结果

- 已在 Graph、API DTO、编译计划和前端类型中加入 `workflowKind`、总步数/Token 预算、节点策略/允许目标/默认目标/访问上限，以及边的策略类型、routeKey、受限表达式和优先级。旧 JSON 缺省仍编译为 `STATIC`，旧静态 DAG 校验与执行语义未改变。
- 新增纯领域 `IntelligentWorkflowRouter`。取消与预算先于全部业务策略；业务固定顺序为 `FAILURE → FIXED → SUCCESS → EXPRESSION → NODE_SUGGESTION → AI_ROUTER → DEFAULT`，节点策略数组不可改变平台优先级。
- 表达式只接受 `status|output|suggestion == '常量'` 和 `output|suggestion contains '常量'`，不执行 SpEL、脚本或反射表达式。
- 编译器仅对 `INTELLIGENT` 放开普通回路，并强制有效根节点、`maxSteps=1..200`、节点 `maxVisits=1..50`、有效目标、每个有出边节点的 DEFAULT 出口和表达式白名单；静态工作流仍拒绝非自循环环。
- 真实测试命令：`mvn -pl ai-agent-scaffold-app -am -DskipTests=false -Dtest=IntelligentWorkflowRouterTest,WorkflowDagCompilerTest -Dsurefire.failIfNoSpecifiedTests=false test`。
- 真实结果：7 tests，0 failures，0 errors，0 skipped；六个 Maven reactor 模块均 `BUILD SUCCESS`，总耗时 8.100 秒。
- 审计修正：智能工作流运行端点、持久化状态机与 SSE 必须独立于旧 `chat_stream`；后续不把结构化事件塞进旧 `message` 事件，也不让 SSE 断开取消后台 Run。

### 2026-08-03 阶段 1 持久化子阶段执行计划

1. 保留 `chat_run` 作为根运行、取消和根 `traceId` 真相，新增智能运行状态、节点执行、路由决定、事件、任务、调用账本和 Outbox 增量表。
2. 新增领域仓储端口和基础设施实现；事件 sequence 通过运行状态行锁分配，禁止 `MAX(sequence)+1`。
3. 所有表必存根 `traceId`；任务、调用和路由写入必须携带 tenant/run 作用域。
4. 增加 Mapper 加载、SQL 合同和领域事件序列测试；不宣称 XML 测试等价于真实 MySQL 事务测试。

### 2026-08-04 持久化、运行时与 Trace 第一闭环结果

- 新增非破坏性 MySQL 增量/回滚脚本，定义智能运行状态、节点执行、路由裁决、可续传事件、执行任务、调用账本和 Outbox 七类持久化对象。`chat_run` 继续是取消、会话归属和根 `traceId` 的权威真相；回滚只暂停任务，不删除表和历史证据。
- `workflow_run_event` 使用 `(tenant_id, run_id, sequence)` 唯一键；事件仓储先 `SELECT ... FOR UPDATE` 锁 `intelligent_workflow_run`，再以 `next_sequence` 分配序号并同事务推进，明确禁止 `MAX(sequence)+1`。
- 新增独立启动端点 `POST /api/v1/intelligent-workflow-runs` 与续传端点 `GET /api/v1/intelligent-workflow-runs/{runId}/stream?afterSequence=`。`STREAM_METADATA` 是不占序号的传输帧，持久业务事件使用 `workflow-event-v1`；SSE 断开只释放订阅，不取消后台 Run。
- 新增单活动路径运行时：事务提交后在现有有界 coordinator 执行；每个节点都能根据表达式、节点建议 `[route:key]`、AI 路由键和 DEFAULT 选择下一跳；循环按 `maxVisits/maxSteps` 收口；最终回答仍复用既有 RAG 引用校验和消息终态事务。
- 新增调用线性化门禁：模型网络调用前先锁同一 `chat_run`，确认状态可执行且未出现 `cancel_requested_at`，再写 `workflow_invocation`。取消已经提交时登记影响行数为 0 且不执行模型；同一次执行以幂等键拒绝重复调用。
- 新增事件根 Trace 硬校验：扩展 Run、Event、Invocation、SSE metadata 和每个业务事件都携带同一个根 `traceId`；续传 HTTP 自身使用独立 `operationTraceId`，不会替换 Run 根链路。
- 真实测试过程留痕：首次 Mapper 合同测试因测试源码把 XML 注释中的 `MAX(sequence)+1` 也当成 SQL 而出现 1 个断言失败；已把断言收紧为禁止 `SELECT MAX(sequence)` 后重跑通过。首次运行时测试在本机 Java 25 下因 Byte Buddy 只声明支持到 Java 24 而无法 mock `RunControlService`；按项目既有做法增加 `-Dnet.bytebuddy.experimental=true` 后重跑通过，生产目标仍是 Java 17。
- 最终定向命令：`mvn -Dnet.bytebuddy.experimental=true -pl ai-agent-scaffold-app -am -DskipTests=false -Dtest=IntelligentWorkflowRuntimeServiceTest,WorkflowInvocationGuardServiceTest,IntelligentWorkflowRouterTest,WorkflowEventRepositoryTest,WorkflowEventMapperContractTest,MyBatisMapperLoadTest -Dsurefire.failIfNoSpecifiedTests=false test`。
- 最终真实结果：11 tests，0 failures，0 errors，0 skipped；六个 reactor 模块全部成功，总耗时 7.948 秒。
- 当前证据边界：上述测试证明编译、领域路由、调用门禁、Mapper 加载和 SQL 合同；尚不能代替真实 MySQL 8 的事务/锁竞争、重启接管和浏览器 E2E，后续阶段必须补齐后才能宣布最终交付。

### 2026-08-04 阶段 3/4 本次执行计划

1. 在前端新增智能工作流 Tab，保持旧标准工作流配置语义不变；智能模式显示 Token 成本提示、全局预算和节点路由配置。
2. 新增 `workflow-event-v1` 类型、纯 reducer 和 fetch-SSE 客户端，严格校验 runId/traceId/schemaVersion，按 eventId/sequence 去重并处理断线续传。
3. 在聊天主回答上方新增节点执行下拉面板；节点事件只进入面板，`FINAL_ANSWER_*` 只进入主回答。
4. 增加前端单元测试基础和 reducer/SSE parser 测试，再执行类型检查、构建与浏览器验收。
