# 普通 DAG 节点事件与工作流模板库闭环计划

## 目标

在不改变既有静态 DAG 调度语义和智能工作流路由语义的前提下完成两项交付：

1. 普通 DAG 的串行、并行、汇聚和有限自循环节点均实时发送、持久化并可回放中间状态与展示输出，前端与智能工作流共用节点执行面板。
2. 前端提供不少于 24 个可载入画布的工作流模板，覆盖普通 DAG、智能路由、生产参考和确定性测试场景。

最终必须通过后端、前端和真实浏览器端到端测试；测试结果只记录真实命令和真实输出。

## 强制约束

- 修改前遵循 `codex.md`，保持 `trigger -> domain <- infrastructure`，不在 Controller 或 DAO 中承载调度规则。
- 复用 `workflow-event-v1`、Run Control、TraceContext、工作流编译器和现有有界线程池，不另造不兼容事件协议。
- 普通 DAG 继续使用 Kahn 拓扑分层：同层并行、跨层等待依赖；智能工作流继续使用单活动路径路由。
- 一个 Run 的所有事件必须使用同一个根 `traceId`；事件以数据库分配的严格递增 `sequence` 续传，以 `nodeExecutionId` 隔离并行和循环实例。
- SSE 断开不得取消后台 Run；取消提交后不得启动新节点、模型或工具调用。
- 不暴露思维链、系统提示词、凭据或未授权数据；节点面板只展示允许持久化的 display output。
- 不覆盖工作树中已有的 `ChatService`、`RunControlService` 和日志/RAG 文档改动；提交时不纳入日志、对象存储及无关未跟踪文件。
- 每个阶段执行前在本文追加计划，完成后追加改动、测试和失败诊断；重大闭环使用中文本地提交。

## 需求与权威证据

| 需求 | 完成证据 |
|---|---|
| 普通 DAG 发送节点中间状态 | 静态工作流 Run 持久化出现 STARTED、NODE、FINAL、终态事件；SSE 可按 sequence 回放 |
| 串行正确展示 | 后继节点 `NODE_STARTED` 严格晚于前置节点 `NODE_COMPLETED` |
| 并行正确展示 | 同层两个节点均可处于 running，各自 `nodeExecutionId` 和输出互不覆盖，汇聚节点等待二者完成 |
| 输出实时传输 | 节点模型产出的安全文本以 `NODE_OUTPUT_DELTA` 增量到达，`NODE_COMPLETED` 带最终快照 |
| 刷新与断线恢复 | `afterSequence` 重连及会话历史恢复得到相同节点集合、顺序和最终回答 |
| 失败/取消闭环 | 节点失败、工作流失败、取消终态准确；取消后新增 invocation/节点执行为 0 |
| 智能工作流不回归 | 既有 AI_ROUTER、DEFAULT、循环和节点面板测试继续通过 |
| 24+ 模板 | 模板清单测试断言数量、唯一 ID、分类覆盖、图编译合法；浏览器可载入并编辑 |
| 全局链路 | API、Run、节点、路由、模型、事件和最终消息根 `traceId` 一致，前端可复制 |

## 实施设计

### 1. 通用工作流事件运行边界

- 将事件流的运行授权从“必须存在智能运行扩展行”泛化为“必须存在当前租户/用户可访问的 workflow 类型 `chat_run`”。
- 保留智能运行扩展表负责智能路由状态；事件 sequence 使用独立的通用工作流事件游标，不借用智能状态字段，避免静态 Run 伪装为智能 Run。
- 保留 `/api/v1/intelligent-workflow-runs/{runId}/stream` 兼容入口，新增通用 `/api/v1/workflow-runs/{runId}/stream` 供两类工作流使用。
- 事件仍落 `workflow_run_event`，不复制第二套事件表。

### 2. 普通 DAG 节点事件

- Run 创建成功后发布 `WORKFLOW_STARTED`。
- 每个节点每次循环生成唯一 `nodeExecutionId`，依次发布 `NODE_STARTED`、零到多条 `NODE_OUTPUT_DELTA`、`NODE_COMPLETED`；异常发布 `NODE_FAILED`。
- 并行节点共享只读上游快照，但分别持有输出缓冲、证据和事件身份；数据库 sequence 只定义观察顺序，不改变并行调度。
- DAG 完成后分别发布 `FINAL_ANSWER_DELTA`、`FINAL_ANSWER_COMPLETED`、`WORKFLOW_COMPLETED`；失败和取消发布对应唯一终态。
- 节点输出的流式边界优先使用 ADK Event 增量；若 Provider 只返回快照，则通过差分生成增量，最终快照由 `NODE_COMPLETED` 校正。

