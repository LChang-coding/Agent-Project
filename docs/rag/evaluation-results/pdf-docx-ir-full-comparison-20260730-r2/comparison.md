# PDF/DOCX 同源RAG配对评测报告

PDF run：`pdf-ir-full-20260729-224110`；DOCX run：`docx-ir-full-20260729-005614`；问题数：200。

## 质量与延迟

| 格式 | 变体 | Recall@1 | Recall@5 | Recall@10 | MRR@10 | nDCG@10 | mean ms | p95 ms | p99 ms |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|
| PDF | hybrid_rrf_rerank | 0.825 | 0.920 | 0.925 | 0.862 | 0.878 | 11074.600 | 19442 | 31202 |
| PDF | dense | 0.825 | 0.935 | 0.960 | 0.877 | 0.898 | 2462.495 | 3920 | 4267 |
| PDF | sparse | 0.610 | 0.785 | 0.820 | 0.687 | 0.719 | 2162.820 | 3577 | 4751 |
| PDF | hybrid_rrf | 0.775 | 0.885 | 0.925 | 0.827 | 0.851 | 2667.670 | 4361 | 4968 |
| DOCX | hybrid_rrf_rerank | 0.840 | 0.915 | 0.920 | 0.869 | 0.881 | 6956.290 | 9796 | 11266 |
| DOCX | dense | 0.835 | 0.950 | 0.960 | 0.885 | 0.904 | 1764.475 | 2175 | 2535 |
| DOCX | sparse | 0.630 | 0.805 | 0.830 | 0.701 | 0.733 | 1508.300 | 1739 | 2407 |
| DOCX | hybrid_rrf | 0.800 | 0.900 | 0.920 | 0.844 | 0.863 | 1845.890 | 2146 | 2462 |

## 同问题配对结果

| 变体 | 双命中 | 双失败 | 仅PDF命中 | 仅DOCX命中 | PDF名次更好 | DOCX名次更好 | 同名次 |
|---|---:|---:|---:|---:|---:|---:|---:|
| hybrid_rrf_rerank | 180 | 11 | 5 | 4 | 7 | 15 | 178 |
| dense | 189 | 5 | 3 | 3 | 9 | 14 | 177 |
| sparse | 159 | 29 | 5 | 7 | 22 | 26 | 152 |
| hybrid_rrf | 180 | 11 | 5 | 4 | 12 | 22 | 166 |

## 摄取

| 格式 | chunk总数 | 摄取mean ms | p50 | p95 | max |
|---|---:|---:|---:|---:|---:|
| PDF | 530 | 20608.660 | 19283 | 31332 | 41552 |
| DOCX | 706 | 11626.580 | 10747 | 16533 | 26640 |

## 资源瓶颈

| 容器 | CPU mean% | CPU max% | 内存mean% | 内存max% | PIDs max |
|---|---:|---:|---:|---:|---:|
| rag-node-exporter | 0.326 | 20.250 | 8.512 | 9.150 | 10 |
| rag-docling | 25.254 | 375.200 | 39.937 | 53.300 | 48 |
| rag-model-gateway | 0.496 | 40.030 | 11.847 | 14.090 | 23 |
| rag-mysql | 5.269 | 52.420 | 62.330 | 65.730 | 51 |
| rag-qdrant | 1.275 | 21.100 | 3.671 | 3.760 | 49 |
| rag-prometheus | 1.754 | 78.610 | 16.981 | 21.190 | 14 |
| rag-reranker | 84.013 | 455.790 | 67.828 | 67.920 | 19 |
| rag-embedding | 31.469 | 455.660 | 62.062 | 62.340 | 19 |

## 格式独占与共同失败样本

### hybrid_rrf_rerank

仅PDF命中：

