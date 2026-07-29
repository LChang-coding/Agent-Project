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

### 2026-07-29：第二批执行计划——预处理消融与评测落库

目标：让五种预处理策略在不改变生产默认行为的前提下可重复运行，并建立离线评测专用的幂等落库闭环。

执行边界：

- 生产默认仍固定为 `IR_FULL`；任何非完整策略只能在显式开启 benchmark preprocessing mode 时启动，防止消融配置误入正常租户流量。
- 预处理策略按应用进程启动配置冻结，单次正式运行不在任务中途动态切换；每个策略使用独立知识库/运行身份，避免同一任务恢复时配置漂移。
- 现有 parser、对象存储、租约、取消、checkpoint、Embedding 和 Qdrant 链路保持不变，只在“解析完成后选择进入 Cleaner/IR/扁平化输入”和“分块方式”两个明确切点做消融。
- 评测表不复用在线 `rag_retrieval_record` 充当实验主表；在线表继续保存真实检索审计，新增 benchmark dataset/run/query-result/aggregate/failure 表保存实验身份、金标、原始排名、阶段延迟与失败证据引用。
- benchmark CLI 通过环境变量读取数据库连接信息，源码、SQL、日志和产物不得出现凭据。

本批计划：

1. 在领域层定义稳定的预处理策略枚举和构造规则，输出明确的 parser/cleaner/chunker strategy revision。
2. 在 `RagProperties.Worker` 增加受保护的 benchmark mode 与 preprocessing strategy，增加配置校验和脱敏摘要。
3. 将 `RagIngestWorker` 的清洗与分块切点提取为可单测的策略执行器；为五种策略建立契约测试，证明输入、Cleaner、结构信息和 chunk 结果确实不同。
4. 让 IR、质量报告、chunk manifest 和任务日志记录实际策略，保证失败可追溯；恢复 checkpoint 时校验策略，发现漂移立即失败而不是继续污染索引。
5. 新增评测数据库迁移，包含数据集、运行、逐问题结果、汇总、失败案例五类表及唯一键、hash、终态和查询索引。
6. benchmark 模块增加 JDBC 幂等写入/校验命令，先以本地构造结果做单元和 MySQL 契约验证，再接入格式批量运行结果。
7. 运行相关模块测试和构建，把实际变更、门禁和数据库验证结果追加到本节后中文提交。

本批实际操作与结果：

- 新增五种进程级预处理策略：`LEGACY_MARKDOWN_FLATTEN`、`RAW_TEXT_CHUNK`、`IR_NO_CLEANER`、`IR_NO_STRUCTURED_CHUNKING`、`IR_FULL`；每种策略都有稳定 revision，并写入 Document IR、质量报告、chunk manifest 和 checkpoint 恢复校验。
- 生产默认保持 `IR_FULL`。非完整策略必须同时显式启用 `benchmarkPreprocessingEnabled`；基准进程允许记录低质量处置后继续索引，正常生产进程仍执行 `NEEDS_REVIEW/REJECTED` 门禁，避免对照组被质量门禁提前截断。
- 新增可独立单测的 `DocumentPreprocessingStrategyExecutor`，明确控制 Cleaner 是否执行、结构块是否保留、是否退化为旧式 Markdown/纯文本扁平输入；`RagIngestWorker` 的解析、强制 OCR、对象存储、租约、取消和向量写入边界未改。
- 新增 `run-format` 生产黑盒命令：逐个上传 200 份 PDF 或 DOCX，等待每个任务终态，校验文档激活，将后端内部 documentId 映射回 SciFact sourceDocumentId，再执行 200×4 检索消融。任一摄取错误、检索错误、降级、空排名、未知文档或无效 Rerank 都立即触发门禁。
- 新增格式运行器 HTTP 模拟端到端测试：实际遍历 200 份 PDF、产生 200 条逐文档结果和 800 条检索记录，验证内部 ID 回映后四组 Recall@10 均为 1.0。该值仅是可控 HTTP 桩的映射正确性证明，不是产品 RAG 质量结果。
- 新增六张评测表迁移：dataset、run、document_result、query_result、aggregate、failure_case；完整保存运行身份、数据/配置/hash、逐文档摄取、逐问题质量与阶段延迟、汇总和失败证据引用。
- 新增 `persist-evaluation` JDBC 事务写入：同一 dataset manifest 冲突拒绝覆盖，200 条文档、800 条查询和 4 条汇总在提交前分别校验行数；H2 MySQL 模式集成测试连续写入两次，验证幂等后仍为 1/1/200/800/4 行。
- `mvn -pl ai-agent-scaffold-benchmark test`：54 个测试通过，0 失败、0 错误、0 跳过。
- Java 17 下运行 `DocumentPreprocessingStrategyExecutorTest`、`RagIngestWorkerTest`、`RagPropertiesTest`：32 个测试通过，0 失败、0 错误、0 跳过。Java 25 会触发现有 Byte Buddy 兼容问题，因此正式测试固定使用项目约束的 Java 17。
- 本批尚未把迁移应用到真实 MySQL，也尚未启动真实 PDF/DOCX 摄取；这些属于下一批运行环境门禁与正式实验，不将模拟测试数据冒充真实评测结果。

