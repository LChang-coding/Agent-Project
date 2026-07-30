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
- DOCX 因果报告在修正后的协议门禁下通过：92/92 最终排名与正式 run 精确一致、`integrityHealthy=true`。首个可观测覆盖损失：融合阈值/TopK 34、Dense raw TopK 1、Sparse raw TopK 1、无损失 56；Rerank 对 23 个代表问题排序改善 9、伤害 5、中性 9。
- 新增 `persist-failure-analysis`：只接受数据库中已完成 run，校验内部报告完整、全部最终排名一致、failure report hash 一致，再按 `runId + category + queryId` 稳定 failureId 幂等写入 `rag_benchmark_failure_case`。数据库保存 query、gold、变体、首个失败步骤、直接事实、内部损失、竞争文档、替代解释、证据路径及 hash。
- H2 MySQL 模式验证连续写入两次仍只有一行；不健康内部报告在任何数据库写入前被拒绝。Java 17 对 failure store 与内部 reporter 的 12 个测试通过并重建 CLI。
- 真实 MySQL 落库并回读通过：PDF 30 条失败分类记录、21 个唯一 query、7 类；DOCX 29 条、23 个唯一 query、7 类；全部证据 hash 长度 64。两次 run 的原 200 文档、800 查询、4 聚合记录保持不变。
- 首次生成双格式资源对照报告时，`remote-inspect-before/after.txt` 字节级相等门禁失败并停止输出。处理计划：检查 diff，只比较容器 ID、镜像、运行状态、OOMKilled、RestartCount 等稳定安全字段；Docker inspect 中自然变化的时间戳或健康历史只作为观测字段，不能导致“容器重启”误判。新规则必须对真实8容器全覆盖，并保留两个原文件 hash。
- diff 证明两个 inspect 文件只有首行采样时间不同，后续 8 个容器稳定字段逐行完全相等：RestartCount=0、OOMKilled=false、running、healthy、镜像未变。对照器现跳过首行观测时间，严格比较所有稳定行，并要求其数量与资源采样容器集合一致。
- 双格式配对报告已生成：`docs/rag/evaluation-results/pdf-docx-ir-full-comparison-20260730-r2/`。200 问题 × 4 变体均完成逐 query 配对；Dense 双命中 189、双失败 5、仅 PDF 命中 3、仅 DOCX 命中 3；Hybrid/Rerank 均为双命中 180、双失败 11、仅 PDF 5、仅 DOCX 4。
- 摄取对照：PDF 530 chunks、平均 20,608.660 ms、p95 31,332 ms；DOCX 706 chunks、平均 11,626.580 ms、p95 16,533 ms。DOCX 多 176 chunks（+33.2%），但在该派生集上摄取更快；不能据此把更多 chunk 直接解释成更慢。
- 资源证据：本机 JVM 2,487 样本、远端每个容器 1,712 样本、8 容器；采样错误文件为空。Reranker CPU mean/max 84.013%/455.790%、内存 mean/max 67.828%/67.920%，是计算与延迟首要热点；Embedding CPU mean/max 31.469%/455.660%、内存约 62%；Docling CPU mean/max 25.254%/375.200%。本机 JVM CPU mean/max 1.408%/30.6%，RSS max 571,040 KiB，说明瓶颈主要不在本机 Java CPU。
- 新增可复现 `compare-formats` 命令与 200 问题 × 4 变体的配对契约测试；测试同时验证格式独占命中、资源样本、稳定 inspect 字段与 Markdown 输出。
- Java 17 下 benchmark 模块完整测试与打包通过：60 个测试，0 失败、0 错误、0 跳过。提交范围只包含成功正式产物、成功内部诊断、资源证据、配对报告、工具代码和本计划；首次门禁失败目录保留本地但不混入正式成功产物提交。

### 2026-07-30：第六批执行计划——五种预处理策略双格式消融

目标：在同一 200 问题/200 文档快照、同一应用 JAR、同一模型与检索配置上，完成 `LEGACY_MARKDOWN_FLATTEN`、`RAW_TEXT_CHUNK`、`IR_NO_CLEANER`、`IR_NO_STRUCTURED_CHUNKING` 相对 `IR_FULL` 的 PDF/DOCX 全量消融，量化旧式 Markdown 压平、Cleaner 和结构感知分块的真实收益与代价。

执行顺序与门禁：

1. 冻结现有应用 JAR SHA-256、数据集 tree/manifest hash、模型服务和数据库连接；不重建包含其他用户未提交改动的应用 JAR，不上传 Java 项目到服务器。
2. 每个策略单独重启本机 8091 应用，显式设置 `benchmarkPreprocessingEnabled=true` 与唯一策略；健康检查、实际进程环境和一次小文档摄取均确认策略后，才启动 200 份正式运行。
3. 顺序按 `LEGACY_MARKDOWN_FLATTEN → RAW_TEXT_CHUNK → IR_NO_CLEANER → IR_NO_STRUCTURED_CHUNKING`；每个策略先 PDF 后 DOCX，禁止并行挤占 Docling/Embedding/Reranker。
4. 每个 run 必须满足 200 文档完成、800 个 query×variant 唯一记录、0 错误、0 降级、0 空结果、hash 一致，并立即落库；任何门禁失败先停止该策略后续格式并追加诊断。
5. config hash 由固定的“benchmark mode、strategy、strategy revision、应用 JAR hash、冻结数据集 hash”规范字符串计算；同策略 PDF/DOCX 必须相同，不同策略必须不同。
6. 每个策略保存逐文档耗时/chunk、逐 query 质量与阶段延迟；完成后以 `IR_FULL` 为共同基线输出组件增益矩阵、格式交互、失败转移和瓶颈。不得跨策略用不同 query 或不同快照拼接指标。
7. 旧式/消融运行不会替代生产默认；全部完成后恢复本机应用为 `IR_FULL`，执行健康检查和一条真实检索。

第六批首次运行门禁诊断计划（`pdf-legacy-markdown-20260730-030455`）：

- 首次进度检查错误地用不存在的 `success` 与 `chunkCount` 字段判定摄取失败，并在 8/200 时主动中断。实际记录协议使用 `status=completed`、`stage=completed`、`processedChunks/totalChunks` 与 `errorCode=null`；抽查前三条均为真实成功，分别写入 3、1、1 个 chunk。
- 本次中断属于评测监控器的字段口径错误，不是 RAG 摄取失败。该目录作为“人工中断的无效 run”保留，不进入汇总、不落库、不覆盖，也不计入正式结果。
- 修正后摄取门禁统一为：`status=completed AND stage=completed AND errorCode IS null AND processedChunks=totalChunks AND totalChunks>0`；检索门禁仍为 `errorCode` 为空、`degraded=false`、`rankedDocumentIds` 非空。
- 使用相同应用 JAR、策略 revision、config hash、数据集和随机种子新建不同 runId，从 0/200 重跑 PDF；完成前持续按正确协议检查，禁止复用或拼接前 8 条记录。

第六批第二次运行门禁诊断计划（`pdf-legacy-markdown-20260730-030732-r2`）：

- 该 run 已完成 200/200 文档、436 chunks、20/20 warmup，正式检索写入 57/800 后 runner 进程消失；已写记录仍为 0 错误、0 降级、0 空排名、57 个唯一组合，但 run 不完整，不能汇总或落库。
- 诊断顺序：先读取统一执行会话的退出码与标准输出，检查 `run-manifest.json` 状态和结果目录是否存在失败摘要；再按最后一条 query/variant 的 trace/retrieval 身份检查本机应用日志，区分 CLI 进程被外部终止、HTTP/模型异常、序列化异常或本机资源问题。
- 同时检查应用 8091、MySQL 隧道以及远端 Docling/Embedding/Reranker/Qdrant 健康；只做只读核验，不迁移 MySQL、不上传项目。
- 若属于 runner 自身异常，先补回归测试并修复 CLI；若属于瞬时外部错误，确认服务恢复且现有 runner 不支持安全断点后，用新 runId 从 0/200 重跑，禁止拼接 57 条部分结果。
- 当前部分目录作为失败证据保留，不覆盖、不落库；只有新的 200 文档、800 唯一组合、0 错误、0 降级、0 空结果 run 才能成为正式消融数据。

第六批第二次运行诊断事实与恢复门禁：

- runner 退出码为 1，明确异常为 `RAG_BENCHMARK_MEASURED_GATE_FAILED: sample_unhealthy`；manifest 已原子标记 `status=failed`，因此不是进程被外部杀死，也不是部分 run 被误判完成。
- 失败样本是 `queryId=1194` 的 `hybrid_rrf_rerank`。对应 `retrievalId=ret_c5fd7978068f46b3a397b76b72c4b1cc`、`traceId=e9eb6ad6-d37f-42d5-8800-104e62e6cf62`：Dense 100、Sparse 100、融合 10、候选加载和过滤均成功；Reranker 等待后失败，服务端以 `RAG_RERANK_UNAVAILABLE` 降级返回融合顺序，整条请求耗时 35,407 ms。严格 gate 拒绝写盘，因此 `run.jsonl` 只保留此前 57 条健康前缀。
- 公网端口 22、8082 均可建立 TCP；Reranker 最小探针首次在 5.014 秒收到 empty reply，随后四次均为 HTTP 200（0.135–0.161 秒），与瞬时连接被服务端关闭相符。Java 适配器已对 IOException/超时执行最多 2 次重试，本次是在重试耗尽后降级，不属于 runner 漏重试。
- 同一失败问题在服务恢复后连续重放三次 Rerank 变体均 HTTP 200、`degraded=false`、10 个 rerank 候选；总耗时 19.961、9.297、7.326 秒，Rerank 阶段分别 5.646、7.119、5.439 秒。第一次重放的 12.796 秒发生在 Embedding 阶段，说明远端模型链路存在显著冷/抖动尾延迟，但当前功能已恢复。
- SSH TCP 可达但服务器主动关闭密码会话，现有本机凭据暂时不能读取容器日志；这不阻塞通过公开模型网关和 Java 端全链路证据完成恢复确认，但容器级根因（网关 upstream reset、TEI 进程重启或宿主资源尖峰）仍标记为未证实，禁止臆测。
- 重跑前新增恢复门禁：用真实网关对 Reranker 执行连续批量探针，要求全部 HTTP 200、响应数量完整、无空响应；再用失败 query 连续执行三次 Java 全链路 Rerank，要求 0 降级。两项通过后才以新 runId 从 0/200 重跑。

