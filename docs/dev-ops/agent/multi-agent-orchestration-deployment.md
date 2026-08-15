# Multi-Agent 编排中间件交付说明

## 本次代码边界

本交付包含代码、SQL、Kafka Topic 初始化脚本、配置契约和本地回归验证。执行迁移前必须核对目标库与 Kafka 集群，先备份再执行，不得依赖 Broker 自动建 Topic。

## 权威数据与加速数据

- MySQL `agent_subagent_task` 是任务、Lease、fencing token、结果摘要、完整上下文、回调和业务 ACK 的权威账本。
- MySQL `agent_parent_inbox` 与 `agent_parent_resume_request` 保存待续跑摘要、消费游标、单飞状态和恢复 Lease。
- MySQL `agent_tool_permission` 与 `agent_tool_approval_request` 保存工具策略、审批快照、决定和超时状态。
- MySQL `agent_orchestration_outbox` 与业务变更同事务写入，解决数据库提交成功但 Kafka 消息丢失的问题。
- Kafka 只负责异步唤醒和解耦，不承载权威任务正文；消费者必须回查 MySQL。
- Redis 只缓存临时实例镜像和 Parent Inbox 索引。Redis 数据丢失不影响权威恢复。
- 子 Agent 文本结果直接存入 MySQL `MEDIUMTEXT`，不依赖对象存储。

## Topic 契约

| Topic | 分区键 | 生产时机 | 消费动作 |
|---|---|---|---|
| `agent.subagent.task.v1` | `taskId` | 任务与 Outbox 同事务落库 | Worker 回查任务、领取 Lease、执行子 Agent |
| `agent.subagent.result.v1` | `parentRunId` | fencing CAS 写入任务终态 | 结果消费者登记 Parent Inbox 和 Resume Request |
| `agent.subagent.cleanup.v1` | `parentRunId` | 摘要被主 Agent 接收并提交业务 ACK，或任务取消 | 删除 Redis 临时实例与 Inbox 索引 |
| `agent.parent.resume.v1` | `parentRunId` | 父侧就绪且全部子任务终态后，由数据库 CAS 只激活一次 | Resume Worker 单飞读取完整终态结果并续跑主 Agent |

消息统一带 `schemaVersion=1` 和租户、聚合标识；子任务事件使用 `taskId/parentRunId`，可选 `traceId`。Topic 禁止自动创建；生产环境建议副本数 3、`min.insync.replicas=2`、生产者 `acks=all`。结果回调还需要预建 `${result-topic}-retry-0` 和 `${result-topic}-dlt`；`-retry-0` 来自 Spring Kafka `@RetryableTopic` 默认的索引后缀策略。因此共四个业务 Topic、两个回调重试 Topic。工具审批不经 Kafka。

## Lease、心跳与恢复

Worker 只有成功把任务从 `READY`（或 Lease 已过期的 `RUNNING`）原子推进为 `RUNNING` 后才获得执行权。每次领取递增 `fencing_token`，心跳每 20 秒续 60 秒 Lease。旧 Worker 即使恢复，也无法用旧 token 写结果。恢复任务定时扫描过期任务、回调、Resume Request 与未发布 Outbox，以数据库 CAS 补发唤醒。审批定时器只负责将超时请求落为默认决定，不执行工具。

定时扫描器不是唯一事实来源：它可以多实例部署，数据库条件更新负责互斥。若所有扫描实例同时宕机，待处理记录仍保留在 MySQL；实例恢复后继续扫描。生产环境必须监控 Outbox 最老事件年龄、积压量、`DEAD` 数和过期 Lease 数。

## 主 Agent 等待、结果与回调

主 Agent 调用 `create_subagent_instances` 后仍可完成自己的分析以及 MCP、Skill、RAG 调用；当前轮结束时保存隐藏草稿并打开父侧屏障，不占用 HTTP 线程或 Java 线程等待。每个 `SUBAGENT_RESULT_READY` 到达时，结果消费者只登记 MySQL Parent Inbox 和任务回调状态，不直接调用模型。只有父侧已就绪、至少存在一个子任务、且全部子任务都进入终态并完成回调登记后，数据库 CAS 才会把 Resume Request 从 `WAITING` 激活为 `PENDING` 并产生唯一恢复事件。

独立 Resume Worker 按 `tenantId + parentRunId` 领取单飞 Lease，一次读取该父运行的全部终态结果和主 Agent 草稿，以稳定的恢复 Run ID 续跑主 Agent。最终回答落库后才统一写业务 ACK、推进恢复请求为 `COMPLETED` 并清理 Outbox。重复或乱序回调只会重复触发条件检查，不会形成阶段性回答。Redis Inbox 只是可丢失索引，恢复扫描以 MySQL 为准补发唤醒。