### 2026-07-29：第三批执行计划——真实环境门禁与 IR_FULL 正式运行

目标：在不上传本地项目、不迁移现有 MySQL 实例的前提下，把评测表应用到当前数据库，使用本机最新 Java 应用连接既有远端中间件，完成真实 PDF/DOCX 冒烟和 `IR_FULL` 正式运行。

执行顺序与停止条件：

1. 只读核验本机 8091 进程、MySQL SSH 隧道、RAG 模型/Docling/Qdrant/Kafka/MinIO 连通性及磁盘资源；不输出凭据，不改变远端中间件拓扑。
2. 通过现有本地数据库连接应用评测迁移；校验六张表存在、列/索引完整且既有在线 RAG 表未变化。
3. 用 Java 17 构建最新应用和 benchmark CLI；记录 Git SHA、JAR SHA-256、脱敏配置 SHA-256和运行命令模板。
4. 停止旧的本机 8091 进程并启动本提交构建的应用，生产策略固定 `IR_FULL`；完成健康检查、登录、单份 PDF 和单份 DOCX 的上传—摄取—检索—引用身份回映冒烟。
5. 冒烟中任一任务错误、超时、降级、空排名、模型不通或索引数量不一致，立即停止扩大运行，保存 taskId/traceId/文档/阶段和错误码后先修复。
6. 冒烟通过后依次执行 PDF 200 和 DOCX 200 的 `IR_FULL` 正式运行；每个运行使用独立知识库与 runId，原始产物写入项目内 `docs/rag/evaluation-results/`。
7. 每个正式运行完成后校验 200 条文档、800 条查询、4 个唯一变体、0 业务错误、0 静默降级、0 空结果、manifest/data/config/run hash，并事务落库。
8. 把真实数量、任务与运行身份、质量和阶段延迟、数据库行数、失败门禁及修复过程追加到本节；完成 IR_FULL 双格式闭环后中文本地提交。

真实启动阻塞诊断（执行中）：

- 最新应用 JAR 构建成功，但首次以 `java -jar` 启动在 Spring 初始化前终止；异常为 `Application.isExecutableFile` 对 JAR 内 `ZipPath` 调用 `toFile()`，抛出 `UnsupportedOperationException`。
- 根因范围已收敛到“本地观测脚本自动发现”辅助逻辑，不是 MySQL、Kafka、Qdrant、Embedding、Reranker 或 Docling 故障；旧 IntelliJ classpath 启动不会经过 ZipPath，因而此前未暴露。
- 修复计划：让脚本探测只接受默认文件系统中的真实文件，JAR 内资源直接跳过本地脚本自启动；增加 classpath/ZipPath 契约测试，重新执行 Java 17 测试、打包和 `java -jar` 启动门禁后再继续格式冒烟。

真实绑定门禁诊断（执行中）：

