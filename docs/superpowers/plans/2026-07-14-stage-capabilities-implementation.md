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

当前阶段：**阶段 E：分布式定时任务闭环**。

本段计划：

1. 复核现有三张调度表、PO/DAO/Mapper、Spring 配置、Docker/服务器部署和前端导航；
2. 查询 XXL-JOB 官方当前稳定版与 Spring Boot 3/Java 17 接入边界，先将选型版本和部署拓扑写入本计划；
3. 设计增量表结构：业务唯一键、规范化 config hash/version、next fire、misfire、租约、fencing、trigger key 和执行幂等；
4. 实现长间隔 Reconciler，以配置为真源做 hash upsert、Cron 修改冲突更新、禁用收敛；
5. 实现短间隔 Dispatcher，以短事务 claim、事务外执行、fencing CAS 完成并推进下一触发时间；
6. 建立可替换唤醒入口：XXL-JOB handler 调用同一 Domain Service，本地 fixed-delay 仅作为显式降级；
7. 实现调度配置/启停/Cron 预览/历史/重试/手动触发 API 与前端管理页；
8. 编写多实例抢占、Cron 改动、幂等、misfire、租户隔离和 Mapper 测试；
9. 在服务器部署 XXL-JOB、执行迁移并完成健康/回调验证；若外部状态阻断，保留可重复部署脚本和直接证据并继续其余闭环；
10. 通过 Java 17 干净测试、前端构建和可行 E2E 后追加记录并按重大边界中文提交。

#### 当前子闭环二执行计划（部署与前端管理）

1. 复核现有 Compose 分层、环境变量模板、Web 路由/导航/API 封装和 UI 组件约定；
2. 增加固定 `3.4.0` 镜像的 XXL-JOB Admin 部署定义、独立 `xxl_job` 库初始化方式、健康检查、持久化与最小暴露端口；
3. 增加执行器/Admin 环境变量模板、两个固定唤醒任务的初始化/核对说明和可重复部署脚本，不在仓库写入服务器凭据；
4. 实现前端定时任务 API 类型与调用、管理路由和导航入口；
5. 实现配置创建/修改、Cron 预览、启停、手动触发、历史与失败重试交互，所有错误沿用现有登录与提示规范；
6. 执行 `docker compose config`、Java 调度回归、TypeScript/Vite production build；
7. 将真实变更、测试与仍待服务器执行项追加到本计划，精确暂存并以中文提交“部署与前端管理”闭环。

#### 当前服务器上线执行计划

1. 只读预检 `CentOS-Server`：Docker/Compose 版本、磁盘/内存、端口占用、现有容器、应用发布目录与数据库三表结构；
2. 确认服务器不存在冲突的 XXL-JOB/`xxl_job` 库，生成部署专用高熵密码/token，仅写入服务器权限受限 `.env`；
3. 对业务三张调度表做带时间戳的结构与数据备份，记录迁移前行数和字段；
4. 执行 `2026-07-15-distributed-scheduler.sql`，核验新增字段、唯一键和现有数据兼容结果；
5. 上传固定版本部署资产，在服务器执行 Compose 展开验证与 `deploy.sh`，核对 Admin HTTP、独立 MySQL、两条唤醒任务和样例任务清理；
6. 核对当前应用发布方式；只有确认可安全滚动发布本仓库新版本时，才配置执行器并验证自动注册，不覆盖未知人工进程；
7. 使用真实 JWT 完成定时任务 API 创建/预览/启停/立即触发/历史 E2E，并核验业务 task/execution 数据推进；
8. 失败时按检查点停止唤醒、恢复备份或保留数据卷，记录直接证据；成功后追加计划、执行最终回归并中文提交上线记录。

#### 阶段 E 已确认设计（修改前）

