# 2026-07-16 性能与体验优化基线

## 采样说明

- 基准代码：`main` 分支 `d90fbe3`。
- 本地工程：Spring Boot 模块化单体 + Vue 3/Vite。
- 服务器：`CentOS-Server`，只读采样，不上传项目、不改配置、不重启服务。
- 本文档只记录可复现指标；密码、Token、证书路径细节和业务正文不写入文档。

## 本地应用资源基线

| 项目 | 优化前 | 证据 |
|---|---:|---|
| 通用线程池核心/最大线程 | 20 / 50 | `application-dev.yml` 与 `application-prod.yml` |
| 通用线程池队列 | 5000 | `application-dev.yml` 与 `application-prod.yml` |
| 线程 keep-alive | 5000 秒 | 配置值传入 `TimeUnit.SECONDS` |
| Hikari 目标最小空闲/最大连接 | 15 / 25 | `application-dev.yml`；当前层级为 `spring.hikari`，需验证实际绑定 |
| Logback 配置扫描周期 | 10 秒 | `logback-spring.xml` |
| INFO/WARN 异步日志队列 | 8192 / 1024 | `logback-spring.xml` |
| 应用日志理论总上限 | 17 GB | INFO 10 GB + WARN/ERROR 5 GB + 观测 2 GB |
| 启动观测样例 | 默认开启 | `ai.observability.sample.enabled: true` |
| 调度协调/执行批次 | 200 / 50 | `application.yml` |
| 本地调度兜底 | 默认关闭 | 避免与 XXL-JOB 重复唤醒 |

### 代码链路初始证据

- `ContextInsightService.query` 会加载会话全部有效消息以求最大序号，然后再调用上下文预览；会话越长，返回大文本与对象组装成本越高。
- 工具调用列表查询最多返回 100 条，内存有界，但用它统计总调用数会丢失 100 条之前的历史。
- `ScheduleReconciler` 每轮对取出的配置都重算 Cron、JSON 规范化与 SHA-256，并执行 upsert/update；当前查询只按 `last_reconciled_at` 排序，会周期性重写未变配置。
- `ScheduleDispatcher` 按任务逐条 claim 与执行，实际并发受 XXL-JOB 单机串行策略约束；心跳执行器只常驻 1 个 daemon 线程。

## Web 构建基线

`npm run build` 在基准代码上通过，当前 `dist` 总大小为 336,018 bytes。

| 产物 | 原始大小 | gzip |
|---|---:|---:|
| 首屏主 JS | 167.14 kB | 64.36 kB |
| 会话页 JS | 16.99 kB | 6.27 kB |
| 会话 Store JS | 14.28 kB | 4.40 kB |
| 会话页 CSS | 13.22 kB | 2.87 kB |
| 工作流页 JS | 19.57 kB | 6.96 kB |
| 定时任务页 JS | 11.14 kB | 4.43 kB |

### 交互与布局初始证据

- 页面已有部分按钮文字 loading，但缺少统一的进度指示、成功确认、禁用原因和全局消息容器。
- 会话工作台单文件同时承载会话轨、消息、上下文洞察、附件和组合器样式，样式超过千行，遮挡与响应式回归成本高。
- 当前路由页面已全部懒加载；主 chunk 主要承载 Vue、Router、Pinia、Axios 和全局布局，不应为追求分包数量而盲目拆分。

## 服务器与中间件基线

首轮只读 SSH 采样结果：

| 项目 | 优化前 |
|---|---:|
| CPU | 4 vCPU，load 0.10 / 0.16 / 0.20 |
| 内存 | 7.5 GiB，used 3.5 GiB，available 3.6 GiB |
| Swap | 0 |
| 根盘 | 30 GB，已用 10 GB（34%） |
| 额外数据盘 | 70 GB ext4，当前未挂载 |
| Docker images / build cache | 5.24 GB / 977.5 MB |