### 3. 前端统一展示

- 静态和智能工作流都在启动后创建 `WorkflowRunViewState` 并订阅通用事件流。
- Reducer 继续按 `runId + nodeExecutionId` 建模；并行节点允许同时 running，循环执行保留多个栏目。
- 主回答只消费 `FINAL_ANSWER_*`，节点输出只进入执行面板，避免重复。
- 会话恢复不再限定 `workflowKind === INTELLIGENT`；有 workflow-event-v1 的工作流 Run 均回放，旧历史没有事件时保留最终消息而不伪造面板。
- 面板标题改为通用“工作流运行中/已完成”，智能运行才展示路由去向。

### 4. 模板库

- 使用前端纯 TypeScript 模板定义和深拷贝载入函数，不把模板硬编码散落在 Vue 视图。
- 至少 24 个模板，建议 12 个生产参考、12 个测试验证；普通 DAG 与智能工作流各不少于 10 个。
- 每个模板包含稳定 ID、名称、说明、用途标签、工作流类型、依赖提示和完整合法 Graph。
- “载入模板”只替换当前草稿画布，不自动保存或发布；载入前有未保存改动时需要明确确认。
- 测试模板覆盖串行、并行、扇出汇聚、多层汇聚、自循环、失败演示、AI 二分支、多分支、DEFAULT 兜底和有限回路。

## 执行阶段

### 阶段 0：审计与计划落盘

1. 核对普通/智能运行入口、事件仓储、数据库表、SSE、前端 reducer/恢复和现有测试。
2. 识别工作树已有改动并确定不覆盖边界。
3. 完成本计划及验收证据矩阵。

### 阶段 1：通用事件持久化与 API

1. 新增通用事件游标迁移、领域仓储端口和基础设施实现。
2. 泛化事件授权和 SSE Controller，保留智能入口兼容。
3. 增加真实序号并发、租户隔离、续传与终态单元/集成测试。

### 阶段 2：普通 DAG 事件发布

1. 在静态 DAG Run、节点、输出、失败、取消和最终回答边界发布事件。
2. 保证并行节点各自事件身份和输出隔离，汇聚语义不变。
3. 增加串行、并行、汇聚、自循环、失败与取消后无新调用测试。

### 阶段 3：前端统一订阅与恢复

1. 将普通工作流从旧最终文本 SSE 切换到通用工作流运行+事件流，或在兼容入口中同时消费结构化事件。
2. 统一节点面板、终态、断线重连和历史回放。
3. 补 reducer、SSE parser、Store 分支和组件渲染测试。

### 阶段 4：24+ 模板库

1. 建立模板类型、清单、图工厂、载入入口和分类筛选。
2. 提供生产参考与测试验证模板，并逐个通过前后端图校验。
3. 增加数量、唯一性、分类、深拷贝和合法性测试。

### 阶段 5：端到端验收与提交

1. 执行 Maven 定向测试及模块测试、前端单测、类型检查和生产构建。
2. 启动当前后端和前端，真实运行静态串行、静态并行汇聚、智能路由各一条；校验 UI、SSE、数据库和 Trace。
3. 执行刷新回放、断线续传、失败与取消场景；记录任何中间件不稳定，不以重试掩盖失败。
4. 将真实结果追加本文，审计 git diff，只提交本任务文件，提交信息使用中文。

## 2026-08-04 阶段 0 执行记录

- 已读取 `codex.md` 和既有智能工作流交付/实施文档。
- 已确认智能工作流拥有持久化 `workflow-event-v1`、严格 sequence、断线续传和节点面板；普通 DAG 仅向日志写节点开始/完成，`Flowable<String>` 在整个 DAG 完成后只发送一个最终值。
- 已确认前端只有 `workflowKind === INTELLIGENT` 时创建/恢复 `workflowRuns`，因此普通 DAG 不可能显示节点面板。
- 已确认现有事件序号借用 `intelligent_workflow_run.next_sequence` 行锁，阶段 1 必须泛化序号所有权，不能直接让普通 DAG 写入该仓储。
- 已确认工作树中 `ChatService`、`RunControlService` 有既有未提交修改，后续按最小补丁保留；日志、对象存储、RAG 评测产物均不纳入提交。