第六批第三次运行实际结果——PDF `LEGACY_MARKDOWN_FLATTEN`：

- 恢复门禁通过后以全新 runId `pdf-legacy-markdown-20260730-041002-r3` 从 0 开始重跑；没有复用首次人工中断的 8 条记录，也没有拼接第二次失败运行的 57 条健康前缀。
- 运行身份保持冻结：代码 revision `6ad021a8a6e9dcb99d2648c24c9e95e31be7ad89`，应用 JAR SHA-256 `16123df8ce18d0ae4af84ff47175fc5172eb2fe3dbb3b0c1c28b44279aaff2c9`，策略 revision `legacy-markdown-flatten-v1`，config SHA-256 `3aa1a66b60140f32e8c50dbb46cc603b1a55992e4b44134b8f73ece4b962ba52`，数据集 manifest SHA-256 `6c368107d66c192194933884d3cc0ac156283364f3a1a388cdafd6c69b9e12ac`。
- 200/200 份 PDF 摄取完成，源文档、内部 documentId、taskId 均为 200 个唯一值；源文件 SHA-256 与冻结 documents manifest 200/200 一致。共生成 436 个 chunk，0 失败；摄取耗时 mean/p50/p95/p99/max 为 13,566.910/12,790/17,282/19,607/27,296 ms。
- 20 条 warmup 全部满足无错误、无降级、非空排名；正式运行产生 800 个唯一 `queryId × variant`，200 个唯一 query，每个变体 200 条，0 错误、0 降级、0 空结果、0 未知文档。
- `run.jsonl` SHA-256 为 `4b808a9dc80e2a653c01a1ff3b2d65caed8f4ce7367d72de32ce25b312c724a1`，`document-results.jsonl` SHA-256 为 `f2db1e6f302343e04b573d1747c98b6c4c9f41ca9e5003b884f84365a7255586`；两者均与完成态 run manifest 精确一致。完整运行总时长 4,997,195 ms。
- 真实质量：
  - dense：Recall@1/5/10 = 0.840/0.945/0.980，MRR@10 = 0.889143，nDCG@10 = 0.911210，MAP@10 = 0.889143。
  - sparse：Recall@1/5/10 = 0.640/0.775/0.825，MRR@10 = 0.701845，nDCG@10 = 0.731579，MAP@10 = 0.701845。
  - hybrid_rrf：Recall@1/5/10 = 0.755/0.895/0.910，MRR@10 = 0.819929，nDCG@10 = 0.842603，MAP@10 = 0.819929。
  - hybrid_rrf_rerank：Recall@1/5/10 = 0.845/0.910/0.910，MRR@10 = 0.871833，nDCG@10 = 0.881517，MAP@10 = 0.871833。
- 端到端检索耗时 mean/p50/p95/p99/max（ms）：
  - dense：1,549.475/1,506/1,940/2,234/2,851。
  - sparse：1,323.785/1,302/1,504/1,852/2,491。
  - hybrid_rrf：1,631.465/1,569/2,110/2,449/3,294。
  - hybrid_rrf_rerank：6,583.030/6,601/8,166/9,064/9,410；其中 Rerank 阶段平均 4,926.325 ms，占该变体平均端到端耗时约 74.8%，仍是首要延迟瓶颈。
- 旧版压平策略上也不是组件越多越好：相对 dense，等权 RRF 的 Recall@10 下降 0.070；Rerank 只能重排融合后 10 个候选，无法恢复已被融合裁掉的文档，因此 Recall@10 仍为 0.910。Rerank 将 hybrid 的 Recall@1 从 0.755 提升至 0.845，但平均延迟增加 4,951.565 ms（约 4.04 倍）。
- 已用 `persist-evaluation` 事务写入真实 MySQL，并独立回读确认：run 1、document_result 200、query_result 800、aggregate 4、failure_case 0；run 身份、格式、策略、revision、Git SHA、config hash 和 completed 状态均与本地产物一致。

第六批第四次执行计划——DOCX `LEGACY_MARKDOWN_FLATTEN`：

1. 复用已完成 PDF 正式运行的同一 8091 应用进程，不重建 JAR；再次核验实际进程环境为 `benchmarkPreprocessingEnabled=true`、`LEGACY_MARKDOWN_FLATTEN`，保持相同应用 JAR、策略 revision、config hash、数据集和随机种子。
2. 使用全新 runId `docx-legacy-markdown-20260730-053647` 从 0 摄取 200 份 DOCX；只按任务终态、分块完整性、错误码和源文件 SHA-256 判定健康，不复用任何其他格式或失败 run 的记录。
3. 摄取必须满足 200 个唯一源文档、内部 documentId 和 taskId、0 失败、全部 `processedChunks=totalChunks>0`；随后执行 20 条 warmup，要求 0 错误、0 降级、0 空排名。
4. 正式查询必须形成 200×4=800 个唯一组合，每个变体 200 条，0 错误、0 降级、0 空结果；首个异常立即停止并先追加诊断，不做样本级重试或结果拼接。
5. 完成后独立校验 run/document hash，汇总质量、逐阶段延迟和 chunk/摄取性能；用 `persist-evaluation` 事务落库并回读 1/200/800/4/0 行。
6. 把真实结果追加到本计划并中文本地提交；只有 PDF 与 DOCX 同策略都闭环后，才进入下一预处理策略。

DOCX `LEGACY_MARKDOWN_FLATTEN` 首次落库门禁诊断计划：

- 本地正式 run 已完成 200 文档和 800 查询，所有业务门禁与文件 hash 均通过；首次 `persist-evaluation` 执行会话没有返回标准输出，但数据库独立回读为 run/document/query/aggregate/failure = 0/0/0/0/0，不能认定落库成功。
- 先确认不存在仍在运行的持久化 JVM，避免并发重复写；再以同一只读输入和同一数据库环境直接执行 CLI，保留明确退出码与脱敏异常，不修改任何评测文件。
- 若 CLI 报数据/manifest/hash 门禁错误，停止并核对本地产物；若是数据库连接或瞬时事务错误，确认隧道和 MySQL 可用后利用既有唯一键/事务幂等语义重试。
- 重试后必须独立回读 1/200/800/4/0 和 run 身份；在此之前不提交 DOCX 正式产物、不进入下一策略。

DOCX `LEGACY_MARKDOWN_FLATTEN` 实际结果与落库诊断结论：

- 正式 run `docx-legacy-markdown-20260730-053647` 使用与 PDF 完全相同的代码 revision、应用 JAR、策略 revision、config hash、数据集和随机种子，从 0 独立运行；没有复用其他 run 的任何记录。
- 200/200 份 DOCX 摄取完成，源文档、内部 documentId、taskId 均为 200 个唯一值；源文件 SHA-256 与冻结 documents manifest 200/200 一致。共生成 501 个 chunk，0 失败；摄取耗时 mean/p50/p95/p99/max 为 9,483.180/9,087/13,273/14,929/23,560 ms。
- 20 条 warmup 全部健康；正式运行形成 800 个唯一 `queryId × variant`、200 个唯一 query、每个变体 200 条，0 错误、0 降级、0 空结果。
- `run.jsonl` SHA-256 为 `54ab887d21f0dc4ce6a452a3d04c881f7c2a809de970295fb0249574f71d5faa`，`document-results.jsonl` SHA-256 为 `67547b2aca133fda47efbcfa1d7d83595132c2573d270203f584a5d0d096d295`；两者均与完成态 manifest 一致。完整运行总时长 4,277,598 ms。
- 真实质量：
  - dense：Recall@1/5/10 = 0.825/0.940/0.960，MRR@10 = 0.876792，nDCG@10 = 0.897415，MAP@10 = 0.876792。
  - sparse：Recall@1/5/10 = 0.645/0.795/0.820，MRR@10 = 0.707264，nDCG@10 = 0.734857，MAP@10 = 0.707264。
  - hybrid_rrf：Recall@1/5/10 = 0.765/0.880/0.920，MRR@10 = 0.822907，nDCG@10 = 0.846708，MAP@10 = 0.822907。
  - hybrid_rrf_rerank：Recall@1/5/10 = 0.855/0.920/0.920，MRR@10 = 0.880000，nDCG@10 = 0.890080，MAP@10 = 0.880000。
- 端到端检索耗时 mean/p50/p95/p99/max（ms）：
  - dense：1,560.440/1,517/2,012/2,209/3,111。
  - sparse：1,361.365/1,327/1,657/2,149/2,379。
  - hybrid_rrf：1,634.350/1,577/2,154/2,578/2,659。
  - hybrid_rrf_rerank：7,020.835/6,898/8,694/9,570/10,847；其中 Rerank 阶段平均 5,342.560 ms，占平均端到端耗时约 76.1%。
