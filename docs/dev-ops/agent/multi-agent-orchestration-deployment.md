# Multi-Agent 编排中间件交付说明

## 本次代码边界

本机无法访问 `codex.md` 所述业务服务器，仓库内也未发现该文件。因此本次只交付代码、SQL、配置契约和本地静态/单元验证，不连接共享 MySQL、Redis、Kafka，不创建 Topic，不执行迁移。

## 权威数据与加速数据

- MySQL `agent_subagent_task` 是任务、Lease、fencing token、结果、回调和 ACK 的权威账本。
- MySQL `agent_orchestration_outbox` 与任务变更同事务写入，解决数据库提交成功但 Kafka 消息丢失的问题。
- Kafka 只负责异步唤醒和解耦，不承载权威任务正文；消费者必须用 `tenantId + taskId` 回查 MySQL。
- Redis 只缓存 `agent:instance:{tenantId}:{taskId}` 临时实例镜像和 `agent:inbox:{tenantId}:{parentRunId}` Parent Inbox 索引。Redis 数据丢失不影响恢复。
- 子 Agent 的文本结果直接保存在 MySQL `MEDIUMTEXT`，不依赖对象存储。

## Topic 契约

| Topic | 分区键 | 生产时机 | 消费动作 |
|---|---|---|---|
| `agent.subagent.task.v1` | `taskId` | 任务与 Outbox 同事务落库 | Worker 回查任务、领取 Lease、并行执行子 Agent |
| `agent.subagent.result.v1` | `parentRunId` | fencing CAS 成功写入终态结果 | 写 Redis Inbox，幂等领取回调权，唤醒主 Agent |
| `agent.subagent.cleanup.v1` | `parentRunId` | 主 Agent 回调成功并原子 ACK，或任务被取消 | 删除 Redis 临时实例与 Inbox 索引 |

消息统一包含 `schemaVersion=1`、`tenantId`、`taskId`、`parentRunId`，可选 `traceId`。Topic 禁止自动创建；生产环境建议副本数 3、`min.insync.replicas=2`、生产者 `acks=all`。结果回调还需要预建 `${result-topic}-retry` 和 `${result-topic}-dlt`，总计五个 Topic。

## Lease 与心跳

Worker 只有成功把任务从 `READY`（或 Lease 已过期的 `RUNNING`）原子推进为 `RUNNING` 后才获得执行权。每次领取递增 `fencing_token`，心跳每 20 秒续 60 秒 Lease。旧 Worker 即使恢复，也无法用旧 token 写结果。恢复任务每 10 秒扫描过期执行和回调 Lease，以 CAS 重置状态并重新写 Outbox，避免 Kafka 在 Lease 到期前重投并提交 offset 后永久失去唤醒消息。

## 主 Agent 等待与回调

主 Agent 调用 `create_subagent_instances` 后不占用 HTTP 线程或 Java 线程等待。任务账本和 Parent Inbox 表示逻辑等待状态。任一 `SUBAGENT_RESULT_READY` 到达时，回调消费者按 `parentRunId` 分区处理，并用数据库 `callback_status` 抑制普通 Kafka 重投导致的重复续跑。内部 ThreadLocal 仅把原始 `orchestrationRootRunId` 传入新一轮主 Agent 推理；模型参数不能伪造该身份。只有该轮可信回调完整成功后，数据库才在同一事务中写入结果 ACK 和临时实例清理事件；`read_subagent_result` 本身只读，不提前 ACK。

回调采用企业消息系统常见的 at-least-once 语义：若进程在主 Agent 已完成、数据库 ACK 前崩溃，过期回调 Lease 会允许重试，因此主 Agent 续跑逻辑必须继续依赖任务 ID、工具 `functionCallId` 和现有运行幂等闸门。系统不宣称跨模型调用和数据库事务的 exactly-once。

## Agent 模板

静态 YAML 内置 `orchestration-role`、`category`、`best-for`、`not-for`、`capabilities` 和 `allowed-sub-agent-ids`。只有服务端可信角色为 `SUPERVISOR` 且白名单非空时，模型才能发现四个编排工具。委派时再次校验白名单、租户启停状态和运行身份。

四个稳定工具协议为：`search_agent_catalog` 查询可用模板；`create_subagent_instances` 批量创建临时实例；`read_subagent_result` 只读结果；`cancel_subagent_instances` 取消实例。所有 schema 均禁止额外字段，Agent 身份、租户、父运行和白名单只取服务端上下文。

## 上线步骤

1. 评审并通过变更平台执行 `2026-08-12-agent-orchestration.sql`，保留回滚 SQL。
2. 创建任务、结果、结果 retry、结果 DLT、清理五个 Topic，配置 ACL。
3. 确认 Redis TTL 与内存水位告警；Redis 不开启永久任务事实存储。
4. 先部署代码但保持 `AI_AGENT_ORCHESTRATION_ENABLED=false`。
5. 灰度开启一个实例，验证 Outbox 延迟、Lease 接管、重复消息、回调幂等和 ACK 清理。
6. 扩大 Worker，并监控 `READY` 积压、过期 Lease、Outbox `DEAD`、callback `RETRYING`、端到端耗时。

## 必测故障场景

- Worker 领取后宕机：Lease 到期后其他 Worker 接管，旧 Worker 结果因 fencing token 被拒绝。
- Kafka 重复投递：任务 claim 或 callback claim 失败后安全跳过。
- Kafka 暂时不可用：Outbox 指数退避，达到上限进入 `DEAD` 并告警。
- Redis 清空：任务仍能从 MySQL 恢复；Inbox 索引由结果事件重建。
- 主 Agent 回调失败：callback 状态回到 `RETRYING`，Kafka 不 ACK 并进入重试链。
- 回调重试耗尽：消息进入 DLT，任务不写伪 ACK；告警后由运维重放原始结果事件。
- ACK 重放：已 ACKED 任务不重复改变权威结果，清理操作保持幂等。