- PDF 与 DOCX 各一份已真实摄取并进入 `ready`；DOCX 首次用 curl 默认 `application/octet-stream` 被 `RAG_FILE_MIME_MISMATCH` 正确拒绝，改用规范 OOXML MIME 后成功。该拒绝证明格式/MIME 安全门禁有效，不计作解析链失败。
- 调试绑定拒绝 benchmark 生成的虚构 workflow target，错误为 `RAG_BINDING_TARGET_NOT_FOUND`。根因是当前领域层已要求目标属于当前租户且工作流必须发布，而旧 benchmark runner 仍直接拼接 targetId。
- 修复计划：benchmark 通过生产 Workflow API 为四个检索变体分别创建并发布最小合法工作流，再创建一对一 RAG binding；HTTP 桩和真实冒烟都验证“已发布目标—唯一 profile—唯一 binding”，禁止绕过目标授权。

### 2026-07-29：第四批执行计划——合法目标冒烟与双格式正式评测

目标：关闭可执行 JAR 与真实 Workflow 目标绑定两个运行阻塞，在真实本机应用和既有远端 RAG 中间件上取得可落库的 PDF/DOCX 质量与阶段延迟数据。

执行门禁：

1. 用 Java 17 重跑可执行 JAR 路径测试、benchmark 目标创建/发布测试和完整模块测试；任一失败不启动正式运行。
2. 重建应用与 CLI，记录新 Git/JAR/config hash；应用只在本机运行，Java 项目不得上传 RAG 服务器。
3. 通过生产 API 创建最小 Workflow 并发布，以返回的真实 workflowId 创建检索 profile/binding；用已 ready 的 PDF/DOCX 冒烟库执行一次真实检索。
4. 冒烟必须满足 HTTP/业务成功、`degraded=false`、排名与引用非空、文档身份可回映、阶段延迟字段存在；否则保留 traceId/taskId 后停止扩大运行。
5. 冒烟通过后，按 PDF `IR_FULL`、DOCX `IR_FULL` 顺序运行各 200 文档、200 问题和四个检索变体。长任务持续检查摄取错误、降级、空排名和进程存活。
6. 每个格式运行结束立即校验 manifest、200 条 document result、800 条 query result、四个唯一变体和全部 hash，再用评测 JDBC 事务写入六张表并回读计数。
7. 把真实质量指标、摄取/检索阶段 p50/p95/p99、资源瓶颈和失败案例对应源文档/chunk 追加到本计划及正式报告；禁止用模拟桩数据替代正式结果。

本批第一阶段实际操作与结果：

- 修复应用以可执行 JAR 启动时把 JAR 内 `ZipPath` 当成本地文件调用 `toFile()` 的问题：观测脚本探测现在只接受默认文件系统中的真实可执行文件，JAR 内资源明确跳过。
- 新增 `ApplicationTest`，分别验证本地可执行文件可识别、ZIP 文件系统路径不抛异常且不会被误判。Java 17 定向测试 1/1 通过。
- benchmark 不再构造虚假的 workflow targetId；四个检索变体分别通过生产 Workflow API 创建并发布最小工作流，再使用服务端返回的 workflowId 建立 RAG binding。
- `RagBenchmarkRunnerTest` 与 `RagFormatBenchmarkRunnerTest` 已覆盖工作流创建、发布、绑定和检索；benchmark 模块全量 54 个测试通过，0 失败、0 错误、0 跳过。
- 真实冒烟使用已完成摄取的 PDF/DOCX 同源知识库，通过生产 API 创建并发布工作流、创建 profile/binding 后执行检索：业务码 `0000`，`citations=2`，`degraded=false`，诊断候选 14；阶段耗时为 embedding 247 ms、dense 565 ms、sparse 68 ms、fusion 0 ms、rerank 870 ms、检索 total 2262 ms、service 2689 ms。
- 本次冒烟响应携带 traceId 与 retrievalId，可由日志 CLI 继续关联；未在计划或源码写入访问令牌、数据库密码或模型 API Key。

本批第二阶段实际操作与结果——PDF `IR_FULL`：

