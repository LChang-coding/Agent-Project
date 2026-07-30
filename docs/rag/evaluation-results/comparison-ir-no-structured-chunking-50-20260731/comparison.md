# PDF/DOCX 同源RAG配对评测报告

PDF run：`pdf-ir-no-structured-chunking-50-20260731-023138`；DOCX run：`docx-ir-no-structured-chunking-50-20260731-031011`；问题数：50。

## 质量与延迟

| 格式 | 变体 | Recall@1 | Recall@5 | Recall@10 | MRR@10 | nDCG@10 | mean ms | p95 ms | p99 ms |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|
| PDF | sparse | 0.800 | 0.880 | 0.880 | 0.826 | 0.839 | 1819.920 | 2290 | 2556 |
| PDF | dense | 0.960 | 1.000 | 1.000 | 0.977 | 0.983 | 2121.780 | 2688 | 3313 |
| PDF | hybrid_rrf_rerank | 0.940 | 1.000 | 1.000 | 0.961 | 0.970 | 11458.700 | 18319 | 25730 |
| PDF | hybrid_rrf | 0.860 | 0.940 | 1.000 | 0.897 | 0.921 | 2154.720 | 2873 | 3358 |
| DOCX | sparse | 0.780 | 0.880 | 0.900 | 0.816 | 0.836 | 1805.000 | 2239 | 2477 |
| DOCX | dense | 0.900 | 1.000 | 1.000 | 0.947 | 0.960 | 2102.560 | 2701 | 2964 |
| DOCX | hybrid_rrf_rerank | 0.920 | 0.960 | 0.960 | 0.937 | 0.943 | 10456.260 | 15570 | 17948 |
| DOCX | hybrid_rrf | 0.840 | 0.940 | 0.960 | 0.886 | 0.905 | 2116.080 | 2477 | 3077 |

## 同问题配对结果

| 变体 | 双命中 | 双失败 | 仅PDF命中 | 仅DOCX命中 | PDF名次更好 | DOCX名次更好 | 同名次 |
|---|---:|---:|---:|---:|---:|---:|---:|
| sparse | 44 | 5 | 0 | 1 | 1 | 2 | 47 |
| dense | 50 | 0 | 0 | 0 | 3 | 0 | 47 |
| hybrid_rrf_rerank | 48 | 0 | 2 | 0 | 4 | 1 | 45 |
| hybrid_rrf | 48 | 0 | 2 | 0 | 3 | 2 | 45 |

## 摄取

| 格式 | chunk总数 | 摄取mean ms | p50 | p95 | max |
|---|---:|---:|---:|---:|---:|
| PDF | 81 | 21229.260 | 19653 | 33221 | 43707 |
| DOCX | 98 | 13493.260 | 12466 | 22394 | 23670 |

## 资源瓶颈

本轮未同步采集系统资源序列；不使用事后样本替代。

## 格式独占与共同失败样本

### sparse

仅PDF命中：

- 无

仅DOCX命中：

- queryId=`690`，PDF rank=Top10未命中，DOCX rank=8，问题：Less than 10% of the gabonese children with Schimmelpenning-Feuerstein-Mims syndrome (SFM) had a plasma lactate of more than 5mmol/L.；源文件 DOCX=`prepared/docx/190-scifact-18750453.docx` / PDF=`prepared/pdf/190-scifact-18750453.pdf`

两者均未命中：

- queryId=`198`，PDF rank=Top10未命中，DOCX rank=Top10未命中，问题：CCL19 is absent within dLNs.；源文件 DOCX=`prepared/docx/117-scifact-2177022.docx` / PDF=`prepared/pdf/117-scifact-2177022.pdf`
- queryId=`535`，PDF rank=Top10未命中，DOCX rank=Top10未命中，问题：Hypertension is frequently observed in type 1 diabetes patients.；源文件 DOCX=`prepared/docx/192-scifact-39368721.docx` / PDF=`prepared/pdf/192-scifact-39368721.pdf`
- queryId=`560`，PDF rank=Top10未命中，DOCX rank=Top10未命中，问题：Immune responses result in the development of inflammatory Th17 cells and anti-inflammatory iTregs.；源文件 DOCX=`prepared/docx/011-scifact-40096222.docx` / PDF=`prepared/pdf/011-scifact-40096222.pdf`
- queryId=`914`，PDF rank=Top10未命中，DOCX rank=Top10未命中，问题：PPAR-RXRs can be activated by PPAR ligands.；源文件 DOCX=`prepared/docx/067-scifact-3203590.docx` / PDF=`prepared/pdf/067-scifact-3203590.pdf`
- queryId=`830`，PDF rank=Top10未命中，DOCX rank=Top10未命中，问题：NF2 (Merlin) causes phosphorylation and subsequent cytoplasmic sequestration of YAP in Drosophila by activating LATS1/2 kinases.；源文件 DOCX=`prepared/docx/026-scifact-1897324.docx` / PDF=`prepared/pdf/026-scifact-1897324.pdf`