- 组件选择 XXL-JOB `3.4.0`：官方 GitHub 于 2026-04-05 发布该稳定版；使用固定版本镜像和 `xxl-job-core`，禁止 floating latest；
- XXL-JOB Admin 独立使用 `xxl_job` 数据库，仅部署管理/唤醒能力；业务配置、下一触发时间、租约和执行历史仍以项目库为唯一真源；
- 两个 Handler 固定命名 `scheduleReconcileJobHandler` 与 `scheduleDispatchJobHandler`，Admin 分别配置长间隔和短间隔 Cron；两者只调用同一 Domain Service，不承载业务状态；
- `agent_schedule_config` 作为用户配置源，补齐 taskType/payload、runAsRole、misfire/maxRetry、configHash/version/reconciledAt；
- `agent_schedule_task` 改为“一配置一运行态”，稳定 businessKey/configId 唯一，保存 Cron 快照、下一/上次计划时间、租约、fencing、retryAt 和 rowVersion；Reconciler 使用规范化 SHA-256 + `ON DUPLICATE KEY UPDATE`，Cron 改动只推进版本并重算 nextFire，不新增重复任务；
- `agent_schedule_execution` 表达“一计划触发点一逻辑执行”，triggerKey 唯一，保存 configId/plannedTime/attempt/fencing/result；同一触发点重试更新原记录，不产生第二个逻辑执行；
- Dispatcher 使用单条条件更新抢占到期 task，事务立即提交；业务执行在事务外并定时续租；完成时以 taskId + leaseOwner + fencingToken CAS，旧 worker 无权推进下一次时间；
- 默认 misfire 为 `fire_once_now`：只补一次后从当前时间计算下一触发；支持 `skip` 与有上限的 `catch_up`；失败按指数退避到 maxRetries，超过后记录 dead 并推进下一 Cron；
- 首版任务类型为 `agent_prompt`，API 强制 runAsUserId 为当前 JWT 用户并固化当前 roleCode，payload 只允许 message；后续 workflow 类型通过 Handler 扩展，不在 Dispatcher 写分支；
- Spring fixed-delay 唤醒器仅在 `ai.scheduler.local-fallback-enabled=true` 时启用，生产默认关闭，防止与 XXL-JOB 的两个 Handler 重复唤醒；即使误开，多实例数据库租约仍保证单 task 抢占；
- 官方依据：`https://github.com/xuxueli/xxl-job/releases/tag/v3.4.0` 与 `https://www.xuxueli.com/xxl-job/index.html`。

#### 阶段 D 已确认设计（修改前）

- 导出媒体类型为 `application/vnd.ai-agent.chat-session+json`，`schemaVersion=chat-session-export/v1`；顶层只包含导出时间、会话标题/Agent 展示信息和按序的有效消息；
- 消息白名单仅为 `sequenceNo/role/contentType/content/createdAt`，禁止输出 tenantId、userId、sessionId、messageId、runId、traceId、失效原因、工具凭据和上下文快照；
- `chat_session_share` 保存 owner、source session、token SHA-256、私有 bucket/objectKey、内容 SHA-256/size、状态、有效期和下载上限；数据库只保存 token hash，分享 URL 中的原 token 只返回创建者一次；
- `chat_session_import` 以 `tenantId + userId + shareId` 唯一，保存来源 checksum 和新 sessionId；同一接收者重复导入返回同一副本；
- MinIO 对象键由服务端生成 `chat-shares/yyyy/MM/{shareId}.json`，bucket 保持私有；下载、预览和导入均先验证 token、状态、过期、次数与 checksum，不向浏览器暴露 MinIO 凭据；
- 创建分享先生成不可变导出字节并写对象，再写授权记录；数据库写失败时尽力删除对象；撤销立即阻断服务端读取并删除对象；
- 导入在对象读取与 checksum 校验后进入短事务，锁分享记录、再次检查授权、创建新 session、重建新 messageId/sequence/parent 映射并写幂等记录；不复制 run、压缩、工具日志或原租户权限；
- 下载和导入链接要求现有 JWT 登录，由 Web API 携带 Bearer token 访问服务端；跨用户允许依赖高熵分享 token，不依赖原会话 tenant/user 权限；
- 单文件首版限制 8 MiB、最多 10,000 条消息、单消息最多 256 KiB，避免 byte[] 现有基建被滥用；对象存储同时补齐删除、大小检查与本地路径 root 边界校验。

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