- queryId=`94`，PDF rank=1，DOCX rank=Top10未命中，问题：Albendazole is used to treat lymphatic filariasis.；源文件 [PDF](../../evaluation-data/pdf-docx-200/prepared/pdf/038-scifact-1215116.pdf) / [DOCX](../../evaluation-data/pdf-docx-200/prepared/docx/038-scifact-1215116.docx)
- queryId=`1`，PDF rank=9，DOCX rank=Top10未命中，问题：0-dimensional biomaterials show inductive properties.；源文件 [PDF](../../evaluation-data/pdf-docx-200/prepared/pdf/168-scifact-31715818.pdf) / [DOCX](../../evaluation-data/pdf-docx-200/prepared/docx/168-scifact-31715818.docx)
- queryId=`502`，PDF rank=1，DOCX rank=Top10未命中，问题：Healthcare delivery efficiency in crowded delivery centers is impaired by improving structural, logistical, and interpersonal elements.；源文件 [PDF](../../evaluation-data/pdf-docx-200/prepared/pdf/162-scifact-13071728.pdf) / [DOCX](../../evaluation-data/pdf-docx-200/prepared/docx/162-scifact-13071728.docx)
- queryId=`1363`，PDF rank=1，DOCX rank=Top10未命中，问题：Venules have a thinner or absent smooth layer compared to arterioles.；源文件 [PDF](../../evaluation-data/pdf-docx-200/prepared/pdf/030-scifact-8290953.pdf) / [DOCX](../../evaluation-data/pdf-docx-200/prepared/docx/030-scifact-8290953.docx)
- queryId=`517`，PDF rank=1，DOCX rank=Top10未命中，问题：High levels of copeptin decrease risk of diabetes.；源文件 [PDF](../../evaluation-data/pdf-docx-200/prepared/pdf/062-scifact-15663829.pdf) / [DOCX](../../evaluation-data/pdf-docx-200/prepared/docx/062-scifact-15663829.docx)

仅DOCX命中：

- queryId=`1175`，PDF rank=Top10未命中，DOCX rank=1，问题：The PPR MDA5 has two N-terminal CARD domains.；源文件 [PDF](../../evaluation-data/pdf-docx-200/prepared/pdf/115-scifact-31272411.pdf) / [DOCX](../../evaluation-data/pdf-docx-200/prepared/docx/115-scifact-31272411.docx)
- queryId=`775`，PDF rank=Top10未命中，DOCX rank=8，问题：Mice defective for deoxyribonucleic acid (DNA) polymerase I (polI) reveal increased sensitivity to ionizing radiation (IR).；源文件 [PDF](../../evaluation-data/pdf-docx-200/prepared/pdf/037-scifact-32275758.pdf) / [DOCX](../../evaluation-data/pdf-docx-200/prepared/docx/037-scifact-32275758.docx)
- queryId=`1196`，PDF rank=Top10未命中，DOCX rank=1，问题：The availability of safe places to study is effective at decreasing homelessness.；源文件 [PDF](../../evaluation-data/pdf-docx-200/prepared/pdf/003-scifact-25649714.pdf) / [DOCX](../../evaluation-data/pdf-docx-200/prepared/docx/003-scifact-25649714.docx)
- queryId=`132`，PDF rank=Top10未命中，DOCX rank=2，问题：Aspirin inhibits the production of PGE2.；源文件 [PDF](../../evaluation-data/pdf-docx-200/prepared/pdf/053-scifact-7975937.pdf) / [DOCX](../../evaluation-data/pdf-docx-200/prepared/docx/053-scifact-7975937.docx)

两者均未命中：