- 正式运行身份：`pdf-ir-full-20260729-224110`；代码 revision `6ad021a8a6e9dcb99d2648c24c9e95e31be7ad89`；config SHA-256 `f1e064aa829ae6c1a4b751a49e701dc0bbb53f0ee944d6691372b25c34a6d9e2`。
- 200 份 PDF 全部摄取完成，0 失败；复杂度分布 SIMPLE 80、MEDIUM 70、COMPLEX 50；共生成 530 个 chunk。逐文档摄取耗时最小 14,019 ms、平均 20,608.66 ms、最大 41,552 ms。
- 正式测量产生 800 条唯一“queryId × variant”记录，每个变体 200 条；另有 20 条 warmup 只保留在线审计、不计质量指标。800 条中 0 错误、0 降级、0 空排名、0 未知文档，Rerank 变体 200 条均记录有效 `rerankMs`。
- 产物 manifest 门禁通过：dataset manifest/tree hash 与冻结数据集一致；`run.jsonl` SHA-256 `839bc25bf08055824850a351f03f613fe4ffe3c6d05583ce3547936fefe1ab08`，`document-results.jsonl` SHA-256 `c06e6239e3ceeb7f68302f7c0cc2745b234a7cf53828a69bd64045f18ede6ed0`，均与 run manifest 相符。
- 真实检索质量：
  - dense：Recall@1/5/10 = 0.825/0.935/0.960，MRR@10 = 0.877109，nDCG@10 = 0.897501。
  - sparse：Recall@1/5/10 = 0.610/0.785/0.820，MRR@10 = 0.686562，nDCG@10 = 0.719277。
  - hybrid_rrf：Recall@1/5/10 = 0.775/0.885/0.925，MRR@10 = 0.827032，nDCG@10 = 0.850781。
  - hybrid_rrf_rerank：Recall@1/5/10 = 0.825/0.920/0.925，MRR@10 = 0.862222，nDCG@10 = 0.877817。
- 真实端到端检索耗时（mean/p50/p95/p99/max，单位 ms）：
  - dense：2462.495 / 2239 / 3920 / 4267 / 7520。
  - sparse：2162.820 / 1902 / 3577 / 4751 / 8449。
  - hybrid_rrf：2667.670 / 2393 / 4361 / 4968 / 5782。
  - hybrid_rrf_rerank：11074.600 / 10001 / 19442 / 31202 / 40551；其中 `rerankMs` 平均 8325.095 ms，是已证实的首要性能瓶颈。
- PDF 上的技术结论不是“组件越多越好”：Dense 的 Recall@10 最高；加入当前等权 RRF 后 Recall@10 下降 0.035，Rerank 没有恢复召回集合，只把 Recall@1 从 0.775 拉回 0.825，并以约 4.15 倍于 hybrid 的平均端到端耗时为代价。正式报告必须将其列为负收益/需调参项。
- 已通过 `persist-evaluation` 事务写入真实 MySQL，并回读确认：run 1、document_result 200、query_result 800、aggregate 4、failure_case 0；未把 warmup 或 HTTP 桩数据写入评测结果表。

### 2026-07-30：第五批执行计划——双格式失败案例、资源证据与正式报告

目标：把“质量未命中”从汇总比例下钻到可复核的问题、源文件、金标证据和在线候选 chunk，并完成双格式资源/瓶颈报告。

执行计划：

1. 增加格式数据集失败分析输入适配：从冻结的 `gold.jsonl`、`queries.jsonl` 和文档 manifest 确定性生成 reporter 所需的规范 query、文档正文和 document-map；每个文档同时保留 PDF/DOCX 本地相对路径。
2. 对 PDF、DOCX 两个 800 条 run 分别执行失败分类，至少覆盖 persistent miss、dense/sparse 单路差异、RRF 得失和 Rerank 重排增益/伤害；报告中明确“事实、推断、替代解释、证伪方法”。
3. 从每类选取代表性问题，通过各自真实 target 再跑 `diagnose-cases`，保存 Dense/Sparse/Fusion/Rerank 候选、chunkId、分数、阶段结果和 trace/retrieval 身份；禁止只凭词项重合宣称根因。
4. 对双格式同一 queryId 做配对比较，列出 PDF 命中而 DOCX 未命中、DOCX 命中而 PDF 未命中、两者都失败以及排序显著变化的实例，并链接对应源 PDF、DOCX。
5. 校验 2,487 个本机 JVM 样本和 1,712 个远端八容器样本，生成带文件 hash 的资源 evidence manifest；确认采样错误为空、容器前后状态/重启/OOM 无变化。
6. 输出正式中文报告，包含数据集边界、运行命令与线程/并发、摄取/质量/延迟、格式配对、技术点增益、资源峰值、失败案例因果、瓶颈和可执行优化建议。
7. 把真实 DOCX 结果、数据库回读、失败分析和报告路径追加到本计划，运行相关测试与完整产物门禁后做中文本地提交。

