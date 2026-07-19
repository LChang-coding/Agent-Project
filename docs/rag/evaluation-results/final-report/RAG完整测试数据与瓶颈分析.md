# RAG完整测试数据、瓶颈与失败因果报告

> 结论约束：本报告所有数字均由随附原始JSON/JSONL程序化生成；没有证据的项目明确标为未测。检索命中不等于最终答案正确。

## 一、最终结论

1. 当前SciFact检索指标最好的已测配置其实是`Dense`：Recall@10=0.797944、MRR@10=0.655835、nDCG@10=0.683385、MAP@10=0.641088。`Hybrid RRF + Rerank`是混合链路内部最好的配置，但四项指标仍全部低于Dense；不能宣称技术组件越多质量就越好。
2. Rerank保持Recall@10不变，却把MRR/nDCG/MAP分别提高0.079398/0.058705/0.075396；代价是质量run p50从1.851s升至12.408s。它改善67个query的排序，也伤害29个query；20个内部复测代表中为8改善、7伤害、5不变。
3. 在本次SciFact在线检索负载中，首个已证明的主导瓶颈是Reranker：并发4出现67.190s fallback，Reranker CPU峰值566.50%；稳定健康容量只证明到并发2。该结论不外推到大文件摄取或多租户全系统。
4. 三格式真实MinIO链路r6为15/15检索证据词项覆盖、0降级；MySQL child chunk/distinct vector point与Qdrant exact point一致，MinIO哈希一致。PDF摄取37.597s，其中Docling HTTP 34.163s，是本轮摄取主导阶段。
5. 页码链路已在独立r4真实复测中闭环：三页PDF的pageCount=3，6个章节数据库页码为1/1/1/2/2/3，30条查询citation全部与金标一致，5/5问题均召回其正确证据章节和页码。Markdown与当前Docling DOCX继续按页语义未知处理，没有猜页码。
6. 尚不能宣告完整答案质量闭环：SciFact没有gold answer；无答案题虽然能召回“文档未提供该值”的段落，但检索层仍返回5～6条候选，Agent是否拒绝编造尚未黑盒评测。

## 二、测试口径与有效数据

| 数据集/轮次 | 样本 | 用途 | 可用于什么结论 |
|---|---:|---|---|
| SciFact r11 | 300问题×4=1200 | Dense/Sparse/Hybrid/Rerank消融 | Recall/MRR/nDCG/MAP与同轮延迟 |
| 内部诊断 | 20问题×4=80 | 候选阶段轨迹 | 首个可观测失效步骤、Rerank同请求前后 |
| 稳定性能r1+r2 | 320 measured，另有warmup | 并发1/2、顺序反转 | 已验证健康容量和延迟范围 |
| 并发4边界 | 共199条measured（并发1=80、2=80、4=39） | 容量失败定位 | 并发4不能作为稳定分位数，只作失败边界 |
| 三格式r6 | 3文件、15答案问题、3无答案探针 | 真实MinIO摄取/召回 | 格式功能、三端一致性、小文件单线程性能 |
| 页码r4 | 同一3文件、15答案问题、PDF 6章节金标 | 真实MinIO重新摄取/召回 | PDF页码准确性、未知页语义不猜测 |

## 三、RAG技术点前后差异

| 配置 | Recall@10 | MRR@10 | nDCG@10 | MAP@10 | p50/p95/max | 错误/降级/空 |
|---|---:|---:|---:|---:|---:|---:|
| sparse | 0.487778 | 0.321922 | 0.355442 | 0.310521 | 717/2380/6949 ms | 0/0/0 |
| dense | 0.797944 | 0.655835 | 0.683385 | 0.641088 | 1264/4016/10882 ms | 0/0/0 |
| hybrid_rrf | 0.750667 | 0.566630 | 0.604539 | 0.552843 | 1851/4704/9548 ms | 0/0/0 |
| hybrid_rrf_rerank | 0.750667 | 0.646028 | 0.663244 | 0.628238 | 12408/21100/89563 ms | 0/0/0 |

关键差值：

- Sparse→Hybrid：Recall@10 +0.262889，MRR@10 +0.244708。Dense通道补回了81个Sparse漏召回而Hybrid命中的query。
- Dense→Hybrid：Recall@10 -0.047278，MRR@10 -0.089205。Hybrid总体反而下降。另有98个query是Dense命中而Sparse未命中，这证明Dense通道在本数据集更强；它不等价于98个Hybrid漏召回，Hybrid相对Dense的具体损失必须按逐query差值另算。
- Hybrid→Hybrid+Rerank：Recall@10 +0.000000，MRR@10 +0.079398，nDCG@10 +0.058705，MAP@10 +0.075396。Rerank改变顺序，不补回已被Top10截掉的文档。

