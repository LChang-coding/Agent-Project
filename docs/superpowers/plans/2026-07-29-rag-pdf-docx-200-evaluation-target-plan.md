# RAG PDF/DOCX 200 份细粒度评测目标计划

> 日期：2026-07-29
> 状态：执行中
> 约束来源：`codex.md`、既有 RAG Java/评测计划、现有 Canonical Document IR 链路

## 一、目标

在不上传本地项目、不迁移中间件、不伪造数据的前提下，完成 PDF 与 DOCX 两种格式各 200 份文档的可复现端到端评测，量化：

1. 原生格式从上传、解析、清洗、切块、Embedding、索引到召回的成功率、质量和性能。
2. 旧“统一压平成 Markdown 再处理”与当前“格式专用解析 + Canonical Document IR + 可逆 Cleaner + 结构感知切块”的差异。
3. 简单、中等、复杂文档在解析保真、召回、排序、引用和耗时上的差异。
4. Dense、Sparse、Hybrid/RRF、Rerank 及清洗/结构化切块组件开关的真实增益或伤害。
5. 每个重要失败问题对应的原文档、金标证据、实际文档块、候选排名、阶段分数、失败步骤和因果分析。
6. 数据集、运行配置、原始结果、汇总指标、失败证据和性能证据本地留痕，并将可查询的评测成果写入 MySQL。

## 二、数据集口径

### 2.1 数量硬门禁

- PDF：200 份。
- DOCX：200 份。
- 每份至少 1 个可判定问题；总问题数以实际可验证金标为准，不为了凑数量生成无证据问题。
- 同源配对文档使用稳定 `sourceDocumentId` 关联，避免把内容差异误判为格式差异。

### 2.2 分层

每种格式目标分布：

- 简单：80 份。
- 中等：70 份。
- 复杂：50 份。

复杂度由可复核特征决定：页数、标题层级、列表、表格、合并单元格、跨页内容、页眉页脚、脚注、公式、图片、多栏、阅读顺序、扫描/OCR 等；不得仅凭文件大小猜测。

### 2.3 来源策略

- PDF 以公开、带 QA/证据或结构标注的数据为主，优先 DocBench，并以 OmniDocBench/DocLayNet 的复杂版面样本补足结构评测。
- DOCX 使用公开原生 Word 文档、公开结构标注样本和同源可复现生成文档组合；没有公开金标的问题必须从可验证源内容建立规则型金标并做确定性校验。
- 下载前记录来源 URL、版本、许可证、原始哈希；下载后写本地 manifest。来源或许可证无法核实的文件不得进入正式结果。

## 三、实验矩阵

### 3.1 文档预处理消融

- `LEGACY_MARKDOWN_FLATTEN`：复现旧的统一 Markdown 派生文本路径。
- `RAW_TEXT_CHUNK`：解析文本最小清洗后直接切块。
- `IR_NO_CLEANER`：格式专用解析和 IR，关闭 Cleaner。
- `IR_NO_STRUCTURED_CHUNKING`：完整 Cleaner，关闭结构感知切块。
- `IR_FULL`：当前完整格式专用链路。

每个策略必须冻结 parser/cleaner/chunker/tokenizer/embedding/index schema 版本，禁止不同运行使用漂移配置。

### 3.2 检索消融

- Dense。
- Sparse。
- Dense + Sparse RRF。
- Dense + Sparse RRF + Rerank。

同一比较必须使用相同文档快照、问题集、TopK、候选数和超时口径。

## 四、指标

### 4.1 摄取与解析

- 成功、失败、降级、需人工复核、空文本和静默截断数量。
- 正文字符保留率、标题层级 F1、阅读顺序错误率、表格单元格 F1、编号/金额/日期保真率。
- 重复率、替换字符率、页眉页脚抑制准确率、chunk 金标覆盖率、source span/page 定位准确率。
- 解析、清洗、切块、Embedding、Qdrant 写入和总摄取延迟 P50/P90/P95/P99。

### 4.2 检索与回答证据

- Recall@1/3/5/10、Precision@K、MRR、nDCG@K、MAP、Hit Rate。
- 金标文档召回率、金标 chunk 召回率、证据覆盖率、引用定位正确率。
- 无结果、错误、降级、Rerank 救回和 Rerank 伤害数量。
- 查询 Embedding、Dense、Sparse、融合、Rerank、上下文预算和总延迟 P50/P90/P95/P99。