## 后续实际执行记录

> 每个阶段完成后继续追加真实改动、测试命令、结果、失败原因和提交号。

### 2026-08-04 阶段 1/2 第一轮实现记录

- 新增通用工作流事件游标的增量、回填和非删除式回滚 SQL；事件 sequence 不再借用 `intelligent_workflow_run.next_sequence`，静态 Run 不会伪装成智能运行。
- 事件授权已泛化到租户/用户可访问且 `source_type=workflow` 的 `chat_run`，并继续强制校验根 `traceId`；原智能运行查询入口保留兼容。
- 新增普通 DAG 独立启动服务与 `POST /api/v1/workflow-runs`，启动响应返回 `runId/sessionId/traceId`，后台执行不依赖随后建立的 SSE 连接。
- 新增通用 `GET /api/v1/workflow-runs/{runId}/stream?afterSequence=`；普通和智能工作流均可从同一持久事件协议续传。
- 普通 DAG 已在工作流、节点、模型输出、失败、取消和最终回答边界发布事件；每次循环生成独立 `nodeExecutionId`，并行节点输出按执行身份隔离。
- 修正普通 DAG 异步线程角色丢失风险：在请求线程冻结可信 `roleCode` 后显式传入协调线程，不再在线程池内读取可能为空的 ThreadLocal。
- 前端发送分支已改为普通/智能工作流分别启动、统一订阅通用事件流；历史回放不再只限智能工作流；节点失败可在面板展示。
- 第一轮编译命令：`mvn -DskipTests -pl ai-agent-scaffold-app -am compile`；结果为六个 reactor 模块全部 `BUILD SUCCESS`，总耗时 5.809 秒。
- 第一轮定向测试命令：`mvn -Dnet.bytebuddy.experimental=true -pl ai-agent-scaffold-app -am -DskipTests=false -Dtest=StaticWorkflowRuntimeServiceTest,WorkflowEventStreamServiceTest,WorkflowEventRepositoryTest,WorkflowEventCursorRepositoryTest,WorkflowEventMapperContractTest,WorkflowEventCursorMigrationContractTest,MyBatisMapperLoadTest -Dsurefire.failIfNoSpecifiedTests=false test`。
- 真实结果：13 tests，0 failures，0 errors，0 skipped；六个 reactor 模块全部成功，总耗时 2.087 秒。
- 一次中间编译曾因并行实施中的 `requireWorkflowRun` 尚未写入而在 trigger 模块出现 1 个符号缺失；通用授权方法完成后重跑已通过，未把中间失败伪记为成功。

### 2026-08-04 阶段 2/3 下一轮执行计划

1. 增加普通 DAG 串行、并行、汇聚、失败和取消事件的领域级可验证测试，重点校验并行实时顺序与节点输出隔离。
2. 完成前端普通/智能统一 reducer、事件续传、历史恢复和面板测试，修复任何构建或类型问题。
3. 完成 24+ 模板的画布载入入口，并用前端单测和后端编译器双重校验图合法性。

### 2026-08-04 阶段 4 本次执行计划

1. 使用独立 TypeScript 模板定义、图工厂和深拷贝载入函数，不把模板数据散落在 Vue 视图中。
2. 交付不少于 24 个模板，生产参考与测试验证均覆盖，`STATIC` 和 `INTELLIGENT` 各不少于 10 个。
3. 将模板分类筛选、选择和“载入当前草稿”接入工作流构建页；载入前检测未保存改动并确认，载入后不自动调用保存或发布。
4. 使用 Node 内置测试补齐数量、稳定 ID、分类覆盖、深拷贝隔离和关键图结构断言，并将新测试纳入 `test:unit`。
5. 完成后追加真实文件、命令、测试结果和未解决边界；本阶段不修改 `chat.ts`、后端或无关文件，不提交。

### 2026-08-04 阶段 4 实际执行记录