- queryId=`830`，PDF rank=Top10未命中，DOCX rank=Top10未命中，问题：NF2 (Merlin) causes phosphorylation and subsequent cytoplasmic sequestration of YAP in Drosophila by activating LATS1/2 kinases.；源文件 [PDF](../../evaluation-data/pdf-docx-200/prepared/pdf/026-scifact-1897324.pdf) / [DOCX](../../evaluation-data/pdf-docx-200/prepared/docx/026-scifact-1897324.docx)
- queryId=`560`，PDF rank=Top10未命中，DOCX rank=Top10未命中，问题：Immune responses result in the development of inflammatory Th17 cells and anti-inflammatory iTregs.；源文件 [PDF](../../evaluation-data/pdf-docx-200/prepared/pdf/011-scifact-40096222.pdf) / [DOCX](../../evaluation-data/pdf-docx-200/prepared/docx/011-scifact-40096222.docx)
- queryId=`1191`，PDF rank=Top10未命中，DOCX rank=Top10未命中，问题：The amount of publicly available DNA data doubles every 10 years.；源文件 [PDF](../../evaluation-data/pdf-docx-200/prepared/pdf/041-scifact-30655442.pdf) / [DOCX](../../evaluation-data/pdf-docx-200/prepared/docx/041-scifact-30655442.docx)
- queryId=`1199`，PDF rank=Top10未命中，DOCX rank=Top10未命中，问题：The benefits of colchicine were achieved with effective widespread use of secondary prevention strategies such as high-dose statins.；源文件 [PDF](../../evaluation-data/pdf-docx-200/prepared/pdf/179-scifact-16760369.pdf) / [DOCX](../../evaluation-data/pdf-docx-200/prepared/docx/179-scifact-16760369.docx)
- queryId=`577`，PDF rank=Top10未命中，DOCX rank=Top10未命中，问题：In mice, P. chabaudi parasites are able to proliferate faster early in infection when inoculated at lower numbers than when inoculated at high numbers.；源文件 [PDF](../../evaluation-data/pdf-docx-200/prepared/pdf/057-scifact-5289038.pdf) / [DOCX](../../evaluation-data/pdf-docx-200/prepared/docx/057-scifact-5289038.docx)
- queryId=`914`，PDF rank=Top10未命中，DOCX rank=Top10未命中，问题：PPAR-RXRs can be activated by PPAR ligands.；源文件 [PDF](../../evaluation-data/pdf-docx-200/prepared/pdf/067-scifact-3203590.pdf) / [DOCX](../../evaluation-data/pdf-docx-200/prepared/docx/067-scifact-3203590.docx)
- queryId=`431`，PDF rank=Top10未命中，DOCX rank=Top10未命中，问题：FoxO3a activation in neuronal death is mediated by reactive oxygen species (ROS).；源文件 [PDF](../../evaluation-data/pdf-docx-200/prepared/pdf/092-scifact-28937856.pdf) / [DOCX](../../evaluation-data/pdf-docx-200/prepared/docx/092-scifact-28937856.docx)
- queryId=`887`，PDF rank=Top10未命中，DOCX rank=Top10未命中，问题：Only a minority of cells survive development after differentiation into stress-resistant spores.；源文件 [PDF](../../evaluation-data/pdf-docx-200/prepared/pdf/022-scifact-18855191.pdf) / [DOCX](../../evaluation-data/pdf-docx-200/prepared/docx/022-scifact-18855191.docx)
- queryId=`437`，PDF rank=Top10未命中，DOCX rank=Top10未命中，问题：Functional consequences of genomic alterations due to Myelodysplastic syndrome (MDS) are poorly understood due to the lack of an animal model.；源文件 [PDF](../../evaluation-data/pdf-docx-200/prepared/pdf/080-scifact-18399038.pdf) / [DOCX](../../evaluation-data/pdf-docx-200/prepared/docx/080-scifact-18399038.docx)
- queryId=`690`，PDF rank=Top10未命中，DOCX rank=Top10未命中，问题：Less than 10% of the gabonese children with Schimmelpenning-Feuerstein-Mims syndrome (SFM) had a plasma lactate of more than 5mmol/L.；源文件 [PDF](../../evaluation-data/pdf-docx-200/prepared/pdf/190-scifact-18750453.pdf) / [DOCX](../../evaluation-data/pdf-docx-200/prepared/docx/190-scifact-18750453.docx)

### dense

仅PDF命中：

- queryId=`1175`，PDF rank=6，DOCX rank=Top10未命中，问题：The PPR MDA5 has two N-terminal CARD domains.；源文件 [PDF](../../evaluation-data/pdf-docx-200/prepared/pdf/115-scifact-31272411.pdf) / [DOCX](../../evaluation-data/pdf-docx-200/prepared/docx/115-scifact-31272411.docx)
- queryId=`324`，PDF rank=9，DOCX rank=Top10未命中，问题：Deleting Raptor reduces G-CSF levels.；源文件 [PDF](../../evaluation-data/pdf-docx-200/prepared/pdf/029-scifact-2014909.pdf) / [DOCX](../../evaluation-data/pdf-docx-200/prepared/docx/029-scifact-2014909.docx)
- queryId=`887`，PDF rank=7，DOCX rank=Top10未命中，问题：Only a minority of cells survive development after differentiation into stress-resistant spores.；源文件 [PDF](../../evaluation-data/pdf-docx-200/prepared/pdf/022-scifact-18855191.pdf) / [DOCX](../../evaluation-data/pdf-docx-200/prepared/docx/022-scifact-18855191.docx)

仅DOCX命中：

- queryId=`768`，PDF rank=Top10未命中，DOCX rank=2，问题：Mercaptopurine is anabolized into the inactive methylmercaptopurine by thiopurine methyltrasnferase (TPMT).；源文件 [PDF](../../evaluation-data/pdf-docx-200/prepared/pdf/051-scifact-6421792.pdf) / [DOCX](../../evaluation-data/pdf-docx-200/prepared/docx/051-scifact-6421792.docx)
- queryId=`1`，PDF rank=Top10未命中，DOCX rank=7，问题：0-dimensional biomaterials show inductive properties.；源文件 [PDF](../../evaluation-data/pdf-docx-200/prepared/pdf/168-scifact-31715818.pdf) / [DOCX](../../evaluation-data/pdf-docx-200/prepared/docx/168-scifact-31715818.docx)
- queryId=`517`，PDF rank=Top10未命中，DOCX rank=5，问题：High levels of copeptin decrease risk of diabetes.；源文件 [PDF](../../evaluation-data/pdf-docx-200/prepared/pdf/062-scifact-15663829.pdf) / [DOCX](../../evaluation-data/pdf-docx-200/prepared/docx/062-scifact-15663829.docx)

