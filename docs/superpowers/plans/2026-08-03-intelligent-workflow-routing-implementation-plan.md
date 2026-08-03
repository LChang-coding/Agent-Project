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

### 2026-08-04 阶段 3/4 当前实施与测试记录

- 前端新增 `workflow-event-v1` 强类型、纯 reducer、SSE parser 和 fetch-SSE 客户端。启动响应明确区分 Run 根 `traceId` 与 HTTP `operationTraceId`；SSE 响应头、`STREAM_METADATA`、Run、sequence 和每条事件都会交叉校验，换号立即拒绝。
- 聊天 Store 已使用独立智能工作流启动端点；断线后从最后 `sequence` 最多重连 3 次；终态事件到达后主动结束长连接。`NODE_*` 只更新节点面板，`FINAL_ANSWER_*` 只更新聊天主回答。
- 新增聊天节点下拉面板，按 `nodeExecutionId` 展示循环中每次执行、运行动画、中间输出、路由去向、Token 和根 Trace ID；支持 `prefers-reduced-motion`。
- 工作流编辑页新增“系统工作流 / 智能工作流”独立 Tab。智能模式可编辑总步数/Token 预算、节点路由指令、最大访问次数、允许目标、显式 END 路由和每条边的策略/键/表达式/优先级；标准工作流仍沿用旧 DAG 语义。
- 后端异步执行改为显式携带 Run 根 Trace，不再捕获可能换号的触发请求 Trace。节点开始/完成/失败和路由裁决同时写入专用审计表与中文结构化日志，日志包含 `runId/nodeExecutionId/sourceNodeId/targetNodeId/strategy/traceId`。
- 前端第一次单测命令失败：测试放在 `src` 导致生产类型检查缺 Node 声明，且 ESM bundle 误打包 Axios CommonJS 依赖。已把纯 SSE parser 下沉到无 HTTP 依赖的 domain 文件，测试移至 `tests/` 并用 esbuild + Node test runner。修正后 `npm run test:unit` 为 3 tests / 0 fail；`npm run build` 类型检查和生产构建成功。
- 远端 MySQL 8.0.46 已执行非破坏性增量脚本，7 张新表和所有唯一索引实际存在。真实事务回滚验证返回 node/route/invocation/event=`1/1/1/2`、重复幂等写影响行 0、事件 Trace distinct=1、sequence=1..2，ROLLBACK 后 Run 剩余 0。首次手工 SQL 因遗漏必填 `replay_class` 失败，补齐后通过。
- MySQL 公网 TLS 仍有间歇性问题：一次独立查询返回 `SSL routines::wrong version number`，第 2 次重试成功并确认 `TLSv1.3 / TLS_AES_128_GCM_SHA256`。这项不能记为网络稳定性门禁通过。

### 2026-08-04 阶段 5 端到端验收执行计划

1. 停止 8091 上 2026-07-31 启动且 HTTP 请求持续超时的旧 Jar，打包并启动当前提交；不改动服务器中间件部署。
2. 通过真实注册/登录身份创建一个最小智能工作流，保存、发布并从聊天启动；校验 Run 根 Trace、节点/路由/事件/调用账本和最终消息。
3. 执行 SSE 事件顺序、`afterSequence` 重放、同 eventId 幂等、主回答/节点输出分流、取消后无新 invocation 与旧静态工作流回归。
4. 启动前端并进行真实浏览器点击验收；截图/响应/数据库回读只记录真实结果。

### 2026-08-04 阶段 5 首次端到端失败诊断与重试计划

- 首次启动当前 Jar 后，应用端口 8091 可用，未认证请求返回 401 且响应头带 `X-Trace-Id`；但端到端脚本没有生成 `intelligent_workflow_run` 记录，因此本次不能计为业务 E2E 通过。
- 同期真实日志显示开发环境默认开启历史 RAG Dispatcher/Worker/Kafka Listener。历史摄取任务占用仅 1 条连接的 Hikari 池，随后出现 `Connection is not available`、`Communications link failure` 和远端 MySQL TLS 读超时；智能工作流请求因此没有获得稳定数据库窗口。
- 本轮失败归类为“测试进程未隔离无关后台任务 + 远端 MySQL 公网连接不稳定”，不是智能路由断言失败。保留该失败记录，不覆盖、不伪造成功结果。

重试计划：

1. 停止当前测试实例，以环境变量显式关闭 `AI_RAG_OUTBOX_ENABLED`、`AI_RAG_WORKER_ENABLED`、`AI_RAG_KAFKA_LISTENER_ENABLED`，同时继续关闭 Nacos 注册，只隔离本轮无关任务，不改默认生产配置。
2. 确认 8091 健康、数据库连接可用后，重新执行注册、登录、创建/保存/发布智能工作流、创建会话、启动 Run、消费 SSE 的完整链路。
3. 从 MySQL 回读 Run、节点执行、路由决定、调用账本、事件和最终消息，硬校验同一 Run 只有一个根 `traceId`、事件 sequence 连续且终态完整。
4. 对同一 Run 执行 `afterSequence` 重放，并补充前端 reducer/页面验收；只有形成可复核证据后才追加“通过”。

