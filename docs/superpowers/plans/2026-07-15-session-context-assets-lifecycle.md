# 会话、上下文、Token、附件与资源生命周期闭环计划

## 一、执行约束

- 严格遵循仓库根目录 `codex.md` 的模块边界、租户隔离、可信身份和注释规范。
- 每个阶段开始前先在本文追加阶段计划，完成后追加真实操作、测试结果和提交号。
- 每个重大闭环使用中文提交信息，只暂存本阶段文件，不包含日志、密钥、构建产物和用户已有改动。
- 本地项目源码和构建产物不得上传服务器；如需数据库变更，仅以版本化迁移文件交付，执行前备份并校验。
- RAG 不在本轮范围内；相关统计保留兼容字段并返回零值。

## 二、总体交付范围

1. 数据库会话列表、消息分页读取和会话软删除。
2. Context Manager 真实上下文构成与占用统计。
3. 模型调用级、运行级、会话级 Token 用量持久化和展示。
4. 聊天附件上传、解析、引用，以及租户级资产中心。
5. Agent 租户级禁用、工作流软删除和前端删除入口。
6. 分享会话的工具依赖清单、接收方权限预检和风险提醒。
7. 前后端构建、单元/集成测试和可执行的端到端验收记录。

## 三、阶段计划

### 阶段一：数据库会话读取与会话删除

#### 完成条件

- 后端提供按可信租户与用户隔离的会话分页、消息游标分页接口。
- 后端提供会话软删除接口；删除后普通查询不可见，历史消息及审计数据不物理删除。
- 删除过程处理运行中会话、分享链接、上下文缓存、压缩任务和附件引用的一致性。
- 前端会话列表与消息恢复改为数据库来源，本地存储只保留草稿和界面偏好。
- 前端提供带二次确认和失败提示的会话删除入口。
- 相关后端测试、前端类型检查与构建通过。

#### 预定接口

- `GET /api/v1/sessions?cursor=&limit=`
- `GET /api/v1/sessions/{sessionId}/messages?beforeSequence=&limit=`
- `DELETE /api/v1/sessions/{sessionId}`

#### 主要风险与验证

- 风险：跨租户读取或删除。验证所有仓储查询均包含 `tenantId`，用户权限由 `TenantContextHolder` 提供。
- 风险：删除污染运行和压缩状态。验证运行中删除的冲突/取消语义及压缩任务失效逻辑。
- 风险：前端刷新后状态丢失。验证登录后重新拉取数据库会话和消息。

#### 执行实录

完成时间：2026-07-15。

实际操作：

- 新增 `GET /api/v1/sessions`，使用最后消息时间与会话 ID 组成稳定游标，按可信 `tenantId + userId` 分页读取。
- 新增 `GET /api/v1/sessions/{sessionId}/messages`，只返回 `validity_status=active` 的有效消息，使用消息序号向前分页并按阅读顺序返回。
- 新增 `DELETE /api/v1/sessions/{sessionId}` 与 `SessionLifecycleService`，删除时取消全部非终态运行、推进上下文版本、废弃整会话压缩任务、阻止晚到快照激活、提交后清理缓存、撤销全部活动分享，最后软删除会话。
- 统一运行创建、取消、引导和消息终结的锁顺序为“会话锁 → 运行锁”，避免会话删除与模型晚到消息产生反向锁和删除后续写。
- 保留消息、运行、工具日志和附件关联用于审计，没有物理删除历史数据。
- 前端新增会话 API，Pinia 会话列表与消息切换改为数据库读取；移除完整会话和消息的 localStorage 读写，仅保留认证等原有浏览器配置。
- 前端增加会话删除按钮、二次确认、逐项 loading、服务端错误提示；切换会话增加请求代次，防止慢响应覆盖后来选择。
- 分享导入后重新读取数据库会话，而不是重新写入本地会话副本。

验证结果：

- 使用 Java 17 执行 `SessionControllerTest`、`SessionLifecycleServiceTest`、`SessionDomainTest`、`RunControlServiceTest`、`MyBatisMapperLoadTest`：共 11 个测试，全部通过。
- 执行前端 `npm run build`：`vue-tsc --noEmit` 和 Vite production build 均通过，共转换 1904 个模块。
- 搜索确认前端源码不存在 `ai_agent_scaffold_chat_sessions`、`sessionStorageKey`、`restoreSessions` 或完整会话 localStorage 写入。
- 对本阶段文件执行 `git diff --check`：通过。