### 2026-07-15：阶段 D 完成——会话安全分享与复制导入

#### 数据与协议

- 新增 `chat_session_share` 与 `chat_session_import` 增量表；分享记录保存 token hash、私有对象位置、checksum、有效期、状态和读取上限，导入按 `shareId + recipientScopeKey` 唯一；
- 固定 `chat-session-export/v1` 白名单协议，导出仅包含会话标题/Agent 展示字段以及有效文本消息的 sequence、role、content、createdAt；
- 服务端生成 256-bit URL-safe token，数据库不保存原令牌；对象键固定在 `chat-shares/yyyy/MM/{shareId}.json`，浏览器只访问带令牌的服务端路由。

#### 后端闭环

- 新增创建、预览、受控下载、复制导入和撤销 API；所有创建者操作使用可信 TenantContext，跨用户读取必须同时具备登录身份与高熵 token；
- 创建分享从 `SessionDomain` 查询有效消息，序列化不可变 JSON、写入私有 MinIO/本地对象后落授权记录，数据库失败会尽力清理孤儿对象；
- 下载和导入均执行状态、过期、次数、8 MiB 上限和 SHA-256 校验；读取次数使用条件更新原子消费；
- 导入先在事务外读取对象，随后在短事务中锁分享、检查接收者幂等记录、创建独立 session、重建全部 messageId/sequence 并写导入记录；不复制 run、trace、压缩、工具日志或原权限；
- 对象存储补齐限量读取与删除，本地 fallback 使用绝对规范化 root 边界检查，拒绝 `../` 越界。

#### 前端闭环

- 聊天工作台增加分享按钮，生成链接后自动尝试复制并允许手动复制；
- 新增 `/share/:token` 预览页，支持下载服务端校验后的 JSON、复制导入和失效反馈；
- 导入成功后把服务端返回的独立会话与消息写入当前用户隔离的本地展示索引，并跳转打开该会话。

#### 验证结果

- Java 17 干净编译通过；分享、对象存储、会话、Mapper、取消/压缩/工具回归共 20 项测试最终全部通过；
- 白名单测试直接断言导出 JSON 不含 tenantId、userId、messageId、runId、traceId；重复导入测试断言不再次消费读取次数；路径测试断言本地对象键越界被拒绝；
- MyBatis 全量 Mapper 装载包含新增分享/导入 XML 并通过；
- `npm run build` 通过，`vue-tsc` 与 Vite 完成 1899 个模块；
- 首次 20 项测试出现 1 个 mock 未声明 insert 成功导致的防御校验错误，补齐真实返回后原集合 20 项 0 失败、0 错误；
- 真正 MinIO/MySQL 跨用户 API E2E 待阶段 F 部署迁移后一并验证。

### 2026-07-15：阶段 E 子闭环一——分布式调度数据与执行后端

#### 数据结构与一致性

- 新增 `2026-07-15-distributed-scheduler.sql` 并同步全量建库脚本：配置表增加 taskType/payload、固化角色、misfire、重试与 hash/version；任务表成为 configId/businessKey 双唯一的单配置运行态；执行表以 triggerKey 唯一表达单计划触发点；
- Reconciler 对 Cron、时区、白名单 payload、执行身份、misfire、重试与启停状态生成规范化 SHA-256，通过 MySQL null-safe `ON DUPLICATE KEY UPDATE` 冲突更新；Cron 修改保持 taskId 不变，只推进 configVersion 并重算 nextFire；
- Dispatcher 通过单条条件更新抢占 ready/retry 或租约过期 running，领取时递增 fencingToken；长业务执行在事务外，心跳续租，结果以 execution/task 双栅栏在同一短事务提交；
- 同一触发点重试复用原 execution 并增加 attemptNo；旧 worker 的完成更新因 fencingToken 不匹配被拒绝；数据库层保证一个逻辑执行记录，外部 Agent/工具副作用仍遵循可恢复的至少一次语义，依赖现有 run/tool 幂等闸门进一步约束；
- 支持 `fire_once_now`、`skip` 与受 dispatch batch 上限约束的 `catch_up`，失败指数退避，超过 maxRetries 将 execution 标记 dead 并推进下一 Cron，避免单次失败永久阻塞配置。

