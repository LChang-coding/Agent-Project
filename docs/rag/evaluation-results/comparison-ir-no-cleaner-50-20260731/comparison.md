# PDF/DOCX 同源RAG配对评测报告

PDF run：`pdf-ir-no-cleaner-50-20260731-011534`；DOCX run：`docx-ir-no-cleaner-50-20260731-020024`；问题数：50。

## 质量与延迟

| 格式 | 变体 | Recall@1 | Recall@5 | Recall@10 | MRR@10 | nDCG@10 | mean ms | p95 ms | p99 ms |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|
| PDF | hybrid_rrf_rerank | 0.820 | 0.960 | 0.980 | 0.890 | 0.913 | 12042.740 | 23609 | 30075 |
| PDF | hybrid_rrf | 0.860 | 0.940 | 0.980 | 0.894 | 0.914 | 2623.280 | 3616 | 12882 |
| PDF | sparse | 0.780 | 0.860 | 0.880 | 0.814 | 0.830 | 1917.380 | 2902 | 2997 |
| PDF | dense | 0.900 | 1.000 | 1.000 | 0.942 | 0.956 | 2473.900 | 3521 | 9240 |
| DOCX | hybrid_rrf_rerank | 0.860 | 0.960 | 0.980 | 0.910 | 0.927 | 9518.460 | 13343 | 18447 |
| DOCX | hybrid_rrf | 0.860 | 0.960 | 0.980 | 0.900 | 0.919 | 2355.120 | 3116 | 3825 |
| DOCX | sparse | 0.780 | 0.840 | 0.900 | 0.814 | 0.834 | 1798.060 | 2256 | 2458 |
| DOCX | dense | 0.900 | 1.000 | 1.000 | 0.945 | 0.959 | 2262.180 | 3254 | 3898 |

## 同问题配对结果

| 变体 | 双命中 | 双失败 | 仅PDF命中 | 仅DOCX命中 | PDF名次更好 | DOCX名次更好 | 同名次 |
|---|---:|---:|---:|---:|---:|---:|---:|
| hybrid_rrf_rerank | 48 | 0 | 1 | 1 | 3 | 4 | 43 |
| hybrid_rrf | 48 | 0 | 1 | 1 | 3 | 1 | 46 |
| sparse | 44 | 5 | 0 | 1 | 3 | 4 | 43 |
| dense | 50 | 0 | 0 | 0 | 1 | 2 | 47 |

## 摄取

| 格式 | chunk总数 | 摄取mean ms | p50 | p95 | max |
|---|---:|---:|---:|---:|---:|
| PDF | 137 | 26912.360 | 23295 | 46209 | 57410 |
| DOCX | 167 | 15927.540 | 13854 | 28205 | 38728 |

## 资源瓶颈

本轮未同步采集系统资源序列；不使用事后样本替代。

## 格式独占与共同失败样本

### hybrid_rrf_rerank

仅PDF命中：

- queryId=`830`，PDF rank=1，DOCX rank=Top10未命中，问题：NF2 (Merlin) causes phosphorylation and subsequent cytoplasmic sequestration of YAP in Drosophila by activating LATS1/2 kinases.；源文件 PDF=`prepared/pdf/026-scifact-1897324.pdf` / DOCX=`prepared/docx/026-scifact-1897324.docx`

仅DOCX命中：

- queryId=`914`，PDF rank=Top10未命中，DOCX rank=1，问题：PPAR-RXRs can be activated by PPAR ligands.；源文件 PDF=`prepared/pdf/067-scifact-3203590.pdf` / DOCX=`prepared/docx/067-scifact-3203590.docx`

两者均未命中：

- 无

### hybrid_rrf

仅PDF命中：

- queryId=`830`，PDF rank=8，DOCX rank=Top10未命中，问题：NF2 (Merlin) causes phosphorylation and subsequent cytoplasmic sequestration of YAP in Drosophila by activating LATS1/2 kinases.；源文件 PDF=`prepared/pdf/026-scifact-1897324.pdf` / DOCX=`prepared/docx/026-scifact-1897324.docx`

仅DOCX命中：

