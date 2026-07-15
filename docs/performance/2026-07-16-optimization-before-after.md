# 2026-07-16 全链路性能与体验优化前后对比

## 1. 结论摘要

本轮在不上传本地项目、不删除业务数据、不改变公网端口的前提下，完成了服务器容错、中间件资源边界、后端高频链路、长会话渲染和控制台交互反馈的闭环优化。

| 维度 | 优化前 | 优化后 | 结论 |
|---|---:|---:|---|
| 七个核心中间件内存 | 约 2848 MiB | 稳态约 2036–2048 MiB | 减少约 800–812 MiB，约 28% |
| 宿主 Swap | 0 | 2 GiB，swappiness=10，当前 used=0 | 增加突发 OOM 缓冲，不把 Swap 当常态内存 |
| Kafka Broker 堆 | Xms/Xmx=1G/1G，容器 1 GiB | 256m/512m，容器 1 GiB | 为直接内存、线程栈和运维 CLI 留出余量 |
| 工具前压缩 5 秒等待 | 最多约 100 次查询 | 指数退避，通常约 9 次、上界不超过 15 次 | 热轮询查询量降低约 85%–91% |
| 流式运行状态读取 | 250ms 轮询叠加模型事件查询 | 200ms、租户隔离、容量 2048 的快照并写后失效 | 查询频率与 token/event 数解耦 |
| Context Insight | 全量消息/附件/工具实体读取 | MAX/COUNT/COUNT DISTINCT 聚合 | 统计查询返回 O(1) 行 |
| 工作流执行 | IO/commonPool 混用、并发无统一边界 | coordinator 1/2/8 + node 2/4/0 有界双池 | 有背压、拒绝、取消、关闭和租户传播 |
| 长会话首屏 | 循环拉取到尽头并全量渲染 | 首次最近 50 条，游标加载，DOM 最多 100 条 | 长会话不再随历史总量线性占用 DOM |
| 前端主入口 | 167.14 kB / gzip 64.36 kB | 165.19 kB / gzip 63.61 kB | 小幅下降；未达到 gzip 50 KiB 目标 |
| 前端生产构建 | 843ms，336,018 bytes | 857ms，352,564 bytes | 构建稳定；新增完整功能使总产物增长 4.9% |

## 2. 服务器与中间件

### 2.1 资源与稳定性

| 服务 | 优化前内存 | 优化后稳定/末次采样 | 最终 memory / PID 上限 |
|---|---:|---:|---:|
| Kafka | 678 MiB（专项同口径峰值） | 427–460 MiB | 1 GiB / 192 |
| Nacos | 754 MiB | 592–622 MiB | 1.25 GiB / 384 |
| XXL-JOB Admin | 439 MiB | 265–294 MiB | 768 MiB / 128 |
| XXL-JOB MySQL | 429 MiB | 374–377 MiB | 1 GiB / 128 |
| MinIO | 294 MiB | 255–256 MiB | 640 MiB / 128 |
| Grafana | 119 MiB | 66–72 MiB | 384 MiB / 128 |
| Loki | 135 MiB | 48–63 MiB | 384 MiB / 128 |

最终只读复采：宿主总内存 8,057,884,672 bytes，available 4,220,452,864 bytes；2 GiB Swap 使用量为 0；根盘使用率 40%，可用 19,494,236,160 bytes。七个容器均为 `RestartCount=0`，XXL MySQL 为 healthy，Loki/Grafana/MinIO/Nacos 返回 200，XXL-JOB 返回预期登录跳转 302。

所有容器统一设置 Docker `json-file max-size=20m,max-file=3`。Loki 旧容器约 110.4 MB stdout 日志随 Compose 重建自然回收；没有手工删除 Loki 时序数据、挂载卷或业务记录。

### 2.2 Kafka 与可回滚性

