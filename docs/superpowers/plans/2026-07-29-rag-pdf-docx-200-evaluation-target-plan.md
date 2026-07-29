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

### 2026-07-29：第一批执行计划——冻结同源格式数据集

目标：先建立可重复、可验证、可公平对比的 PDF/DOCX 各 200 份数据集，不在这一批启动远端摄取。

执行前门禁与已确认事实：

- SciFact 正式 prepared 数据含 300 个问题、339 条正例标注、283 个唯一金标文档；可以在保留每个入选问题全部正例的前提下确定性选出恰好 200 个问题和 200 个金标文档。
- PDF 与 DOCX 必须使用相同的 200 个源文档、问题和 qrels，保证格式/解析策略是对照实验的唯一主要变量。
- DocBench 数据下载依赖外部网盘且仓库内未发现可直接核验的正式许可证；TableBank 对数据再分发另有研究用途约束。本轮正式检索质量集不引入许可证不清晰的数据。
- 正式数据源固定为本地已校验的公开 SciFact 快照；源 URL、许可证、源文件哈希和派生算法写入 manifest。
- 版面复杂度为确定性生成的受控变量，报告中必须标注为“同源派生版面压力集”，不得表述成天然采集的真实办公文档。

本批实现：

1. 在 `ai-agent-scaffold-benchmark` 增加 PDFBox 与 Apache POI 依赖，版本与现有基础设施保持一致。
2. 增加 `prepare-formats` 命令，按稳定哈希选择 200 个正例闭包文档，并为 PDF、DOCX 分别生成简单 80、中等 70、复杂 50 份文件。
3. 输出 dataset manifest、document manifest、query JSONL、qrels TSV、gold JSONL、许可证与来源说明、逐文件 SHA-256；目标目录已存在时拒绝覆盖。
4. 复杂度模板至少覆盖标题层级、列表、表格、页眉页脚、分页与跨段内容；金标正文和稳定证据标识在两种格式中保持一致。
5. 增加确定性、数量分层、qrels 闭包、文件可读、文本/证据保留和防覆盖测试。
6. 生成项目内本地备份，运行模块测试并校验 manifest；完成后把实际命令、数量、哈希、测试结果和偏差追加在本节下方，再做中文本地提交。

本批实际操作与结果：

- 新增 Java CLI 命令 `prepare-formats`：只选择单金标且源文档不重复的问题，以稳定哈希和固定种子 `20260729` 选出 200 对问题/文档，避免未入选正例污染 qrels。
- 新增 Java CLI 命令 `validate-formats`：独立读取落盘 manifest，逐个重算 400 个文件的 SHA-256，实际使用 PDFBox/POI 打开文件并检查证据标识，同时校验 200 个源文档的 PDF/DOCX 配对、80/70/50 分层、问题/gold/qrels 闭包和整棵目录哈希。
- 新增格式生成单元测试，覆盖恰好 200 对、格式与复杂度数量、二次生成哈希一致、PDF/DOCX 可打开、证据标识可提取、qrels 闭包、输入不足和禁止覆盖。
- 首轮确定性测试发现 DOCX ZIP entry 时间戳随运行时间漂移；未放宽门禁，改为生成后固定 ZIP entry 的创建/访问/修改时间并以固定压缩级别重封装。修复后二次生成 tree hash 一致。
- 已生成本地备份 `docs/rag/evaluation-data/pdf-docx-200/`：PDF 200 份、DOCX 200 份、共约 2.6 MiB；每种格式简单 80、中等 70、复杂 50。
- 数据集身份：`scifact-paired-pdf-docx-200-v1`；派生算法：`scifact-paired-format-layout-v1`；数据目录 `treeSha256=4b6d97580c7f84d2e43f5ddcd3793c59b973540f114f5eaf1396a7648334d463`；dataset manifest 文件 `sha256=6c368107d66c192194933884d3cc0ac156283364f3a1a388cdafd6c69b9e12ac`。
- 独立全量校验结果：`valid=true`、格式文件 400、配对源文档 200、问题 200、qrels 200、失败 0。
- `mvn -pl ai-agent-scaffold-benchmark test`：52 个测试通过，0 失败、0 错误、0 跳过。
- 已确认该集是同源受控版面压力集，不代表真实世界原生 Office/PDF 分布；生成式答案没有金标，正式报告不得据此计算答案正确率或忠实度。