## 四、非互斥失败标签与首个失效步骤

这些是可重叠布尔标签，同一query可以进入多类，计数不可相加当作300题的互斥分布。`dense_only_success`表示Dense命中且Sparse未命中，`sparse_only_success`反之；`rerank_rescue/harm`表示Top10命中集合进出，`rerank_reorder_gain/harm`表示两者都命中时MRR顺序改变。

| 标签 | 全量query数 | 布尔语义 |
|---|---:|---|
| dense_miss_hybrid_hit | 10 | Dense未命中且Hybrid命中 |
| sparse_miss_hybrid_hit | 81 | Sparse未命中且Hybrid命中 |
| rerank_rescue | 0 | Rerank前Top10未命中、后命中 |
| rerank_harm | 0 | Rerank前Top10命中、后未命中 |
| dense_only_success | 98 | Dense命中且Sparse未命中（不表示Hybrid失败） |
| sparse_only_success | 3 | Sparse命中且Dense未命中（不表示Hybrid失败） |
| persistent_miss | 45 | 四个变体Top10均未命中 |
| rerank_reorder_gain | 67 | 前后均命中且MRR提高 |
| rerank_reorder_harm | 29 | 前后均命中且MRR降低 |

内部80条阶段证据的首个完全损失：

- `DENSE_RAW_TOPK_MISS`：2条。
- `FUSION_THRESHOLD_OR_TOPK_LOSS`：17条。
- `NONE`：52条。
- `RAW_RECALL_TOTAL_MISS`：4条。
- `SPARSE_RAW_TOPK_MISS`：5条。

这说明最常见的可观测损失不是Qdrant完全找不到，而是候选进入融合后被联合threshold/TopK裁掉；但当前埋点不能继续区分是阈值还是TopK，不能越过证据下结论。

## 五、召回失败文档与因果链（代表案例）

下面每类展示1个确定性代表；完整附件含21个代表case、全部Gold截断摘要、各case前三条非Gold截断摘要及各变体Top10文档ID，见[召回失败案例全集](../scifact-r11-failure-cases.md)。逐候选内部复测分数与阶段轨迹见[内部阶段失败证据](../scifact-r11-internal-failure-analysis.md)。

### dense_miss_hybrid_hit / queryId=598

问题：Incidence rates of cervical cancer have increased due to nationwide screening programs based primarily on cytology to detect uterine cervical cancer.

Gold文档：

- `25742130` Mass screening programmes and trends in cervical cancer in Finland and the Netherlands.

  > With respect to cervical cancer management, Finland and the Netherlands are comparable in relevant characteristics, e.g., fertility rate, age-of-mother at first birth and a national screening programme for several years. The aim of this study is to compare trends in incidence of and mortality from cervical cancer in Finland and the Netherlands in relation to the introduction and intensity of the screening programmes. Therefore, incidence and mortality rates were calculated using the Cancer Registries of Finland and the Netherlands. Data on screening intensity were obtained from the Finnish Can…

问题变体：`dense`；Recall@10=0.000000，MRR@10=0.000000。

实际排在前面的错误文档：

- rank=1 `9764256` Human papillomavirus testing for the detection of high-grade cervical intraepithelial neoplasia and cancer: final results of the POBASCAM randomised controlled trial.

  > BACKGROUND Human papillomavirus (HPV) testing is more sensitive for the detection of high-grade cervical lesions than is cytology, but detection of HPV by DNA screening in two screening rounds 5 years apart has not been assessed. The aim of this study was to assess whether HPV DNA testing in the first screen decreases detection of cervical intraepithelial neoplasia (CIN) grade 3 or worse, CIN grade 2 or worse, and cervical cancer in the second screening. METHODS In this randomised trial, women aged 29-56 years participating in the cervical screening programme in the Netherlands were randomly a…
- rank=2 `6561200` Efficacy of HPV DNA testing with cytology triage and/or repeat HPV DNA testing in primary cervical cancer screening.

  > BACKGROUND Primary cervical screening with both human papillomavirus (HPV) DNA testing and cytological examination of cervical cells with a Pap test (cytology) has been evaluated in randomized clinical trials. Because the vast majority of women with positive cytology are also HPV DNA positive, screening strategies that use HPV DNA testing as the primary screening test may be more effective. METHODS We used the database from the intervention arm (n = 6,257 women) of a population-based randomized trial of double screening with cytology and HPV DNA testing to evaluate the efficacy of 11 possible…
- rank=3 `27873158` Efficacy of human papillomavirus testing for the detection of invasive cervical cancers and cervical intraepithelial neoplasia: a randomised controlled trial.

  > BACKGROUND Human papillomavirus (HPV) testing is known to be more sensitive, but less specific than cytology for detecting cervical intraepithelial neoplasia (CIN). We assessed the efficacy of cervical-cancer screening policies that are based on HPV testing. METHODS Between March, 2004, and December, 2004, in two separate recruitment phases, women aged 25-60 years were randomly assigned to conventional cytology or to HPV testing in combination with liquid-based cytology (first phase) or alone (second phase). Randomisation was done by computer in two screening centres and by sequential opening…