- Kafka 从镜像默认 1G/1G 堆收敛为 256m/512m，并把 1 GiB memory、192 PID、20m×3 日志上限固化到服务器本地 `/root/ai-agent-kafka/docker-compose.yml`。
- KRaft 最终验证为 LeaderId=1、MaxFollowerLag=0；三个上下文压缩 Topic 均存在，公网 9094 可连接，日志无新增 ERROR/FATAL/OOM。
- 短期回滚容器为 `ai-agent-kafka-pre-compose-20260715-135146`；配置与 inspect 备份位于服务器 `/root/ai-agent-kafka/backups/20260715-135123`。
- Loki/Grafana、MinIO、Nacos、XXL-JOB 的 Compose 原目录均有时间戳备份，详见服务器专项计划。

## 3. 后端耗时链路

### 3.1 应用资源默认值

| 项目 | 优化前 | 优化后 |
|---|---:|---:|
| 通用线程池 core/max/queue | 20 / 50 / 5000 | 4 / 8 / 256 |
| 线程 keep-alive | 5000 秒 | 60 秒，并允许核心线程超时 |
| Hikari 最小/最大连接 | 15 / 25，且配置层级错误 | 2 / 10，修正到 `spring.datasource.hikari` |
| Logback 扫描 | 每 10 秒 | 关闭运行时扫描 |
| INFO/WARN/观测异步队列 | 8192 / 1024 / 大队列 | 1024 / 256 / 512 |
| 应用日志理论上限 | 17 GB | 2 GB |
| 观测样例 | 默认开启 | 默认关闭 |

MinIO 客户端改为服务内安全复用，桶存在成功结果缓存；最终回归又补上生产构造器显式装配，避免测试构造器影响 Spring 选择。

### 3.2 查询、轮询和并发

- Context Insight 使用数据库聚合计算消息最大序号、消息数、附件数和工具调用数，修复原工具列表最多 100 条导致统计不完整的问题。
- 工具前压缩等待由固定 50ms 改为 50/100/200/400/800/1000ms 有界退避；5 秒内查询从约 100 次降到通常约 9 次。
- 运行状态增加按 tenant/user/run 隔离的 200ms 快照和写后失效；取消轮询与模型事件校验共享快照，工具真正产生副作用前仍保留数据库行锁授权，不以缓存替代强一致闸门。
- 工作流使用独立有界 coordinator/node 执行器，不再使用 `Schedulers.io()` 或 `CompletableFuture` commonPool；支持拒绝、调用方背压、Rx 取消中断、Spring 关闭及租户上下文清理。
- 定时配置协调只扫描未协调或已变更记录，以更新时间 CAS 和 hash 保证 Cron 变更冲突更新；派发按默认并发 2、允许 1–16 的有限波次 claim/执行并推进下一次时间。
- 附件上下文先按最新 32 个候选、累计 131072 字符在 SQL 窗口中截断，再由领域层二次限额；Skill ZIP 最多 256 entries、`SKILL.md` 展开最多 1 MiB、8 KiB 流式读取和严格 UTF-8，消除无界 `readAllBytes()`。

### 3.3 已知运行边界

- 调度关闭会中断可中断 handler；若第三方 handler 主动吞掉中断，关闭仍可能被其拖延。
- 200ms 运行态缓存只优化读取，工具分发安全仍依赖数据库锁和状态迁移；不能擅自拉长为秒级缓存。
- MySQL 大表 `EXPLAIN ANALYZE`、真实生产会话 P95 和模型端到端延迟缺少足够业务样本，本轮没有制造虚假数字。

## 4. Web 性能与体验

### 4.1 交互闭环

- 会话发送、取消、引导、上传、删除和分享统一显示请求中、成功、失败和禁用原因；流式状态只观察最后一条消息，滚动用 `requestAnimationFrame` 合并。
- Workflow 保存/发布/删除互斥，详情请求带 generation，迟到响应不能覆盖新选择。
- Skill、MCP、Schedule 写操作按资源行防重，提供就地 pending/error/重试；`aria-live` 向辅助技术播报状态。
- 建立 content/sticky/nav/popover/modal/toast 语义层级，清理散落数字 z-index；宽表操作列桌面端 sticky，768px 以下切换为资源卡，320px 操作按钮可换行。
- 修复分享条额外 Grid 行挤压消息区/组合器的问题，并补充矮屏、窄屏的 overflow 和 composer 约束。

### 4.2 长会话与请求