| 服务 | 优化前内存 | 线程/PID或限制 | 数据/日志体量 |
|---|---:|---|---:|
| Nacos | 754 MiB | 282 PIDs，JVM Xms256m/Xmx512m，容器无限制 | data 521 MB + logs 97 MB |
| Kafka | 551 MiB | 105 PIDs，JVM Xms=Xmx1G，容器 limit 1 GiB | data 58 MB + logs 7.2 MB |
| XXL-JOB Admin | 439 MiB | JVM Xms256m/Xmx512m，容器无限制 | 与专用 MySQL 分离 |
| XXL-JOB MySQL | 429 MiB | 容器无限制 | 205 MB |
| 宿主 MySQL | 684 MiB RSS | 宿主进程 | 226 MB |
| MinIO | 294 MiB | 容器无限制 | 136 KB |
| Loki | 135 MiB | 容器无限制 | 31 MB |
| Grafana | 119 MiB | 容器无限制 | 1 MB |
| 宿主 Redis | 15 MiB | 宿主进程 | 待补 |

健康性：Grafana 数据库检查正常，Loki ready，XXL-JOB Admin 返回登录跳转，XXL-JOB MySQL 容器 healthy，MinIO live/ready 请求成功；Nacos readiness 首次 5 秒超时，需要复核。Kafka 为单节点 KRaft，默认 6 分区、副本 1、禁止自动建 Topic。

结构性风险：除 Kafka 外的容器均无 CPU/内存/PID 限制；Kafka 堆上限等于容器内存上限，没有给直接内存、线程栈、page cache 留余量；宿主无 Swap，当突发并发或后台 compaction 发生时容易直接 OOM kill。70 GB 数据盘未挂载，Docker 数据和中间件持久化内容仍竞争 30 GB 根盘。

补充证据：

- Kafka 容器内执行只读 Topic 列表 CLI 时，CLI JVM 因容器余量不足发生 heap OOM；Broker 仍 running，未被 OOMKilled。这直接证明 1 GiB 容器上限与 1 GiB Broker 堆的组合没有运维余量。
- XXL-JOB 库内当前 2 个任务均停用；现存 159 条任务日志全部 `trigger_code=500/handle_code=0`，说明失败发生在调度到执行器之前，不应直接重新启用。
- Loki 自身数据仅 31 MB，但它的 Docker json 日志已达 110.4 MB，约 2 天就超过时序数据本身；配置未见明确 `retention_period/retention_enabled`。
- 宿主 MySQL 默认 buffer pool 128 MiB、`max_connections=151`、slow log 关闭；运行态认证失败后没有绕过权限。XXL-JOB MySQL 当前 connected/running 线程 11/2，慢查询为 0。

## 后端热点基线

| 优先级 | 链路 | 优化前行为 | 目标指标 |
|---|---|---|---|
| P0 | 流式取消检测 | 每 250ms 查取消状态，同时每个模型事件再查可执行状态；30s 流仅轮询就至少 120 次 SQL | 单流控制查询 <= 2–5 次/秒，并与 token/event 数解耦 |
| P0 | 工具前压缩 | 未 claim 时最多等待 5s，每 50ms 查任务，最多约 100 次 SQL | 取消热轮询，单等待查询 < 10 |
| P0 | 工作流并发 | Rx `Schedulers.io()` + `CompletableFuture` commonPool + worker 内 `blockingForEach` | 显式有界 executor，fan-out、队列与拒绝可观测 |
| P1 | Context Insight | 全量消息 + 预览重复读取 + 工具/附件实体列表计数 | MAX/COUNT/聚合查询，统计 SQL 返回 O(1) 行 |
| P1 | 附件与 Skill | 附件上下文 `SELECT a.*` 无 limit；ZIP 中 `SKILL.md` 使用 `readAllBytes()` | 正文/累计字节上限，ZIP 流式硬限制 |
| P1 | MinIO | 每次操作创建 Client，上传前每次 `bucketExists` | 复用单例 Client，桶存在结果成功缓存 |
| P1 | 调度派发 | claim 后同步执行完整任务才继续下一条 | 显式小型有界并发，保留 fencing/单 task 串行 |
| P1 | 调度 SQL | `ORDER BY COALESCE(...)` 与现有索引列不一致 | 用真实数据 `EXPLAIN ANALYZE` 验证 filesort/rows examined 后修正 |