首个终态可观测失败：`dense_final_top10`。内部首个完全损失：`fusion/FUSION_THRESHOLD_OR_TOPK_LOSS`。

直接事实：Dense gold首名次=Top10未命中；Sparse gold首名次=1；Hybrid-RRF gold首名次=2；Hybrid-RRF+Rerank gold首名次=6；分类规则=dense_miss_hybrid_hit；质量run未采集逐候选分数；内部复测已采集并用于阶段定位

内部复测Gold轨迹摘录：

- stage=dense_raw rank=14 outcome=returned_by_vector_store dense=0.83227265 sparse=None fusion=None rerank=None

因果推断（可证伪）：Dense语义向量把主题相近干扰文档排在gold之前，而精确术语帮助Sparse命中；最大词项Jaccard=0.1250。

替代解释：相关性标注不完整、gold摘要与claim粒度不同、分块边界或Top10截断；当前终态run不能排除这些因素（dense_miss_hybrid_hit）。

复证实验：固定同一索引和query，额外留存Dense/Sparse候选Top100、融合逐项贡献、Rerank输入输出分数及chunk文本，再复跑该query验证首个内部失效点（dense_miss_hybrid_hit）。

### sparse_miss_hybrid_hit / queryId=216

问题：CX3CR1 on the Th2 cells impairs T cell survival

Gold文档：

- `21366394` CX3CR1 is required for airway inflammation by promoting T helper cell survival and maintenance in inflamed lung

  > Allergic asthma is a T helper type 2 (T(H)2)-dominated disease of the lung. In people with asthma, a fraction of CD4(+) T cells express the CX3CL1 receptor, CX3CR1, and CX3CL1 expression is increased in airway smooth muscle, lung endothelium and epithelium upon allergen challenge. Here we found that untreated CX3CR1-deficient mice or wild-type (WT) mice treated with CX3CR1-blocking reagents show reduced lung disease upon allergen sensitization and challenge. Transfer of WT CD4(+) T cells into CX3CR1-deficient mice restored the cardinal features of asthma, and CX3CR1-blocking reagents prevented…

问题变体：`sparse`；Recall@10=0.000000，MRR@10=0.000000。

实际排在前面的错误文档：

- rank=1 `11666252` Maintaining the norm: T-cell homeostasis

  > The persistence of naive and memory T cells has long been of interest to immunologists, but the factors that influence the survival and homeostasis of these subsets have remained obscure. In recent years, it has become evident that the homeostasis of both naive and memory T-cell pools is highly dynamic and tightly regulated by internal stimuli, including cytokines and self-peptide–MHC ligands for the T-cell receptor. These homeostatic mechanisms might have a vital influence on the capacity of the T-cell pool to respond to both foreign and self-antigens.
- rank=2 `22210434` The kinase TAK1 integrates antigen and cytokine receptor signaling for T cell development, survival and function

  > The kinase TAK1 is critical for innate and B cell immunity. The function of TAK1 in T cells is unclear, however. We show here that T cell–specific deletion of the gene encoding TAK1 resulted in reduced development of thymocytes, especially of regulatory T cells expressing the transcription factor Foxp3. In mature thymocytes, TAK1 was required for interleukin 7–mediated survival and T cell receptor–dependent activation of transcription factor NF-κB and the kinase Jnk. In effector T cells, TAK1 was dispensable for T cell receptor–dependent NF-κB activation and cytokine production, but was import…
- rank=3 `20610557` Alkylating agent melphalan augments the efficacy of adoptive immunotherapy using tumor-specific CD4+ T cells.

  > In recent years, the immune-potentiating effects of some widely used chemotherapeutic agents have been increasingly appreciated. This provides a rationale for combining conventional chemotherapy with immunotherapy strategies to achieve durable therapeutic benefits. Previous studies have implicated the immunomodulatory effects of melphalan, an alkylating agent commonly used to treat multiple myeloma, but the underlying mechanisms remain obscure. In the present study, we investigated the impact of melphalan on endogenous immune cells as well as adoptively transferred tumor-specific CD4(+) T cell…

首个终态可观测失败：`sparse_final_top10`。内部首个完全损失：`fusion/FUSION_THRESHOLD_OR_TOPK_LOSS`。

直接事实：Dense gold首名次=1；Sparse gold首名次=Top10未命中；Hybrid-RRF gold首名次=1；Hybrid-RRF+Rerank gold首名次=1；分类规则=sparse_miss_hybrid_hit；质量run未采集逐候选分数；内部复测已采集并用于阶段定位

