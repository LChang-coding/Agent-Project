# SciFact RAG 评测文档副本

本目录固化当前项目 RAG 质量评测使用的公开 SciFact 数据，避免原始数据与实际摄取文档只存在于本机 `/tmp`。这里不包含任何租户私有文档、凭据或服务日志。

## 内容

- `source/scifact.zip`：从 `manifest.json` 的 `sourceUrl` 下载的完整原始包。
- `prepared/documents/benchmark-0001.md`：项目实际上传并切块的单一 Markdown 分片，包含 5,183 篇语料文档。
- `prepared/document-map.jsonl`：原始文档 ID 到 Markdown 标题标记和分片的可审计映射。
- `prepared/queries.jsonl`：质量评测使用的 300 条查询。
- `prepared/qrels.tsv`：300 条查询对应的 339 条相关性标注。
- `manifest.json`：来源、许可声明、数量、字节数和 SHA-256。

## 完整性校验

在本目录执行：

```bash
shasum -a 256 source/scifact.zip \
  prepared/documents/benchmark-0001.md \
  prepared/document-map.jsonl \
  prepared/queries.jsonl \
  prepared/qrels.tsv
```

期望摘要以 `manifest.json` 为准。原始包内部文件可用 `unzip -p source/scifact.zip <archive-path>` 提取并按 `sourceFiles` 复核。

## 数据许可

准备清单记录的许可口径为 `CC-BY-4.0_annotations_ODC-By-1.0_S2ORC`。使用、再分发或发布评测结果时，应保留 SciFact、BEIR 与底层 S2ORC 数据来源的归属信息；本目录只是测试副本，不改变上游许可。