- 切换会话立即清空旧消息并只加载最新 50 条；旧请求用 generation 隔离，历史以 sequence cursor 每次 50 条加载并去重。
- 可见窗口严格最多 100 条消息，历史前插保存滚动锚点；发送和流式期间固定回到最新窗口。
- 同一 method/URL/params 的短窗请求去重；会话切换不再由 watcher 和显式动作重复加载。
- 生产构建仍为按路由懒加载，未采用会增加总 JS 的 vendor 强拆实验。

### 4.3 构建对比与未达目标

| 产物 | 优化前 | 优化后 |
|---|---:|---:|
| 主入口 JS | 167.14 / gzip 64.36 kB | 165.19 / gzip 63.61 kB |
| Chat 路由 JS | 16.99 / gzip 6.27 kB | 21.48 / gzip 7.78 kB |
| Chat store JS | 14.28 / gzip 4.40 kB | 16.08 / gzip 4.79 kB |
| Workflow 路由 JS | 19.57 / gzip 6.96 kB | 20.50 / gzip 7.19 kB |
| Schedule 路由 JS | 11.14 / gzip 4.43 kB | 12.70 / gzip 4.86 kB |
| 全部 JS raw | 约 292,116 bytes | 302,058 bytes |
| 全部 CSS raw | 约 43,438 bytes | 50,044 bytes |
| dist 总量 | 336,018 bytes | 352,564 bytes |

新增取消、引导、分享、资产、长会话和控制台反馈后，总产物增长约 4.9%；主入口略降且每个路由 gzip 仍低于 10 KiB，但“主入口 gzip <=50 KiB”和“总 JS 不高于基线”没有达到。曾验证强制 vendor 分包能降低入口，却会增加总 JS，因此已回退，不用分包数字掩盖总体积增长。

## 5. 验证结果

- Java 17 定向 clean 回归：工作流 18/18、调度与附件 35/35，以及各专项测试均通过。
- Java 17 完整 reactor clean：共发现 138 项，124 项通过、14 项 error、0 failure、0 skip；完整应用上下文 `ApiTest` 1/1 通过。
- 14 项 error 分类：9 项演示测试没有 JUnit4 可执行方法；2 项旧 ChatService 测试缺少可信租户上下文；3 项自动配置测试缺少本地 `100001/100002` Agent Bean。这些夹具仍需后续治理，因此全量 Maven 命令最终状态如实记录为 `BUILD FAILURE`。
- 前端 `vue-tsc --noEmit && vite build` 通过：1914 modules，857ms。
- 服务器健康、容器限制、Swap、磁盘、公网服务与 Kafka 运行态均完成复核。
- 本地没有可用登录态/JWT，项目没有 Playwright，因此未执行真实浏览器 E2E、2000 条真实会话 P95 或像素级截图；静态断言、类型检查和生产构建已完成，但不能替代真实 E2E。

## 6. 未执行与后续建议

1. 70 GB 数据盘未挂载，Docker data-root 和中间件数据未迁移；该项需要独立停机、数据校验和回滚窗口。
2. 未删除历史镜像、build cache、Topic、Loki 数据或 MinIO 对象；所有删除动作仍需单独授权。
3. 建议下一阶段先修复 9 个演示测试的 JUnit 结构，并给 Chat/Agent 自动配置测试提供隔离 profile，使全量 `clean test` 可作为 CI 门禁。
4. 有可用测试账号后，补发送→流式→取消/引导→工具前闸门、长会话向前翻页、控制台失败重试的 Playwright E2E，并采集 2000 条会话的 P95/long task。
5. 主入口降到 gzip 50 KiB 需要重新评估框架共享依赖或页面壳拆分，不能仅靠手工 vendor chunk 达成。

## 7. 本阶段提交

`10d886e`、`341faa3`、`9b591b3`、`f763b4a`、`6e4f866`、`7b76e09`、`5ee7153`、`7eee93d`、`807832c`、`d7ccc15`、`491d96d`、`509ef79`、`c70aa34`、`d09ccee`，提交信息均为中文。最终报告提交号见本文件所在提交。

本轮服务器操作只涉及服务器本地中间件配置与运行态；没有向服务器上传本地项目源码、构建产物或资料。
