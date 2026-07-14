# 阶段能力闭环实施计划

> 状态：执行中
> 创建日期：2026-07-14
> 需求基线：`docs/superpowers/specs/2026-07-14-stage-capabilities-roadmap-and-core-prompts.md`
> 项目规范：`codex.md`

## 1. 执行目标

在不破坏现有模块化单体架构、租户隔离、工具网关和 Kafka 上下文压缩链路的前提下，完整实现并验证：

1. 工具调用前的 token 阈值检查与上下文压缩阻断；
2. 正在执行 run 的取消、消息失效、压缩失效和工具调用保护；
3. 正在执行 run 的引导与后继执行链；
4. 会话导出、MinIO 私有分享和复制导入；
5. 分布式定时任务的配置协调、到期派发、幂等执行、部署和前端管理。

## 2. 强制执行约束

- 每轮业务修改前先更新本计划中的“当前执行段”。
- 每轮修改后在“执行记录”追加真实文件、行为、测试和遗留项。
- 每形成一个可验证的重大闭环，以中文提交信息进行本地提交。
- 提交时只暂存本任务文件，禁止混入工作区既有日志、`data-alloy/`、`skills/` 等用户改动。
- 先读真实代码再定结构；优先扩展现有 Service、Repository、ToolGateway、Runtime、Kafka 与 MinIO 封装。
- Controller 只做协议适配，核心状态和并发规则位于 Domain；Infrastructure 负责 DAO、Kafka、MinIO 和外部组件。
- 所有用户资源查询包含可信 `tenantId/userId` 条件。
- 新增类、接口和业务方法按 `codex.md` 使用简洁中文注释。
- 测试失败不作为提前停止理由；继续定位、修复并执行仍可运行的验证。

## 3. 工作区基线

执行开始时存在与本任务无关的用户改动：

- 已修改：四个运行日志文件；
- 未跟踪：`data-alloy/`、`docs/design-questions/`、`docs/design-viz-methodology.md`、`skills/`；
- 本任务已创建需求规划稿：`docs/superpowers/specs/2026-07-14-stage-capabilities-roadmap-and-core-prompts.md`。

这些文件默认不修改、不清理、不纳入功能提交；规划稿和本执行计划属于本任务，可随首个文档闭环提交。

## 4. 完成证据矩阵

| 要求 | 权威证据 |
|---|---|
| 工具前压缩 | 工具统一入口代码、阈值测试、取消竞态测试、工具调用计数 |
| 取消闭环 | run/message/compaction 状态迁移、API、前端、并发测试、审计日志 |
| 引导闭环 | steer API、前驱后继 run、上下文重建测试、前端交互 |
| 分享导入 | 导出 schema、MinIO 私有对象、分享权限与撤销、导入幂等和安全测试 |
| 分布式定时任务 | 数据迁移、协调器、派发器、租约/fencing、部署文件、管理端和并发测试 |
| 架构合规 | 模块依赖检查、代码审查、租户过滤、ToolGateway 唯一入口 |
| 上线验证 | Maven 测试/构建、前端构建、可行的集成/E2E、部署配置检查 |
| 本地提交 | 中文提交记录，按重大闭环拆分且不包含用户无关改动 |

## 5. 分阶段计划

### 阶段 A：真实代码与数据基线调研

1. 绘制聊天请求、流式响应、Agent Runner、工具调用的真实链路；
2. 绘制上下文 token 统计、Kafka 压缩任务、快照激活和恢复链路；
3. 检查消息、会话、压缩、工具日志和现有调度表；
4. 检查 MinIO 封装、认证、租户上下文和前端 API/store；
5. 检查测试框架、部署 Compose/Nacos 配置与数据库迁移方式；
6. 将调研结果、实际差距和最终实现切片追加到本计划。

验收：所有后续扩展点有真实文件和调用路径证据，没有凭规划稿臆造架构。