已知环境说明：

- 本机默认 Java 25 超出当前 Byte Buddy 支持范围，第一次 Mockito 运行出现环境兼容错误；切换项目规定的 Temurin Java 17 后全部测试通过，业务断言无失败。
- 本阶段不需要数据库结构迁移，没有向服务器上传任何源码或构建产物。

### 阶段二：Context Manager 与模型 Token 用量

#### 完成条件

- 提供 `GET /api/v1/sessions/{sessionId}/context-insight`，统计必须复用真实上下文组装器和同一 Token Counter，不产生压缩、缓存写入或模型调用副作用。
- 响应包含模型窗口、有效 Token、占用率，以及 system/history/summary/toolResult/attachment/rag 的构成，包含有效消息范围、压缩状态、工具/调用/附件数量。
- 模型每次真实调用完成后按唯一 `callId` 持久化 provider 返回的 prompt/candidate/total Token；成功、失败、取消和重试可区分且保持幂等。
- 提供会话最新调用、运行汇总和会话汇总用量查询；现有报表 API 复用同一事实表。
- 聊天洞察面板、Context 页面和 Token 页面去除占位值，发送、工具、附件、取消、引导和压缩后可刷新真实统计。
- 后端领域、仓储、Mapper、流式终态和租户隔离测试通过；前端类型检查和生产构建通过。

#### 数据与兼容策略

- 以现有 `model_usage` 为基础做增量迁移，新增 `call_id`、`run_id`、`usage_type`、`call_status`、`finish_reason` 与唯一幂等约束；旧字段和已有查询保持兼容。
- Token 以模型供应商返回 usage 为最终事实；供应商没有返回时记录状态但不伪造精确用量，上下文预估与账单用量分开展示。
- RAG 尚未接入，本阶段 `ragTokens=0`；附件统计在阶段三接入后自动使用预留字段。
- 不在前端硬编码模型价格；本阶段只闭环 Token，不把估算价格冒充真实账单。

#### 主要风险与验证

- 风险：为了统计再次执行上下文装配副作用。验证只读预览不发布压缩、不写缓存、不推进版本。
- 风险：流式多次回调重复入库。验证同一 `callId` 只有一条最终事实，重试使用新 `callId`。
- 风险：取消/失败丢失已产生用量。验证终态记录 status 与可用的 provider usage。
- 风险：跨租户读取。验证所有查询使用 `TenantContextHolder` 的 tenant/user/session 范围。

#### 执行实录

闭环时间：2026-07-15。

实际操作：

- 新增会话上下文洞察、会话/运行模型用量和用户近期用量接口；所有查询身份均来自 `TenantContextHolder`，并按 tenant/user/session 范围校验。
- `ConversationMemoryService` 增加只读 `preview`，直接读取数据库有效摘要和消息，不读写 Redis、不发布压缩任务、不推进版本；返回摘要、历史、上游、RAG 与实际选中序号范围。
- 修正 `ContextAssembler` 的预算扣减：按完整片段实际估算 Token 扣减，超出片段上限或总预算的片段不注入，避免“只扣上限但注入全文”；最终序号和裁剪原因按实际选中片段返回。
- 增加最近压缩任务查询，使洞察能够显示 processing/succeeded/stale 等真实最近状态，而不是把有效租约内的 processing 误报为 idle。
- 建立 `ModelUsageService` 与独立仓储，模型调用前先以唯一 `callId` 写入 running，流式完成后补发唯一 `partial=false` 终态并以供应商 usage 幂等更新；失败写 failed，运行取消或引导替代时将 running 调用更新为 cancelled。
- `model_usage` 聚合支持最新调用、会话、运行和近 N 天范围；空聚合逐字段归零，Token 分项只允许单调增加，调用终态不可被晚到回调倒退。
- 新增可重复执行的增量迁移，使用 `information_schema + 动态 DDL` 兼容 MySQL 8，新增调用/运行/终态列、唯一调用键和运行范围索引；同步更新全量初始化脚本。
- 前端新增 insight API 与 Pinia Store，包含请求代次隔离；聊天洞察、Context 页面和 Token 页面移除硬编码示例值。存在供应商最新 usage 时展示最近一次实际 Prompt Token，否则明确展示 Context Manager 当前估算；工具、调用、附件徽标改用会话真实统计。
- 运行正常结束、异常、取消、引导收口和会话切换后触发洞察刷新；没有最新模型调用时 prompt/candidate/total 严格显示 `--`。

