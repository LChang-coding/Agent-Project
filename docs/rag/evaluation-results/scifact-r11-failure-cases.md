# RAG召回失败案例可复算报告

生成器：rag-failure-case-v1；查询数：300；run记录数：1200。

## 证据边界

- run只保存最终Top10文档ID，没有逐候选分数或Dense/Sparse内部候选ID；对应字段明确标记为未采集。
- 首个失败步骤是基于四个消融终态排名的首个可观测步骤，不等同于内部算子级因果证明。
- 词项重合只用于提出可证伪推断，不作为失败原因的直接证明。

## 分类总账

| 类别 | 全量案例数 | 展示数 |
|---|---:|---:|
| dense_miss_hybrid_hit | 10 | 3 |
| sparse_miss_hybrid_hit | 81 | 3 |
| rerank_rescue | 0 | 0 |
| rerank_harm | 0 | 0 |
| dense_only_success | 98 | 3 |
| sparse_only_success | 3 | 3 |
| persistent_miss | 45 | 3 |
| rerank_reorder_gain | 67 | 3 |
| rerank_reorder_harm | 29 | 3 |

## dense_miss_hybrid_hit

### queryId=598

问题：Incidence rates of cervical cancer have increased due to nationwide screening programs based primarily on cytology to detect uterine cervical cancer.

Gold文档：

- `25742130` Mass screening programmes and trends in cervical cancer in Finland and the Netherlands.

  > With respect to cervical cancer management, Finland and the Netherlands are comparable in relevant characteristics, e.g., fertility rate, age-of-mother at first birth and a national screening programme for several years. The aim of this study is to compare trends in incidence of and mortality from cervical cancer in Finland and the Netherlands in relation to the introduction and intensity of the screening programmes. Therefore, incidence and mortality rates were calculated using the Cancer Registries of Finland and the Netherlands. Data on screening intensity were obtained from the Finnish Can…

| 变体 | Recall@10 | MRR@10 | nDCG@10 | Gold首名次 | 延迟ms | Top10文档ID（*为gold） |
|---|---:|---:|---:|---:|---:|---|
| dense | 0.000000 | 0.000000 | 0.000000 | Top10未命中 | 1056 | 9764256, 6561200, 27873158, 27446873, 36355784 |
| sparse | 1.000000 | 1.000000 | 1.000000 | 1 | 934 | 25742130*, 41074251, 7639744, 27446873, 46695481, 756887, 8082528 |
| hybrid_rrf | 1.000000 | 0.500000 | 0.630930 | 2 | 2638 | 27446873, 25742130*, 46695481, 36355784, 6561200, 27873158, 12779444 |
| hybrid_rrf_rerank | 1.000000 | 0.166667 | 0.356207 | 6 | 12595 | 36355784, 27446873, 6561200, 27873158, 46695481, 25742130*, 12779444 |

首个失败步骤中的关键错误召回文档（前3条非gold）：

- rank=1 `9764256` Human papillomavirus testing for the detection of high-grade cervical intraepithelial neoplasia and cancer: final results of the POBASCAM randomised controlled trial.（本地heading=`BENCH_DOC_B64_OTc2NDI1Ng`）

  > BACKGROUND Human papillomavirus (HPV) testing is more sensitive for the detection of high-grade cervical lesions than is cytology, but detection of HPV by DNA screening in two screening rounds 5 years apart has not been assessed. The aim of this study was to assess whether HPV DNA testing in the first screen decreases detection of cervical intraepithelial neoplasia (CIN) grade 3 or worse, CIN grade 2 or worse, and cervical cancer in the second screening. METHODS In this randomised trial, women aged 29-56 years participating in the cervical screening programme in the Netherlands were randomly a…
- rank=2 `6561200` Efficacy of HPV DNA testing with cytology triage and/or repeat HPV DNA testing in primary cervical cancer screening.（本地heading=`BENCH_DOC_B64_NjU2MTIwMA`）

  > BACKGROUND Primary cervical screening with both human papillomavirus (HPV) DNA testing and cytological examination of cervical cells with a Pap test (cytology) has been evaluated in randomized clinical trials. Because the vast majority of women with positive cytology are also HPV DNA positive, screening strategies that use HPV DNA testing as the primary screening test may be more effective. METHODS We used the database from the intervention arm (n = 6,257 women) of a population-based randomized trial of double screening with cytology and HPV DNA testing to evaluate the efficacy of 11 possible…
- rank=3 `27873158` Efficacy of human papillomavirus testing for the detection of invasive cervical cancers and cervical intraepithelial neoplasia: a randomised controlled trial.（本地heading=`BENCH_DOC_B64_Mjc4NzMxNTg`）

  > BACKGROUND Human papillomavirus (HPV) testing is known to be more sensitive, but less specific than cytology for detecting cervical intraepithelial neoplasia (CIN). We assessed the efficacy of cervical-cancer screening policies that are based on HPV testing. METHODS Between March, 2004, and December, 2004, in two separate recruitment phases, women aged 25-60 years were randomly assigned to conventional cytology or to HPV testing in combination with liquid-based cytology (first phase) or alone (second phase). Randomisation was done by computer in two screening centres and by sequential opening…

首个可观测失败步骤：`dense_final_top10`。

直接证据：

- Dense gold首名次=Top10未命中
- Sparse gold首名次=1
- Hybrid-RRF gold首名次=2
- Hybrid-RRF+Rerank gold首名次=6
- 分类规则=dense_miss_hybrid_hit
- 逐候选分数=未采集

推断：Dense语义向量把主题相近干扰文档排在gold之前，而精确术语帮助Sparse命中；最大词项Jaccard=0.1250。

其他可能解释：相关性标注不完整、gold摘要与claim粒度不同、分块边界或Top10截断；当前终态run不能排除这些因素（dense_miss_hybrid_hit）。

反证实验：固定同一索引和query，额外留存Dense/Sparse候选Top100、融合逐项贡献、Rerank输入输出分数及chunk文本，再复跑该query验证首个内部失效点（dense_miss_hybrid_hit）。

### queryId=715

问题：Low expression of miR7a does represses target genes and exerts a biological function in ovaries.

Gold文档：

- `18421962` Assessing the ceRNA hypothesis with quantitative measurements of miRNA and target abundance.

  > Recent studies have reported that competitive endogenous RNAs (ceRNAs) can act as sponges for a microRNA (miRNA) through their binding sites and that changes in ceRNA abundances from individual genes can modulate the activity of miRNAs. Consideration of this hypothesis would benefit from knowing the quantitative relationship between a miRNA and its endogenous target sites. Here, we altered intracellular target site abundance through expression of an miR-122 target in hepatocytes and livers and analyzed the effects on miR-122 target genes. Target repression was released in a threshold-like mann…

| 变体 | Recall@10 | MRR@10 | nDCG@10 | Gold首名次 | 延迟ms | Top10文档ID（*为gold） |
|---|---:|---:|---:|---:|---:|---|
| dense | 0.000000 | 0.000000 | 0.000000 | Top10未命中 | 1759 | 13290521, 2000038, 19358586, 12652963, 17544977, 8247469, 1574014, 21373240, 885056 |
| sparse | 0.000000 | 0.000000 | 0.000000 | Top10未命中 | 786 | 11935250, 279052, 37438296, 1285713, 21108759, 9159495, 38243984, 16691520, 22623275, 3531388 |
| hybrid_rrf | 1.000000 | 0.500000 | 0.630930 | 2 | 2630 | 2000038, 18421962*, 153744, 37438296, 12440953, 19047331, 15590539, 4387494, 5253987, 2619579 |
| hybrid_rrf_rerank | 1.000000 | 0.142857 | 0.333333 | 7 | 13420 | 2000038, 12440953, 2619579, 153744, 5253987, 15590539, 18421962*, 37438296, 4387494, 19047331 |

首个失败步骤中的关键错误召回文档（前3条非gold）：

- rank=1 `13290521` MicroRNA-7: A miRNA with expanding roles in development and disease.（本地heading=`BENCH_DOC_B64_MTMyOTA1MjE`）

  > MicroRNAs (miRNAs) are a family of short, non-coding RNA molecules (∼22nt) involved in post-transcriptional control of gene expression. They act via base-pairing with mRNA transcripts that harbour target sequences, resulting in accelerated mRNA decay and/or translational attenuation. Given miRNAs mediate the expression of molecules involved in many aspects of normal cell development and functioning, it is not surprising that aberrant miRNA expression is closely associated with many human diseases. Their pivotal role in driving a range of normal cellular physiology as well as pathological proce…
- rank=2 `2000038` MicroRNAs can generate thresholds in target gene expression（本地heading=`BENCH_DOC_B64_MjAwMDAzOA`）

  > MicroRNAs (miRNAs) are short, highly conserved noncoding RNA molecules that repress gene expression in a sequence-dependent manner. We performed single-cell measurements using quantitative fluorescence microscopy and flow cytometry to monitor a target gene's protein expression in the presence and absence of regulation by miRNA. We find that although the average level of repression is modest, in agreement with previous population-based measurements, the repression among individual cells varies dramatically. In particular, we show that regulation by miRNAs establishes a threshold level of target…
- rank=3 `19358586` Functional proteomics identifies miRNAs to target a p27/Myc/phospho-Rb signature in breast and ovarian cancer（本地heading=`BENCH_DOC_B64_MTkzNTg1ODY`）

  > The myc oncogene is overexpressed in almost half of all breast and ovarian cancers, but attempts at therapeutic interventions against myc have proven to be challenging. Myc regulates multiple biological processes, including the cell cycle, and as such is associated with cell proliferation and tumor progression. We identified a protein signature of high myc, low p27 and high phospho-Rb significantly correlated with poor patient survival in breast and ovarian cancers. Screening of a miRNA library by functional proteomics in multiple cell lines and integration of data from patient tumors revealed…

首个可观测失败步骤：`dense_final_top10`。

直接证据：

- Dense gold首名次=Top10未命中
- Sparse gold首名次=Top10未命中
- Hybrid-RRF gold首名次=2
- Hybrid-RRF+Rerank gold首名次=7
- 分类规则=dense_miss_hybrid_hit
- 逐候选分数=未采集

推断：Top10截断、语义近邻竞争或Rerank排序偏好可能共同影响；query与gold最大词项Jaccard=0.0789。

其他可能解释：相关性标注不完整、gold摘要与claim粒度不同、分块边界或Top10截断；当前终态run不能排除这些因素（dense_miss_hybrid_hit）。

反证实验：固定同一索引和query，额外留存Dense/Sparse候选Top100、融合逐项贡献、Rerank输入输出分数及chunk文本，再复跑该query验证首个内部失效点（dense_miss_hybrid_hit）。

### queryId=185

问题：Breast cancer development is determined exclusively by genetic factors.

Gold文档：