### 阶段 B：统一 run/context 基础、工具前压缩与取消

1. 以最小兼容方式补足 run 状态、消息有效性、context revision 和审计；
2. 让上下文/压缩/记忆读取统一排除无效消息；
3. 在 ToolGateway 的唯一真实外部调用入口增加执行闸门；
4. 工具前超阈值时触发或复用压缩，激活后重新检查执行条件；
5. 实现幂等 cancel API、跨线程/节点信号、流式停止和压缩结果失效；
6. 实现前端取消交互；
7. 完成单元、集成和关键竞态测试。

验收：取消后尚未调用的工具调用数为 0；旧压缩结果无法污染新上下文。

提交边界：`实现会话取消与工具前上下文压缩闭环`。

### 阶段 C：引导闭环

1. 建立 steer 事件和确定性排队规则；
2. 将旧 run 安全置为被替代，并创建 successor run；
3. 处理 partial 消息、已完成工具结果和压缩快照；
4. 实现前端引导输入、状态和取消后继 run；
5. 覆盖连续引导、引导与取消/完成并发。

验收：引导后旧工具意图不执行，且只形成一条合法后继链。

提交边界：`实现会话执行中引导闭环`。

### 阶段 D：会话分享与导入闭环

1. 定义版本化导出 schema、有效消息视图和敏感字段白名单；
2. 建立分享记录、MinIO 私有对象、过期/撤销/次数限制；
3. 实现受控下载、checksum 和流式处理；
4. 实现租户隔离的幂等复制导入与 ID 映射；
5. 实现分享、预览、导入前端；
6. 完成越权、篡改、失效消息泄漏、重复导入测试。

验收：接收者得到独立副本，不能继承原会话私有权限或敏感信息。

提交边界：`实现会话安全分享与复制导入闭环`。

### 阶段 E：分布式定时任务闭环

1. 基于服务器与现有部署方式确认调度组件；
2. 兼容演进现有调度表，增加业务唯一键、config hash/version、租约、fencing 和执行幂等；
3. 实现长间隔 Reconciler 的规范化 hash/upsert/禁用收敛；
4. 实现短间隔 Dispatcher 的到期抢占、事务边界、心跳、恢复和推进；
5. 部署调度组件并加入配置、健康检查和运维说明；
6. 实现前端定义、Cron 预览、启停、历史、重试和手动触发；
7. 完成多实例、Cron 修改、宕机恢复、misfire 和租户隔离测试。

验收：同一业务配置仅一个活动定义，同一计划触发点仅一个逻辑执行。

提交边界：可按“数据与执行后端”“部署与前端管理”拆成两个中文提交。

### 阶段 F：全链路验证、文档与完成审计

1. 运行所有相关 Maven 测试和前端构建；
2. 在不泄露凭据的前提下连接既有服务器中间件完成可行的集成验证；
3. 启动本地应用并执行可行的 API/E2E；
4. 对需求逐项核验证据，修复所有阻断项；
5. 更新接口、迁移、部署、测试和回滚文档；
6. 最终中文提交并确认工作区只剩用户原有改动。

验收：需求基线中的每项要求均有直接证据；无法执行的外部验证需有明确原因，但不得用窄测试替代广泛完成声明。

## 6. 当前执行段

当前阶段：**阶段 D：会话分享与导入闭环**。

本段计划：

1. 复核 SessionDomain、消息查询、ObjectStorageService、认证与现有前端会话事实源；
2. 在本计划先固定版本化导出格式、白名单字段、对象键、校验和与生命周期；
3. 新增分享记录与导入幂等记录迁移，所有读写带 tenantId/userId；
4. 扩展对象存储流式/删除/边界校验能力，MinIO bucket 继续保持私有；
5. 实现创建分享、受控下载/预览、撤销和复制导入 API；
6. 导出仅包含有效消息，导入重建新 session/message ID 与 sequence，绝不继承运行态和原权限；
7. 前端实现分享、复制链接、预览、导入和失效反馈；
8. 覆盖越权、过期、撤销、篡改、失效消息泄漏、重复导入和对象键穿越测试；
9. 通过 Maven 干净测试与前端构建后追加真实结果并中文提交。