验证结果：

- Java 17 下执行 `ObservabilitySpringAITest`、`ContextAssemblerTest`、`ConversationMemoryServiceTest`、`ContextInsightServiceTest`、`SessionInsightControllerTest`、`ModelUsageServiceTest`、`RunControlServiceTest`、`SessionLifecycleServiceTest`、`MyBatisMapperLoadTest`：共 22 个测试，全部通过。
- 流式专项测试验证两个 partial 片段之后只生成一个完整终态响应，终态 `partial=false`、`turnComplete=true`，模型用量成功落库路径可达。
- 前端执行 `npm run build`：`vue-tsc --noEmit` 和 Vite production build 均通过，共转换 1907 个模块。
- 对本阶段源码、迁移和计划文件执行 `git diff --check`：通过。
- 未上传本地项目源码或构建产物。迁移前将远端 `model_usage` 单表备份到本机项目目录之外的 `/tmp/ai_agent_scaffold_model_usage_20260715_2238.sql`，备份 SHA-256 为 `4f5c7d7d822db354aa24346c60f90653603285ec6af7a39432a0c91637b6a5ec`，大小 3637 字节。
- 增量迁移 SHA-256 为 `01ffb21a559ad7ce690c9e0aa692da1a0f2f54ba2c790e54c75ff78162d63c72`；在远端 MySQL 8.0.46 连续执行两次均成功，确认五个新增列、`uk_model_usage_call` 和 `idx_model_usage_run` 已存在，迁移前后表内业务记录数均为 0。

### 阶段三：聊天附件与资产中心

#### 完成条件

- 后端提供聊天附件上传、会话附件列表、下载和删除接口，上传身份只取 `TenantContextHolder`，对象键必须包含可信 tenant/user 隔离路径。
- 附件元数据以数据库为事实源，记录原文件名、MIME、大小、SHA-256、对象键、解析状态、会话/消息绑定和拥有者；重复上传按拥有者与哈希复用对象但保留引用语义。
- 文本、Markdown、PDF、Word 和常见图片有明确处理策略：可解析文本提取后进入附件上下文，不可解析或超限文件仍可作为资产保存但不注入模型。
- 发送消息时只能引用当前用户、当前会话下处于 ready 状态的附件；后端在保存用户消息后原子绑定 messageId，客户端不能伪造其他租户资产。
- 附件上下文通过 `ContextContributor` 接入现有预算器，计入 `attachmentTokens/attachmentCount`；取消、引导和压缩只读取有效消息关联，失效消息的附件文本不得污染后续上下文。
- 前端聊天输入区支持选择/上传/移除附件并在发送时携带 assetId；资产中心展示当前用户资产、来源会话、解析状态、大小、下载和删除入口。
- 增量迁移、领域测试、租户越权测试、解析限制测试、前端类型检查和生产构建通过。

#### 预定接口

- `POST /api/v1/assets/chat-attachments`（multipart：file、可选 sessionId）
- `GET /api/v1/assets?cursor=&limit=&sessionId=&kind=chat_attachment`
- `GET /api/v1/assets/{assetId}/download`
- `DELETE /api/v1/assets/{assetId}`
- 聊天请求新增可选 `attachmentIds`，保持旧客户端不传时兼容。

#### 数据与安全策略