- 同策略格式差异：DOCX 比 PDF 多 65 chunks（+14.9%），但摄取平均耗时少 4,083.730 ms（-30.1%）。PDF 的 dense Recall@10 高 0.020，DOCX 的 hybrid/rerank Recall@10 高 0.010；该差异来自解析/分块与索引内容交互，不能仅凭 chunk 数解释，需在最终跨策略失败转移中下钻。
- 首次“落库失败”是监控误判：异步执行器在 JDBC 事务仍运行时提前向上层返回，数据库被过早回读为 0 行；不存在 SQL 异常或回滚。改用直接命令会话等待约 44 秒后，CLI 明确返回 persisted，退出码 0。
- 最终真实 MySQL 独立回读为 run 1、document_result 200、query_result 800、aggregate 4、failure_case 0；run 身份、DOCX 格式、策略/revision、Git SHA、config hash 和 completed 状态全部一致。

### 2026-07-30：第七批执行计划——`RAW_TEXT_CHUNK` 双格式消融

目标：保持应用 JAR、数据集、模型/检索配置、随机种子和串行负载不变，只把预处理策略切换为 `RAW_TEXT_CHUNK`，完成 PDF/DOCX 各 200 文档与 800 查询，用于隔离“直接纯文本切块”相对旧式 Markdown 压平和完整 Document IR 的质量、chunk 与性能差异。

执行计划与门禁：

1. config 身份使用无空白、键排序的 UTF-8 JSON：`applicationJarSha256`、`benchmarkPreprocessingEnabled=true`、`datasetManifestSha256`、`preprocessingRevision`、`preprocessingStrategy`，不含格式和 runId，保证同策略 PDF/DOCX 相同。`RAW_TEXT_CHUNK/raw-text-chunk-v1` 的 config SHA-256 固定为 `4059e93db18b442727d9b518f8b0200a3140552c2798f94e76a6806c88b9ffcc`。
2. 只停止本机 8091 Java 进程，以原 JAR SHA-256 `16123df8ce18d0ae4af84ff47175fc5172eb2fe3dbb3b0c1c28b44279aaff2c9` 重启；设置 benchmark mode 与 `RAW_TEXT_CHUNK`，不上传项目、不修改远端中间件、不迁移 MySQL。
3. 通过实际进程环境、端口和生产 API 登录/小文档摄取确认策略进程健康；任一启动、认证、解析、Embedding 或索引错误先诊断，不扩大到 200 文档。
4. 按 PDF 后 DOCX 串行运行；每个格式均要求 200 个唯一文档/任务、源文件 SHA 200/200、分块完整、20 warmup 健康、800 个唯一查询组合、每变体 200 条、0 错误、0 降级、0 空结果。
5. 每个格式完成后校验 manifest 与结果 hash，汇总摄取/chunk、Recall/MRR/nDCG/MAP、端到端及阶段 P50/P95/P99，事务落库并独立回读 1/200/800/4/0，随后追加真实结果和中文提交。
6. 任一门禁失败不进入下一格式或策略；保留失败 run，先追加诊断计划并使用新 runId 从 0 重跑，禁止拼接。

第七批第一阶段实际结果——PDF `RAW_TEXT_CHUNK`：

- 策略切换前置校验最初错误地要求模型端点必须来自进程环境；实际模型/中间件配置由 Nacos `ai-agent-scaffold-app-dev.yml` 提供。校验在停止旧 PID 之前安全退出，旧 8091 未中断。修正为按真实环境来源校验后再切换。
- 第一次用后台子进程启动的新 JVM 已成功运行到 Tomcat 8091，但命令会话结束时被执行器回收；未开始正式 run、未产生有效评测记录。随后改用前台持久会话托管 JVM，独立日志目录为 `/private/tmp/rag-app-raw-text-20260730`。
- 实际进程 PID 92494，环境核验为 benchmark mode=true、`RAW_TEXT_CHUNK`；真实单 PDF 冒烟任务 `ragtask_cd9a91b27bca4c55821e6cc2f6a8efb5` 完成，1/1 chunk、无错误码。Java 项目未上传服务器，远端中间件拓扑未改。
- 正式 run 为 `pdf-raw-text-20260730-065633`。200/200 份 PDF 摄取成功，源文档、内部 documentId、taskId 各 200 个唯一值，源 SHA 200/200 一致；434 chunks、0 失败。摄取 mean/p50/p95/p99/max 为 13,536.610/12,889/17,396/21,131/26,950 ms。
- 20 条 warmup 全部健康；正式运行形成 800 个唯一 `queryId × variant`、每变体 200 条，0 错误、0 降级、0 空结果。`run.jsonl` SHA-256 `5071990bda94c8a10dff7067a3a395c8b87b55729e6697b1a779a527b2a46b78`，`document-results.jsonl` SHA-256 `f1d3cede817d797a4bb6a60ef2c99d138d3f63d01578bf42cdff4754e37e3b17`，均与 manifest 相符。
- 真实质量：
  - dense：Recall@1/5/10 = 0.840/0.955/0.970，MRR@10 = 0.888548，nDCG@10 = 0.908683，MAP@10 = 0.888548。
  - sparse：Recall@1/5/10 = 0.635/0.795/0.825，MRR@10 = 0.701720，nDCG@10 = 0.731890，MAP@10 = 0.701720。
  - hybrid_rrf：Recall@1/5/10 = 0.775/0.900/0.905，MRR@10 = 0.827964，nDCG@10 = 0.847419，MAP@10 = 0.827964。
  - hybrid_rrf_rerank：Recall@1/5/10 = 0.825/0.905/0.905，MRR@10 = 0.860583，nDCG@10 = 0.871943，MAP@10 = 0.860583。
- 端到端检索 mean/p50/p95/p99/max（ms）：
  - dense：1,662.795/1,604/2,097/2,863/3,500。
  - sparse：1,410.270/1,388/1,642/2,004/2,342。
  - hybrid_rrf：1,740.170/1,685/2,207/2,874/3,142。
  - hybrid_rrf_rerank：7,716.640/7,139/10,714/17,230/24,497；Rerank 阶段平均 5,922.895 ms，占端到端约 76.8%，并出现被原样保留的尾延迟。
- 相对 PDF 旧压平，纯文本只少 2 chunks（-0.46%），摄取平均仅快 30.300 ms（-0.22%）；两种扁平策略在该数据上的摄取规模与成本几乎一致。Dense Recall@10 低 0.010，Hybrid/Rerank Recall@10 各低 0.005，Sparse 相同。相对 `IR_FULL`，Dense Recall@10 高 0.010，但 Hybrid/Rerank 各低 0.020，说明结构/Cleaner 的影响与召回组件存在交互。
- 本轮检索耗时高于旧压平，但两轮发生在不同时间窗口且调用远端模型，不能把全部延迟差直接归因于预处理；质量与 chunk 是本策略消融的主要因果指标，延迟差作为生产观测保留。
- `persist-evaluation` 明确退出码 0 后，真实 MySQL 回读为 run 1、document_result 200、query_result 800、aggregate 4、failure_case 0；格式、策略/revision、config hash 和 completed 状态全部一致。

第七批第二阶段执行计划——DOCX `RAW_TEXT_CHUNK`：

1. 复用 PID 92494 的同一前台持久 JVM；启动前再次确认 8091 监听、benchmark mode=true、策略为 `RAW_TEXT_CHUNK`，不重建或重启，从而保持与 PDF 相同的 JAR 和进程配置。
2. 使用独立 runId `docx-raw-text-20260730-082725`，同一 config SHA-256 `4059e93db18b442727d9b518f8b0200a3140552c2798f94e76a6806c88b9ffcc`，从 0 摄取 200 份 DOCX。
3. 门禁保持为 200 个唯一文档/任务、SHA 200/200、分块完整、20 warmup 健康、800 个唯一查询组合、四变体各 200、0 错误、0 降级、0 空结果；任一失败停止且不拼接。
4. 完成后校验 hash、汇总质量和阶段延迟，与同策略 PDF、旧压平及 `IR_FULL` 做事实对照；等待 JDBC 命令明确退出后回读 1/200/800/4/0。
5. 把真实结果追加到本计划并中文本地提交；双格式闭环后才切换 `IR_NO_CLEANER`。

第七批第二阶段实际结果——DOCX `RAW_TEXT_CHUNK`：

- 正式 run `docx-raw-text-20260730-082725` 复用 PID 92494 的同一策略进程，JAR/revision/config hash 与 PDF 一致，从 0 独立执行。
- 200/200 份 DOCX 摄取完成，源文档、内部 documentId、taskId 各 200 个唯一值，源 SHA 200/200 一致；495 chunks、0 失败。摄取 mean/p50/p95/p99/max 为 9,822.955/9,376/12,668/13,793/16,415 ms。
- 20 条 warmup 全部健康；800 个唯一 `queryId × variant`、每变体 200 条，0 错误、0 降级、0 空结果。`run.jsonl` SHA-256 `dd0af42ef676b55847179ac270271e1674e7af6ae7b43848789a84d9a5f25c99`，`document-results.jsonl` SHA-256 `d8c88359f47fb154f4e62905c3308bba02e8ab56a6cda20ca8052716098cc243`，均与 manifest 相符。
- 真实质量：
  - dense：Recall@1/5/10 = 0.820/0.960/0.965，MRR@10 = 0.878375，nDCG@10 = 0.900058，MAP@10 = 0.878375。
  - sparse：Recall@1/5/10 = 0.635/0.800/0.830，MRR@10 = 0.700609，nDCG@10 = 0.732073，MAP@10 = 0.700609。
  - hybrid_rrf：Recall@1/5/10 = 0.785/0.890/0.910，MRR@10 = 0.833262，nDCG@10 = 0.852340，MAP@10 = 0.833262。
  - hybrid_rrf_rerank：Recall@1/5/10 = 0.845/0.910/0.910，MRR@10 = 0.870583，nDCG@10 = 0.880477，MAP@10 = 0.870583。