## 7. 执行记录

### 2026-07-14：执行启动

- 已阅读并遵循 `codex.md`；
- 已确认项目中间件由远端 `CentOS-Server` 承载，本地不部署中间件；
- 已确认工作区存在用户日志和未跟踪目录，后续提交将精确暂存；
- 已创建本执行计划；
- 尚未修改业务代码或数据库。

### 2026-07-14：阶段 A 真实代码调研完成

#### 会话、上下文与工具链

- 当前没有业务级 turn/run/context revision；只有 session/message、ADK 瞬时 invocation 和每次调用的临时 ADK session；
- `ChatService` 在保存用户消息后直接调用 `InMemoryRunner.runAsync`，普通流式取消只 dispose 当前进程 Rx 订阅；
- `doOnCancel` 会调用 `saveAssistantErrorMessage`，将 partial 包装成有效 `[assistant_error]`，进入 Redis、后续模型上下文和压缩范围；
- 工作流 DAG 使用 common-pool `CompletableFuture`，当前无逐层、逐节点、逐循环取消检查；
- 自动压缩只在 assistant 完整落库后的 `onAssistantMessageSaved` 触发；已有 `compactSynchronously` 没有调用者，也没有等待/复用与保留最近 token 的完整策略；
- 压缩已有 task key 幂等、claim CAS 和 memory version CAS，但没有 context revision、覆盖 hash、快照血缘、取消/陈旧状态和 processing 租约；
- processing worker 宕机后任务可能永久卡住；压缩远程模型调用当前处于长事务中；
- 最终业务工具入口是 `ToolGateway.invoke -> dispatch`，但 ADK 前置点可使用 `Plugin.beforeToolCallback`；两层需要共享执行闸门；
- Spring AI 模型 options 中仍可能配置旧 `ToolCallback`，存在绕过 GatewayToolset/ToolGateway 的旁路，阶段 B 必须先封闭；
- tool log 仅在结束后落 success/failed，没有 started、runId、functionCallId 或幂等键，无法证明取消与副作用先后。

#### 分享、MinIO 与前端

- `ObjectStorageService` 只有 byte[] put/get，无流式、删除、stat、预签名；本地 fallback 的 normalize 后没有 root 边界校验；
- 分享对象 key 必须由服务端生成，MinIO bucket 保持私有；导出必须使用专用白名单 DTO，不能序列化 PO 后删字段；
- `SessionDomain.assertSessionAccess` 已有 tenant/user/session 复合校验，可作为导出归属校验；部分消息与 artifact DAO 查询只按 session/asset ID，不能直接用于分享；
- 前端真实入口是 `api/agent.ts`、`stores/chat.ts`、`ChatWorkspaceView.vue`；当前会话/消息以 localStorage 为事实源，无法承载跨用户复制导入；
- fetch 已接受 AbortSignal，但 store 没有 AbortController；发送中按钮只禁用；打字机和异步回调也没有 sessionId/runId 隔离，切换会话时可能把旧流写入新会话；
- Web 当前无 Vitest/Playwright 测试框架，只有 TypeScript 检查与 Vite 构建。

#### 定时任务与部署