- 优先兼容演进现有 `artifact_asset`，通过日期增量迁移补齐 `asset_kind`、`sha256`、`mime_type`、`size_bytes`、`parse_status`、`extracted_text`、`message_id` 等必要字段；不另造与现有基础设施重复的对象表。
- 默认单文件上限 20 MiB、单次最多 10 个；文件名只作展示，存储对象键由服务端生成；下载使用短时 MinIO 预签名 URL 或受控流式响应，不暴露永久公网对象地址。
- 提取文本设置字符/Token 上限，不把二进制、对象地址、异常堆栈或解析器内部信息注入模型；失败原因只保存截断后的安全摘要。
- 资产删除为软删除并解除后续上下文引用；已随历史消息产生的审计关系保留，不立即物理删除对象，后续由独立清理任务处理无引用对象。

#### 主要风险与验证

- 风险：跨租户下载或引用。验证所有查询包含 tenantId + ownerUserId + assetId/sessionId，分享导入不自动复制发送方私有附件权限。
- 风险：文件炸弹和内存放大。验证上传大小、解析文本上限、PDF/Word 页数或压缩展开限制，解析失败可降级。
- 风险：取消消息附件污染上下文。验证 contributor 只关联 active 消息，未绑定草稿附件和 invalidated 消息附件不进入组装。
- 风险：上传成功但数据库失败留下孤儿对象。使用补偿删除或 orphan 状态，并测试异常路径。

#### 执行实录

闭环时间：2026-07-15。

实际操作：

- 在现有 `artifact_asset`、MinIO `ObjectStorageService` 和 Context Manager 扩展点上完成资产领域闭环，没有新建重复对象存储体系；新增可信上传、拥有者游标分页、受控下载、软删除、SHA-256 对象复用与数据库失败补偿删除。
- 聊天请求增加可选 `attachmentIds`，Agent 与工作流共用同一条附件路径；消息写入、附件绑定和运行消息绑定统一进入 `RunControlService.appendUserMessage` 事务，任一步失败都会整体回滚，不再采用“消息先落库、失败后取消”的非原子补偿。
- 附件绑定 SQL 同时校验 tenant、owner、session、active、ready、未绑定状态和 assetId 集合；下载、列表、删除均由 `TenantContextHolder` 提供可信用户身份，客户端不能提交 owner/tenant。
- 文本、Markdown、CSV、JSON、PDF、DOCX 提取安全截断后的文本；单文件限制 20 MiB、PDF 限制 200 页、提取内容限制 60000 字符、错误摘要限制 240 字符。图片、空文本和未知格式明确保存为 `unsupported`，不会伪装为已进入模型。
- 附件上下文通过 `ContextContributor` 接入现有组装器，独立占用 `attachmentTokens`；仓储只返回 visibleThroughSequence 以内、active 用户消息绑定的 ready 附件，取消/引导失效消息不会进入上下文。预算不足时优先保留最近附件并按 Token 安全截断，渲染时恢复时间顺序。
- 会话洞察增加真实 `attachmentTokens` 与 `attachmentCount`；资产分页接口统一返回 `items/nextCursor/hasMore`，修复并行实现中前端按分页对象读取、后端却返回裸数组的契约不一致。
- 前端新增资产 API 与 Pinia Store；聊天区支持点击选择、拖拽/文件选择上传、待发送附件移除与发送快照，会话切换时用 scope 和请求代次隔离附件草稿；资产中心支持分页、上传、状态展示、下载和软删除。
- 新增 MySQL 8 可重复增量迁移，演进 `artifact_asset` 的 `asset_kind/sha256/parse_status/extracted_text/parse_error` 与拥有者哈希索引；同步更新全量初始化脚本。由于现有聚合 POM 与各模块 parent 坐标不一致，PDFBox 和 POI 暂在基础设施模块显式锁定版本，避免产生无法解析的伪依赖管理。

验证结果：

- Java 17 下执行 `AssetServiceTest`、`AssetContextContributorTest`、`DefaultAssetTextExtractorTest`、`RunControlServiceTest`、`ContextInsightServiceTest`、`MyBatisMapperLoadTest`：共 14 个测试，全部通过；覆盖可信存储路径、对象复用、部分绑定拒绝、可见边界、图片降级、取消竞态、消息/附件事务入口和 Mapper 装载。
- 前端执行 `npm run build`：`vue-tsc --noEmit` 与 Vite production build 均通过，共转换 1910 个模块。
- 对本阶段源码、迁移与计划文件执行排除用户日志后的 `git diff --check`：通过。
- 未向服务器上传本地项目源码或构建产物。迁移前将远端 `artifact_asset` 单表备份到项目目录外的 `/tmp/ai_agent_scaffold_artifact_asset_20260715_2303.sql`，备份 SHA-256 为 `dc71f35c2a0712600348d937d9c69b13926db745e0d30084770a1e481d1458bb`。
- 增量迁移 SHA-256 为 `297a26ae11827eb2909934b43206979c37d2c798f0961790d2747c3eee83b3b9`；在远端 MySQL 连续执行两次均成功，五个预期列与 `idx_artifact_owner_hash` 均唯一存在，备份与迁移后业务记录数均为 3。