- 端到端检索 mean/p50/p95/p99/max（ms）：
  - dense：1,728.695/1,678/2,205/2,489/2,530。
  - sparse：1,511.190/1,468/1,852/2,033/2,329。
  - hybrid_rrf：1,853.160/1,781/2,334/3,249/4,064。
  - hybrid_rrf_rerank：8,696.750/8,431/11,893/14,356/15,056；Rerank 阶段平均 6,800.140 ms，占端到端约 78.2%。
- 相对 DOCX 旧压平，纯文本少 6 chunks（-1.2%），摄取平均慢 339.775 ms（+3.6%）；Dense Recall@10 高 0.005、Sparse 高 0.010，但 Hybrid/Rerank 各低 0.010。相对 DOCX `IR_FULL`，Dense 高 0.005、Sparse相同，Hybrid/Rerank 各低 0.010；融合覆盖损失再次证明单路局部提升不能替代最终策略评估。
- 同策略格式差异：DOCX 比 PDF 多 61 chunks（+14.1%），摄取平均快 3,713.655 ms（-27.4%）；DOCX Dense Recall@10 高 0.005、Sparse高 0.005、Hybrid/Rerank 各高 0.005，差异较小但可复核。
- 检索延迟较此前时间窗口继续上升，尤其 Rerank；由于预处理只影响离线索引内容、检索调用远端共享模型，跨时间延迟不作清洗策略因果结论，但保留为容量/稳定性证据。
- JDBC 明确完成后，真实 MySQL 回读 run 1、document_result 200、query_result 800、aggregate 4、failure_case 0；格式、策略/revision、config hash 和 completed 状态一致。

### 2026-07-30：第八批执行计划——`IR_NO_CLEANER` 双格式消融

目标：保留 Document IR 和结构感知分块，只关闭 Cleaner，直接量化去页眉页脚、断词/空白修复、重复内容处置等清洗对 PDF/DOCX chunk、召回与排序的真实影响。

执行计划与门禁：

1. 固定策略 `IR_NO_CLEANER`、revision `document-ir-no-cleaner-v1`，按第七批相同规范 JSON 计算 config SHA-256 `3c4ecb1b9715fb7142b4c87749008e0a5385e3e7b04562c04f913fa16fff5fd4`。
2. 正常终止当前前台 JVM，会话确认退出后，以相同 JAR、数据库/Nacos 配置、单 Worker 和 benchmark mode 前台启动新策略；等待 8091，核验实际进程环境并完成一份真实 PDF 冒烟。
3. 冒烟必须 completed、分块完整、无错误码；随后按 PDF → DOCX 串行执行，每格式 200 文档、20 warmup、800 查询，保持相同数据集、随机种子、四个检索变体与请求超时。
4. 严格门禁为 200 个唯一源/内部文档/任务、SHA 200/200、0 摄取失败、800 唯一组合、每变体 200、0 查询错误、0 降级、0 空结果、manifest/hash 一致。
5. 每格式完成后等待 JDBC 明确退出并回读 1/200/800/4/0；追加 Cleaner 开/关的 PDF、DOCX 配对差异和性能事实后中文提交。
6. 任一失败停止扩大运行，先追加诊断计划；失败 run 不落库、不拼接、不覆盖。

第八批 PDF 正式 runId 固定为 `pdf-ir-no-cleaner-20260730-095323`；只在上述真实 PDF 冒烟 completed、1/1 chunk、无错误后启动。

#### 第八批 PDF 失败诊断与重跑计划（2026-07-30）

事实门禁：

- `pdf-ir-no-cleaner-20260730-095323` 在第 45 份文档终止；完成态 manifest 为 `failed`，错误码 `RAG_BENCHMARK_HTTP_500`，仅 44/200 文档成功，0/800 查询执行。
- 唯一失败输入为 `prepared/pdf/173-scifact-14079881.pdf`，源文档 ID `14079881`，任务 `ragtask_9d71835274d74530a63e48a1f4365e13`；结果停在 `upload_or_poll`，0/0 chunks。
- 应用日志同时出现 JDBC `Communications link failure`、`Connection refused`、Hikari `total=0`，说明本机到 MySQL 的 13306 隧道已经中断；本轮不是可接受的策略质量结果，不落库、不拼接、不覆盖。

处理计划：

1. 保留失败目录作为诊断证据，确认评测进程已终止；检查 13306 监听、SSH 隧道进程、MySQL 握手和一次只读查询，不迁移 MySQL、不修改远端数据。
2. 若隧道丢失，使用 `codex.md` 既有 SSH 配置和数据库身份重建本机端口转发；敏感值只从本地配置读取，不写入命令输出、评测产物或提交。
3. 隧道恢复后观察 Hikari 重新建连；执行应用健康检查，并重新摄取同一失败 PDF，必须得到 completed、chunks 完整、无错误码，证明故障已解除。
4. 使用全新 runId 从第 1 份文档开始重跑 PDF `IR_NO_CLEANER`；不得复用前 44 份结果。仍执行 200 文档、20 warmup、800 查询及全部 hash/唯一性/错误门禁。
5. 仅当新 run 全部门禁通过后持久化并独立回读 1/200/800/4/0，再追加真实结果并中文提交；若同一文档再次失败，则转为解析/策略级根因诊断，不继续扩大。

诊断处理结果与重跑执行计划：

- 13306 SSH 转发进程仍在，但故障窗口内远端转发目标短暂拒绝连接；随后本机经同一隧道完成 MySQL 握手和 `SELECT 1`，没有迁移或修改数据库。
- 重新认证后，对原失败知识库与同一文件 `173-scifact-14079881.pdf` 定点复测：上传接口 200，任务 `ragtask_9d71835274d74530a63e48a1f4365e13` 最终为 `completed/completed`，7/7 chunks、无错误码。该事实表明文档解析和 `IR_NO_CLEANER` 本身可完成，第一次失败来自数据库链路瞬断期间的 HTTP 500。
- 新正式 runId 固定为 `pdf-ir-no-cleaner-20260730-214250-r2`。从 0 创建新知识库并摄取全部 200 份 PDF，失败 run 的 44 条成功记录不得复制；执行前再次确认 8091、13306 和 MySQL 查询正常。
- 运行中持续门禁 `document-results.jsonl`；任何非 completed/stage completed、错误码、0 chunk 或 chunk 数不一致立即停止。摄取通过后再检查 warmup 与 800 查询门禁，最终才允许落库。

#### 第八批 PDF 执行托管中断与第三次执行计划（2026-07-30）

- `pdf-ir-no-cleaner-20260730-214250-r2` 在 114/200 文档处停止；已有 114 条均 completed、chunks 完整、0 错误，尚未执行 warmup 和正式查询。
- 停止时 8091 应用 JVM 与 benchmark JVM 均不存在，应用日志最后停在正常 Docling 200 响应；13306 和 MySQL 仍健康。根因是前台统一命令会话随任务回合结束被执行环境回收，不是 RAG 业务、输入文档或数据库门禁失败。
- 该未完成 run 不落库、不拼接、不覆盖。为避免再次被回合生命周期回收，应用与评测改由本机 `screen` 独立会话托管；会话只运行本地 Java 项目和 benchmark，不上传项目、不修改远端中间件。

第三次执行步骤：

1. 使用 `screen` 启动 `IR_NO_CLEANER` 应用，敏感数据库值仍从 `codex.md` 运行时读取；日志写入新的 `/private/tmp/rag-app-ir-no-cleaner-screen-20260730`，核验 8091、实际策略环境和 MySQL。
2. 重新对单 PDF 做健康摄取门禁；然后以新 runId `pdf-ir-no-cleaner-20260730-225435-r3` 从 0 执行 200 文档、20 warmup、800 查询。
3. 独立监控进程、manifest 和结果文件；运行期间即使当前交互回合结束，`screen` 会话必须继续。任何业务门禁失败仍立即终止并诊断。
4. 只有 r3 完整通过 hash、唯一性、0 错误/降级/空结果后才持久化；前两次失败/中断目录只作为可审计证据。

第三次执行阶段性事实：

- `screen` 托管后的 r3 已越过 45/200 关键位置；第 45 份正是第一次失败的 `prepared/pdf/173-scifact-14079881.pdf`。
- 该文档本次为 `completed/completed`、7/7 chunks、无错误码，耗时 45,395 ms；随后第 46 份也已完成，累计 46 个唯一源文档、内部文档和任务，0 无效记录。
- 同一输入、同一策略在数据库链路健康时稳定完成，进一步证实第一次 `RAG_BENCHMARK_HTTP_500` 不是确定性解析失败。正式结论仍以 200 文档与 800 查询全部完成为准。

#### 第八批 PDF r3 向量库故障诊断计划（2026-07-30）

事实门禁：

- r3 在 57/200 成功后终止；第 58 份为 `prepared/pdf/079-scifact-16056514.pdf`，任务 `ragtask_bc8beda0d84a4d5b9a0f7845ede3be85`。
- 任务最终为 `dead/indexing/RAG_QDRANT_UNAVAILABLE`，benchmark 对应 `RAG_BENCHMARK_INGEST_FAILED`；0/0 chunks，单任务等待 220,869 ms。57 份成功记录和该失败记录均不落库。
- 线程证据显示 Docling 已返回 200，失败前 Worker 停在 Qdrant `countVersion` 校验；因此本次门禁失败位于向量写入/版本计数阶段，不归因于 PDF 解析或 Cleaner 消融。
- 同时观察到 Hikari 剔除服务端关闭的旧连接，但 MySQL 独立查询正常；该现象单独保留为连接池配置风险，不与明确的 `RAG_QDRANT_UNAVAILABLE` 错误码混为同一根因。

处理计划：