- 当前仅有 `agent_schedule_config/task/execution` 三表、PO、DAO、Mapper 和 PlatformRepository 便利方法；没有领域服务、Trigger、Cron、派发、租约、API、前端或专项测试；
- 三张表远端当前为空，可兼容演进：config 继续作为用户配置，task 改为每配置唯一运行态，execution 表达计划触发点及尝试；
- Reconciler 应使用稳定业务键与规范化 SHA-256 hash 做原子 upsert；Dispatcher 使用短事务 claim、事务外 execute、带 fencing 的 CAS complete；
- 业务 Cron 由项目用 Spring `CronExpression` 计算，XXL-JOB 仅固定唤醒 Reconciler/Dispatcher，避免双主数据源；
- XXL-JOB 尚未部署，正式接入需验证其 core 与 Spring Boot 3.4.3/Java 17 兼容性，并建立与应用可回调的共享网络；
- 若组件接入暂时受阻，数据库租约保证正确性，两个 Spring 固定频率 Trigger 可作为同一 Domain Service 的可替换入口。

#### 修改前验证基线

- `mvn -DskipTests compile`：通过，但发现增量产物会保留旧包路径 class，不能作为最终证据；
- `npm run build`：通过，1895 个模块完成 production build；
- `mvn clean ... ContextAssemblerTest,ConversationMemoryServiceTest,ChatServiceTest test`：Context 5 项通过，ChatService 2 项失败；
- ChatService 失败是现有测试没有建立可信工具运行身份，报 `TOOL_CONTEXT_INVALID`，同时测试会连接真实 Nacos/MySQL/MCP/Kafka，属于既有集成测试隔离缺口；
- 干净构建证明源码包路径本身可编译，第一次测试中的 package mismatch 来自旧 target class；
- 测试运行还暴露 Java 25 与现有 Kafka SASL 客户端的 `Subject.getSubject` 兼容问题；项目目标 Java 为 17，后续验证需使用项目 Java 17 运行时或对测试禁用真实 Kafka listener；
- Maven POM 还存在既有 `ai-agent-scaffold-api` parent groupId/relativePath 警告，本任务暂不把无关 POM 清理混入首个功能提交。

#### 阶段 A 文件改动

- 新增需求规划稿：`docs/superpowers/specs/2026-07-14-stage-capabilities-roadmap-and-core-prompts.md`；
- 新增并持续维护本执行计划：`docs/superpowers/plans/2026-07-14-stage-capabilities-implementation.md`；
- 未修改业务代码、SQL、配置或前端源码。

### 2026-07-14：阶段 B 子闭环一——运行基座、取消失效与工具前压缩闸门

#### 实际代码改动

- 新增 `chat_run` 运行表及 Repository/DAO/Mapper，建立 run/turn、状态、版本、context revision、前驱/后继关系等基础字段；
- 增量扩展 session/message/compaction/snapshot/tool log：消息可绑定 run 并标记无效，压缩任务与快照携带 revision/hash/血缘；
- 新增 `RunControlService`、`ActiveRunRegistry`、`RunExecutionGate`和取消 API，取消时先迁移状态，再失效 run 消息、废弃重叠压缩任务、恢复安全摘要、推进 revision，事务提交后中断本机流；
- `ChatService` 流式 agent 路径创建并传播 runId/contextRevision，用户和助手消息绑定 run，取消后不再落库有效 `[assistant_error]`；
- 上下文查询与 token 统计统一排除无效消息；压缩在远程 LLM 前后均校验 revision/hash，避免旧结果激活；
- 新增工具前同步压缩能力，支持复用/领取已有任务；压缩完成时拦截当次工具调用并要求模型基于新上下文重新推理；
- 在 ADK `beforeToolCallback` 与 `ToolGateway.invoke` 外部副作用前建立双层闸门，同时从 ChatModel/Agent 模型 options 移除旧 Spring AI `ToolCallback` 执行旁路，保持 `GatewayToolset + ToolGateway` 唯一分发；
- 补齐了会话/压缩测试 Fake Repository 对新契约的实现，并新增取消与工具闸门单元测试。

#### 验证结果