## 初始优先级

### P0：低风险、立即收益

1. 收敛应用线程池、Hikari 连接池、Logback 队列与保留上限，关闭默认启动样例。
2. 使用数据库聚合查询替换洞察页面的全量消息/附件/工具记录加载。
3. 让调度协调只拾取未协调或已变更配置，避免对稳定配置持续 upsert。
4. 前端建立统一操作通知和按钮忙碌态，先覆盖会话、调度、资产与工作流高频路径。

### P1：需专项测试

1. 会话长列表渲染窗口化/分段加载与洞察请求合并。
2. 上下文 Token 计算结果复用、附件解析并发边界和工具调用日志写入成本。
3. 根据服务器实测收敛中间件 JVM/容器限制与日志保留。

### P2：需运行时样本或容量增长后再做

1. Kafka Topic 分区/副本变更、MySQL 大规模表结构改造和 MinIO 对象清理。
2. 需中断服务的容器重编排或中间件大版本升级。

## 待补证据

- 服务器同一时间窗的整机/容器指标。
- MySQL 实际连接、缓冲池与慢查询状态。
- Kafka/Nacos/XXL-JOB JVM 实际堆与重启参数。
- Loki/Grafana/MinIO 数据目录体量和保留策略。
- 有用户数据时的会话长度分布、洞察接口耗时和每轮 SQL 数。

## Web 问题优先级与验收口径

### P1 热点

1. 长会话会循环分页到尽头后全量渲染；流式每字符更新又对所有消息 `map + join`，并每次 `nextTick + smooth scroll`。
2. 分享提示条作为第四个直接子元素插入只定义三行的 `.chat-stage` Grid，会将消息区/组合器推入隐式行并被 `overflow:hidden` 裁切。
3. 会话切换时 session watcher 与显式 `createSession/switchSession` 重复加载 tool calls；附件 watcher 再单独加载 assets。
4. “刷新运行目标”会级联扫描全部会话页，并刷新工具目录，动作范围超出按钮语义。
5. Workflow 保存/发布缺少互斥，快速切换 Workflow 时旧请求可覆盖新详情。
6. Skill/MCP/Schedule 行级发布、禁用、测试、触发缺少按资源维度的 pending/error/防重。
7. Skill/MCP 宽表在全局 `.table-card { overflow:hidden }` 下裁切右侧操作列，窄屏不可达。
8. 矮屏下洞察面板、可拉伸 textarea 与固定 viewport 高度共同挤压消息区，并由容器隐藏溢出。
9. z-index 仅以 10/20/30 零散声明，分享页使用未定义的颜色/阴影变量，成功和错误的视觉语义也不完全一致。

### 量化验收口径

- 主 chunk gzip 目标 <= 50 KiB，单路由 JS gzip <= 10 KiB，总 JS 不高于当前 285.27 KiB。
- 会话切换除消息页外 <= 3 个 GET，相同 method+URL+params 在 100ms 窗口内重复数为 0；刷新运行目标不请求 sessions/messages。
- 长会话首屏 DOM 消息 <= 100；2000 条历史下切换首屏 P95 < 500ms，流式更新不产生 > 50ms long task。
- 所有写操作请求期间 100% 防重，100ms 内出现 pending，成功/失败有明确且颜色正确的反馈。
- 在 320x568、390x844、768x1024、1024x600、1366x768 下无页面级横向溢出、无操作列/组合器/提示被裁切。
