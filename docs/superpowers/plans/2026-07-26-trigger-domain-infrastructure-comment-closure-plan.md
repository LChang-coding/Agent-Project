# 触发器层、领域层、基建层逐行语义注释闭环计划

## 一、目标

在不改变运行逻辑、接口协议、数据库语义和依赖关系的前提下，为以下三个生产模块补齐高信息密度中文注释：

- `ai-agent-scaffold-trigger`
- `ai-agent-scaffold-domain`
- `ai-agent-scaffold-infrastructure`

完成后，读者应能直接从代码理解请求入口、领域决策、状态变化、外部副作用、持久化约束、异常后果、租户隔离、并发控制、取消闸门和链路追踪。

## 二、注释标准

1. 每个生产类说明职责、输入输出、所属层和明确边界。
2. 每个方法说明业务动作、参数语义、返回结果、主要副作用和异常后果。
3. 每段有效业务代码说明“为什么执行”和“执行后改变什么”，不机械复述 Java 语法。
4. 条件分支说明命中条件对应的业务后果。
5. 数据库、消息、网络、文件、模型、工具调用必须标明外部副作用和失败边界。
6. 并发代码必须说明线程归属、共享状态、取消检查、幂等或竞争控制。
7. 身份相关代码必须说明 `tenantId`、`userId`、`sessionId`、`runId`、`traceId` 的来源与隔离用途。
8. 不为 `package`、普通 `import`、花括号、空行、显然的 getter/setter 和链式调用的每个物理行制造无意义注释。
9. XML Mapper 对查询目的、租户条件、软删除、锁、乐观锁、幂等更新和状态推进进行语义注释。
10. 不修改业务行为；发现缺陷只记录到计划文档，未经授权不顺手修复。

## 三、范围

### 纳入

- 三个模块的 `src/main/java`
- 基建层 `src/main/resources` 中由本项目维护的 Mapper、配置和运行资源

### 排除

- `target`、构建产物、日志、缓存、对象存储数据
- 第三方源码和自动生成文件
- 测试源码；测试只作为编译与行为回归证据，本轮不做逐行注释
- 当前工作区已有且与本目标无关的修改和未跟踪文件

## 四、执行阶段

### 阶段 0：基线与覆盖清单

- 完整读取 `codex.md`。
- 识别 Spring Boot 版本、模块依赖和现有注释规范。
- 统计三个模块生产文件、代码行数、Java 类型和 Mapper 数量。
- 生成逐文件覆盖清单，记录初始 Git 状态。

验收：

- 范围清单可复核。
- 无关工作区修改有明确记录且后续不纳入提交。

### 阶段 1：触发器层

- Controller、Job、Listener、RPC/HTTP 适配入口。
- 请求身份、参数转换、响应、SSE、异常映射和 Trace 传播。

验收：

- 触发器层生产文件全部覆盖。
- 模块编译通过。
- 计划文档追加实际操作与验证结果。
- 使用中文提交信息完成本地提交。

### 阶段 2：领域层

- Agent、工作流、会话、运行控制、上下文压缩、RAG、工具、模型用量等领域链路。
- 重点解释状态机、不变量、取消检查、并行行为和副作用边界。

验收同阶段 1，并补充领域层相关单元测试。

### 阶段 3：基建层

- Repository、DAO、PO、MyBatis Mapper、外部客户端、消息和存储实现。
- 重点解释持久化映射、SQL 条件、锁、幂等、重试和基础设施失败边界。

验收同阶段 1，并补充 Mapper/XML 解析与基建层测试。

### 阶段 4：全量验收

- 全量编译。
- 可执行的单元测试和集成测试。
- 注释覆盖审计：逐文件检查类、方法、复杂分支、外部副作用和 SQL。
- 检查注释是否与代码一致、是否存在复述语法或误导性说明。
- 检查 Git diff，确认没有业务逻辑变化和无关文件混入。
- 追加最终操作记录并完成中文本地提交。

## 五、风险与控制

1. **范围巨大**：按层级和业务包拆分小批次，避免一次性产生不可审查 diff。
2. **注释污染可读性**：以语义覆盖为标准，不按物理行机械堆叠。
3. **误改逻辑**：只使用注释补丁；每批通过 `git diff --word-diff` 和编译检查。
4. **注释失真**：从调用链、实体和 SQL 交叉验证后再描述，不凭方法名猜测。
5. **已有脏工作区**：只暂存本计划和本目标修改，禁止 `git add .`。