- `JAVA_HOME=Java17 mvn -DskipTests clean compile`：通过，7 个 Reactor 模块全部 SUCCESS；
- `RunControlServiceTest, RunExecutionGateTest, MyBatisMapperLoadTest, ToolGatewayStdioTest`：5 项全部通过；
- 首次测试在系统 Java 25 下遇到现有 ByteBuddy 不支持 class version 69，按项目 Java 17 基线重跑后通过；
- MyBatis Mapper 全量 XML 装载测试通过，新增/扩展映射无语法错误；
- 尚未完成前端取消、非流式/工作流 run 覆盖、跨节点通知和 steer，因此阶段 B 仍保持进行中。

### 2026-07-15：阶段 B/C 完成——取消、引导、压缩与工具副作用闭环

#### 运行与消息一致性

- `RunControlService` 将用户消息写入/绑定、助手消息写入/完成、错误写入/失败均收敛为运行行锁内的短事务，取消或引导先取得锁时，晚到消息不会落成有效上下文；
- Agent、工作流、复合与非流式入口统一创建和传播 run；浏览器预分配受格式约束的 runId，因此非流式响应返回前也能发起服务端取消；
- SSE 在正文前返回 run 事件；工作流 DAG 在层、节点、循环和事件边界检查取消，Agent 流增加跨节点 250ms 轮询，本机注册表用于立即 dispose；
- 引导 API 将旧 run 迁移为 `STEER_REQUESTED -> SUPERSEDED`，失效旧消息与重叠压缩、推进 revision，并只创建一个 `CREATED` 后继；后继启动时校验会话、来源和 predecessor。

#### 上下文压缩与并发恢复

- 工具回调前执行 token 阈值检查并同步创建/复用压缩任务；压缩生效后拒绝旧工具意图，要求模型基于新 revision 重新推理；
- 压缩任务新增 owner/lease/fencing，worker 可回收过期 processing；取消/引导会将相关任务 stale，完成激活再次锁定 session 并校验任务围栏、revision、coverage hash 和当前版本；
- 远程摘要模型调用保持在事务外，最终激活使用独立短事务，避免长事务与取消互锁；没有消息但属于被取消 run 的压缩任务同样会失效。

#### 工具副作用边界

- 新增 `ToolDispatchAuthorizationService`，在同一短事务中锁定 run 并以 SHA-256 幂等键写入 `started` 日志；只有首次取得分发权才会离开事务调用外部工具；
- `tool_call_log` 实际接入 runId、functionCallId、idempotencyKey、startedAt，成功/失败使用 started CAS 完成；重复 functionCallId 复用历史结果或拒绝未知状态，不会二次产生外部消耗；
- `GatewayToolset + ToolGateway` 保持唯一分发入口，模型 options 中的旧 Spring AI ToolCallback 旁路已移除。

#### 前端取消与引导

- Store 增加 AbortController、request generation、sessionId/runId 三重隔离，取消先请求服务端再中断本地流，旧 SSE 与打字机回调无法污染新会话；
- 发送按钮在运行中切换为取消按钮，并提供引导输入；引导成功冻结旧输出、标记 superseded 并以服务端后继 run 启动新流，引导失败恢复原输出；
- 流式、非流式、Agent 与工作流均使用客户端可知 runId，切换会话/目标和新建会话前会收口当前运行。

#### 验证结果

- `JAVA_HOME=Java17 mvn -DskipTests clean compile`：7 个 Reactor 模块全部通过；
- 干净定向测试：`ConversationMemoryServiceTest, RunControlServiceTest, RunExecutionGateTest, ToolDispatchAuthorizationServiceTest, ToolGatewayStdioTest, MyBatisMapperLoadTest, WorkflowDagCompilerTest, WorkflowRuntimeCompilerTest` 共 19 项，0 失败、0 错误；
- `npm run build`：`vue-tsc --noEmit` 与 Vite production build 通过，1895 个模块完成构建；
- `git diff --check` 通过；既有 Maven parent relativePath 警告仍为任务外基线，不影响当前闭环；
- 端到端真实模型/数据库验证留到阶段 F，在增量 SQL 部署后统一执行。