子 Agent 结果采用“两级载荷”：最多 1000 字符的 `resultSummary` 自动进入 Parent Inbox；`fullContext` 留在 MySQL。主 Agent 只有判断确有必要时，才调用 `read_subagent_full_context` 获取完整上下文，减少 Kafka 载荷和模型上下文占用。`read_subagent_result` 与完整上下文工具均只读，不提前确认交付。

回调采用 at-least-once 语义：若进程在主 Agent 模型调用已完成、数据库收口提交前崩溃，过期 Resume Lease 会允许重试。稳定恢复 Run ID 与消息终态 CAS 保证最终回答只落库和展示一次，但租约接管仍可能重复调用模型、重复消耗 Token；跨模型调用与数据库事务不宣称 exactly-once，上线时必须观察重复执行率。

这里有三类不同的确认语义：Kafka consumer ACK/offset commit 只表示消息已消费；Kafka producer `acks=all` 只表示 ISR 已确认写入；任务 `ACKED` 表示摘要已被主 Agent 成功接收并提交游标。三者不可混称。

## 人工审批链路

任意平台工具、MCP 或 Skill 的策略为 `REQUIRE_APPROVAL` 时，ToolGateway 都会在领取幂等执行权和产生外部副作用前创建持久审批请求。后端通过带 Bearer Token 的 SSE `GET /api/v1/tool-approvals/stream` 向当前用户推送安全裁剪后的待审批事件；前端通过 `POST /api/v1/tool-approvals/{approvalId}/decision` 单点提交 `APPROVE`、`REJECT`、`APPROVE_WITH_CHANGES` 或 `REPLAN`。SSE 断线可按序号重连，HTTP 决定使用 revision 和数据库 CAS 防重复。

创建审批请求后，当前工具调用保持在原 Java/HTTP 执行栈中，每秒轮询 MySQL 决定，默认最长等待 600 秒，可配置 60–3600 秒。`APPROVE` 用原参数继续同一个 `functionCallId`；`APPROVE_WITH_CHANGES` 先以原 Schema 校验修改参数，再继续同一调用；`REJECT` 和 `REPLAN` 作为当前工具失败返回 Agent。审批扫描器和等待线程均可用数据库 CAS 落超时默认决定；不再存在审批 Kafka Topic、审批 Worker 或新建对话续跑。

这是有意选择的简化模型：审批期间会占用一个服务端请求线程，因此网关、Ingress、Servlet 和模型工具调用超时都必须高于审批上限，并按同时待审批数配置线程池与连接上限。进程重启或客户端断开时，本次原地 continuation 不可恢复，持久化记录仅用于审计和超时收口。

## Agent 模板与工具权限

静态 YAML 内置 `orchestration-role`、`category`、`best-for`、`not-for`、`capabilities` 和 `allowed-sub-agent-ids`。只有服务端可信角色为 `SUPERVISOR` 且白名单非空时，模型才能发现编排工具。owner/admin 可以按 Agent 分别为其实际可见的平台工具、MCP 与 Skill 配置 `ALLOW`、`REQUIRE_APPROVAL` 或 `DENY`；动态工具使用稳定的 `mcp:<id>` / `skill:<code>` 权限键。委派时仍会再次校验白名单、租户启停状态、任务数量和指令长度。

通用模板集中维护在 Nacos `DEFAULT_GROUP/ai-agent-templates-dev.yml`，并在 classpath 保留同等的启动兜底。当前内置 `100001` 通用编码、`100002` 通用调查、`100004` 通用审查和 `100003` Supervisor。Nacos 可以修改模板指令、能力元数据和 Supervisor 白名单，但 Armory 目前只在 `ApplicationReadyEvent` 中构建运行时 Bean；变更模板后必须重启应用，不得将属性刷新误称为 Agent 运行时热更新。

五个稳定工具协议为：`search_agent_catalog` 查询可用模板；`create_subagent_instances` 批量创建临时实例；`read_subagent_result` 读取状态与摘要；`read_subagent_full_context` 按需读取完整上下文；`cancel_subagent_instances` 取消实例。所有 schema 均禁止额外字段，Agent 身份、租户、父运行和白名单只取服务端上下文。

## 上线步骤

1. 停止旧服务并完成数据库备份后，评审并执行 `2026-08-12-agent-orchestration.sql`。本次普通发布或应用回滚禁止执行会删除六张表的 `2026-08-12-agent-orchestration-rollback.sql`。
2. 执行只读 `2026-08-12-agent-orchestration-verify.sql`，确认 6 张表、关键列和唯一索引全部为 `OK`。
3. 先以默认 dry-run 执行 `docs/dev-ops/kafka/bootstrap-agent-orchestration-topics.sh`，复核分区和副本数后设置 `APPLY=true`，幂等创建四个业务 Topic 与结果 retry/DLT，并按环境配置 ACL。
4. 确认 Redis TTL 与内存水位告警；Redis 不存永久任务事实。
5. 先部署代码但保持 `AI_AGENT_ORCHESTRATION_ENABLED=false`。
6. 灰度开启一个实例，验证 Outbox 延迟、Lease 接管、重复消息、审批超时、回调幂等和业务 ACK 清理。
7. 扩大 Worker，并监控任务积压、过期 Lease、Outbox `DEAD`、回调重试、重复续跑率和端到端耗时。