## 六、操作记录

### 2026-07-26：计划建立

- 已明确三个模块的注释目标、范围、排除项、分层执行顺序和验收门禁。
- 已确定采用“每段有效业务代码均有准确语义解释”的标准，避免为花括号、导入和显然语句添加噪声。
- 已完整读取 `codex.md`，确认项目为 Java 17 + Spring Boot 模块化单体，注释必须服从现有分层、租户隔离、ToolGateway 和中文提交规范。
- 已建立生产代码基线：
  - 触发器层：23 个 Java 文件，3,719 行；其中 HTTP 17 个、Job 4 个、Listener 2 个。
  - 领域层：281 个 Java 文件，23,531 行；主要包括 RAG 79 个、Agent 47 个、Tool 35 个、Context 27 个、Workflow 18 个等。
  - 基建层：130 个 Java 文件，15,508 行；另有 36 个 MyBatis Mapper XML。
  - 总计：434 个 Java 文件、42,758 行 Java 生产代码、36 个 Mapper XML。
- 已记录初始脏工作区：运行日志、对象存储目录、既有 RAG 文档、设计文档、Skill 等均为用户已有或运行产生内容，本目标禁止暂存和提交这些文件。
- 已确认 `plan-orchestrate` 仅用于生成外部编排提示词，不适用于当前直接修改代码；后续使用本计划和本地验收门禁推进。
- 下一步生成触发器层逐文件清单，按 HTTP、Job、Listener 三批补齐注释。

### 2026-07-26：触发器层第一批执行计划

范围：

- `trigger/job` 下 3 个实现类和包说明。
- `trigger/listener/ContextCompactionConsumer` 和包说明。
- 小型 HTTP 入口：`AgentConfigController`、`RunControlController`、`RagKnowledgeBaseController`、`RagKnowledgeBaseDeletionController`、`RagRetrievalDebugController`。

本批重点：

- 说明 XXL-JOB 与本地兜底调度的互斥和触发边界。
- 说明 Kafka 压缩消费者的主消息、重试、死信和幂等处理语义。
- 说明控制器如何从可信上下文取得身份，以及为何只做协议转换、不承载领域规则。
- 补齐所有业务方法契约，并为关键参数转换、状态判断和异常后果增加短注释。

本批门禁：

- 只产生注释和计划文档修改，不改变可执行语句。
- `ai-agent-scaffold-trigger` 编译通过。
- 通过 diff 审计确认没有混入日志和现有无关文件。

### 2026-07-26：触发器层第一批操作记录

- 已完成 9 个实现类的语义注释：
  - 调度：`ScheduleLocalFallback`、`ScheduleXxlJobHandler`、`XxlJobConfiguration`。
  - 消费：`ContextCompactionConsumer`。
  - HTTP：`AgentConfigController`、`RunControlController`、`RagKnowledgeBaseController`、`RagKnowledgeBaseDeletionController`、`RagRetrievalDebugController`。
- 注释已经覆盖调度唤醒与业务账本边界、Kafka CAS 认领与线程上下文清理、JWT 身份来源、乐观锁、防误删、运行取消/引导、RAG 调试 Trace 关联和 DTO 转换边界。
- 使用零上下文 diff 审计新增行，除注释和计划记录外没有新增可执行语句。
- 执行 `mvn -pl ai-agent-scaffold-trigger -am -DskipTests compile`，结果 `BUILD SUCCESS`。
- Maven 仍报告 `ai-agent-scaffold-api` 的 `parent.relativePath` 指向不同 groupId，以及旧版 resources plugin 不识别 `propertiesEncoding`；这是既有构建告警，本批未修改。
- 下一批处理剩余 HTTP 控制器中的 RAG 配置/文档、资产、会话洞察和定时任务入口。

### 2026-07-26：触发器层第二批执行计划

范围：

- `RagRetrievalConfigurationController`
- `RagDocumentController`
- `AssetController`
- `SessionInsightController`
- `ScheduleController`

本批重点：

- 说明 RAG Profile、绑定和会话选择的权限与乐观锁边界。
- 说明文档上传、摄取任务、取消和重试的异步语义。
- 说明附件元数据与对象存储访问边界。
- 说明会话统计只读取数据库事实，不从浏览器缓存推断。
- 说明调度配置、任务和执行记录的协议映射。

