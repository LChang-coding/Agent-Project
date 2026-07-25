# 日志与分析字段

`jsonl` 输出依次包含：

1. `meta`：实际 LogQL、查询起止时间、是否自动扩大窗口。
2. `entry`：UTC 时间戳、脱敏后的原始行、Loki labels、解析后的 logfmt fields。
3. `analysis`：仅基于当前返回日志推导的结构化摘要。

优先关联字段：

- 通用：`traceId`、`tenantId`、`userId`、`event`、`eventName`、`stage`。
- Agent：`runId`、`sessionId`、`workflowId`、`agentId`、`toolCallId`。
- RAG 检索：`retrievalId`、`queryId`、`inputCount`、`outputCount`、
  `costMs`、`degraded`、`errorCode`。
- RAG 摄取：`taskId`、`documentId`、`knowledgeBaseId`、`generation`、
  `chunkCount`、`attempt`、`outcome`。

诊断字段：

- `failures`：观察到 ERROR、错误码、`success=false` 或失败 outcome。
- `degradations`：明确带有 degraded 证据的记录。
- `cancellations`：明确取消事件或 outcome。
- `slowStages`：带 `costMs` 的最慢十个阶段，不代表根因。
- `incompleteStages`：当前时间窗内出现开始但没有匹配终态的阶段。
- `candidateFunnel`：带输入/输出数量的候选集变化。
- `terminalEvents`：业务事件名以 completed、failed 或 cancelled 结尾的终态。

日志缺失、时间窗截断、采集延迟都可能造成 `incompleteStages`，因此它是调查线索，
不是失败结论。
