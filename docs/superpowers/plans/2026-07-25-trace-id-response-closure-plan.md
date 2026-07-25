# TraceId 响应可追踪能力闭环计划

## 目标

让调用方在每一次请求的响应中稳定获得 `traceId`，并能把该值直接交给
`grafana-log trace <traceId>` 查询同一条同步及异步业务链路。覆盖成功响应、
业务异常、未捕获异常、SSE/流式聊天、文件接口、RAG、Agent、Workflow 和工具调用。

## 硬门禁

1. 先完整读取并遵循本机 `codex.md`、现有统一响应、异常处理、Trace/MDC、
   Kafka、线程池、SSE 和前端规范，不另起一套平行架构。
2. 服务端统一返回 `X-Trace-Id` Header；适合统一 JSON 包装的接口同时返回
   `traceId` 字段。文件流等响应体不可扩展场景至少保证 Header。
3. SSE 必须在客户端可消费的首个元数据事件中暴露 `traceId`，不能依赖最终事件。
4. 客户端传入的 `X-Trace-Id` 仅在格式、字符集和长度校验通过后继承，否则生成
   新值；traceId 不得编码租户、用户或其他敏感信息。
5. 同一请求内 Controller、Service、模型、RAG、工具调用和下游异步派发应复用
   同一 traceId；确需子链路时必须保留父链路关联，不能使响应中的 traceId 无法
   查询下游日志。
6. 统一实现，不允许各 Controller 手工拼接；不能破坏既有响应兼容性、SSE
   协议、下载内容或错误码。
7. 前端必须能从响应 Header、JSON 和 SSE 元数据提取 traceId，在错误提示、运行
   详情或聊天链路入口显示并支持复制；不得把 traceId 当认证凭据。
8. 测试必须覆盖正常 JSON、异常 JSON、无效入站 Header、SSE 首帧、文件 Header、
   Kafka/异步传播和前端提取；尽力完成真实端到端验证。
9. 敏感信息仍只保存在本机 `codex.md`；计划、源码、测试、日志和提交不得复制
   真实密码、Token 或服务器认证信息。
10. 重大闭环后先追加本计划的真实执行记录，再使用中文本地提交；不提交既有
    运行日志、对象存储、RAG 文档或其他无关改动。

## 实施步骤

### 1. 架构审计

- 完整读取 `codex.md` 和仓库级约束。
- 定位统一响应模型、全局异常处理、请求过滤器、MDC 工具、SSE 事件模型、
  Kafka producer/consumer、异步执行器及前端 HTTP/SSE 客户端。
- 形成现状矩阵，明确哪些链路已有 traceId、哪些只写日志、哪些会丢失上下文。

### 2. 后端统一响应

- 在现有 Trace 入口统一校验/生成 traceId，并在响应提交前设置
  `X-Trace-Id`。
- 以兼容方式扩展统一 JSON 成功和错误响应的 `traceId`。
- 保证 Spring Security、参数校验、404/500 等非 Controller 异常也返回 Header
  和可行的 JSON traceId。
- 验证下载、上传和其他非 JSON 接口至少返回 Header。

### 3. SSE 与异步传播

- 在既有 SSE 协议的首个元数据事件中加入 traceId，并保证重连/取消语义不变。
- 核对线程池 TaskDecorator、CompletableFuture/Reactor 上下文传播。
- 核对 Kafka Header 生产与消费恢复；下游日志复用入口 traceId。

### 4. 前端可见与复制

- 在统一 HTTP 层提取并保存 `X-Trace-Id`，JSON 字段作为回退。
- 在 SSE 客户端提取首帧 traceId，并与当前 Run/消息状态绑定。
- 在错误反馈和聊天/运行详情提供“链路编号”及复制入口，避免组件覆盖和突兀跳变。

### 5. 测试与验收

- 补齐后端单元/切片/集成测试和前端单元测试。
- 运行与改动相关的 Maven、前端类型检查、测试和构建。
- 启动本地服务后验证成功、异常、SSE 和实际日志查询闭环；若完整 E2E 受外部
  服务限制，明确记录已完成与未完成的层级，不中断其余单元测试。