## 五、失败证据

每个选入报告的失败案例必须包含：

- `format/dataset/documentId/queryId/strategy/retrievalVariant`。
- 问题、标准答案、标准文档、标准页码/结构块和标准 chunk。
- 原文件相对路径及 SHA-256。
- parser 输出、Canonical IR、旧 Markdown、清洗审计、chunk manifest。
- 实际候选文档/chunk、排名、Dense/Sparse/Fusion/Rerank 分数。
- 首个失败阶段：解析、清洗、切块、Embedding、Dense、Sparse、融合、Rerank、预算或引用。
- 基于证据的原因、影响和可执行优化；未知原因明确标记为待验证。

## 六、成果目录与落库

本地目录：

```text
docs/rag/evaluation-data/pdf-docx-200/
  source/
  prepared/
  manifests/
  gold/
  licenses/
  scripts/

docs/rag/evaluation-results/pdf-docx-200/
  runs/
  metrics/
  failures/
  performance/
  final-report/
```

数据库至少保存：

- 数据集版本、manifest hash、文档/问题数量和来源摘要。
- 运行身份、Git SHA、配置 hash、策略、格式、复杂度、开始/结束时间和终态。
- 每条问题的金标、排名结果、质量指标、阶段延迟、错误码和降级状态。
- 失败案例分类、证据对象相对路径和内容 hash。
- 汇总指标和样本量；禁止只保存平均值而丢失原始明细。

敏感凭据不进入表、源码、测试产物或 Git。

## 七、执行阶段

1. 审计现有 benchmark、HTTP E2E、数据库表、IR/质量产物和运行环境，冻结可复用边界。
2. 完成数据下载器、许可证/哈希/数量/重复校验器和 200+200 分层 manifest。
3. 扩展 Java benchmark 与必要的应用测试接口/配置，使预处理和检索消融可由同一 harness 可重复执行。
4. 设计并迁移评测落库表，提供幂等写入、断点续跑和结果查询。
5. 先运行小规模冒烟；任何错误、降级、空结果或 hash 门禁失败必须停止扩大运行并先诊断。
6. 执行 PDF、DOCX 全量摄取和检索矩阵，持续保存原始 JSONL、数据库明细和阶段性能。
7. 程序化提取失败案例并人工复核代表样本；生成格式、复杂度、清洗和检索组件对照报告。
8. 验证本地备份、数据库行数/唯一性、manifest/config/result hash、测试与构建；追加真实操作记录并中文本地提交。

## 八、完成门禁

- PDF 200/200、DOCX 200/200 文件和 manifest 校验通过。
- 正式运行组合唯一、无重复计数；错误/降级/空结果必须单独报告，不能删除失败样本换取全绿。
- 数据库明细数量与原始 JSONL、manifest、问题集一致，随机抽样可从数据库回溯到本地原文件和 chunk 证据。
- 所有对照表只比较相同快照与相同问题，不跨运行拼接收益。
- 报告明确瓶颈、失败因果、优化建议和未覆盖边界。
- 相关 Java 单元/契约/集成测试通过，生产构建通过；全仓既有环境测试若失败，保留原始错误并说明与本轮关系。
- 形成至少一个重大闭环中文本地提交，不提交日志、对象存储运行目录、凭据和无关未跟踪文件。

## 九、操作记录

### 2026-07-29：启动审计

- 已读取 `codex.md`、既有 RAG Java/评测计划、现有 benchmark 文件清单和本地评测目录。
- 已确认既有 SciFact 质量评测不能证明 PDF/DOCX 原生摄取质量；既有三格式 E2E 只有每格式 1 份、5 个问题，只能作为冒烟基线。
- 已确认当前生产链路具备 Canonical Document IR、格式专用解析、可逆 Cleaner、结构感知分块、质量报告和影子 Generation 激活，可作为本轮新方案基线。
- 工作区已有 `RunControlService.java`、日志、RAG 审计文档和若干未跟踪运行目录改动；均视为用户/其他任务内容，本轮不覆盖、不清理、不混入提交。