重试实况：关闭 RAG 三个后台开关后，注册、登录、创建、保存和发布智能工作流均成功；启动 Run 时仍在创建 `chat_session` 前失败。服务日志证实同一 JDBC 连接在约 15 秒后由 `socketTimeout=15000` 触发 `Read timed out`，随后事务回滚；独立 MySQL CLI 同期也出现一次 TLS `wrong version number`。因此继续把公网 MySQL 稳定性列为外部门禁，不将本次记为工作流业务通过。下一轮仅在测试进程覆盖 JDBC `connectTimeout/socketTimeout`，验证长等待是否可恢复；不迁移数据库、不修改默认部署配置。

测试启动参数把 `socketTimeout` 提升到 600 秒后，新建连接仍在握手后的 `isReadOnly` 查询阶段于约 13 秒内断开，证明不是客户端 15 秒预算不足；`nc` 同时确认公网 22/3306 TCP 端口均开放。为区分公网 MySQL TLS 代理故障与业务实现，下一轮通过同一主机 `103.205.240.84` 的 SSH 本地端口转发访问服务器 MySQL，仅用于本机 E2E，不上传项目、不迁移数据；公网直连失败仍作为独立未通过基础设施门禁保留。

### 2026-08-04 智能路由 END 失败诊断与修复计划

- 经 SSH 转发后，真实 Run `run_d924119a-8280-4778-8b14-0cda99996832` 已成功落库并调用真实模型；根 Trace 为 `75986927-4c49-4af3-b580-d091148dda0b`。
- 持久事件 sequence 1..4 依次为 `WORKFLOW_STARTED / NODE_STARTED / NODE_OUTPUT_DELTA / NODE_COMPLETED`，模型真实输出“审核通过。”；sequence 5 为 `WORKFLOW_FAILED`，错误码 `WORKFLOW_ROUTE_NOT_FOUND`。所有事件的根 Trace 一致。
- 该样本证明节点执行和中间输出流已打通，但单节点智能工作流的 DEFAULT 边无法路由到显式 `END`，因此不能判定核心闭环通过。

修复计划：

1. 只读核对编译器对 END 边的保留规则、路由器 DEFAULT 匹配和允许目标校验，定位丢边或过滤条件。
2. 先增加“单节点 DEFAULT → END”的领域回归测试，再做最小修复；同时覆盖 routeKey 缺省和允许目标包含 END。
3. 重跑领域定向测试、完整前端测试和真实 API/SSE E2E；数据库必须出现路由审计、最终回答事件和 `COMPLETED` 终态。

### 2026-08-04 阶段 5 修复与端到端闭环结果

- 根因确认：`WorkflowRuntimeCompiler.dagEdges` 只保留目标也是 LLM 节点的边，导致智能图合法的显式 `END` 边在编译计划中丢失。已仅对 `INTELLIGENT` 图保留 `source LLM → END`，不改变旧静态 DAG 的边过滤语义。
- 新增 `shouldKeepExplicitEndEdgeForSingleNodeIntelligentWorkflow` 回归用例。最终 Java 定向测试共 16 tests，0 failures、0 errors、0 skipped，六个 reactor 模块全部 `BUILD SUCCESS`。
- 前端最终测试：`npm run test:unit` 为 3 tests / 3 pass / 0 fail；`npm run build` 的 `vue-tsc --noEmit` 与 Vite 生产构建通过，1923 modules transformed。
- 真实 API/SSE Run `run_67471a3d-570c-49f2-ad68-b479d15daed9` 进入 `COMPLETED`，根 Trace `419639a5-5dc6-4925-9b89-c69aeff29722`。事件 sequence 严格为 1..8：`WORKFLOW_STARTED → NODE_STARTED → NODE_OUTPUT_DELTA → NODE_COMPLETED → ROUTE_DECIDED → FINAL_ANSWER_DELTA → FINAL_ANSWER_COMPLETED → WORKFLOW_COMPLETED`。
- 数据库回读：Run/NodeExecution/RouteDecision/Invocation/Event/Message 行数分别为 `1/1/1/1/8/2`，各表 `COUNT(DISTINCT trace_id)=1`，值均等于 Run 根 Trace；节点 `COMPLETED`、路由 `review→END:DEFAULT`、调用 `SUCCEEDED`、消息角色为 `user,assistant`。
- `afterSequence=4` 真实重放只返回 sequence `5,6,7,8`，类型和根 Trace 均与首次事件一致，无缺口、无换号。
- 真实浏览器从登录开始选择“数据库工作流”，发送“请审核浏览器端到端消息”；主回答显示“审核通过”，节点下拉面板显示“审核节点 / 第 1 次 / 已完成 / 路由 DEFAULT → END / 163 Token / 完整 Trace ID”，浏览器控制台错误 0。
- Grafana/Loki 使用浏览器 Run 根 Trace `5e229942-213e-4088-80cd-ca8f8e365b1b` 查询到 15 条日志，失败 0、降级 0、取消 0、未闭合阶段 0；覆盖运行开始、节点开始、模型调用、上下文组装、Token 记账、节点完成、路由裁决、消息保存和运行完成。
- 未伪装通过的基础设施问题：公网 `103.205.240.84:3306` TCP 可达，但 MySQL TLS 仍随机出现 `wrong version number/read timed out`；本轮业务 E2E 通过同一主机 SSH 转发完成。公网 MySQL 稳定性仍是独立上线阻塞项。