## 必测故障场景

- Worker 领取后宕机：Lease 到期后其他 Worker 接管，旧 Worker 结果因 fencing token 被拒绝。
- Kafka 重复投递：任务、回调或恢复 claim 失败后安全跳过。
- Kafka 暂时不可用：Outbox 退避重试，达到上限进入 `DEAD` 并告警。
- Redis 清空：任务仍能从 MySQL 恢复，Redis 索引可重建。
- 主 Agent 续跑失败：Resume Request 进入 `RETRYING`，任务不写伪业务 ACK。
- 回调重试耗尽：消息进入 DLT，告警后由运维重放原始结果事件。
- ACK 重放：已 `ACKED` 任务不重复改变权威结果，清理操作保持幂等。
- Outbox 扫描实例全部宕机：积压保留在 MySQL，实例恢复后继续扫描。
- SSE 断线或 Token 过期：前端刷新 Token 后按 `afterSequence` 重连，审批决定仍以 MySQL 为准。
- 审批超时：即使用户页面关闭，等待线程或超时扫描器也会用 CAS 落默认决定，等待线程随后继续或返回失败。
- 同一父运行的回调乱序、重复或包含失败/取消：全部任务终态后只激活一次恢复，主 Agent 同时获得完整结果集合。

## 本地验证与未验证项

本地已执行 Maven 定向测试、MyBatis 映射装载测试、前端 TypeScript/Vite 构建和应用打包。MySQL 迁移、Kafka Topic 与应用灰度启动的实际结果见下方部署记录。Redis TTL、真实 Supervisor 多 Agent 续跑以及宕机/重复投递故障注入尚未执行，不能写成已验证。

## 2026-08-12 部署记录

- 业务 MySQL 执行迁移前已生成全库一致性备份：`/opt/ai-agent-rag/backups/agent-orchestration-20260812-220146`；6 张表、关键列与唯一索引的只读校验全部为 `OK`。
- Kafka 为单 Broker，因此按现有环境约定创建 6 分区、1 副本 Topic，初始基线保存在 `/root/ai-agent-kafka/backups/agent-orchestration-20260812-220328`。灰度时确认 Spring Kafka 实际重试 Topic 为 `agent.subagent.result.v1-retry-0`，已显式固定后缀策略、修正脚本并补建 Topic；修正脚本快照保存在 `/root/ai-agent-kafka/backups/agent-orchestration-contract-fix-20260812-221021`。误创建的空 Topic `agent.subagent.result.v1-retry` 暂时保留，避免部署过程执行破坏性删除。
- 应用发布包位于 `/Users/codeliu/.ai-agent-scaffold/releases/20260812-221205-multi-agent`，编排开关已开启。业务库连接显式走 `127.0.0.1:13306` SSH 隧道，避免公网 MySQL 链路的偶发读超时。
- 上线门禁已验证：应用启动、Hikari 建连、编排主 Topic/retry/DLT 全分区分配、本机与域名 HTTP 链路、Trace ID 响应头均正常；启动后观察窗口无新的数据库或 Kafka 异常。真实 Supervisor 委派、人工审批和故障接管仍属于业务验收项。

## 2026-08-13 在线验收记录

- 当前发布目录：`/opt/ai-agent-scaffold/releases/20260813-023000-unified-tool-policy`，线上入口为 `http://lcodeagent.lcode.top`。
- 已真实验证主 Agent 创建子 Agent、异步回调、左侧二级任务树、子任务详情和刷新恢复；审批期间输入区锁定，审批后继续原调用。
- 已真实验证普通平台工具 `search_agent_catalog` 的通用审批，证明权限门禁不再只覆盖创建子 Agent；Agent 管理页可展示 Supervisor 的完整平台工具清单，MCP 与 Skill 发布后动态进入权限列表。
- 会话采用软删除，Agent/MCP/Skill 采用作用域禁用语义；四个页面均支持多选、全选、批量删除和部分失败反馈。在线浏览器已分别完成会话、Agent、MCP、Skill 批量删除。
- Skill 上传在线测试发现并修复资产记录缺失 `asset_kind`、`parse_status` 与 `sha256` 的问题；修复后 ZIP 上传、草稿创建和批量禁用已闭环通过。
- 桌面端治理链无页面异常或 5xx；390px 移动端文档宽度与视口均为 390px，无横向溢出。