两者均未命中：

- queryId=`1191`，PDF rank=Top10未命中，DOCX rank=Top10未命中，问题：The amount of publicly available DNA data doubles every 10 years.；源文件 [PDF](../../evaluation-data/pdf-docx-200/prepared/pdf/041-scifact-30655442.pdf) / [DOCX](../../evaluation-data/pdf-docx-200/prepared/docx/041-scifact-30655442.docx)
- queryId=`1199`，PDF rank=Top10未命中，DOCX rank=Top10未命中，问题：The benefits of colchicine were achieved with effective widespread use of secondary prevention strategies such as high-dose statins.；源文件 [PDF](../../evaluation-data/pdf-docx-200/prepared/pdf/179-scifact-16760369.pdf) / [DOCX](../../evaluation-data/pdf-docx-200/prepared/docx/179-scifact-16760369.docx)
- queryId=`437`，PDF rank=Top10未命中，DOCX rank=Top10未命中，问题：Functional consequences of genomic alterations due to Myelodysplastic syndrome (MDS) are poorly understood due to the lack of an animal model.；源文件 [PDF](../../evaluation-data/pdf-docx-200/prepared/pdf/080-scifact-18399038.pdf) / [DOCX](../../evaluation-data/pdf-docx-200/prepared/docx/080-scifact-18399038.docx)
- queryId=`502`，PDF rank=Top10未命中，DOCX rank=Top10未命中，问题：Healthcare delivery efficiency in crowded delivery centers is impaired by improving structural, logistical, and interpersonal elements.；源文件 [PDF](../../evaluation-data/pdf-docx-200/prepared/pdf/162-scifact-13071728.pdf) / [DOCX](../../evaluation-data/pdf-docx-200/prepared/docx/162-scifact-13071728.docx)
- queryId=`1363`，PDF rank=Top10未命中，DOCX rank=Top10未命中，问题：Venules have a thinner or absent smooth layer compared to arterioles.；源文件 [PDF](../../evaluation-data/pdf-docx-200/prepared/pdf/030-scifact-8290953.pdf) / [DOCX](../../evaluation-data/pdf-docx-200/prepared/docx/030-scifact-8290953.docx)

### sparse

仅PDF命中：

- queryId=`1221`，PDF rank=8，DOCX rank=Top10未命中，问题：The genomic aberrations found in matasteses are very similar to those found in the primary tumor.；源文件 [PDF](../../evaluation-data/pdf-docx-200/prepared/pdf/126-scifact-19736671.pdf) / [DOCX](../../evaluation-data/pdf-docx-200/prepared/docx/126-scifact-19736671.docx)
- queryId=`384`，PDF rank=5，DOCX rank=Top10未命中，问题：Epidemiological disease burden from noncommunicable diseases is more prevalent in low economic settings.；源文件 [PDF](../../evaluation-data/pdf-docx-200/prepared/pdf/072-scifact-13770184.pdf) / [DOCX](../../evaluation-data/pdf-docx-200/prepared/docx/072-scifact-13770184.docx)
- queryId=`517`，PDF rank=3，DOCX rank=Top10未命中，问题：High levels of copeptin decrease risk of diabetes.；源文件 [PDF](../../evaluation-data/pdf-docx-200/prepared/pdf/062-scifact-15663829.pdf) / [DOCX](../../evaluation-data/pdf-docx-200/prepared/docx/062-scifact-15663829.docx)
- queryId=`1200`，PDF rank=6，DOCX rank=Top10未命中，问题：The binding orientation of the ML-SA1 activator at hTRPML2 is different from the binding orientation of the ML-SA1 activator at hTRPML1.；源文件 [PDF](../../evaluation-data/pdf-docx-200/prepared/pdf/194-scifact-3441524.pdf) / [DOCX](../../evaluation-data/pdf-docx-200/prepared/docx/194-scifact-3441524.docx)
- queryId=`300`，PDF rank=4，DOCX rank=Top10未命中，问题：Cytosolic proteins bind to iron-responsive elements on mRNAs coding for DMT1. Cytosolic proteins bind to iron-responsive elements on mRNAs coding for proteins involved in iron uptake.；源文件 [PDF](../../evaluation-data/pdf-docx-200/prepared/pdf/099-scifact-3553087.pdf) / [DOCX](../../evaluation-data/pdf-docx-200/prepared/docx/099-scifact-3553087.docx)