1. 保留 r3 失败目录并确认 benchmark 已退出；只读检查 Qdrant HTTP/collections/节点资源与容器日志，核对故障时间窗，不修改向量数据。
2. 定点检查该任务的 checkpoint、向量写入与文档版本状态，确认是否已经部分写入；依赖既有幂等和代际隔离清理/重试，禁止手工伪造 completed。
3. 若 Qdrant 已恢复，使用新知识库重摄取同一 `079-scifact-16056514.pdf`，必须 completed、chunks 完整、无错误；若仍失败则继续向量库容量/连接诊断，不启动全量。
4. 针对 Hikari 关闭连接警告，读取当前 MySQL `wait_timeout` 与应用池 `maxLifetime/keepaliveTime` 的真实配置；如池寿命不短于服务端超时，则先按现有配置规范修正并验证，不通过盲目重启掩盖问题。
5. 所有依赖门禁恢复后，使用全新 runId 从 0 重跑；不得拼接 r3 的 57 条。只有 200/200、20 warmup、800 查询及 hash 门禁通过才落库。

诊断结果与恢复执行计划：

- Qdrant 容器已连续运行 12 天，restart=0、OOM=false、health=healthy；诊断时仅使用约 231 MiB/6 GiB、CPU 0.21%、36 PIDs，服务器磁盘 43%，不存在容量或进程重启证据。
- 服务器本机 20 次 `/collections` 探测全部 200，耗时 2–31 ms；开发机直连公网 10 次探测中 2 次为空响应，其余尾延迟最高 3.84 秒，超过应用 `AI_RAG_QDRANT_TIMEOUT=3s`。Qdrant 服务日志中的绝大多数请求为毫秒级，故障窗口有一次 upsert 服务端耗时 17.08 秒，且开发机公网出口在运行中发生变化。
- 使用持久 SSH 转发 `127.0.0.1:16333 -> RAG服务器127.0.0.1:6333` 后，30/30 探测成功，最大 0.68 秒。后续应用改用该本机端点，仍保留单请求 3 秒、2 次重试、总时限 30 秒，不通过放宽超时掩盖故障；本地项目仍不上传服务器。
- 失败任务 checkpoint 表明解析、1 个 chunk 的 Embedding 和向量 upsert 均已推进，最终死于索引校验。文档 active generation 仍为 0、版本 failed、索引未激活；临时 chunk 记录均 deleted，证明失败代次没有污染可检索数据。
- MySQL `wait_timeout/interactive_timeout=28800s`，Hikari `maxLifetime=1200s`、keepalive=300s，不存在池寿命长于服务端超时；关闭连接警告作为 SSH 转发切换/旧连接剔除风险记录，不调整正式参数。

恢复执行步骤：

1. 以 `AI_RAG_QDRANT_ENDPOINT=http://127.0.0.1:16333` 重启同一 JAR、同一 `IR_NO_CLEANER` 策略应用，确认隧道、8091、MySQL 和真实进程环境。
2. 在新知识库重摄取 `079-scifact-16056514.pdf`，要求 completed、分块完整、无错误；再连续执行 Qdrant 读探测，验证 3 秒门禁下无瞬断。
3. 定点门禁通过后再记录全新正式 runId，并从 0 运行；最终报告必须披露前六组使用公网直连、后续组使用 SSH 转发，因此跨时间摄取延迟只作生产观测，清洗策略因果主要依据质量和 chunk 差异。

恢复验证结果与 r4 执行计划：

- 新进程 PID 71510 的实际环境已核验为 benchmark mode=true、`IR_NO_CLEANER`、Qdrant endpoint=`http://127.0.0.1:16333`；8091、16333 和 MySQL 均正常。
- 原失败文件 `079-scifact-16056514.pdf` 在新知识库定点复测完成：任务 `ragtask_083cb0eba8524483ae73c1509ab6ea1f` 为 `completed/completed`、1/1 chunk、无错误码。
- 隧道端点随后连续探测 100 次，100/100 返回 200，平均 0.188 秒、最大 0.616 秒，均低于保留的 3 秒单请求超时。
- 第四次正式 runId 固定为 `pdf-ir-no-cleaner-20260730-235002-r4`。r4 从 0 创建知识库、摄取 200 份 PDF；不得读取或复制 r1/r2/r3 的文档结果。执行中继续检查 Qdrant 隧道进程，隧道消失或任一文档门禁失败立即停止。

#### 第八批 PDF r4 MySQL 转发故障诊断计划（2026-07-30）

事实门禁：

- r4 的第 1 份文档完成，第 2 份 `prepared/pdf/056-scifact-10300888.pdf` 在上传事务前失败，未生成 documentId/taskId；benchmark 等待 91,231 ms 后收到业务码 `0001`。
- 同一时间应用 Hikari 为 total=0，创建连接重试三次均收到 `ConnectException: Connection refused`，文档上传事务被回滚异常覆盖；该失败发生在业务记录创建阶段，与已经稳定的 Qdrant 隧道无关。
- r4 只完成 1/200、0 查询，不落库、不拼接。

处理计划：

1. 检查 13306 监听进程、SSH 转发父进程与远端 MySQL 容器/127.0.0.1:3306，区分本机转发进程退出、SSH 会话断开和远端容器拒绝。
2. 读取现有本机 LaunchAgent/SSH 保活配置；若 13306 不是由可自动重启的持久任务托管，修复为 `ExitOnForwardFailure`、keepalive 和自动重启的本机任务。不得迁移或重建 MySQL。
3. 连续执行至少 100 次本机 13306 `SELECT 1` 并跨越一次保活周期；同时验证应用 Hikari 能重建连接。
4. MySQL 稳定后在新知识库重传 r4 失败 PDF，必须产生 task 并 completed；再启动全新 run。不得靠增大上传 HTTP 超时掩盖数据库不可达。

转发根因与修复步骤：

- 两个 LaunchAgent 均配置 `BatchMode=yes + KeepAlive`，但当前密钥认证不可用；故障后累计约 8.7 万次退出码 255 的快速重启，13306/16333 均无稳定监听。
- 当前移动网络可以完成 TCP 和 SSH banner，但在客户端发送 KEXINIT 后丢包；使用 `IPQoS=none` 关闭 OpenSSH 默认 QoS 标记后，密码认证立即成功。远端 MySQL/Qdrant 均为 running/healthy、restart=0。
- 修复前先停止两条重连任务；随后把现有本机 ED25519 公钥幂等写入 RAG 服务器 authorized_keys，在 `RAG-Server` Host 与两份 LaunchAgent 参数中加入 `IPQoS=none`，保持 BatchMode、ExitOnForwardFailure 和 ServerAlive。
- 重新加载后要求两条隧道均监听、密钥登录不提示密码、LaunchAgent 在跨保活周期内 runs 不再快速增长；再执行 100 次 MySQL 查询和 100 次 Qdrant 请求。

修复验证结果与 r5 执行计划：

- 现有 ED25519 公钥已幂等加入 RAG 服务器，`BatchMode=yes` 密钥认证成功；`~/.ssh/config` 与两份 LaunchAgent 均使用 `IPQoS=none`。
- 两条 LaunchAgent 重载后各自 runs=1、last exit=never exited，13306/16333 均持续监听；MySQL 100/100 次独立连接查询成功，Qdrant 100/100 次请求成功，平均 0.399 秒、最大 1.288 秒。
- 应用 Hikari 已重新建立连接，认证事务成功；r4 失败文件 `056-scifact-10300888.pdf` 在新知识库定点复测为 `completed/completed`、1/1 chunk、无错误码。
- 隧道配置与诊断计划已提交为 `f272253`（`修复：稳定RAG评测SSH隧道`）。失败 r1-r4 目录和运行日志未提交。
- 第五次正式 runId 固定为 `pdf-ir-no-cleaner-20260731-000507-r5`。继续使用同一 JAR/策略/config hash，从 0 执行；运行中同时把 13306、16333 监听和 LaunchAgent runs 作为外部门禁。

#### 第八批 PDF r5 文档门禁失败诊断与第六次执行计划（2026-07-31）

事实边界：`pdf-ir-no-cleaner-20260731-000507-r5` 在完成 42 份文档后，第 43 份文档触发门禁；manifest 已标记 `failed`，`document-results.jsonl` 为 43 行且恰有 1 条无效记录，查询阶段尚未开始。r5 立即作废，不落库、不复制前 42 条成功记录。

1. 冻结 r5 目录，先读取唯一失败行的 sourceDocumentId、taskId、stage、errorCode 与错误信息，并通过同一 taskId/traceId 检索应用日志；同时核验 8091、13306、16333、应用 PID、两个 LaunchAgent 的 PID/runs/last-exit，区分解析、Embedding、Qdrant、MySQL和执行托管故障。
2. 若属于外部链路故障，先在不修改 JAR、策略、数据集和模型参数的前提下恢复并做连续探测；若属于确定性文档故障，则保存对应 PDF、解析产物和失败分块证据，修复代码前另行追加代码变更计划。不得通过提高重试次数或超时掩盖根因。
3. 使用 r5 的失败源文件建立全新临时知识库做定点复测；必须得到 completed、processedChunks=totalChunks>0、无错误码，并确认失败阶段日志闭环。临时结果不进入正式统计。
4. 只有在数据库、Qdrant、应用和定点复测全部通过后，才使用全新 runId 从第 1 份 PDF 开始第六次正式执行；保持 `IR_NO_CLEANER/document-ir-no-cleaner-v1`、config SHA-256 `3c4ecb1b9715fb7142b4c87749008e0a5385e3e7b04562c04f913fa16fff5fd4` 及全部评测门禁不变。
5. 第六次执行仍要求 200/200 文档、20 条 warmup、800 个唯一查询组合、0 错误、0 降级、0 空结果；只有完整校验和 JDBC 明确提交、数据库独立回读 1/200/800/4/0 后，才追加真实结果并中文提交。

诊断与恢复实际结果：