门禁与第一批相同：只改注释、模块编译通过、diff 不混入业务逻辑和无关文件。

### 2026-07-26：触发器层第二批操作记录

- 已完成 `RagRetrievalConfigurationController`、`RagDocumentController`、`AssetController`、`SessionInsightController`、`ScheduleController` 的语义注释。
- 已明确：
  - 检索策略和绑定的强类型转换、管理员权限与乐观锁边界。
  - Multipart 暂存文件生命周期、JWT 身份注入、异步摄取/删除/取消/重试语义。
  - 资产归属先校验后下载、游标分页多取一条和 MIME 降级策略。
  - 上下文与 Token 统计来自服务端账本，不使用浏览器估值。
  - 手动触发只登记任务，Agent 不在 HTTP 线程执行；调度执行身份固定为当前 JWT 用户。
- 零上下文 diff 审计未发现新增可执行语句。
- 再次执行 `mvn -pl ai-agent-scaffold-trigger -am -DskipTests compile`，结果 `BUILD SUCCESS`。
- 下一批处理 `SessionController`、`SessionShareController`、`AuthController`、`WorkflowController`、`ToolController`、`AgentServiceController` 及三个包说明，完成触发器层整体闭环。

### 2026-07-26：触发器层第三批执行计划

范围：

- `SessionController`
- `SessionShareController`
- `AuthController`
- `WorkflowController`
- `ToolController`
- `AgentServiceController`
- `trigger/http`、`trigger/job`、`trigger/listener` 的包说明

本批重点：

- 会话创建、数据库历史读取、删除和 RAG 会话选择。
- 分享快照、工具权限差异提醒、导入和下载边界。
- 认证 Cookie/JWT、租户上下文与敏感信息响应边界。
- 工作流草稿、发布、运行和版本选择。
- ToolGateway 管理面与实际调用面的职责区分。
- Agent 普通/SSE 对话、附件绑定、runId/traceId 返回、取消监听及错误事件。

门禁：

- 审计 6 个大型控制器的全部方法契约和复杂分支。
- 三个包说明准确描述适配器边界。
- 编译与触发器相关测试通过后，追加操作记录并中文提交。

### 2026-07-26：触发器层第三批操作记录

- 已完成 6 个大型控制器和 3 个包说明的注释审计与补齐。
- `AgentServiceController` 已明确：
  - `message` 经 `ChatService` 保存后进入普通 ADK Agent 或工作流 DAG。
  - 同步链如何汇总 Event/文本，SSE 链如何依次返回 trace/session/run/message/citation_validation。
  - run 创建与取消句柄注册的竞态封闭、附件ID传递和最终引用快照读取时机。
- `SessionController`、`SessionShareController` 已补足游标分页、数据库历史、RAG 会话快照、引用原文、分享快照、工具权限预检和独立导入语义。
- `AuthController`、`WorkflowController`、`ToolController` 已明确认证领域边界、工作流图转换边界，以及 Tool 管理面与 ToolGateway 运行面的区分。
- 注释覆盖统计：触发器层 23 个 Java 文件均有文件/类说明；20 个实现类的每个声明方法均有对应 Javadoc，复杂分支另有原因注释。
- 执行 `mvn -pl ai-agent-scaffold-trigger -am -DskipTests clean compile`，23 个触发器源文件及上游模块从干净目录重新编译成功。
- 首次运行 28 个相关测试时，Java 25 超出 Byte Buddy 1.15.11 官方支持范围，18 个 Mockito inline mock 在测试初始化阶段报错；根因不是代码失败。
- 使用 `-Dnet.bytebuddy.experimental=true` 适配当前 Java 25 后重新执行相同 28 个测试：`Tests run: 28, Failures: 0, Errors: 0, Skipped: 0`，`BUILD SUCCESS`。
- 触发器层阶段完成。下一阶段进入领域层，先按包和类复杂度建立小批次覆盖清单。

### 2026-07-26：领域层第一批执行计划（调度与会话分享）

范围：

- `domain/schedule` 下 12 个 Java 文件。
- `domain/share` 下 9 个 Java 文件。

本批重点：

