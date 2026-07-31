# RAG 测试总账报告计划

## 目标

生成一份截至 2026-07-31 的 RAG 模块测试总账级 Markdown 报告：覆盖所有可核验数据集、正式运行、指标、性能、失败语料、本地路径、落库证据、瓶颈和证据边界；不编造数据，不将失败重跑混入正式结果。

## 执行计划

1. 阅读 `codex.md`、既有 RAG 计划、报告和数据许可说明，冻结统计口径。
2. 盘点 `docs/rag/evaluation-data`、`evaluation-results`、benchmark 输出与本地原始语料，建立数据集、问题、qrels、gold/答案的路径清单。
3. 只从 completed manifest、metrics、run.jsonl、document-results、comparison 和已记录的数据库回读中汇总有效运行；单独列出失败/诊断/冒烟目录。
4. 汇总检索质量、摄取和分阶段延迟、重试、门禁、预处理策略差异；不存在的指标标记为“未测试”。
5. 建立失败语料总索引：问题、金标、原文件、切块、排名、retrieval/trace 和可证明的因果边界；如历史诊断缺失则显式说明。
6. 生成 `docs/rag/RAG模块全量测试总账-2026-07-31.md`，校验文件路径、数量、hash 和内部数值一致性。
7. 将实际操作、验证结果和未覆盖边界追加到本计划，仅对本次文档做中文本地提交。

## 门禁

- 任何总数必须能追溯到文件或已记录的数据库回读。
- 不删除、不重跑、不修改历史评测产物。
- 不提交日志、用户已有业务代码改动或无关未跟踪文件。
- 不把 evidenceText/qrels 写成标准生成答案。
- 对无资源时序、无候选分数、无答案金标的运行保持“未测试/不可归因”。

## 实际操作记录

### 2026-07-31 总账闭环

1. 已阅读 `codex.md`、`docs/rag/evaluation.md`、预处理闭环、历史完整报告、PDF/DOCX 50 份细粒度报告和各正式 run manifest，将 completed 结果与 failed/running/中间轮次分开。
2. 已盘点 SciFact、PDF/DOCX 200、PDF/DOCX 50、format-e2e、page-gold 与 Agent/Workflow fixture，在总账中显式列出原文、问题、qrels、evidence gold、license、manifest 和 hash 路径。
3. 已生成 `docs/rag/RAG模块全量测试总账-2026-07-31.md`，汇总 Markdown SciFact 1,200 条正式检索、PDF/DOCX 200 份全策略、50 份消融、三格式结构/页码、Agent/Workflow、删除/故障恢复、稳定负载和并发边界。
4. 已建立失败语料总索引：SciFact 失败分类、内部诊断、24 份代表性金标原文快照、PDF/DOCX 配对失败清单、内部阶段分析，并详解 query 914/560/830 的可证因果链。
5. 已明确证据边界：evidence/qrels 不冒充自然语言答案；不报告无金标的 Faithfulness/Answer Correctness；不把无同步资源时序的运行用于 CPU/内存归因。
6. 验证结果：总账 351 行、27,618 bytes；所有 Markdown 相对链接均存在；PDF/DOCX 200 数据集为 200 PDF + 200 DOCX + 200 queries + 200 evidence gold，50 数据集为 50 + 50 + 50 + 50；SciFact run.jsonl 为 1,200 行、1,200 个唯一组合、0 error、0 degraded、0 empty。
7. 本次未修改任何业务代码、历史评测产物或中间件；将只提交本计划与新增总账文档。