- r5 唯一失败源为复杂 PDF `prepared/pdf/196-scifact-13906581.pdf`（SHA-256 `cbb5e2cf027a3533f403046b8f37d91e25e3577f04330999f347cbd1e26a86e2`），task `ragtask_8eec305744984da499501c5b57d46915`；benchmark 在轮询阶段收到 HTTP 500。任务最终记录为 `failed/embedding`、0/6 chunks、`RAG_INGEST_LEASE_LOST`。
- 同一任务日志证明 Docling 已在 20,094 ms 内返回 HTTP 200；随后 Hikari 连续发现 MySQL 连接已关闭，任务轮询接口因无法取得 JDBC 连接而返回 500。因此 r5 失败发生在数据库转发瞬断及 lease 持久化阶段，不属于 PDF 解析、Cleaner 或切块算法失败。
- RAG 服务器 `rag-mysql` 容器持续 healthy、restart=0，MySQL `wait_timeout=28800`，排除容器重启和空闲超时；公网 `103.205.240.84:3306` 同口径 20/20 建连失败，不能作为替代链路。
- 两个 SSH LaunchAgent 的 `ServerAliveInterval` 从 15 秒收紧到 5 秒、`ServerAliveCountMax` 从 3 收紧到 2；应用使用 `MYSQL_POOL_KEEPALIVE_MS=30000`、`MYSQL_POOL_CONNECTION_TIMEOUT_MS=30000` 重启，JAR 和 RAG 策略未变。
- 主动杀死 MySQL tunnel PID 3892 后，LaunchAgent 自动启动 PID 4904，runs 1→2；故障窗口中的知识库 API 在 2,908 ms 内仍返回 HTTP 200/业务码 0000，随后 30/30 个 API 请求成功。
- 在全新知识库 `kb_4fecbe18384e4ae5a5414efc263fbb13` 对原失败 PDF 定点复测，task `ragtask_1378a508c9d9419f8a6c42b9bfe7ca04` 最终 `completed/completed`、6/6 chunks、无错误码。恢复后 MySQL 和 Qdrant 各 100 次独立探测均 100/100 成功；MySQL avg/max 479/1,454 ms，Qdrant avg/max 163/411 ms。

第六次正式执行身份：

1. 正式 runId 固定为 `pdf-ir-no-cleaner-20260731-004138-r6`，输出目录 `docs/rag/evaluation-results/pdf-ir-no-cleaner-20260731-004138-r6`，必须从空目录和第 1 份 PDF 开始。
2. 复用 PID 3938 的 JVM；实际环境必须为 benchmark mode=true、`IR_NO_CLEANER`、Qdrant `http://127.0.0.1:16333`、MySQL `127.0.0.1:13306`，JAR SHA、Git SHA、数据集 SHA、config SHA 继续使用冻结值。
3. 运行中同时监控文档门禁、查询门禁、8091/13306/16333 监听和两个 LaunchAgent 的 PID/runs；除上述主动故障注入造成的 MySQL runs=2 外，任何新增重启或业务失败均立即终止。
4. r6 完成前不落库、不复用 r1-r5 的任何结果；完成后执行 200 文档、800 查询、唯一组合、hash、0 错误/降级/空结果校验，再事务落库并独立回读。

#### 第八批 PDF r6 Qdrant 隧道门禁失败诊断计划（2026-07-31）

事实边界：r6 摄取到 7 份文档且业务门禁仍为 0 时，Qdrant LaunchAgent runs 从基线 1 增至 3，违反执行前约定的外部门禁。runner 已终止；r6 属于基础设施不稳定下的不完整 run，禁止落库或续跑。

1. 保存 r6 当前 manifest/document-results 只作诊断，读取 Qdrant tunnel 当前 PID、runs、last-exit、stderr mtime 和重启窗口；检查 RAG 服务器 sshd、Qdrant 容器 restart/health/OOM 与同时间日志，区分 SSH 主连接掉线和 Qdrant 服务故障。
2. 验证收紧到 5 秒的 SSH keepalive 是否因移动网络短暂抖动导致过度重启；MySQL 与 Qdrant 分别评估，不因一个通道的问题盲目同时修改。所有改动先保留业务级健康证明。
3. 对 Qdrant tunnel 做一次可控故障注入，验证 LaunchAgent 重启、应用 Qdrant HTTP 调用和现有 Hikari 链路都能恢复；随后执行不少于 10 分钟的连续 Qdrant/MySQL/API 探测，要求 0 失败且非注入期间 runs 不再增长。
4. 基础设施稳定门禁通过后，追加全新 r7 精确 runId，从第 1 份 PDF 开始，不使用 r6 的 7 条成功记录；策略、模型、JAR、数据集和 config hash 均不得变化。

Qdrant 隧道诊断事实与联合隧道验证计划：

- r6 冻结时为 7/200 文档、18 chunks、业务失败 0；manifest 因 runner 被外部门禁终止仍为 running，仅作不完整诊断产物。
- Qdrant tunnel stderr 在 00:45–00:46 更新，包含 `Timeout, server 103.205.240.84 not responding`；LaunchAgent runs 由 1 连续增长到 4、last exit=255。同期 MySQL tunnel 保持 PID 4904/runs=2，没有自然重启。
- 远端 `rag-qdrant` 持续 healthy、restart=0、OOM=false；故障窗口前后的 PUT/count/scroll 均有 HTTP 200 记录。故障属于独立 SSH 会话掉线，不归因于 Qdrant 容器或向量写入逻辑。
- 当前本地需同时维持 MySQL、Qdrant 两条到同一 RAG 服务器的 SSH 长连接；移动网络对两条并行会话表现不同。为减少连接数并复用已证明稳定的 MySQL 通道，下一步由一个 LaunchAgent/SSH 进程同时承载 `127.0.0.1:13306→3306` 与 `127.0.0.1:16333→6333`，停用独立 Qdrant tunnel；不改变服务器或项目部署。

联合隧道执行与验收：

1. 在 `rag-mysql-tunnel.plist` 的同一 ssh 命令增加第二个 `-L 127.0.0.1:16333:127.0.0.1:6333`，卸载独立 Qdrant LaunchAgent，只启动联合隧道。
2. 确认单一 PID 同时监听 13306、16333；MySQL 查询、Qdrant `/collections` 和应用知识库 API 各做独立成功验证。
3. 主动终止联合 tunnel 一次，要求 LaunchAgent runs 仅增加 1、两个端口都恢复，故障窗口 API 请求仍成功；随后连续 10 分钟并行探测 MySQL、Qdrant 与应用 API，0 失败且 runs 不再增加。
4. 验收后重启应用一次清空旧 TCP 连接，复测原失败 PDF；再追加 r7 的精确执行身份并从 0 正式运行。

### 2026-07-31：后续正式评测缩减为 50 文档的口径变更

用户要求后续每个格式直接改为 50 文档，避免 200 文档批次耗时过长。此前已经完成并落库的 6 个 200 文档 run 保持不变；r1-r6 等失败或不完整 run 仍不落库。

为避免候选语料规模不同导致伪因果结论，后续采用独立的 50 文档配对队列：

1. 从冻结的 `pdf-docx-200` 中按固定种子 `20260731` 和 complexity 分层选择 50 个唯一 sourceDocumentId；按原 80/70/50 分布取 SIMPLE/MEDIUM/COMPLEX=20/17/13，避免只保留两端而漏掉中等复杂度。组内按 `SHA-256("20260731:"+sourceDocumentId)` 升序固定抽样；同一 source 集同时生成 PDF、DOCX，保持 query、qrels、gold、source SHA 和证据标记一一对应。
2. 新数据集单独落在 `docs/rag/evaluation-data/pdf-docx-50/`，生成自己的 dataset manifest/hash/资源清单；原始 200 数据集不修改。执行前必须验证 50 个唯一 source、50 PDF、50 DOCX、50 query、50 qrel，格式文件 SHA 与派生 manifest 100% 一致。
3. 按用户最新指示，不重跑前面已完成的 `IR_FULL`、旧压平和纯文本批次；后续只跑 `IR_NO_CLEANER` 与 `IR_NO_STRUCTURED_CHUNKING`，每个策略按 PDF→DOCX 串行。因此后续共 2×2=4 个正式 run，每 run 为 50 文档、5×4=20 warmup、50×4=200 条正式查询。
4. 每个 50 文档 run 的门禁改为：document-results=50、唯一 source/internalDocumentId/taskId=50、分块完整且 source SHA 50/50；run.jsonl=200、`queryId|variant` 唯一组合=200、每变体=50、0 错误、0 降级、0 空排名；manifest、prepared、targets 和配置 hash 全部一致。
5. 50 文档 run 使用新 dataset identity 落库，每次独立回读 1/50/200/4/失败数；不得写入原 200 文档 dataset 身份。最终报告分列“200 文档历史队列”和“50 文档后续队列”；前 6 组不重跑，因此跨 200/50 队列只作方向性观察，不声称严格同集因果对照。两个剩余策略在同一 50 集上的差异可直接比较。
6. 先完成当前联合 SSH 隧道的故障注入及 10 分钟稳定性门禁，再生成 50 数据集和启动正式批次；基础设施失败不得被缩小数据量掩盖。

50 文档子集工具实现计划：

1. 在 benchmark Java CLI 增加 `subset-formats` 命令；输入冻结的格式数据集、输出空目录、seed 和 SIMPLE/MEDIUM/COMPLEX 配额，拒绝重复 source、缺失配对、配额不足和非空输出。
2. 工具按计划中的稳定哈希抽样，复制对应 PDF/DOCX 与 license，过滤 queries/qrels/gold/documents manifest，重新生成 dataset name、数量、源文件哈希和 tree SHA；不重写二进制文档。
3. 增加单元测试覆盖确定性、三档配额、双格式配对、查询/qrels/gold 闭包、tree hash 可被现有 `validate-formats` 通过，以及相同输出目录拒绝覆盖。
4. 先执行 benchmark 定向测试与打包，再用正式 200 集生成 50 集；记录命令、seed、配额、所有哈希和验证输出。遵循 benchmark 技能的 before/after 口径，但因用户明确要求不重跑既有基线，报告必须显式披露跨队列限制，不构造不存在的 50 文档 `IR_FULL` 数据。