### 2026-08-04 阶段 6 终态流、刷新恢复与真实取消计划

完成度复核确认上一阶段仍有三个直接缺口：服务端在持久历史包含终态事件后仍继续等待本实例 live processor，导致 `curl` 必须依赖超时结束；会话历史虽然已返回 `runId/traceId`，前端刷新后只恢复最终消息，没有重放节点面板；取消只有领域门禁单测，尚无真实竞态证据。

本阶段按以下顺序执行：

1. 让工作流事件流在包含 `WORKFLOW_COMPLETED/FAILED/CANCELLED` 的终态事件后包含该事件并正常完成，补领域服务测试，禁止依赖客户端主动断流才释放连接。
2. 会话切换读取历史消息后，按 assistant 消息携带的 `runId + traceId` 重放智能工作流事件，恢复节点面板；失败的非智能旧 Run 不阻断消息历史。
3. 扩充前端事件测试，验证完整历史重放与刷新恢复结果一致、最终回答不重复、根 Trace 换号仍拒绝。
4. 重新打包，以真实浏览器刷新既有智能会话，确认节点面板仍可展开；再执行真实立即取消并回读 invocation、事件和下一节点状态。
5. 用根 Trace 查询 Grafana/Loki，追加真实结果、失败因果和未通过门禁后再中文本地提交。

#### 阶段 6 取消日志诊断与修复追加计划

真实取消 Run `run_d1937ffc-bc1c-4485-a6a2-7f7d02d0cbfe` 的数据库门禁已通过，但 Loki 根 Trace `3dba6216-830d-48b6-b4dc-137787db37fd` 只有 5 条开始态日志，分析结果将 `run/node_execute` 判为未闭合。原因是取消事务只输出普通文本日志，没有带原 Run 根 Trace 的结构化取消终态。

1. 增加稳定中文事件 `chat_run_cancelled/会话运行已取消`，由取消事务用数据库中的 Run 根 Trace 覆盖当前取消请求的操作 Trace，并记录 run/session/reason/cost/stage。
2. 增加日志契约单测，验证事件编码、中文名、终态字段和显式根 Trace 可被 Loki 检索。
3. 重新执行真实立即取消，再分别回读数据库与 Loki；门禁为取消后 invocation/route=0、唯一根 Trace、`chat_run_cancelled` 可见且 run 阶段不再未闭合。

#### 阶段 6 交付文档与响应 Trace 收口计划

最终验收发现运行启动响应已同时返回根 `traceId` 与 `operationTraceId`，但取消/引导响应数据体尚未显式返回这两个身份；外层统一响应的 `traceId` 仅代表本次控制请求，调用方容易误把它当成 Run 根链路号。

1. 在运行控制响应数据体补充不可变的 Run 根 `traceId` 和本次 HTTP `operationTraceId`，取消、引导保持同一契约；外层响应 Trace 仍维持现有过滤器语义。
2. 增加 Controller 契约测试，断言取消后数据体根 Trace 不换号、操作 Trace 可单独审计，且同步触发智能工作流取消终态收口。
3. 修订交付文档中设计目标与当前实现混写的内容，用真实事件序列、真实测试数量和真实 Run/Trace 证据更新当前交付边界。
4. 重跑后端定向测试、前端 reducer/构建、真实取消 API/DB/SSE/Loki，并逐项追加结果；未跑过的 Human/SubWorkflow/并行、故障注入、性能与稳定性继续明确列为上线前门禁。

真实双 Trace 样本 `run_e4e4cc8b-5619-4cd5-850b-92fe859ae2e0` 首次返回 `WORKFLOW_RUN_CONCURRENT_MODIFICATION`。取消事务与后台节点启动同时推进智能 Run revision，同步取消协调读取后再更新时使用了过期 revision。该样本不得记为通过，追加修复步骤：