- 调度领域：解释配置同步、Cron 解析、一致性哈希、任务认领、分片执行、下一次触发时间推进、失败重试和本地/XXL-JOB 触发器边界。
- 会话分享：解释不可变导出快照、对象存储交付、工具依赖收集、接收方权限预检、导入后身份重建和分享有效期。
- 模型与端口：说明字段所代表的领域事实、状态值约束及仓储方法的原子性要求。
- 服务：为所有公开和私有业务方法补齐契约，并在非显然分支旁说明“为什么这样处理”。

门禁：

- 只新增或修订注释，不改变可执行语句、配置和数据结构。
- 21 个文件逐一通过类说明、方法说明和复杂分支覆盖审计。
- `ai-agent-scaffold-domain` 干净编译通过，并运行现有调度/分享相关测试。
- 将真实操作与测试结果追加到本节后，再以中文信息本地提交。

### 2026-07-26：领域层第一批操作记录（调度与会话分享）

- 已完成 `domain/schedule` 12 个文件和 `domain/share` 9 个文件，共 21 个 Java 文件的语义注释。
- 调度领域已明确：
  - 配置摘要只纳入执行语义字段，稳定业务键不随 Cron 变化，从而通过冲突更新避免重复任务。
  - 数据库短租约负责故障接管，递增栅栏令牌负责拒绝过期 Worker 回写。
  - 同一触发键先幂等登记执行；命中成功或终止记录时只推进游标，不再次调用 Agent。
  - `skip`、`fire_once_now`、`catch_up` 三类错过策略的触发点处理和下一时间计算边界。
  - 固化执行身份只在 Worker 线程临时恢复，并在成功、失败和中断路径统一清理。
- 会话分享领域已明确：
  - 原令牌只返回一次，数据库仅保存摘要；对象存储快照使用内容摘要验真。
  - 导出协议只允许会话白名单元数据及人机纯文本，不复制源租户、主键、附件地址和内部事件。
  - 分享额度、接收方幂等导入和新会话创建在分享行锁与事务内完成。
  - 工具依赖由服务端运行证据生成，接收方按工具标识与版本逐项预检；风险需显式确认。
  - 导入通过会话领域方法重建所有权和消息，不复用源消息身份。
- 覆盖审计：
  - 本批 79 个显式声明方法均有契约说明。
  - 零上下文 diff 未发现任何新增可执行语句；`git diff --check` 对本批路径通过。
- 执行 `mvn -pl ai-agent-scaffold-domain -am -DskipTests clean compile`，281 个领域层源文件干净编译成功。
- 使用 Java 25 兼容参数运行 `CronScheduleSupportTest`、`ScheduleDispatcherTest`、`ScheduleReconcilerTest`、`SessionShareServiceTest`：共 12 个测试，失败 0、错误 0、跳过 0，`BUILD SUCCESS`。
- 测试中的一次 `InterruptedException` 警告由 `ScheduleDispatcherTest` 主动中断 Worker 验证关闭语义产生，断言通过，不是产品异常。

### 2026-07-26：领域层第二批执行计划（运行控制与会话）

范围：

- `domain/run` 下 10 个 Java 文件。
- `domain/session` 下 7 个 Java 文件。

本批重点：

- 运行控制：解释 run 状态机、取消/引导的版本推进、活动运行互斥、工具调用前门禁、快照缓存与数据库事实的关系。
- 会话领域：解释会话所有权、数据库历史游标、消息序号、无效消息过滤、删除会话前的关联资源清理，以及工作流会话身份。
- 跨域一致性：明确“运行被取消”如何阻止后续工具副作用，以及被取消消息为何不能进入会话历史、上下文压缩和分享快照。

门禁：

- 17 个文件逐一审计类职责、字段语义、方法契约和状态分支。
- diff 中只允许注释和计划记录，不得改变运行状态机或查询条件。
- 领域层干净编译通过，并运行现有 run/session 相关测试。
- 追加真实操作记录后中文本地提交。

### 2026-07-26：领域层第三批操作记录（上下文管理）

- 已逐一审计 `domain/context` 27 个 Java 文件，补齐模型字段、策略参数、服务依赖、私有算法和关键并发分支注释。
- 上下文组装已明确：
  - 长期摘要、最近对话、附件、工作流上游和 RAG 统一进入候选集，再按类别优先级和模型总预算选择完整片段。
  - 有效历史只读取摘要覆盖点之后、本次可见序号之前的数据库消息；预览复用生产口径但不写缓存。
  - RAG 已开启但 Context Manager 或 RAG 预算不可用时显式失败，不允许静默退化。