#### 接口与唤醒入口

- 新增配置创建/修改、列表、启停、Cron 预览、执行历史、手动触发与重试 API；runAsUser/role 只能来自当前 `TenantContext`，payload 只允许一个非空 message；
- 首个 `agent_prompt` Handler 复用 `IChatService`，执行前由 Dispatcher 绑定持久化 tenant/user/role，结束后必定清理线程上下文；
- 锁定 XXL-JOB `3.4.0`，增加 `scheduleReconcileJobHandler` 与 `scheduleDispatchJobHandler`；Admin 仅作唤醒，业务数据库仍是唯一真源；
- XXL 执行器默认关闭，只有 `XXL_JOB_EXECUTOR_ENABLED=true` 才注册；本地 fixed-delay 降级同样默认关闭，且调用与 XXL 相同的 Domain Service。

#### 验证结果

- Java 17 `mvn -DskipTests clean compile`：7 个 Reactor 模块全部通过；
- `CronScheduleSupportTest, ScheduleReconcilerTest, ScheduleDispatcherTest, MyBatisMapperLoadTest` 共 5 项，0 失败、0 错误；覆盖时区转 UTC、非法时区、稳定 taskId/hash 版本推进、可信执行身份、栅栏提交和全部 Mapper XML 装载；
- 首次专项测试仅有一处测试期望把上海当天 09:00 错写为次日，修正 UTC 断言后原集合通过；同时补强了过期 running 接管与 NULL hash 首次对账；
- 依赖树确认 Spring 保持 `6.2.3`、Netty 保持项目既有 `4.1.118.Final`，XXL-JOB core 未把主框架升级到其父 POM 的 Spring Boot 4；
- 既有父 POM 坐标警告仍存在，因此 Trigger 对 XXL 版本显式锁定 `3.4.0`；未在本闭环扩大修改全项目 Maven 坐标。

#### 后续项

- 本子闭环尚未包含 Web 管理页、XXL-JOB Admin Compose/服务器部署、业务表迁移和真实 API E2E；这些进入阶段 E 子闭环二与阶段 F，不将当前后端验证冒充上线完成。

### 2026-07-15：阶段 E 子闭环二——部署资产与前端管理

#### XXL-JOB 部署资产

- 新增独立 `docs/dev-ops/xxl-job` 部署单元，固定 `xuxueli/xxl-job-admin:3.4.0` 与 MySQL `8.0.32`，Admin 数据使用独立命名卷和内部网络，不复用业务数据库；
- `.env.example` 只保留占位值，`.gitignore` 明确排除任意真实 `.env`；Admin 默认只绑定 `127.0.0.1`，数据库不暴露宿主端口，执行器 token、数据库密码和后台密码全部要求显式安全值；
- `deploy.sh` 下载固定 tag 的官方初始化 SQL 并校验 SHA-256，初始化后立即修改默认 Admin 密码、移除官方示例任务，再运行本项目幂等 bootstrap；
- `bootstrap-business-jobs.sql` 以 appName + handler 判重并冲突更新，创建每五分钟的 Reconciler 与每五秒的 Dispatcher，重复部署不会新增第二份唤醒配置；
- README 记录应用执行器变量、9999 端口网络边界、上线顺序、暂停与回滚路径；真实凭据没有进入仓库。

#### 前端管理闭环

- 新增 `/schedules` 控制台路由与导航，沿用现有 SectionHeader、card、table、badge 和鉴权请求封装；
- 新增定时任务 API/类型，覆盖列表、创建、更新、启停、Cron 预览、历史、立即触发和失败重触发；
- 管理页支持 Agent 选择、消息白名单、六段式 Cron、时区、misfire、重试次数与启用状态；预览同时显示后端 UTC 值和浏览器本地时间；
- 配置表展示稳定版本与对账时间，执行表展示计划时间、attempt、状态、耗时和错误；失败/dead 可重新排入派发；页面明确数据库逻辑幂等与外部至少一次语义。