### 阶段四：Agent、工作流删除与分享工具权限提醒

#### 执行计划（执行前落盘）

1. 先沿 `codex.md` 规定的 API → Trigger → Domain → Infrastructure 链路，盘点 Agent、工作流、工具权限和会话分享现有表、仓储、接口及前端入口，明确哪些对象来自数据库、哪些来自静态装配配置；不把配置型 Agent 伪装成可物理删除对象。
2. Agent 采用租户/用户作用域内的“禁用优先、删除语义映射为禁用”策略：新增可信身份作用域的状态事实源和幂等启停接口；列表、启动运行、工作流引用和装配刷新都必须尊重禁用状态。若现有数据库 Agent 已具备软删除字段，则复用原表；否则以最小覆盖表记录禁用覆盖，不修改共享基础配置。
3. 工作流复用现有数据库事实源实现软删除，所有查询、发布、运行和版本读取排除已删除记录；删除前校验租户/用户归属和活动运行/引用冲突，重复删除保持幂等。前端列表和构建器增加明确删除入口、二次确认、失败恢复和删除后的路由/缓存收口。
4. 会话分享在生成快照时固化该会话实际使用过的工具清单（toolId、名称、来源和必要权限标识），工具证据优先取持久化调用日志，不信任客户端提交；分享下载结构增加版本化 `toolDependencies`，保持旧分享记录兼容。
5. 分享接收/导入前，以接收方可信 tenant/user 权限查询可用工具，返回 `available/missing/denied` 预检结果；缺少权限时前端必须显示具体工具提醒并要求用户确认。提醒不泄露发送方密钥、私有配置或工具结果，也不自动授予权限；导入后缺权工具保持不可执行状态。
6. 增加必要的日期增量迁移并按数据库迁移规范先备份、再连续执行两次验证幂等；测试覆盖跨租户禁用/删除、运行前拒绝、工作流软删除、分享工具清单快照、接收方缺权预检和旧分享兼容。
7. 执行 Java 17 定向测试、Mapper 装载、前端类型检查与生产构建；具备可启动依赖时补 HTTP/E2E 验证。完成后把真实改动、测试、迁移、遗留限制和提交号追加到本节，再进行中文本地提交。

#### 完成条件

- Agent 和工作流在前端均有可发现的删除/禁用入口，后端强制可信作用域且运行入口无法绕过状态检查。
- 工作流删除是可审计软删除，历史会话和运行记录保留；Agent 配置事实源不被跨租户物理破坏。
- 分享快照包含服务端计算的工具依赖；接收方缺少任一工具权限时能够看到明确提醒，未经确认不导入。
- 新旧分享数据均可读取；越权、取消/失效消息和未完成工具调用不会污染工具依赖清单。

#### 执行实录

闭环时间：2026-07-15。

实际操作：