内部复测Gold轨迹摘录：

- stage=sparse_raw rank=14 outcome=returned_by_vector_store dense=None sparse=0.28246087 fusion=None rerank=None

因果推断（可证伪）：Sparse对同义改写和词形差异敏感，缺少语义改写鲁棒性；该解释仍需词法归一化/BM25参数消融反证。

替代解释：相关性标注不完整、gold摘要与claim粒度不同、分块边界或Top10截断；当前终态run不能排除这些因素（sparse_miss_hybrid_hit）。

复证实验：固定同一索引和query，额外留存Dense/Sparse候选Top100、融合逐项贡献、Rerank输入输出分数及chunk文本，再复跑该query验证首个内部失效点（sparse_miss_hybrid_hit）。

### dense_only_success / queryId=1014

问题：Rapamycin decreases the concentration of triacylglycerols in fruit flies.

Gold文档：

- `6277638` Mechanisms of Life Span Extension by Rapamycin in the Fruit Fly Drosophila melanogaster

  > The target of rapamycin (TOR) pathway is a major nutrient-sensing pathway that, when genetically downregulated, increases life span in evolutionarily diverse organisms including mammals. The central component of this pathway, TOR kinase, is the target of the inhibitory drug rapamycin, a highly specific and well-described drug approved for human use. We show here that feeding rapamycin to adult Drosophila produces the life span extension seen in some TOR mutants. Increase in life span by rapamycin was associated with increased resistance to both starvation and paraquat. Analysis of the underlyi…

问题变体：`sparse`；Recall@10=0.000000，MRR@10=0.000000。

实际排在前面的错误文档：

- rank=1 `10530014` A point mutation in KINDLIN3 ablates activation of three integrin subfamilies in humans

  > Monogenic deficiency diseases provide unique opportunities to define the contributions of individual molecules to human physiology and to identify pathologies arising from their dysfunction. Here we describe a deficiency disease in two human siblings that presented with severe bleeding, frequent infections and osteopetrosis at an early age. These symptoms are consistent with but more severe than those reported for people with leukocyte adhesion deficiency III (LAD-III). Mechanistically, these symptoms arose from an inability to activate the integrins expressed on hematopoietic cells, including…
- rank=2 `36271512` T-cell activation.

  > INTRODUCTION • • CELLULAR AND MOLECULAR REQUIREMENTS FOR T-CELL ACTIVATION . The T-Cell Antigen Receptor Complex . . . .. . . . ..... . . . . . . . . . . . . . . . . ...... . . . T-Cell Activation by Antibodies and Leetins . . . . . . . . . . .. . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . Other Cell Surface Structures (Accessory Molecules) Involved in Antigen Recognition and Activation . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . .…
- rank=3 `8065561` Specific and cooperative binding of E. coli single-stranded DNA binding protein to mRNA.

  > Fluorometric titration of E. coli single-stranded DNA binding protein with various RNAs showed that the protein specifically and cooperatively binds to its own mRNA. The binding inhibited in vitro expression of ssb and bla but not nusA. This inhibition takes place at a physiological concentration of SSB. The function of the protein in gene regulation is discussed.

首个终态可观测失败：`sparse_final_top10`。内部首个完全损失：`fusion/FUSION_THRESHOLD_OR_TOPK_LOSS`。

直接事实：Dense gold首名次=1；Sparse gold首名次=Top10未命中；Hybrid-RRF gold首名次=2；Hybrid-RRF+Rerank gold首名次=1；分类规则=dense_only_success；质量run未采集逐候选分数；内部复测已采集并用于阶段定位

内部复测Gold轨迹摘录：

- stage=sparse_raw rank=49 outcome=returned_by_vector_store dense=None sparse=0.33438396 fusion=None rerank=None

因果推断（可证伪）：Sparse对同义改写和词形差异敏感，缺少语义改写鲁棒性；该解释仍需词法归一化/BM25参数消融反证。

替代解释：相关性标注不完整、gold摘要与claim粒度不同、分块边界或Top10截断；当前终态run不能排除这些因素（dense_only_success）。

复证实验：固定同一索引和query，额外留存Dense/Sparse候选Top100、融合逐项贡献、Rerank输入输出分数及chunk文本，再复跑该query验证首个内部失效点（dense_only_success）。

### persistent_miss / queryId=1

问题：0-dimensional biomaterials show inductive properties.

Gold文档：

