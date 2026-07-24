# RAG 文档结构化预处理闭环

## 1. 闭环结论

本轮将原先“PDF、DOCX、Markdown 先压平成 `normalized.md`，再按文本切分”的主链路改为：

```text
不可变原件
  → 格式专用解析
  → Canonical Document IR
  → 可逆 Cleaner Chain
  → 质量评估与 OCR 升级
  → 结构感知父子分块
  → Dense / Sparse 向量
  → 数据库与 Qdrant 精确一致性门禁
  → Generation 原子激活
```

`normalized.md` 仍然生成，但只作为面向人类查看和兼容旧工具的派生产物，不再作为分块、引用和索引的事实源。主事实源是带版本号的 `document-ir-v1.json`。

## 2. 不变量

- 原始文件按租户、知识库、文档、版本形成不可变对象。
- Markdown、DOCX、PDF 分别使用格式专用解析策略，不能在解析入口统一压平。
- IR 同时保留 `rawText`、`normalizedText`、来源区间、页码、边界框、阅读顺序、标题路径、表格结构、置信度、质量标记和清洗轨迹。
- 展示与引用使用 `displayText`；向量模型使用单独构造的 `embeddingText`，两者不能互相覆盖。
- 清洗只生成新 IR；重复页眉页脚和重复块使用 `suppressed` 标记，不物理删除，支持恢复原文。
- 新版本只有在数据库子块和 Qdrant 的 `point_id + chunk_id + content_hash` 集合完全一致时才能激活。
- `VERIFYING` 阶段可恢复；重试不会重新解析、重新嵌入或重复写入向量。
- 取消、终态失败和删除会清理新旧全部预处理产物、分块和当前版本向量。
- 所有对象键继续由可信租户范围生成，DOCX 外部关系只识别和告警，不访问外部目标。

## 3. 格式专用解析

### 3.1 Markdown

采用 CommonMark AST，并启用表格、任务列表和 YAML Front Matter 扩展。保留：

- H1 标题、H2-H6 层级；
- 段落、列表项、引用、代码块；
- 表格单元格和行列结构；
- YAML 元数据；
- 源字符区间。

H1 映射为 `TITLE`，其他标题映射为 `HEADING`。标题层级按真实 heading level 入栈，跳级标题不会被错误地当成父子关系。

### 3.2 DOCX

首选 Apache POI 读取 OOXML，而不是调用通用转换器。保留：

- 正文元素原始顺序；
- Heading 样式、普通段落和列表；
- 表格、`gridSpan`、纵向合并；
- 页眉、页脚和脚注；
- 图片描述、批注、修订、文本框、公式和嵌入对象的存在性告警。

DOCX 自身通常不包含可信的最终排版页码，因此在没有受控渲染器时明确输出 `DOCX_PAGE_NUMBER_UNAVAILABLE_WITHOUT_CONTROLLED_RENDERER`，页数保持 0，不伪造页码。POI 结构解析失败时才降级到 Docling。

### 3.3 PDF

PDF 使用 Docling 的布局 JSON，映射：

- 真实页码和页面尺寸；
- 文本块阅读顺序；
- bounding box；
- 标题层级；
- 表格单元格；
- OCR 标记和置信度。

默认先以 `AUTO` 解析；质量评估发现内容过少或低质量时，以 `FORCED` OCR 再解析一次，并重新评估，避免所有 PDF 都无条件 OCR。

## 4. Canonical Document IR

IR schema 版本为 `1.0`，核心结构如下：

```text
DocumentIr
├── parserName / parserRevision
├── metadata / warnings / flags
└── pages[]
    └── blocks[]
        ├── type
        ├── rawText / normalizedText
        ├── sourceSpan / boundingBox
        ├── readingOrder / regionId / columnIndex
        ├── headingPath / language / confidence
        ├── table.rows[].cells[]
        ├── suppressed / retrievable / suppressionReason
        └── cleaningChanges[]
```

每次清洗变更都记录清洗器、变更前后文本、标记和抑制状态。`restoreOriginal` 可以从已清洗 IR 恢复原文和初始可检索状态。