本批第一阶段实际操作与结果：

- 新增 `prepare-format-failure-inputs`：从冻结的 `gold.jsonl` 与 `manifests/documents.jsonl` 确定性生成失败分析所需的 `queries.jsonl`、`documents.md` 和 `document-map.jsonl`，并记录所有输入/输出 SHA-256。
- `document-map.jsonl` 同时保存每个 SciFact 文档对应的 PDF、DOCX 项目内相对路径；失败案例 JSON 与 Markdown 的 gold 文档和错误召回文档均继承这些路径，能够从 queryId 直接回溯到两种原文件。
- 输入构建器拒绝覆盖已有目录，校验 200 个 gold 文档均存在 PDF/DOCX 配对、文档 ID/标题/正文不空以及 manifest 记录数一致；单元测试覆盖确定性 hash、源路径和拒绝覆盖。
- Java 17 定向执行失败输入、失败报告、在线诊断和内部因果报告共 14 个测试，0 失败、0 错误、0 跳过。
- Java 17 执行 benchmark 模块完整测试并打包 CLI：55 个测试通过，0 失败、0 错误、0 跳过；生成 `ai-agent-scaffold-benchmark-cli-jar-with-dependencies.jar`。下一阶段只使用该已验证 CLI 生成真实 PDF/DOCX 失败证据。

在线内部诊断门禁失败与处理计划：