- 确认静态 Agent 的事实源是 YAML/Nacos `AiAgentAutoConfigProperties`，没有物理删除共享装配配置；新增 `agent_tenant_override` 租户覆盖表、领域仓储和可用性服务，以“无覆盖即启用、删除等同禁用”的方式保存状态、原因、操作人、禁用时间和乐观锁修订号。
- 新增 Agent 管理列表、启停和删除接口。管理列表包含禁用项并返回 `sourceType/manageable/revision/disabledAt`，只有可信 owner/admin 可变更；原聊天和定时任务 Agent 列表仍只返回启用项。
- 禁用闸门收口到 `ChatService.requireAgent`，创建会话、普通/流式对话、运行恢复和定时任务无法绕过；工作流动态生成的临时 Agent 不属于静态配置覆盖范围，默认不受错误禁用影响。
- 工作流复用 `agent_workflow.status/deleted` 做软删除，新增删除人和删除时间审计；删除前校验 owner/admin 写权限和 `chat_run` 可执行状态冲突，保留版本、历史会话和运行记录，删除成功后清除运行时缓存，重复删除幂等成功。
- 修复工作流写权限边界：保存、发布和删除均显式从 Controller 传入可信 roleCode，领域层不读取 ThreadLocal；非拥有者且非管理员不能修改。运行时加载强制主表为 `published`，已停用、归档或删除工作流不能借旧版本继续启动。
- 保留普通租户成员创建自己私有工作流的原能力，没有把新增删除权限错误扩大成“只有管理员才能创建”。前端工作流删除采用悲观更新，服务端成功前保留列表、当前详情和未保存画布；删除中禁用保存、发布和切换。
- 会话分享格式升级为 `chat-session-export/v2`，服务端从 `tool_call_log` 联结 active 消息，只固化有效运行中 `success` 的工具类型、ID、名称和版本；不导出输入、输出、端点、环境变量或密钥。取消/引导失效消息、started/failed 调用不进入依赖清单。
- 分享预览读取接收方真实可用工具目录，按工具 ID、类型和版本返回 available/missing 预检；缺少工具或版本且未显式确认时返回 `SHARE_TOOL_CONFIRM_REQUIRED`，不消费下载次数、不创建会话、不自动授权。已有幂等导入记录可直接返回，不重复计次。旧 v1 快照按空依赖兼容并明确标识。
- 前端新增 Agent 管理页、Store、路由和导航；Agent 支持启用、禁用和“删除等同禁用”的二次确认。分享页展示工具依赖及权限状态，缺权时必须勾选“不自动授权”确认；导入按服务端 `sourceType/workflowId` 恢复会话类型，不再依赖本地缓存猜测。

验证结果：

- Java 17 下执行 `AgentAvailabilityServiceTest`、`WorkflowLifecycleServiceTest`、`SessionShareServiceTest`、`WorkflowDagCompilerTest`、`RunControlServiceTest`、`MyBatisMapperLoadTest`：共 16 个测试，全部通过；覆盖租户隔离、owner/admin 权限、乐观锁入口、活动运行删除冲突、分享白名单、缺权确认、旧快照兼容和 Mapper 装载。
- 前端执行 `npm run build`：`vue-tsc --noEmit` 与 Vite production build 均通过，共转换 1914 个模块。
- 在远端 MySQL 对分享工具依赖聚合 SQL 做空范围语法探测，通过；对 Agent 覆盖表执行事务内 insert/update 后回滚，事务内 1 条、回滚后 0 条，没有留下探测数据。
- 未向服务器上传本地项目源码或构建产物。迁移前将远端 `agent_workflow` 单表备份到项目目录外 `/tmp/ai_agent_scaffold_agent_workflow_20260715_2321.sql`，备份 SHA-256 为 `5879da2f55892daef48045fe9538f8311d89897b9aeebea575bf2e872fb83ee4`。
- 增量迁移 SHA-256 为 `cbc3ce50fcd42260547d39c49815b19589f4e4b643c8a5591449046242ce0213`；在远端 MySQL 连续执行两次成功，`agent_tenant_override`、唯一覆盖索引和两个工作流删除审计列均唯一存在，工作流记录迁移前后均为 6 条。

### 阶段五：综合回归与验收

#### 执行计划（执行前落盘）