仅DOCX命中：

- queryId=`1395`，PDF rank=Top10未命中，DOCX rank=7，问题：p16INK4A accumulation is  linked to an abnormal wound response caused by the microinvasive step of advanced Oral Potentially Malignant Lesions (OPMLs).；源文件 [PDF](../../evaluation-data/pdf-docx-200/prepared/pdf/188-scifact-17717391.pdf) / [DOCX](../../evaluation-data/pdf-docx-200/prepared/docx/188-scifact-17717391.docx)
- queryId=`554`，PDF rank=Top10未命中，DOCX rank=3，问题：Immune complex triggered cell death leads to extracellular release of neutrophil protein HMGB1.；源文件 [PDF](../../evaluation-data/pdf-docx-200/prepared/pdf/134-scifact-1049501.pdf) / [DOCX](../../evaluation-data/pdf-docx-200/prepared/docx/134-scifact-1049501.docx)
- queryId=`115`，PDF rank=Top10未命中，DOCX rank=5，问题：Anthrax spores can be disposed of easily after they are dispersed.；源文件 [PDF](../../evaluation-data/pdf-docx-200/prepared/pdf/120-scifact-33872649.pdf) / [DOCX](../../evaluation-data/pdf-docx-200/prepared/docx/120-scifact-33872649.docx)
- queryId=`1282`，PDF rank=Top10未命中，DOCX rank=3，问题：Therapeutic use of the drug Dapsone to treat pyoderma gangrenous is based on anecdotal evidence.；源文件 [PDF](../../evaluation-data/pdf-docx-200/prepared/pdf/127-scifact-23649163.pdf) / [DOCX](../../evaluation-data/pdf-docx-200/prepared/docx/127-scifact-23649163.docx)
- queryId=`5`，PDF rank=Top10未命中，DOCX rank=5，问题：1/2000 in UK have abnormal PrP positivity.；源文件 [PDF](../../evaluation-data/pdf-docx-200/prepared/pdf/027-scifact-13734012.pdf) / [DOCX](../../evaluation-data/pdf-docx-200/prepared/docx/027-scifact-13734012.docx)
- queryId=`1088`，PDF rank=Top10未命中，DOCX rank=4，问题：Silencing of Bcl2 is important for the maintenance and progression of tumors.；源文件 [PDF](../../evaluation-data/pdf-docx-200/prepared/pdf/043-scifact-37549932.pdf) / [DOCX](../../evaluation-data/pdf-docx-200/prepared/docx/043-scifact-37549932.docx)
- queryId=`1204`，PDF rank=Top10未命中，DOCX rank=2，问题：The combination of H3K4me3 and H3K79me2 is found in quiescent hair follicle stem cells.；源文件 [PDF](../../evaluation-data/pdf-docx-200/prepared/pdf/085-scifact-31141365.pdf) / [DOCX](../../evaluation-data/pdf-docx-200/prepared/docx/085-scifact-31141365.docx)

两者均未命中：

