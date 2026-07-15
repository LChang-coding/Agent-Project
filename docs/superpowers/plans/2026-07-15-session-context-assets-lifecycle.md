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

执行前追加细化计划。

### 阶段四：Agent、工作流删除与分享工具权限提醒

执行前追加细化计划。

### 阶段五：综合回归与验收

执行前追加细化计划。