## 5. 五类可审计产物

每个文档版本固定生成：

| 产物 | 作用 |
| --- | --- |
| `parsed/parser-output.json` | 解析器原始结构输出或格式专用解析快照 |
| `ir/document-ir-v1.json` | 分块、引用、恢复和重建的主事实源 |
| `normalized/normalized.md` | 人类查看与旧链路兼容，不参与主分块 |
| `quality/quality-report.json` | 质量分数、处置、告警和指标 |
| `chunks/chunk-manifest.json` | 父子块、哈希、来源块和 tokenizer 版本清单 |

摄取 checkpoint 保存 IR 对象键、SHA-256 和字节数。恢复时重新读取并校验摘要，摘要不符立即失败，不使用损坏的中间产物。

## 6. 清洗、质量和分块

Cleaner Chain 当前包含：

1. 文本卫生：NFC、控制字符、零宽字符、换行和英文断词修复；
2. 重复页面装饰：跨页重复页眉页脚只标记抑制；
3. 重复块：保留第一份，后续重复块标记抑制；
4. 内容标注：代码、表格、OCR 等结构质量标记。

展示文本只做 NFC；NFKC 仅用于 `embeddingText` 的检索副本，避免引用文本中的公式、全角编号和兼容字符被改写。

质量处置分为：

- `READY`
- `READY_WITH_WARNING`
- `NEEDS_REVIEW`
- `REJECTED`
- `FAILED`

质量报告在 embedding 和 Qdrant 副作用之前持久化。`NEEDS_REVIEW` / `REJECTED` 当前以明确错误码终止版本，不会把低质量文档激活到线上索引。

结构感知分块以标题路径、页面、表格和块类型为边界，先生成 child，再生成 parent。表格以完整行优先；超限才拆分。每个 child 同时保存：

- `displayText`：引用和界面展示；
- `embeddingText`：标题路径 + 结构标签 + 正文；
- 来源 block id、source span、页码范围和质量标记；
- 稳定 chunk id 和内容 SHA-256。

## 7. 恢复、取消和原子激活

- `CHUNKING` 后重试从带摘要的 IR artifact 恢复，不重新下载和解析。
- `VERIFYING` 后进程退出，重试仅执行精确索引核验与 CAS 激活。
- Worker 每个外部副作用前都经过租约、fencing token 和取消屏障。
- 激活前同时校验：
  - Qdrant 精确 count；
  - 数据库 child count；
  - Qdrant 与数据库的 `point_id + chunk_id + content_hash` 集合完全相等。
- 任一项不一致，版本不激活，并清理未激活分块和向量。
- 激活元数据持久化 parser、IR schema、质量处置、质量分数、artifact key 和 tokenizer 版本，便于后续重建与审计。

## 8. 本轮真实验收

### 8.1 编译和针对性测试

- `mvn -q -pl ai-agent-scaffold-app -am -DskipTests compile`：通过。
- 预处理、解析协议、Cleaner、质量评估、Worker、持久化和 Qdrant 契约共 71 项：71 通过，0 失败，0 错误。
- `mvn -q -DskipTests package`：通过，生成当前代码包。

本机 Maven 实际使用 Java 25；Mockito 所依赖的 Byte Buddy 版本只正式声明支持到 Java 24，因此测试命令增加 `-Dnet.bytebuddy.experimental=true`。生产目标仍遵循项目的 Java 17 约束。

### 8.2 三格式正式 HTTP 端到端

当前代码包以独立 8092 端口运行，通过正式注册、登录、建库、上传、异步摄取、建 Workflow、绑定和 retrieval-debug API 验收。测试数据来自：

- `docs/rag/evaluation-data/format-e2e/format-fidelity.md`
- `docs/rag/evaluation-data/format-e2e/format-fidelity.docx`
- `docs/rag/evaluation-data/format-e2e/format-fidelity.pdf`
- `docs/rag/evaluation-data/format-e2e/fixture.json`

真实结果：