- queryId=`768`，PDF rank=Top10未命中，DOCX rank=Top10未命中，问题：Mercaptopurine is anabolized into the inactive methylmercaptopurine by thiopurine methyltrasnferase (TPMT).；源文件 [PDF](../../evaluation-data/pdf-docx-200/prepared/pdf/051-scifact-6421792.pdf) / [DOCX](../../evaluation-data/pdf-docx-200/prepared/docx/051-scifact-6421792.docx)
- queryId=`544`，PDF rank=Top10未命中，DOCX rank=Top10未命中，问题：IFIT1 restricts viral replication by sequestrating mis-capped viral RNAs.；源文件 [PDF](../../evaluation-data/pdf-docx-200/prepared/pdf/189-scifact-24221369.pdf) / [DOCX](../../evaluation-data/pdf-docx-200/prepared/docx/189-scifact-24221369.docx)
- queryId=`1175`，PDF rank=Top10未命中，DOCX rank=Top10未命中，问题：The PPR MDA5 has two N-terminal CARD domains.；源文件 [PDF](../../evaluation-data/pdf-docx-200/prepared/pdf/115-scifact-31272411.pdf) / [DOCX](../../evaluation-data/pdf-docx-200/prepared/docx/115-scifact-31272411.docx)
- queryId=`1225`，PDF rank=Top10未命中，DOCX rank=Top10未命中，问题：The locus rs647161 is associated with colorectal carcinoma.；源文件 [PDF](../../evaluation-data/pdf-docx-200/prepared/pdf/033-scifact-9650982.pdf) / [DOCX](../../evaluation-data/pdf-docx-200/prepared/docx/033-scifact-9650982.docx)
- queryId=`775`，PDF rank=Top10未命中，DOCX rank=Top10未命中，问题：Mice defective for deoxyribonucleic acid (DNA) polymerase I (polI) reveal increased sensitivity to ionizing radiation (IR).；源文件 [PDF](../../evaluation-data/pdf-docx-200/prepared/pdf/037-scifact-32275758.pdf) / [DOCX](../../evaluation-data/pdf-docx-200/prepared/docx/037-scifact-32275758.docx)
- queryId=`94`，PDF rank=Top10未命中，DOCX rank=Top10未命中，问题：Albendazole is used to treat lymphatic filariasis.；源文件 [PDF](../../evaluation-data/pdf-docx-200/prepared/pdf/038-scifact-1215116.pdf) / [DOCX](../../evaluation-data/pdf-docx-200/prepared/docx/038-scifact-1215116.docx)
- queryId=`830`，PDF rank=Top10未命中，DOCX rank=Top10未命中，问题：NF2 (Merlin) causes phosphorylation and subsequent cytoplasmic sequestration of YAP in Drosophila by activating LATS1/2 kinases.；源文件 [PDF](../../evaluation-data/pdf-docx-200/prepared/pdf/026-scifact-1897324.pdf) / [DOCX](../../evaluation-data/pdf-docx-200/prepared/docx/026-scifact-1897324.docx)
- queryId=`560`，PDF rank=Top10未命中，DOCX rank=Top10未命中，问题：Immune responses result in the development of inflammatory Th17 cells and anti-inflammatory iTregs.；源文件 [PDF](../../evaluation-data/pdf-docx-200/prepared/pdf/011-scifact-40096222.pdf) / [DOCX](../../evaluation-data/pdf-docx-200/prepared/docx/011-scifact-40096222.docx)
- queryId=`1191`，PDF rank=Top10未命中，DOCX rank=Top10未命中，问题：The amount of publicly available DNA data doubles every 10 years.；源文件 [PDF](../../evaluation-data/pdf-docx-200/prepared/pdf/041-scifact-30655442.pdf) / [DOCX](../../evaluation-data/pdf-docx-200/prepared/docx/041-scifact-30655442.docx)
- queryId=`1194`，PDF rank=Top10未命中，DOCX rank=Top10未命中，问题：The arm density of TatAd complexes is due to structural rearrangements within Class1 TatAd complexes such as the 'charge zipper mechanism'.；源文件 [PDF](../../evaluation-data/pdf-docx-200/prepared/pdf/141-scifact-11419230.pdf) / [DOCX](../../evaluation-data/pdf-docx-200/prepared/docx/141-scifact-11419230.docx)

### hybrid_rrf

仅PDF命中：

- queryId=`94`，PDF rank=6，DOCX rank=Top10未命中，问题：Albendazole is used to treat lymphatic filariasis.；源文件 [PDF](../../evaluation-data/pdf-docx-200/prepared/pdf/038-scifact-1215116.pdf) / [DOCX](../../evaluation-data/pdf-docx-200/prepared/docx/038-scifact-1215116.docx)
- queryId=`1`，PDF rank=1，DOCX rank=Top10未命中，问题：0-dimensional biomaterials show inductive properties.；源文件 [PDF](../../evaluation-data/pdf-docx-200/prepared/pdf/168-scifact-31715818.pdf) / [DOCX](../../evaluation-data/pdf-docx-200/prepared/docx/168-scifact-31715818.docx)
- queryId=`502`，PDF rank=5，DOCX rank=Top10未命中，问题：Healthcare delivery efficiency in crowded delivery centers is impaired by improving structural, logistical, and interpersonal elements.；源文件 [PDF](../../evaluation-data/pdf-docx-200/prepared/pdf/162-scifact-13071728.pdf) / [DOCX](../../evaluation-data/pdf-docx-200/prepared/docx/162-scifact-13071728.docx)
- queryId=`1363`，PDF rank=2，DOCX rank=Top10未命中，问题：Venules have a thinner or absent smooth layer compared to arterioles.；源文件 [PDF](../../evaluation-data/pdf-docx-200/prepared/pdf/030-scifact-8290953.pdf) / [DOCX](../../evaluation-data/pdf-docx-200/prepared/docx/030-scifact-8290953.docx)
- queryId=`517`，PDF rank=7，DOCX rank=Top10未命中，问题：High levels of copeptin decrease risk of diabetes.；源文件 [PDF](../../evaluation-data/pdf-docx-200/prepared/pdf/062-scifact-15663829.pdf) / [DOCX](../../evaluation-data/pdf-docx-200/prepared/docx/062-scifact-15663829.docx)