- 新增 `ai-agent-scaffold-web/src/domain/workflow-templates.ts`，集中定义 24 个稳定模板：生产参考 12 个、测试验证 12 个，`STATIC` 12 个、`INTELLIGENT` 12 个；每个模板均含稳定 ID、说明、标签、依赖提示和完整 Graph。
- 模板覆盖串行、并行、扇出汇聚、多层汇聚、有界自循环、节点失败演示、AI 二/多分支、`DEFAULT` 兜底、表达式路由、节点建议和有界回路；静态图仅使用 DAG 或有 `maxIterations` 的自循环，智能图均配置 `maxSteps`、`tokenBudget`、`maxVisits`、允许目标和 `DEFAULT` 出口。
- 新增 `cloneWorkflowTemplateGraph()` 深拷贝载入函数，避免画布编辑污染模板常量或其他载入实例。
- 在 `WorkflowBuilderView.vue` 增加用途/类型筛选、模板详情、依赖提示和“载入当前草稿”入口；载入只深拷贝替换本地画布，没有调用保存或发布 API。服务端详情/保存成功后记录指纹基线，新建态也在初始画布上建立基线，覆盖已有未保存改动前必须二次确认。
- 新增 `ai-agent-scaffold-web/tests/workflow-templates.test.mjs`，校验模板数量/分类、稳定唯一 ID、节点与边引用、静态 DAG/自循环、智能预算/兜底/允许目标/表达式、深拷贝隔离和关键图结构；`package.json` 已将该测试纳入 `test:unit`。
- 首次单测编译暴露模板清单的括号语法错误，修正后重新执行门禁；未隐藏或绕过该失败。
- 最终执行 `npm run test:unit`：10 项通过、0 失败、0 跳过，包含原有 4 项工作流事件测试和新增 6 项模板测试。
- 最终执行 `npm run build`：`vue-tsc --noEmit` 通过，Vite 7.3.6 转换 1925 个模块并于 1.02 s 完成生产构建。
- 执行本阶段文件 `git diff --check`：通过，无空白或冲突标记错误。
- 本阶段未修改 `chat.ts`、后端或无关文件，未上传服务器，按子任务边界未创建 Git 提交。

### 2026-08-04 端到端前生产审计结论

当前实现尚不能进入最终端到端验收，必须先关闭以下一致性缺口：

1. 同一 Run 的并行发布虽然能从数据库获得递增 sequence，但实时推送仍可能先观察到较大 sequence，导致前端游标丢弃稍后到达的较小 sequence；必须让实时观察也按数据库顺序交付，并保留跨实例续传能力。
2. 同层任一节点失败时，协调线程会过早退出 join，其他兄弟节点可能在 `WORKFLOW_FAILED` 之后继续发布事件；必须先收敛同层全部 Future，再发布工作流终态。
3. `chat_run` 完成、最终助手消息与 `FINAL_ANSWER_* / WORKFLOW_COMPLETED` 当前不是同一事务，事件落库失败时可能出现数据库显示完成、事件流却失败的矛盾状态；必须建立单一事务终结边界，并只在事务提交后通知实时订阅者。
4. Run 尚在队列或尚未进入工作线程时取消，工作线程可能永远没有机会发布 `WORKFLOW_CANCELLED`；取消入口必须主动幂等补齐工作流取消终态，且后续节点、模型和工具调用均被门禁阻断。
5. 会话刷新时，运行中的普通工作流可能只有用户消息而没有助手占位消息，现有恢复逻辑无法重新挂载节点面板；必须从任何带 runId/traceId 的工作流消息恢复，并为未完成 Run 建立临时助手展示位。
6. SSE 需要心跳、断开释放、稳定错误协议；数据库回放不能只有固定 1000 条的一次性窗口。

### 2026-08-04 阶段 2/3 一致性修正执行计划

1. 改造工作流事件流为“数据库有序回放/追尾 + 本机通知唤醒”，实时处理以连续 sequence 为唯一准入条件，分页读取直到追平；移除会无限增长的每 Run 回放处理器，并补并发乱序、超过 1000 条、重连和终态测试。
2. 修改静态 DAG 同层并行收敛：等待当前层全部节点结束，收集首个失败后停止推进下一层；取消节点发布 `NODE_CANCELLED`，确保工作流终态是该 Run 最后一条业务事件。
3. 增加工作流 Run 事务终结服务，将 Run 状态、最终助手消息、最终回答事件和唯一工作流终态置于同一事务；实时事件仅在提交后通知。取消 Controller 统一调用幂等终态协调逻辑。
4. 完善前端普通工作流运行中恢复、节点取消状态和连续游标测试；补通用 SSE 心跳及连接释放。
5. 完成后依次执行后端定向测试、全模块测试、前端单测与构建，再启动真实前后端进行串行、并行汇聚、失败、取消、刷新续传、智能路由和模板载入端到端验收。

