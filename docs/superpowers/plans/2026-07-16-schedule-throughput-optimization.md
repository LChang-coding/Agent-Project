# 定时任务协调与派发吞吐优化计划

## 优化前证据

- `queryForReconcile` 仅按 `COALESCE(last_reconciled_at,...)` 排序，每轮会再次选中已稳定配置，重复执行 Cron/JSON/SHA-256、upsert 与 reconciled update。
- `ScheduleDispatcher.dispatchBatch` claim 一个任务后同步执行完整 handler 才继续，长任务会让同批其他到期任务排队；XXL 单机串行策略进一步放大等待。

## 执行计划（执行前落盘）

1. 协调查询只选未协调、摘要为空或配置更新时间晚于最后协调时间的脏配置，保留保存/启停时主动清空摘要的现有语义。
2. 为派发器增加默认 2 的小型可配置执行并发；每批最多只 claim 可立即执行的有界数量，等待本批完成后再返回 XXL，保留租约、fencing、单 occurrence 幂等。
3. 执行器有明确线程数、队列/拒绝和关闭边界；每个 worker 传播并清理可信租户身份，异常仍由现有 finishFailure 路径提交。
4. 补充稳定配置不重复协调、脏配置可再次协调、两任务并发、并发上限、关闭释放与原调度回归。

## 验收条件

- 稳定配置第二轮协调为 0，不再重复 upsert/update。
- 默认同批最多并行 2 个 handler，第三个不越过上限；dispatchBatch 返回时本批状态均已提交。
- 现有 misfire/retry/lease/fencing/租户语义不变。

## 执行实录

- 协调查询改为只选择 `config_hash` 空、`last_reconciled_at` 空或业务 `update_time > last_reconciled_at` 的配置；稳定配置第二轮返回 0。保存/启停继续通过清空摘要主动置脏。
- `updateReconciled` 增加 `expectedUpdateTime` CAS，避免协调期间用户修改被旧结果覆盖。SQL 显式 `update_time = update_time` 用于抑制该列的 `ON UPDATE CURRENT_TIMESTAMP` 自动推进，业务修改时间保持不变。
- 派发器新增 `dispatchConcurrency`，默认 2、环境变量 `AI_SCHEDULER_DISPATCH_CONCURRENCY` 可调且代码夹紧到 1–16。每个 wave 最多 claim 并提交 concurrency 个任务，等待本 wave 的成功/失败栅栏全部提交后才继续或返回 XXL。
- 新增固定大小 worker、容量等于并发数的有界队列和 `AbortPolicy`；批次锁避免同实例重复 dispatch。worker 沿用每任务 `executeWithIdentity` 的可信租户设置/清理，租约心跳仍由单 daemon 调度器承担。
- shutdown 先阻止新 claim/submit，再 `shutdownNow` 中断可中断 handler，并等待当前 batch 完成失败/成功结果提交后释放；已关闭实例不再查库。无法响应线程中断的第三方 handler 仍可能延迟进程关闭，最终风险文档需保留此限制。
- 测试新增稳定配置第二轮 0 次、变更后再次协调、业务 update_time 保留、默认两任务并发/第三任务下一 wave、claim 严格上限、dispatchBatch 等待整批、关闭后不 claim、关闭中断 handler 且失败栅栏落库。
- Java 17 定向 reactor 验证：`ScheduleDispatcherTest` 4 项、`ScheduleReconcilerTest` 2 项、`MyBatisMapperLoadTest` 1 项，共 7/7 通过，0 failure / 0 error / 0 skip，六模块 `BUILD SUCCESS`，总耗时 6.365s；目标文件 `git diff --check` 通过。
- 一次扩大 schedule 测试遇到并行子任务同时重写共享 target 导致的 class/Lombok 产物不一致；最终综合回归将使用无并行编译的 clean reactor 复验，不将该共享构建产物问题计为源码通过或失败。
- 主审在所有并行实现结束后执行调度+附件无并行 Java 17 `clean test`，共 35/35 通过，0 failure / 0 error / 0 skip，六模块 `BUILD SUCCESS`，总耗时 13.281s；由此排除共享 target 产物干扰。