仅DOCX命中：

- queryId=`1175`，PDF rank=Top10未命中，DOCX rank=4，问题：The PPR MDA5 has two N-terminal CARD domains.；源文件 [PDF](../../evaluation-data/pdf-docx-200/prepared/pdf/115-scifact-31272411.pdf) / [DOCX](../../evaluation-data/pdf-docx-200/prepared/docx/115-scifact-31272411.docx)
- queryId=`775`，PDF rank=Top10未命中，DOCX rank=7，问题：Mice defective for deoxyribonucleic acid (DNA) polymerase I (polI) reveal increased sensitivity to ionizing radiation (IR).；源文件 [PDF](../../evaluation-data/pdf-docx-200/prepared/pdf/037-scifact-32275758.pdf) / [DOCX](../../evaluation-data/pdf-docx-200/prepared/docx/037-scifact-32275758.docx)
- queryId=`1196`，PDF rank=Top10未命中，DOCX rank=2，问题：The availability of safe places to study is effective at decreasing homelessness.；源文件 [PDF](../../evaluation-data/pdf-docx-200/prepared/pdf/003-scifact-25649714.pdf) / [DOCX](../../evaluation-data/pdf-docx-200/prepared/docx/003-scifact-25649714.docx)
- queryId=`132`，PDF rank=Top10未命中，DOCX rank=9，问题：Aspirin inhibits the production of PGE2.；源文件 [PDF](../../evaluation-data/pdf-docx-200/prepared/pdf/053-scifact-7975937.pdf) / [DOCX](../../evaluation-data/pdf-docx-200/prepared/docx/053-scifact-7975937.docx)

两者均未命中：

- queryId=`830`，PDF rank=Top10未命中，DOCX rank=Top10未命中，问题：NF2 (Merlin) causes phosphorylation and subsequent cytoplasmic sequestration of YAP in Drosophila by activating LATS1/2 kinases.；源文件 [PDF](../../evaluation-data/pdf-docx-200/prepared/pdf/026-scifact-1897324.pdf) / [DOCX](../../evaluation-data/pdf-docx-200/prepared/docx/026-scifact-1897324.docx)
- queryId=`560`，PDF rank=Top10未命中，DOCX rank=Top10未命中，问题：Immune responses result in the development of inflammatory Th17 cells and anti-inflammatory iTregs.；源文件 [PDF](../../evaluation-data/pdf-docx-200/prepared/pdf/011-scifact-40096222.pdf) / [DOCX](../../evaluation-data/pdf-docx-200/prepared/docx/011-scifact-40096222.docx)
- queryId=`1191`，PDF rank=Top10未命中，DOCX rank=Top10未命中，问题：The amount of publicly available DNA data doubles every 10 years.；源文件 [PDF](../../evaluation-data/pdf-docx-200/prepared/pdf/041-scifact-30655442.pdf) / [DOCX](../../evaluation-data/pdf-docx-200/prepared/docx/041-scifact-30655442.docx)
- queryId=`1199`，PDF rank=Top10未命中，DOCX rank=Top10未命中，问题：The benefits of colchicine were achieved with effective widespread use of secondary prevention strategies such as high-dose statins.；源文件 [PDF](../../evaluation-data/pdf-docx-200/prepared/pdf/179-scifact-16760369.pdf) / [DOCX](../../evaluation-data/pdf-docx-200/prepared/docx/179-scifact-16760369.docx)
- queryId=`577`，PDF rank=Top10未命中，DOCX rank=Top10未命中，问题：In mice, P. chabaudi parasites are able to proliferate faster early in infection when inoculated at lower numbers than when inoculated at high numbers.；源文件 [PDF](../../evaluation-data/pdf-docx-200/prepared/pdf/057-scifact-5289038.pdf) / [DOCX](../../evaluation-data/pdf-docx-200/prepared/docx/057-scifact-5289038.docx)
- queryId=`914`，PDF rank=Top10未命中，DOCX rank=Top10未命中，问题：PPAR-RXRs can be activated by PPAR ligands.；源文件 [PDF](../../evaluation-data/pdf-docx-200/prepared/pdf/067-scifact-3203590.pdf) / [DOCX](../../evaluation-data/pdf-docx-200/prepared/docx/067-scifact-3203590.docx)
- queryId=`431`，PDF rank=Top10未命中，DOCX rank=Top10未命中，问题：FoxO3a activation in neuronal death is mediated by reactive oxygen species (ROS).；源文件 [PDF](../../evaluation-data/pdf-docx-200/prepared/pdf/092-scifact-28937856.pdf) / [DOCX](../../evaluation-data/pdf-docx-200/prepared/docx/092-scifact-28937856.docx)
- queryId=`887`，PDF rank=Top10未命中，DOCX rank=Top10未命中，问题：Only a minority of cells survive development after differentiation into stress-resistant spores.；源文件 [PDF](../../evaluation-data/pdf-docx-200/prepared/pdf/022-scifact-18855191.pdf) / [DOCX](../../evaluation-data/pdf-docx-200/prepared/docx/022-scifact-18855191.docx)
- queryId=`437`，PDF rank=Top10未命中，DOCX rank=Top10未命中，问题：Functional consequences of genomic alterations due to Myelodysplastic syndrome (MDS) are poorly understood due to the lack of an animal model.；源文件 [PDF](../../evaluation-data/pdf-docx-200/prepared/pdf/080-scifact-18399038.pdf) / [DOCX](../../evaluation-data/pdf-docx-200/prepared/docx/080-scifact-18399038.docx)
- queryId=`690`，PDF rank=Top10未命中，DOCX rank=Top10未命中，问题：Less than 10% of the gabonese children with Schimmelpenning-Feuerstein-Mims syndrome (SFM) had a plasma lactate of more than 5mmol/L.；源文件 [PDF](../../evaluation-data/pdf-docx-200/prepared/pdf/190-scifact-18750453.pdf) / [DOCX](../../evaluation-data/pdf-docx-200/prepared/docx/190-scifact-18750453.docx)