- `18340282` Gene–environment interactions in 7610 women with breast cancer: prospective evidence from the Million Women Study

  > BACKGROUND Information is scarce about the combined effects on breast cancer incidence of low-penetrance genetic susceptibility polymorphisms and environmental factors (reproductive, behavioural, and anthropometric risk factors for breast cancer). To test for evidence of gene-environment interactions, we compared genotypic relative risks for breast cancer across the other risk factors in a large UK prospective study. METHODS We tested gene-environment interactions in 7610 women who developed breast cancer and 10 196 controls without the disease, studying the effects of 12 polymorphisms (FGFR2-…

| 变体 | Recall@10 | MRR@10 | nDCG@10 | Gold首名次 | 延迟ms | Top10文档ID（*为gold） |
|---|---:|---:|---:|---:|---:|---|
| dense | 0.000000 | 0.000000 | 0.000000 | Top10未命中 | 1584 | 38784540, 15721252, 6790197, 20839751, 23557241, 27123743, 22482820, 52188256, 16691520, 3285322 |
| sparse | 0.000000 | 0.000000 | 0.000000 | Top10未命中 | 2420 | 13831842, 12207167, 20839751, 38784540, 32534305, 5864770, 52188256, 4767806, 8671456, 1153655 |
| hybrid_rrf | 1.000000 | 0.200000 | 0.386853 | 5 | 4696 | 38784540, 20839751, 52188256, 5864770, 18340282*, 21874312, 27123743, 23557241, 32534305 |
| hybrid_rrf_rerank | 1.000000 | 0.500000 | 0.630930 | 2 | 15592 | 23557241, 18340282*, 38784540, 27123743, 5864770, 21874312, 20839751, 32534305, 52188256 |

首个失败步骤中的关键错误召回文档（前3条非gold）：

- rank=1 `38784540` Life course breast cancer risk factors and adult breast density (United Kingdom)（本地heading=`BENCH_DOC_B64_Mzg3ODQ1NDA`）

  > Objective To determine whether risk factors in childhood and early adulthood affect later mammographic breast density. Methods: Subjects were 628 women who attended a medical examination at the University of Glasgow Student Health Service (1948–1968), responded to a questionnaire (2001) and had a screening mammogram in Scotland (1989–2002). Mammograms (median age of 59years) were classified using a six category classification (SCC) of breast density percent. Logistic regression was used to determine associations between risk factors and having a high-risk mammogram (≥25 dense). Results: In mul…
- rank=2 `15721252` PD 0332991, a selective cyclin D kinase 4/6 inhibitor, preferentially inhibits proliferation of luminal estrogen receptor-positive human breast cancer cell lines in vitro（本地heading=`BENCH_DOC_B64_MTU3MjEyNTI`）

  > INTRODUCTION Alterations in cell cycle regulators have been implicated in human malignancies including breast cancer. PD 0332991 is an orally active, highly selective inhibitor of the cyclin D kinases (CDK)4 and CDK6 with ability to block retinoblastoma (Rb) phosphorylation in the low nanomolar range. To identify predictors of response, we determined the in vitro sensitivity to PD 0332991 across a panel of molecularly characterized human breast cancer cell lines. METHODS Forty-seven human breast cancer and immortalized cell lines representing the known molecular subgroups of breast cancer were…
- rank=3 `6790197` Prostate cancer-associated gene expression alterations determined from needle biopsies.（本地heading=`BENCH_DOC_B64_Njc5MDE5Nw`）

  > PURPOSE To accurately identify gene expression alterations that differentiate neoplastic from normal prostate epithelium using an approach that avoids contamination by unwanted cellular components and is not compromised by acute gene expression changes associated with tumor devascularization and resulting ischemia. EXPERIMENTAL DESIGN Approximately 3,000 neoplastic and benign prostate epithelial cells were isolated using laser capture microdissection from snap-frozen prostate biopsy specimens provided by 31 patients who subsequently participated in a clinical trial of preoperative chemotherapy…

首个可观测失败步骤：`dense_final_top10`。

直接证据：

- Dense gold首名次=Top10未命中
- Sparse gold首名次=Top10未命中
- Hybrid-RRF gold首名次=5
- Hybrid-RRF+Rerank gold首名次=2
- 分类规则=dense_miss_hybrid_hit
- 逐候选分数=未采集

推断：Top10截断、语义近邻竞争或Rerank排序偏好可能共同影响；query与gold最大词项Jaccard=0.0769。

其他可能解释：相关性标注不完整、gold摘要与claim粒度不同、分块边界或Top10截断；当前终态run不能排除这些因素（dense_miss_hybrid_hit）。

反证实验：固定同一索引和query，额外留存Dense/Sparse候选Top100、融合逐项贡献、Rerank输入输出分数及chunk文本，再复跑该query验证首个内部失效点（dense_miss_hybrid_hit）。

## sparse_miss_hybrid_hit

### queryId=216

问题：CX3CR1 on the Th2 cells impairs T cell survival

Gold文档：

- `21366394` CX3CR1 is required for airway inflammation by promoting T helper cell survival and maintenance in inflamed lung

  > Allergic asthma is a T helper type 2 (T(H)2)-dominated disease of the lung. In people with asthma, a fraction of CD4(+) T cells express the CX3CL1 receptor, CX3CR1, and CX3CL1 expression is increased in airway smooth muscle, lung endothelium and epithelium upon allergen challenge. Here we found that untreated CX3CR1-deficient mice or wild-type (WT) mice treated with CX3CR1-blocking reagents show reduced lung disease upon allergen sensitization and challenge. Transfer of WT CD4(+) T cells into CX3CR1-deficient mice restored the cardinal features of asthma, and CX3CR1-blocking reagents prevented…

| 变体 | Recall@10 | MRR@10 | nDCG@10 | Gold首名次 | 延迟ms | Top10文档ID（*为gold） |
|---|---:|---:|---:|---:|---:|---|
| dense | 1.000000 | 1.000000 | 1.000000 | 1 | 2887 | 21366394*, 12058271, 34905328, 40590358, 7386360, 9500590, 21363424, 14492339, 3935126 |
| sparse | 0.000000 | 0.000000 | 0.000000 | Top10未命中 | 2958 | 11666252, 22210434, 20610557, 6961811, 25085979, 4422734, 2248870, 22198971, 34436231, 14767844 |
| hybrid_rrf | 1.000000 | 1.000000 | 1.000000 | 1 | 3553 | 21366394*, 7386360, 21363424, 20220731, 2248870, 22210434, 40608679, 15128866, 266641, 6123924 |
| hybrid_rrf_rerank | 1.000000 | 1.000000 | 1.000000 | 1 | 16614 | 21366394*, 40608679, 21363424, 22210434, 2248870, 6123924, 7386360, 20220731, 15128866, 266641 |

首个失败步骤中的关键错误召回文档（前3条非gold）：

- rank=1 `11666252` Maintaining the norm: T-cell homeostasis（本地heading=`BENCH_DOC_B64_MTE2NjYyNTI`）

  > The persistence of naive and memory T cells has long been of interest to immunologists, but the factors that influence the survival and homeostasis of these subsets have remained obscure. In recent years, it has become evident that the homeostasis of both naive and memory T-cell pools is highly dynamic and tightly regulated by internal stimuli, including cytokines and self-peptide–MHC ligands for the T-cell receptor. These homeostatic mechanisms might have a vital influence on the capacity of the T-cell pool to respond to both foreign and self-antigens.
- rank=2 `22210434` The kinase TAK1 integrates antigen and cytokine receptor signaling for T cell development, survival and function（本地heading=`BENCH_DOC_B64_MjIyMTA0MzQ`）

  > The kinase TAK1 is critical for innate and B cell immunity. The function of TAK1 in T cells is unclear, however. We show here that T cell–specific deletion of the gene encoding TAK1 resulted in reduced development of thymocytes, especially of regulatory T cells expressing the transcription factor Foxp3. In mature thymocytes, TAK1 was required for interleukin 7–mediated survival and T cell receptor–dependent activation of transcription factor NF-κB and the kinase Jnk. In effector T cells, TAK1 was dispensable for T cell receptor–dependent NF-κB activation and cytokine production, but was import…
- rank=3 `20610557` Alkylating agent melphalan augments the efficacy of adoptive immunotherapy using tumor-specific CD4+ T cells.（本地heading=`BENCH_DOC_B64_MjA2MTA1NTc`）

  > In recent years, the immune-potentiating effects of some widely used chemotherapeutic agents have been increasingly appreciated. This provides a rationale for combining conventional chemotherapy with immunotherapy strategies to achieve durable therapeutic benefits. Previous studies have implicated the immunomodulatory effects of melphalan, an alkylating agent commonly used to treat multiple myeloma, but the underlying mechanisms remain obscure. In the present study, we investigated the impact of melphalan on endogenous immune cells as well as adoptively transferred tumor-specific CD4(+) T cell…

首个可观测失败步骤：`sparse_final_top10`。

直接证据：

- Dense gold首名次=1
- Sparse gold首名次=Top10未命中
- Hybrid-RRF gold首名次=1
- Hybrid-RRF+Rerank gold首名次=1
- 分类规则=sparse_miss_hybrid_hit
- 逐候选分数=未采集

推断：稀疏词项匹配对同义改写或词形差异不敏感；query与gold标题/片段最大词项Jaccard=0.0769。

其他可能解释：相关性标注不完整、gold摘要与claim粒度不同、分块边界或Top10截断；当前终态run不能排除这些因素（sparse_miss_hybrid_hit）。

反证实验：固定同一索引和query，额外留存Dense/Sparse候选Top100、融合逐项贡献、Rerank输入输出分数及chunk文本，再复跑该query验证首个内部失效点（sparse_miss_hybrid_hit）。

### queryId=217

问题：CX3CR1 on the Th2 cells promotes T cell survival

Gold文档：

- `21366394` CX3CR1 is required for airway inflammation by promoting T helper cell survival and maintenance in inflamed lung

  > Allergic asthma is a T helper type 2 (T(H)2)-dominated disease of the lung. In people with asthma, a fraction of CD4(+) T cells express the CX3CL1 receptor, CX3CR1, and CX3CL1 expression is increased in airway smooth muscle, lung endothelium and epithelium upon allergen challenge. Here we found that untreated CX3CR1-deficient mice or wild-type (WT) mice treated with CX3CR1-blocking reagents show reduced lung disease upon allergen sensitization and challenge. Transfer of WT CD4(+) T cells into CX3CR1-deficient mice restored the cardinal features of asthma, and CX3CR1-blocking reagents prevented…

| 变体 | Recall@10 | MRR@10 | nDCG@10 | Gold首名次 | 延迟ms | Top10文档ID（*为gold） |
|---|---:|---:|---:|---:|---:|---|
| dense | 1.000000 | 1.000000 | 1.000000 | 1 | 1201 | 21366394*, 12058271, 14492339, 40608679, 3935126, 3654468, 9500590, 57783564, 34905328 |
| sparse | 0.000000 | 0.000000 | 0.000000 | Top10未命中 | 468 | 11666252, 22210434, 20610557, 25085979, 4422734, 2248870, 22198971, 34436231, 14767844, 25453683 |
| hybrid_rrf | 1.000000 | 1.000000 | 1.000000 | 1 | 1536 | 21366394*, 40608679, 45414636, 21363424, 22874817, 15128866, 266641, 2248870, 20220731, 28006126 |
| hybrid_rrf_rerank | 1.000000 | 1.000000 | 1.000000 | 1 | 12059 | 21366394*, 40608679, 2248870, 21363424, 45414636, 28006126, 20220731, 15128866, 22874817, 266641 |

首个失败步骤中的关键错误召回文档（前3条非gold）：

- rank=1 `11666252` Maintaining the norm: T-cell homeostasis（本地heading=`BENCH_DOC_B64_MTE2NjYyNTI`）

  > The persistence of naive and memory T cells has long been of interest to immunologists, but the factors that influence the survival and homeostasis of these subsets have remained obscure. In recent years, it has become evident that the homeostasis of both naive and memory T-cell pools is highly dynamic and tightly regulated by internal stimuli, including cytokines and self-peptide–MHC ligands for the T-cell receptor. These homeostatic mechanisms might have a vital influence on the capacity of the T-cell pool to respond to both foreign and self-antigens.
- rank=2 `22210434` The kinase TAK1 integrates antigen and cytokine receptor signaling for T cell development, survival and function（本地heading=`BENCH_DOC_B64_MjIyMTA0MzQ`）

  > The kinase TAK1 is critical for innate and B cell immunity. The function of TAK1 in T cells is unclear, however. We show here that T cell–specific deletion of the gene encoding TAK1 resulted in reduced development of thymocytes, especially of regulatory T cells expressing the transcription factor Foxp3. In mature thymocytes, TAK1 was required for interleukin 7–mediated survival and T cell receptor–dependent activation of transcription factor NF-κB and the kinase Jnk. In effector T cells, TAK1 was dispensable for T cell receptor–dependent NF-κB activation and cytokine production, but was import…
- rank=3 `20610557` Alkylating agent melphalan augments the efficacy of adoptive immunotherapy using tumor-specific CD4+ T cells.（本地heading=`BENCH_DOC_B64_MjA2MTA1NTc`）

  > In recent years, the immune-potentiating effects of some widely used chemotherapeutic agents have been increasingly appreciated. This provides a rationale for combining conventional chemotherapy with immunotherapy strategies to achieve durable therapeutic benefits. Previous studies have implicated the immunomodulatory effects of melphalan, an alkylating agent commonly used to treat multiple myeloma, but the underlying mechanisms remain obscure. In the present study, we investigated the impact of melphalan on endogenous immune cells as well as adoptively transferred tumor-specific CD4(+) T cell…

首个可观测失败步骤：`sparse_final_top10`。

直接证据：

- Dense gold首名次=1
- Sparse gold首名次=Top10未命中
- Hybrid-RRF gold首名次=1
- Hybrid-RRF+Rerank gold首名次=1
- 分类规则=sparse_miss_hybrid_hit
- 逐候选分数=未采集

推断：稀疏词项匹配对同义改写或词形差异不敏感；query与gold标题/片段最大词项Jaccard=0.0769。

其他可能解释：相关性标注不完整、gold摘要与claim粒度不同、分块边界或Top10截断；当前终态run不能排除这些因素（sparse_miss_hybrid_hit）。

反证实验：固定同一索引和query，额外留存Dense/Sparse候选Top100、融合逐项贡献、Rerank输入输出分数及chunk文本，再复跑该query验证首个内部失效点（sparse_miss_hybrid_hit）。

### queryId=268

问题：Cold exposure increases BAT recruitment.

Gold文档：

- `970012` Cold Exposure Promotes Atherosclerotic Plaque Growth and Instability via UCP1-Dependent Lipolysis

  > Molecular mechanisms underlying the cold-associated high cardiovascular risk remain unknown. Here, we show that the cold-triggered food-intake-independent lipolysis significantly increased plasma levels of small low-density lipoprotein (LDL) remnants, leading to accelerated development of atherosclerotic lesions in mice. In two genetic mouse knockout models (apolipoprotein E(-/-) [ApoE(-/-)] and LDL receptor(-/-) [Ldlr(-/-)] mice), persistent cold exposure stimulated atherosclerotic plaque growth by increasing lipid deposition. Furthermore, marked increase of inflammatory cells and plaque-asso…

| 变体 | Recall@10 | MRR@10 | nDCG@10 | Gold首名次 | 延迟ms | Top10文档ID（*为gold） |
|---|---:|---:|---:|---:|---:|---|
| dense | 1.000000 | 1.000000 | 1.000000 | 1 | 512 | 970012*, 1568684, 4319174, 4345315, 36444198, 46353045, 16056410, 36838958, 13000926, 12470783 |
| sparse | 0.000000 | 0.000000 | 0.000000 | Top10未命中 | 233 | 12207167, 86217760, 36271512 |
| hybrid_rrf | 1.000000 | 1.000000 | 1.000000 | 1 | 603 | 970012*, 13000926, 86217760, 1568684, 36838958, 29381091, 21868715, 15135001, 25817686 |
| hybrid_rrf_rerank | 1.000000 | 0.500000 | 0.630930 | 2 | 11089 | 36838958, 970012*, 13000926, 1568684, 29381091, 21868715, 15135001, 25817686, 86217760 |

首个失败步骤中的关键错误召回文档（前3条非gold）：

- rank=1 `12207167` Adverse effects of excessive consumption of amino acids.（本地heading=`BENCH_DOC_B64_MTIyMDcxNjc`）

  > PHENYLALANINE TOXICITY 158 Developing the 0. -M ethylphenylalanine Model. . . . . . .. . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . 160 Use of the a-Methyl phenylalanine Model in Brain Protein Synthesis . . . . . . . . . . . . . . . . . . . 161 TYROSINE TOXICITY 162 General Nutritional Observations . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . 162 Factors Affecting Tissue Concentrations of Tyrosine . ... .. .. .. ...... . . . . . . .. . . 163 Probable Cause of Tyrosine Toxicity . . .…
- rank=2 `86217760` The Self-Incompatibility Genes of Brassica: Expression and Use in Genetic Ablation of Floral Tissues（本地heading=`BENCH_DOC_B64_ODYyMTc3NjA`）

  > INTRODUCTION . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . .. . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . 394 POLLINATION AND POLLEN TUBE GROWTH . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . 395 Interaction s between the M ale G ameto phyte and Pistil . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . 395 SelfIncom patibili ty Systems: Gameto phytic and S poro phyti c Determin ation of Pollen Phenoty pe . . . . . . . . . . . . . . . . .. . . . . . . . . . . .…
- rank=3 `36271512` T-cell activation.（本地heading=`BENCH_DOC_B64_MzYyNzE1MTI`）

  > INTRODUCTION • • CELLULAR AND MOLECULAR REQUIREMENTS FOR T-CELL ACTIVATION . The T-Cell Antigen Receptor Complex . . . .. . . . ..... . . . . . . . . . . . . . . . . ...... . . . T-Cell Activation by Antibodies and Leetins . . . . . . . . . . .. . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . Other Cell Surface Structures (Accessory Molecules) Involved in Antigen Recognition and Activation . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . .…

首个可观测失败步骤：`sparse_final_top10`。

直接证据：

- Dense gold首名次=1
- Sparse gold首名次=Top10未命中
- Hybrid-RRF gold首名次=1
- Hybrid-RRF+Rerank gold首名次=2
- 分类规则=sparse_miss_hybrid_hit
- 逐候选分数=未采集

推断：稀疏词项匹配对同义改写或词形差异不敏感；query与gold标题/片段最大词项Jaccard=0.0278。

其他可能解释：相关性标注不完整、gold摘要与claim粒度不同、分块边界或Top10截断；当前终态run不能排除这些因素（sparse_miss_hybrid_hit）。

反证实验：固定同一索引和query，额外留存Dense/Sparse候选Top100、融合逐项贡献、Rerank输入输出分数及chunk文本，再复跑该query验证首个内部失效点（sparse_miss_hybrid_hit）。

## rerank_rescue

当前完整run中无符合该确定性规则的案例。

## rerank_harm

当前完整run中无符合该确定性规则的案例。

## dense_only_success

### queryId=1014

问题：Rapamycin decreases the concentration of triacylglycerols in fruit flies.

Gold文档：

- `6277638` Mechanisms of Life Span Extension by Rapamycin in the Fruit Fly Drosophila melanogaster

  > The target of rapamycin (TOR) pathway is a major nutrient-sensing pathway that, when genetically downregulated, increases life span in evolutionarily diverse organisms including mammals. The central component of this pathway, TOR kinase, is the target of the inhibitory drug rapamycin, a highly specific and well-described drug approved for human use. We show here that feeding rapamycin to adult Drosophila produces the life span extension seen in some TOR mutants. Increase in life span by rapamycin was associated with increased resistance to both starvation and paraquat. Analysis of the underlyi…

| 变体 | Recall@10 | MRR@10 | nDCG@10 | Gold首名次 | 延迟ms | Top10文档ID（*为gold） |
|---|---:|---:|---:|---:|---:|---|
| dense | 1.000000 | 1.000000 | 1.000000 | 1 | 819 | 6277638*, 712078, 36991551, 24770913, 12622860, 8417211, 36904081, 435529, 17195001, 41256402 |
| sparse | 0.000000 | 0.000000 | 0.000000 | Top10未命中 | 3843 | 10530014, 36271512, 8065561, 37562370, 4387784, 7662395, 24645237, 6308416, 46517055, 34498093 |
| hybrid_rrf | 1.000000 | 0.500000 | 0.630930 | 2 | 2388 | 36271512, 6277638*, 10790846, 16056410, 4303939, 24632480, 46517055, 4687948, 29828242, 86217760 |
| hybrid_rrf_rerank | 1.000000 | 1.000000 | 1.000000 | 1 | 8372 | 6277638*, 10790846, 24632480, 46517055, 86217760, 4303939, 16056410, 4687948, 36271512, 29828242 |

首个失败步骤中的关键错误召回文档（前3条非gold）：

- rank=1 `10530014` A point mutation in KINDLIN3 ablates activation of three integrin subfamilies in humans（本地heading=`BENCH_DOC_B64_MTA1MzAwMTQ`）

  > Monogenic deficiency diseases provide unique opportunities to define the contributions of individual molecules to human physiology and to identify pathologies arising from their dysfunction. Here we describe a deficiency disease in two human siblings that presented with severe bleeding, frequent infections and osteopetrosis at an early age. These symptoms are consistent with but more severe than those reported for people with leukocyte adhesion deficiency III (LAD-III). Mechanistically, these symptoms arose from an inability to activate the integrins expressed on hematopoietic cells, including…
- rank=2 `36271512` T-cell activation.（本地heading=`BENCH_DOC_B64_MzYyNzE1MTI`）

  > INTRODUCTION • • CELLULAR AND MOLECULAR REQUIREMENTS FOR T-CELL ACTIVATION . The T-Cell Antigen Receptor Complex . . . .. . . . ..... . . . . . . . . . . . . . . . . ...... . . . T-Cell Activation by Antibodies and Leetins . . . . . . . . . . .. . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . Other Cell Surface Structures (Accessory Molecules) Involved in Antigen Recognition and Activation . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . .…
- rank=3 `8065561` Specific and cooperative binding of E. coli single-stranded DNA binding protein to mRNA.（本地heading=`BENCH_DOC_B64_ODA2NTU2MQ`）

  > Fluorometric titration of E. coli single-stranded DNA binding protein with various RNAs showed that the protein specifically and cooperatively binds to its own mRNA. The binding inhibited in vitro expression of ssb and bla but not nusA. This inhibition takes place at a physiological concentration of SSB. The function of the protein in gene regulation is discussed.

首个可观测失败步骤：`sparse_final_top10`。

直接证据：

- Dense gold首名次=1
- Sparse gold首名次=Top10未命中
- Hybrid-RRF gold首名次=2
- Hybrid-RRF+Rerank gold首名次=1
- 分类规则=dense_only_success
- 逐候选分数=未采集

推断：稀疏词项匹配对同义改写或词形差异不敏感；query与gold标题/片段最大词项Jaccard=0.0714。

其他可能解释：相关性标注不完整、gold摘要与claim粒度不同、分块边界或Top10截断；当前终态run不能排除这些因素（dense_only_success）。

反证实验：固定同一索引和query，额外留存Dense/Sparse候选Top100、融合逐项贡献、Rerank输入输出分数及chunk文本，再复跑该query验证首个内部失效点（dense_only_success）。

### queryId=1020

问题：Rapid up-regulation and higher basal expression of interferon-induced genes increase survival of granule cell neurons that are infected by West Nile virus.

Gold文档：

- `9433958` Differential innate immune response programs in neuronal subtypes determine susceptibility to infection in the brain by positive stranded RNA viruses

  > Although susceptibility of neurons in the brain to microbial infection is a major determinant of clinical outcome, little is known about the molecular factors governing this vulnerability. Here we show that two types of neurons from distinct brain regions showed differential permissivity to replication of several positive-stranded RNA viruses. Granule cell neurons of the cerebellum and cortical neurons from the cerebral cortex have unique innate immune programs that confer differential susceptibility to viral infection ex vivo and in vivo. By transducing cortical neurons with genes that were e…

| 变体 | Recall@10 | MRR@10 | nDCG@10 | Gold首名次 | 延迟ms | Top10文档ID（*为gold） |
|---|---:|---:|---:|---:|---:|---|
| dense | 1.000000 | 1.000000 | 1.000000 | 1 | 781 | 9433958*, 10559501, 38376189, 14474178, 6182947, 26731863, 2947124, 5137019, 3493623, 24707550 |
| sparse | 0.000000 | 0.000000 | 0.000000 | Top10未命中 | 680 | 25238950, 15561961, 515489, 3848469, 23599024, 26731863, 29367554, 4387494, 22800314 |
| hybrid_rrf | 1.000000 | 0.500000 | 0.630930 | 2 | 1137 | 26731863, 9433958*, 6144969, 6182947, 6163801, 2947124, 72159, 19005293, 25488034, 8247469 |
| hybrid_rrf_rerank | 1.000000 | 1.000000 | 1.000000 | 1 | 11716 | 9433958*, 8247469, 6144969, 72159, 26731863, 6163801, 25488034, 6182947, 19005293, 2947124 |

首个失败步骤中的关键错误召回文档（前3条非gold）：

- rank=1 `25238950` FGF-2 enhances intestinal stem cell survival and its expression is induced after radiation injury.（本地heading=`BENCH_DOC_B64_MjUyMzg5NTA`）

  > Fibroblast growth factors (FGFs) have mitogenic activity toward a wide variety of cells of mesenchymal, neuronal, and epithelial origin and regulate events in normal embryonic development, angiogenesis, wound repair, and neoplasia. FGF-2 is expressed in many normal adult tissues and can regulate migration and replication of intestinal epithelial cells in culture. However, little is known about the effects of FGF-2 on intestinal epithelial stem cells during either normal epithelial renewal or regeneration of a functional epithelium after injury. In this study, we investigated the expression of…
- rank=2 `15561961` expression by oxidized linoleic（本地heading=`BENCH_DOC_B64_MTU1NjE5NjE`）

  > Hypercholesterolemia is associated with impairments in endothelium-dependent vascular relaxations. Paradoxically, endothelial production of nitrogen oxides is increased in early stages of hypercholesterolemia. Prior work has shown that oxidized low density lipoprotein (LDL) has both stimulatory and inhibitory effects on endothelial nitric oxide synthase expression (eNOS) and has focused on lysophosphatidyl choline (LPC) as a component of oxidized LDL which may modulate this effect. Another biologically active component of oxidized LDL is 13-hydroperoxyoctadecadienoic acid (13-HPODE), an oxidiz…
- rank=3 `515489` Oncofetal long noncoding RNA PVT1 promotes proliferation and stem cell-like property of hepatocellular carcinoma cells by stabilizing NOP2.（本地heading=`BENCH_DOC_B64_NTE1NDg5`）

  > UNLABELLED Many protein-coding oncofetal genes are highly expressed in murine and human fetal liver and silenced in adult liver. The protein products of these hepatic oncofetal genes have been used as clinical markers for the recurrence of hepatocellular carcinoma (HCC) and as therapeutic targets for HCC. Herein we examined the expression profiles of long noncoding RNAs (lncRNAs) found in fetal and adult liver in mice. Many fetal hepatic lncRNAs were identified; one of these, lncRNA-mPvt1, is an oncofetal RNA that was found to promote cell proliferation, cell cycling, and the expression of ste…

首个可观测失败步骤：`sparse_final_top10`。

直接证据：

- Dense gold首名次=1
- Sparse gold首名次=Top10未命中
- Hybrid-RRF gold首名次=2
- Hybrid-RRF+Rerank gold首名次=1
- 分类规则=dense_only_success
- 逐候选分数=未采集

推断：稀疏词项匹配对同义改写或词形差异不敏感；query与gold标题/片段最大词项Jaccard=0.0988。

其他可能解释：相关性标注不完整、gold摘要与claim粒度不同、分块边界或Top10截断；当前终态run不能排除这些因素（dense_only_success）。

反证实验：固定同一索引和query，额外留存Dense/Sparse候选Top100、融合逐项贡献、Rerank输入输出分数及chunk文本，再复跑该query验证首个内部失效点（dense_only_success）。

### queryId=1021

问题：Rapid up-regulation and higher basal expression of interferon-induced genes reduce survival of granule cell neurons that are infected by West Nile virus.

Gold文档：

- `9433958` Differential innate immune response programs in neuronal subtypes determine susceptibility to infection in the brain by positive stranded RNA viruses

  > Although susceptibility of neurons in the brain to microbial infection is a major determinant of clinical outcome, little is known about the molecular factors governing this vulnerability. Here we show that two types of neurons from distinct brain regions showed differential permissivity to replication of several positive-stranded RNA viruses. Granule cell neurons of the cerebellum and cortical neurons from the cerebral cortex have unique innate immune programs that confer differential susceptibility to viral infection ex vivo and in vivo. By transducing cortical neurons with genes that were e…

| 变体 | Recall@10 | MRR@10 | nDCG@10 | Gold首名次 | 延迟ms | Top10文档ID（*为gold） |
|---|---:|---:|---:|---:|---:|---|
| dense | 1.000000 | 1.000000 | 1.000000 | 1 | 2270 | 9433958*, 10559501, 26731863, 38376189, 6182947, 14474178, 3493623, 2947124, 6144969, 5137019 |
| sparse | 0.000000 | 0.000000 | 0.000000 | Top10未命中 | 2310 | 25238950, 15561961, 515489, 3848469, 23599024, 26731863, 4387494, 22800314, 16550075 |
| hybrid_rrf | 1.000000 | 0.500000 | 0.630930 | 2 | 7020 | 26731863, 9433958*, 6144969, 6163801, 2947124, 72159, 30437264, 19005293, 25238950, 15561961 |
| hybrid_rrf_rerank | 1.000000 | 1.000000 | 1.000000 | 1 | 18459 | 9433958*, 26731863, 6144969, 72159, 30437264, 15561961, 6163801, 2947124, 19005293, 25238950 |

首个失败步骤中的关键错误召回文档（前3条非gold）：

- rank=1 `25238950` FGF-2 enhances intestinal stem cell survival and its expression is induced after radiation injury.（本地heading=`BENCH_DOC_B64_MjUyMzg5NTA`）

  > Fibroblast growth factors (FGFs) have mitogenic activity toward a wide variety of cells of mesenchymal, neuronal, and epithelial origin and regulate events in normal embryonic development, angiogenesis, wound repair, and neoplasia. FGF-2 is expressed in many normal adult tissues and can regulate migration and replication of intestinal epithelial cells in culture. However, little is known about the effects of FGF-2 on intestinal epithelial stem cells during either normal epithelial renewal or regeneration of a functional epithelium after injury. In this study, we investigated the expression of…
- rank=2 `15561961` expression by oxidized linoleic（本地heading=`BENCH_DOC_B64_MTU1NjE5NjE`）

  > Hypercholesterolemia is associated with impairments in endothelium-dependent vascular relaxations. Paradoxically, endothelial production of nitrogen oxides is increased in early stages of hypercholesterolemia. Prior work has shown that oxidized low density lipoprotein (LDL) has both stimulatory and inhibitory effects on endothelial nitric oxide synthase expression (eNOS) and has focused on lysophosphatidyl choline (LPC) as a component of oxidized LDL which may modulate this effect. Another biologically active component of oxidized LDL is 13-hydroperoxyoctadecadienoic acid (13-HPODE), an oxidiz…
- rank=3 `515489` Oncofetal long noncoding RNA PVT1 promotes proliferation and stem cell-like property of hepatocellular carcinoma cells by stabilizing NOP2.（本地heading=`BENCH_DOC_B64_NTE1NDg5`）

  > UNLABELLED Many protein-coding oncofetal genes are highly expressed in murine and human fetal liver and silenced in adult liver. The protein products of these hepatic oncofetal genes have been used as clinical markers for the recurrence of hepatocellular carcinoma (HCC) and as therapeutic targets for HCC. Herein we examined the expression profiles of long noncoding RNAs (lncRNAs) found in fetal and adult liver in mice. Many fetal hepatic lncRNAs were identified; one of these, lncRNA-mPvt1, is an oncofetal RNA that was found to promote cell proliferation, cell cycling, and the expression of ste…

首个可观测失败步骤：`sparse_final_top10`。

直接证据：

- Dense gold首名次=1
- Sparse gold首名次=Top10未命中
- Hybrid-RRF gold首名次=2
- Hybrid-RRF+Rerank gold首名次=1
- 分类规则=dense_only_success
- 逐候选分数=未采集

推断：稀疏词项匹配对同义改写或词形差异不敏感；query与gold标题/片段最大词项Jaccard=0.0988。

其他可能解释：相关性标注不完整、gold摘要与claim粒度不同、分块边界或Top10截断；当前终态run不能排除这些因素（dense_only_success）。

反证实验：固定同一索引和query，额外留存Dense/Sparse候选Top100、融合逐项贡献、Rerank输入输出分数及chunk文本，再复跑该query验证首个内部失效点（dense_only_success）。

## sparse_only_success

### queryId=598

问题：Incidence rates of cervical cancer have increased due to nationwide screening programs based primarily on cytology to detect uterine cervical cancer.

Gold文档：

- `25742130` Mass screening programmes and trends in cervical cancer in Finland and the Netherlands.

  > With respect to cervical cancer management, Finland and the Netherlands are comparable in relevant characteristics, e.g., fertility rate, age-of-mother at first birth and a national screening programme for several years. The aim of this study is to compare trends in incidence of and mortality from cervical cancer in Finland and the Netherlands in relation to the introduction and intensity of the screening programmes. Therefore, incidence and mortality rates were calculated using the Cancer Registries of Finland and the Netherlands. Data on screening intensity were obtained from the Finnish Can…

| 变体 | Recall@10 | MRR@10 | nDCG@10 | Gold首名次 | 延迟ms | Top10文档ID（*为gold） |
|---|---:|---:|---:|---:|---:|---|
| dense | 0.000000 | 0.000000 | 0.000000 | Top10未命中 | 1056 | 9764256, 6561200, 27873158, 27446873, 36355784 |
| sparse | 1.000000 | 1.000000 | 1.000000 | 1 | 934 | 25742130*, 41074251, 7639744, 27446873, 46695481, 756887, 8082528 |
| hybrid_rrf | 1.000000 | 0.500000 | 0.630930 | 2 | 2638 | 27446873, 25742130*, 46695481, 36355784, 6561200, 27873158, 12779444 |
| hybrid_rrf_rerank | 1.000000 | 0.166667 | 0.356207 | 6 | 12595 | 36355784, 27446873, 6561200, 27873158, 46695481, 25742130*, 12779444 |

首个失败步骤中的关键错误召回文档（前3条非gold）：

- rank=1 `9764256` Human papillomavirus testing for the detection of high-grade cervical intraepithelial neoplasia and cancer: final results of the POBASCAM randomised controlled trial.（本地heading=`BENCH_DOC_B64_OTc2NDI1Ng`）

  > BACKGROUND Human papillomavirus (HPV) testing is more sensitive for the detection of high-grade cervical lesions than is cytology, but detection of HPV by DNA screening in two screening rounds 5 years apart has not been assessed. The aim of this study was to assess whether HPV DNA testing in the first screen decreases detection of cervical intraepithelial neoplasia (CIN) grade 3 or worse, CIN grade 2 or worse, and cervical cancer in the second screening. METHODS In this randomised trial, women aged 29-56 years participating in the cervical screening programme in the Netherlands were randomly a…
- rank=2 `6561200` Efficacy of HPV DNA testing with cytology triage and/or repeat HPV DNA testing in primary cervical cancer screening.（本地heading=`BENCH_DOC_B64_NjU2MTIwMA`）

  > BACKGROUND Primary cervical screening with both human papillomavirus (HPV) DNA testing and cytological examination of cervical cells with a Pap test (cytology) has been evaluated in randomized clinical trials. Because the vast majority of women with positive cytology are also HPV DNA positive, screening strategies that use HPV DNA testing as the primary screening test may be more effective. METHODS We used the database from the intervention arm (n = 6,257 women) of a population-based randomized trial of double screening with cytology and HPV DNA testing to evaluate the efficacy of 11 possible…
- rank=3 `27873158` Efficacy of human papillomavirus testing for the detection of invasive cervical cancers and cervical intraepithelial neoplasia: a randomised controlled trial.（本地heading=`BENCH_DOC_B64_Mjc4NzMxNTg`）

  > BACKGROUND Human papillomavirus (HPV) testing is known to be more sensitive, but less specific than cytology for detecting cervical intraepithelial neoplasia (CIN). We assessed the efficacy of cervical-cancer screening policies that are based on HPV testing. METHODS Between March, 2004, and December, 2004, in two separate recruitment phases, women aged 25-60 years were randomly assigned to conventional cytology or to HPV testing in combination with liquid-based cytology (first phase) or alone (second phase). Randomisation was done by computer in two screening centres and by sequential opening…

首个可观测失败步骤：`dense_final_top10`。

直接证据：

- Dense gold首名次=Top10未命中
- Sparse gold首名次=1
- Hybrid-RRF gold首名次=2
- Hybrid-RRF+Rerank gold首名次=6
- 分类规则=sparse_only_success
- 逐候选分数=未采集

推断：Dense语义向量把主题相近干扰文档排在gold之前，而精确术语帮助Sparse命中；最大词项Jaccard=0.1250。

其他可能解释：相关性标注不完整、gold摘要与claim粒度不同、分块边界或Top10截断；当前终态run不能排除这些因素（sparse_only_success）。

反证实验：固定同一索引和query，额外留存Dense/Sparse候选Top100、融合逐项贡献、Rerank输入输出分数及chunk文本，再复跑该query验证首个内部失效点（sparse_only_success）。

### queryId=820

问题：N-terminal cleavage increases success identifying transcription start sites.

Gold文档：

- `8646760` Identification and Functional Characterization of N-Terminally Acetylated Proteins in Drosophila melanogaster

  > Protein modifications play a major role for most biological processes in living organisms. Amino-terminal acetylation of proteins is a common modification found throughout the tree of life: the N-terminus of a nascent polypeptide chain becomes co-translationally acetylated, often after the removal of the initiating methionine residue. While the enzymes and protein complexes involved in these processes have been extensively studied, only little is known about the biological function of such N-terminal modification events. To identify common principles of N-terminal acetylation, we analyzed the…

| 变体 | Recall@10 | MRR@10 | nDCG@10 | Gold首名次 | 延迟ms | Top10文档ID（*为gold） |
|---|---:|---:|---:|---:|---:|---|
| dense | 0.000000 | 0.000000 | 0.000000 | Top10未命中 | 2061 | 16056410, 25799020, 461550, 11498670, 25175223, 365896, 16167746, 36540079, 34498325, 35531883 |
| sparse | 1.000000 | 0.125000 | 0.315465 | 8 | 3054 | 12207167, 4387494, 36271512, 86217760, 26030079, 4890578, 5116145, 8646760* |
| hybrid_rrf | 1.000000 | 0.200000 | 0.386853 | 5 | 2603 | 35531883, 36540079, 4411760, 86217760, 8646760*, 502591, 8712839, 20052986, 19485243, 18467982 |
| hybrid_rrf_rerank | 1.000000 | 0.142857 | 0.333333 | 7 | 8981 | 19485243, 4411760, 8712839, 35531883, 36540079, 18467982, 8646760*, 86217760, 20052986, 502591 |

首个失败步骤中的关键错误召回文档（前3条非gold）：

- rank=1 `16056410` Posttranslational Acetylation of α-Tubulin Constrains Protofilament Number in Native Microtubules（本地heading=`BENCH_DOC_B64_MTYwNTY0MTA`）

  > BACKGROUND Microtubules are built from linear polymers of α-β tubulin dimers (protofilaments) that form a tubular quinary structure. Microtubules assembled from purified tubulin in vitro contain between 10 and 16 protofilaments; however, such structural polymorphisms are not found in cells. This discrepancy implies that factors other than tubulin constrain microtubule protofilament number, but the nature of these constraints is unknown. RESULTS Here, we show that acetylation of MEC-12 α-tubulin constrains protofilament number in C. elegans touch receptor neurons (TRNs). Whereas the sensory den…
- rank=2 `25799020` Direct isolation and identification of promoters in the human genome.（本地heading=`BENCH_DOC_B64_MjU3OTkwMjA`）

  > Transcriptional regulatory elements play essential roles in gene expression during animal development and cellular response to environmental signals, but our knowledge of these regions in the human genome is limited despite the availability of the complete genome sequence. Promoters mark the start of every transcript and are an important class of regulatory elements. A large, complex protein structure known as the pre-initiation complex (PIC) is assembled on all active promoters, and the presence of these proteins distinguishes promoters from other sequences in the genome. Using components of…
- rank=3 `461550` Multiplex genome engineering using CRISPR/Cas systems.（本地heading=`BENCH_DOC_B64_NDYxNTUw`）

  > Functional elucidation of causal genetic variants and elements requires precise genome editing technologies. The type II prokaryotic CRISPR (clustered regularly interspaced short palindromic repeats)/Cas adaptive immune system has been shown to facilitate RNA-guided site-specific DNA cleavage. We engineered two different type II CRISPR/Cas systems and demonstrate that Cas9 nucleases can be directed by short RNAs to induce precise cleavage at endogenous genomic loci in human and mouse cells. Cas9 can also be converted into a nicking enzyme to facilitate homology-directed repair with minimal mut…

首个可观测失败步骤：`dense_final_top10`。

直接证据：

- Dense gold首名次=Top10未命中
- Sparse gold首名次=8
- Hybrid-RRF gold首名次=5
- Hybrid-RRF+Rerank gold首名次=7
- 分类规则=sparse_only_success
- 逐候选分数=未采集

推断：Dense语义向量把主题相近干扰文档排在gold之前，而精确术语帮助Sparse命中；最大词项Jaccard=0.0135。

其他可能解释：相关性标注不完整、gold摘要与claim粒度不同、分块边界或Top10截断；当前终态run不能排除这些因素（sparse_only_success）。

反证实验：固定同一索引和query，额外留存Dense/Sparse候选Top100、融合逐项贡献、Rerank输入输出分数及chunk文本，再复跑该query验证首个内部失效点（sparse_only_success）。

### queryId=821

问题：N-terminal cleavage reduces success identifying transcription start sites.

Gold文档：

- `8646760` Identification and Functional Characterization of N-Terminally Acetylated Proteins in Drosophila melanogaster

  > Protein modifications play a major role for most biological processes in living organisms. Amino-terminal acetylation of proteins is a common modification found throughout the tree of life: the N-terminus of a nascent polypeptide chain becomes co-translationally acetylated, often after the removal of the initiating methionine residue. While the enzymes and protein complexes involved in these processes have been extensively studied, only little is known about the biological function of such N-terminal modification events. To identify common principles of N-terminal acetylation, we analyzed the…

| 变体 | Recall@10 | MRR@10 | nDCG@10 | Gold首名次 | 延迟ms | Top10文档ID（*为gold） |
|---|---:|---:|---:|---:|---:|---|
| dense | 0.000000 | 0.000000 | 0.000000 | Top10未命中 | 1432 | 34498325, 6333347, 13072112, 12642224, 25799020, 29214508, 14380875, 36540079, 11498670, 25175223 |
| sparse | 1.000000 | 0.125000 | 0.315465 | 8 | 1059 | 12207167, 4387494, 36271512, 86217760, 26030079, 4890578, 5116145, 8646760* |
| hybrid_rrf | 1.000000 | 0.125000 | 0.315465 | 8 | 2363 | 35531883, 4411760, 3805841, 36540079, 86217760, 26030079, 36904081, 8646760*, 18467982, 40667066 |
| hybrid_rrf_rerank | 1.000000 | 0.142857 | 0.333333 | 7 | 13281 | 40667066, 4411760, 35531883, 26030079, 36540079, 18467982, 8646760*, 86217760, 3805841, 36904081 |

首个失败步骤中的关键错误召回文档（前3条非gold）：

- rank=1 `34498325` A conserved modified wobble nucleoside (mcm5s2U) in lysyl-tRNA is required for viability in yeast.（本地heading=`BENCH_DOC_B64_MzQ0OTgzMjU`）

  > Transfer RNAs specific for Gln, Lys, and Glu from all organisms (except Mycoplasma) and organelles have a 2-thiouridine derivative (xm(5)s(2)U) as wobble nucleoside. These tRNAs read the A- and G-ending codons in the split codon boxes His/Gln, Asn/Lys, and Asp/Glu. In eukaryotic cytoplasmic tRNAs the conserved constituent (xm(5)-) in position 5 of uridine is 5-methoxycarbonylmethyl (mcm(5)). A protein (Tuc1p) from yeast resembling the bacterial protein TtcA, which is required for the synthesis of 2-thiocytidine in position 32 of the tRNA, was shown instead to be required for the synthesis of 2…
- rank=2 `6333347` AIR-2: An Aurora/Ipl1-related Protein Kinase Associated with Chromosomes and Midbody Microtubules Is  Required for Polar Body Extrusion and Cytokinesis  in Caenorhabditis elegans Embryos（本地heading=`BENCH_DOC_B64_NjMzMzM0Nw`）

  > An emerging family of kinases related to the Drosophila Aurora and budding yeast Ipl1 proteins has been implicated in chromosome segregation and mitotic spindle formation in a number of organisms. Unlike other Aurora/Ipl1-related kinases, the Caenorhabditis elegans orthologue, AIR-2, is associated with meiotic and mitotic chromosomes. AIR-2 is initially localized to the chromosomes of the most mature prophase I–arrested oocyte residing next to the spermatheca. This localization is dependent on the presence of sperm in the spermatheca. After fertilization, AIR-2 remains associated with chromoso…
- rank=3 `13072112` Distinction and relationship between elongation rate and processivity of RNA polymerase II in vivo.（本地heading=`BENCH_DOC_B64_MTMwNzIxMTI`）

  > A number of proteins and drugs have been implicated in the process of transcriptional elongation by RNA polymerase (Pol) II, but the factors that govern the elongation rate (nucleotide additions per min) and processivity (nucleotide additions per initiation event) in vivo are poorly understood. Here, we show that a mutation in the Rpb2 subunit of Pol II reduces both the elongation rate and processivity in vivo. In contrast, none of the putative elongation factors tested affect the elongation rate, although mutations in the THO complex and in Spt4 significantly reduce processivity. The drugs 6-…

首个可观测失败步骤：`dense_final_top10`。

直接证据：

- Dense gold首名次=Top10未命中
- Sparse gold首名次=8
- Hybrid-RRF gold首名次=8
- Hybrid-RRF+Rerank gold首名次=7
- 分类规则=sparse_only_success
- 逐候选分数=未采集

推断：Dense语义向量把主题相近干扰文档排在gold之前，而精确术语帮助Sparse命中；最大词项Jaccard=0.0135。

其他可能解释：相关性标注不完整、gold摘要与claim粒度不同、分块边界或Top10截断；当前终态run不能排除这些因素（sparse_only_success）。

反证实验：固定同一索引和query，额外留存Dense/Sparse候选Top100、融合逐项贡献、Rerank输入输出分数及chunk文本，再复跑该query验证首个内部失效点（sparse_only_success）。

## persistent_miss

### queryId=1

问题：0-dimensional biomaterials show inductive properties.

Gold文档：

- `31715818` New opportunities: the use of nanotechnologies to manipulate and track stem cells.

  > Nanotechnologies are emerging platforms that could be useful in measuring, understanding, and manipulating stem cells. Examples include magnetic nanoparticles and quantum dots for stem cell labeling and in vivo tracking; nanoparticles, carbon nanotubes, and polyplexes for the intracellular delivery of genes/oligonucleotides and protein/peptides; and engineered nanometer-scale scaffolds for stem cell differentiation and transplantation. This review examines the use of nanotechnologies for stem cell tracking, differentiation, and transplantation. We further discuss their utility and the potentia…

| 变体 | Recall@10 | MRR@10 | nDCG@10 | Gold首名次 | 延迟ms | Top10文档ID（*为gold） |
|---|---:|---:|---:|---:|---:|---|
| dense | 0.000000 | 0.000000 | 0.000000 | Top10未命中 | 2359 | 25404036, 19685306, 7583104, 40212412, 86217760, 17388232, 15337254, 14719322, 35711485, 29148743 |
| sparse | 0.000000 | 0.000000 | 0.000000 | Top10未命中 | 1462 | 12207167, 8317408, 36271512, 25937484, 21993510, 5431268, 86217760, 6549091 |
| hybrid_rrf | 0.000000 | 0.000000 | 0.000000 | Top10未命中 | 2754 | 86217760, 36271512, 34268160, 12207167, 25404036, 8317408, 19685306, 7583104 |
| hybrid_rrf_rerank | 0.000000 | 0.000000 | 0.000000 | Top10未命中 | 15175 | 86217760, 12207167, 19685306, 36271512, 25404036, 7583104, 34268160, 8317408 |

首个失败步骤中的关键错误召回文档（前3条非gold）：

- rank=1 `25404036` Three-Dimensional Modeling and Quantitative Analysis of Gap Junction Distributions in Cardiac Tissue（本地heading=`BENCH_DOC_B64_MjU0MDQwMzY`）

  > Gap junctions play a fundamental role in intercellular communication in cardiac tissue. Various types of heart disease including hypertrophy and ischemia are associated with alterations of the spatial arrangement of gap junctions. Previous studies applied two-dimensional optical and electron-microscopy to visualize gap junction arrangements. In normal cardiomyocytes, gap junctions were primarily found at cell ends, but can be found also in more central regions. In this study, we extended these approaches toward three-dimensional reconstruction of gap junction distributions based on high-resolu…
- rank=2 `19685306` Orientationally invariant indices of axon diameter and density from diffusion MRI.（本地heading=`BENCH_DOC_B64_MTk2ODUzMDY`）

  > This paper proposes and tests a technique for imaging orientationally invariant indices of axon diameter and density in white matter using diffusion magnetic resonance imaging. Such indices potentially provide more specific markers of white matter microstructure than standard indices from diffusion tensor imaging. Orientational invariance allows for combination with tractography and presents new opportunities for mapping brain connectivity and quantifying disease processes. The technique uses a four-compartment tissue model combined with an optimized multishell high-angular-resolution pulsed-g…
- rank=3 `7583104` IDEAL in meshes for prolapse, urinary incontinence, and hernia repair.（本地heading=`BENCH_DOC_B64_NzU4MzEwNA`）

  > PURPOSE Mesh surgeries are counted among the most frequently applied surgical procedures. Despite global spread of mesh applying surgeries, there is no current systematic analysis of incidence and possible prevention of adverse events after mesh implantation. MATERIALS AND METHODS Based on the recommendations of IDEAL an in vitro test system for biocompatibility of surgical meshes has been generated (Innovation). Coating strategies for biocompatibility optimization have been developed (Development). The native and modified alloplastic materials have been tested in an animal model over 2 years…

首个可观测失败步骤：`dense_and_sparse_final_top10`。

直接证据：

- Dense gold首名次=Top10未命中
- Sparse gold首名次=Top10未命中
- Hybrid-RRF gold首名次=Top10未命中
- Hybrid-RRF+Rerank gold首名次=Top10未命中
- 分类规则=persistent_miss
- 逐候选分数=未采集

推断：Top10截断、语义近邻竞争或Rerank排序偏好可能共同影响；query与gold最大词项Jaccard=0.0000。

其他可能解释：相关性标注不完整、gold摘要与claim粒度不同、分块边界或Top10截断；当前终态run不能排除这些因素（persistent_miss）。

反证实验：固定同一索引和query，额外留存Dense/Sparse候选Top100、融合逐项贡献、Rerank输入输出分数及chunk文本，再复跑该query验证首个内部失效点（persistent_miss）。

### queryId=1100

问题：Statins increase blood cholesterol.

Gold文档：

- `7662206` A Century of Cholesterol and Coronaries: From Plaques to Genes to Statins

  > One-fourth of all deaths in industrialized countries result from coronary heart disease. A century of research has revealed the essential causative agent: cholesterol-carrying low-density lipoprotein (LDL). LDL is controlled by specific receptors (LDLRs) in liver that remove it from blood. Mutations that eliminate LDLRs raise LDL and cause heart attacks in childhood, whereas mutations that raise LDLRs reduce LDL and diminish heart attacks. If we are to eliminate coronary disease, lowering LDL should be the primary goal. Effective means to achieve this goal are currently available. The key ques…

| 变体 | Recall@10 | MRR@10 | nDCG@10 | Gold首名次 | 延迟ms | Top10文档ID（*为gold） |
|---|---:|---:|---:|---:|---:|---|
| dense | 0.000000 | 0.000000 | 0.000000 | Top10未命中 | 1816 | 21557614, 30981192, 4687948, 22420524, 43629704, 5698494, 13933299, 11557602 |
| sparse | 0.000000 | 0.000000 | 0.000000 | Top10未命中 | 1002 | 12207167, 86217760, 36271512, 13801259 |
| hybrid_rrf | 0.000000 | 0.000000 | 0.000000 | Top10未命中 | 2167 | 21557614, 13933299, 7552215, 24005548, 10692948, 198309074, 9315213, 9617381, 22420524, 46202852 |
| hybrid_rrf_rerank | 0.000000 | 0.000000 | 0.000000 | Top10未命中 | 14772 | 21557614, 24005548, 22420524, 9617381, 7552215, 10692948, 198309074, 9315213, 46202852, 13933299 |

首个失败步骤中的关键错误召回文档（前3条非gold）：

- rank=1 `21557614` Pleiotropic effects of statins.（本地heading=`BENCH_DOC_B64_MjE1NTc2MTQ`）

  > Statins are potent inhibitors of cholesterol biosynthesis. In clinical trials, statins are beneficial in the primary and secondary prevention of coronary heart disease. However, the overall benefits observed with statins appear to be greater than what might be expected from changes in lipid levels alone, suggesting effects beyond cholesterol lowering. Indeed, recent studies indicate that some of the cholesterol-independent or "pleiotropic" effects of statins involve improving endothelial function, enhancing the stability of atherosclerotic plaques, decreasing oxidative stress and inflammation,…
- rank=2 `30981192` How to control residual cardiovascular risk despite statin treatment: focusing on HDL-cholesterol.（本地heading=`BENCH_DOC_B64_MzA5ODExOTI`）

  > Lowering low-density lipoprotein-cholesterol (LDL-C) is the primary target in the management of dyslipidemia in patients at high risk of cardiovascular disease. However, patients who have achieved LDL-C levels below the currently recommended targets may still experience cardiovascular events. This may result, in part, from elevated triglyceride (TG) levels and low levels of high-density lipoprotein-cholesterol (HDL-C). Low HDL-C and high TG levels are common and are recognized as independent risk factors for cardiovascular morbidity and mortality. Furthermore, atherogenic dyslipidemia, charact…
- rank=3 `4687948` HMG-CoA reductase inhibitors and the risk of hip fractures in elderly patients.（本地heading=`BENCH_DOC_B64_NDY4Nzk0OA`）

  > CONTEXT Recent animal studies have found that 3-hydroxy-3-methylglutaryl coenzyme A (HMG-CoA) lipid-lowering drugs (statins) substantially increase bone formation, but whether statin use in humans results in clinically meaningful bone formation or a reduction in the risk of osteoporotic fractures is not known. OBJECTIVE To determine whether the use of statins is associated with reduced hip fracture risk. DESIGN Case-control study. SETTING AND PATIENTS A total of 6110 New Jersey residents aged 65 years or older and enrolled in Medicare and either Medicaid or the Pharmacy Assistance for the Aged…

首个可观测失败步骤：`dense_and_sparse_final_top10`。

直接证据：

- Dense gold首名次=Top10未命中
- Sparse gold首名次=Top10未命中
- Hybrid-RRF gold首名次=Top10未命中
- Hybrid-RRF+Rerank gold首名次=Top10未命中
- 分类规则=persistent_miss
- 逐候选分数=未采集

推断：Top10截断、语义近邻竞争或Rerank排序偏好可能共同影响；query与gold最大词项Jaccard=0.0429。

其他可能解释：相关性标注不完整、gold摘要与claim粒度不同、分块边界或Top10截断；当前终态run不能排除这些因素（persistent_miss）。

反证实验：固定同一索引和query，额外留存Dense/Sparse候选Top100、融合逐项贡献、Rerank输入输出分数及chunk文本，再复跑该query验证首个内部失效点（persistent_miss）。

### queryId=1110

问题：Suboptimal nutrition is not predictive of chronic disease

Gold文档：

- `13770184` Global, regional, and national comparative risk assessment of 79 behavioural, environmental and occupational, and metabolic risks or clusters of risks, 1990–2015: a systematic analysis for the Global Burden of Disease Study 2015

  > BACKGROUND The Global Burden of Diseases, Injuries, and Risk Factors Study 2015 provides an up-to-date synthesis of the evidence for risk factor exposure and the attributable burden of disease. By providing national and subnational assessments spanning the past 25 years, this study can inform debates on the importance of addressing risks in context. METHODS We used the comparative risk assessment framework developed for previous iterations of the Global Burden of Disease Study to estimate attributable deaths, disability-adjusted life-years (DALYs), and trends in exposure by age group, sex, yea…

| 变体 | Recall@10 | MRR@10 | nDCG@10 | Gold首名次 | 延迟ms | Top10文档ID（*为gold） |
|---|---:|---:|---:|---:|---:|---|
| dense | 0.000000 | 0.000000 | 0.000000 | Top10未命中 | 963 | 21274919, 8529693, 32766786, 6327940, 39851630, 13338820, 5145974, 4303939, 12205576, 21993510 |
| sparse | 0.000000 | 0.000000 | 0.000000 | Top10未命中 | 863 | 12236208, 33409100, 97884, 19673227, 21884059, 23918031, 3752408, 34378726, 43128141, 29657303 |
| hybrid_rrf | 0.000000 | 0.000000 | 0.000000 | Top10未命中 | 1087 | 32766786, 33409100, 12236208, 58050905, 41264017, 12205576, 5145974, 31562330, 23983289, 3828508 |
| hybrid_rrf_rerank | 0.000000 | 0.000000 | 0.000000 | Top10未命中 | 6981 | 33409100, 12205576, 31562330, 3828508, 41264017, 23983289, 58050905, 12236208, 5145974, 32766786 |

首个失败步骤中的关键错误召回文档（前3条非gold）：

- rank=1 `21274919` The association between common physical impairments and dementia in low and middle income countries, and, among people with dementia, their association with cognitive function and disability. A 10/66 Dementia Research Group population-based study.（本地heading=`BENCH_DOC_B64_MjEyNzQ5MTk`）

  > OBJECTIVE Chronic physical comorbidity is common in dementia. However, there is an absence of evidence to support good practice guidelines for attention to these problems. We aimed to study the extent of this comorbidity and its impact on cognitive function and disability in population-based studies in low and middle income countries, where chronic diseases and impairments are likely to be both common and undertreated. METHODS A multicentre cross-sectional survey of all over 65 year old residents (n = 15 022) in 11 catchment areas in China, India, Cuba, Dominican Republic, Venezuela, Mexico an…
- rank=2 `8529693` Maternal and child undernutrition: consequences for adult health and human capital（本地heading=`BENCH_DOC_B64_ODUyOTY5Mw`）

  > In this paper we review the associations between maternal and child undernutrition with human capital and risk of adult diseases in low-income and middle-income countries. We analysed data from five long-standing prospective cohort studies from Brazil, Guatemala, India, the Philippines, and South Africa and noted that indices of maternal and child undernutrition (maternal height, birthweight, intrauterine growth restriction, and weight, height, and body-mass index at 2 years according to the new WHO growth standards) were related to adult outcomes (height, schooling, income or assets, offsprin…
- rank=3 `32766786` Neoadjuvant androgen ablation before radical prostatectomy in cT2bNxMo prostate cancer: 5-year results.（本地heading=`BENCH_DOC_B64_MzI3NjY3ODY`）

  > PURPOSE In the initial report of the Lupron Depot Neoadjuvant Prostate Cancer Study Group patients who received 3 months of androgen deprivation had a significant decrease in the positive margin rate. We monitored these patients for 5 years and to our knowledge present the longest followup of any neoadjuvant trial. MATERIALS AND METHODS A multi-institutional prospective randomized trial was performed between February 1992 and April 1994 involving patients with stage cT2b prostate cancer, including 138 who received 3 months of leuprolide plus flutamide before radical prostatectomy and 144 who u…

首个可观测失败步骤：`dense_and_sparse_final_top10`。

直接证据：

- Dense gold首名次=Top10未命中
- Sparse gold首名次=Top10未命中
- Hybrid-RRF gold首名次=Top10未命中
- Hybrid-RRF+Rerank gold首名次=Top10未命中
- 分类规则=persistent_miss
- 逐候选分数=未采集

推断：Top10截断、语义近邻竞争或Rerank排序偏好可能共同影响；query与gold最大词项Jaccard=0.0250。

其他可能解释：相关性标注不完整、gold摘要与claim粒度不同、分块边界或Top10截断；当前终态run不能排除这些因素（persistent_miss）。

反证实验：固定同一索引和query，额外留存Dense/Sparse候选Top100、融合逐项贡献、Rerank输入输出分数及chunk文本，再复跑该query验证首个内部失效点（persistent_miss）。

## rerank_reorder_gain

### queryId=1204

问题：The combination of H3K4me3 and H3K79me2 is found in quiescent hair follicle stem cells.

Gold文档：

- `31141365` Genome-wide maps of histone modifications unwind in vivo chromatin states of the hair follicle lineage.

  > Using mouse skin, where bountiful reservoirs of synchronized hair follicle stem cells (HF-SCs) fuel cycles of regeneration, we explore how adult SCs remodel chromatin in response to activating cues. By profiling global mRNA and chromatin changes in quiescent and activated HF-SCs and their committed, transit-amplifying (TA) progeny, we show that polycomb-group (PcG)-mediated H3K27-trimethylation features prominently in HF-lineage progression by mechanisms distinct from embryonic-SCs. In HF-SCs, PcG represses nonskin lineages and HF differentiation. In TA progeny, nonskin regulators remain PcG-r…

| 变体 | Recall@10 | MRR@10 | nDCG@10 | Gold首名次 | 延迟ms | Top10文档ID（*为gold） |
|---|---:|---:|---:|---:|---:|---|
| dense | 1.000000 | 1.000000 | 1.000000 | 1 | 536 | 31141365*, 2701077, 3669694, 13048272, 4312169, 3849194, 7426741, 17271462, 10165258, 30468386 |
| sparse | 0.000000 | 0.000000 | 0.000000 | Top10未命中 | 207 | 6082738, 4335423, 27686445, 7583161, 1848452, 20996244, 31439189, 34982259, 15907458, 21676556 |
| hybrid_rrf | 1.000000 | 0.100000 | 0.289065 | 10 | 1724 | 17271462, 20996244, 30468386, 31439189, 15907458, 1889358, 25597580, 6308416, 6082738, 31141365* |
| hybrid_rrf_rerank | 1.000000 | 1.000000 | 1.000000 | 1 | 12766 | 31141365*, 17271462, 20996244, 25597580, 15907458, 30468386, 6082738, 31439189, 1889358, 6308416 |

首个失败步骤中的关键错误召回文档（前3条非gold）：

- rank=1 `17271462` Tie2/Angiopoietin-1 Signaling Regulates Hematopoietic Stem Cell Quiescence in the Bone Marrow Niche（本地heading=`BENCH_DOC_B64_MTcyNzE0NjI`）

  > The quiescent state is thought to be an indispensable property for the maintenance of hematopoietic stem cells (HSCs). Interaction of HSCs with their particular microenvironments, known as the stem cell niches, is critical for adult hematopoiesis in the bone marrow (BM). Here, we demonstrate that HSCs expressing the receptor tyrosine kinase Tie2 are quiescent and antiapoptotic, and comprise a side-population (SP) of HSCs, which adhere to osteoblasts (OBs) in the BM niche. The interaction of Tie2 with its ligand Angiopoietin-1 (Ang-1) induced cobblestone formation of HSCs in vitro and maintaine…
- rank=2 `20996244` Nonproductive human immunodeficiency virus type 1 infection in nucleoside-treated G0 lymphocytes.（本地heading=`BENCH_DOC_B64_MjA5OTYyNDQ`）

  > Productive infection by human immunodeficiency virus type 1 (HIV-1) requires the activation of target cells. Infection of quiescent peripheral CD4 lymphocytes by HIV-1 results in incomplete, labile, reverse transcripts. We have previously identified G1b as the cell cycle stage required for the optimal completion of the reverse transcription process in T lymphocytes. However, the mechanism(s) involved in the blockage of reverse transcription remains undefined. In this study we investigated whether nucleotide levels influence viral reverse transcription in G0 cells. For this purpose the role of…
- rank=3 `30468386` Adult c-Kit(+) progenitor cells are necessary for maintenance and regeneration of olfactory neurons.（本地heading=`BENCH_DOC_B64_MzA0NjgzODY`）

  > The olfactory epithelium houses chemosensory neurons, which transmit odor information from the nose to the brain. In adult mammals, the olfactory epithelium is a uniquely robust neuroproliferative zone, with the ability to replenish its neuronal and non-neuronal populations due to the presence of germinal basal cells. The stem and progenitor cells of these germinal layers, and their regulatory mechanisms, remain incompletely defined. Here we show that progenitor cells expressing c-Kit, a receptor tyrosine kinase marking stem cells in a variety of embryonic tissues, are required for maintenance…

首个可观测失败步骤：`hybrid_rrf_rank_order`。

直接证据：

- Dense gold首名次=1
- Sparse gold首名次=Top10未命中
- Hybrid-RRF gold首名次=10
- Hybrid-RRF+Rerank gold首名次=1
- 分类规则=rerank_reorder_gain
- 逐候选分数=未采集

推断：稀疏词项匹配对同义改写或词形差异不敏感；query与gold标题/片段最大词项Jaccard=0.1154。

其他可能解释：相关性标注不完整、gold摘要与claim粒度不同、分块边界或Top10截断；当前终态run不能排除这些因素（rerank_reorder_gain）。

反证实验：固定同一索引和query，额外留存Dense/Sparse候选Top100、融合逐项贡献、Rerank输入输出分数及chunk文本，再复跑该query验证首个内部失效点（rerank_reorder_gain）。

### queryId=237

问题：Cells lacking clpC have a defect in sporulation efficiency in Bacillus subtilis.

Gold文档：

- `4942718` High-Throughput Genetic Screens Identify a Large and Diverse Collection of New Sporulation Genes in Bacillus subtilis

  > The differentiation of the bacterium Bacillus subtilis into a dormant spore is among the most well-characterized developmental pathways in biology. Classical genetic screens performed over the past half century identified scores of factors involved in every step of this morphological process. More recently, transcriptional profiling uncovered additional sporulation-induced genes required for successful spore development. Here, we used transposon-sequencing (Tn-seq) to assess whether there were any sporulation genes left to be discovered. Our screen identified 133 out of the 148 genes with know…

| 变体 | Recall@10 | MRR@10 | nDCG@10 | Gold首名次 | 延迟ms | Top10文档ID（*为gold） |
|---|---:|---:|---:|---:|---:|---|
| dense | 1.000000 | 1.000000 | 1.000000 | 1 | 1332 | 4942718*, 26625002, 34498325, 24645237, 31844040, 10660080, 24706198, 21479575, 39225849 |
| sparse | 0.000000 | 0.000000 | 0.000000 | Top10未命中 | 1178 | 712078, 38793927, 4256553, 36211049, 37578311, 20996244, 37673301, 3330111, 16119973, 19343151 |
| hybrid_rrf | 1.000000 | 0.100000 | 0.289065 | 10 | 1549 | 712078, 20996244, 25550665, 39225849, 12881593, 2140513, 464511, 24706198, 27438378, 4942718* |
| hybrid_rrf_rerank | 1.000000 | 1.000000 | 1.000000 | 1 | 8185 | 4942718*, 12881593, 39225849, 24706198, 464511, 712078, 25550665, 20996244, 2140513, 27438378 |

首个失败步骤中的关键错误召回文档（前3条非gold）：

- rank=1 `712078` Pharmacological correction of a defect in PPARγ signaling ameliorates disease severity in Cftr-deficient mice（本地heading=`BENCH_DOC_B64_NzEyMDc4`）

  > Cystic fibrosis is caused by mutations in the cystic fibrosis transmembrane conductance regulator (encoded by Cftr) that impair its role as an apical chloride channel that supports bicarbonate transport. Individuals with cystic fibrosis show retained, thickened mucus that plugs airways and obstructs luminal organs as well as numerous other abnormalities that include inflammation of affected organs, alterations in lipid metabolism and insulin resistance. Here we show that colonic epithelial cells and whole lung tissue from Cftr-deficient mice show a defect in peroxisome proliferator-activated r…
- rank=2 `20996244` Nonproductive human immunodeficiency virus type 1 infection in nucleoside-treated G0 lymphocytes.（本地heading=`BENCH_DOC_B64_MjA5OTYyNDQ`）

  > Productive infection by human immunodeficiency virus type 1 (HIV-1) requires the activation of target cells. Infection of quiescent peripheral CD4 lymphocytes by HIV-1 results in incomplete, labile, reverse transcripts. We have previously identified G1b as the cell cycle stage required for the optimal completion of the reverse transcription process in T lymphocytes. However, the mechanism(s) involved in the blockage of reverse transcription remains undefined. In this study we investigated whether nucleotide levels influence viral reverse transcription in G0 cells. For this purpose the role of…
- rank=3 `25550665` BLM is required for faithful chromosome segregation and its localization defines a class of ultrafine anaphase bridges.（本地heading=`BENCH_DOC_B64_MjU1NTA2NjU`）

  > Mutations in BLM cause Bloom's syndrome, a disorder associated with cancer predisposition and chromosomal instability. We investigated whether BLM plays a role in ensuring the faithful chromosome segregation in human cells. We show that BLM-defective cells display a higher frequency of anaphase bridges and lagging chromatin than do isogenic corrected derivatives that eptopically express the BLM protein. In normal cells undergoing mitosis, BLM protein localizes to anaphase bridges, where it colocalizes with its cellular partners, topoisomerase IIIalpha and hRMI1 (BLAP75). Using BLM staining as…

首个可观测失败步骤：`hybrid_rrf_rank_order`。

直接证据：

- Dense gold首名次=1
- Sparse gold首名次=Top10未命中
- Hybrid-RRF gold首名次=10
- Hybrid-RRF+Rerank gold首名次=1
- 分类规则=rerank_reorder_gain
- 逐候选分数=未采集

推断：稀疏词项匹配对同义改写或词形差异不敏感；query与gold标题/片段最大词项Jaccard=0.0471。

其他可能解释：相关性标注不完整、gold摘要与claim粒度不同、分块边界或Top10截断；当前终态run不能排除这些因素（rerank_reorder_gain）。

反证实验：固定同一索引和query，额外留存Dense/Sparse候选Top100、融合逐项贡献、Rerank输入输出分数及chunk文本，再复跑该query验证首个内部失效点（rerank_reorder_gain）。

### queryId=1207

问题：The composition of myosin-II isoform switches from the polarizable B isoform to the more homogenous A isoform during hematopoietic differentiation.

Gold文档：

- `18909530` Contractile forces sustain and polarize hematopoiesis from stem and progenitor cells.

  > Self-renewal and differentiation of stem cells depend on asymmetric division and polarized motility processes that in other cell types are modulated by nonmuscle myosin-II (MII) forces and matrix mechanics. Here, mass spectrometry-calibrated intracellular flow cytometry of human hematopoiesis reveals MIIB to be a major isoform that is strongly polarized in hematopoietic stem cells and progenitors (HSC/Ps) and thereby downregulated in differentiated cells via asymmetric division. MIIA is constitutive and activated by dephosphorylation during cytokine-triggered differentiation of cells grown on…

| 变体 | Recall@10 | MRR@10 | nDCG@10 | Gold首名次 | 延迟ms | Top10文档ID（*为gold） |
|---|---:|---:|---:|---:|---:|---|
| dense | 1.000000 | 1.000000 | 1.000000 | 1 | 756 | 18909530*, 243694, 34016987, 3152612, 4254064, 38380061, 6333347, 10086360, 9507605, 28517384 |
| sparse | 0.000000 | 0.000000 | 0.000000 | Top10未命中 | 1168 | 5775033, 44562221, 10607877, 26133404, 8629328, 28193026, 8246922, 33063763, 16626846, 21719289 |
| hybrid_rrf | 1.000000 | 0.111111 | 0.301030 | 9 | 1349 | 36271512, 9507605, 42787108, 19701340, 7114092, 22036571, 29828242, 5775033, 18909530*, 44562221 |
| hybrid_rrf_rerank | 1.000000 | 1.000000 | 1.000000 | 1 | 7602 | 18909530*, 22036571, 9507605, 7114092, 44562221, 42787108, 36271512, 29828242, 5775033, 19701340 |

首个失败步骤中的关键错误召回文档（前3条非gold）：

- rank=1 `36271512` T-cell activation.（本地heading=`BENCH_DOC_B64_MzYyNzE1MTI`）

  > INTRODUCTION • • CELLULAR AND MOLECULAR REQUIREMENTS FOR T-CELL ACTIVATION . The T-Cell Antigen Receptor Complex . . . .. . . . ..... . . . . . . . . . . . . . . . . ...... . . . T-Cell Activation by Antibodies and Leetins . . . . . . . . . . .. . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . Other Cell Surface Structures (Accessory Molecules) Involved in Antigen Recognition and Activation . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . .…
- rank=2 `9507605` Focal contacts as mechanosensors: externally applied local mechanical force induces growth of focal contacts by an mDia1-dependent and ROCKindependent mechanism（本地heading=`BENCH_DOC_B64_OTUwNzYwNQ`）

  > The transition of cell–matrix adhesions from the initial punctate focal complexes into the mature elongated form, known as focal contacts, requires GTPase Rho activity. In particular, activation of myosin II–driven contractility by a Rho target known as Rho-associated kinase (ROCK) was shown to be essential for focal contact formation. To dissect the mechanism of Rho-dependent induction of focal contacts and to elucidate the role of cell contractility, we applied mechanical force to vinculin-containing dot-like adhesions at the cell edge using a micropipette. Local centripetal pulling led to l…
- rank=3 `42787108` Nodal/Activin signaling predicts human pluripotent stem cell lines prone to differentiate toward the hematopoietic lineage.（本地heading=`BENCH_DOC_B64_NDI3ODcxMDg`）

  > Lineage-specific differentiation potential varies among different human pluripotent stem cell (hPSC) lines, becoming therefore highly desirable to prospectively know which hPSC lines exhibit the highest differentiation potential for a certain lineage. We have compared the hematopoietic potential of 14 human embryonic stem cell (hESC)/induced pluripotent stem cell (iPSC) lines. The emergence of hemogenic progenitors, primitive and mature blood cells, and colony-forming unit (CFU) potential was analyzed at different time points. Significant differences in the propensity to differentiate toward b…

首个可观测失败步骤：`hybrid_rrf_rank_order`。

直接证据：

- Dense gold首名次=1
- Sparse gold首名次=Top10未命中
- Hybrid-RRF gold首名次=9
- Hybrid-RRF+Rerank gold首名次=1
- 分类规则=rerank_reorder_gain
- 逐候选分数=未采集

推断：稀疏词项匹配对同义改写或词形差异不敏感；query与gold标题/片段最大词项Jaccard=0.1233。

其他可能解释：相关性标注不完整、gold摘要与claim粒度不同、分块边界或Top10截断；当前终态run不能排除这些因素（rerank_reorder_gain）。

反证实验：固定同一索引和query，额外留存Dense/Sparse候选Top100、融合逐项贡献、Rerank输入输出分数及chunk文本，再复跑该query验证首个内部失效点（rerank_reorder_gain）。

## rerank_reorder_harm

### queryId=956

问题：Pleiotropic coupling of GLP-1R to intracellular effectors promotes distinct profiles of cellular signaling.

Gold文档：

- `12956194` The Extracellular Surface of the GLP-1 Receptor Is a Molecular Trigger for Biased Agonism

  > Ligand-directed signal bias offers opportunities for sculpting molecular events, with the promise of better, safer therapeutics. Critical to the exploitation of signal bias is an understanding of the molecular events coupling ligand binding to intracellular signaling. Activation of class B G protein-coupled receptors is driven by interaction of the peptide N terminus with the receptor core. To understand how this drives signaling, we have used advanced analytical methods that enable separation of effects on pathway-specific signaling from those that modify agonist affinity and mapped the funct…

| 变体 | Recall@10 | MRR@10 | nDCG@10 | Gold首名次 | 延迟ms | Top10文档ID（*为gold） |
|---|---:|---:|---:|---:|---:|---|
| dense | 1.000000 | 0.166667 | 0.356207 | 6 | 1933 | 14719322, 24624992, 3127341, 6061927, 13411519, 12956194*, 21754541, 3610282, 16511863 |
| sparse | 1.000000 | 0.166667 | 0.356207 | 6 | 940 | 31107919, 8246922, 17933691, 19701340, 36271512, 12956194*, 20821402, 11256632, 22623275, 44935041 |
| hybrid_rrf | 1.000000 | 1.000000 | 1.000000 | 1 | 4231 | 12956194*, 11557602, 31107919, 11200685, 3127341, 6144969, 16511863, 7433668, 4321947, 4611267 |
| hybrid_rrf_rerank | 1.000000 | 0.200000 | 0.386853 | 5 | 18305 | 31107919, 7433668, 3127341, 11200685, 12956194*, 4611267, 4321947, 6144969, 16511863, 11557602 |

首个失败步骤中的关键错误召回文档（前3条非gold）：

- rank=1 `31107919` Differential Requirement of the Extracellular Domain in Activation of Class B G Protein-coupled Receptors.（本地heading=`BENCH_DOC_B64_MzExMDc5MTk`）

  > G protein-coupled receptors (GPCRs) from the secretin-like (class B) family are key players in hormonal homeostasis and are important drug targets for the treatment of metabolic disorders and neuronal diseases. They consist of a large N-terminal extracellular domain (ECD) and a transmembrane domain (TMD) with the GPCR signature of seven transmembrane helices. Class B GPCRs are activated by peptide hormones with their C termini bound to the receptor ECD and their N termini bound to the TMD. It is thought that the ECD functions as an affinity trap to bind and localize the hormone to the receptor…
- rank=2 `7433668` Preexisting helminth infection induces inhibition of innate pulmonary anti-tuberculosis defense by engaging the IL-4 receptor pathway（本地heading=`BENCH_DOC_B64_NzQzMzY2OA`）

  > Tuberculosis and helminthic infections coexist in many parts of the world, yet the impact of helminth-elicited Th2 responses on the ability of the host to control Mycobacterium tuberculosis (Mtb) infection has not been fully explored. We show that mice infected with the intestinal helminth Nippostrongylus brasiliensis (Nb) exhibit a transitory impairment of resistance to airborne Mtb infection. Furthermore, a second dose of Nb infection substantially increases the bacterial burden in the lungs of co-infected mice. Interestingly, the Th2 response in the co-infected animals did not impair the on…
- rank=3 `3127341` Polymorphism and ligand dependent changes in human glucagon-like peptide-1 receptor (GLP-1R) function: allosteric rescue of loss of function mutation.（本地heading=`BENCH_DOC_B64_MzEyNzM0MQ`）

  > The glucagon-like peptide-1 receptor (GLP-1R) is a key physiological regulator of insulin secretion and a major therapeutic target for the treatment of type II diabetes. However, regulation of GLP-1R function is complex with multiple endogenous peptides that interact with the receptor, including full-length (1-37) and truncated (7-37) forms of GLP-1 that can exist in an amidated form (GLP-1(1-36)NH₂ and GLP-1(7-36)NH₂) and the related peptide oxyntomodulin. In addition, the GLP-1R possesses exogenous agonists, including exendin-4, and the allosteric modulator, compound 2 (6,7-dichloro-2-methyl…

首个可观测失败步骤：`rerank_final_top10`。

直接证据：

- Dense gold首名次=6
- Sparse gold首名次=6
- Hybrid-RRF gold首名次=1
- Hybrid-RRF+Rerank gold首名次=5
- 分类规则=rerank_reorder_harm
- 逐候选分数=未采集

推断：Top10截断、语义近邻竞争或Rerank排序偏好可能共同影响；query与gold最大词项Jaccard=0.0779。

其他可能解释：相关性标注不完整、gold摘要与claim粒度不同、分块边界或Top10截断；当前终态run不能排除这些因素（rerank_reorder_harm）。

反证实验：固定同一索引和query，额外留存Dense/Sparse候选Top100、融合逐项贡献、Rerank输入输出分数及chunk文本，再复跑该query验证首个内部失效点（rerank_reorder_harm）。

### queryId=212

问题：CR is associated with higher methylation age.

Gold文档：

- `22038539` Caloric restriction delays age-related methylation drift

  > In mammals, caloric restriction consistently results in extended lifespan. Epigenetic information encoded by DNA methylation is tightly regulated, but shows a striking drift associated with age that includes both gains and losses of DNA methylation at various sites. Here, we report that epigenetic drift is conserved across species and the rate of drift correlates with lifespan when comparing mice, rhesus monkeys, and humans. Twenty-two to 30-year-old rhesus monkeys exposed to 30% caloric restriction since 7-14 years of age showed attenuation of age-related methylation drift compared to ad libi…

| 变体 | Recall@10 | MRR@10 | nDCG@10 | Gold首名次 | 延迟ms | Top10文档ID（*为gold） |
|---|---:|---:|---:|---:|---:|---|
| dense | 1.000000 | 0.500000 | 0.630930 | 2 | 1227 | 7808055, 22038539*, 41548287, 9291668, 4679264, 14475235, 23665162, 12324049 |
| sparse | 1.000000 | 0.111111 | 0.301030 | 9 | 570 | 12207167, 439670, 42150015, 24783597, 39661951, 10692948, 25420421, 13765757, 22038539*, 23649163 |
| hybrid_rrf | 1.000000 | 1.000000 | 1.000000 | 1 | 2338 | 22038539*, 14475235, 7808055, 4434951, 663464, 9291668, 10692948, 11935250 |
| hybrid_rrf_rerank | 1.000000 | 0.250000 | 0.430677 | 4 | 18591 | 7808055, 14475235, 4434951, 22038539*, 9291668, 11935250, 663464, 10692948 |

首个失败步骤中的关键错误召回文档（前3条非gold）：

- rank=1 `7808055` DNA methylation age of human tissues and cell types（本地heading=`BENCH_DOC_B64_NzgwODA1NQ`）

  > BACKGROUND It is not yet known whether DNA methylation levels can be used to accurately predict age across a broad spectrum of human tissues and cell types, nor whether the resulting age prediction is a biologically meaningful measure. RESULTS I developed a multi-tissue predictor of age that allows one to estimate the DNA methylation age of most tissues and cell types. The predictor, which is freely available, was developed using 8,000 samples from 82 Illumina DNA methylation array datasets, encompassing 51 healthy tissues and cell types. I found that DNA methylation age has the following prop…
- rank=2 `14475235` Age-Associated Sperm DNA Methylation Alterations: Possible Implications in Offspring Disease Susceptibility（本地heading=`BENCH_DOC_B64_MTQ0NzUyMzU`）

  > Recent evidence demonstrates a role for paternal aging on offspring disease susceptibility. It is well established that various neuropsychiatric disorders (schizophrenia, autism, etc.), trinucleotide expansion associated diseases (myotonic dystrophy, Huntington's, etc.) and even some forms of cancer have increased incidence in the offspring of older fathers. Despite strong epidemiological evidence that these alterations are more common in offspring sired by older fathers, in most cases the mechanisms that drive these processes are unclear. However, it is commonly believed that epigenetics, and…
- rank=3 `4434951` Diverse interventions that extend mouse lifespan suppress shared age-associated epigenetic changes at critical gene regulatory regions（本地heading=`BENCH_DOC_B64_NDQzNDk1MQ`）

  > BACKGROUND Age-associated epigenetic changes are implicated in aging. Notably, age-associated DNA methylation changes comprise a so-called aging "clock", a robust biomarker of aging. However, while genetic, dietary and drug interventions can extend lifespan, their impact on the epigenome is uncharacterised. To fill this knowledge gap, we defined age-associated DNA methylation changes at the whole-genome, single-nucleotide level in mouse liver and tested the impact of longevity-promoting interventions, specifically the Ames dwarf Prop1 df/df mutation, calorie restriction and rapamycin. RESULTS…

首个可观测失败步骤：`rerank_final_top10`。

直接证据：

- Dense gold首名次=2
- Sparse gold首名次=9
- Hybrid-RRF gold首名次=1
- Hybrid-RRF+Rerank gold首名次=4
- 分类规则=rerank_reorder_harm
- 逐候选分数=未采集

推断：Top10截断、语义近邻竞争或Rerank排序偏好可能共同影响；query与gold最大词项Jaccard=0.0735。

其他可能解释：相关性标注不完整、gold摘要与claim粒度不同、分块边界或Top10截断；当前终态run不能排除这些因素（rerank_reorder_harm）。

反证实验：固定同一索引和query，额外留存Dense/Sparse候选Top100、融合逐项贡献、Rerank输入输出分数及chunk文本，再复跑该query验证首个内部失效点（rerank_reorder_harm）。

### queryId=36

问题：A deficiency of vitamin B12 increases blood levels of homocysteine.

Gold文档：

- `11705328` Randomized trial of folic acid supplementation and serum homocysteine levels.

  > BACKGROUND Lowering serum homocysteine levels with folic acid is expected to reduce mortality from ischemic heart disease. Homocysteine reduction is known to be maximal at a folic acid dosage of 1 mg/d, but the effect of lower doses (relevant to food fortification) is unclear. METHODS We randomized 151 patients with ischemic heart disease to 1 of 5 dosages of folic acid (0.2, 0.4, 0.6, 0.8, and 1.0 mg/d) or placebo. Fasting blood samples for serum homocysteine and serum folate analysis were taken initially, after 3 months of supplementation, and 3 months after folic acid use was discontinued.…
- `5152028` Folic acid improves endothelial function in coronary artery disease via mechanisms largely independent of homocysteine lowering.

  > BACKGROUND Homocysteine is a risk factor for coronary artery disease (CAD), although a causal relation remains to be proven. The importance of determining direct causality rests in the fact that plasma homocysteine can be safely and inexpensively reduced by 25% with folic acid. This reduction is maximally achieved by doses of 0.4 mg/d. High-dose folic acid (5 mg/d) improves endothelial function in CAD, although the mechanism is controversial. It has been proposed that improvement occurs through reduction in total (tHcy) or free (non-protein bound) homocysteine (fHcy). We investigated the effec…

| 变体 | Recall@10 | MRR@10 | nDCG@10 | Gold首名次 | 延迟ms | Top10文档ID（*为gold） |
|---|---:|---:|---:|---:|---:|---|
| dense | 0.500000 | 0.142857 | 0.204382 | 7 | 827 | 33409100, 3215494, 37424881, 42441846, 16252863, 21636085, 11705328*, 12810152 |
| sparse | 0.500000 | 1.000000 | 0.613147 | 1 | 412 | 11705328*, 36271512, 21868715, 42441846, 1958440, 73136607, 275294, 20821402, 11256632, 10698739 |
| hybrid_rrf | 0.500000 | 1.000000 | 0.613147 | 1 | 827 | 11705328*, 42441846, 33409100, 3215494, 275294, 13801259, 16252863, 21636085, 12207167 |
| hybrid_rrf_rerank | 0.500000 | 0.250000 | 0.264068 | 4 | 13287 | 42441846, 33409100, 21636085, 11705328*, 3215494, 16252863, 12207167, 13801259, 275294 |

首个失败步骤中的关键错误召回文档（前3条非gold）：

- rank=1 `42441846` Gene--nutrition interactions in coronary artery disease: correlation between the MTHFR C677T polymorphism and folate and homocysteine status in a Korean population.（本地heading=`BENCH_DOC_B64_NDI0NDE4NDY`）

  > INTRODUCTION Elevated plasma total homocysteine is a major risk for coronary artery disease (CAD). Methyltetrahydrofolate reductase (MTHFR) is a main regulatory enzyme in homocysteine metabolism; a common C677T mutation in the MTHFR gene results in decreased enzyme activity, and contributes to increased homocysteine levels and decreased folate levels. We investigated the frequency of MTHFR C677T alleles in a Korean population, determined the genotype-specific threshold levels of folate or vitamin B12, and investigated the relationship between the TT genotype and the risk of CAD. MATERIALS AND…
- rank=2 `33409100` Effect of homocysteine lowering on mortality and vascular disease in advanced chronic kidney disease and end-stage renal disease: a randomized controlled trial.（本地heading=`BENCH_DOC_B64_MzM0MDkxMDA`）

  > CONTEXT High plasma homocysteine levels are a risk factor for mortality and vascular disease in observational studies of patients with chronic kidney disease. Folic acid and B vitamins decrease homocysteine levels in this population but whether they lower mortality is unknown. OBJECTIVE To determine whether high doses of folic acid and B vitamins administered daily reduce mortality in patients with chronic kidney disease. DESIGN, SETTING, AND PARTICIPANTS Double-blind randomized controlled trial (2001-2006) in 36 US Department of Veterans Affairs medical centers. Median follow-up was 3.2 years…
- rank=3 `21636085` The effect of folic acid supplementation on plasma homocysteine in an elderly population.（本地heading=`BENCH_DOC_B64_MjE2MzYwODU`）

  > BACKGROUND Increased plasma homocysteine is associated with coronary artery disease, peripheral vascular disease and venous thrombosis. Folic acid is the most effective therapy for reducing homocysteine levels. The lowest effective supplement of folic acid is not known, particularly for the elderly who have the highest prevalence of these conditions. AIM To explore the effects of daily supplements of 0, 50, 100, 200, 400 and 600 microg folic acid on plasma homocysteine in an elderly population. DESIGN Randomized double-blind placebo-controlled trial. METHODS Participants (n=368) aged 65-75 yea…

首个可观测失败步骤：`rerank_final_top10`。

直接证据：

- Dense gold首名次=7
- Sparse gold首名次=1
- Hybrid-RRF gold首名次=1
- Hybrid-RRF+Rerank gold首名次=4
- 分类规则=rerank_reorder_harm
- 逐候选分数=未采集

推断：Top10截断、语义近邻竞争或Rerank排序偏好可能共同影响；query与gold最大词项Jaccard=0.0635。

其他可能解释：相关性标注不完整、gold摘要与claim粒度不同、分块边界或Top10截断；当前终态run不能排除这些因素（rerank_reorder_harm）。

反证实验：固定同一索引和query，额外留存Dense/Sparse候选Top100、融合逐项贡献、Rerank输入输出分数及chunk文本，再复跑该query验证首个内部失效点（rerank_reorder_harm）。

## 输入SHA-256

- queries: `331f88f940774ac84e1fc6ef517720dd94d07deab77efbdc85f42fc405335ad0`
- qrels: `5602d9f31c96d309a906692e1b722a9acfc4138c5d52e06d47bbb89a9c4ab7c3`
- documents: `0287493f09e9cb8d13d44bd46c01540229a7bad18d8c9da344f60429a89d6680`
- documentMap: `1718e1ed99f145f839156afccca3b13de7608a154232e5d829f20a36cb124c84`
- run: `24331ecdbb58978e37a92bff9c1afad5c09d1abadba68b88fdbf4d0b0ee792a5`