### dense

仅PDF命中：

- 无

仅DOCX命中：

- 无

两者均未命中：

- 无

### hybrid_rrf_rerank

仅PDF命中：

- queryId=`560`，PDF rank=5，DOCX rank=Top10未命中，问题：Immune responses result in the development of inflammatory Th17 cells and anti-inflammatory iTregs.；源文件 DOCX=`prepared/docx/011-scifact-40096222.docx` / PDF=`prepared/pdf/011-scifact-40096222.pdf`
- queryId=`830`，PDF rank=1，DOCX rank=Top10未命中，问题：NF2 (Merlin) causes phosphorylation and subsequent cytoplasmic sequestration of YAP in Drosophila by activating LATS1/2 kinases.；源文件 DOCX=`prepared/docx/026-scifact-1897324.docx` / PDF=`prepared/pdf/026-scifact-1897324.pdf`

仅DOCX命中：

- 无

两者均未命中：

- 无

### hybrid_rrf

仅PDF命中：

- queryId=`560`，PDF rank=7，DOCX rank=Top10未命中，问题：Immune responses result in the development of inflammatory Th17 cells and anti-inflammatory iTregs.；源文件 DOCX=`prepared/docx/011-scifact-40096222.docx` / PDF=`prepared/pdf/011-scifact-40096222.pdf`
- queryId=`830`，PDF rank=10，DOCX rank=Top10未命中，问题：NF2 (Merlin) causes phosphorylation and subsequent cytoplasmic sequestration of YAP in Drosophila by activating LATS1/2 kinases.；源文件 DOCX=`prepared/docx/026-scifact-1897324.docx` / PDF=`prepared/pdf/026-scifact-1897324.pdf`

仅DOCX命中：

- 无

两者均未命中：

- 无

## 结论

- Dense Recall@10：PDF 1.000，DOCX 1.000。
- DOCX生成98个chunk，PDF生成81个，DOCX多17个。
- Rerank平均端到端耗时相对Hybrid：PDF 5.32x，DOCX 4.94x。
- Dense格式独占命中：PDF 0，DOCX 0；同为未命中 0。
- 未同步采集系统资源序列；资源瓶颈不作推断。

## 证据边界

- PDF与DOCX使用同一50问题、同一qrels、同一源正文与同一检索配置。
- 当前预处理策略为IR_NO_STRUCTURED_CHUNKING。
- 该数据集是确定性派生版面压力集，不等价于真实世界原生Office/PDF分布。
- 本次运行未同步采集操作系统/容器资源序列；瓶颈判断仅使用请求阶段计时，不能补写事后资源数据。

## 输入SHA-256

- qrels: `c3a1cd04e97834a8047a0fecfb95c4dc699fea67346c986aea5f6c04ce8d894a`
- documentManifest: `e83cce34a3eaf60f6c3411166530e2b3431edbe68534baebf719bb1249d6ebbe`
- docxRun: `c384989fa06c13a964de8c35b6711b5e53d4f24d4e193b79f7f7181f2c750dab`
- pdfDocuments: `97a9d2426386abd8178c8958f0f3856f0ff69387dbccf3a0e97a8483e8e0348b`
- pdfRun: `1531cef1cd2b7810c0bd3c64683307a5a4558591c1bf3877b9e155bc58ffbde7`
- queries: `91eb0724efa964c6d95c0da25edb0f74a0ebefdbdca0ec982ca9ef27ac12ae31`
- docxDocuments: `4f5b8198fa605336d3d8298a6728482f23eba8acef3a766ee3ea2484b718a196`