#### 验证结果

- `bash -n docs/dev-ops/xxl-job/deploy.sh`：通过；部署脚本无 shell 语法错误；
- `npm run build`：`vue-tsc --noEmit` 与 Vite production build 通过，共转换 1903 个模块；
- Java 17 调度回归 `CronScheduleSupportTest, ScheduleReconcilerTest, ScheduleDispatcherTest, MyBatisMapperLoadTest` 共 5 项再次全部通过；
- 任务文件范围 `git diff --check` 通过；运行日志中的既有尾随空格不属于本任务且不会暂存；
- 本机没有 Docker CLI，`docker compose config` 返回 `command not found`，因此 Compose 展开、镜像启动和 HTTP 健康检查必须在下一服务器部署段补证，当前不声明组件已经上线。

#### 下一执行段

- 将本闭环本地提交后，进入服务器预检：只读确认 Docker/Compose、端口、磁盘和现有 MySQL 表版本；再备份业务表、执行迁移、部署 XXL-JOB、配置应用执行器并完成真实 API/前端 E2E。

### 2026-07-15：阶段 E 子闭环三——服务器数据迁移与 XXL-JOB 上线

#### 业务库迁移

- 只读预检确认服务器为 Docker `29.6.1` / Compose `5.2.0`，端口 `8080` 与 `9999` 未被占用，且原环境不存在 XXL-JOB 容器或 `xxl_job` 库；
- 迁移前对业务库三张调度表生成结构与数据备份，备份位于服务器 `/root/backups/ai-agent-scheduler/20260714-151510`，同目录保存 SHA-256 校验文件；
- 执行 `2026-07-15-distributed-scheduler.sql` 成功：配置/任务/执行表字段数分别从 16/12/15 提升为 24/26/22，`uk_schedule_task_config`、`uk_schedule_task_business`、`uk_schedule_execution_trigger` 均存在；
- 三张表迁移前后都是 0 行，没有发生旧任务重复、丢失或意外触发。

#### XXL-JOB 服务器部署

- 部署目录为 `/opt/ai-agent-scheduler/xxl-job`，真实 `.env` 仅存服务器且权限为 `600`，仓库和执行输出未暴露密码或 access token；
- 首次启动在 `source .env` 处发现带空格的 JVM 参数未加引号，尚未创建容器即安全停止；已修正 `.env.example` 和服务器 `.env`，`bash -n` 与 `docker compose config --quiet` 通过后重试成功；
- `xxl-job-mysql` 运行且健康，数据库端口只在 Compose 内部网络；`xxl-job-admin` 运行且仅映射 `127.0.0.1:8080`，两容器重启策略均为 `unless-stopped`；
- Admin 根路径返回预期 `302` 登录跳转，使用服务器安全密码调用 3.4.0 的 `/auth/doLogin` 返回业务码 `200`，带会话访问任务页同样为 HTTP `200`；
- 官方库共 8 张表，当前只有 1 个 `ai-agent-scheduler` 执行器组和 2 个业务唤醒任务，官方样例组/任务数均为 0；
- 由于服务器当前没有运行业务应用，两个唤醒任务保持 `trigger_status=0`；bootstrap 已改为首次默认停用且重复部署不强制启用，避免在执行器未注册时产生持续失败与额外消耗；
- 最近 10 分钟 Admin/MySQL 日志未匹配到 `exception`/`fatal`/`access denied`/`failed`。

#### 当前安全边界与下一步

- 服务器上已有的 `0.0.0.0:3306` 属于原业务 MySQL `mysqld`，不是新部署的 XXL-JOB MySQL；本次没有改动原服务暴露策略；
- XXL-JOB 管理页尚未对公网开放，需要后续通过 Nginx/TLS 与访问控制发布；这不影响 Admin 对执行器的内网调度；
- 下一执行段先审计业务应用安全发布方式，再部署/启动执行器；只有 Admin 确认注册地址可回调后才启用两条唤醒任务，随后使用真实 API 完成配置、对账、派发和历史 E2E。

