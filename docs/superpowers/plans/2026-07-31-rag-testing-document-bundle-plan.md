# RAG 测试原始文档包补全计划

## 目标

在 `/Users/codeliu/项目根据地/rag测试` 根目录下补充清晰可见的“测试文档”专区，确保用于 200 份同源格式评测的每一份 Markdown、PDF 和 DOCX 都有实体文件，并提供逐文件可点击索引。

## 执行计划

1. 核验证据包内 PDF/DOCX 200 份数据的实际文件数、文档映射和 hash。
2. 在证据包根目录建立 `测试文档/200份同源格式测试/Markdown`、`PDF` 和 `DOCX`，收纳全部 200 + 200 + 200 份文档。
3. 收纳 Markdown 原始/规范化语料与三格式 fixture，避免格式测试只看到 PDF/DOCX。
4. 生成根目录 `测试文档索引.md`，列出 200 个配对编号、SciFact 文档 ID、Markdown/PDF/DOCX 的相对可点击路径，并标明问题/qrels/evidence gold 路径。
5. 更新证据包 README 和 SHA256SUMS，校验 200 Markdown、200 PDF、200 DOCX、200 唯一配对、索引链接和源/副本 hash 一致。
6. 追加本计划实际操作记录并作中文本地提交。

## 门禁

- 只复制和新建，不删除、移动或改写原始测试文档。
- Markdown、PDF 和 DOCX 必须各 200 份，且配对编号完整为 001–200。
- 所有索引路径必须是证据包内的相对路径。
- 不复制 `codex.md` 或任何敏感配置。

## 实际操作记录

### 2026-07-31 文档实体补全

1. 已验证原证据包的 `evaluation-data/pdf-docx-200/prepared` 实际包含 200 PDF 和 200 DOCX，编号 001–200 无缺口。
2. 已在证据包根目录新建 `测试文档/200份同源格式测试/Markdown`、`PDF` 和 `DOCX`。PDF/DOCX 分别复制 200 份实体文档；Markdown 从本地 `gold.jsonl` 的 title/evidenceText 按同一文档 ID 确定性导出 200 份，每份明确标注不是模型生成答案。
3. 已新建 `测试文档/Markdown/SciFact全量语料`，收纳 5,183 篇规范化 Markdown 合并文件、document-map、queries 和 qrels；已新建 `测试文档/三格式功能样本`，收纳同源 Markdown/PDF/DOCX 及 fixture/page-gold。
4. 已生成根目录 `测试文档索引.md`，含 200 行配对记录，每行显示编号、SciFact 文档 ID、Query ID、复杂度和可点击 Markdown/PDF/DOCX 链接；索引共 614 个可点击链接，缺失数为 0。
5. 已在证据包 README 和总账增加“测试文档索引”入口，仅修改证据包内副本，未改动项目原报告。
6. 验收结果：Markdown=200、PDF=200、DOCX=200、索引配对=200，三格式编号缺口=0，Markdown 文档 ID/证据标记各 200 个唯一值，索引链接缺失=0，总账链接缺失=0；PDF/DOCX 源/副本逐文件 SHA-256 集合完全一致。
7. 已重建证据包 `SHA256SUMS`，包含 1,827 个文件且全部回校 OK。最终证据包约 107 MB，共 1,828 个文件、131 个目录。
8. 全过程只执行新建和复制，没有删除、移动或覆盖任何原始测试文档。