1. 以 `codex.md`、本计划四个阶段的完成条件和三次阶段提交为基线，做一次只读全链路审计：核对 API → Trigger → Domain → Infrastructure → MyBatis → MySQL 与 Web API → Store → View 的契约，重点检查租户可信身份、软删除/失效过滤、游标分页、幂等和旧数据兼容。
2. 复查 Context Manager 与附件的生命周期边界：确认压缩覆盖区间、附件可见区间、取消/引导失效消息、压缩摘要和工具调用前预检之间不存在重复注入或已失效内容回流；发现会污染上下文或造成 Token 重复计费的缺口时，先补领域测试再做最小修复。
3. 复查会话数据库读取、Token 用量、资产、Agent/工作流生命周期及分享工具权限的前后端字段一致性和运行入口强制性；对仅由前端隐藏、后端可绕过，或仅写库未被运行时消费的问题做阻断级修复。
4. 运行 Java 17 全量可执行测试（优先聚合模块；若仓库既有无关测试依赖外部服务，则记录阻塞并以全部相关定向测试补足）、Mapper 装载、前端 `vue-tsc` 与 production build；检查 SQL 迁移幂等结论、Git 空白错误、敏感信息和保护目录。
5. 若本地依赖允许启动，执行关键 HTTP 链路的端到端冒烟；若外部中间件或既有启动配置阻止 E2E，不中断闭环，必须记录精确阻塞证据、已完成的替代验证和上线前人工检查项。
6. 将本阶段发现、实际修复、测试结果、未解决限制和最终提交号追加到本节；任何代码修复形成独立中文本地提交。最终确认工作树只剩用户原有日志与资料目录变更，不覆盖、不提交这些内容。

#### 验收门槛

- 数据库会话历史、Context Manager 真实统计、模型 Token 用量、附件资产、Agent/工作流删除及分享权限提醒均有服务端事实源，前端刷新后不依赖浏览器缓存维持正确性。
- 取消、引导、压缩、附件和工具调用共享一致的有效消息边界；已取消/失效消息不能进入摘要、附件上下文、工具依赖或后续模型请求，工具调用前的取消与压缩预检不能绕过。
- 相关 Java 测试、Mapper 装载和前端生产构建全部通过；无法执行的全量/E2E 项必须有明确证据且不掩盖产品逻辑缺口。
- 阶段提交均为中文，本地源码和构建产物未上传服务器，远端数据库变更均已有项目目录外备份和幂等验证记录。

#### 执行实录

闭环时间：2026-07-15。

实际操作：

- 以四个阶段提交为基线完成 API → Trigger → Domain → Infrastructure → MyBatis → Web 全链路审计，并使用三个并行 Agent 分别审查上下文/附件、会话/Token、Agent/工作流/分享。审计确认数据库会话分页本身正确、前端聊天记录不再写 localStorage、工具真实分发由 `ToolGateway.claim` 在外部副作用前锁定运行并二次校验取消。
- 修复本轮附件不可见：历史消息仍以当前用户消息序号 `N-1` 为上界，附件新增独立上界 `N`，避免重复注入当前用户文本的同时保证本轮上传附件立即进入模型。
- 完成附件与压缩承接：Contributor 增加摘要覆盖下界，仅注入 `(coveredToSequence, attachmentVisibleThrough]`；压缩提示和 `coverageHash` 纳入覆盖区间内 active/ready 附件文本，执行及激活前均重验；摘要激活后旧附件由摘要承接，不再全文重复注入。同次上下文按 SHA-256 去重并修正容器标签 Token 预算。
- 修复取消/引导缓存提交竞态：运行失效不再在数据库事务提交前清 Redis，而是注册 `afterCommit` 二次清理；前端取消或引导成功后强制重新分页读取当前会话的数据库有效消息，不把已 invalidated/superseded 的临时消息继续作为历史事实展示。
- 为 `chat_session` 新增 `source_type/workflow_version/model_code` 服务端事实字段，Agent 会话固化 `agent`，工作流会话固化服务端实际解析版本和模型；实体、命令、PO、Mapper、会话 DTO、分享 v2、前端 Store 和请求恢复全部贯通，旧会话/旧分享缺失字段时兼容为 Agent，不再根据 `wf_` 前缀或当前工作流目录猜测。
- 阻断动态工作流 Agent 绕过：普通 Agent 公共创建、非流式、流式和富内容入口只接受静态配置 Agent；动态 Runtime Agent 只能在 `loadRuntime` 授权后由工作流内部路径解析。工作流 list/detail/loadRuntime 统一实施拥有者本人、owner/admin 或 `tenant_public` 可读规则，私有越权与不存在返回同口径，且鉴权先于版本解析和 runtime cache。
- 加固分享和工具隔离：分享导入先查询接收方既有 import，再判断当前额度，首次成功但响应丢失后即使额度耗尽也能返回同一会话且不重复计次；私有 Skill/MCP 查询改为 tenantId + ownerUserId 双条件；Agent 删除前端传递 revision，不再绕过乐观锁。
- 补全模型用量状态口径：partial 响应携带供应商累计 usage 时以 running 状态单调 upsert，取消/失败保留已消耗 Token；聚合增加 running/cancelled 数量并在前端展示，使 callCount 与各状态合计可解释。
- 清理会话前端瞬态偏差：新建会话不再插入数据库不存在的 system 消息，内存会话列表不再发送一次消息后截断到 30 条。