#### 执行器与调度 E2E 执行计划

1. 遵循 `codex.md` “本地运行项目代码、服务器只部署中间件”的边界，在本地构建和运行业务应用，不上传包含本地 `secrets.properties` 的 JAR；
2. 使用临时 SSH 本地转发让应用访问只绑定服务器 loopback 的 Admin；使用临时反向转发与只在 XXL Docker 内网网关监听的 TCP relay，让 Admin 回调本地 `9999`，不向公网开放执行器；
3. 使用服务器 `.env` 中现有 access token 通过标准输入传给本地进程，不打印、不写入仓库；启动时禁用 local fallback，确保触发来自 XXL-JOB；
4. 先验证应用启动、执行器自动注册和 Admin 对 handler 的手动调用，再启用 Reconciler/Dispatcher 两条唤醒任务；
5. 使用新建的 E2E 用户/JWT 调用定时任务 API，完成 Cron 预览、创建、对账、立即触发和历史检查；优先使用不产生外部模型费用的可控 handler 验证派发基建，如现有业务类型只有 `agent_prompt`，则仅执行一次最小真实请求并核对 run/tool 幂等边界；
6. 验收后立即停用两条唤醒任务、清理 E2E 数据、结束本地应用/隧道/relay，再证明服务器无 `9999` 公网监听；
7. 执行 Java 综合回归、前端 production build、Compose/脚本检查和服务器最终健康检查，将真实证据追加到本文档后中文提交。

#### E2E 发现的存量迁移缺口与修复计划

- 首次真实 E2E 已完成注册、JWT、Cron 预览、配置创建/启用/立即触发、XXL Dispatcher 回调和执行栅栏，但 `agent_prompt` 在创建会话时因服务器 `chat_session` 缺少 `context_revision` 失败；
- Dispatcher 已将本次记录标为 `dead`，未发起模型或工具调用；E2E 配置和 task 已立即强制停用并清除租约；
- 修复前先对照 `2026-07-11-context-manager.sql`、`2026-07-14-chat-run-control.sql`、`2026-07-15-chat-session-share.sql` 与服务器实际 schema，只执行缺失且可幂等的增量；
- 对即将修改的会话、消息、上下文、run、工具和分享表做带校验文件的二次备份，执行后核对字段/索引与行数；
- 迁移后新建第二个临时 E2E 用户与调度，重复同一条完整路径；成功后删除两次 E2E 租户的所有数据，失败则保留匿名化错误证据后再清理。

#### 执行器与真实 E2E 结果

- 本地 Java 17 执行 `mvn -DskipTests package` 成功，7 个 Reactor 模块均为 SUCCESS；使用新 JAR 在 `18091` 启动独立实例，未停止用户原有 IntelliJ `8091` 进程；
- 临时 SSH 路径为本地 `127.0.0.1:18080 -> 服务器 127.0.0.1:8080`、服务器 `127.0.0.1:19999 -> 本地 127.0.0.1:9999`，relay 只绑定 XXL Docker 网关 `172.21.0.1:9999`；全程无公网 `9999` 监听；
- 执行器成功向 Admin 注册 `http://172.21.0.1:9999`，两个 handler 均被执行器加载；Admin 手动唤醒 Reconciler 的 trigger/handle 码都为 200，首次证明 Admin -> relay -> SSH -> 本地 handler 回调闭环；
- 首次 API E2E 已完成注册、登录、Cron 预览、创建、列表、启用、立即触发和 Dispatcher 唤醒，但因 `chat_session.context_revision` 缺失记录为 `dead`，在创建会话阶段停止，未调用模型/工具；
- 对会话、消息、上下文、工具日志 5 张存量表生成第二份备份 `/root/backups/ai-agent-scheduler/20260714-154100-pre-run-share`，大小约 8.8 MB 且保存 SHA-256；
- 核对后仅补执行缺失的 `2026-07-14-chat-run-control.sql` 和 `2026-07-15-chat-session-share.sql`；`chat_run`、两张分享表和 18 个必需运行控制字段全部存在，30 条旧会话以 revision 0 兼容保留；
- 迁移后重跑同一条真实路径，execution 为 `success`、耗时 5969 ms、模型最终返回 `OK`；输入 51 token、输出 1 token，本次无工具调用；
- 终态前共核对到 2 条 execution（1 success/1 dead）、1 个会话、2 条有效消息、1 个 run、0 条工具调用；随后按租户删除全部 E2E 数据，租户/用户/配置/会话/run 均为 0 残留；
- 本地 E2E 应用优雅退出时 Admin 返回 registry-remove 200；relay、隧道、`18080/18091/9999/19999` 临时监听和 3 条 XXL 测试日志均已清理；
- 最终 Admin HTTP 为预期 302，Admin/MySQL 容器保持运行且 MySQL healthy，2 条业务唤醒任务 enabled 数仍为 0，未在本地业务应用停止后留下无执行器调度。

