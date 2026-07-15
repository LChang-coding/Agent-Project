# Context Insight 查询放大优化计划

## 优化前证据

- 每次查询先返回会话全部有效消息只为计算最大 `sequence_no`，随后 `preview` 再读上下文范围。
- 工具调用以最多 100 条实体在 Java 内统计，既传输 input/output JSON，又使总数在超过 100 时不准确。
- 附件计数使用上下文资产全字段列表 `.size()`，会读取 `extracted_text`。

## 执行计划（执行前落盘）

1. 在 Domain repository 增加最小聚合端口：有效消息最大序号、会话工具总调用数/去重工具数、上下文附件数。
2. 在 DAO/Mapper 用 `MAX/COUNT/COUNT DISTINCT` 实现，所有查询保留 tenant/user/session/validity/deleted 边界。
3. `ContextInsightService` 仅消费聚合数值，不再为统计加载消息正文、工具 JSON 和附件正文。
4. 补 Mapper 加载与 Service 测试，证明返回值和租户条件。
5. 追加执行实录，测试通过后中文提交。

## 验收条件

- 洞察统计查询的返回行数与会话长度无关，不加载 content/input_json/output_json/extracted_text。
- 超过 100 次工具调用的总数仍准确。
- 预览上下文语义、附件 token 和压缩状态不变。

## 执行实录

- 新增“有效消息最大序号”聚合端口和 `MAX(sequence_no)` SQL，与消息分配序号的原查询分离，严格限制 active/deleted/tenant/user/session。
- 新增 `ToolCallStatisticsEntity/PO`，使用单行 `COUNT(*)/COUNT(DISTINCT tool_id)` 替换最多 100 条工具实体列表，不再读取 input/output JSON，137 次调用测试统计正确。
- 新增上下文附件 `COUNT(*)`，完整保留原 JOIN、active/ready、用户消息、租户 null-safe 和序号范围，不再为 `.size()` 返回 `extracted_text`。
- `ContextInsightService` 改用三个聚合结果，保留 `memoryService.preview`、附件 token、压缩任务和其他上下文语义；long count 转 DTO int 时做饱和保护。
- Java 17 定向测试 `ContextInsightServiceTest`、`MyBatisMapperLoadTest`、`SessionDomainTest` 共 5 项全部通过；Mapper 测试校验了 tenant/null tenant、validity/deleted 以及聚合 SQL 不包含正文/JSON 列。
- 本闭环无数据库结构变更，回滚时恢复 Service 的列表统计与对应端口即可。