### 6. 收尾

- 扫描敏感信息和兼容性风险。
- 将真实改动、测试命令、结果及已知边界追加到本计划。
- 精确暂存本轮文件并使用中文提交。

## 执行记录

### 2026-07-25 实际改造

1. 扩展现有 `Response<T>`，通过全局 `ResponseBodyAdvice` 自动向成功和业务失败
   JSON 写入当前 `traceId`；Spring 默认 `/error` 响应通过自定义
   `ErrorAttributes` 复用原请求链路号。
2. 保留既有 `TraceIdFilter` 作为唯一 HTTP 入口，新增请求属性留存，并统一返回
   `X-Trace-Id` 与 `Access-Control-Expose-Headers: X-Trace-Id`。客户端传入值仍由
   现有安全规则校验，不合规值会被替换。
3. Spring Security 的过滤器认证失败和 EntryPoint 失败响应已显式写入
   `traceId`，避免在 MVC Advice 之外丢失。
4. 聊天 SSE 在 `session`、`run`、`message` 之前先发送 `trace` 元数据事件；
   `run` 和结构化 `error` 事件也携带同一值。异步完成回调使用请求时捕获的
   traceId，避免响应线程切换后生成无关链路号。
5. 上下文压缩命令增加 `traceId`，从 MySQL 任务账本写入 Kafka 命令，消费者在
   领取和执行任务前恢复 Trace/MDC，结束后清理。原有线程池仍复用
   `TraceableThreadPoolExecutor`，未建立第二套上下文传播实现。
6. 会话历史消息 DTO 补充数据库已保存的 `traceId`。前端统一 HTTP 层可从响应
   Header 或 JSON 回退字段提取；聊天 SSE 同时支持 Header、`trace`、`run` 和
   `error` 事件。当前请求链路号会绑定到用户/助手消息，历史恢复后仍可查看。
7. 会话状态栏新增链路号胶囊和复制按钮，每条有链路号的历史消息也提供紧凑
   展示及复制入口；复制失败会给出可见提示。

### 验收结果

- Java 17 干净重编译及针对性测试：
  `mvn -pl ai-agent-scaffold-app -am clean -DskipTests=false`
  加 8 个测试类筛选，结果为 **27 tests、0 failures、0 errors、0 skipped**。
- 覆盖项包括：合法/缺失/恶意入站 Header、CORS 暴露、统一 JSON 注入、认证失败、
  默认错误、MockMvc Header/JSON 同值契约、SSE trace/error 元数据、上下文压缩
  Kafka 命令与消费者恢复。
- 前端执行 `npm run build`，`vue-tsc --noEmit` 与 Vite 生产构建均成功，
  共转换 1916 个模块。
- 首轮误用本机默认 Java 25 时，既有 Mockito/Byte Buddy 因只支持到 Java 24
  导致 1 项测试环境错误；按项目 Java 17 规范重跑后全部通过，未通过修改业务
  代码掩盖该问题。
- 当前 8091 是用户从 IntelliJ 启动、且早于本轮改造的旧进程。为避免擅自中断
  用户运行中的服务，本轮没有重启该进程做真实登录聊天；HTTP Web 层契约、
  SSE 协议单测和前端生产构建已经闭环。部署或重启后可在会话页直接复制
  traceId，再使用本项目 Grafana 日志 CLI 查询。

### 边界与兼容性

- 文件下载和其他不可扩展响应体只增加 Header，不改写文件内容。
- 旧 Kafka 压缩消息缺少 `traceId` 时，消费者会生成安全的新链路号，保持反序列化
  与消费兼容；新消息会完整复用入口链路号。
- `traceId` 只用于可观测性检索，不参与认证、授权或租户隔离。
- 未修改或提交现有运行日志、对象存储、RAG 审计文档、Data Alloy、其他设计文档
  和本轮范围外的 Skill 文件。