10 分钟瞬态重试实现计划：

1. `run-format` 增加可配置的 `--transient-retry-seconds`，后续固定为 600 秒；重试预算覆盖网络异常、HTTP 429/5xx、MySQL/Qdrant 短暂不可用和可重试的 ingest lease/embedding/indexing 错误。
2. GET 轮询、文档读取和检索请求可在预算内使用有上限的退避重试；上传请求只有在后端幂等/去重语义能确认时才重发。任务进入明确瞬态失败终态时，调用现有 retry API，并继续跟踪同一文档。
3. 永久解析/格式/数据错误、哈希不一致、未激活文档、查询降级、空排名、未知文档和无效 Rerank 不重试，仍立即触发门禁。
4. 每次重试记录 operation、attempt、errorCode、elapsedMs 和最终 outcome；manifest 汇总瞬态重试次数与耗时，使最终性能报告能区分正常路径和基础设施恢复成本。
5. 增加 HTTP 5xx 后恢复、任务 lease 丢失后 retry 成功、超过 600 秒失败、永久错误不重试的单元测试；通过后重新打包 benchmark JAR，新的 JAR SHA 写入后续 run 身份。

#### 联合 SSH 隧道业务健康门禁失败与自愈计划（2026-07-31）

事实边界：联合 tunnel 故障注入后，PID 11110→11505、runs 1→2；故障窗口知识库 API 2,671 ms 内成功，Qdrant 1,172 ms 内恢复，两个端口由同一 PID 监听。但随后的 10 分钟门禁在第 9/60 次、累计 106 秒时失败：MySQL 新建连接失败，Qdrant 和知识库 API 成功，PID/runs 均未变化。

1. 读取本次 MySQL 客户端错误、同秒 SSH verbose/服务端 MySQL状态，确认是单 channel 握手丢包、MySQL拒绝还是认证问题；不因 API 使用旧池连接成功而判定新连接健康。
2. 联合 SSH 主进程的 keepalive 只能检测会话，不能检测每个转发目标；增加本地业务健康守护：固定周期分别执行真实 MySQL `SELECT 1` 和 Qdrant `/collections`，连续失败达到阈值时重启整个联合 tunnel，并记录时间、目标、连续失败数和重启计数。
3. 守护不得把凭据写进 plist 或脚本；运行时只从 `codex.md` 读取，日志不得输出密码。重启必须带互斥与冷却，避免网络故障时形成 restart storm。
4. 先用故障注入证明守护能重启并恢复两个端口，再重新执行完整 10 分钟稳定性门禁；门禁以业务探测 0 失败为目标，若发生自愈则延长观察并重新计时，直到连续 10 分钟无失败/无重启。
5. 只有该门禁通过，才运行新的 50 文档正式评测；当前联合 tunnel 结果和 r6 均不作为正式数据。

SSH 传输端口 A/B 验证计划：

- 当前 22 端口独立 SSH 命令 8 次均最终成功，但延迟为 0.888–24.203 秒，出现多次 8–24 秒停顿；这与 tunnel channel 丢包、MySQL查询长尾一致。服务器 443 当前无监听，sshd 只监听 22。

1. 在 RAG 服务器新增独立 sshd drop-in，让 SSH 同时监听 22 和 443；执行 `sshd -t` 后仅 reload，不关闭 22，避免现有入口中断。UFW 只新增 443/tcp。
2. 本地使用同一密钥、相同 `IPQoS=none` 对 22/443 各做不少于 20 次串行连接，统计成功率、p50/p95/max；443 未达到 0 失败或长尾没有实质改善则不切换。
3. 若 443 明显更稳定，更新本地 `RAG-Server` SSH config 的 Port 和联合 tunnel，重新加载并执行故障注入及连续 10 分钟 MySQL/Qdrant/API 业务门禁；22 继续保留为人工回退入口。
4. 若云防火墙阻断 443 或效果无改善，撤回 443 监听/UFW规则，回到业务健康守护方案；不得为赶进度降低正式评测门禁。

端口 A/B 与最新重试口径实际结论：

- 22/443 各 20 次交错 SSH 建连：22 为 19/20 成功，avg/p50/p95/max=7,715.9/858/17,654/120,362 ms；443 为 20/20 成功，avg/p50/p95/max=1,068.0/821/1,688/3,799 ms。服务器保留 22，并新增 443；本地 `RAG-Server` 和联合 tunnel 已切到 443。
- 443 联合 tunnel 能由同一 PID 同时提供 13306/16333，MySQL、Qdrant 首次功能探测成功；但严格 10 分钟零失败门禁在第 4 次、100 秒处仍出现一次 MySQL initial communication packet 丢失，Qdrant和应用 API 同时成功。
- 随后 10 次 MySQL 独立新连接仅 5 次成功，成功延迟 3,125–11,852 ms；失败来自当前移动网络到服务器的 TCP/SSH channel 抖动，而非容器、认证或数据集。端口 443 显著改善但不能保证底层每次握手 0 丢包。
- 用户随后明确允许瞬态故障重试窗口为 10 分钟。因此正式门禁调整为“单次底层失败允许在 600 秒预算内恢复”，不再要求原始网络探测 0 失败；最终仍必须 50/50 文档、200/200 查询、0 最终错误、0 降级、0 空结果。manifest 的重试次数/等待耗时必须单独披露，超过 600 秒仍失败。

50 文档工具与重试闭环实际结果：

- 新增 benchmark Java CLI `subset-formats`，按 `SHA-256(seed+":"+sourceDocumentId)` 分层抽样，拒绝覆盖、配对不闭包、源 tree hash 不一致和配额不足；复制二进制原件并重新生成 queries/qrels/gold/documents manifest/dataset manifest/tree hash。
- `run-format` 已去除 200 文档硬编码，按 dataset manifest 的 pairedDocumentCount/queryCount 动态校验；新增 `--transient-retry-seconds`。上传、任务轮询、文档读取、检索 debug 对网络异常、429/5xx 和明确瞬态业务码做有上限退避；lease 丢失等瞬态终态调用现有 retry API；永久错误、降级和空结果不重试。
- run manifest 新增 `transientRetrySeconds`、`transientRetryCount`、`transientRetryDelayMs`，日志逐次记录 operation/attempt/errorCode/delayMs。单测覆盖 503 两次后恢复、20 ms 预算耗尽、永久 Qdrant schema 错误不重试、ingest lease lost 后 retry 成功。
- `mvn -pl ai-agent-scaffold-benchmark clean package` 完成：62 tests、0 failures、0 errors、0 skipped。新 benchmark fat JAR SHA-256 为 `5ab05b5d357823374f925bd34b108518d4bef95ab6c9f7d8d9149a6b7bda3251`。
- 正式派生数据集为 `docs/rag/evaluation-data/pdf-docx-50/`：PDF=50、DOCX=50、唯一 source=50、queries/qrels/gold=50/50/50，复杂度 SIMPLE/MEDIUM/COMPLEX=20/17/13。`validate-formats` 为 valid=true、failures=0。
- 50 数据集 manifest SHA-256 `fb7ac5f910990a52b0634ebbadea0d75854d830cd2b754ffae4a3bf4ea6cb37d`，tree SHA-256 `6059f64d4c7f09237255b747407cd554607624127a99d83d606f69e17067a060`；原 200 数据集未修改。

最终收口校验（以本段为准）：

- 瞬态错误识别已从宽泛后缀判断收紧为明确基础设施错误码白名单，并保留包含 `TRANSIENT` 的后端显式可重试码；未知永久错误不会因名称以 `UNAVAILABLE/TIMEOUT` 结尾而被误重试。
- 收紧后重新执行 `mvn -pl ai-agent-scaffold-benchmark clean package`：62 tests、0 failures、0 errors、0 skipped，BUILD SUCCESS。移除一个无效 import 后又从最终源码执行同一完整构建并再次通过；正式 run 使用的最终 benchmark fat JAR SHA-256 为 `5784af267df845cf24cb1c9322f374a200940e428f8649a0e83332aaa2f9812d`。该值取代上文修改过程中的临时 JAR hash。
- 使用最终 JAR 再次执行 `validate-formats --prepared docs/rag/evaluation-data/pdf-docx-50`：valid=true、formatDocuments=100、pairedDocuments=50、queries=50、qrels=50、failures=0，实际 tree SHA-256 仍为 `6059f64d4c7f09237255b747407cd554607624127a99d83d606f69e17067a060`。
- 后续只执行四个尚未完成的 50 文档 run，均显式传入 `--transient-retry-seconds 600`；已完成的六个 200 文档 run 不重跑。

#### 第一个 50 文档正式 run：PDF / IR_NO_CLEANER 执行计划（2026-07-31）

执行身份冻结：

- runId：`pdf-ir-no-cleaner-50-20260731-011534`
- 输出目录：`docs/rag/evaluation-results/pdf-ir-no-cleaner-50-20260731-011534`
- Git revision：`907bb67622282cac05cad7afb7cc90bf6be09c30`
- application JAR SHA-256：`16123df8ce18d0ae4af84ff47175fc5172eb2fe3dbb3b0c1c28b44279aaff2c9`
- benchmark fat JAR SHA-256：`5784af267df845cf24cb1c9322f374a200940e428f8649a0e83332aaa2f9812d`
- dataset manifest SHA-256：`fb7ac5f910990a52b0634ebbadea0d75854d830cd2b754ffae4a3bf4ea6cb37d`
- dataset tree SHA-256：`6059f64d4c7f09237255b747407cd554607624127a99d83d606f69e17067a060`
- preprocessing strategy/revision：`IR_NO_CLEANER` / `document-ir-no-cleaner-v1`
- config SHA-256：`082c20776696745bd04d00a843f1afad0958dc8643dd18ecb19d420b26d1b0ab`