- `31715818` New opportunities: the use of nanotechnologies to manipulate and track stem cells.

  > Nanotechnologies are emerging platforms that could be useful in measuring, understanding, and manipulating stem cells. Examples include magnetic nanoparticles and quantum dots for stem cell labeling and in vivo tracking; nanoparticles, carbon nanotubes, and polyplexes for the intracellular delivery of genes/oligonucleotides and protein/peptides; and engineered nanometer-scale scaffolds for stem cell differentiation and transplantation. This review examines the use of nanotechnologies for stem cell tracking, differentiation, and transplantation. We further discuss their utility and the potentia…

问题变体：`hybrid_rrf_rerank`；Recall@10=0.000000，MRR@10=0.000000。

实际排在前面的错误文档：

- rank=1 `86217760` The Self-Incompatibility Genes of Brassica: Expression and Use in Genetic Ablation of Floral Tissues

  > INTRODUCTION . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . .. . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . 394 POLLINATION AND POLLEN TUBE GROWTH . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . 395 Interaction s between the M ale G ameto phyte and Pistil . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . 395 SelfIncom patibili ty Systems: Gameto phytic and S poro phyti c Determin ation of Pollen Phenoty pe . . . . . . . . . . . . . . . . .. . . . . . . . . . . .…
- rank=2 `12207167` Adverse effects of excessive consumption of amino acids.

  > PHENYLALANINE TOXICITY 158 Developing the 0. -M ethylphenylalanine Model. . . . . . .. . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . 160 Use of the a-Methyl phenylalanine Model in Brain Protein Synthesis . . . . . . . . . . . . . . . . . . . 161 TYROSINE TOXICITY 162 General Nutritional Observations . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . 162 Factors Affecting Tissue Concentrations of Tyrosine . ... .. .. .. ...... . . . . . . .. . . 163 Probable Cause of Tyrosine Toxicity . . .…
- rank=3 `19685306` Orientationally invariant indices of axon diameter and density from diffusion MRI.

  > This paper proposes and tests a technique for imaging orientationally invariant indices of axon diameter and density in white matter using diffusion magnetic resonance imaging. Such indices potentially provide more specific markers of white matter microstructure than standard indices from diffusion tensor imaging. Orientational invariance allows for combination with tractography and presents new opportunities for mapping brain connectivity and quantifying disease processes. The technique uses a four-compartment tissue model combined with an optimized multishell high-angular-resolution pulsed-g…

首个终态可观测失败：`dense_and_sparse_final_top10`。内部首个完全损失：`raw_union/RAW_RECALL_TOTAL_MISS`。

直接事实：Dense gold首名次=Top10未命中；Sparse gold首名次=Top10未命中；Hybrid-RRF gold首名次=Top10未命中；Hybrid-RRF+Rerank gold首名次=Top10未命中；分类规则=persistent_miss；质量run未采集逐候选分数；内部复测已采集并用于阶段定位

因果推断（可证伪）：该内部复测最早已观察到Gold不在Dense/Sparse原始Top100并集中，因此不能归因最终Top10或Rerank；尚无法区分索引覆盖、向量/词项表示、分块边界或query-gold粒度。

替代解释：qrels可能不完整，Gold正文与claim粒度也可能不匹配；但最终Top10截断和Rerank已发生在原始并集漏召回之后，不是该次复测的首因。

复证实验：核验Gold对应chunk确实写入同一generation与Qdrant payload，再分别扩大Dense/Sparse原始TopK并替换Embedding/词法归一化，观察Gold首次出现位置。

### rerank_reorder_harm / queryId=956

问题：Pleiotropic coupling of GLP-1R to intracellular effectors promotes distinct profiles of cellular signaling.

Gold文档：

- `12956194` The Extracellular Surface of the GLP-1 Receptor Is a Molecular Trigger for Biased Agonism

  > Ligand-directed signal bias offers opportunities for sculpting molecular events, with the promise of better, safer therapeutics. Critical to the exploitation of signal bias is an understanding of the molecular events coupling ligand binding to intracellular signaling. Activation of class B G protein-coupled receptors is driven by interaction of the peptide N terminus with the receptor core. To understand how this drives signaling, we have used advanced analytical methods that enable separation of effects on pathway-specific signaling from those that modify agonist affinity and mapped the funct…

问题变体：`hybrid_rrf_rerank`；Recall@10=1.000000，MRR@10=0.200000。

实际排在前面的错误文档：

- rank=1 `31107919` Differential Requirement of the Extracellular Domain in Activation of Class B G Protein-coupled Receptors.

  > G protein-coupled receptors (GPCRs) from the secretin-like (class B) family are key players in hormonal homeostasis and are important drug targets for the treatment of metabolic disorders and neuronal diseases. They consist of a large N-terminal extracellular domain (ECD) and a transmembrane domain (TMD) with the GPCR signature of seven transmembrane helices. Class B GPCRs are activated by peptide hormones with their C termini bound to the receptor ECD and their N termini bound to the TMD. It is thought that the ECD functions as an affinity trap to bind and localize the hormone to the receptor…