## 结论

- Dense在两种格式的Recall@10均为0.960，格式没有改变Top10总召回上限。
- DOCX生成706个chunk，PDF生成530个，DOCX多176个。
- Rerank平均端到端耗时相对Hybrid：PDF 4.15x，DOCX 3.77x。
- Dense格式独占命中：PDF 3，DOCX 3；同为未命中 5。
- 资源峰值显示Reranker与Embedding为主要计算热点；容器inspect前后完全一致=true。

## 证据边界

- PDF与DOCX使用同一200问题、同一qrels、同一源正文与同一检索配置。
- 该数据集是确定性派生版面压力集，不等价于真实世界原生Office/PDF分布。
- 资源采样覆盖两次串行IR_FULL运行，不能拆分成每个格式各自独立资源分布。

## 输入SHA-256

- qrels: `2a808171a79832d5798afb879c2d912f5c8863b09c6427fe454f20dc2a025f73`
- remote-sampler.err.log: `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855`
- remote-inspect-before.txt: `f77641207bc649583796fc76b45e4f9f1e909dcd8b52711fc01b39608a528c0a`
- docxRun: `cc81197ce8d5f06856ce9b1b143fdfe4207b4abba0d07709e03632c0508f2ed1`
- local-process.jsonl: `8e41d97bf37216591a60120779554aa9178bd2cde99e973bdd0bc8e5c5a609de`
- queries: `146e928420eabd22ee95322f1711cdee9bd42cfa456db44090a35e8c414eaf35`
- pdfRun: `839bc25bf08055824850a351f03f613fe4ffe3c6d05583ce3547936fefe1ab08`
- pdfDocuments: `c06e6239e3ceeb7f68302f7c0cc2745b234a7cf53828a69bd64045f18ede6ed0`
- remote-containers.jsonl: `3e5a984116e77dea32ea3a84d6dca31f45cba8ad4703fcec977e878d9b99a8c3`
- docxDocuments: `14cd30a708c4f30ab0824a93ec6ca4c36cddc765252ab6b695b5858fc3e0f6d0`
- remote-inspect-after.txt: `7203cc73ec3f9e357489c8e612633c0468cb77a97ad80e9aa458a280af0ab0b8`
- documentManifest: `2f32d1cafa883caec5c4bf2149f438f5ea99abdfe02f1cb95651ad71ed42bbfa`
