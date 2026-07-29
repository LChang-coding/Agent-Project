# RAG召回失败案例可复算报告

生成器：rag-failure-case-v1；查询数：200；run记录数：800。

## 证据边界

- run只保存最终Top10文档ID，没有逐候选分数或Dense/Sparse内部候选ID；对应字段明确标记为未采集。
- 首个失败步骤是基于四个消融终态排名的首个可观测步骤，不等同于内部算子级因果证明。
- 词项重合只用于提出可证伪推断，不作为失败原因的直接证明。

## 分类总账

| 类别 | 全量案例数 | 展示数 |
|---|---:|---:|
| dense_miss_hybrid_hit | 2 | 2 |
| sparse_miss_hybrid_hit | 19 | 5 |
| rerank_rescue | 0 | 0 |
| rerank_harm | 0 | 0 |
| dense_only_success | 28 | 5 |
| sparse_only_success | 2 | 2 |
| persistent_miss | 5 | 5 |
| rerank_reorder_gain | 17 | 5 |
| rerank_reorder_harm | 11 | 5 |

## dense_miss_hybrid_hit

### queryId=324

问题：Deleting Raptor reduces G-CSF levels.

Gold文档：

- `2014909` Oncogenic mTOR signaling recruits myeloid-derived suppressor cells to promote tumor initiation

  > Myeloid-derived suppressor cells (MDSCs) play critical roles in primary and metastatic cancer progression. MDSC regulation is widely variable even among patients harbouring the same type of malignancy, and the mechanisms governing such heterogeneity are largely unknown. Here, integrating human tumour genomics and syngeneic mammary tumour models, we demonstrate that mTOR signalling in cancer cells dictates a mammary tumour's ability to stimulate MDSC accumulation through regulating G-CSF. Inhibiting this pathway or its activators (for example, FGFR) impairs tumour progression, which is partiall…

  本地源文件： `DOCX=prepared/docx/029-scifact-2014909.docx` `PDF=prepared/pdf/029-scifact-2014909.pdf`

| 变体 | Recall@10 | MRR@10 | nDCG@10 | Gold首名次 | 延迟ms | Top10文档ID（*为gold） |
|---|---:|---:|---:|---:|---:|---|
| dense | 0.000000 | 0.000000 | 0.000000 | Top10未命中 | 1755 | 3553087, 12827098, 26851674, 33370, 16472469, 14767844, 2988714, 8460275 |
| sparse | 1.000000 | 0.500000 | 0.630930 | 2 | 1467 | 31272411, 2014909*, 16256507, 16472469, 9745001, 1834762, 39381118, 32159283, 12827098 |
| hybrid_rrf | 1.000000 | 0.333333 | 0.500000 | 3 | 1660 | 16472469, 12827098, 2014909*, 14767844, 26851674, 33370, 28937856, 13905670, 970012, 17755060 |
| hybrid_rrf_rerank | 1.000000 | 1.000000 | 1.000000 | 1 | 5926 | 2014909*, 12827098, 17755060, 970012, 13905670, 33370, 28937856, 16472469, 26851674, 14767844 |

首个失败步骤中的关键错误召回文档（前3条非gold）：

- rank=1 `3553087` Mitochondrial iron chelation ameliorates cigarette-smoke induced bronchitis and emphysema in mice（本地heading=`SCIFACT-EVIDENCE-3553087`）

  > Chronic obstructive pulmonary disease (COPD) is linked to both cigarette smoking and genetic determinants. We have previously identified iron-responsive element-binding protein 2 (IRP2) as an important COPD susceptibility gene and have shown that IRP2 protein is increased in the lungs of individuals with COPD. Here we demonstrate that mice deficient in Irp2 were protected from cigarette smoke (CS)-induced experimental COPD. By integrating RNA immunoprecipitation followed by sequencing (RIP-seq), RNA sequencing (RNA-seq), and gene expression and functional enrichment clustering analysis, we ide…

  本地源文件： `DOCX=prepared/docx/099-scifact-3553087.docx` `PDF=prepared/pdf/099-scifact-3553087.pdf`
- rank=2 `12827098` Tissue-resident macrophages self-maintain locally throughout adult life with minimal contribution from circulating monocytes.（本地heading=`SCIFACT-EVIDENCE-12827098`）

  > Despite accumulating evidence suggesting local self-maintenance of tissue macrophages in the steady state, the dogma remains that tissue macrophages derive from monocytes. Using parabiosis and fate-mapping approaches, we confirmed that monocytes do not show significant contribution to tissue macrophages in the steady state. Similarly, we found that after depletion of lung macrophages, the majority of repopulation occurred by stochastic cellular proliferation in situ in a macrophage colony-stimulating factor (M-Csf)- and granulocyte macrophage (GM)-CSF-dependent manner but independently of inte…

  本地源文件： `DOCX=prepared/docx/046-scifact-12827098.docx` `PDF=prepared/pdf/046-scifact-12827098.pdf`