| 格式 | 摄取 | 尝试 | child chunks | 摄取耗时 | 有答案问题证据命中 |
| --- | --- | ---: | ---: | ---: | ---: |
| Markdown | COMPLETED | 1/3 | 5 | 9,283 ms | 5/5 |
| DOCX | COMPLETED | 1/3 | 6 | 9,878 ms | 5/5 |
| PDF | COMPLETED | 1/3 | 6 | 69,368 ms | 5/5 |
| 合计 | 3/3 成功 | - | 17 | - | 15/15 |

运行目录为 `/tmp/rag-preprocess-format-e2e-20260725`，未纳入 Git。manifest 记录了样本文件大小、SHA-256、单线程参数、代码 revision 和应用包 SHA-256。该评测的“通过”定义是检索证据包含金标术语，不是 LLM 答案裁判；不能把 15/15 解读成完整问答正确率。

PDF 任务由当前 8092 实例实际执行，Docling 单次 HTTP 解析为 59,538 ms，占 PDF 摄取 69,368 ms 的约 85.8%，是本轮实测最主要瓶颈。

### 8.3 全量回归

全量 Maven 共执行 441 项：

- 427 项通过；
- 0 项 assertion failure；
- 14 项 error。

14 项均属于仓库原有的手工示例/外部上下文测试：Agent、LLM/Tool 示例缺少 `API_KEY`，以及旧 Chat/App 测试缺少可信租户或 Spring Bean。它们不位于本轮改动文件；补充分页游标防死循环测试后，预处理相关最终 71 项全部通过。全量套件因此仍返回非零，不能宣称“全量测试全绿”。

## 9. 仍需继续优化的地方

这些是已确认的边界，不在报告中伪装为已解决：

1. **PDF 解析延迟**：Docling 是绝对瓶颈。后续应增加按内容 SHA-256 的解析缓存、短 PDF 快速路径、页级并行上限和模型预热；不能无界增加并发压垮 RAG 服务器。
2. **DOCX 页码**：POI 无法可靠重现 Word 排版页。若产品必须展示页码，需要受控 LibreOffice/Word 渲染为 PDF 后再做页级对齐，且把渲染器版本写入 provenance。
3. **复杂公式**：当前能识别公式存在，但不能保证完整恢复为语义正确的 LaTeX。需要 OMML/MathML 专用转换和公式金标集。
4. **图片语义**：当前只保留图片描述和位置告警，没有 VLM/OCR 图表理解。应按租户策略选择性启用，避免默认消耗大量算力。
5. **复杂跨页表格**：当前保留 Docling/OOXML 提供的表格结构，但跨页续表、重复表头和多级合并单元格还需要专用 stitching 规则及金标。
6. **多栏阅读顺序**：IR 已有 `columnIndex` 和 `readingOrder`，但复杂杂志式 PDF 仍依赖 Docling 输出质量，需要增加阅读顺序人工金标与 Kendall tau 指标。
7. **精确 tokenizer**：当前结构分块使用显式版本化的近似 tokenizer；在最终模型确定后，应接入模型同源 tokenizer，并用 tokenizer 版本触发可控重建。
8. **人工复核产品闭环**：后端已经产出 `NEEDS_REVIEW` 和质量报告，但尚未提供管理员逐块对照原件、修正并重新激活的 UI/API。
9. **预处理质量评测规模**：本轮三格式样本证明链路闭合，不代表对扫描件、双栏论文、财报、复杂合同和超大文档已充分覆盖。后续应新增结构金标而不只测检索术语。

## 10. 下一阶段建议门禁

- 文本保真：字符保留率、replacement character 比例、乱码率；
- 结构保真：标题层级 F1、表格 cell F1、阅读顺序 Kendall tau；
- 页级保真：页码准确率、bbox IoU；
- 分块质量：标题污染率、跨节率、表格断裂率、token 超限率；
- 检索影响：Recall@K、MRR、nDCG、证据覆盖率；
- 性能：按格式记录 parse / clean / quality / chunk / embedding / index 各阶段 P50/P95/P99；
- 稳定性：取消、租约抢占、IR 损坏、Qdrant 哈希不一致和 OCR 重试必须持续回归。