- PDF 诊断在首条 `queryId=1/dense` 停止，`completedRecordCount=0`，失败类型 `IllegalStateException`；没有把不健康响应写成有效证据，也没有继续请求后续案例。
- 该门禁仅说明当前调试响应至少违反以下条件之一：排名非空、`degraded=false`、诊断候选非空、未截断、声明数量与实际数量一致、每个候选含 `benchmarkDocumentId`。现阶段不能凭异常文本猜测具体条件。
- 处理顺序：先让 runner 在异常中输出不含正文/凭据的结构化健康摘要；增加单元测试证明异常能精确指出哪个条件失败；重建 CLI 后只重跑 `queryId=1` 冒烟。若仍失败，使用该响应的 retrievalId/traceId 查日志和在线检索记录，确认是诊断上限、文档身份 payload、绑定目标或服务端版本问题。
- 只有单查询四变体全部满足完整候选门禁后，才删除本次空的失败输出目录并新建不同 runId 的全量诊断；不得把失败目录覆盖为成功目录。
- 已实现脱敏健康摘要，包含 retrievalId、降级状态、排名数量、候选截断状态、声明/实际候选数量、最大采集数和缺失 benchmark 文档身份数量；定向测试 2 个通过并重建 CLI。下一步以该提交 revision 执行单查询冒烟。
- 单查询复跑得到精确事实：`retrievalId=ret_b1617d4d677249349273e5f072ea64a1`、`degraded=false`、最终排名 9、诊断候选 140/140、未截断、最大采集数 2048，但 140 个候选全部缺少由 heading marker 推导的 benchmark 文档身份。
- 根因已定位为诊断工具的身份回映假设不适配格式数据集：PDF/DOCX 解析清洗后 `headingPath` 不保证保留 benchmark marker；正式格式运行本来就是通过各自 `document-map.jsonl` 的 `internalDocumentId -> sourceDocumentId` 做权威回映。服务端候选仍携带 internal documentId，因此证据没有丢失。
- 修复方案：`diagnose-cases` 显式接收该正式运行的 document-map，以 internal documentId 回映 sourceDocumentId；若 heading marker 也存在则校验两种映射一致，未知 internal ID 或冲突仍立即失败。诊断 manifest 记录 document-map SHA-256，确保 PDF 与 DOCX 不会串库。
- 已实现权威 document-map 回映、未知 internal ID/双映射冲突门禁和 manifest hash；单元测试证明 heading marker 缺失时仍可由正式运行映射恢复 sourceDocumentId，未知文档仍失败。Java 17 定向测试 2 个通过并重建 CLI。
- PDF 全量代表案例在线诊断完成：21 个唯一问题、84 条四变体记录，0 请求错误、0 降级、0 截断；`diagnostic.jsonl` SHA-256 `0a82918471f1118d23a1bc748208ba077fccd7520e34c81abc20b44768621bd8`。
- 因果报告门禁在 `queryId=1/dense` 发现在线诊断最终排名与原正式 run 漂移后拒绝生成；这说明“当前内部候选”不能未经标注直接冒充“正式运行当时的内部状态”。
- 漂移诊断计划：程序化比较 84 条记录与原 run 的顺序相等、集合相等、Top10 交集和 gold 命中状态；若 gold 成败改变，则该 query 只能列为时间漂移证据、不得归因。若 gold 状态不变但顺序变化，因果报告需显式记录 drift 类型并只使用首个失败阶段的候选存在/缺失事实，不使用当前名次证明当时排序。任何放宽都必须有单测和报告醒目标识。
- 核验原始记录后排除真实排名漂移：原 run 的 `queryId=1/dense` 为 9 个 sourceDocumentId，诊断记录为相同顺序的 9 个 internalDocumentId；runner 只回映了内部候选，没有回映最终 citations 的 documentId，导致报告比较了两套身份空间。
- 已把最终排名也按同一正式 run document-map 回映；若返回值已经是合法 sourceDocumentId 则保持，既非 internal ID 也非 source ID 时失败。Java 17 对 runner 与因果 reporter 的 10 个定向测试通过并重建 CLI；需用新 revision 重跑诊断，旧 84 条保留为身份回映失败证据而不进入因果结论。
- PDF 新 revision 诊断完成 21 问题/84 记录，SHA-256 `1f1039b37ec4431d0b1b633661d70f48c1f0ed55c54c65840c06eef068771384`；报告校验 84/84 最终排名与正式 run 精确一致、`integrityHealthy=true`。首个可观测覆盖损失计数：融合阈值/TopK 30、Sparse raw TopK 1、无损失 53；Rerank 在 21 个代表问题中排序改善 9、伤害 5、中性 7。
- DOCX 在线诊断完成 23 问题/92 记录，SHA-256 `9b73f0e5e5bf20d27f087855d8820e699c6e9931e9fad752f4ed89f14189c1fe`；报告在 `queryId=1175/sparse/candidate_filter` 的 rank 连续性门禁停止。
- DOCX rank 门禁诊断计划：对该 query 的 Sparse raw、candidate_filter、context_filter 与最终阶段逐项核对 rank、chunkId、outcome 和分数；确认 candidate_filter 的 rank 是“阶段内重排位次”还是“保留上游位次”。若协议语义是保留上游位次，则验证应改成唯一、正数、单调且为上游 rank 子集，连续性只适用于会重新编号的阶段；必须增加含 rank 空洞和重复 rank 的正反测试。
- 源码与真实轨迹证明 `candidate_filter` 有两套合法 rank 语义：淘汰项记录融合阶段原 rank，保留项按过滤后次序重新编号；因此同一数字可分别出现在一个淘汰项和一个保留项上。`1175/sparse` 的 rank 8 正是“融合 rank 8 被重复内容淘汰 + 原融合 rank 9 压缩为保留 rank 8”。
- reporter 已改为：普通阶段仍要求正数、唯一、1..N 连续；candidate_filter 要求全 chunk 与 fusion 集合严格闭合、chunk 不重复、淘汰项 `(rank,chunkId)` 与 fusion 一致、仅 kept 子序列要求连续。新增正例覆盖合法重复 rank，反例覆盖 kept rank 空洞；Java 17 测试 10 个通过。
