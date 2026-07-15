# 工具前压缩等待退避优化计划

## 优化前证据

- `ConversationMemoryService.waitForCompaction` 最多等待 5 秒，固定每 50ms 查询一次任务状态，竞争路径最多约 100 次 SQL。
- 该等待发生在工具调用前，会同时增加工具首调延迟和数据库负载。

## 执行计划（执行前落盘）

1. 将固定 50ms 轮询改为从 50ms 开始、指数增长且有最大间隔的退避，每次 sleep 不超过剩余 deadline。
2. 保留 SUCCEEDED/DEAD/STALE/CANCEL_REQUESTED/超时/线程中断的原语义。
3. 补充可控任务状态测试，验证成功等待与查询次数上限。
4. 执行上下文、工具前执行门和取消回归，追加实录后中文提交。

## 验收条件

- 5 秒等待窗口的任务查询不超过 15 次，显著低于原约 100 次。
- 任务成功、失败、取消和超时时的工具调用门禁不变。

## 执行实录

- 实现结果：轮询间隔由固定 `50ms` 改为 `50 → 100 → 200 → 400 → 800 → 1000ms`，之后封顶 1 秒；每次等待不超过 deadline 剩余时间。
- 语义核对：`SUCCEEDED`、`DEAD`、`STALE`、`CANCEL_REQUESTED`、超时与线程中断的处理保持不变，中断标记仍会恢复。
- 测试补充：覆盖第 4 次查询成功、5 秒超时查询上限、三类终止状态立即拒绝、线程中断四类回归。
- Java 17 验证命令：`OBS_LOG_DIR=/tmp/ai-agent-compaction-backoff-test JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn -pl ai-agent-scaffold-app -am -Dtest=ConversationMemoryServiceTest,RunExecutionGateTest,ContextInvalidationServiceTest -Dsurefire.failIfNoSpecifiedTests=false test`。
- 验证结果：`BUILD SUCCESS`，共 14 个测试，0 failure / 0 error / 0 skip；5 秒窗口查询次数验收上限为 15（正常调度约 9 次）。
- 静态检查：`git diff --check` 通过。