执行步骤与门禁：

1. 核验 8091 JVM 的实际环境为 benchmark preprocessing=true、strategy=`IR_NO_CLEANER`、Qdrant=`127.0.0.1:16333`；核验同一 SSH PID 同时监听 13306/16333，并以真实 MySQL、Qdrant、知识库 API 请求完成启动前健康检查。
2. 从空输出目录运行 50 份 PDF；参数固定为 warmup=5、poll=1 秒、ingest timeout=1800 秒、HTTP request timeout=120 秒、transient retry=600 秒。不得读取 r1-r6 或任一 200 文档 run 的中间结果。
3. 摄取阶段要求 50 条文档结果、50 个唯一 source/document/task、50/50 source SHA 一致、全部 active 且 processedChunks=totalChunks>0。瞬态失败可在 600 秒总预算内重试，所有尝试和等待时间必须写入 manifest；预算耗尽或永久错误立即停止。
4. 查询阶段要求 20 条 warmup 通过，再产生 200 个 `queryId|variant` 唯一组合；最终 0 errorCode、0 degraded、0 空 rankedDocumentIds，各变体恰好 50 条。
5. 全部门禁通过后才执行 JDBC 事务落库，独立回读必须为 run/document/query/aggregate/failure=`1/50/200/4/0`。执行实际结果、重试统计、质量指标和阶段延迟追加到本计划后再中文提交。

启动前认证门禁诊断计划：

- MySQL `SELECT 1` 与 Qdrant `/collections` 已成功，但使用冻结在权限 600 环境文件中的 access token 调用知识库 API 返回 HTTP 401。正式输出目录尚未创建，故本次不构成失败 run，也没有复用或消耗任何文档结果。
- 先从 `codex.md` 已有本地账户配置读取凭据，通过项目现有登录接口获取新 token；只更新 `/private/tmp/rag-format-auth-c9b1693.env`，继续保持 mode 600，命令和日志不得输出密码或 token。
- 使用新 token 再执行知识库 API 健康检查；只有 HTTP 200 且业务码 `0000`，才启动上述冻结 runId。若登录失败，则读取应用认证日志诊断，不通过关闭鉴权绕过门禁。

PDF / IR_NO_CLEANER 实际执行结果：

- 旧 token 确认过期；使用同一权限 600 环境文件中的本地测试账户动态调用现有 `/api/v1/auth/login` 成功，知识库 API 返回 HTTP 200/业务码 `0000`。正式 runner 每次启动时动态刷新 token，不把新 token 写入项目或日志。
- run 于 `2026-07-30T17:17:27.237256Z` 开始，`2026-07-30T17:57:35.402356Z` 完成，总耗时 2,408,160 ms。manifest 为 completed，transientRetryCount=0、transientRetryDelayMs=0。
- 独立文档校验：50/50 行、50 唯一 source、50 唯一 internalDocumentId、50 唯一 taskId、source SHA 与冻结 manifest 50/50 匹配、无无效状态；共生成 137 chunks，全部 `processedChunks=totalChunks>0`。摄取 elapsed mean/p50/p95/max=26,912/23,295/46,209/57,410 ms；COMPLEX/MEDIUM/SIMPLE 分别 13/17/20 份，平均摄取 36,465/21,472/25,328 ms，分块 70/42/25。
- 独立查询校验：warmup=20；正式结果 200 行、200 个唯一 `queryId|variant`，dense/sparse/hybrid_rrf/hybrid_rrf_rerank 各 50；0 errorCode、0 degraded、0 空 rankedDocumentIds。`run.jsonl` 与 `document-results.jsonl` 实际 SHA 分别等于 manifest 的 `27b74b1994930def4ef447ef82de5f80f804e375eb29ee0badb334549b8feead`、`8e7edf41f9fa4ce21202fc28aa087af5531b244f5a3ffe928452345c90bc2485`。
- 真实质量指标：
  - dense：Recall@1/5/10=`0.90/1.00/1.00`，MRR@10=`0.941667`，nDCG@10=`0.956469`，MAP@10=`0.941667`。
  - sparse：`0.78/0.86/0.88`，MRR@10=`0.814000`，nDCG@10=`0.830098`，MAP@10=`0.814000`。
  - hybrid_rrf：`0.86/0.94/0.98`，MRR@10=`0.893690`，nDCG@10=`0.914208`，MAP@10=`0.893690`。
  - hybrid_rrf_rerank：`0.82/0.96/0.98`，MRR@10=`0.890000`，nDCG@10=`0.912836`，MAP@10=`0.890000`。本数据上 rerank 没有超过 dense，且相对 hybrid RRF 降低 Recall@1 0.04；该现象保留为后续失败 case 因果分析对象。
- 检索端到端 mean/p50/p95/max：dense=`2,474/2,125/3,521/9,240 ms`，sparse=`1,917/1,801/2,902/2,997 ms`，hybrid_rrf=`2,623/2,328/3,616/12,882 ms`，hybrid_rrf_rerank=`12,043/10,339/23,609/30,075 ms`；rerank 阶段本身平均 9,444 ms，是当前最明确的检索时延瓶颈。
- JDBC 明确输出 `documents=50 queryResults=200 aggregates=4`；随后独立数据库回读 run/document/query/aggregate/failure=`1/50/200/4/0`。本 run 已完整落库。

#### 第二个 50 文档正式 run：DOCX / IR_NO_CLEANER 执行计划（2026-07-31）

1. runId 固定为 `docx-ir-no-cleaner-50-20260731-020024`，输出目录同名；使用刚完成闭环提交 `a889f9b2c7351729d1c0e79480f31a49c10f65b1`。application/benchmark JAR、50 数据集 manifest/tree、策略 revision 和 config SHA 继续分别为 `16123df...aff2c9`、`5784af...f9812d`、`fb7ac5...6cb37d`/`6059f6...7a060`、`document-ir-no-cleaner-v1`、`082c20...1b0ab`。
2. 启动前重新执行 MySQL、Qdrant、知识库 API 和实际 JVM 环境门禁；token 动态登录刷新，任何敏感值不写入产物。保持当前 JVM，不重启、不切换策略，以减少 PDF/DOCX 同策略对比中的环境变量。
3. 从空目录摄取同一 50 个 source 的 DOCX 原件；运行参数与 PDF 完全一致：5 个 warmup query、20 条 warmup、200 条正式查询、poll 1 秒、ingest timeout 1800 秒、request timeout 120 秒、transient retry 600 秒。
4. 文档、查询、hash 和质量门禁仍为 50/200/0/0/0；完成后对 PDF/DOCX 的 chunk 数、摄取延迟和四种检索指标作同集比较，但本阶段只落真实单 run，不提前生成缺失策略结论。
5. 全部门禁通过后事务落库并独立回读 `1/50/200/4/0`，将真实结果追加到计划并中文提交。

DOCX / IR_NO_CLEANER 实际执行结果：

- run 于 `2026-07-30T18:01:23Z` 左右开始，`2026-07-30T18:29:28.152168Z` 完成，总耗时 1,684,942 ms；transient retry=0 次/0 ms。
- 独立文档校验为 50/50、50 唯一 source/document/task、source SHA 50/50、无无效状态，共 167 chunks。摄取 mean/p50/p95/max=`15,928/13,854/28,205/38,728 ms`；COMPLEX/MEDIUM/SIMPLE 平均=`21,629/15,170/12,865 ms`，分块=`81/55/31`。
- 独立查询校验为 20 warmup、200 行/200 唯一组合、各变体 50，0 error/degraded/empty。run/document SHA 分别为 `571016620ba3e1d99d5d1213fafd9dd19ea2309f2e4aabaeb1aa19d6bffdba78`、`dde54e1dd06b302dd7c120443ffee81dc4ea47f49eca5374c3d26725dd87b805`，均与 manifest 一致。
- 真实质量：dense Recall@1/5/10=`0.90/1.00/1.00`、MRR/nDCG/MAP=`0.945000/0.959088/0.945000`；sparse=`0.78/0.84/0.90`、`0.813667/0.833880/0.813667`；hybrid_rrf=`0.86/0.96/0.98`、`0.899833/0.919284/0.899833`；hybrid_rrf_rerank=`0.86/0.96/0.98`、`0.909524/0.927141/0.909524`。
- 检索 mean/p50/p95/max：dense=`2,262/2,081/3,254/3,898 ms`，sparse=`1,798/1,772/2,256/2,458 ms`，hybrid_rrf=`2,355/2,246/3,116/3,825 ms`，hybrid_rrf_rerank=`9,518/8,938/13,343/18,447 ms`；rerank 阶段平均 6,993 ms，仍为主瓶颈。
- 与同策略 PDF 的同集差异：DOCX chunks 167 vs 137（+30，+21.9%），摄取平均时延 15,928 vs 26,912 ms（-40.8%）；dense Recall@10 相同为 1.00。DOCX hybrid+rerank 的 Recall@1/MRR/nDCG 比 PDF 高 `+0.04/+0.019524/+0.014305`，但仍未超过 DOCX dense；这支持“格式结构影响候选/重排排序”，不支持“更多分块必然提高 dense 召回”的结论。
- JDBC 输出 50/200/4，独立数据库回读 run/document/query/aggregate/failure=`1/50/200/4/0`；本 run 已完整落库。