- rank=3 `26851674` Dissection of signaling cascades through gp130 in vivo: reciprocal roles for STAT3- and SHP2-mediated signals in immune responses.（本地heading=`SCIFACT-EVIDENCE-26851674`）

  > We generated a series of knockin mouse lines, in which the cytokine receptor gp130-dependent STAT3 and/or SHP2 signals were disrupted, by replacing the mouse gp130 gene with human gp130 mutant cDNAs. The SHP2 signal-deficient mice (gp130F759/F759 were born normal but displayed splenomegaly and lymphadenopathy and an enhanced acute phase reaction. In contrast, the STAT3 signal-deficient mice (gp130FXQ/FXXQ) died perinatally, like the gp130-deficient mice (gp130D/D). The gp130F759/F759 mice showed prolonged gp130-induced STAT3 activation, indicating a negative regulatory role for SHP2. Th1-type…

  本地源文件： `DOCX=prepared/docx/098-scifact-26851674.docx` `PDF=prepared/pdf/098-scifact-26851674.pdf`

首个可观测失败步骤：`dense_final_top10`。

直接证据：

- Dense gold首名次=Top10未命中
- Sparse gold首名次=2
- Hybrid-RRF gold首名次=3
- Hybrid-RRF+Rerank gold首名次=1
- 分类规则=dense_miss_hybrid_hit
- 逐候选分数=未采集

推断：Dense语义向量把主题相近干扰文档排在gold之前，而精确术语帮助Sparse命中；最大词项Jaccard=0.0128。

其他可能解释：相关性标注不完整、gold摘要与claim粒度不同、分块边界或Top10截断；当前终态run不能排除这些因素（dense_miss_hybrid_hit）。

反证实验：固定同一索引和query，额外留存Dense/Sparse候选Top100、融合逐项贡献、Rerank输入输出分数及chunk文本，再复跑该query验证首个内部失效点（dense_miss_hybrid_hit）。

### queryId=1175

问题：The PPR MDA5 has two N-terminal CARD domains.

Gold文档：

- `31272411` Immune signaling by RIG-I-like receptors.

  > The RIG-I-like receptors (RLRs) RIG-I, MDA5, and LGP2 play a major role in pathogen sensing of RNA virus infection to initiate and modulate antiviral immunity. The RLRs detect viral RNA ligands or processed self RNA in the cytoplasm to trigger innate immunity and inflammation and to impart gene expression that serves to control infection. Importantly, RLRs cooperate in signaling crosstalk networks with Toll-like receptors and other factors to impart innate immunity and to modulate the adaptive immune response. RLR regulation occurs at a variety of levels ranging from autoregulation to ligand a…

  本地源文件： `DOCX=prepared/docx/115-scifact-31272411.docx` `PDF=prepared/pdf/115-scifact-31272411.pdf`

| 变体 | Recall@10 | MRR@10 | nDCG@10 | Gold首名次 | 延迟ms | Top10文档ID（*为gold） |
|---|---:|---:|---:|---:|---:|---|
| dense | 0.000000 | 0.000000 | 0.000000 | Top10未命中 | 1750 | 4423559, 15319019, 11603066, 5531479, 4387784 |
| sparse | 0.000000 | 0.000000 | 0.000000 | Top10未命中 | 1390 | 8646760, 11603066, 39381118, 28937856, 4687948 |
| hybrid_rrf | 1.000000 | 0.250000 | 0.430677 | 4 | 1700 | 11603066, 8646760, 22180793, 31272411*, 24221369, 15319019, 4387784 |
| hybrid_rrf_rerank | 1.000000 | 1.000000 | 1.000000 | 1 | 7910 | 31272411*, 22180793, 11603066, 8646760, 15319019, 4387784, 24221369 |

首个失败步骤中的关键错误召回文档（前3条非gold）：

- rank=1 `4423559` Planar cell polarity signalling couples cell division and morphogenesis during neurulation（本地heading=`SCIFACT-EVIDENCE-4423559`）

  > Environmental and genetic aberrations lead to neural tube closure defects (NTDs) in 1 out of every 1,000 births. Mouse and frog models for these birth defects have indicated that Van Gogh-like 2 (Vangl2, also known as Strabismus) and other components of planar cell polarity (PCP) signalling might control neurulation by promoting the convergence of neural progenitors to the midline. Here we show a novel role for PCP signalling during neurulation in zebrafish. We demonstrate that non-canonical Wnt/PCP signalling polarizes neural progenitors along the anteroposterior axis. This polarity is transi…

  本地源文件： `DOCX=prepared/docx/034-scifact-4423559.docx` `PDF=prepared/pdf/034-scifact-4423559.pdf`
- rank=2 `15319019` N348I in the Connection Domain of HIV-1 Reverse Transcriptase Confers Zidovudine and Nevirapine Resistance（本地heading=`SCIFACT-EVIDENCE-15319019`）

  > Background The catalytically active 66-kDa subunit of the human immunodeficiency virus type 1 (HIV-1) reverse transcriptase (RT) consists of DNA polymerase, connection, and ribonuclease H (RNase H) domains. Almost all known RT inhibitor resistance mutations identified to date map to the polymerase domain of the enzyme. However, the connection and RNase H domains are not routinely analysed in clinical samples and none of the genotyping assays available for patient management sequence the entire RT coding region. The British Columbia Centre for Excellence in HIV/AIDS (the Centre) genotypes clini…

  本地源文件： `DOCX=prepared/docx/185-scifact-15319019.docx` `PDF=prepared/pdf/185-scifact-15319019.pdf`
- rank=3 `11603066` Using Structural Information to Change the Phosphotransfer Specificity of a Two-Component Chemotaxis Signalling Complex（本地heading=`SCIFACT-EVIDENCE-11603066`）

  > Two-component signal transduction pathways comprising histidine protein kinases (HPKs) and their response regulators (RRs) are widely used to control bacterial responses to environmental challenges. Some bacteria have over 150 different two-component pathways, and the specificity of the phosphotransfer reactions within these systems is tightly controlled to prevent unwanted crosstalk. One of the best understood two-component signalling pathways is the chemotaxis pathway. Here, we present the 1.40 A crystal structure of the histidine-containing phosphotransfer domain of the chemotaxis HPK, CheA…

  本地源文件： `DOCX=prepared/docx/170-scifact-11603066.docx` `PDF=prepared/pdf/170-scifact-11603066.pdf`

首个可观测失败步骤：`dense_final_top10`。

直接证据：

- Dense gold首名次=Top10未命中
- Sparse gold首名次=Top10未命中
- Hybrid-RRF gold首名次=4
- Hybrid-RRF+Rerank gold首名次=1
- 分类规则=dense_miss_hybrid_hit
- 逐候选分数=未采集

推断：Top10截断、语义近邻竞争或Rerank排序偏好可能共同影响；query与gold最大词项Jaccard=0.0294。

其他可能解释：相关性标注不完整、gold摘要与claim粒度不同、分块边界或Top10截断；当前终态run不能排除这些因素（dense_miss_hybrid_hit）。

反证实验：固定同一索引和query，额外留存Dense/Sparse候选Top100、融合逐项贡献、Rerank输入输出分数及chunk文本，再复跑该query验证首个内部失效点（dense_miss_hybrid_hit）。

## sparse_miss_hybrid_hit

### queryId=1049

问题：Ribosomopathies have a low degree of cell and tissue specific pathology.

Gold文档：

- `12486491` Ribosome-Mediated Specificity in Hox mRNA Translation and Vertebrate Tissue Patterning

  > Historically, the ribosome has been viewed as a complex ribozyme with constitutive rather than regulatory capacity in mRNA translation. Here we identify mutations of the Ribosomal Protein L38 (Rpl38) gene in mice exhibiting surprising tissue-specific patterning defects, including pronounced homeotic transformations of the axial skeleton. In Rpl38 mutant embryos, global protein synthesis is unchanged; however the translation of a select subset of Homeobox mRNAs is perturbed. Our data reveal that RPL38 facilitates 80S complex formation on these mRNAs as a regulatory component of the ribosome to…

  本地源文件： `DOCX=prepared/docx/174-scifact-12486491.docx` `PDF=prepared/pdf/174-scifact-12486491.pdf`

| 变体 | Recall@10 | MRR@10 | nDCG@10 | Gold首名次 | 延迟ms | Top10文档ID（*为gold） |
|---|---:|---:|---:|---:|---:|---|
| dense | 1.000000 | 1.000000 | 1.000000 | 1 | 1936 | 12486491*, 13905670, 3441524, 5476778, 1049501, 3475317 |
| sparse | 0.000000 | 0.000000 | 0.000000 | Top10未命中 | 1502 | 45638119, 24142891, 7521113, 5483793, 13230773, 23460562, 4350400 |
| hybrid_rrf | 1.000000 | 1.000000 | 1.000000 | 1 | 2026 | 12486491*, 7521113, 23460562, 13230773, 1215116 |
| hybrid_rrf_rerank | 1.000000 | 1.000000 | 1.000000 | 1 | 4942 | 12486491*, 23460562, 7521113, 1215116, 13230773 |

首个失败步骤中的关键错误召回文档（前3条非gold）：

- rank=1 `45638119` ALDH1 is a marker of normal and malignant human mammary stem cells and a predictor of poor clinical outcome.（本地heading=`SCIFACT-EVIDENCE-45638119`）

  > Application of stem cell biology to breast cancer research has been limited by the lack of simple methods for identification and isolation of normal and malignant stem cells. Utilizing in vitro and in vivo experimental systems, we show that normal and cancer human mammary epithelial cells with increased aldehyde dehydrogenase activity (ALDH) have stem/progenitor properties. These cells contain the subpopulation of normal breast epithelium with the broadest lineage differentiation potential and greatest growth capacity in a xenotransplant model. In breast carcinomas, high ALDH activity identifi…

  本地源文件： `DOCX=prepared/docx/050-scifact-45638119.docx` `PDF=prepared/pdf/050-scifact-45638119.pdf`
- rank=2 `24142891` Tmem27: a cleaved and shed plasma membrane protein that stimulates pancreatic beta cell proliferation.（本地heading=`SCIFACT-EVIDENCE-24142891`）

  > The signals and molecular mechanisms that regulate the replication of terminally differentiated beta cells are unknown. Here, we report the identification and characterization of transmembrane protein 27 (Tmem27, collectrin) in pancreatic beta cells. Expression of Tmem27 is reduced in Tcf1(-/-) mice and is increased in islets of mouse models with hypertrophy of the endocrine pancreas. Tmem27 forms dimers and its extracellular domain is glycosylated, cleaved and shed from the plasma membrane of beta cells. This cleavage process is beta cell specific and does not occur in other cell types. Overe…

  本地源文件： `DOCX=prepared/docx/006-scifact-24142891.docx` `PDF=prepared/pdf/006-scifact-24142891.pdf`
- rank=3 `7521113` Fate mapping reveals origins and dynamics of monocytes and tissue macrophages under homeostasis.（本地heading=`SCIFACT-EVIDENCE-7521113`）

  > Mononuclear phagocytes, including monocytes, macrophages, and dendritic cells, contribute to tissue integrity as well as to innate and adaptive immune defense. Emerging evidence for labor division indicates that manipulation of these cells could bear therapeutic potential. However, specific ontogenies of individual populations and the overall functional organization of this cellular network are not well defined. Here we report a fate-mapping study of the murine monocyte and macrophage compartment taking advantage of constitutive and conditional CX(3)CR1 promoter-driven Cre recombinase expressi…

  本地源文件： `DOCX=prepared/docx/195-scifact-7521113.docx` `PDF=prepared/pdf/195-scifact-7521113.pdf`

首个可观测失败步骤：`sparse_final_top10`。

直接证据：

- Dense gold首名次=1
- Sparse gold首名次=Top10未命中
- Hybrid-RRF gold首名次=1
- Hybrid-RRF+Rerank gold首名次=1
- 分类规则=sparse_miss_hybrid_hit
- 逐候选分数=未采集

推断：稀疏词项匹配对同义改写或词形差异不敏感；query与gold标题/片段最大词项Jaccard=0.0533。

其他可能解释：相关性标注不完整、gold摘要与claim粒度不同、分块边界或Top10截断；当前终态run不能排除这些因素（sparse_miss_hybrid_hit）。

反证实验：固定同一索引和query，额外留存Dense/Sparse候选Top100、融合逐项贡献、Rerank输入输出分数及chunk文本，再复跑该query验证首个内部失效点（sparse_miss_hybrid_hit）。

### queryId=1207

问题：The composition of myosin-II isoform switches from the polarizable B isoform to the more homogenous A isoform during hematopoietic differentiation.

Gold文档：

- `18909530` Contractile forces sustain and polarize hematopoiesis from stem and progenitor cells.

  > Self-renewal and differentiation of stem cells depend on asymmetric division and polarized motility processes that in other cell types are modulated by nonmuscle myosin-II (MII) forces and matrix mechanics. Here, mass spectrometry-calibrated intracellular flow cytometry of human hematopoiesis reveals MIIB to be a major isoform that is strongly polarized in hematopoietic stem cells and progenitors (HSC/Ps) and thereby downregulated in differentiated cells via asymmetric division. MIIA is constitutive and activated by dephosphorylation during cytokine-triggered differentiation of cells grown on…

  本地源文件： `DOCX=prepared/docx/177-scifact-18909530.docx` `PDF=prepared/pdf/177-scifact-18909530.pdf`

| 变体 | Recall@10 | MRR@10 | nDCG@10 | Gold首名次 | 延迟ms | Top10文档ID（*为gold） |
|---|---:|---:|---:|---:|---:|---|
| dense | 1.000000 | 1.000000 | 1.000000 | 1 | 1747 | 18909530*, 11603066, 18174210 |
| sparse | 0.000000 | 0.000000 | 0.000000 | Top10未命中 | 1515 | 6173523, 306006, 12956194, 4942718, 11603066, 4387784, 1606628, 17628888, 1148122 |
| hybrid_rrf | 1.000000 | 1.000000 | 1.000000 | 1 | 2026 | 18909530*, 11603066, 4942718, 1148122 |
| hybrid_rrf_rerank | 1.000000 | 1.000000 | 1.000000 | 1 | 6653 | 18909530*, 11603066, 4942718, 1148122 |

首个失败步骤中的关键错误召回文档（前3条非gold）：

- rank=1 `6173523` A culture-independent sequence-based metagenomics approach to the investigation of an outbreak of Shiga-toxigenic Escherichia coli O104:H4.（本地heading=`SCIFACT-EVIDENCE-6173523`）

  > IMPORTANCE Identification of the bacterium responsible for an outbreak can aid in disease management. However, traditional culture-based diagnosis can be difficult, particularly if no specific diagnostic test is available for an outbreak strain. OBJECTIVE To explore the potential of metagenomics, which is the direct sequencing of DNA extracted from microbiologically complex samples, as an open-ended clinical discovery platform capable of identifying and characterizing bacterial strains from an outbreak without laboratory culture. DESIGN, SETTING, AND PATIENTS In a retrospective investigation,…

  本地源文件： `DOCX=prepared/docx/087-scifact-6173523.docx` `PDF=prepared/pdf/087-scifact-6173523.pdf`
- rank=2 `306006` The stimulatory potency of T cell antigens is influenced by the formation of the immunological synapse.（本地heading=`SCIFACT-EVIDENCE-306006`）

  > T cell activation is predicated on the interaction between the T cell receptor and peptide-major histocompatibility (pMHC) ligands. The factors that determine the stimulatory potency of a pMHC molecule remain unclear. We describe results showing that a peptide exhibiting many hallmarks of a weak agonist stimulates T cells to proliferate more than the wild-type agonist ligand. An in silico approach suggested that the inability to form the central supramolecular activation cluster (cSMAC) could underlie the increased proliferation. This conclusion was supported by experiments that showed that en…

  本地源文件： `DOCX=prepared/docx/077-scifact-306006.docx` `PDF=prepared/pdf/077-scifact-306006.pdf`
- rank=3 `12956194` The Extracellular Surface of the GLP-1 Receptor Is a Molecular Trigger for Biased Agonism（本地heading=`SCIFACT-EVIDENCE-12956194`）

  > Ligand-directed signal bias offers opportunities for sculpting molecular events, with the promise of better, safer therapeutics. Critical to the exploitation of signal bias is an understanding of the molecular events coupling ligand binding to intracellular signaling. Activation of class B G protein-coupled receptors is driven by interaction of the peptide N terminus with the receptor core. To understand how this drives signaling, we have used advanced analytical methods that enable separation of effects on pathway-specific signaling from those that modify agonist affinity and mapped the funct…

  本地源文件： `DOCX=prepared/docx/010-scifact-12956194.docx` `PDF=prepared/pdf/010-scifact-12956194.pdf`

首个可观测失败步骤：`sparse_final_top10`。

直接证据：

- Dense gold首名次=1
- Sparse gold首名次=Top10未命中
- Hybrid-RRF gold首名次=1
- Hybrid-RRF+Rerank gold首名次=1
- 分类规则=sparse_miss_hybrid_hit
- 逐候选分数=未采集

推断：稀疏词项匹配对同义改写或词形差异不敏感；query与gold标题/片段最大词项Jaccard=0.1233。

其他可能解释：相关性标注不完整、gold摘要与claim粒度不同、分块边界或Top10截断；当前终态run不能排除这些因素（sparse_miss_hybrid_hit）。

反证实验：固定同一索引和query，额外留存Dense/Sparse候选Top100、融合逐项贡献、Rerank输入输出分数及chunk文本，再复跑该query验证首个内部失效点（sparse_miss_hybrid_hit）。

### queryId=1221

问题：The genomic aberrations found in matasteses are very similar to those found in the primary tumor.

Gold文档：

- `19736671` Evolution of metastasis revealed by mutational landscapes of chemically induced skin cancers

  > Human tumors show a high level of genetic heterogeneity, but the processes that influence the timing and route of metastatic dissemination of the subclones are unknown. Here we have used whole-exome sequencing of 103 matched benign, malignant and metastatic skin tumors from genetically heterogeneous mice to demonstrate that most metastases disseminate synchronously from the primary tumor, supporting parallel rather than linear evolution as the predominant model of metastasis. Shared mutations between primary carcinomas and their matched metastases have the distinct A-to-T signature of the init…

  本地源文件： `DOCX=prepared/docx/126-scifact-19736671.docx` `PDF=prepared/pdf/126-scifact-19736671.pdf`

| 变体 | Recall@10 | MRR@10 | nDCG@10 | Gold首名次 | 延迟ms | Top10文档ID（*为gold） |
|---|---:|---:|---:|---:|---:|---|
| dense | 1.000000 | 1.000000 | 1.000000 | 1 | 1591 | 19736671*, 24341590, 18399038, 7521113, 16472469, 13519661, 2014909 |
| sparse | 0.000000 | 0.000000 | 0.000000 | Top10未命中 | 1466 | 12789595, 4687948, 17755060, 25742130, 11172205, 33370, 1606628, 3475317 |
| hybrid_rrf | 1.000000 | 1.000000 | 1.000000 | 1 | 1866 | 19736671*, 17755060, 18399038, 16787954, 33370, 2014909, 3475317, 14637235, 13519661 |
| hybrid_rrf_rerank | 1.000000 | 1.000000 | 1.000000 | 1 | 11266 | 19736671*, 2014909, 18399038, 33370, 17755060, 3475317, 13519661, 14637235, 16787954 |

首个失败步骤中的关键错误召回文档（前3条非gold）：

- rank=1 `12789595` Computer assisted learning in undergraduate medical education.（本地heading=`SCIFACT-EVIDENCE-12789595`）

  > It is becoming “a truth universally acknowledged” that the education of undergraduate medical students will be enhanced through the use of computer assisted learning. Access to the wide range of online options illustrated in the figure must surely make learning more exciting, effective, and likely to be retained. This assumption is potentially but by no means inevitably correct. ### Box 1: Why fund computer assisted learning? Computer assisted learning is inevitable —Individual lecturers and departments are already beginning to introduce a wide range of computer based applications, sometimes i…

  本地源文件： `DOCX=prepared/docx/069-scifact-12789595.docx` `PDF=prepared/pdf/069-scifact-12789595.pdf`
- rank=2 `4687948` HMG-CoA reductase inhibitors and the risk of hip fractures in elderly patients.（本地heading=`SCIFACT-EVIDENCE-4687948`）

  > CONTEXT Recent animal studies have found that 3-hydroxy-3-methylglutaryl coenzyme A (HMG-CoA) lipid-lowering drugs (statins) substantially increase bone formation, but whether statin use in humans results in clinically meaningful bone formation or a reduction in the risk of osteoporotic fractures is not known. OBJECTIVE To determine whether the use of statins is associated with reduced hip fracture risk. DESIGN Case-control study. SETTING AND PATIENTS A total of 6110 New Jersey residents aged 65 years or older and enrolled in Medicare and either Medicaid or the Pharmacy Assistance for the Aged…

  本地源文件： `DOCX=prepared/docx/036-scifact-4687948.docx` `PDF=prepared/pdf/036-scifact-4687948.pdf`
- rank=3 `17755060` Control of Nutrient Stress-Induced Metabolic Reprogramming by PKCζ in Tumorigenesis（本地heading=`SCIFACT-EVIDENCE-17755060`）

  > Tumor cells have high-energetic and anabolic needs and are known to adapt their metabolism to be able to survive and keep proliferating under conditions of nutrient stress. We show that PKCζ deficiency promotes the plasticity necessary for cancer cells to reprogram their metabolism to utilize glutamine through the serine biosynthetic pathway in the absence of glucose. PKCζ represses the expression of two key enzymes of the pathway, PHGDH and PSAT1, and phosphorylates PHGDH at key residues to inhibit its enzymatic activity. Interestingly, the loss of PKCζ in mice results in enhanced intestinal…

  本地源文件： `DOCX=prepared/docx/159-scifact-17755060.docx` `PDF=prepared/pdf/159-scifact-17755060.pdf`

首个可观测失败步骤：`sparse_final_top10`。

直接证据：

- Dense gold首名次=1
- Sparse gold首名次=Top10未命中
- Hybrid-RRF gold首名次=1
- Hybrid-RRF+Rerank gold首名次=1
- 分类规则=sparse_miss_hybrid_hit
- 逐候选分数=未采集

推断：稀疏词项匹配对同义改写或词形差异不敏感；query与gold标题/片段最大词项Jaccard=0.0641。

其他可能解释：相关性标注不完整、gold摘要与claim粒度不同、分块边界或Top10截断；当前终态run不能排除这些因素（sparse_miss_hybrid_hit）。

反证实验：固定同一索引和query，额外留存Dense/Sparse候选Top100、融合逐项贡献、Rerank输入输出分数及chunk文本，再复跑该query验证首个内部失效点（sparse_miss_hybrid_hit）。

### queryId=1194

问题：The arm density of TatAd complexes is due to structural rearrangements within Class1 TatAd complexes such as the 'charge zipper mechanism'.

Gold文档：

- `11419230` Folding and Self-Assembly of the TatA Translocation Pore Based on a Charge Zipper Mechanism

  > We propose a concept for the folding and self-assembly of the pore-forming TatA complex from the Twin-arginine translocase and of other membrane proteins based on electrostatic "charge zippers. " Each subunit of TatA consists of a transmembrane segment, an amphiphilic helix (APH), and a C-terminal densely charged region (DCR). The sequence of charges in the DCR is complementary to the charge pattern on the APH, suggesting that the protein can be "zipped up" by a ladder of seven salt bridges. The length of the resulting hairpin matches the lipid bilayer thickness, hence a transmembrane pore cou…

  本地源文件： `DOCX=prepared/docx/141-scifact-11419230.docx` `PDF=prepared/pdf/141-scifact-11419230.pdf`

| 变体 | Recall@10 | MRR@10 | nDCG@10 | Gold首名次 | 延迟ms | Top10文档ID（*为gold） |
|---|---:|---:|---:|---:|---:|---|
| dense | 1.000000 | 1.000000 | 1.000000 | 1 | 2151 | 11419230*, 33499189, 15319019, 11603066, 17628888 |
| sparse | 0.000000 | 0.000000 | 0.000000 | Top10未命中 | 1859 | 11335781, 33499189, 8460275, 28937856, 5476778, 5289038, 10991183, 20381484, 24221369 |
| hybrid_rrf | 1.000000 | 0.500000 | 0.630930 | 2 | 1890 | 33499189, 11419230*, 17628888, 10991183, 11603066 |
| hybrid_rrf_rerank | 1.000000 | 1.000000 | 1.000000 | 1 | 6353 | 11419230*, 10991183, 33499189, 17628888, 11603066 |

首个失败步骤中的关键错误召回文档（前3条非gold）：

- rank=1 `11335781` Is autoimmunity the Achilles' heel of cancer immunotherapy?（本地heading=`SCIFACT-EVIDENCE-11335781`）

  > The emergence of immuno-oncology as the first broadly successful strategy for metastatic cancer will require clinicians to integrate this new pillar of medicine with chemotherapy, radiation, and targeted small-molecule compounds. Of equal importance is gaining an understanding of the limitations and toxicities of immunotherapy. Immunotherapy was initially perceived to be a relatively less toxic approach to cancer treatment than other available therapies—and surely it is, when compared to those. However, as the use of immunotherapy becomes more common, especially as first- and second-line treat…

  本地源文件： `DOCX=prepared/docx/018-scifact-11335781.docx` `PDF=prepared/pdf/018-scifact-11335781.pdf`
- rank=2 `33499189` Full activation of the T cell receptor requires both clustering and conformational changes at CD3.（本地heading=`SCIFACT-EVIDENCE-33499189`）

  > T cell receptor (TCR-CD3) triggering involves both receptor clustering and conformational changes at the cytoplasmic tails of the CD3 subunits. The mechanism by which TCRalphabeta ligand binding confers conformational changes to CD3 is unknown. By using well-defined ligands, we showed that induction of the conformational change requires both multivalent engagement and the mobility restriction of the TCR-CD3 imposed by the plasma membrane. The conformational change is elicited by cooperative rearrangements of two TCR-CD3 complexes and does not require accompanying changes in the structure of th…

  本地源文件： `DOCX=prepared/docx/156-scifact-33499189.docx` `PDF=prepared/pdf/156-scifact-33499189.pdf`
- rank=3 `8460275` The Utilization of Extracellular Proteins as Nutrients Is Suppressed by mTORC1（本地heading=`SCIFACT-EVIDENCE-8460275`）

  > Despite being surrounded by diverse nutrients, mammalian cells preferentially metabolize glucose and free amino acids. Recently, Ras-induced macropinocytosis of extracellular proteins was shown to reduce a transformed cell's dependence on extracellular glutamine. Here, we demonstrate that protein macropinocytosis can also serve as an essential amino acid source. Lysosomal degradation of extracellular proteins can sustain cell survival and induce activation of mTORC1 but fails to elicit significant cell accumulation. Unlike its growth-promoting activity under amino-acid-replete conditions, we d…

  本地源文件： `DOCX=prepared/docx/193-scifact-8460275.docx` `PDF=prepared/pdf/193-scifact-8460275.pdf`

首个可观测失败步骤：`sparse_final_top10`。

直接证据：

- Dense gold首名次=1
- Sparse gold首名次=Top10未命中
- Hybrid-RRF gold首名次=2
- Hybrid-RRF+Rerank gold首名次=1
- 分类规则=sparse_miss_hybrid_hit
- 逐候选分数=未采集

推断：稀疏词项匹配对同义改写或词形差异不敏感；query与gold标题/片段最大词项Jaccard=0.0854。

其他可能解释：相关性标注不完整、gold摘要与claim粒度不同、分块边界或Top10截断；当前终态run不能排除这些因素（sparse_miss_hybrid_hit）。

反证实验：固定同一索引和query，额外留存Dense/Sparse候选Top100、融合逐项贡献、Rerank输入输出分数及chunk文本，再复跑该query验证首个内部失效点（sparse_miss_hybrid_hit）。

### queryId=1196

问题：The availability of safe places to study is effective at decreasing homelessness.

Gold文档：

- `25649714` Mental health problems of homeless children and families: longitudinal study.

  > OBJECTIVE To establish the mental health needs of homeless children and families before and after rehousing. DESIGN Cross sectional, longitudinal study. SETTING City of Birmingham. SUBJECTS 58 rehoused families with 103 children aged 2-16 years and 21 comparison families of low socioeconomic status in stable housing, with 54 children. MAIN OUTCOME MEASURES Children's mental health problems and level of communication; mothers' mental health problems and social support one year after rehousing. RESULTS Mental health problems remained significantly higher in rehoused mothers and their children th…

  本地源文件： `DOCX=prepared/docx/003-scifact-25649714.docx` `PDF=prepared/pdf/003-scifact-25649714.pdf`

| 变体 | Recall@10 | MRR@10 | nDCG@10 | Gold首名次 | 延迟ms | Top10文档ID（*为gold） |
|---|---:|---:|---:|---:|---:|---|
| dense | 1.000000 | 1.000000 | 1.000000 | 1 | 1913 | 25649714*, 13906581, 11718220, 26016929, 13770184, 12670680, 19675911 |
| sparse | 0.000000 | 0.000000 | 0.000000 | Top10未命中 | 1510 | 39381118, 33499189, 12789595, 791050, 8780599, 5289038 |
| hybrid_rrf | 1.000000 | 0.500000 | 0.630930 | 2 | 1810 | 1606628, 25649714*, 8780599, 13625993, 27768226, 13770184, 11718220, 5289038 |
| hybrid_rrf_rerank | 1.000000 | 1.000000 | 1.000000 | 1 | 8193 | 25649714*, 8780599, 13625993, 11718220, 13770184, 5289038, 27768226, 1606628 |

首个失败步骤中的关键错误召回文档（前3条非gold）：

- rank=1 `39381118` At the gates of death.（本地heading=`SCIFACT-EVIDENCE-39381118`）

  > Apoptosis that proceeds via the mitochondrial pathway involves mitochondrial outer membrane permeabilization (MOMP), responsible for the release of cytochrome c and other proteins of the mitochondrial intermembrane space. This essential step is controlled and mediated by proteins of the Bcl-2 family. The proapoptotic proteins Bax and Bak are required for MOMP, while the antiapoptotic Bcl-2 proteins, including Bcl-2, Bcl-xL, Mcl-1, and others, prevent MOMP. Different proapoptotic BH3-only proteins act to interfere with the function of the antiapoptotic Bcl-2 members and/or activate Bax and Bak.…

  本地源文件： `DOCX=prepared/docx/183-scifact-39381118.docx` `PDF=prepared/pdf/183-scifact-39381118.pdf`
- rank=2 `33499189` Full activation of the T cell receptor requires both clustering and conformational changes at CD3.（本地heading=`SCIFACT-EVIDENCE-33499189`）

  > T cell receptor (TCR-CD3) triggering involves both receptor clustering and conformational changes at the cytoplasmic tails of the CD3 subunits. The mechanism by which TCRalphabeta ligand binding confers conformational changes to CD3 is unknown. By using well-defined ligands, we showed that induction of the conformational change requires both multivalent engagement and the mobility restriction of the TCR-CD3 imposed by the plasma membrane. The conformational change is elicited by cooperative rearrangements of two TCR-CD3 complexes and does not require accompanying changes in the structure of th…

  本地源文件： `DOCX=prepared/docx/156-scifact-33499189.docx` `PDF=prepared/pdf/156-scifact-33499189.pdf`
- rank=3 `12789595` Computer assisted learning in undergraduate medical education.（本地heading=`SCIFACT-EVIDENCE-12789595`）

  > It is becoming “a truth universally acknowledged” that the education of undergraduate medical students will be enhanced through the use of computer assisted learning. Access to the wide range of online options illustrated in the figure must surely make learning more exciting, effective, and likely to be retained. This assumption is potentially but by no means inevitably correct. ### Box 1: Why fund computer assisted learning? Computer assisted learning is inevitable —Individual lecturers and departments are already beginning to introduce a wide range of computer based applications, sometimes i…

  本地源文件： `DOCX=prepared/docx/069-scifact-12789595.docx` `PDF=prepared/pdf/069-scifact-12789595.pdf`

首个可观测失败步骤：`sparse_final_top10`。

直接证据：

- Dense gold首名次=1
- Sparse gold首名次=Top10未命中
- Hybrid-RRF gold首名次=2
- Hybrid-RRF+Rerank gold首名次=1
- 分类规则=sparse_miss_hybrid_hit
- 逐候选分数=未采集

推断：稀疏词项匹配对同义改写或词形差异不敏感；query与gold标题/片段最大词项Jaccard=0.0615。

其他可能解释：相关性标注不完整、gold摘要与claim粒度不同、分块边界或Top10截断；当前终态run不能排除这些因素（sparse_miss_hybrid_hit）。

反证实验：固定同一索引和query，额外留存Dense/Sparse候选Top100、融合逐项贡献、Rerank输入输出分数及chunk文本，再复跑该query验证首个内部失效点（sparse_miss_hybrid_hit）。

## rerank_rescue

当前完整run中无符合该确定性规则的案例。

## rerank_harm

当前完整run中无符合该确定性规则的案例。

## dense_only_success

### queryId=1049

问题：Ribosomopathies have a low degree of cell and tissue specific pathology.

Gold文档：

- `12486491` Ribosome-Mediated Specificity in Hox mRNA Translation and Vertebrate Tissue Patterning

  > Historically, the ribosome has been viewed as a complex ribozyme with constitutive rather than regulatory capacity in mRNA translation. Here we identify mutations of the Ribosomal Protein L38 (Rpl38) gene in mice exhibiting surprising tissue-specific patterning defects, including pronounced homeotic transformations of the axial skeleton. In Rpl38 mutant embryos, global protein synthesis is unchanged; however the translation of a select subset of Homeobox mRNAs is perturbed. Our data reveal that RPL38 facilitates 80S complex formation on these mRNAs as a regulatory component of the ribosome to…

  本地源文件： `DOCX=prepared/docx/174-scifact-12486491.docx` `PDF=prepared/pdf/174-scifact-12486491.pdf`

| 变体 | Recall@10 | MRR@10 | nDCG@10 | Gold首名次 | 延迟ms | Top10文档ID（*为gold） |
|---|---:|---:|---:|---:|---:|---|
| dense | 1.000000 | 1.000000 | 1.000000 | 1 | 1936 | 12486491*, 13905670, 3441524, 5476778, 1049501, 3475317 |
| sparse | 0.000000 | 0.000000 | 0.000000 | Top10未命中 | 1502 | 45638119, 24142891, 7521113, 5483793, 13230773, 23460562, 4350400 |
| hybrid_rrf | 1.000000 | 1.000000 | 1.000000 | 1 | 2026 | 12486491*, 7521113, 23460562, 13230773, 1215116 |
| hybrid_rrf_rerank | 1.000000 | 1.000000 | 1.000000 | 1 | 4942 | 12486491*, 23460562, 7521113, 1215116, 13230773 |

首个失败步骤中的关键错误召回文档（前3条非gold）：

- rank=1 `45638119` ALDH1 is a marker of normal and malignant human mammary stem cells and a predictor of poor clinical outcome.（本地heading=`SCIFACT-EVIDENCE-45638119`）

  > Application of stem cell biology to breast cancer research has been limited by the lack of simple methods for identification and isolation of normal and malignant stem cells. Utilizing in vitro and in vivo experimental systems, we show that normal and cancer human mammary epithelial cells with increased aldehyde dehydrogenase activity (ALDH) have stem/progenitor properties. These cells contain the subpopulation of normal breast epithelium with the broadest lineage differentiation potential and greatest growth capacity in a xenotransplant model. In breast carcinomas, high ALDH activity identifi…

  本地源文件： `DOCX=prepared/docx/050-scifact-45638119.docx` `PDF=prepared/pdf/050-scifact-45638119.pdf`
- rank=2 `24142891` Tmem27: a cleaved and shed plasma membrane protein that stimulates pancreatic beta cell proliferation.（本地heading=`SCIFACT-EVIDENCE-24142891`）

  > The signals and molecular mechanisms that regulate the replication of terminally differentiated beta cells are unknown. Here, we report the identification and characterization of transmembrane protein 27 (Tmem27, collectrin) in pancreatic beta cells. Expression of Tmem27 is reduced in Tcf1(-/-) mice and is increased in islets of mouse models with hypertrophy of the endocrine pancreas. Tmem27 forms dimers and its extracellular domain is glycosylated, cleaved and shed from the plasma membrane of beta cells. This cleavage process is beta cell specific and does not occur in other cell types. Overe…

  本地源文件： `DOCX=prepared/docx/006-scifact-24142891.docx` `PDF=prepared/pdf/006-scifact-24142891.pdf`
- rank=3 `7521113` Fate mapping reveals origins and dynamics of monocytes and tissue macrophages under homeostasis.（本地heading=`SCIFACT-EVIDENCE-7521113`）

  > Mononuclear phagocytes, including monocytes, macrophages, and dendritic cells, contribute to tissue integrity as well as to innate and adaptive immune defense. Emerging evidence for labor division indicates that manipulation of these cells could bear therapeutic potential. However, specific ontogenies of individual populations and the overall functional organization of this cellular network are not well defined. Here we report a fate-mapping study of the murine monocyte and macrophage compartment taking advantage of constitutive and conditional CX(3)CR1 promoter-driven Cre recombinase expressi…

  本地源文件： `DOCX=prepared/docx/195-scifact-7521113.docx` `PDF=prepared/pdf/195-scifact-7521113.pdf`

首个可观测失败步骤：`sparse_final_top10`。

直接证据：

- Dense gold首名次=1
- Sparse gold首名次=Top10未命中
- Hybrid-RRF gold首名次=1
- Hybrid-RRF+Rerank gold首名次=1
- 分类规则=dense_only_success
- 逐候选分数=未采集

推断：稀疏词项匹配对同义改写或词形差异不敏感；query与gold标题/片段最大词项Jaccard=0.0533。

其他可能解释：相关性标注不完整、gold摘要与claim粒度不同、分块边界或Top10截断；当前终态run不能排除这些因素（dense_only_success）。

反证实验：固定同一索引和query，额外留存Dense/Sparse候选Top100、融合逐项贡献、Rerank输入输出分数及chunk文本，再复跑该query验证首个内部失效点（dense_only_success）。

### queryId=1194

问题：The arm density of TatAd complexes is due to structural rearrangements within Class1 TatAd complexes such as the 'charge zipper mechanism'.

Gold文档：

- `11419230` Folding and Self-Assembly of the TatA Translocation Pore Based on a Charge Zipper Mechanism

  > We propose a concept for the folding and self-assembly of the pore-forming TatA complex from the Twin-arginine translocase and of other membrane proteins based on electrostatic "charge zippers. " Each subunit of TatA consists of a transmembrane segment, an amphiphilic helix (APH), and a C-terminal densely charged region (DCR). The sequence of charges in the DCR is complementary to the charge pattern on the APH, suggesting that the protein can be "zipped up" by a ladder of seven salt bridges. The length of the resulting hairpin matches the lipid bilayer thickness, hence a transmembrane pore cou…

  本地源文件： `DOCX=prepared/docx/141-scifact-11419230.docx` `PDF=prepared/pdf/141-scifact-11419230.pdf`

| 变体 | Recall@10 | MRR@10 | nDCG@10 | Gold首名次 | 延迟ms | Top10文档ID（*为gold） |
|---|---:|---:|---:|---:|---:|---|
| dense | 1.000000 | 1.000000 | 1.000000 | 1 | 2151 | 11419230*, 33499189, 15319019, 11603066, 17628888 |
| sparse | 0.000000 | 0.000000 | 0.000000 | Top10未命中 | 1859 | 11335781, 33499189, 8460275, 28937856, 5476778, 5289038, 10991183, 20381484, 24221369 |
| hybrid_rrf | 1.000000 | 0.500000 | 0.630930 | 2 | 1890 | 33499189, 11419230*, 17628888, 10991183, 11603066 |
| hybrid_rrf_rerank | 1.000000 | 1.000000 | 1.000000 | 1 | 6353 | 11419230*, 10991183, 33499189, 17628888, 11603066 |

首个失败步骤中的关键错误召回文档（前3条非gold）：

- rank=1 `11335781` Is autoimmunity the Achilles' heel of cancer immunotherapy?（本地heading=`SCIFACT-EVIDENCE-11335781`）

  > The emergence of immuno-oncology as the first broadly successful strategy for metastatic cancer will require clinicians to integrate this new pillar of medicine with chemotherapy, radiation, and targeted small-molecule compounds. Of equal importance is gaining an understanding of the limitations and toxicities of immunotherapy. Immunotherapy was initially perceived to be a relatively less toxic approach to cancer treatment than other available therapies—and surely it is, when compared to those. However, as the use of immunotherapy becomes more common, especially as first- and second-line treat…

  本地源文件： `DOCX=prepared/docx/018-scifact-11335781.docx` `PDF=prepared/pdf/018-scifact-11335781.pdf`
- rank=2 `33499189` Full activation of the T cell receptor requires both clustering and conformational changes at CD3.（本地heading=`SCIFACT-EVIDENCE-33499189`）

  > T cell receptor (TCR-CD3) triggering involves both receptor clustering and conformational changes at the cytoplasmic tails of the CD3 subunits. The mechanism by which TCRalphabeta ligand binding confers conformational changes to CD3 is unknown. By using well-defined ligands, we showed that induction of the conformational change requires both multivalent engagement and the mobility restriction of the TCR-CD3 imposed by the plasma membrane. The conformational change is elicited by cooperative rearrangements of two TCR-CD3 complexes and does not require accompanying changes in the structure of th…

  本地源文件： `DOCX=prepared/docx/156-scifact-33499189.docx` `PDF=prepared/pdf/156-scifact-33499189.pdf`
- rank=3 `8460275` The Utilization of Extracellular Proteins as Nutrients Is Suppressed by mTORC1（本地heading=`SCIFACT-EVIDENCE-8460275`）

  > Despite being surrounded by diverse nutrients, mammalian cells preferentially metabolize glucose and free amino acids. Recently, Ras-induced macropinocytosis of extracellular proteins was shown to reduce a transformed cell's dependence on extracellular glutamine. Here, we demonstrate that protein macropinocytosis can also serve as an essential amino acid source. Lysosomal degradation of extracellular proteins can sustain cell survival and induce activation of mTORC1 but fails to elicit significant cell accumulation. Unlike its growth-promoting activity under amino-acid-replete conditions, we d…

  本地源文件： `DOCX=prepared/docx/193-scifact-8460275.docx` `PDF=prepared/pdf/193-scifact-8460275.pdf`

首个可观测失败步骤：`sparse_final_top10`。

直接证据：

- Dense gold首名次=1
- Sparse gold首名次=Top10未命中
- Hybrid-RRF gold首名次=2
- Hybrid-RRF+Rerank gold首名次=1
- 分类规则=dense_only_success
- 逐候选分数=未采集

推断：稀疏词项匹配对同义改写或词形差异不敏感；query与gold标题/片段最大词项Jaccard=0.0854。

其他可能解释：相关性标注不完整、gold摘要与claim粒度不同、分块边界或Top10截断；当前终态run不能排除这些因素（dense_only_success）。

反证实验：固定同一索引和query，额外留存Dense/Sparse候选Top100、融合逐项贡献、Rerank输入输出分数及chunk文本，再复跑该query验证首个内部失效点（dense_only_success）。

### queryId=1196

问题：The availability of safe places to study is effective at decreasing homelessness.

Gold文档：

- `25649714` Mental health problems of homeless children and families: longitudinal study.

  > OBJECTIVE To establish the mental health needs of homeless children and families before and after rehousing. DESIGN Cross sectional, longitudinal study. SETTING City of Birmingham. SUBJECTS 58 rehoused families with 103 children aged 2-16 years and 21 comparison families of low socioeconomic status in stable housing, with 54 children. MAIN OUTCOME MEASURES Children's mental health problems and level of communication; mothers' mental health problems and social support one year after rehousing. RESULTS Mental health problems remained significantly higher in rehoused mothers and their children th…

  本地源文件： `DOCX=prepared/docx/003-scifact-25649714.docx` `PDF=prepared/pdf/003-scifact-25649714.pdf`

| 变体 | Recall@10 | MRR@10 | nDCG@10 | Gold首名次 | 延迟ms | Top10文档ID（*为gold） |
|---|---:|---:|---:|---:|---:|---|
| dense | 1.000000 | 1.000000 | 1.000000 | 1 | 1913 | 25649714*, 13906581, 11718220, 26016929, 13770184, 12670680, 19675911 |
| sparse | 0.000000 | 0.000000 | 0.000000 | Top10未命中 | 1510 | 39381118, 33499189, 12789595, 791050, 8780599, 5289038 |
| hybrid_rrf | 1.000000 | 0.500000 | 0.630930 | 2 | 1810 | 1606628, 25649714*, 8780599, 13625993, 27768226, 13770184, 11718220, 5289038 |
| hybrid_rrf_rerank | 1.000000 | 1.000000 | 1.000000 | 1 | 8193 | 25649714*, 8780599, 13625993, 11718220, 13770184, 5289038, 27768226, 1606628 |

首个失败步骤中的关键错误召回文档（前3条非gold）：

- rank=1 `39381118` At the gates of death.（本地heading=`SCIFACT-EVIDENCE-39381118`）

  > Apoptosis that proceeds via the mitochondrial pathway involves mitochondrial outer membrane permeabilization (MOMP), responsible for the release of cytochrome c and other proteins of the mitochondrial intermembrane space. This essential step is controlled and mediated by proteins of the Bcl-2 family. The proapoptotic proteins Bax and Bak are required for MOMP, while the antiapoptotic Bcl-2 proteins, including Bcl-2, Bcl-xL, Mcl-1, and others, prevent MOMP. Different proapoptotic BH3-only proteins act to interfere with the function of the antiapoptotic Bcl-2 members and/or activate Bax and Bak.…

  本地源文件： `DOCX=prepared/docx/183-scifact-39381118.docx` `PDF=prepared/pdf/183-scifact-39381118.pdf`
- rank=2 `33499189` Full activation of the T cell receptor requires both clustering and conformational changes at CD3.（本地heading=`SCIFACT-EVIDENCE-33499189`）

  > T cell receptor (TCR-CD3) triggering involves both receptor clustering and conformational changes at the cytoplasmic tails of the CD3 subunits. The mechanism by which TCRalphabeta ligand binding confers conformational changes to CD3 is unknown. By using well-defined ligands, we showed that induction of the conformational change requires both multivalent engagement and the mobility restriction of the TCR-CD3 imposed by the plasma membrane. The conformational change is elicited by cooperative rearrangements of two TCR-CD3 complexes and does not require accompanying changes in the structure of th…

  本地源文件： `DOCX=prepared/docx/156-scifact-33499189.docx` `PDF=prepared/pdf/156-scifact-33499189.pdf`
- rank=3 `12789595` Computer assisted learning in undergraduate medical education.（本地heading=`SCIFACT-EVIDENCE-12789595`）

  > It is becoming “a truth universally acknowledged” that the education of undergraduate medical students will be enhanced through the use of computer assisted learning. Access to the wide range of online options illustrated in the figure must surely make learning more exciting, effective, and likely to be retained. This assumption is potentially but by no means inevitably correct. ### Box 1: Why fund computer assisted learning? Computer assisted learning is inevitable —Individual lecturers and departments are already beginning to introduce a wide range of computer based applications, sometimes i…

  本地源文件： `DOCX=prepared/docx/069-scifact-12789595.docx` `PDF=prepared/pdf/069-scifact-12789595.pdf`

首个可观测失败步骤：`sparse_final_top10`。

直接证据：

- Dense gold首名次=1
- Sparse gold首名次=Top10未命中
- Hybrid-RRF gold首名次=2
- Hybrid-RRF+Rerank gold首名次=1
- 分类规则=dense_only_success
- 逐候选分数=未采集

推断：稀疏词项匹配对同义改写或词形差异不敏感；query与gold标题/片段最大词项Jaccard=0.0615。

其他可能解释：相关性标注不完整、gold摘要与claim粒度不同、分块边界或Top10截断；当前终态run不能排除这些因素（dense_only_success）。

反证实验：固定同一索引和query，额外留存Dense/Sparse候选Top100、融合逐项贡献、Rerank输入输出分数及chunk文本，再复跑该query验证首个内部失效点（dense_only_success）。

### queryId=1200

问题：The binding orientation of the ML-SA1 activator at hTRPML2 is different from the binding orientation of the ML-SA1 activator at hTRPML1.

Gold文档：

- `3441524` Human TRPML1 channel structures in open and closed conformations

  > Transient receptor potential mucolipin 1 (TRPML1) is a Ca2+-releasing cation channel that mediates the calcium signalling and homeostasis of lysosomes. Mutations in TRPML1 lead to mucolipidosis type IV, a severe lysosomal storage disorder. Here we report two electron cryo-microscopy structures of full-length human TRPML1: a 3.72-Å apo structure at pH 7.0 in the closed state, and a 3.49-Å agonist-bound structure at pH 6.0 in an open state. Several aromatic and hydrophobic residues in pore helix 1, helices S5 and S6, and helix S6 of a neighbouring subunit, form a hydrophobic cavity to house the…

  本地源文件： `DOCX=prepared/docx/194-scifact-3441524.docx` `PDF=prepared/pdf/194-scifact-3441524.pdf`

| 变体 | Recall@10 | MRR@10 | nDCG@10 | Gold首名次 | 延迟ms | Top10文档ID（*为gold） |
|---|---:|---:|---:|---:|---:|---|
| dense | 1.000000 | 1.000000 | 1.000000 | 1 | 1898 | 3441524*, 33499189, 11603066, 12486491 |
| sparse | 0.000000 | 0.000000 | 0.000000 | Top10未命中 | 1507 | 39381118, 33499189, 4387784, 12956194, 17628888, 12631697 |
| hybrid_rrf | 1.000000 | 0.500000 | 0.630930 | 2 | 1914 | 33499189, 3441524*, 12956194, 306006, 17628888, 12631697, 11603066 |
| hybrid_rrf_rerank | 1.000000 | 1.000000 | 1.000000 | 1 | 7925 | 3441524*, 11603066, 12956194, 12631697, 33499189, 17628888, 306006 |

首个失败步骤中的关键错误召回文档（前3条非gold）：

- rank=1 `39381118` At the gates of death.（本地heading=`SCIFACT-EVIDENCE-39381118`）

  > Apoptosis that proceeds via the mitochondrial pathway involves mitochondrial outer membrane permeabilization (MOMP), responsible for the release of cytochrome c and other proteins of the mitochondrial intermembrane space. This essential step is controlled and mediated by proteins of the Bcl-2 family. The proapoptotic proteins Bax and Bak are required for MOMP, while the antiapoptotic Bcl-2 proteins, including Bcl-2, Bcl-xL, Mcl-1, and others, prevent MOMP. Different proapoptotic BH3-only proteins act to interfere with the function of the antiapoptotic Bcl-2 members and/or activate Bax and Bak.…

  本地源文件： `DOCX=prepared/docx/183-scifact-39381118.docx` `PDF=prepared/pdf/183-scifact-39381118.pdf`
- rank=2 `33499189` Full activation of the T cell receptor requires both clustering and conformational changes at CD3.（本地heading=`SCIFACT-EVIDENCE-33499189`）

  > T cell receptor (TCR-CD3) triggering involves both receptor clustering and conformational changes at the cytoplasmic tails of the CD3 subunits. The mechanism by which TCRalphabeta ligand binding confers conformational changes to CD3 is unknown. By using well-defined ligands, we showed that induction of the conformational change requires both multivalent engagement and the mobility restriction of the TCR-CD3 imposed by the plasma membrane. The conformational change is elicited by cooperative rearrangements of two TCR-CD3 complexes and does not require accompanying changes in the structure of th…

  本地源文件： `DOCX=prepared/docx/156-scifact-33499189.docx` `PDF=prepared/pdf/156-scifact-33499189.pdf`
- rank=3 `4387784` Structure of the proton-gated urea channel from the gastric pathogen Helicobacter pylori（本地heading=`SCIFACT-EVIDENCE-4387784`）

  > Half the world's population is chronically infected with Helicobacter pylori, causing gastritis, gastric ulcers and an increased incidence of gastric adenocarcinoma. Its proton-gated inner-membrane urea channel, HpUreI, is essential for survival in the acidic environment of the stomach. The channel is closed at neutral pH and opens at acidic pH to allow the rapid access of urea to cytoplasmic urease. Urease produces NH(3) and CO(2), neutralizing entering protons and thus buffering the periplasm to a pH of roughly 6.1 even in gastric juice at a pH below 2.0. Here we report the structure of HpUr…

  本地源文件： `DOCX=prepared/docx/020-scifact-4387784.docx` `PDF=prepared/pdf/020-scifact-4387784.pdf`

首个可观测失败步骤：`sparse_final_top10`。

直接证据：

- Dense gold首名次=1
- Sparse gold首名次=Top10未命中
- Hybrid-RRF gold首名次=2
- Hybrid-RRF+Rerank gold首名次=1
- 分类规则=dense_only_success
- 逐候选分数=未采集

推断：稀疏词项匹配对同义改写或词形差异不敏感；query与gold标题/片段最大词项Jaccard=0.0519。

其他可能解释：相关性标注不完整、gold摘要与claim粒度不同、分块边界或Top10截断；当前终态run不能排除这些因素（dense_only_success）。

反证实验：固定同一索引和query，额外留存Dense/Sparse候选Top100、融合逐项贡献、Rerank输入输出分数及chunk文本，再复跑该query验证首个内部失效点（dense_only_success）。

### queryId=1207

问题：The composition of myosin-II isoform switches from the polarizable B isoform to the more homogenous A isoform during hematopoietic differentiation.

Gold文档：

- `18909530` Contractile forces sustain and polarize hematopoiesis from stem and progenitor cells.

  > Self-renewal and differentiation of stem cells depend on asymmetric division and polarized motility processes that in other cell types are modulated by nonmuscle myosin-II (MII) forces and matrix mechanics. Here, mass spectrometry-calibrated intracellular flow cytometry of human hematopoiesis reveals MIIB to be a major isoform that is strongly polarized in hematopoietic stem cells and progenitors (HSC/Ps) and thereby downregulated in differentiated cells via asymmetric division. MIIA is constitutive and activated by dephosphorylation during cytokine-triggered differentiation of cells grown on…

  本地源文件： `DOCX=prepared/docx/177-scifact-18909530.docx` `PDF=prepared/pdf/177-scifact-18909530.pdf`

| 变体 | Recall@10 | MRR@10 | nDCG@10 | Gold首名次 | 延迟ms | Top10文档ID（*为gold） |
|---|---:|---:|---:|---:|---:|---|
| dense | 1.000000 | 1.000000 | 1.000000 | 1 | 1747 | 18909530*, 11603066, 18174210 |
| sparse | 0.000000 | 0.000000 | 0.000000 | Top10未命中 | 1515 | 6173523, 306006, 12956194, 4942718, 11603066, 4387784, 1606628, 17628888, 1148122 |
| hybrid_rrf | 1.000000 | 1.000000 | 1.000000 | 1 | 2026 | 18909530*, 11603066, 4942718, 1148122 |
| hybrid_rrf_rerank | 1.000000 | 1.000000 | 1.000000 | 1 | 6653 | 18909530*, 11603066, 4942718, 1148122 |

首个失败步骤中的关键错误召回文档（前3条非gold）：

- rank=1 `6173523` A culture-independent sequence-based metagenomics approach to the investigation of an outbreak of Shiga-toxigenic Escherichia coli O104:H4.（本地heading=`SCIFACT-EVIDENCE-6173523`）

  > IMPORTANCE Identification of the bacterium responsible for an outbreak can aid in disease management. However, traditional culture-based diagnosis can be difficult, particularly if no specific diagnostic test is available for an outbreak strain. OBJECTIVE To explore the potential of metagenomics, which is the direct sequencing of DNA extracted from microbiologically complex samples, as an open-ended clinical discovery platform capable of identifying and characterizing bacterial strains from an outbreak without laboratory culture. DESIGN, SETTING, AND PATIENTS In a retrospective investigation,…

  本地源文件： `DOCX=prepared/docx/087-scifact-6173523.docx` `PDF=prepared/pdf/087-scifact-6173523.pdf`
- rank=2 `306006` The stimulatory potency of T cell antigens is influenced by the formation of the immunological synapse.（本地heading=`SCIFACT-EVIDENCE-306006`）

  > T cell activation is predicated on the interaction between the T cell receptor and peptide-major histocompatibility (pMHC) ligands. The factors that determine the stimulatory potency of a pMHC molecule remain unclear. We describe results showing that a peptide exhibiting many hallmarks of a weak agonist stimulates T cells to proliferate more than the wild-type agonist ligand. An in silico approach suggested that the inability to form the central supramolecular activation cluster (cSMAC) could underlie the increased proliferation. This conclusion was supported by experiments that showed that en…

  本地源文件： `DOCX=prepared/docx/077-scifact-306006.docx` `PDF=prepared/pdf/077-scifact-306006.pdf`
- rank=3 `12956194` The Extracellular Surface of the GLP-1 Receptor Is a Molecular Trigger for Biased Agonism（本地heading=`SCIFACT-EVIDENCE-12956194`）

  > Ligand-directed signal bias offers opportunities for sculpting molecular events, with the promise of better, safer therapeutics. Critical to the exploitation of signal bias is an understanding of the molecular events coupling ligand binding to intracellular signaling. Activation of class B G protein-coupled receptors is driven by interaction of the peptide N terminus with the receptor core. To understand how this drives signaling, we have used advanced analytical methods that enable separation of effects on pathway-specific signaling from those that modify agonist affinity and mapped the funct…

  本地源文件： `DOCX=prepared/docx/010-scifact-12956194.docx` `PDF=prepared/pdf/010-scifact-12956194.pdf`

首个可观测失败步骤：`sparse_final_top10`。

直接证据：

- Dense gold首名次=1
- Sparse gold首名次=Top10未命中
- Hybrid-RRF gold首名次=1
- Hybrid-RRF+Rerank gold首名次=1
- 分类规则=dense_only_success
- 逐候选分数=未采集

推断：稀疏词项匹配对同义改写或词形差异不敏感；query与gold标题/片段最大词项Jaccard=0.1233。

其他可能解释：相关性标注不完整、gold摘要与claim粒度不同、分块边界或Top10截断；当前终态run不能排除这些因素（dense_only_success）。

反证实验：固定同一索引和query，额外留存Dense/Sparse候选Top100、融合逐项贡献、Rerank输入输出分数及chunk文本，再复跑该query验证首个内部失效点（dense_only_success）。

## sparse_only_success

### queryId=324

问题：Deleting Raptor reduces G-CSF levels.

Gold文档：

- `2014909` Oncogenic mTOR signaling recruits myeloid-derived suppressor cells to promote tumor initiation

  > Myeloid-derived suppressor cells (MDSCs) play critical roles in primary and metastatic cancer progression. MDSC regulation is widely variable even among patients harbouring the same type of malignancy, and the mechanisms governing such heterogeneity are largely unknown. Here, integrating human tumour genomics and syngeneic mammary tumour models, we demonstrate that mTOR signalling in cancer cells dictates a mammary tumour's ability to stimulate MDSC accumulation through regulating G-CSF. Inhibiting this pathway or its activators (for example, FGFR) impairs tumour progression, which is partiall…

  本地源文件： `DOCX=prepared/docx/029-scifact-2014909.docx` `PDF=prepared/pdf/029-scifact-2014909.pdf`

| 变体 | Recall@10 | MRR@10 | nDCG@10 | Gold首名次 | 延迟ms | Top10文档ID（*为gold） |
|---|---:|---:|---:|---:|---:|---|
| dense | 0.000000 | 0.000000 | 0.000000 | Top10未命中 | 1755 | 3553087, 12827098, 26851674, 33370, 16472469, 14767844, 2988714, 8460275 |
| sparse | 1.000000 | 0.500000 | 0.630930 | 2 | 1467 | 31272411, 2014909*, 16256507, 16472469, 9745001, 1834762, 39381118, 32159283, 12827098 |
| hybrid_rrf | 1.000000 | 0.333333 | 0.500000 | 3 | 1660 | 16472469, 12827098, 2014909*, 14767844, 26851674, 33370, 28937856, 13905670, 970012, 17755060 |
| hybrid_rrf_rerank | 1.000000 | 1.000000 | 1.000000 | 1 | 5926 | 2014909*, 12827098, 17755060, 970012, 13905670, 33370, 28937856, 16472469, 26851674, 14767844 |

首个失败步骤中的关键错误召回文档（前3条非gold）：

- rank=1 `3553087` Mitochondrial iron chelation ameliorates cigarette-smoke induced bronchitis and emphysema in mice（本地heading=`SCIFACT-EVIDENCE-3553087`）

  > Chronic obstructive pulmonary disease (COPD) is linked to both cigarette smoking and genetic determinants. We have previously identified iron-responsive element-binding protein 2 (IRP2) as an important COPD susceptibility gene and have shown that IRP2 protein is increased in the lungs of individuals with COPD. Here we demonstrate that mice deficient in Irp2 were protected from cigarette smoke (CS)-induced experimental COPD. By integrating RNA immunoprecipitation followed by sequencing (RIP-seq), RNA sequencing (RNA-seq), and gene expression and functional enrichment clustering analysis, we ide…

  本地源文件： `DOCX=prepared/docx/099-scifact-3553087.docx` `PDF=prepared/pdf/099-scifact-3553087.pdf`
- rank=2 `12827098` Tissue-resident macrophages self-maintain locally throughout adult life with minimal contribution from circulating monocytes.（本地heading=`SCIFACT-EVIDENCE-12827098`）

  > Despite accumulating evidence suggesting local self-maintenance of tissue macrophages in the steady state, the dogma remains that tissue macrophages derive from monocytes. Using parabiosis and fate-mapping approaches, we confirmed that monocytes do not show significant contribution to tissue macrophages in the steady state. Similarly, we found that after depletion of lung macrophages, the majority of repopulation occurred by stochastic cellular proliferation in situ in a macrophage colony-stimulating factor (M-Csf)- and granulocyte macrophage (GM)-CSF-dependent manner but independently of inte…

  本地源文件： `DOCX=prepared/docx/046-scifact-12827098.docx` `PDF=prepared/pdf/046-scifact-12827098.pdf`
- rank=3 `26851674` Dissection of signaling cascades through gp130 in vivo: reciprocal roles for STAT3- and SHP2-mediated signals in immune responses.（本地heading=`SCIFACT-EVIDENCE-26851674`）

  > We generated a series of knockin mouse lines, in which the cytokine receptor gp130-dependent STAT3 and/or SHP2 signals were disrupted, by replacing the mouse gp130 gene with human gp130 mutant cDNAs. The SHP2 signal-deficient mice (gp130F759/F759 were born normal but displayed splenomegaly and lymphadenopathy and an enhanced acute phase reaction. In contrast, the STAT3 signal-deficient mice (gp130FXQ/FXXQ) died perinatally, like the gp130-deficient mice (gp130D/D). The gp130F759/F759 mice showed prolonged gp130-induced STAT3 activation, indicating a negative regulatory role for SHP2. Th1-type…

  本地源文件： `DOCX=prepared/docx/098-scifact-26851674.docx` `PDF=prepared/pdf/098-scifact-26851674.pdf`

首个可观测失败步骤：`dense_final_top10`。

直接证据：

- Dense gold首名次=Top10未命中
- Sparse gold首名次=2
- Hybrid-RRF gold首名次=3
- Hybrid-RRF+Rerank gold首名次=1
- 分类规则=sparse_only_success
- 逐候选分数=未采集

推断：Dense语义向量把主题相近干扰文档排在gold之前，而精确术语帮助Sparse命中；最大词项Jaccard=0.0128。

其他可能解释：相关性标注不完整、gold摘要与claim粒度不同、分块边界或Top10截断；当前终态run不能排除这些因素（sparse_only_success）。

反证实验：固定同一索引和query，额外留存Dense/Sparse候选Top100、融合逐项贡献、Rerank输入输出分数及chunk文本，再复跑该query验证首个内部失效点（sparse_only_success）。

### queryId=1363

问题：Venules have a thinner or absent smooth layer compared to arterioles.

Gold文档：

- `8290953` Scaffold-based three-dimensional human fibroblast culture provides a structural matrix that supports angiogenesis in infarcted heart tissue.

  > BACKGROUND We have developed techniques to implant angiogenic patches onto the epicardium over regions of infarcted cardiac tissue to stimulate revascularization of the damaged tissue. These experiments used a scaffold-based 3D human dermal fibroblast culture (3DFC) as an epicardial patch. The 3DFC contains viable cells that secrete angiogenic growth factors and has previously been shown to stimulate angiogenic activity. The hypothesis tested was that a viable 3DFC cardiac patch would stimulate an angiogenic response within an area of infarcted cardiac tissue. METHODS AND RESULTS A coronary oc…

  本地源文件： `DOCX=prepared/docx/030-scifact-8290953.docx` `PDF=prepared/pdf/030-scifact-8290953.pdf`

| 变体 | Recall@10 | MRR@10 | nDCG@10 | Gold首名次 | 延迟ms | Top10文档ID（*为gold） |
|---|---:|---:|---:|---:|---:|---|
| dense | 0.000000 | 0.000000 | 0.000000 | Top10未命中 | 1755 | 17741440, 16760369, 12991445, 4423559 |
| sparse | 1.000000 | 0.333333 | 0.500000 | 3 | 1617 | 13619127, 17077004, 8290953*, 32159283, 27768226, 23649163 |
| hybrid_rrf | 0.000000 | 0.000000 | 0.000000 | Top10未命中 | 1789 | 16760369, 16495649, 13619127, 23649163, 11718220, 4388470, 13625993, 3067015 |
| hybrid_rrf_rerank | 0.000000 | 0.000000 | 0.000000 | Top10未命中 | 7307 | 11718220, 16495649, 16760369, 23649163, 13625993, 3067015, 13619127, 4388470 |

首个失败步骤中的关键错误召回文档（前3条非gold）：

- rank=1 `17741440` Netting neutrophils in autoimmune small-vessel vasculitis（本地heading=`SCIFACT-EVIDENCE-17741440`）

  > Small-vessel vasculitis (SVV) is a chronic autoinflammatory condition linked to antineutrophil cytoplasm autoantibodies (ANCAs). Here we show that chromatin fibers, so-called neutrophil extracellular traps (NETs), are released by ANCA-stimulated neutrophils and contain the targeted autoantigens proteinase-3 (PR3) and myeloperoxidase (MPO). Deposition of NETs in inflamed kidneys and circulating MPO-DNA complexes suggest that NET formation triggers vasculitis and promotes the autoimmune response against neutrophil components in individuals with SVV.

  本地源文件： `DOCX=prepared/docx/124-scifact-17741440.docx` `PDF=prepared/pdf/124-scifact-17741440.pdf`
- rank=2 `16760369` Comparative determinants of 4-year cardiovascular event rates in stable outpatients at risk of or with atherothrombosis.（本地heading=`SCIFACT-EVIDENCE-16760369`）

  > CONTEXT Clinicians and trialists have difficulty with identifying which patients are highest risk for cardiovascular events. Prior ischemic events, polyvascular disease, and diabetes mellitus have all been identified as predictors of ischemic events, but their comparative contributions to future risk remain unclear. OBJECTIVE To categorize the risk of cardiovascular events in stable outpatients with various initial manifestations of atherothrombosis using simple clinical descriptors. DESIGN, SETTING, AND PATIENTS Outpatients with coronary artery disease, cerebrovascular disease, or peripheral…

  本地源文件： `DOCX=prepared/docx/179-scifact-16760369.docx` `PDF=prepared/pdf/179-scifact-16760369.pdf`
- rank=3 `12991445` Influence of smoking and plasma factors on patency of femoropopliteal vein grafts.（本地heading=`SCIFACT-EVIDENCE-12991445`）

  > OBJECTIVE To determine the effects of smoking, plasma lipids, lipoproteins, apolipoproteins, and fibrinogen on the patency of saphenous vein femoropopliteal bypass grafts at one year. DESIGN Prospective study of patients with saphenous vein femoropopliteal bypass grafts entered into a multicentre trial. SETTING Surgical wards, outpatient clinics, and home visits coordinated by two tertiary referral centres in London and Birmingham. PATIENTS 157 Patients (mean age 66.6 (SD 8.2) years), 113 with patent grafts and 44 with occluded grafts one year after bypass. MAIN OUTCOME MEASURE Cumulative perc…

  本地源文件： `DOCX=prepared/docx/014-scifact-12991445.docx` `PDF=prepared/pdf/014-scifact-12991445.pdf`

首个可观测失败步骤：`dense_final_top10`。

直接证据：

- Dense gold首名次=Top10未命中
- Sparse gold首名次=3
- Hybrid-RRF gold首名次=Top10未命中
- Hybrid-RRF+Rerank gold首名次=Top10未命中
- 分类规则=sparse_only_success
- 逐候选分数=未采集

推断：Dense语义向量把主题相近干扰文档排在gold之前，而精确术语帮助Sparse命中；最大词项Jaccard=0.0260。

其他可能解释：相关性标注不完整、gold摘要与claim粒度不同、分块边界或Top10截断；当前终态run不能排除这些因素（sparse_only_success）。

反证实验：固定同一索引和query，额外留存Dense/Sparse候选Top100、融合逐项贡献、Rerank输入输出分数及chunk文本，再复跑该query验证首个内部失效点（sparse_only_success）。

## persistent_miss

### queryId=1191

问题：The amount of publicly available DNA data doubles every 10 years.

Gold文档：

- `30655442` The EMBL nucleotide sequence database.

  > The EMBL Nucleotide Sequence Database (http://www.ebi.ac.uk/embl. html ) constitutes Europe's primary nucleotide sequence resource. DNA and RNA sequences are directly submitted from researchers and genome sequencing groups and collected from the scientific literature and patent applications (Fig. 1). In collaboration with DDBJ and GenBank the database is produced, maintained and distributed at the European Bioinformatics Institute. Database releases are produced quarterly and are distributed on CD-ROM. EBI's network services allow access to the most up-to-date data collection via Internet and…

  本地源文件： `DOCX=prepared/docx/041-scifact-30655442.docx` `PDF=prepared/pdf/041-scifact-30655442.pdf`

| 变体 | Recall@10 | MRR@10 | nDCG@10 | Gold首名次 | 延迟ms | Top10文档ID（*为gold） |
|---|---:|---:|---:|---:|---:|---|
| dense | 0.000000 | 0.000000 | 0.000000 | Top10未命中 | 1818 | 10874408, 13519661, 14079881, 16472469, 44172171, 13770184, 27768226 |
| sparse | 0.000000 | 0.000000 | 0.000000 | Top10未命中 | 1577 | 39381118, 17628888, 791050, 8780599, 44172171, 25742130 |
| hybrid_rrf | 0.000000 | 0.000000 | 0.000000 | Top10未命中 | 2462 | 44172171, 10874408, 25742130, 13519661, 791050, 6173523, 9650982, 22038539 |
| hybrid_rrf_rerank | 0.000000 | 0.000000 | 0.000000 | Top10未命中 | 6204 | 10874408, 6173523, 44172171, 22038539, 9650982, 791050, 25742130, 13519661 |

首个失败步骤中的关键错误召回文档（前3条非gold）：

- rank=1 `10874408` Mapping Meiotic Single-Strand DNA Reveals a New Landscape of DNA Double-Strand Breaks in Saccharomyces cerevisiae（本地heading=`SCIFACT-EVIDENCE-10874408`）

  > DNA double-strand breaks (DSBs), which are formed by the Spo11 protein, initiate meiotic recombination. Previous DSB-mapping studies have used rad50S or sae2Δ mutants, which are defective in break processing, to accumulate Spo11-linked DSBs, and report large (≥ 50 kb) “DSB-hot” regions that are separated by “DSB-cold” domains of similar size. Substantial recombination occurs in some DSB-cold regions, suggesting that DSB patterns are not normal in rad50S or sae2Δ mutants. We therefore developed a novel method to map genome-wide, single-strand DNA (ssDNA)–associated DSBs that accumulate in proce…

  本地源文件： `DOCX=prepared/docx/167-scifact-10874408.docx` `PDF=prepared/pdf/167-scifact-10874408.pdf`
- rank=2 `13519661` Linkage Disequilibrium Mapping of       CHEK2: Common Variation and Breast Cancer Risk（本地heading=`SCIFACT-EVIDENCE-13519661`）

  > Background Checkpoint kinase 2 (CHEK2) averts cancer development by promoting cell cycle arrest and activating DNA repair in genetically damaged cells. Previous investigation has established a role for the CHEK2 gene in breast cancer aetiology, but studies have largely been limited to the rare 1100delC mutation. Whether common polymorphisms in this gene influence breast cancer risk remains unknown. In this study, we aimed to assess the importance of common CHEK2 variants on population risk for breast cancer by capturing the majority of diversity in the gene using haplotype tagging single nucle…

  本地源文件： `DOCX=prepared/docx/009-scifact-13519661.docx` `PDF=prepared/pdf/009-scifact-13519661.pdf`
- rank=3 `14079881` Perceived age as clinically useful biomarker of ageing: cohort study.（本地heading=`SCIFACT-EVIDENCE-14079881`）

  > OBJECTIVE To determine whether perceived age correlates with survival and important age related phenotypes. DESIGN Follow-up study, with survival of twins determined up to January 2008, by which time 675 (37%) had died. SETTING Population based twin cohort in Denmark. PARTICIPANTS 20 nurses, 10 young men, and 11 older women (assessors); 1826 twins aged >or=70. MAIN OUTCOME MEASURES Assessors: perceived age of twins from photographs. Twins: physical and cognitive tests and molecular biomarker of ageing (leucocyte telomere length). RESULTS For all three groups of assessors, perceived age was sig…

  本地源文件： `DOCX=prepared/docx/173-scifact-14079881.docx` `PDF=prepared/pdf/173-scifact-14079881.pdf`

首个可观测失败步骤：`dense_and_sparse_final_top10`。

直接证据：

- Dense gold首名次=Top10未命中
- Sparse gold首名次=Top10未命中
- Hybrid-RRF gold首名次=Top10未命中
- Hybrid-RRF+Rerank gold首名次=Top10未命中
- 分类规则=persistent_miss
- 逐候选分数=未采集

推断：Top10截断、语义近邻竞争或Rerank排序偏好可能共同影响；query与gold最大词项Jaccard=0.0423。

其他可能解释：相关性标注不完整、gold摘要与claim粒度不同、分块边界或Top10截断；当前终态run不能排除这些因素（persistent_miss）。

反证实验：固定同一索引和query，额外留存Dense/Sparse候选Top100、融合逐项贡献、Rerank输入输出分数及chunk文本，再复跑该query验证首个内部失效点（persistent_miss）。

### queryId=1199

问题：The benefits of colchicine were achieved with effective widespread use of secondary prevention strategies such as high-dose statins.

Gold文档：

- `16760369` Comparative determinants of 4-year cardiovascular event rates in stable outpatients at risk of or with atherothrombosis.

  > CONTEXT Clinicians and trialists have difficulty with identifying which patients are highest risk for cardiovascular events. Prior ischemic events, polyvascular disease, and diabetes mellitus have all been identified as predictors of ischemic events, but their comparative contributions to future risk remain unclear. OBJECTIVE To categorize the risk of cardiovascular events in stable outpatients with various initial manifestations of atherothrombosis using simple clinical descriptors. DESIGN, SETTING, AND PATIENTS Outpatients with coronary artery disease, cerebrovascular disease, or peripheral…

  本地源文件： `DOCX=prepared/docx/179-scifact-16760369.docx` `PDF=prepared/pdf/179-scifact-16760369.pdf`

| 变体 | Recall@10 | MRR@10 | nDCG@10 | Gold首名次 | 延迟ms | Top10文档ID（*为gold） |
|---|---:|---:|---:|---:|---:|---|
| dense | 0.000000 | 0.000000 | 0.000000 | Top10未命中 | 1713 | 11718220, 4687948, 1469751 |
| sparse | 0.000000 | 0.000000 | 0.000000 | Top10未命中 | 1606 | 4687948, 24088502, 18340282, 5289038, 16737210, 39381118, 20381484, 4942718, 18421962 |
| hybrid_rrf | 0.000000 | 0.000000 | 0.000000 | Top10未命中 | 2231 | 4687948, 24088502, 13843341, 5289038, 9745001, 34873974, 29564505 |
| hybrid_rrf_rerank | 0.000000 | 0.000000 | 0.000000 | Top10未命中 | 8515 | 4687948, 13843341, 9745001, 24088502, 29564505, 5289038, 34873974 |

首个失败步骤中的关键错误召回文档（前3条非gold）：

- rank=1 `11718220` Effectiveness of thigh-length graduated compression stockings to reduce the risk of deep vein thrombosis after stroke (CLOTS trial 1): a multicentre, randomised controlled trial（本地heading=`SCIFACT-EVIDENCE-11718220`）

  > BACKGROUND Deep vein thrombosis (DVT) and pulmonary embolism are common after stroke. In small trials of patients undergoing surgery, graduated compression stockings (GCS) reduce the risk of DVT. National stroke guidelines extrapolating from these trials recommend their use in patients with stroke despite insufficient evidence. We assessed the effectiveness of thigh-length GCS to reduce DVT after stroke. METHODS In this outcome-blinded, randomised controlled trial, 2518 patients who were admitted to hospital within 1 week of an acute stroke and who were immobile were enrolled from 64 centres i…

  本地源文件： `DOCX=prepared/docx/186-scifact-11718220.docx` `PDF=prepared/pdf/186-scifact-11718220.pdf`
- rank=2 `4687948` HMG-CoA reductase inhibitors and the risk of hip fractures in elderly patients.（本地heading=`SCIFACT-EVIDENCE-4687948`）

  > CONTEXT Recent animal studies have found that 3-hydroxy-3-methylglutaryl coenzyme A (HMG-CoA) lipid-lowering drugs (statins) substantially increase bone formation, but whether statin use in humans results in clinically meaningful bone formation or a reduction in the risk of osteoporotic fractures is not known. OBJECTIVE To determine whether the use of statins is associated with reduced hip fracture risk. DESIGN Case-control study. SETTING AND PATIENTS A total of 6110 New Jersey residents aged 65 years or older and enrolled in Medicare and either Medicaid or the Pharmacy Assistance for the Aged…

  本地源文件： `DOCX=prepared/docx/036-scifact-4687948.docx` `PDF=prepared/pdf/036-scifact-4687948.pdf`
- rank=3 `1469751` Aptamer-functionalized lipid nanoparticles targeting osteoblasts as a novel RNA interference–based bone anabolic strategy（本地heading=`SCIFACT-EVIDENCE-1469751`）

  > Currently, major concerns about the safety and efficacy of RNA interference (RNAi)-based bone anabolic strategies still exist because of the lack of direct osteoblast-specific delivery systems for osteogenic siRNAs. Here we screened the aptamer CH6 by cell-SELEX, specifically targeting both rat and human osteoblasts, and then we developed CH6 aptamer–functionalized lipid nanoparticles (LNPs) encapsulating osteogenic pleckstrin homology domain-containing family O member 1 (Plekho1) siRNA (CH6-LNPs-siRNA). Our results showed that CH6 facilitated in vitro osteoblast-selective uptake of Plekho1 si…

  本地源文件： `DOCX=prepared/docx/151-scifact-1469751.docx` `PDF=prepared/pdf/151-scifact-1469751.pdf`

首个可观测失败步骤：`dense_and_sparse_final_top10`。

直接证据：

- Dense gold首名次=Top10未命中
- Sparse gold首名次=Top10未命中
- Hybrid-RRF gold首名次=Top10未命中
- Hybrid-RRF+Rerank gold首名次=Top10未命中
- 分类规则=persistent_miss
- 逐候选分数=未采集

推断：Top10截断、语义近邻竞争或Rerank排序偏好可能共同影响；query与gold最大词项Jaccard=0.0526。

其他可能解释：相关性标注不完整、gold摘要与claim粒度不同、分块边界或Top10截断；当前终态run不能排除这些因素（persistent_miss）。

反证实验：固定同一索引和query，额外留存Dense/Sparse候选Top100、融合逐项贡献、Rerank输入输出分数及chunk文本，再复跑该query验证首个内部失效点（persistent_miss）。

### queryId=437

问题：Functional consequences of genomic alterations due to Myelodysplastic syndrome (MDS) are poorly understood due to the lack of an animal model.

Gold文档：

- `18399038` Establishment of human iPSC-based models for the study and targeting of glioma initiating cells

  > Glioma tumour-initiating cells (GTICs) can originate upon the transformation of neural progenitor cells (NPCs). Studies on GTICs have focused on primary tumours from which GTICs could be isolated and the use of human embryonic material. Recently, the somatic genomic landscape of human gliomas has been reported. RTK (receptor tyrosine kinase) and p53 signalling were found dysregulated in ∼90% and 86% of all primary tumours analysed, respectively. Here we report on the use of human-induced pluripotent stem cells (hiPSCs) for modelling gliomagenesis. Dysregulation of RTK and p53 signalling in hiP…

  本地源文件： `DOCX=prepared/docx/080-scifact-18399038.docx` `PDF=prepared/pdf/080-scifact-18399038.pdf`

| 变体 | Recall@10 | MRR@10 | nDCG@10 | Gold首名次 | 延迟ms | Top10文档ID（*为gold） |
|---|---:|---:|---:|---:|---:|---|
| dense | 0.000000 | 0.000000 | 0.000000 | Top10未命中 | 1920 | 3863543, 4388470, 11369420, 1084345, 22038539, 12827098, 26851674, 37549932, 19736671, 12486491 |
| sparse | 0.000000 | 0.000000 | 0.000000 | Top10未命中 | 1681 | 1606628, 14637235, 5476778, 11603066, 4388470, 49556906, 8780599, 33370 |
| hybrid_rrf | 0.000000 | 0.000000 | 0.000000 | Top10未命中 | 1954 | 4388470, 3863543, 5476778, 19736671, 49556906, 37549932, 1084345, 7521113, 14637235, 17081238 |
| hybrid_rrf_rerank | 0.000000 | 0.000000 | 0.000000 | Top10未命中 | 7318 | 3863543, 4388470, 19736671, 49556906, 1084345, 14637235, 37549932, 5476778, 7521113, 17081238 |

首个失败步骤中的关键错误召回文档（前3条非gold）：

- rank=1 `3863543` Mesenchymal Inflammation Drives Genotoxic Stress in Hematopoietic Stem Cells and Predicts Disease Evolution in Human Pre-leukemia.（本地heading=`SCIFACT-EVIDENCE-3863543`）

  > Mesenchymal niche cells may drive tissue failure and malignant transformation in the hematopoietic system, but the underlying molecular mechanisms and relevance to human disease remain poorly defined. Here, we show that perturbation of mesenchymal cells in a mouse model of the pre-leukemic disorder Shwachman-Diamond syndrome (SDS) induces mitochondrial dysfunction, oxidative stress, and activation of DNA damage responses in hematopoietic stem and progenitor cells. Massive parallel RNA sequencing of highly purified mesenchymal cells in the SDS mouse model and a range of human pre-leukemic syndr…

  本地源文件： `DOCX=prepared/docx/044-scifact-3863543.docx` `PDF=prepared/pdf/044-scifact-3863543.pdf`
- rank=2 `4388470` Somatic sex identity is cell-autonomous in the chicken（本地heading=`SCIFACT-EVIDENCE-4388470`）

  > In the mammalian model of sex determination, embryos are considered to be sexually indifferent until the transient action of a sex-determining gene initiates gonadal differentiation. Although this model is thought to apply to all vertebrates, this has yet to be established. Here we have examined three lateral gynandromorph chickens (a rare, naturally occurring phenomenon in which one side of the animal appears male and the other female) to investigate the sex-determining mechanism in birds. These studies demonstrated that gynandromorph birds are genuine male:female chimaeras, and indicated tha…

  本地源文件： `DOCX=prepared/docx/135-scifact-4388470.docx` `PDF=prepared/pdf/135-scifact-4388470.pdf`
- rank=3 `11369420` Tetraspanin 3 Is Required for the Development and Propagation of Acute Myelogenous Leukemia.（本地heading=`SCIFACT-EVIDENCE-11369420`）

  > Acute Myelogenous Leukemia (AML) is an aggressive cancer that strikes both adults and children and is frequently resistant to therapy. Thus, identifying signals needed for AML propagation is a critical step toward developing new approaches for treating this disease. Here, we show that Tetraspanin 3 is a target of the RNA binding protein Musashi 2, which plays a key role in AML. We generated Tspan3 knockout mice that were born without overt defects. However, Tspan3 deletion impaired leukemia stem cell self-renewal and disease propagation and markedly improved survival in mouse models of AML. Ad…

  本地源文件： `DOCX=prepared/docx/028-scifact-11369420.docx` `PDF=prepared/pdf/028-scifact-11369420.pdf`

首个可观测失败步骤：`dense_and_sparse_final_top10`。

直接证据：

- Dense gold首名次=Top10未命中
- Sparse gold首名次=Top10未命中
- Hybrid-RRF gold首名次=Top10未命中
- Hybrid-RRF+Rerank gold首名次=Top10未命中
- 分类规则=persistent_miss
- 逐候选分数=未采集

推断：Top10截断、语义近邻竞争或Rerank排序偏好可能共同影响；query与gold最大词项Jaccard=0.0349。

其他可能解释：相关性标注不完整、gold摘要与claim粒度不同、分块边界或Top10截断；当前终态run不能排除这些因素（persistent_miss）。

反证实验：固定同一索引和query，额外留存Dense/Sparse候选Top100、融合逐项贡献、Rerank输入输出分数及chunk文本，再复跑该query验证首个内部失效点（persistent_miss）。

### queryId=502

问题：Healthcare delivery efficiency in crowded delivery centers is impaired by improving structural, logistical, and interpersonal elements.

Gold文档：

- `13071728` The HIV Treatment Gap: Estimates of the Financial Resources Needed versus Available for Scale-Up of Antiretroviral Therapy in 97 Countries from 2015 to 2020

  > BACKGROUND The World Health Organization (WHO) released revised guidelines in 2015 recommending that all people living with HIV, regardless of CD4 count, initiate antiretroviral therapy (ART) upon diagnosis. However, few studies have projected the global resources needed for rapid scale-up of ART. Under the Health Policy Project, we conducted modeling analyses for 97 countries to estimate eligibility for and numbers on ART from 2015 to 2020, along with the facility-level financial resources required. We compared the estimated financial requirements to estimated funding available. METHODS AND F…

  本地源文件： `DOCX=prepared/docx/162-scifact-13071728.docx` `PDF=prepared/pdf/162-scifact-13071728.pdf`

| 变体 | Recall@10 | MRR@10 | nDCG@10 | Gold首名次 | 延迟ms | Top10文档ID（*为gold） |
|---|---:|---:|---:|---:|---:|---|
| dense | 0.000000 | 0.000000 | 0.000000 | Top10未命中 | 1675 | 5289038, 13906581, 25649714, 1215116, 16495649, 19675911, 2177022, 1642727 |
| sparse | 0.000000 | 0.000000 | 0.000000 | Top10未命中 | 1553 | 15928989, 8551160, 18909530, 3475317, 5531479, 36606083, 6157837, 39381118, 13900610, 20231138 |
| hybrid_rrf | 0.000000 | 0.000000 | 0.000000 | Top10未命中 | 1678 | 3475317, 13906581, 15928989, 5531479, 12789595, 1469751, 13770184, 16284655, 3553087 |
| hybrid_rrf_rerank | 0.000000 | 0.000000 | 0.000000 | Top10未命中 | 8599 | 13906581, 15928989, 5531479, 3475317, 16284655, 3553087, 12789595, 1469751, 13770184 |

首个失败步骤中的关键错误召回文档（前3条非gold）：

- rank=1 `5289038` Partitioning regulatory mechanisms of within-host malaria dynamics using the effective propagation number.（本地heading=`SCIFACT-EVIDENCE-5289038`）

  > Immune clearance and resource limitation (via red blood cell depletion) shape the peaks and troughs of malaria parasitemia, which in turn affect disease severity and transmission. Quantitatively partitioning the relative roles of these effects through time is challenging. Using data from rodent malaria, we estimated the effective propagation number, which reflects the relative importance of contrasting within-host control mechanisms through time and is sensitive to the inoculating parasite dose. Our analysis showed that the capacity of innate responses to restrict initial parasite growth satur…

  本地源文件： `DOCX=prepared/docx/057-scifact-5289038.docx` `PDF=prepared/pdf/057-scifact-5289038.pdf`
- rank=2 `13906581` Patient Outcomes with Teaching Versus Nonteaching Healthcare: A Systematic Review（本地heading=`SCIFACT-EVIDENCE-13906581`）

  > Background  Extensive debate exists in the healthcare community over whether outcomes of medical care at teaching hospitals and other healthcare units are better or worse than those at the respective nonteaching ones. Thus, our goal was to systematically evaluate the evidence pertaining to this question. Methods and Findings  We reviewed all studies that compared teaching versus nonteaching healthcare structures for mortality or any other patient outcome, regardless of health condition. Studies were retrieved from PubMed, contact with experts, and literature cross-referencing. Data were extrac…

  本地源文件： `DOCX=prepared/docx/196-scifact-13906581.docx` `PDF=prepared/pdf/196-scifact-13906581.pdf`
- rank=3 `25649714` Mental health problems of homeless children and families: longitudinal study.（本地heading=`SCIFACT-EVIDENCE-25649714`）

  > OBJECTIVE To establish the mental health needs of homeless children and families before and after rehousing. DESIGN Cross sectional, longitudinal study. SETTING City of Birmingham. SUBJECTS 58 rehoused families with 103 children aged 2-16 years and 21 comparison families of low socioeconomic status in stable housing, with 54 children. MAIN OUTCOME MEASURES Children's mental health problems and level of communication; mothers' mental health problems and social support one year after rehousing. RESULTS Mental health problems remained significantly higher in rehoused mothers and their children th…

  本地源文件： `DOCX=prepared/docx/003-scifact-25649714.docx` `PDF=prepared/pdf/003-scifact-25649714.pdf`

首个可观测失败步骤：`dense_and_sparse_final_top10`。

直接证据：

- Dense gold首名次=Top10未命中
- Sparse gold首名次=Top10未命中
- Hybrid-RRF gold首名次=Top10未命中
- Hybrid-RRF+Rerank gold首名次=Top10未命中
- 分类规则=persistent_miss
- 逐候选分数=未采集

推断：Top10截断、语义近邻竞争或Rerank排序偏好可能共同影响；query与gold最大词项Jaccard=0.0235。

其他可能解释：相关性标注不完整、gold摘要与claim粒度不同、分块边界或Top10截断；当前终态run不能排除这些因素（persistent_miss）。

反证实验：固定同一索引和query，额外留存Dense/Sparse候选Top100、融合逐项贡献、Rerank输入输出分数及chunk文本，再复跑该query验证首个内部失效点（persistent_miss）。

### queryId=887

问题：Only a minority of cells survive development after differentiation into stress-resistant spores.

Gold文档：

- `18855191` Exploitative and Hierarchical Antagonism in a Cooperative Bacterium

  > Social organisms that cooperate with some members of their own species, such as close relatives, may fail to cooperate with other genotypes of the same species. Such noncooperation may take the form of outright antagonism or social exploitation. Myxococcus xanthus is a highly social prokaryote that cooperatively develops into spore-bearing, multicellular fruiting bodies in response to starvation. Here we have characterized the nature of social interactions among nine developmentally proficient strains of M. xanthus isolated from spatially distant locations. Strains were competed against one an…

  本地源文件： `DOCX=prepared/docx/022-scifact-18855191.docx` `PDF=prepared/pdf/022-scifact-18855191.pdf`

| 变体 | Recall@10 | MRR@10 | nDCG@10 | Gold首名次 | 延迟ms | Top10文档ID（*为gold） |
|---|---:|---:|---:|---:|---:|---|
| dense | 0.000000 | 0.000000 | 0.000000 | Top10未命中 | 1727 | 4942718, 4381486, 9559146, 18909530, 2356950, 15928989, 9433958, 28937856 |
| sparse | 0.000000 | 0.000000 | 0.000000 | Top10未命中 | 1440 | 33872649, 3863543, 123859, 5373138, 40632104, 23349986, 28937856, 2251426, 45638119 |
| hybrid_rrf | 0.000000 | 0.000000 | 0.000000 | Top10未命中 | 1679 | 2356950, 28937856, 18909530, 4942718, 3863543, 23460562, 5373138, 123859 |
| hybrid_rrf_rerank | 0.000000 | 0.000000 | 0.000000 | Top10未命中 | 7638 | 4942718, 28937856, 23460562, 18909530, 2356950, 3863543, 123859, 5373138 |

首个失败步骤中的关键错误召回文档（前3条非gold）：

- rank=1 `4942718` High-Throughput Genetic Screens Identify a Large and Diverse Collection of New Sporulation Genes in Bacillus subtilis（本地heading=`SCIFACT-EVIDENCE-4942718`）

  > The differentiation of the bacterium Bacillus subtilis into a dormant spore is among the most well-characterized developmental pathways in biology. Classical genetic screens performed over the past half century identified scores of factors involved in every step of this morphological process. More recently, transcriptional profiling uncovered additional sporulation-induced genes required for successful spore development. Here, we used transposon-sequencing (Tn-seq) to assess whether there were any sporulation genes left to be discovered. Our screen identified 133 out of the 148 genes with know…

  本地源文件： `DOCX=prepared/docx/133-scifact-4942718.docx` `PDF=prepared/pdf/133-scifact-4942718.pdf`
- rank=2 `4381486` Haematopoietic stem cells do not asymmetrically segregate chromosomes or retain BrdU（本地heading=`SCIFACT-EVIDENCE-4381486`）

  > Stem cells are proposed to segregate chromosomes asymmetrically during self-renewing divisions so that older (‘immortal’) DNA strands are retained in daughter stem cells whereas newly synthesized strands segregate to differentiating cells. Stem cells are also proposed to retain DNA labels, such as 5-bromo-2-deoxyuridine (BrdU), either because they segregate chromosomes asymmetrically or because they divide slowly. However, the purity of stem cells among BrdU-label-retaining cells has not been documented in any tissue, and the ‘immortal strand hypothesis’ has not been tested in a system with de…

  本地源文件： `DOCX=prepared/docx/019-scifact-4381486.docx` `PDF=prepared/pdf/019-scifact-4381486.pdf`
- rank=3 `9559146` Senescent Cells, Tumor Suppression, and Organismal Aging: Good Citizens, Bad Neighbors（本地heading=`SCIFACT-EVIDENCE-9559146`）

  > Cells from organisms with renewable tissues can permanently withdraw from the cell cycle in response to diverse stress, including dysfunctional telomeres, DNA damage, strong mitogenic signals, and disrupted chromatin. This response, termed cellular senescence, is controlled by the p53 and RB tumor suppressor proteins and constitutes a potent anticancer mechanism. Nonetheless, senescent cells acquire phenotypic changes that may contribute to aging and certain age-related diseases, including late-life cancer. Thus, the senescence response may be antagonistically pleiotropic, promoting early-life…

  本地源文件： `DOCX=prepared/docx/090-scifact-9559146.docx` `PDF=prepared/pdf/090-scifact-9559146.pdf`

首个可观测失败步骤：`dense_and_sparse_final_top10`。

直接证据：

- Dense gold首名次=Top10未命中
- Sparse gold首名次=Top10未命中
- Hybrid-RRF gold首名次=Top10未命中
- Hybrid-RRF+Rerank gold首名次=Top10未命中
- 分类规则=persistent_miss
- 逐候选分数=未采集

推断：Top10截断、语义近邻竞争或Rerank排序偏好可能共同影响；query与gold最大词项Jaccard=0.0247。

其他可能解释：相关性标注不完整、gold摘要与claim粒度不同、分块边界或Top10截断；当前终态run不能排除这些因素（persistent_miss）。

反证实验：固定同一索引和query，额外留存Dense/Sparse候选Top100、融合逐项贡献、Rerank输入输出分数及chunk文本，再复跑该query验证首个内部失效点（persistent_miss）。

## rerank_reorder_gain

### queryId=1225

问题：The locus rs647161 is associated with colorectal carcinoma.

Gold文档：

- `9650982` Genome-wide association analyses in East Asians identify new susceptibility loci for colorectal cancer

  > To identify new genetic factors for colorectal cancer (CRC), we conducted a                 genome-wide association study in east Asians. By analyzing genome-wide data in 2,098                 cases and 5,749 controls, we selected 64 promising SNPs for replication in an                 independent set of samples, including up to 5,358 cases and 5,922 controls. We                 identified four SNPs with association P values of 8.58 ×                     10(-7) to 3.77 × 10(-10)                 in the combined analysis of all east Asian samples. Three of the four were                 replicate…

  本地源文件： `DOCX=prepared/docx/033-scifact-9650982.docx` `PDF=prepared/pdf/033-scifact-9650982.pdf`

| 变体 | Recall@10 | MRR@10 | nDCG@10 | Gold首名次 | 延迟ms | Top10文档ID（*为gold） |
|---|---:|---:|---:|---:|---:|---|
| dense | 1.000000 | 1.000000 | 1.000000 | 1 | 1692 | 9650982*, 15476777, 5304891, 17717391, 1469751, 7521113 |
| sparse | 0.000000 | 0.000000 | 0.000000 | Top10未命中 | 1459 | 56893404, 2095573, 23649163, 17930286, 24512064, 1897324, 24341590, 14376683, 17000834 |
| hybrid_rrf | 1.000000 | 0.142857 | 0.333333 | 7 | 1801 | 2095573, 5304891, 15476777, 13905670, 18340282, 56893404, 9650982*, 23649163, 24341590 |
| hybrid_rrf_rerank | 1.000000 | 1.000000 | 1.000000 | 1 | 6191 | 9650982*, 24341590, 23649163, 18340282, 15476777, 5304891, 56893404, 2095573, 13905670 |

首个失败步骤中的关键错误召回文档（前3条非gold）：

- rank=1 `2095573` LDL-cholesterol concentrations: a genome-wide association study（本地heading=`SCIFACT-EVIDENCE-2095573`）

  > BACKGROUND LDL cholesterol has a causal role in the development of cardiovascular disease. Improved understanding of the biological mechanisms that underlie the metabolism and regulation of LDL cholesterol might help to identify novel therapeutic targets. We therefore did a genome-wide association study of LDL-cholesterol concentrations. METHODS We used genome-wide association data from up to 11,685 participants with measures of circulating LDL-cholesterol concentrations across five studies, including data for 293 461 autosomal single nucleotide polymorphisms (SNPs) with a minor allele frequen…

  本地源文件： `DOCX=prepared/docx/021-scifact-2095573.docx` `PDF=prepared/pdf/021-scifact-2095573.pdf`
- rank=2 `5304891` Inter-individual variability and genetic influences on cytokine responses to bacteria and fungi（本地heading=`SCIFACT-EVIDENCE-5304891`）

  > Little is known about the inter-individual variation of cytokine responses to different pathogens in healthy individuals. To systematically describe cytokine responses elicited by distinct pathogens and to determine the effect of genetic variation on cytokine production, we profiled cytokines produced by peripheral blood mononuclear cells from 197 individuals of European origin from the 200 Functional Genomics (200FG) cohort in the Human Functional Genomics Project (http://www.humanfunctionalgenomics.org), obtained over three different years. We compared bacteria- and fungi-induced cytokine pr…

  本地源文件： `DOCX=prepared/docx/103-scifact-5304891.docx` `PDF=prepared/pdf/103-scifact-5304891.pdf`
- rank=3 `15476777` Chemotherapy options in elderly and frail patients with metastatic colorectal cancer (MRC FOCUS2): an open-label, randomised factorial trial（本地heading=`SCIFACT-EVIDENCE-15476777`）

  > BACKGROUND Elderly and frail patients with cancer, although often treated with chemotherapy, are under-represented in clinical trials. We designed FOCUS2 to investigate reduced-dose chemotherapy options and to seek objective predictors of outcome in frail patients with advanced colorectal cancer. METHODS We undertook an open, 2 × 2 factorial trial in 61 UK centres for patients with previously untreated advanced colorectal cancer who were considered unfit for full-dose chemotherapy. After comprehensive health assessment (CHA), patients were randomly assigned by minimisation to: 48-h intravenous…

  本地源文件： `DOCX=prepared/docx/128-scifact-15476777.docx` `PDF=prepared/pdf/128-scifact-15476777.pdf`

首个可观测失败步骤：`hybrid_rrf_rank_order`。

直接证据：

- Dense gold首名次=1
- Sparse gold首名次=Top10未命中
- Hybrid-RRF gold首名次=7
- Hybrid-RRF+Rerank gold首名次=1
- 分类规则=rerank_reorder_gain
- 逐候选分数=未采集

推断：稀疏词项匹配对同义改写或词形差异不敏感；query与gold标题/片段最大词项Jaccard=0.0476。

其他可能解释：相关性标注不完整、gold摘要与claim粒度不同、分块边界或Top10截断；当前终态run不能排除这些因素（rerank_reorder_gain）。

反证实验：固定同一索引和query，额外留存Dense/Sparse候选Top100、融合逐项贡献、Rerank输入输出分数及chunk文本，再复跑该query验证首个内部失效点（rerank_reorder_gain）。

### queryId=768

问题：Mercaptopurine is anabolized into the inactive methylmercaptopurine by thiopurine methyltrasnferase (TPMT).

Gold文档：

- `6421792` Activating mutations in the NT5C2 nucleotidase gene drive chemotherapy resistance in relapsed ALL

  > Acute lymphoblastic leukemia (ALL) is an aggressive hematological tumor resulting from the malignant transformation of lymphoid progenitors. Despite intensive chemotherapy, 20% of pediatric patients and over 50% of adult patients with ALL do not achieve a complete remission or relapse after intensified chemotherapy, making disease relapse and resistance to therapy the most substantial challenge in the treatment of this disease. Using whole-exome sequencing, we identify mutations in the cytosolic 5'-nucleotidase II gene (NT5C2), which encodes a 5'-nucleotidase enzyme that is responsible for the…

  本地源文件： `DOCX=prepared/docx/051-scifact-6421792.docx` `PDF=prepared/pdf/051-scifact-6421792.pdf`

| 变体 | Recall@10 | MRR@10 | nDCG@10 | Gold首名次 | 延迟ms | Top10文档ID（*为gold） |
|---|---:|---:|---:|---:|---:|---|
| dense | 1.000000 | 0.500000 | 0.630930 | 2 | 1684 | 23895668, 6421792*, 17755060, 3441524, 18421962, 11603066, 49556906, 5531479, 1469751, 52873726 |
| sparse | 0.000000 | 0.000000 | 0.000000 | Top10未命中 | 1574 | 20381484, 306006, 21366394, 11603066, 56893404, 8780599, 24294572, 17741440, 8460275 |
| hybrid_rrf | 1.000000 | 0.200000 | 0.386853 | 5 | 2037 | 11603066, 24294572, 33499189, 306006, 6421792*, 24221369, 4423559, 24341590, 16472469 |
| hybrid_rrf_rerank | 1.000000 | 1.000000 | 1.000000 | 1 | 7789 | 6421792*, 24341590, 11603066, 16472469, 24221369, 306006, 24294572, 4423559, 33499189 |

首个失败步骤中的关键错误召回文档（前3条非gold）：

- rank=1 `11603066` Using Structural Information to Change the Phosphotransfer Specificity of a Two-Component Chemotaxis Signalling Complex（本地heading=`SCIFACT-EVIDENCE-11603066`）

  > Two-component signal transduction pathways comprising histidine protein kinases (HPKs) and their response regulators (RRs) are widely used to control bacterial responses to environmental challenges. Some bacteria have over 150 different two-component pathways, and the specificity of the phosphotransfer reactions within these systems is tightly controlled to prevent unwanted crosstalk. One of the best understood two-component signalling pathways is the chemotaxis pathway. Here, we present the 1.40 A crystal structure of the histidine-containing phosphotransfer domain of the chemotaxis HPK, CheA…

  本地源文件： `DOCX=prepared/docx/170-scifact-11603066.docx` `PDF=prepared/pdf/170-scifact-11603066.pdf`
- rank=2 `24294572` PTEN Regulates PI(3,4)P2 Signaling Downstream of Class I PI3K（本地heading=`SCIFACT-EVIDENCE-24294572`）

  > The PI3K signaling pathway regulates cell growth and movement and is heavily mutated in cancer. Class I PI3Ks synthesize the lipid messenger PI(3,4,5)P3. PI(3,4,5)P3 can be dephosphorylated by 3- or 5-phosphatases, the latter producing PI(3,4)P2. The PTEN tumor suppressor is thought to function primarily as a PI(3,4,5)P3 3-phosphatase, limiting activation of this pathway. Here we show that PTEN also functions as a PI(3,4)P2 3-phosphatase, both in vitro and in vivo. PTEN is a major PI(3,4)P2 phosphatase in Mcf10a cytosol, and loss of PTEN and INPP4B, a known PI(3,4)P2 4-phosphatase, leads to sy…

  本地源文件： `DOCX=prepared/docx/081-scifact-24294572.docx` `PDF=prepared/pdf/081-scifact-24294572.pdf`
- rank=3 `33499189` Full activation of the T cell receptor requires both clustering and conformational changes at CD3.（本地heading=`SCIFACT-EVIDENCE-33499189`）

  > T cell receptor (TCR-CD3) triggering involves both receptor clustering and conformational changes at the cytoplasmic tails of the CD3 subunits. The mechanism by which TCRalphabeta ligand binding confers conformational changes to CD3 is unknown. By using well-defined ligands, we showed that induction of the conformational change requires both multivalent engagement and the mobility restriction of the TCR-CD3 imposed by the plasma membrane. The conformational change is elicited by cooperative rearrangements of two TCR-CD3 complexes and does not require accompanying changes in the structure of th…

  本地源文件： `DOCX=prepared/docx/156-scifact-33499189.docx` `PDF=prepared/pdf/156-scifact-33499189.pdf`

首个可观测失败步骤：`hybrid_rrf_rank_order`。

直接证据：

- Dense gold首名次=2
- Sparse gold首名次=Top10未命中
- Hybrid-RRF gold首名次=5
- Hybrid-RRF+Rerank gold首名次=1
- 分类规则=rerank_reorder_gain
- 逐候选分数=未采集

推断：稀疏词项匹配对同义改写或词形差异不敏感；query与gold标题/片段最大词项Jaccard=0.0256。

其他可能解释：相关性标注不完整、gold摘要与claim粒度不同、分块边界或Top10截断；当前终态run不能排除这些因素（rerank_reorder_gain）。

反证实验：固定同一索引和query，额外留存Dense/Sparse候选Top100、融合逐项贡献、Rerank输入输出分数及chunk文本，再复跑该query验证首个内部失效点（rerank_reorder_gain）。

### queryId=1175

问题：The PPR MDA5 has two N-terminal CARD domains.

Gold文档：

- `31272411` Immune signaling by RIG-I-like receptors.

  > The RIG-I-like receptors (RLRs) RIG-I, MDA5, and LGP2 play a major role in pathogen sensing of RNA virus infection to initiate and modulate antiviral immunity. The RLRs detect viral RNA ligands or processed self RNA in the cytoplasm to trigger innate immunity and inflammation and to impart gene expression that serves to control infection. Importantly, RLRs cooperate in signaling crosstalk networks with Toll-like receptors and other factors to impart innate immunity and to modulate the adaptive immune response. RLR regulation occurs at a variety of levels ranging from autoregulation to ligand a…

  本地源文件： `DOCX=prepared/docx/115-scifact-31272411.docx` `PDF=prepared/pdf/115-scifact-31272411.pdf`

| 变体 | Recall@10 | MRR@10 | nDCG@10 | Gold首名次 | 延迟ms | Top10文档ID（*为gold） |
|---|---:|---:|---:|---:|---:|---|
| dense | 0.000000 | 0.000000 | 0.000000 | Top10未命中 | 1750 | 4423559, 15319019, 11603066, 5531479, 4387784 |
| sparse | 0.000000 | 0.000000 | 0.000000 | Top10未命中 | 1390 | 8646760, 11603066, 39381118, 28937856, 4687948 |
| hybrid_rrf | 1.000000 | 0.250000 | 0.430677 | 4 | 1700 | 11603066, 8646760, 22180793, 31272411*, 24221369, 15319019, 4387784 |
| hybrid_rrf_rerank | 1.000000 | 1.000000 | 1.000000 | 1 | 7910 | 31272411*, 22180793, 11603066, 8646760, 15319019, 4387784, 24221369 |

首个失败步骤中的关键错误召回文档（前3条非gold）：

- rank=1 `11603066` Using Structural Information to Change the Phosphotransfer Specificity of a Two-Component Chemotaxis Signalling Complex（本地heading=`SCIFACT-EVIDENCE-11603066`）

  > Two-component signal transduction pathways comprising histidine protein kinases (HPKs) and their response regulators (RRs) are widely used to control bacterial responses to environmental challenges. Some bacteria have over 150 different two-component pathways, and the specificity of the phosphotransfer reactions within these systems is tightly controlled to prevent unwanted crosstalk. One of the best understood two-component signalling pathways is the chemotaxis pathway. Here, we present the 1.40 A crystal structure of the histidine-containing phosphotransfer domain of the chemotaxis HPK, CheA…

  本地源文件： `DOCX=prepared/docx/170-scifact-11603066.docx` `PDF=prepared/pdf/170-scifact-11603066.pdf`
- rank=2 `8646760` Identification and Functional Characterization of N-Terminally Acetylated Proteins in Drosophila melanogaster（本地heading=`SCIFACT-EVIDENCE-8646760`）

  > Protein modifications play a major role for most biological processes in living organisms. Amino-terminal acetylation of proteins is a common modification found throughout the tree of life: the N-terminus of a nascent polypeptide chain becomes co-translationally acetylated, often after the removal of the initiating methionine residue. While the enzymes and protein complexes involved in these processes have been extensively studied, only little is known about the biological function of such N-terminal modification events. To identify common principles of N-terminal acetylation, we analyzed the…

  本地源文件： `DOCX=prepared/docx/114-scifact-8646760.docx` `PDF=prepared/pdf/114-scifact-8646760.pdf`
- rank=3 `22180793` Monoclonal antibody targeting of N-cadherin inhibits prostate cancer growth, metastasis and castration resistance（本地heading=`SCIFACT-EVIDENCE-22180793`）

  > The transition from androgen-dependent to castration-resistant prostate cancer (CRPC) is a lethal event of uncertain molecular etiology. Comparing gene expression in isogenic androgen-dependent and CRPC xenografts, we found a reproducible increase in N-cadherin expression, which was also elevated in primary and metastatic tumors of individuals with CRPC. Ectopic expression of N-cadherin in nonmetastatic, androgen-dependent prostate cancer models caused castration resistance, invasion and metastasis. Monoclonal antibodies against the ectodomain of N-cadherin reduced proliferation, adhesion and…

  本地源文件： `DOCX=prepared/docx/142-scifact-22180793.docx` `PDF=prepared/pdf/142-scifact-22180793.pdf`

首个可观测失败步骤：`hybrid_rrf_rank_order`。

直接证据：

- Dense gold首名次=Top10未命中
- Sparse gold首名次=Top10未命中
- Hybrid-RRF gold首名次=4
- Hybrid-RRF+Rerank gold首名次=1
- 分类规则=rerank_reorder_gain
- 逐候选分数=未采集

推断：Top10截断、语义近邻竞争或Rerank排序偏好可能共同影响；query与gold最大词项Jaccard=0.0294。

其他可能解释：相关性标注不完整、gold摘要与claim粒度不同、分块边界或Top10截断；当前终态run不能排除这些因素（rerank_reorder_gain）。

反证实验：固定同一索引和query，额外留存Dense/Sparse候选Top100、融合逐项贡献、Rerank输入输出分数及chunk文本，再复跑该query验证首个内部失效点（rerank_reorder_gain）。

### queryId=1278

问题：The treatment of cancer patients with co-IR blockade does not cause any adverse autoimmune events.

Gold文档：

- `11335781` Is autoimmunity the Achilles' heel of cancer immunotherapy?

  > The emergence of immuno-oncology as the first broadly successful strategy for metastatic cancer will require clinicians to integrate this new pillar of medicine with chemotherapy, radiation, and targeted small-molecule compounds. Of equal importance is gaining an understanding of the limitations and toxicities of immunotherapy. Immunotherapy was initially perceived to be a relatively less toxic approach to cancer treatment than other available therapies—and surely it is, when compared to those. However, as the use of immunotherapy becomes more common, especially as first- and second-line treat…

  本地源文件： `DOCX=prepared/docx/018-scifact-11335781.docx` `PDF=prepared/pdf/018-scifact-11335781.pdf`

| 变体 | Recall@10 | MRR@10 | nDCG@10 | Gold首名次 | 延迟ms | Top10文档ID（*为gold） |
|---|---:|---:|---:|---:|---:|---|
| dense | 1.000000 | 0.500000 | 0.630930 | 2 | 1726 | 7975937, 11335781*, 10582939, 9745001, 1471041, 23649163, 15476777 |
| sparse | 0.000000 | 0.000000 | 0.000000 | Top10未命中 | 1598 | 15476777, 18340282, 23649163, 4687948, 40632104, 56893404, 9745001 |
| hybrid_rrf | 1.000000 | 0.250000 | 0.430677 | 4 | 2048 | 23649163, 15476777, 9745001, 11335781*, 10582939, 7975937, 4687948 |
| hybrid_rrf_rerank | 1.000000 | 1.000000 | 1.000000 | 1 | 7877 | 11335781*, 10582939, 7975937, 15476777, 23649163, 4687948, 9745001 |

首个失败步骤中的关键错误召回文档（前3条非gold）：

- rank=1 `23649163` Clinical features and treatment of peristomal pyoderma gangrenosum.（本地heading=`SCIFACT-EVIDENCE-23649163`）

  > CONTEXT Peristomal pyoderma gangrenosum (PPG), an unusual variant of pyoderma gangrenosum, has been reported almost exclusively in patients with inflammatory bowel disease (IBD) and is frequently misdiagnosed. OBJECTIVE To better characterize the clinical manifestations, diagnosis, and management of PPG. DESIGN, SETTING, AND PATIENTS Retrospective analysis of 7 patients with PPG observed in a university-affiliated community setting between 1988 and December 1999. MAIN OUTCOME MEASURES Clinical and histopathologic features, associated disorders, and microbiologic findings. RESULTS Two patients…

  本地源文件： `DOCX=prepared/docx/127-scifact-23649163.docx` `PDF=prepared/pdf/127-scifact-23649163.pdf`
- rank=2 `15476777` Chemotherapy options in elderly and frail patients with metastatic colorectal cancer (MRC FOCUS2): an open-label, randomised factorial trial（本地heading=`SCIFACT-EVIDENCE-15476777`）

  > BACKGROUND Elderly and frail patients with cancer, although often treated with chemotherapy, are under-represented in clinical trials. We designed FOCUS2 to investigate reduced-dose chemotherapy options and to seek objective predictors of outcome in frail patients with advanced colorectal cancer. METHODS We undertook an open, 2 × 2 factorial trial in 61 UK centres for patients with previously untreated advanced colorectal cancer who were considered unfit for full-dose chemotherapy. After comprehensive health assessment (CHA), patients were randomly assigned by minimisation to: 48-h intravenous…

  本地源文件： `DOCX=prepared/docx/128-scifact-15476777.docx` `PDF=prepared/pdf/128-scifact-15476777.pdf`
- rank=3 `9745001` Radioiodine treatment of multinodular non-toxic goitre.（本地heading=`SCIFACT-EVIDENCE-9745001`）

  > OBJECTIVE To investigate the long term effect of radioactive iodine on thyroid function and size in patients with non-toxic multinodular goitre. DESIGN Consecutive patients with multinodular non-toxic goitre selected for radioactive iodine treatment and followed for a minimum of 12 months (median 48 months) after an intended dose of 3.7 MBq/g thyroid tissue corrected to a 100% uptake of iodine-131 in 24 hours. PATIENTS 69 patients with a growing multinodular non-toxic goitre causing local compression symptoms or cosmetic inconveniences. The treatment was chosen because of a high operative risk…

  本地源文件： `DOCX=prepared/docx/078-scifact-9745001.docx` `PDF=prepared/pdf/078-scifact-9745001.pdf`

首个可观测失败步骤：`hybrid_rrf_rank_order`。

直接证据：

- Dense gold首名次=2
- Sparse gold首名次=Top10未命中
- Hybrid-RRF gold首名次=4
- Hybrid-RRF+Rerank gold首名次=1
- 分类规则=rerank_reorder_gain
- 逐候选分数=未采集

推断：稀疏词项匹配对同义改写或词形差异不敏感；query与gold标题/片段最大词项Jaccard=0.0625。

其他可能解释：相关性标注不完整、gold摘要与claim粒度不同、分块边界或Top10截断；当前终态run不能排除这些因素（rerank_reorder_gain）。

反证实验：固定同一索引和query，额外留存Dense/Sparse候选Top100、融合逐项贡献、Rerank输入输出分数及chunk文本，再复跑该query验证首个内部失效点（rerank_reorder_gain）。

### queryId=198

问题：CCL19 is absent within dLNs.

Gold文档：

- `2177022` Immobilized chemokine fields and soluble chemokine gradients cooperatively shape migration patterns of dendritic cells.

  > Chemokines orchestrate immune cell trafficking by eliciting either directed or random migration and by activating integrins in order to induce cell adhesion. Analyzing dendritic cell (DC) migration, we showed that these distinct cellular responses depended on the mode of chemokine presentation within tissues. The surface-immobilized form of the chemokine CCL21, the heparan sulfate-anchoring ligand of the CC-chemokine receptor 7 (CCR7), caused random movement of DCs that was confined to the chemokine-presenting surface because it triggered integrin-mediated adhesion. Upon direct contact with CC…

  本地源文件： `DOCX=prepared/docx/117-scifact-2177022.docx` `PDF=prepared/pdf/117-scifact-2177022.pdf`

| 变体 | Recall@10 | MRR@10 | nDCG@10 | Gold首名次 | 延迟ms | Top10文档ID（*为gold） |
|---|---:|---:|---:|---:|---:|---|
| dense | 1.000000 | 1.000000 | 1.000000 | 1 | 2207 | 2177022*, 7370282, 21366394, 4456756, 11369420, 20310709, 4423559, 5531479, 17587795 |
| sparse | 0.000000 | 0.000000 | 0.000000 | Top10未命中 | 1419 | 5289038, 4456756, 11369420, 36606083, 11172205, 3475317, 8290953, 14376683, 19799455, 20381484 |
| hybrid_rrf | 1.000000 | 0.333333 | 0.500000 | 3 | 1693 | 11369420, 4456756, 2177022*, 14376683, 17587795, 37549932, 24088502, 33499189 |
| hybrid_rrf_rerank | 1.000000 | 1.000000 | 1.000000 | 1 | 7728 | 2177022*, 24088502, 17587795, 14376683, 4456756, 11369420, 37549932, 33499189 |

首个失败步骤中的关键错误召回文档（前3条非gold）：

- rank=1 `11369420` Tetraspanin 3 Is Required for the Development and Propagation of Acute Myelogenous Leukemia.（本地heading=`SCIFACT-EVIDENCE-11369420`）

  > Acute Myelogenous Leukemia (AML) is an aggressive cancer that strikes both adults and children and is frequently resistant to therapy. Thus, identifying signals needed for AML propagation is a critical step toward developing new approaches for treating this disease. Here, we show that Tetraspanin 3 is a target of the RNA binding protein Musashi 2, which plays a key role in AML. We generated Tspan3 knockout mice that were born without overt defects. However, Tspan3 deletion impaired leukemia stem cell self-renewal and disease propagation and markedly improved survival in mouse models of AML. Ad…

  本地源文件： `DOCX=prepared/docx/028-scifact-11369420.docx` `PDF=prepared/pdf/028-scifact-11369420.pdf`
- rank=2 `4456756` Autocrine BDNF–TrkB signalling within a single dendritic spine（本地heading=`SCIFACT-EVIDENCE-4456756`）

  > Brain-derived neurotrophic factor (BDNF) and its receptor TrkB are crucial for many forms of neuronal plasticity, including structural long-term potentiation (sLTP), which is a correlate of an animal’s learning. However, it is unknown whether BDNF release and TrkB activation occur during sLTP, and if so, when and where. Here, using a fluorescence resonance energy transfer-based sensor for TrkB and two-photon fluorescence lifetime imaging microscopy, we monitor TrkB activity in single dendritic spines of CA1 pyramidal neurons in cultured murine hippocampal slices. In response to sLTP induction,…

  本地源文件： `DOCX=prepared/docx/032-scifact-4456756.docx` `PDF=prepared/pdf/032-scifact-4456756.pdf`
- rank=4 `14376683` Properties of Commelina yellow mottle virus's complete DNA sequence, genomic discontinuities and transcript suggest that it is a pararetrovirus.（本地heading=`SCIFACT-EVIDENCE-14376683`）

  > The non-enveloped bacilliform viruses are the second group of plant viruses known to possess a genome consisting of circular double-stranded DNA. We have characterized the viral transcript and determined the complete sequence of the genome of Commelina mellow mottle virus (CoYMV), a member of this group. Analysis of the viral transcript indicates that the virus encodes a single terminally-redundant genome-length plus 120 nucleotide transcript. A fraction of the transcripts is polyadenylated, although the majority of the transcript is not polyadenylated. Analysis of the genome sequence indicate…

  本地源文件： `DOCX=prepared/docx/039-scifact-14376683.docx` `PDF=prepared/pdf/039-scifact-14376683.pdf`

首个可观测失败步骤：`hybrid_rrf_rank_order`。

直接证据：

- Dense gold首名次=1
- Sparse gold首名次=Top10未命中
- Hybrid-RRF gold首名次=3
- Hybrid-RRF+Rerank gold首名次=1
- 分类规则=rerank_reorder_gain
- 逐候选分数=未采集

推断：稀疏词项匹配对同义改写或词形差异不敏感；query与gold标题/片段最大词项Jaccard=0.0132。

其他可能解释：相关性标注不完整、gold摘要与claim粒度不同、分块边界或Top10截断；当前终态run不能排除这些因素（rerank_reorder_gain）。

反证实验：固定同一索引和query，额外留存Dense/Sparse候选Top100、融合逐项贡献、Rerank输入输出分数及chunk文本，再复跑该query验证首个内部失效点（rerank_reorder_gain）。

## rerank_reorder_harm

### queryId=1303

问题：Tirasemtiv has no effect on fast-twitch muscle.

Gold文档：

- `12631697` Activation of fast skeletal muscle troponin as a potential therapeutic approach for treating neuromuscular diseases

  > Limited neural input results in muscle weakness in neuromuscular disease because of a reduction in the density of muscle innervation, the rate of neuromuscular junction activation or the efficiency of synaptic transmission. We developed a small-molecule fast-skeletal-troponin activator, CK-2017357, as a means to increase muscle strength by amplifying the response of muscle when neural input is otherwise diminished secondary to neuromuscular disease. Binding selectively to the fast-skeletal-troponin complex, CK-2017357 slows the rate of calcium release from troponin C and sensitizes muscle to c…

  本地源文件： `DOCX=prepared/docx/042-scifact-12631697.docx` `PDF=prepared/pdf/042-scifact-12631697.pdf`

| 变体 | Recall@10 | MRR@10 | nDCG@10 | Gold首名次 | 延迟ms | Top10文档ID（*为gold） |
|---|---:|---:|---:|---:|---:|---|
| dense | 1.000000 | 1.000000 | 1.000000 | 1 | 1743 | 12631697*, 23349986, 24341590, 16787954, 15319019 |
| sparse | 1.000000 | 0.500000 | 0.630930 | 2 | 1452 | 1642727, 12631697*, 12991445, 31272411, 19675911, 9745001 |
| hybrid_rrf | 1.000000 | 1.000000 | 1.000000 | 1 | 1945 | 12631697*, 1642727, 32159283, 24341590, 9745001, 18421962, 10991183, 1568684 |
| hybrid_rrf_rerank | 1.000000 | 0.333333 | 0.500000 | 3 | 6487 | 32159283, 24341590, 12631697*, 10991183, 1642727, 1568684, 9745001, 18421962 |

首个失败步骤中的关键错误召回文档（前3条非gold）：

- rank=1 `32159283` Antibiotics and risk of subsequent first-time acute myocardial infarction.（本地heading=`SCIFACT-EVIDENCE-32159283`）

  > CONTEXT Increasing evidence supports the hypothesis of a causal association between certain bacterial infections and increased risk of developing acute myocardial infarction. If such a causal association exists, subjects who used antibiotics active against the bacteria, regardless of indication, might be at lower risk of developing acute myocardial infarction than nonusers. OBJECTIVE To determine whether previous use of antibiotics decreases the risk of developing a first-time acute myocardial infarction. DESIGN Population-based case-control analysis. SETTING The United Kingdom-based General P…

  本地源文件： `DOCX=prepared/docx/002-scifact-32159283.docx` `PDF=prepared/pdf/002-scifact-32159283.pdf`
- rank=2 `24341590` Association between CYP2D6 polymorphisms and outcomes among women with early stage breast cancer treated with tamoxifen.（本地heading=`SCIFACT-EVIDENCE-24341590`）

  > CONTEXT The growth inhibitory effect of tamoxifen, which is used for the treatment of hormone receptor-positive breast cancer, is mediated by its metabolites, 4-hydroxytamoxifen and endoxifen. The formation of active metabolites is catalyzed by the polymorphic cytochrome P450 2D6 (CYP2D6) enzyme. OBJECTIVE To determine whether CYP2D6 variation is associated with clinical outcomes in women receiving adjuvant tamoxifen. DESIGN, SETTING, AND PATIENTS Retrospective analysis of German and US cohorts of patients treated with adjuvant tamoxifen for early stage breast cancer. The 1325 patients had dia…

  本地源文件： `DOCX=prepared/docx/091-scifact-24341590.docx` `PDF=prepared/pdf/091-scifact-24341590.pdf`
- rank=4 `10991183` The Rho GEFs LARG and GEF-H1 regulate the mechanical response to force on integrins（本地heading=`SCIFACT-EVIDENCE-10991183`）

  > How individual cells respond to mechanical forces is of considerable interest to biologists as force affects many aspects of cell behaviour. The application of force on integrins triggers cytoskeletal rearrangements and growth of the associated adhesion complex, resulting in increased cellular stiffness, also known as reinforcement. Although RhoA has been shown to play a role during reinforcement, the molecular mechanisms that regulate its activity are unknown. By combining biochemical and biophysical approaches, we identified two guanine nucleotide exchange factors (GEFs), LARG and GEF-H1, as…

  本地源文件： `DOCX=prepared/docx/076-scifact-10991183.docx` `PDF=prepared/pdf/076-scifact-10991183.pdf`

首个可观测失败步骤：`rerank_final_top10`。

直接证据：

- Dense gold首名次=1
- Sparse gold首名次=2
- Hybrid-RRF gold首名次=1
- Hybrid-RRF+Rerank gold首名次=3
- 分类规则=rerank_reorder_harm
- 逐候选分数=未采集

推断：Top10截断、语义近邻竞争或Rerank排序偏好可能共同影响；query与gold最大词项Jaccard=0.0303。

其他可能解释：相关性标注不完整、gold摘要与claim粒度不同、分块边界或Top10截断；当前终态run不能排除这些因素（rerank_reorder_harm）。

反证实验：固定同一索引和query，额外留存Dense/Sparse候选Top100、融合逐项贡献、Rerank输入输出分数及chunk文本，再复跑该query验证首个内部失效点（rerank_reorder_harm）。

### queryId=294

问题：Crossover hot spots are not found within gene promoters in Saccharomyces cerevisiae.

Gold文档：

- `10874408` Mapping Meiotic Single-Strand DNA Reveals a New Landscape of DNA Double-Strand Breaks in Saccharomyces cerevisiae

  > DNA double-strand breaks (DSBs), which are formed by the Spo11 protein, initiate meiotic recombination. Previous DSB-mapping studies have used rad50S or sae2Δ mutants, which are defective in break processing, to accumulate Spo11-linked DSBs, and report large (≥ 50 kb) “DSB-hot” regions that are separated by “DSB-cold” domains of similar size. Substantial recombination occurs in some DSB-cold regions, suggesting that DSB patterns are not normal in rad50S or sae2Δ mutants. We therefore developed a novel method to map genome-wide, single-strand DNA (ssDNA)–associated DSBs that accumulate in proce…

  本地源文件： `DOCX=prepared/docx/167-scifact-10874408.docx` `PDF=prepared/pdf/167-scifact-10874408.pdf`

| 变体 | Recall@10 | MRR@10 | nDCG@10 | Gold首名次 | 延迟ms | Top10文档ID（*为gold） |
|---|---:|---:|---:|---:|---:|---|
| dense | 1.000000 | 1.000000 | 1.000000 | 1 | 1968 | 10874408*, 32275758, 18421962, 31141365, 10300888 |
| sparse | 1.000000 | 1.000000 | 1.000000 | 1 | 1596 | 10874408*, 5373138, 1471041, 12789595, 13906581, 10300888, 4381486 |
| hybrid_rrf | 1.000000 | 1.000000 | 1.000000 | 1 | 1677 | 10874408*, 5373138, 10300888, 14637235, 1471041, 4381486 |
| hybrid_rrf_rerank | 1.000000 | 0.333333 | 0.500000 | 3 | 7284 | 10300888, 14637235, 10874408*, 5373138, 4381486, 1471041 |

首个失败步骤中的关键错误召回文档（前3条非gold）：

- rank=1 `10300888` Domestication and Divergence of Saccharomyces cerevisiae Beer Yeasts（本地heading=`SCIFACT-EVIDENCE-10300888`）

  > Whereas domestication of livestock, pets, and crops is well documented, it is still unclear to what extent microbes associated with the production of food have also undergone human selection and where the plethora of industrial strains originates from. Here, we present the genomes and phenomes of 157 industrial Saccharomyces cerevisiae yeasts. Our analyses reveal that today's industrial yeasts can be divided into five sublineages that are genetically and phenotypically separated from wild strains and originate from only a few ancestors through complex patterns of domestication and local diverg…

  本地源文件： `DOCX=prepared/docx/056-scifact-10300888.docx` `PDF=prepared/pdf/056-scifact-10300888.pdf`
- rank=2 `14637235` Histone levels are regulated by phosphorylation and ubiquitylation dependent proteolysis（本地heading=`SCIFACT-EVIDENCE-14637235`）

  > Histone levels are tightly regulated to prevent harmful effects such as genomic instability and hypersensitivity to DNA-damaging agents due to the accumulation of these highly basic proteins when DNA replication slows down or stops. Although chromosomal histones are stable, excess (non-chromatin bound) histones are rapidly degraded in a Rad53 (radiation sensitive 53) kinase-dependent manner in Saccharomyces cerevisiae. Here we demonstrate that excess histones associate with Rad53 in vivo and seem to undergo modifications such as tyrosine phosphorylation and polyubiquitylation, before their pro…

  本地源文件： `DOCX=prepared/docx/066-scifact-14637235.docx` `PDF=prepared/pdf/066-scifact-14637235.pdf`
- rank=4 `5373138` 3D Chromosome Regulatory Landscape of Human Pluripotent Cells.（本地heading=`SCIFACT-EVIDENCE-5373138`）

  > In this study, we describe the 3D chromosome regulatory landscape of human naive and primed embryonic stem cells. To devise this map, we identified transcriptional enhancers and insulators in these cells and placed them within the context of cohesin-associated CTCF-CTCF loops using cohesin ChIA-PET data. The CTCF-CTCF loops we identified form a chromosomal framework of insulated neighborhoods, which in turn form topologically associating domains (TADs) that are largely preserved during the transition between the naive and primed states. Regulatory changes in enhancer-promoter interactions occu…

  本地源文件： `DOCX=prepared/docx/150-scifact-5373138.docx` `PDF=prepared/pdf/150-scifact-5373138.pdf`

首个可观测失败步骤：`rerank_final_top10`。

直接证据：

- Dense gold首名次=1
- Sparse gold首名次=1
- Hybrid-RRF gold首名次=1
- Hybrid-RRF+Rerank gold首名次=3
- 分类规则=rerank_reorder_harm
- 逐候选分数=未采集

推断：Top10截断、语义近邻竞争或Rerank排序偏好可能共同影响；query与gold最大词项Jaccard=0.0789。

其他可能解释：相关性标注不完整、gold摘要与claim粒度不同、分块边界或Top10截断；当前终态run不能排除这些因素（rerank_reorder_harm）。

反证实验：固定同一索引和query，额外留存Dense/Sparse候选Top100、融合逐项贡献、Rerank输入输出分数及chunk文本，再复跑该query验证首个内部失效点（rerank_reorder_harm）。

### queryId=421

问题：Flexible molecules experience greater steric hindrance in the tumor microenviroment than rigid molecules.

Gold文档：

- `11172205` Quantum dots spectrally distinguish multiple species within the tumor milieu in vivo

  > A solid tumor is an organ composed of cancer and host cells embedded in an extracellular matrix and nourished by blood vessels. A prerequisite to understanding tumor pathophysiology is the ability to distinguish and monitor each component in dynamic studies. Standard fluorophores hamper simultaneous intravital imaging of these components. Here, we used multiphoton microscopy techniques and transgenic mice that expressed green fluorescent protein, and combined them with the use of quantum dot preparations. We show that these fluorescent semiconductor nanocrystals can be customized to concurrent…

  本地源文件： `DOCX=prepared/docx/145-scifact-11172205.docx` `PDF=prepared/pdf/145-scifact-11172205.pdf`

| 变体 | Recall@10 | MRR@10 | nDCG@10 | Gold首名次 | 延迟ms | Top10文档ID（*为gold） |
|---|---:|---:|---:|---:|---:|---|
| dense | 1.000000 | 0.142857 | 0.333333 | 7 | 1730 | 16787954, 1469751, 5483793, 18399038, 11041152, 2177022, 11172205*, 5289038, 9433958 |
| sparse | 1.000000 | 0.333333 | 0.500000 | 3 | 1300 | 25742130, 7662395, 11172205*, 12580014, 4687948, 6157837 |
| hybrid_rrf | 1.000000 | 1.000000 | 1.000000 | 1 | 1739 | 11172205*, 23895668, 18399038, 5531479, 6173523, 12580014, 17755060, 12486491, 18855191 |
| hybrid_rrf_rerank | 1.000000 | 0.333333 | 0.500000 | 3 | 5531 | 17755060, 23895668, 11172205*, 18399038, 5531479, 12580014, 12486491, 6173523, 18855191 |

首个失败步骤中的关键错误召回文档（前3条非gold）：

- rank=1 `17755060` Control of Nutrient Stress-Induced Metabolic Reprogramming by PKCζ in Tumorigenesis（本地heading=`SCIFACT-EVIDENCE-17755060`）

  > Tumor cells have high-energetic and anabolic needs and are known to adapt their metabolism to be able to survive and keep proliferating under conditions of nutrient stress. We show that PKCζ deficiency promotes the plasticity necessary for cancer cells to reprogram their metabolism to utilize glutamine through the serine biosynthetic pathway in the absence of glucose. PKCζ represses the expression of two key enzymes of the pathway, PHGDH and PSAT1, and phosphorylates PHGDH at key residues to inhibit its enzymatic activity. Interestingly, the loss of PKCζ in mice results in enhanced intestinal…

  本地源文件： `DOCX=prepared/docx/159-scifact-17755060.docx` `PDF=prepared/pdf/159-scifact-17755060.pdf`
- rank=2 `23895668` mTORC2 Regulates Amino Acid Metabolism in Cancer by Phosphorylation of the Cystine-Glutamate Antiporter xCT.（本地heading=`SCIFACT-EVIDENCE-23895668`）

  > Mutations in cancer reprogram amino acid metabolism to drive tumor growth, but the molecular mechanisms are not well understood. Using an unbiased proteomic screen, we identified mTORC2 as a critical regulator of amino acid metabolism in cancer via phosphorylation of the cystine-glutamate antiporter xCT. mTORC2 phosphorylates serine 26 at the cytosolic N terminus of xCT, inhibiting its activity. Genetic inhibition of mTORC2, or pharmacologic inhibition of the mammalian target of rapamycin (mTOR) kinase, promotes glutamate secretion, cystine uptake, and incorporation into glutathione, linking g…

  本地源文件： `DOCX=prepared/docx/065-scifact-23895668.docx` `PDF=prepared/pdf/065-scifact-23895668.pdf`
- rank=4 `18399038` Establishment of human iPSC-based models for the study and targeting of glioma initiating cells（本地heading=`SCIFACT-EVIDENCE-18399038`）

  > Glioma tumour-initiating cells (GTICs) can originate upon the transformation of neural progenitor cells (NPCs). Studies on GTICs have focused on primary tumours from which GTICs could be isolated and the use of human embryonic material. Recently, the somatic genomic landscape of human gliomas has been reported. RTK (receptor tyrosine kinase) and p53 signalling were found dysregulated in ∼90% and 86% of all primary tumours analysed, respectively. Here we report on the use of human-induced pluripotent stem cells (hiPSCs) for modelling gliomagenesis. Dysregulation of RTK and p53 signalling in hiP…

  本地源文件： `DOCX=prepared/docx/080-scifact-18399038.docx` `PDF=prepared/pdf/080-scifact-18399038.pdf`

首个可观测失败步骤：`rerank_final_top10`。

直接证据：

- Dense gold首名次=7
- Sparse gold首名次=3
- Hybrid-RRF gold首名次=1
- Hybrid-RRF+Rerank gold首名次=3
- 分类规则=rerank_reorder_harm
- 逐候选分数=未采集

推断：Top10截断、语义近邻竞争或Rerank排序偏好可能共同影响；query与gold最大词项Jaccard=0.0366。

其他可能解释：相关性标注不完整、gold摘要与claim粒度不同、分块边界或Top10截断；当前终态run不能排除这些因素（rerank_reorder_harm）。

反证实验：固定同一索引和query，额外留存Dense/Sparse候选Top100、融合逐项贡献、Rerank输入输出分数及chunk文本，再复跑该query验证首个内部失效点（rerank_reorder_harm）。

### queryId=956

问题：Pleiotropic coupling of GLP-1R to intracellular effectors promotes distinct profiles of cellular signaling.

Gold文档：

- `12956194` The Extracellular Surface of the GLP-1 Receptor Is a Molecular Trigger for Biased Agonism

  > Ligand-directed signal bias offers opportunities for sculpting molecular events, with the promise of better, safer therapeutics. Critical to the exploitation of signal bias is an understanding of the molecular events coupling ligand binding to intracellular signaling. Activation of class B G protein-coupled receptors is driven by interaction of the peptide N terminus with the receptor core. To understand how this drives signaling, we have used advanced analytical methods that enable separation of effects on pathway-specific signaling from those that modify agonist affinity and mapped the funct…

  本地源文件： `DOCX=prepared/docx/010-scifact-12956194.docx` `PDF=prepared/pdf/010-scifact-12956194.pdf`

| 变体 | Recall@10 | MRR@10 | nDCG@10 | Gold首名次 | 延迟ms | Top10文档ID（*为gold） |
|---|---:|---:|---:|---:|---:|---|
| dense | 1.000000 | 1.000000 | 1.000000 | 1 | 1587 | 12956194*, 31272411, 5304891, 1897324, 1469751, 30303335, 15928989, 13905670, 3475317 |
| sparse | 1.000000 | 1.000000 | 1.000000 | 1 | 1451 | 12956194*, 6173523, 31272411, 30303335, 16495649, 31715818, 9745001, 39381118, 10991183 |
| hybrid_rrf | 1.000000 | 1.000000 | 1.000000 | 1 | 1940 | 12956194*, 31272411, 30303335, 10991183, 26851674, 33499189, 3475317, 5531479 |
| hybrid_rrf_rerank | 1.000000 | 0.333333 | 0.500000 | 3 | 7232 | 10991183, 30303335, 12956194*, 33499189, 5531479, 31272411, 3475317, 26851674 |

首个失败步骤中的关键错误召回文档（前3条非gold）：

- rank=1 `10991183` The Rho GEFs LARG and GEF-H1 regulate the mechanical response to force on integrins（本地heading=`SCIFACT-EVIDENCE-10991183`）

  > How individual cells respond to mechanical forces is of considerable interest to biologists as force affects many aspects of cell behaviour. The application of force on integrins triggers cytoskeletal rearrangements and growth of the associated adhesion complex, resulting in increased cellular stiffness, also known as reinforcement. Although RhoA has been shown to play a role during reinforcement, the molecular mechanisms that regulate its activity are unknown. By combining biochemical and biophysical approaches, we identified two guanine nucleotide exchange factors (GEFs), LARG and GEF-H1, as…

  本地源文件： `DOCX=prepared/docx/076-scifact-10991183.docx` `PDF=prepared/pdf/076-scifact-10991183.pdf`
- rank=2 `30303335` Control of NFAT Isoform Activation and NFAT-Dependent Gene Expression through Two Coincident and Spatially Segregated Intracellular Ca2+ Signals（本地heading=`SCIFACT-EVIDENCE-30303335`）

  > Excitation-transcription coupling, linking stimulation at the cell surface to changes in nuclear gene expression, is conserved throughout eukaryotes. How closely related coexpressed transcription factors are differentially activated remains unclear. Here, we show that two Ca2+-dependent transcription factor isoforms, NFAT1 and NFAT4, require distinct sub-cellular InsP3 and Ca2+ signals for physiologically sustained activation. NFAT1 is stimulated by sub-plasmalemmal Ca2+ microdomains, whereas NFAT4 additionally requires Ca2+ mobilization from the inner nuclear envelope by nuclear InsP3 recepto…

  本地源文件： `DOCX=prepared/docx/197-scifact-30303335.docx` `PDF=prepared/pdf/197-scifact-30303335.pdf`
- rank=4 `33499189` Full activation of the T cell receptor requires both clustering and conformational changes at CD3.（本地heading=`SCIFACT-EVIDENCE-33499189`）

  > T cell receptor (TCR-CD3) triggering involves both receptor clustering and conformational changes at the cytoplasmic tails of the CD3 subunits. The mechanism by which TCRalphabeta ligand binding confers conformational changes to CD3 is unknown. By using well-defined ligands, we showed that induction of the conformational change requires both multivalent engagement and the mobility restriction of the TCR-CD3 imposed by the plasma membrane. The conformational change is elicited by cooperative rearrangements of two TCR-CD3 complexes and does not require accompanying changes in the structure of th…

  本地源文件： `DOCX=prepared/docx/156-scifact-33499189.docx` `PDF=prepared/pdf/156-scifact-33499189.pdf`

首个可观测失败步骤：`rerank_final_top10`。

直接证据：

- Dense gold首名次=1
- Sparse gold首名次=1
- Hybrid-RRF gold首名次=1
- Hybrid-RRF+Rerank gold首名次=3
- 分类规则=rerank_reorder_harm
- 逐候选分数=未采集

推断：Top10截断、语义近邻竞争或Rerank排序偏好可能共同影响；query与gold最大词项Jaccard=0.0779。

其他可能解释：相关性标注不完整、gold摘要与claim粒度不同、分块边界或Top10截断；当前终态run不能排除这些因素（rerank_reorder_harm）。

反证实验：固定同一索引和query，额外留存Dense/Sparse候选Top100、融合逐项贡献、Rerank输入输出分数及chunk文本，再复跑该query验证首个内部失效点（rerank_reorder_harm）。

### queryId=213

问题：CRP is not predictive of postoperative mortality following Coronary Artery Bypass Graft (CABG) surgery.

Gold文档：

- `13625993` Assessing the cost effectiveness of using prognostic biomarkers with decision models: case study in prioritising patients waiting for coronary artery surgery

  > OBJECTIVE To determine the effectiveness and cost effectiveness of using information from circulating biomarkers to inform the prioritisation process of patients with stable angina awaiting coronary artery bypass graft surgery. DESIGN Decision analytical model comparing four prioritisation strategies without biomarkers (no formal prioritisation, two urgency scores, and a risk score) and three strategies based on a risk score using biomarkers: a routinely assessed biomarker (estimated glomerular filtration rate), a novel biomarker (C reactive protein), or both. The order in which to perform cor…

  本地源文件： `DOCX=prepared/docx/180-scifact-13625993.docx` `PDF=prepared/pdf/180-scifact-13625993.pdf`

| 变体 | Recall@10 | MRR@10 | nDCG@10 | Gold首名次 | 延迟ms | Top10文档ID（*为gold） |
|---|---:|---:|---:|---:|---:|---|
| dense | 1.000000 | 1.000000 | 1.000000 | 1 | 1746 | 13625993*, 12991445, 4687948, 24088502, 13843341, 16760369 |
| sparse | 1.000000 | 1.000000 | 1.000000 | 1 | 1536 | 13625993*, 24088502, 17717391, 18872233 |
| hybrid_rrf | 1.000000 | 1.000000 | 1.000000 | 1 | 1745 | 13625993*, 24088502, 12991445, 4687948, 16760369 |
| hybrid_rrf_rerank | 1.000000 | 0.500000 | 0.630930 | 2 | 6448 | 24088502, 13625993*, 16760369, 12991445, 4687948 |

首个失败步骤中的关键错误召回文档（前3条非gold）：

- rank=1 `24088502` Clinical outcomes following institution of the Canadian universal leukoreduction program for red blood cell transfusions.（本地heading=`SCIFACT-EVIDENCE-24088502`）

  > CONTEXT A number of countries have implemented a policy of universal leukoreduction of their blood supply, but the potential role of leukoreduction in decreasing postoperative mortality and infection is unclear. OBJECTIVE To evaluate clinical outcomes following adoption of a national universal prestorage leukoreduction program for blood transfusions. DESIGN, SETTING, AND POPULATION Retrospective before-and-after cohort study conducted from August 1998 to August 2000 in 23 academic and community hospitals throughout Canada, enrolling 14 786 patients who received red blood cell transfusions foll…

  本地源文件： `DOCX=prepared/docx/139-scifact-24088502.docx` `PDF=prepared/pdf/139-scifact-24088502.pdf`
- rank=3 `16760369` Comparative determinants of 4-year cardiovascular event rates in stable outpatients at risk of or with atherothrombosis.（本地heading=`SCIFACT-EVIDENCE-16760369`）

  > CONTEXT Clinicians and trialists have difficulty with identifying which patients are highest risk for cardiovascular events. Prior ischemic events, polyvascular disease, and diabetes mellitus have all been identified as predictors of ischemic events, but their comparative contributions to future risk remain unclear. OBJECTIVE To categorize the risk of cardiovascular events in stable outpatients with various initial manifestations of atherothrombosis using simple clinical descriptors. DESIGN, SETTING, AND PATIENTS Outpatients with coronary artery disease, cerebrovascular disease, or peripheral…

  本地源文件： `DOCX=prepared/docx/179-scifact-16760369.docx` `PDF=prepared/pdf/179-scifact-16760369.pdf`
- rank=4 `12991445` Influence of smoking and plasma factors on patency of femoropopliteal vein grafts.（本地heading=`SCIFACT-EVIDENCE-12991445`）

  > OBJECTIVE To determine the effects of smoking, plasma lipids, lipoproteins, apolipoproteins, and fibrinogen on the patency of saphenous vein femoropopliteal bypass grafts at one year. DESIGN Prospective study of patients with saphenous vein femoropopliteal bypass grafts entered into a multicentre trial. SETTING Surgical wards, outpatient clinics, and home visits coordinated by two tertiary referral centres in London and Birmingham. PATIENTS 157 Patients (mean age 66.6 (SD 8.2) years), 113 with patent grafts and 44 with occluded grafts one year after bypass. MAIN OUTCOME MEASURE Cumulative perc…

  本地源文件： `DOCX=prepared/docx/014-scifact-12991445.docx` `PDF=prepared/pdf/014-scifact-12991445.pdf`

首个可观测失败步骤：`rerank_final_top10`。

直接证据：

- Dense gold首名次=1
- Sparse gold首名次=1
- Hybrid-RRF gold首名次=1
- Hybrid-RRF+Rerank gold首名次=2
- 分类规则=rerank_reorder_harm
- 逐候选分数=未采集

推断：Top10截断、语义近邻竞争或Rerank排序偏好可能共同影响；query与gold最大词项Jaccard=0.0779。

其他可能解释：相关性标注不完整、gold摘要与claim粒度不同、分块边界或Top10截断；当前终态run不能排除这些因素（rerank_reorder_harm）。

反证实验：固定同一索引和query，额外留存Dense/Sparse候选Top100、融合逐项贡献、Rerank输入输出分数及chunk文本，再复跑该query验证首个内部失效点（rerank_reorder_harm）。

## 输入SHA-256

- queries: `146e928420eabd22ee95322f1711cdee9bd42cfa456db44090a35e8c414eaf35`
- qrels: `2a808171a79832d5798afb879c2d912f5c8863b09c6427fe454f20dc2a025f73`
- documents: `7e1479ca549e3e48dd442b03770e88f160ef90334a8e18f09cfa6349fee24e08`
- documentMap: `8a93c2134c689d3fd78d90ddee9414b3a08bc43e20b56ef55d781ea9f61ef17b`
- run: `cc81197ce8d5f06856ce9b1b143fdfe4207b4abba0d07709e03632c0508f2ed1`