- queryId=`914`，PDF rank=Top10未命中，DOCX rank=2，问题：PPAR-RXRs can be activated by PPAR ligands.；源文件 PDF=`prepared/pdf/067-scifact-3203590.pdf` / DOCX=`prepared/docx/067-scifact-3203590.docx`

两者均未命中：

- 无

### sparse

仅PDF命中：

- 无

仅DOCX命中：

- queryId=`198`，PDF rank=Top10未命中，DOCX rank=10，问题：CCL19 is absent within dLNs.；源文件 PDF=`prepared/pdf/117-scifact-2177022.pdf` / DOCX=`prepared/docx/117-scifact-2177022.docx`

两者均未命中：

- queryId=`535`，PDF rank=Top10未命中，DOCX rank=Top10未命中，问题：Hypertension is frequently observed in type 1 diabetes patients.；源文件 PDF=`prepared/pdf/192-scifact-39368721.pdf` / DOCX=`prepared/docx/192-scifact-39368721.docx`
- queryId=`560`，PDF rank=Top10未命中，DOCX rank=Top10未命中，问题：Immune responses result in the development of inflammatory Th17 cells and anti-inflammatory iTregs.；源文件 PDF=`prepared/pdf/011-scifact-40096222.pdf` / DOCX=`prepared/docx/011-scifact-40096222.docx`
- queryId=`914`，PDF rank=Top10未命中，DOCX rank=Top10未命中，问题：PPAR-RXRs can be activated by PPAR ligands.；源文件 PDF=`prepared/pdf/067-scifact-3203590.pdf` / DOCX=`prepared/docx/067-scifact-3203590.docx`
- queryId=`830`，PDF rank=Top10未命中，DOCX rank=Top10未命中，问题：NF2 (Merlin) causes phosphorylation and subsequent cytoplasmic sequestration of YAP in Drosophila by activating LATS1/2 kinases.；源文件 PDF=`prepared/pdf/026-scifact-1897324.pdf` / DOCX=`prepared/docx/026-scifact-1897324.docx`
- queryId=`690`，PDF rank=Top10未命中，DOCX rank=Top10未命中，问题：Less than 10% of the gabonese children with Schimmelpenning-Feuerstein-Mims syndrome (SFM) had a plasma lactate of more than 5mmol/L.；源文件 PDF=`prepared/pdf/190-scifact-18750453.pdf` / DOCX=`prepared/docx/190-scifact-18750453.docx`

### dense

仅PDF命中：

- 无

仅DOCX命中：

- 无

两者均未命中：

- 无

## 结论

- Dense Recall@10：PDF 1.000，DOCX 1.000。
- DOCX生成167个chunk，PDF生成137个，DOCX多30个。
- Rerank平均端到端耗时相对Hybrid：PDF 4.59x，DOCX 4.04x。
- Dense格式独占命中：PDF 0，DOCX 0；同为未命中 0。
- 未同步采集系统资源序列；资源瓶颈不作推断。

## 证据边界

- PDF与DOCX使用同一50问题、同一qrels、同一源正文与同一检索配置。
- 当前预处理策略为IR_NO_CLEANER。
- 该数据集是确定性派生版面压力集，不等价于真实世界原生Office/PDF分布。
- 本次运行未同步采集操作系统/容器资源序列；瓶颈判断仅使用请求阶段计时，不能补写事后资源数据。

## 输入SHA-256

- pdfDocuments: `8e7edf41f9fa4ce21202fc28aa087af5531b244f5a3ffe928452345c90bc2485`
- pdfRun: `27b74b1994930def4ef447ef82de5f80f804e375eb29ee0badb334549b8feead`
- queries: `91eb0724efa964c6d95c0da25edb0f74a0ebefdbdca0ec982ca9ef27ac12ae31`
- docxDocuments: `dde54e1dd06b302dd7c120443ffee81dc4ea47f49eca5374c3d26725dd87b805`
- qrels: `c3a1cd04e97834a8047a0fecfb95c4dc699fea67346c986aea5f6c04ce8d894a`
- documentManifest: `e83cce34a3eaf60f6c3411166530e2b3431edbe68534baebf719bb1249d6ebbe`
- docxRun: `571016620ba3e1d99d5d1213fafd9dd19ea2309f2e4aabaeb1aa19d6bffdba78`
