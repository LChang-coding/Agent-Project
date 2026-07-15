# 工作流有界并发执行优化计划

## 优化前证据

- 工作流整体使用 RxJava `Schedulers.io()`，该调度器面向 I/O 任务会按需扩张线程，不适合当前服务器紧张场景下的工作流总并发门禁。
- DAG 同层节点通过未显式指定执行器的 `CompletableFuture.supplyAsync` 执行，落入 JVM common pool，无独立队列、线程上限和运行指标边界。
- 节点内部使用阻塞式消费模型输出，突发工作流会放大线程、内存和下游连接竞争。

## 执行计划（执行前落盘）

1. 建立工作流专用的可配置有界线程池，使用小型 core/max、有界队列、空闲回收和明确拒绝策略，不与通用异步任务相互饿饿。
2. 工作流整体调度和 DAG 同层节点均显式使用该执行器，消除 `Schedulers.io()` 和 common pool 的无界扩张路径。
3. 保留 trace 传播，核对租户、用户、取消门禁与 DAG 依赖语义；进程关闭时正常释放线程池。
4. 补充配置默认值和线程池边界测试，执行 Java 17 定向回归，追加实录后中文提交。

## 验收条件

- 工作流不再使用 `Schedulers.io()` 或未指定执行器的 `supplyAsync`。
- 默认工作流线程和排队数有明确上限，且可通过环境变量调整。
- 过载时产生可预期背压，不静默丢任务；现有工作流和授权回归通过。

## 执行实录

- 主审发现并修正了首版单池设计的过载漏洞：同一池同时运行外层编排和内层节点时，`CallerRunsPolicy` 可能把饱和后的整个工作流转移到 HTTP/订阅线程，使总并发上限失真。因此最终拆分为 coordinator 与 node 两个职责明确的有界池。
- coordinator 默认 core/max/queue 为 `1/2/8`，使用 `ArrayBlockingQueue + AbortPolicy`；饱和后 `RejectedExecutionException` 明确进入响应流，不占用提交线程、不静默丢任务。
- node 默认 core/max/queue 为 `2/4/0`，使用 `SynchronousQueue`；满载时仅由 coordinator 调用线程同步执行，避免 coordinator 等待子任务产生嵌套饥饿。主审补充了关闭态显式拒绝，修复 JDK `CallerRunsPolicy` 在 executor 已关闭时可能静默丢任务的边界。
- `ChatService` 外层通过可取消的延迟 Flowable 提交 coordinator，取消订阅会 `Future.cancel(true)` 中断任务；DAG 的全部 `supplyAsync` 只使用 node executor，不再进入 common pool；代码中已无 `Schedulers.io()`。
- 两个池均为小型有界池、30 秒空闲回收、允许核心线程超时，并在 Spring 关闭时 shutdown；dev/test/prod 均可通过 `WORKFLOW_COORDINATOR_*` 和 `WORKFLOW_NODE_*` 环境变量调整。
- `TraceableThreadPoolExecutor` 在原 trace/MDC 传播基础上增加可信 tenant/user/username/role 的复制、恢复与任务后清理，避免复用线程串租户。
- 测试覆盖默认线程/队列边界、coordinator 饱和显式拒绝、node 饱和由 coordinator 同步背压且关闭后显式拒绝、双池 trace/tenant 传播与清理、Rx 取消中断、Spring 双池关闭生命周期。
- 实现阶段 Java 17 定向回归共 15 项全绿。主审加入关闭态拒绝断言后执行 Java 17 reactor `clean test`：App 侧 15 项与 Types 侧 `TraceContextTest` 3 项共 18/18 通过，0 failure / 0 error / 0 skip，`BUILD SUCCESS`，总耗时 7.656s。首次增量回归曾受共享 target 旧 `ChatService.class` 影响报反射字段不存在，clean reactor 复跑证明不是源码失败。