### 2026-07-15：阶段 F——最终综合回归与交付审计

#### 综合验证结果

- Java 17 最终回归覆盖 JWT/Trace、上下文组装与压缩、run 取消/引导、工具授权与 stdio MCP、会话分享/存储、调度对账/派发、工作流与全部 Mapper：23 个测试类共 42 项，0 失败、0 错误、0 跳过；
- 首次 Reactor 测试命令因前置 API 模块不存在指定测试而被 Surefire 拒绝，未执行测试代码；增加 `-Dsurefire.failIfNoSpecifiedTests=false` 后原测试集合全部通过；
- 前端 `npm run build` 通过：`vue-tsc --noEmit` 与 Vite production build 完成 1903 个模块，定时任务、分享与聊天页面均进入生产产物；
- `bash -n docs/dev-ops/xxl-job/deploy.sh` 通过；服务器 `docker compose config --quiet` 通过，Admin 为 HTTP 302，XXL MySQL healthy，`9999/19999` 临时端口不存在；
- 全仓 `git diff --check` 只报告用户既有 4 个运行日志的尾随空格；任务文件排除运行日志后无格式错误，日志与 `data-alloy/`、`docs/design-questions/`、`docs/design-viz-methodology.md`、`skills/` 均未修改/未暂存；
- 已知 Maven `ai-agent-scaffold-api` parent `relativePath` 坐标告警仍是项目原有基线，不影响本轮 7 模块 package 与 42 项回归，本阶段未扩大修改 Maven 坐标。

### 2026-07-15：XXL-JOB 管理凭据文档化计划

1. 从服务器部署目录的权限受限 `.env` 读取当前 Admin 密码，不使用历史默认值；
2. 核对 Admin 地址、固定管理员账号和密码实际可登录；
3. 将 XXL-JOB 地址/账号/密码追加到 `codex.md` 的服务器中间件凭据区；
4. 将实际变更和验证结果追加到本节；`codex.md` 保持本机排除，仅精确暂存不含密码的执行记录并使用中文提交。

#### 实际执行结果

- 已从服务器 `/opt/ai-agent-scheduler/xxl-job/.env` 读取当前 `XXL_JOB_ADMIN_PASSWORD`，未使用官方默认密码或历史推测值；
- `codex.md` 已增加 XXL-JOB 中间件条目、`admin` 账号与当前密码，并标注 Admin 仅在服务器 `127.0.0.1:8080/xxl-job-admin` 监听，需通过 SSH 隧道访问；
- 使用该凭据调用 `/auth/doLogin` 返回 HTTP 200/业务码 200，带登录会话访问任务页返回 HTTP 200；
- `codex.md` 已由 `.git/info/exclude` 标记为本机文件，符合文件内“凭据禁止提交 Git”的约束；因此仅精确提交不含密码的本执行记录，不暂存 `codex.md` 和用户既有运行日志/未跟踪目录。