### 2026-08-04 阶段 2/3 一致性修正实际记录

- 普通 DAG 同层并行已改为先等待本层全部 Future 收敛，再统一写回成功结果或抛出首个失败；失败或取消后不推进下一拓扑层，工作流终态不会早于仍运行的兄弟节点。
- 节点取消与真实失败已拆分为 `NODE_CANCELLED` / `NODE_FAILED`；前端 reducer 和节点面板分别展示“已取消”与“失败”，并行执行实例仍按 `nodeExecutionId` 隔离。
- 通用事件游标新增 `terminal_event_type` 和 `terminal_sequence`。第一个 `WORKFLOW_COMPLETED/FAILED/CANCELLED` 在游标行锁内占用唯一终态槽位，此后任何节点或终态事件都被拒绝；历史回填会从已有事件恢复终态槽位。
- 事件实时传输已改为数据库有序追尾：数据库 sequence 是唯一真相源，本机提交后通知仅用于降低延迟，每秒周期追尾负责跨实例和丢通知恢复；不再为每个 Run 永久保存 `ReplayProcessor`。超过首批 1000 条后会继续分页追尾。
- 事件写入若处于外层事务，只在事务提交后唤醒 SSE；新增 `WorkflowRunFinalizationService`，普通 DAG 的最终助手消息、Run 完成状态、最终答案事件和唯一工作流终态在同一事务提交。失败消息与失败终态同样原子收口。
- 取消接口在 `RunControlService.cancel` 成功后主动协调通用工作流取消终态，因此 Run 即使尚在队列或尚未进入后台线程，也能在取消响应前落下 `WORKFLOW_CANCELLED`；智能扩展状态仍由原服务收口。
- 通用 SSE 增加 15 秒 heartbeat、连接终止订阅释放和稳定错误码；原始异常不再直接暴露给浏览器。
- 会话恢复已允许从带 `runId + traceId` 的用户消息发现运行中工作流；若最终助手消息尚未落库，会建立临时助手展示位并实时恢复节点状态与最终回答。
- 首次定向测试在接口签名和实时流真相源调整后出现 7 个预期失败：5 个旧测试仍调用四参数游标接口，2 个实时测试的 mock 未模拟数据库持久化。修正测试夹具并执行 clean test 后通过，没有跳过失败。
- 后端主代码命令：`mvn -DskipTests -pl ai-agent-scaffold-app -am compile`；结果六模块 `BUILD SUCCESS`，总耗时 5.807 秒。
- 后端定向命令：`mvn -Dnet.bytebuddy.experimental=true -pl ai-agent-scaffold-app -am test -DskipTests=false -Dtest=WorkflowEventStreamServiceTest,WorkflowEventRepositoryTest,WorkflowEventCursorRepositoryTest,WorkflowEventMapperContractTest,WorkflowEventCursorMigrationContractTest,MyBatisMapperLoadTest,StaticWorkflowRuntimeServiceTest,RunControlControllerTest -Dsurefire.failIfNoSpecifiedTests=false`；结果 17 tests，0 failures/errors/skipped，六模块 `BUILD SUCCESS`，总耗时 4.183 秒。
- 前端 `npm run test:unit`：11 tests，11 通过、0 失败；包含普通/智能节点归并、取消实例隔离、用户消息恢复和 24 模板门禁。
- 前端 `npm run build`：`vue-tsc --noEmit` 与 Vite 生产构建均通过，1925 modules，Vite 1.01 秒。

### 2026-08-04 阶段 5 端到端执行前计划

1. 增加终态协调和普通 DAG 串行/并行收敛的定向测试，随后运行本任务相关后端测试集与完整可运行测试门禁。
2. 对真实业务 MySQL 执行通用事件游标 schema 与 backfill，回读表结构、终态槽位和历史 sequence，禁止把未执行 DDL 的 Mapper 测试冒充数据库验收。
3. 启动后端和前端，使用真实认证、真实工作流发布版本和真实模型分别运行普通串行、普通并行汇聚与智能路由；抓取 SSE、数据库事件和根 `traceId`。
4. 使用浏览器验证模板筛选/载入、节点下拉面板并行转动、最终状态、刷新回放；执行取消及断线续传，保存必要的 E2E 证据，不提交运行日志和截图临时文件。