- rank=2 `7433668` Preexisting helminth infection induces inhibition of innate pulmonary anti-tuberculosis defense by engaging the IL-4 receptor pathway

  > Tuberculosis and helminthic infections coexist in many parts of the world, yet the impact of helminth-elicited Th2 responses on the ability of the host to control Mycobacterium tuberculosis (Mtb) infection has not been fully explored. We show that mice infected with the intestinal helminth Nippostrongylus brasiliensis (Nb) exhibit a transitory impairment of resistance to airborne Mtb infection. Furthermore, a second dose of Nb infection substantially increases the bacterial burden in the lungs of co-infected mice. Interestingly, the Th2 response in the co-infected animals did not impair the on…
- rank=3 `3127341` Polymorphism and ligand dependent changes in human glucagon-like peptide-1 receptor (GLP-1R) function: allosteric rescue of loss of function mutation.

  > The glucagon-like peptide-1 receptor (GLP-1R) is a key physiological regulator of insulin secretion and a major therapeutic target for the treatment of type II diabetes. However, regulation of GLP-1R function is complex with multiple endogenous peptides that interact with the receptor, including full-length (1-37) and truncated (7-37) forms of GLP-1 that can exist in an amidated form (GLP-1(1-36)NH₂ and GLP-1(7-36)NH₂) and the related peptide oxyntomodulin. In addition, the GLP-1R possesses exogenous agonists, including exendin-4, and the allosteric modulator, compound 2 (6,7-dichloro-2-methyl…

首个终态可观测失败：`rerank_final_top10`。内部首个完全损失：`未观察到Gold完全损失`。

直接事实：Dense gold首名次=6；Sparse gold首名次=6；Hybrid-RRF gold首名次=1；Hybrid-RRF+Rerank gold首名次=5；分类规则=rerank_reorder_harm；质量run未采集逐候选分数；内部复测已采集并用于阶段定位

内部复测Gold轨迹摘录：

- stage=rerank_input rank=1 outcome=sent_to_reranker dense=0.847072 sparse=0.325567 fusion=0.9242424242424242 rerank=None
- stage=rerank_output rank=5 outcome=kept_after_rerank dense=0.847072 sparse=0.325567 fusion=0.9242424242424242 rerank=0.030666487

因果推断（可证伪）：阶段因果事实：同一请求的Rerank把Gold名次从12956194:1→5，MRR变化-0.800000；Rerank为何偏好竞争文档仍需模型/文本对照实验。

替代解释：Gold与竞争文档的标注粒度或相关性定义可能和Reranker训练偏好不同；但本次名次下降确实发生在同一请求的rerank_input→rerank_output。

复证实验：固定同一10个输入候选，记录完整文本与Rerank分数，替换/关闭Reranker并重复评分。

## 六、性能、容量与瓶颈

两轮稳定结果分别保留，未合并成伪单轮：

| run | 并发 | 配置 | n | p50 | p95 | max | Rerank p95 |
|---|---:|---|---:|---:|---:|---:|---:|
| scifact-load-stable-r1-ebbe5d0 | 1 | sparse | 20 | 276 | 582 | 612 | 0 |
| scifact-load-stable-r1-ebbe5d0 | 1 | hybrid_rrf | 20 | 1153 | 2439 | 3724 | 0 |
| scifact-load-stable-r1-ebbe5d0 | 1 | hybrid_rrf_rerank | 20 | 15054 | 24862 | 31353 | 23717 |
| scifact-load-stable-r1-ebbe5d0 | 1 | dense | 20 | 1194 | 2535 | 3792 | 0 |
| scifact-load-stable-r1-ebbe5d0 | 2 | sparse | 20 | 312 | 583 | 595 | 0 |
| scifact-load-stable-r1-ebbe5d0 | 2 | hybrid_rrf | 20 | 1173 | 2270 | 2382 | 0 |
| scifact-load-stable-r1-ebbe5d0 | 2 | hybrid_rrf_rerank | 20 | 20984 | 33212 | 37055 | 31806 |
| scifact-load-stable-r1-ebbe5d0 | 2 | dense | 20 | 892 | 1691 | 1833 | 0 |
| scifact-load-stable-r2-48f9099 | 2 | hybrid_rrf_rerank | 20 | 18210 | 28004 | 28663 | 27300 |
| scifact-load-stable-r2-48f9099 | 2 | hybrid_rrf | 20 | 844 | 1219 | 1280 | 0 |
| scifact-load-stable-r2-48f9099 | 2 | sparse | 20 | 257 | 540 | 570 | 0 |
| scifact-load-stable-r2-48f9099 | 2 | dense | 20 | 572 | 1241 | 1259 | 0 |
| scifact-load-stable-r2-48f9099 | 1 | hybrid_rrf_rerank | 20 | 10966 | 15169 | 16263 | 14351 |
| scifact-load-stable-r2-48f9099 | 1 | hybrid_rrf | 20 | 1030 | 1808 | 1858 | 0 |
| scifact-load-stable-r2-48f9099 | 1 | sparse | 20 | 242 | 435 | 707 | 0 |
| scifact-load-stable-r2-48f9099 | 1 | dense | 20 | 500 | 828 | 875 | 0 |

并发4门禁失败样本：queryId=1024，配置=hybrid_rrf_rerank，HTTP=200，耗时=67190ms，降级原因=`rerank_fallback:profile_9a6bf176635448799c5b219d7765bc46`。Reranker CPU峰值=566.50%，内存占比峰值=67.07%；容器前后无restart/OOM。

瓶颈优先级：

1. **Reranker CPU推理与排队**：事实—并发4出现67.190s降级回退；稳定轮Rerank占完整链路绝大部分延迟。 有证据支持的解释/待验证假设—Top10按3/3/3/1串行子批是代码层候选解释；Semaphore等待与远端排队各自贡献尚未分段测量。 影响—当前完整Rerank链路只验证到并发2健康。 优化—合并批次、异步批处理/动态batch、缓存与只重排高不确定查询；完成后重跑并发1/2/4。
2. **Docling PDF解析**：事实—r6 PDF摄取37.597s，Docling单次HTTP 34.163s；Docling CPU峰值461.93%。 有证据支持的解释/待验证假设—Docling调用占总墙钟约90.9%；其余约3.434s没有阶段分段，不能分摊给Java、MinIO或向量写入。 影响—PDF摄取显著慢于Markdown 3.056s与DOCX 6.094s。 优化—内容哈希去重、解析缓存、格式快速路径、独立解析队列；用多页/表格PDF复测。
3. **融合TopK/阈值召回损失**：事实—80条内部诊断中17条首个完全损失位于fusion threshold/TopK联合步骤。 有证据支持的解释/待验证假设—当前轨迹将threshold与TopK合并，尚不能进一步分离两者。 影响—部分Gold在原始候选存在但融合后消失。 优化—拆分threshold与TopK轨迹，调大fusion候选并做按query类型的权重/阈值校准。
4. **DOCX固定页语义缺失**：事实—r4三页PDF的6个章节、30条查询citation和5个问题证据章节均通过页码金标；同轮DOCX的Docling pages为空，数据库与citation保持null。 有证据支持的解释/待验证假设—流式DOCX在Docling 1.26.0响应中没有page provenance；当前证据不能把它解释为解析丢失或0页。 影响—PDF页码链路已闭环，但DOCX仍不能提供固定页审计。 优化—若业务刚需DOCX页码，先转换为固定版式PDF或引入能输出版式页span的解析器，再用同一金标门禁复测。

## 七、Markdown/DOCX/PDF真实链路

| 格式 | 摄取ms | attempt | 字符 | child块/Qdrant点 | 页数字段 | 查询p50/p95/max | MinIO原件SHA |
|---|---:|---:|---:|---:|---:|---:|---|
| docx | 6094 | 1 | 1428 | 6/6 | 0（未知，未闭环） | 3396/4588/4588 | `6c7c76fb3e5c66199d4161c5935ac997516c561cca74971d6cd616658756cc52` |
| markdown | 3056 | 1 | 1232 | 5/5 | 0（未知，未闭环） | 2264/2333/2333 | `3a0a294b67c737170adf7c50c831a06ef765a53eeae0e22e33fdc73692e97898` |
| pdf | 37597 | 1 | 1375 | 6/6 | 0（未知，未闭环） | 2309/3034/3034 | `5fd5a95d70b55341861538377dd0818d9c5d5ce8eb928879fc4cd369f6c66448` |

r6资源采样：21个远端样本、46个Java样本。Docling CPU峰值461.93%，Reranker 408.24%，Embedding 359.77%；Java CPU峰值14.9%、RSS峰值566608KiB，前后容器0重启、无OOM。单次PDF Docling日志为34163ms，因此其37.597s摄取耗时主要由解析占据。

### 页码修复前后与真实金标

r1在业务上传前因测试启动脚本误取MinIO账号字段失败；r2在DOCX遇到`pages={}`时被错误判为非法；修正空页为未知后，r3完成15/15，但连续H2被旧标题栈错误嵌套，PDF只有文档标题能匹配页码。r4改用真实标题level出栈后重新摄取同一份PDF，以下结果全部来自MySQL chunk与原始HTTP citation，不是单元测试推断。

| 格式 | r4摄取ms | pageCount语义 | child块/Qdrant点 | 查询citation | 页码门禁 | 对应文档 |
|---|---:|---|---:|---:|---|---|
| docx | 13381 | 未知（数据库/citation均null） | 6/6 | 30 | 未猜测页码 | [源文档](../../evaluation-data/format-e2e/format-fidelity.docx) |
| markdown | 9824 | 未知（数据库/citation均null） | 5/5 | 25 | 未猜测页码 | [源文档](../../evaluation-data/format-e2e/format-fidelity.md) |
| pdf | 62539 | 3页（固定） | 6/6 | 30 | 6章节、全部citation、5问题证据章节均匹配 | [源文档](../../evaluation-data/format-e2e/format-fidelity.pdf) |

PDF章节金标与数据库实值：

| 章节 | pageFrom/pageTo |
|---|---:|
| RAG Format Fidelity Observatory Manual | 1/1 |
| Identity and access | 1/1 |
| Sensor limits | 1/1 |
| Emergency procedure | 2/2 |
| Cross-page continuity | 2/2 |
| Deliberate omissions | 3/3 |

页码失败的因果链是：Docling JSON本身已有正确provenance → Java同时拿到Markdown H2与JSON level=1 → 旧代码按栈长度出栈，把连续H2错误嵌套 → 文本路径不等导致章节页码为null → r4按真实level弹栈后路径一致，数据库与citation金标全部恢复。DOCX则停在更早的解析输出阶段：Docling响应没有pages/provenance，因此系统保留null；这不是同一个标题栈问题。

无答案探针：

| 格式 | 问题 | 返回citation数 | Top heading | 判定 |
|---|---|---:|---|---|
| markdown | What is the launch mass of Aurora Finch Observatory? | 5 | RAG Format Fidelity Observatory Manual / Deliberate omissions | retrieval-only; LLM拒答正确性未评测 |
| docx | What is the launch mass of Aurora Finch Observatory? | 6 | RAG Format Fidelity Observatory Manual / Identity and access | retrieval-only; LLM拒答正确性未评测 |
| pdf | What is the launch mass of Aurora Finch Observatory? | 6 | RAG Format Fidelity Observatory Manual / Deliberate omissions | retrieval-only; LLM拒答正确性未评测 |

## 八、上线前优化与复测门槛

1. Reranker把候选批次由3提升至服务允许且经过内存验证的批量，减少4次串行HTTP；加入query级不确定性门控与短TTL缓存。门槛：并发4至少两轮、每变体≥100 measured、0 fallback，且MRR下降不超过0.005。
2. 融合阶段拆开threshold和TopK埋点，对Dense/Sparse权重、fusionTopK做网格消融。门槛：Recall@10不得低于当前Dense 0.797944，同时报告MRR/延迟代价。
3. Docling按内容哈希缓存解析结果，并分离PDF重任务队列。门槛：真实多页/表格PDF至少30份，报告p50/p95、页面/表格保真和失败重试。
4. PDF页span已贯穿解析、chunk、Qdrant payload和citation并通过单份三页金标；下一门槛是至少30份多页/表格/扫描PDF以及引用回源黑盒。DOCX若刚需固定页码，需增加固定版式转换或替换解析器后用相同金标门禁。
5. 增加有gold answer的端到端Agent评测，至少计算Answer Correctness、Faithfulness、引用精确率/召回率和无答案拒答率；否则不能把当前检索报告当答案质量报告。

## 九、明确未测与证据限制

- SciFact只评测检索，不含标准答案，因此未测Faithfulness、Answer Correctness和幻觉率。
- r6格式题只验证证据词项是否在返回上下文，不等同于最终LLM回答正确。
- 无答案探针仍会返回相关候选；是否正确拒答必须在Agent最终回答黑盒中另测。
- r6每格式仅一个小文件、单Worker、单上传/查询线程，不代表大文件、多租户或长时容量。
- 内部诊断为20个确定性代表问题，不是300问题全量内部轨迹。
- fusion threshold与TopK尚未分开留痕；PDF页码已闭环，但Markdown和当前Docling DOCX的页数仍是未知而非0页。

## 十、证据索引与复算

机器总账：[rag-final-evidence-ledger.json](rag-final-evidence-ledger.json)。关键原始证据均已从`/tmp`固化进项目`docs/rag/evaluation-results/`，总账记录每个输入的SHA-256与字节数。

```bash
python3 ai-agent-scaffold-benchmark/scripts/build-rag-final-report.py \
  --project-root . \
  --out-dir /tmp/rag-final-report-recomputed
cmp docs/rag/evaluation-results/final-report/rag-final-evidence-ledger.json \
    /tmp/rag-final-report-recomputed/rag-final-evidence-ledger.json
cmp docs/rag/evaluation-results/final-report/RAG完整测试数据与瓶颈分析.md \
    /tmp/rag-final-report-recomputed/RAG完整测试数据与瓶颈分析.md
```