验证结果：

- Java 17 下执行干净构建与综合定向回归：`SessionDomainTest`、`SessionLifecycleServiceTest`、`SessionControllerTest`、`ContextAssemblerTest`、`ConversationMemoryServiceTest`、`ContextInsightServiceTest`、`ContextInvalidationServiceTest`、`SessionInsightControllerTest`、`ModelUsageServiceTest`、`AssetServiceTest`、`AssetContextContributorTest`、`DefaultAssetTextExtractorTest`、`AgentAvailabilityServiceTest`、`ChatServiceAuthorizationTest`、`WorkflowLifecycleServiceTest`、`WorkflowDagCompilerTest`、`SessionShareServiceTest`、`RunControlServiceTest`、`RunExecutionGateTest`、`ToolDispatchAuthorizationServiceTest`、`MyBatisMapperLoadTest`，共 55 项，0 失败、0 错误。
- 前端执行 `npm run build`，`vue-tsc --noEmit` 和 Vite production build 均通过，共转换 1914 个模块。
- 全量仓库测试曾执行 83 项，其中 69 项通过、14 项错误；错误均来自仓库既有演示测试没有可运行 `@Test` 方法，或旧 `ChatServiceTest` 未设置新增的可信租户上下文。干净构建已证明 Spring Bean 冲突只是旧 target 残留，综合相关测试全部通过；按照约定没有因这些既有测试夹具问题中断功能闭环。
- 未启动独立本地 Web 服务，因此未执行带真实 JWT 的浏览器 HTTP E2E；以 Controller 测试、Spring 上下文启动、领域竞态测试、Mapper 装载、远端数据库契约验证和前端生产构建替代。上线前仍建议用测试租户补一次浏览器取消/引导、附件、私有工作流和分享导入冒烟。
- 对远端 `chat_session` 迁移前备份到项目目录外 `/tmp/ai_agent_scaffold_chat_session_20260715_2341.sql`，备份 SHA-256 为 `0f4e6308e733a7e281fc7eaddab5a3df711007193c6206acc6e4a334d4e6535d`；迁移 SHA-256 为 `f7cf8b674ae40ed8bf6e5c0e67ef966d731954f36e1755d38a5f1363022c98da`。
- 增量迁移通过标准输入连续执行两次，没有向服务器上传本地项目、SQL 文件或构建产物；迁移前后会话均为 33 条，三个运行目标字段唯一存在，非法 `source_type` 为 0。
- 最终代码修复提交：`cfdfd24 修复综合验收中的上下文与权限闭环缺口`。本阶段未覆盖或提交用户原有日志、`data-alloy/`、设计资料和 `skills/`。

已知非阻断项：

- Maven 聚合模型仍提示 `ai-agent-scaffold-api` 的 parent 坐标/relativePath 与根 POM 坐标不一致，当前可编译但应在独立基建任务中统一。
- Context Insight 中 system/tool 细分仍属于可解释估算，不等同供应商最终完整 LLM Request；真实 prompt/candidate/total Token 已以 `model_usage` 为事实源。
- 模型回调的 invocation 内存映射在极端“流直接取消且供应商无终态回调”时仍依赖后续清理，数据库终态和 Token 不受影响；建议后续增加活动调用指标和统一 `doFinally` 清理。

阶段提交链：

- `31a3765 实现数据库会话读取与会话删除闭环`
- `8392e7b 实现上下文统计与模型Token用量闭环`
- `2d82cc0 实现聊天附件与资产中心闭环`
- `2e6744d 实现Agent工作流生命周期与分享权限预检闭环`
- `cfdfd24 修复综合验收中的上下文与权限闭环缺口`