- 压缩链路已明确：
  - 助手消息保存后只在未覆盖 Token 越过阈值时创建 MySQL 幂等任务，Kafka 仅负责即时通知。
  - 工具调用前重复执行阈值检测；当前线程抢到任务则同步压缩，其他消费者已领取时等待同一任务终态，压缩后要求模型重新推理。
  - 压缩输入用会话上下文版本和覆盖指纹锁定；指纹同时覆盖消息身份、序号、有效性、正文和附件文本。
  - 模型调用后、摘要激活前分别复核任务状态、栅栏、上下文版本、覆盖指纹和摘要基线版本。
  - 摘要 CAS 激活、任务完成和上下文版本推进在同一短事务内完成，模型调用不占用数据库事务。
- 取消/引导污染防护已明确：未完成重叠任务转为 stale；已完成摘要若覆盖失效消息则作废并恢复安全祖先；缓存仅在事务提交后清理。
- 零上下文 diff 审计确认除恢复多行枚举的临时编辑外，最终差异只包含注释；本批路径 `git diff --check` 通过。
- 执行 `mvn -pl ai-agent-scaffold-domain -am -DskipTests clean compile`，281 个领域层源文件干净编译成功。
- 使用 Java 25 兼容参数运行上下文、RAG/附件贡献、消费者 Trace、洞察和快照映射等 9 个测试类：共 24 个测试，失败 0、错误 0、跳过 0，`BUILD SUCCESS`。

### 2026-07-26：领域层第二批操作记录（运行控制与会话）

- 已逐一审计 `domain/run` 10 个文件和 `domain/session` 7 个文件，并补齐缺失的类、字段、私有方法及关键状态分支注释。
- 运行控制已明确：
  - run 的 `CREATED` 到终态状态语义、乐观锁版本和上下文版本各自解决的问题。
  - 引导会先失效旧运行消息和上下文派生状态，再创建继承同一 trace 与固化 RAG 策略的后继运行。
  - 取消会在同一事务链中失效消息、撤销压缩派生状态、推进上下文版本、取消运行中用量，再于提交后中断本机流。
  - 工具回调前先检查取消、再按阈值压缩、压缩后要求模型重推；真正外发工具前锁数据库进行最终授权。
  - 极短 TTL 快照只服务无副作用高频检查，外部工具副作用绝不依赖缓存；事务提交后才失效快照。
- 会话领域已明确：
  - 会话是所有权、运行目标、RAG 策略和上下文版本的聚合根。
  - 锁会话后分配消息序号，保证并发消息顺序唯一；所有历史、分享和上下文查询统一只读有效消息。
  - 删除会话前先取消全部活动运行，再撤销上下文派生状态和分享授权，最后软删除会话。
  - RAG 策略通过版本条件更新，防止多标签页或并发请求静默覆盖。
- 零上下文 diff 审计确认本批未新增或修改可执行语句；本批路径 `git diff --check` 通过。
- 执行 `mvn -pl ai-agent-scaffold-domain -am -DskipTests clean compile`，281 个领域层源文件干净编译成功。
- 使用 Java 25 兼容参数运行 `RunStateSnapshotCacheTest`、`RunControlServiceTest`、`RunExecutionGateTest`、`SessionControllerTest`、`SessionDomainTest`、`SessionLifecycleServiceTest`：共 25 个测试，失败 0、错误 0、跳过 0，`BUILD SUCCESS`。

### 2026-07-26：领域层第三批执行计划（上下文管理）

范围：

- `domain/context` 下 27 个 Java 文件。

本批重点：

- Token 计数、短期窗口选取、附件占用和 Context Manager 统计来源。
- 发送后与工具调用前的压缩门禁、任务幂等键、Kafka 异步状态机和同步工具前压缩。
- 压缩摘要覆盖范围、基线版本、消息有效性，以及取消/引导后对 pending、running、completed 压缩的回滚或失效。
- 运行上下文版本如何阻止旧提示词继续调用模型或工具。

门禁：

- 27 个文件逐一审计类、字段、端口、方法和复杂分支。
- 只改注释；零上下文 diff 不得出现可执行语句变化。
- 领域层干净编译通过，并运行现有 context/compaction 相关测试。
- 追加真实操作记录后中文本地提交。