1. 取消协调仅对并发修改错误做有限重新读取与重试；每次都以数据库最新 revision 更新，发现智能 Run 已是终态时按幂等成功结束。
2. 其他数据库/业务异常不吞掉、不无限重试；重试耗尽仍返回明确失败。
3. 单测模拟首次 revision 冲突、第二次成功，断言只产生一次节点取消审计和一次 `WORKFLOW_CANCELLED` 事件。
4. 重新打包并运行新的真实立即取消样本，双 Trace 响应、数据库、SSE、Invocation/Route 和 Loki 门禁必须再次全部通过。

#### 阶段 6 最终执行结果

- 服务端 SSE 已在历史或实时流遇到 `WORKFLOW_COMPLETED/FAILED/CANCELLED` 后“包含终态再完成”，不再依赖客户端超时；新增 2 个服务单测覆盖历史终态与实时终态。
- 前端会话历史加载后会从 assistant 消息提取唯一 `runId + traceId`，后台重放每个智能 Run；会话切换/重置会中止旧恢复连接，单个 Run 最多重试 3 次。新增历史恢复目标测试后前端共 4 tests / 4 pass，`vue-tsc --noEmit` 与 Vite 构建通过，1924 modules transformed。
- 真实完成 Run `run_39933228-dd7f-4bb4-8807-29eba6ff518c` 根 Trace `3e31430f-9ef4-4da1-8701-eff12788c9a8`：SSE sequence 1..8 后由服务端结束；浏览器硬刷新并重新选择数据库工作流和原会话后，最终消息及“审核节点/第1次/已完成/审核通过/DEFAULT→END/178 Token/原 Trace”恢复，控制台错误 0。
- 首轮取消 Run `run_d1937ffc-bc1c-4485-a6a2-7f7d02d0cbfe` 证明 Invocation/Route=`0/0`，但发现取消日志未闭合；后续多个样本证明只依赖异步线程收口会残留智能 Run/节点 `RUNNING`。修复为取消接口同步更新智能 Run、运行中节点并发布终态，同时增加中文 `chat_run_cancelled`、`workflow_node_cancelled` 日志。
- 双 Trace 首个真实样本 `run_e4e4cc8b-5619-4cd5-850b-92fe859ae2e0` 又发现 revision 竞态，返回 `WORKFLOW_RUN_CONCURRENT_MODIFICATION`。原计划的“重新查询有限重试”在事务默认 `REPEATABLE READ` 下仍可能读取旧快照，因此最终采用单条 `status NOT IN ('CANCELLED','COMPLETED','FAILED')` 原子条件更新作为取消线性化点；重复取消幂等返回 0，后台旧 revision 更新自动失败。
- 最终 Java 命令：`mvn -Dnet.bytebuddy.experimental=true -pl ai-agent-scaffold-app -am -DskipTests=false -Dtest=RunControlControllerTest,DomainLogTest,TraceContextTest,WorkflowEventStreamServiceTest,WorkflowEventRepositoryTest,IntelligentWorkflowRuntimeServiceTest,WorkflowDagCompilerTest,WorkflowEventMapperContractTest,MyBatisMapperLoadTest -Dsurefire.failIfNoSpecifiedTests=false test`。真实结果 27 tests，0 failures、0 errors、0 skipped，六个 reactor 模块全部 `BUILD SUCCESS`。
- 最终 `mvn -DskipTests package` 八个 reactor 模块全部 `BUILD SUCCESS`；前端 `npm run test:unit` 4/4，通过；`npm run build` 成功。
- 最终取消 Run `run_9dcbfc73-b65e-46f4-80d8-da8e3f2ebe4e`：启动根 Trace `787200c2-8f6a-47cd-a7d1-5392ab974359`，取消操作 Trace `80f375b6-af5f-4e2b-b8ca-a9a6d9ff19bb`；响应数据体同时返回两者且根 Trace 不换号。
- 数据库最终门禁：ChatRun/智能 Run/节点=`cancelled/CANCELLED/CANCELLED`，节点 `RUN_CANCELLED`；Invocation/Route=`0/0`；事件为 sequence `1..3` 的 `WORKFLOW_STARTED → NODE_STARTED → WORKFLOW_CANCELLED`，所有记录根 Trace 唯一。
- 取消历史 SSE 真实 0.30 秒返回并关闭。Grafana/Loki 根 Trace 分析为 17 条业务日志、1 个 traceId、1 个 runId，包含 `chat_run_cancelled` 与 `workflow_node_cancelled`，未返回 `incompleteStages`。
- 明确剩余：Human/SubWorkflow/并行 Join、多实例重启接管、在途 Tool/RAG 副作用取消、跨租户全套、安全/故障注入、容量和 60 分钟稳定性尚未执行，不能宣布生产上线门禁全部通过；公网 MySQL TLS 随机失败也是独立阻塞项。