### 2026-08-05 阶段 5 实际执行记录

- 已在真实业务 MySQL 执行 `2026-08-04-workflow-event-cursor.sql` 和 backfill，并独立回读：通用游标 44 行、符合条件的 workflow Run 44 条、历史事件 185 条，`next_sequence` 最小 1/最大 29；非法游标 0、重复终态 0。DDL、Mapper 与应用运行使用同一业务库，不以 H2 或 mock 代替真实迁移验收。
- 浏览器在工作流构建页确认模板库恰好 24 个，载入“测试：扇出汇聚 · 系统”后得到 4 节点/4 边：`start -> left/right -> join`；随后创建并发布普通工作流 `E2E普通DAG节点事件-20260805` v1。该图同时覆盖跨层串行屏障和同层并行扇出/汇聚。
- 真实普通 DAG 运行根链路为 `3a13077f-1e68-4571-ace5-d2abce62e741`，Run 为 `run_a5549a69-e361-404b-83c8-ead36e1e44c8`，会话为 `e62e2af7-4e4a-468c-908d-c1519724c989`。运行过程中页面先显示起点完成、左右分支同时处于执行态；随后左右分支分别输出 `RIGHT`、`LEFT`，只有二者都完成后汇聚节点才进入执行态，最终输出 `JOIN`。
- 页面终态显示“工作流已完成 · 4 次节点执行 · #16”。数据库独立回读得到连续且无缺口的 1–16 号事件：1 `WORKFLOW_STARTED`；2–4 起点；5/6 左右分支同时开始；7–10 左右输出与完成；11–13 汇聚开始、输出与完成；14 `FINAL_ANSWER_DELTA`；15 `FINAL_ANSWER_COMPLETED`；16 `WORKFLOW_COMPLETED`。四个节点输出未互相覆盖，Run、消息和所有事件使用同一根 Trace ID。
- 前端刷新后的历史列表复验未记为通过：公网 MySQL 首轮握手/查询发生连接关闭，切换到同一数据库容器的 SSH 隧道后首个 Hikari 连接仍在事务隔离级别探测时关闭；后续 `/api/v1/sessions` HTTP 日志为 200，但本轮工作台初始化没有重新装载会话列表。因此刷新 UI 没有再次点开节点面板。数据库已确认会话、最终助手消息和 16 条事件持久存在，事件流/历史 reducer 自动测试通过，但本条环境受阻事实保留，不伪造浏览器刷新成功。
- 公网链路瓶颈被真实观察：汇聚节点在 00:43:31 写完 `NODE_COMPLETED` 后，最终事务等待 MySQL 响应，约至 00:52 才提交 14–16 号终态事件。线程栈位于 `WorkflowRunFinalizationService.complete -> WorkflowEventCursorRepository.allocate` 的 JDBC SSL 读取，不是 DAG 调度等待或模型节点未结束。
- 后端最终定向门禁命令覆盖终态事务、事件追尾、通用游标、Mapper/迁移、静态运行入口、取消、DAG 编译和智能路由回归；真实结果为 38 tests、0 failures、0 errors、0 skipped，六模块 `BUILD SUCCESS`，总耗时 7.366 秒。
- 前端最终 `npm run test:unit` 为 11 tests 全部通过；`npm run build` 中 `vue-tsc --noEmit` 通过，Vite 转换 1925 个模块并成功产出。
- 完整 Maven 测试实际运行 484 项，0 failures、14 errors；错误集中在仓库既有的手工示例、外部模型/API 和自动装配环境测试（如 `ParallelAgentTest`、`LoopAgentTest`、`ChatServiceTest`、`SpringAiApiTest`、`LangChain4jApiTest`），不在本任务 38 项定向门禁内。本次没有跳过或篡改这些失败，也不把完整套件声明为通过。
- 本阶段没有上传本地项目，没有迁移 MySQL，没有提交运行日志、对象存储、RAG 评测产物或其他无关未跟踪文件。
