# 流式运行状态读放大优化计划

## 优化前证据

- 流式 Agent 每 250ms 通过 `cancelled` 查询 `chat_run`，同时每个模型事件都调用 `requireExecutable`再查一次。
- 30 秒流仅定时轮询就至少约 120 次 SQL，事件越碎，额外查询越多。
- 工具实际外发前已有 `authorizeToolDispatch` 行锁授权，该强一致性路径不能被缓存替换。

## 执行计划（执行前落盘）

1. 在 `RunControlService` 中为 `requireExecutable/cancelled` 建立按 tenant/user/run 隔离的极短 TTL 只读快照，两个读路径共享同一次数据库结果。
2. TTL 不超过当前 250ms 取消检测周期；运行的启动、取消、引导、完成、失败和版本刷新都主动失效本地快照。
3. 快照容器需有容量和过期回收边界，不因历史 run 无限增长。
4. `authorizeToolDispatch` 仍直接数据库行锁；跨实例取消最多只容忍一个 TTL 的可见延迟。
5. 补单测验证高频读只查一次、TTL 后重查、状态变更立即失效，并回归取消/引导/工具门禁。
6. 追加实录后中文提交。

## 验收条件

- 流式运行状态 SQL 频率与 token/event 数解耦，单活动 run 不超过约 5 次/秒。
- 工具调用前仍在行锁下验证取消和上下文版本。
- 本机取消/引导后不等待 TTL，跨实例状态最多延迟一个 TTL 可见。

## 执行实录

- 实现结果：新增按 `tenant/user/run` 隔离的运行状态快照，默认 TTL `200ms`，构造器强制不得超过 200ms；支持空结果的短 TTL 缓存。
- 并发与容量：使用 64 段锁合并同一 run 的并发 miss，失效与加载使用同一段锁，防止旧查询在失效后回填；容量硬上限 2048，每 64 次读取机会清理过期项，写入时也会清理并淘汰最早快照。
- 一致性：`requireExecutable` 与 `cancelled` 共享快照；启动/恢复/引导、消息绑定、完成、失败、取消和上下文版本更新在事务提交后立即失效；事务回滚不会误失效。
- 强门禁：`authorizeToolDispatch` 未接入快照，仍以数据库行锁结果决定是否允许工具外发。
- 测试覆盖：200 次高频检查仅 1 次查库、TTL 到期重查、上下文版本及取消写后立即失效、工具行锁绕过旧快照、容量上限与过期回收。
- Java 17 验证命令：`JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home PATH=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home/bin:$PATH mvn -pl ai-agent-scaffold-app -am -Dtest=RunStateSnapshotCacheTest,RunControlServiceTest,RunExecutionGateTest,ToolDispatchAuthorizationServiceTest -Dsurefire.failIfNoSpecifiedTests=false test`。
- 验证结果：`BUILD SUCCESS`，共 16 个测试，0 failure / 0 error / 0 skip；新增快照测试单独复跑 6/6 通过，目标文件 `git diff --check` 通过。
