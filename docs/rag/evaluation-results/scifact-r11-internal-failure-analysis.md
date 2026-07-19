# SciFact RAG内部阶段失败证据报告

真实诊断查询：20；请求记录：80；旧run最终排名精确复现：80/80。

## 证据边界

- firstObserved字段表示阶段轨迹中首个可观测损失，不等同于模型或索引的不可反驳根因。
- fusion实现将score threshold与TopK合并，轨迹只能定位到FUSION_THRESHOLD_OR_TOPK_LOSS。
- context outcome可直接证明淘汰分支，但未采集具体Token差额与扩展上下文组成。
- 当前报告只支持每条请求恰好一个binding/profile；多binding局部排名不会被混成全局排名。
- Hybrid raw union只表达Dense/Sparse两路覆盖并集，不提供跨分支全局名次语义。
- 每个变体记录并校验其binding/profile单作用域；四个消融target的binding/profile本来不同，跨变体指纹比较会归一化这两个target局部ID。
- 跨变体可比性校验共享知识库/文档/版本/generation/chunk、outcome与分数容差；未采集完整模型/index冻结指纹。

## 内部失效总账

80条变体轨迹的首个完全损失分布：

| 分类码 | 变体轨迹数 |
|---|---:|
| DENSE_RAW_TOPK_MISS | 2 |
| FUSION_THRESHOLD_OR_TOPK_LOSS | 17 |
| NONE | 52 |
| RAW_RECALL_TOTAL_MISS | 4 |
| SPARSE_RAW_TOPK_MISS | 5 |

同一次Hybrid+Rerank请求内，Rerank输入→输出的排序效果：

| 分类 | 查询数 |
|---|---:|
| RERANK_NEUTRAL | 5 |
| RERANK_ORDER_GAIN | 8 |
| RERANK_ORDER_HARM | 7 |

## queryId=598

问题：Incidence rates of cervical cancer have increased due to nationwide screening programs based primarily on cytology to detect uterine cervical cancer.

原分类：`dense_miss_hybrid_hit`, `sparse_only_success`

Gold文档：

- `25742130` Mass screening programmes and trends in cervical cancer in Finland and the Netherlands.

  > With respect to cervical cancer management, Finland and the Netherlands are comparable in relevant characteristics, e.g., fertility rate, age-of-mother at first birth and a national screening programme for several years. The aim of this study is to compare trends in incidence of and mortality from cervical cancer in Finland and the Netherlands in relation to the introduction and intensity of the screening programmes. Therefore, incidence and mortality rates were calculated using the Cancer Registries of Finland and the Netherlands. Data on screening intensity were obtained from the Finnish Can…

| 变体 | 首个覆盖损失 | 首个完全损失 | 最终排名复现 | Rerank同请求效果 |
|---|---|---|---:|---|
| dense | fusion/FUSION_THRESHOLD_OR_TOPK_LOSS | fusion/FUSION_THRESHOLD_OR_TOPK_LOSS | 是 | 不适用 |
| sparse | 未观察到Gold损失 | 未观察到Gold损失 | 是 | 不适用 |
| hybrid_rrf | 未观察到Gold损失 | 未观察到Gold损失 | 是 | 不适用 |
| hybrid_rrf_rerank | 未观察到Gold损失 | 未观察到Gold损失 | 是 | RERANK_ORDER_HARM (MRR Δ=-0.208333) |

重点失败变体：`dense`。首个内部失效结论：fusion/FUSION_THRESHOLD_OR_TOPK_LOSS。该结论级别为阶段轨迹派生，不外推为模型根因。

重点变体Gold逐阶段路径：

| 阶段 | Gold仍存在 | Gold已缺失 | Gold最佳名次 |
|---|---|---|---:|
| dense_raw | 25742130 |  | 14 |
| fusion |  | 25742130 | - |
| candidate_filter |  | 25742130 | - |
| pre_assembly |  | 25742130 | - |
| context_budget |  | 25742130 | - |

`dense`在`fusion/FUSION_THRESHOLD_OR_TOPK_LOSS`阶段排名靠前的非Gold文档：

- sourceStage=fusion rank=1 `9764256` Human papillomavirus testing for the detection of high-grade cervical intraepithelial neoplasia and cancer: final results of the POBASCAM randomised controlled trial.；dense=0.86123794，sparse=null，fusion=0.93061897，rerank=null

  > BACKGROUND Human papillomavirus (HPV) testing is more sensitive for the detection of high-grade cervical lesions than is cytology, but detection of HPV by DNA screening in two screening rounds 5 years apart has not been assessed. The aim of this study was to assess whether HPV DNA testing in the first screen decreases detection of cervical intraepithelial neoplasia (CIN) grade 3 or worse, CIN grade 2 or worse, and cervical cancer in the second screening. METHODS In this randomised trial, women aged 29-56 years participating in the cervical screening programme in the Netherlands were randomly a…

- sourceStage=fusion rank=2 `6561200` Efficacy of HPV DNA testing with cytology triage and/or repeat HPV DNA testing in primary cervical cancer screening.；dense=0.8510914，sparse=null，fusion=0.9255457，rerank=null

  > BACKGROUND Primary cervical screening with both human papillomavirus (HPV) DNA testing and cytological examination of cervical cells with a Pap test (cytology) has been evaluated in randomized clinical trials. Because the vast majority of women with positive cytology are also HPV DNA positive, screening strategies that use HPV DNA testing as the primary screening test may be more effective. METHODS We used the database from the intervention arm (n = 6,257 women) of a population-based randomized trial of double screening with cytology and HPV DNA testing to evaluate the efficacy of 11 possible…

- sourceStage=fusion rank=3 `27873158` Efficacy of human papillomavirus testing for the detection of invasive cervical cancers and cervical intraepithelial neoplasia: a randomised controlled trial.；dense=0.8508817，sparse=null，fusion=0.92544085，rerank=null

  > BACKGROUND Human papillomavirus (HPV) testing is known to be more sensitive, but less specific than cytology for detecting cervical intraepithelial neoplasia (CIN). We assessed the efficacy of cervical-cancer screening policies that are based on HPV testing. METHODS Between March, 2004, and December, 2004, in two separate recruitment phases, women aged 25-60 years were randomly assigned to conventional cytology or to HPV testing in combination with liquid-based cytology (first phase) or alone (second phase). Randomisation was done by computer in two screening centres and by sequential opening…

## queryId=715

问题：Low expression of miR7a does represses target genes and exerts a biological function in ovaries.

原分类：`dense_miss_hybrid_hit`

Gold文档：

- `18421962` Assessing the ceRNA hypothesis with quantitative measurements of miRNA and target abundance.

  > Recent studies have reported that competitive endogenous RNAs (ceRNAs) can act as sponges for a microRNA (miRNA) through their binding sites and that changes in ceRNA abundances from individual genes can modulate the activity of miRNAs. Consideration of this hypothesis would benefit from knowing the quantitative relationship between a miRNA and its endogenous target sites. Here, we altered intracellular target site abundance through expression of an miR-122 target in hepatocytes and livers and analyzed the effects on miR-122 target genes. Target repression was released in a threshold-like mann…

| 变体 | 首个覆盖损失 | 首个完全损失 | 最终排名复现 | Rerank同请求效果 |
|---|---|---|---:|---|
| dense | fusion/FUSION_THRESHOLD_OR_TOPK_LOSS | fusion/FUSION_THRESHOLD_OR_TOPK_LOSS | 是 | 不适用 |
| sparse | fusion/FUSION_THRESHOLD_OR_TOPK_LOSS | fusion/FUSION_THRESHOLD_OR_TOPK_LOSS | 是 | 不适用 |
| hybrid_rrf | 未观察到Gold损失 | 未观察到Gold损失 | 是 | 不适用 |
| hybrid_rrf_rerank | 未观察到Gold损失 | 未观察到Gold损失 | 是 | RERANK_ORDER_HARM (MRR Δ=-0.357143) |

重点失败变体：`dense`。首个内部失效结论：fusion/FUSION_THRESHOLD_OR_TOPK_LOSS。该结论级别为阶段轨迹派生，不外推为模型根因。

重点变体Gold逐阶段路径：

| 阶段 | Gold仍存在 | Gold已缺失 | Gold最佳名次 |
|---|---|---|---:|
| dense_raw | 18421962 |  | 23 |
| fusion |  | 18421962 | - |
| candidate_filter |  | 18421962 | - |
| pre_assembly |  | 18421962 | - |
| context_budget |  | 18421962 | - |

`dense`在`fusion/FUSION_THRESHOLD_OR_TOPK_LOSS`阶段排名靠前的非Gold文档：

- sourceStage=fusion rank=1 `13290521` MicroRNA-7: A miRNA with expanding roles in development and disease.；dense=0.85275364，sparse=null，fusion=0.92637682，rerank=null

  > MicroRNAs (miRNAs) are a family of short, non-coding RNA molecules (∼22nt) involved in post-transcriptional control of gene expression. They act via base-pairing with mRNA transcripts that harbour target sequences, resulting in accelerated mRNA decay and/or translational attenuation. Given miRNAs mediate the expression of molecules involved in many aspects of normal cell development and functioning, it is not surprising that aberrant miRNA expression is closely associated with many human diseases. Their pivotal role in driving a range of normal cellular physiology as well as pathological proce…

- sourceStage=fusion rank=2 `2000038` MicroRNAs can generate thresholds in target gene expression；dense=0.8505165，sparse=null，fusion=0.92525825，rerank=null

  > MicroRNAs (miRNAs) are short, highly conserved noncoding RNA molecules that repress gene expression in a sequence-dependent manner. We performed single-cell measurements using quantitative fluorescence microscopy and flow cytometry to monitor a target gene's protein expression in the presence and absence of regulation by miRNA. We find that although the average level of repression is modest, in agreement with previous population-based measurements, the repression among individual cells varies dramatically. In particular, we show that regulation by miRNAs establishes a threshold level of target…

- sourceStage=fusion rank=4 `19358586` Functional proteomics identifies miRNAs to target a p27/Myc/phospho-Rb signature in breast and ovarian cancer；dense=0.84309006，sparse=null，fusion=0.92154503，rerank=null

  > The myc oncogene is overexpressed in almost half of all breast and ovarian cancers, but attempts at therapeutic interventions against myc have proven to be challenging. Myc regulates multiple biological processes, including the cell cycle, and as such is associated with cell proliferation and tumor progression. We identified a protein signature of high myc, low p27 and high phospho-Rb significantly correlated with poor patient survival in breast and ovarian cancers. Screening of a miRNA library by functional proteomics in multiple cell lines and integration of data from patient tumors revealed…

`sparse`在`fusion/FUSION_THRESHOLD_OR_TOPK_LOSS`阶段排名靠前的非Gold文档：

- sourceStage=fusion rank=1 `11935250` Widespread and tissue specific age-related DNA methylation changes in mice.；dense=null，sparse=0.3896842，fusion=0.2804120533283749，rerank=null

  > Aberrant methylation of promoter CpG islands in cancer is associated with silencing of tumor-suppressor genes, and age-dependent hypermethylation in normal appearing mucosa may be a risk factor for human colon cancer. It is not known whether this age-related DNA methylation phenomenon is specific to human tissues. We performed comprehensive DNA methylation profiling of promoter regions in aging mouse intestine using methylated CpG island amplification in combination with microarray analysis. By comparing C57BL/6 mice at 3-mo-old versus 35-mo-old for 3627 detectable autosomal genes, we found 77…

- sourceStage=fusion rank=2 `279052` Genomic imprinting in development, growth, behavior and stem cells.；dense=null，sparse=0.37001002，fusion=0.270078331251913，rerank=null

  > Genes that are subject to genomic imprinting in mammals are preferentially expressed from a single parental allele. This imprinted expression of a small number of genes is crucial for normal development, as these genes often directly regulate fetal growth. Recent work has also demonstrated intricate roles for imprinted genes in the brain, with important consequences on behavior and neuronal function. Finally, new studies have revealed the importance of proper expression of specific imprinted genes in induced pluripotent stem cells and in adult stem cells. As we review here, these findings high…

- sourceStage=fusion rank=3 `37438296` Altered microRNA expression in bovine skeletal muscle with age.；dense=null，sparse=0.35054952，fusion=0.2595606564652291，rerank=null

  > Age-dependent decline in skeletal muscle function leads to several inherited and acquired muscular disorders in elderly individuals. The levels of microRNAs (miRNAs) could be altered during muscle maintenance and repair. We therefore performed a comprehensive investigation for miRNAs from five different periods of bovine skeletal muscle development using next-generation small RNA sequencing. In total, 511 miRNAs, including one putatively novel miRNA, were identified. Thirty-six miRNAs were differentially expressed between prenatal and postnatal stages of muscle development including several my…

## queryId=185

问题：Breast cancer development is determined exclusively by genetic factors.

原分类：`dense_miss_hybrid_hit`

Gold文档：

- `18340282` Gene–environment interactions in 7610 women with breast cancer: prospective evidence from the Million Women Study

  > BACKGROUND Information is scarce about the combined effects on breast cancer incidence of low-penetrance genetic susceptibility polymorphisms and environmental factors (reproductive, behavioural, and anthropometric risk factors for breast cancer). To test for evidence of gene-environment interactions, we compared genotypic relative risks for breast cancer across the other risk factors in a large UK prospective study. METHODS We tested gene-environment interactions in 7610 women who developed breast cancer and 10 196 controls without the disease, studying the effects of 12 polymorphisms (FGFR2-…

| 变体 | 首个覆盖损失 | 首个完全损失 | 最终排名复现 | Rerank同请求效果 |
|---|---|---|---:|---|
| dense | fusion/FUSION_THRESHOLD_OR_TOPK_LOSS | fusion/FUSION_THRESHOLD_OR_TOPK_LOSS | 是 | 不适用 |
| sparse | fusion/FUSION_THRESHOLD_OR_TOPK_LOSS | fusion/FUSION_THRESHOLD_OR_TOPK_LOSS | 是 | 不适用 |
| hybrid_rrf | 未观察到Gold损失 | 未观察到Gold损失 | 是 | 不适用 |
| hybrid_rrf_rerank | 未观察到Gold损失 | 未观察到Gold损失 | 是 | RERANK_ORDER_GAIN (MRR Δ=0.300000) |

重点失败变体：`dense`。首个内部失效结论：fusion/FUSION_THRESHOLD_OR_TOPK_LOSS。该结论级别为阶段轨迹派生，不外推为模型根因。

重点变体Gold逐阶段路径：

| 阶段 | Gold仍存在 | Gold已缺失 | Gold最佳名次 |
|---|---|---|---:|
| dense_raw | 18340282 |  | 14 |
| fusion |  | 18340282 | - |
| candidate_filter |  | 18340282 | - |
| pre_assembly |  | 18340282 | - |
| context_budget |  | 18340282 | - |

`dense`在`fusion/FUSION_THRESHOLD_OR_TOPK_LOSS`阶段排名靠前的非Gold文档：

- sourceStage=fusion rank=1 `38784540` Life course breast cancer risk factors and adult breast density (United Kingdom)；dense=0.8390919，sparse=null，fusion=0.9195459500000001，rerank=null

  > Objective To determine whether risk factors in childhood and early adulthood affect later mammographic breast density. Methods: Subjects were 628 women who attended a medical examination at the University of Glasgow Student Health Service (1948–1968), responded to a questionnaire (2001) and had a screening mammogram in Scotland (1989–2002). Mammograms (median age of 59years) were classified using a six category classification (SCC) of breast density percent. Logistic regression was used to determine associations between risk factors and having a high-risk mammogram (≥25 dense). Results: In mul…

- sourceStage=fusion rank=2 `15721252` PD 0332991, a selective cyclin D kinase 4/6 inhibitor, preferentially inhibits proliferation of luminal estrogen receptor-positive human breast cancer cell lines in vitro；dense=0.833331，sparse=null，fusion=0.9166655，rerank=null

  > INTRODUCTION Alterations in cell cycle regulators have been implicated in human malignancies including breast cancer. PD 0332991 is an orally active, highly selective inhibitor of the cyclin D kinases (CDK)4 and CDK6 with ability to block retinoblastoma (Rb) phosphorylation in the low nanomolar range. To identify predictors of response, we determined the in vitro sensitivity to PD 0332991 across a panel of molecularly characterized human breast cancer cell lines. METHODS Forty-seven human breast cancer and immortalized cell lines representing the known molecular subgroups of breast cancer were…

- sourceStage=fusion rank=3 `6790197` Prostate cancer-associated gene expression alterations determined from needle biopsies.；dense=0.8301331，sparse=null，fusion=0.9150665499999999，rerank=null

  > PURPOSE To accurately identify gene expression alterations that differentiate neoplastic from normal prostate epithelium using an approach that avoids contamination by unwanted cellular components and is not compromised by acute gene expression changes associated with tumor devascularization and resulting ischemia. EXPERIMENTAL DESIGN Approximately 3,000 neoplastic and benign prostate epithelial cells were isolated using laser capture microdissection from snap-frozen prostate biopsy specimens provided by 31 patients who subsequently participated in a clinical trial of preoperative chemotherapy…

`sparse`在`fusion/FUSION_THRESHOLD_OR_TOPK_LOSS`阶段排名靠前的非Gold文档：

- sourceStage=fusion rank=1 `13831842` Pooled analysis of prospective cohort studies on height, weight, and breast cancer risk.；dense=null，sparse=0.32081226，fusion=0.24289012883632682，rerank=null

  > The association between anthropometric indices and the risk of breast cancer was analyzed using pooled data from seven prospective cohort studies. Together, these cohorts comprise 337,819 women and 4,385 incident invasive breast cancer cases. In multivariate analyses controlling for reproductive, dietary, and other risk factors, the pooled relative risk (RR) of breast cancer per height increment of 5 cm was 1.02 (95% confidence interval (CI): 0.96, 1.10) in premenopausal women and 1.07 (95% CI: 1.03, 1.12) in postmenopausal women. Body mass index (BMI) showed significant inverse and positive a…

- sourceStage=fusion rank=2 `12207167` Adverse effects of excessive consumption of amino acids.；dense=null，sparse=0.31093046，fusion=0.23718303105109023，rerank=null

  > PHENYLALANINE TOXICITY 158 Developing the 0. -M ethylphenylalanine Model. . . . . . .. . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . 160 Use of the a-Methyl phenylalanine Model in Brain Protein Synthesis . . . . . . . . . . . . . . . . . . . 161 TYROSINE TOXICITY 162 General Nutritional Observations . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . 162 Factors Affecting Tissue Concentrations of Tyrosine . ... .. .. .. ...... . . . . . . .. . . 163 Probable Cause of Tyrosine Toxicity . . .…

- sourceStage=fusion rank=3 `20839751` Apoptosis in breast cancer: relationship with other pathological parameters.；dense=null，sparse=0.29053757，fusion=0.22512910646994957，rerank=null

  > Apoptosis is a frequent phenomenon in breast cancer and it can be detected by light microscopy in conventional histopathological sections or by special staining techniques. The number of apoptotic cells as a percentage of cells present, or the number of apoptotic cells per square millimetre of neoplastic tissue, is usually described as the apoptotic index (AI). In breast cancer, the AI is not related to tumour size, axillary lymph node metastasis or distant metastasis at diagnosis. It is greater in invasive ductal carcinomas than in other histological types. High AI is also related to high his…

## queryId=216

问题：CX3CR1 on the Th2 cells impairs T cell survival

原分类：`sparse_miss_hybrid_hit`

Gold文档：

- `21366394` CX3CR1 is required for airway inflammation by promoting T helper cell survival and maintenance in inflamed lung

  > Allergic asthma is a T helper type 2 (T(H)2)-dominated disease of the lung. In people with asthma, a fraction of CD4(+) T cells express the CX3CL1 receptor, CX3CR1, and CX3CL1 expression is increased in airway smooth muscle, lung endothelium and epithelium upon allergen challenge. Here we found that untreated CX3CR1-deficient mice or wild-type (WT) mice treated with CX3CR1-blocking reagents show reduced lung disease upon allergen sensitization and challenge. Transfer of WT CD4(+) T cells into CX3CR1-deficient mice restored the cardinal features of asthma, and CX3CR1-blocking reagents prevented…

| 变体 | 首个覆盖损失 | 首个完全损失 | 最终排名复现 | Rerank同请求效果 |
|---|---|---|---:|---|
| dense | 未观察到Gold损失 | 未观察到Gold损失 | 是 | 不适用 |
| sparse | fusion/FUSION_THRESHOLD_OR_TOPK_LOSS | fusion/FUSION_THRESHOLD_OR_TOPK_LOSS | 是 | 不适用 |
| hybrid_rrf | 未观察到Gold损失 | 未观察到Gold损失 | 是 | 不适用 |
| hybrid_rrf_rerank | 未观察到Gold损失 | 未观察到Gold损失 | 是 | RERANK_NEUTRAL (MRR Δ=0.000000) |

重点失败变体：`sparse`。首个内部失效结论：fusion/FUSION_THRESHOLD_OR_TOPK_LOSS。该结论级别为阶段轨迹派生，不外推为模型根因。

重点变体Gold逐阶段路径：

| 阶段 | Gold仍存在 | Gold已缺失 | Gold最佳名次 |
|---|---|---|---:|
| sparse_raw | 21366394 |  | 14 |
| fusion |  | 21366394 | - |
| candidate_filter |  | 21366394 | - |
| pre_assembly |  | 21366394 | - |
| context_budget |  | 21366394 | - |

`sparse`在`fusion/FUSION_THRESHOLD_OR_TOPK_LOSS`阶段排名靠前的非Gold文档：

- sourceStage=fusion rank=1 `11666252` Maintaining the norm: T-cell homeostasis；dense=null，sparse=0.30723786，fusion=0.23502827557335282，rerank=null

  > The persistence of naive and memory T cells has long been of interest to immunologists, but the factors that influence the survival and homeostasis of these subsets have remained obscure. In recent years, it has become evident that the homeostasis of both naive and memory T-cell pools is highly dynamic and tightly regulated by internal stimuli, including cytokines and self-peptide–MHC ligands for the T-cell receptor. These homeostatic mechanisms might have a vital influence on the capacity of the T-cell pool to respond to both foreign and self-antigens.

- sourceStage=fusion rank=2 `22210434` The kinase TAK1 integrates antigen and cytokine receptor signaling for T cell development, survival and function；dense=null，sparse=0.30659795，fusion=0.2346536285320209，rerank=null

  > The kinase TAK1 is critical for innate and B cell immunity. The function of TAK1 in T cells is unclear, however. We show here that T cell–specific deletion of the gene encoding TAK1 resulted in reduced development of thymocytes, especially of regulatory T cells expressing the transcription factor Foxp3. In mature thymocytes, TAK1 was required for interleukin 7–mediated survival and T cell receptor–dependent activation of transcription factor NF-κB and the kinase Jnk. In effector T cells, TAK1 was dispensable for T cell receptor–dependent NF-κB activation and cytokine production, but was import…

- sourceStage=fusion rank=3 `20610557` Alkylating agent melphalan augments the efficacy of adoptive immunotherapy using tumor-specific CD4+ T cells.；dense=null，sparse=0.30576676，fusion=0.23416644485574134，rerank=null

  > In recent years, the immune-potentiating effects of some widely used chemotherapeutic agents have been increasingly appreciated. This provides a rationale for combining conventional chemotherapy with immunotherapy strategies to achieve durable therapeutic benefits. Previous studies have implicated the immunomodulatory effects of melphalan, an alkylating agent commonly used to treat multiple myeloma, but the underlying mechanisms remain obscure. In the present study, we investigated the impact of melphalan on endogenous immune cells as well as adoptively transferred tumor-specific CD4(+) T cell…

## queryId=217

问题：CX3CR1 on the Th2 cells promotes T cell survival

原分类：`sparse_miss_hybrid_hit`

Gold文档：

- `21366394` CX3CR1 is required for airway inflammation by promoting T helper cell survival and maintenance in inflamed lung

  > Allergic asthma is a T helper type 2 (T(H)2)-dominated disease of the lung. In people with asthma, a fraction of CD4(+) T cells express the CX3CL1 receptor, CX3CR1, and CX3CL1 expression is increased in airway smooth muscle, lung endothelium and epithelium upon allergen challenge. Here we found that untreated CX3CR1-deficient mice or wild-type (WT) mice treated with CX3CR1-blocking reagents show reduced lung disease upon allergen sensitization and challenge. Transfer of WT CD4(+) T cells into CX3CR1-deficient mice restored the cardinal features of asthma, and CX3CR1-blocking reagents prevented…

| 变体 | 首个覆盖损失 | 首个完全损失 | 最终排名复现 | Rerank同请求效果 |
|---|---|---|---:|---|
| dense | 未观察到Gold损失 | 未观察到Gold损失 | 是 | 不适用 |
| sparse | fusion/FUSION_THRESHOLD_OR_TOPK_LOSS | fusion/FUSION_THRESHOLD_OR_TOPK_LOSS | 是 | 不适用 |
| hybrid_rrf | 未观察到Gold损失 | 未观察到Gold损失 | 是 | 不适用 |
| hybrid_rrf_rerank | 未观察到Gold损失 | 未观察到Gold损失 | 是 | RERANK_NEUTRAL (MRR Δ=0.000000) |

重点失败变体：`sparse`。首个内部失效结论：fusion/FUSION_THRESHOLD_OR_TOPK_LOSS。该结论级别为阶段轨迹派生，不外推为模型根因。

重点变体Gold逐阶段路径：

| 阶段 | Gold仍存在 | Gold已缺失 | Gold最佳名次 |
|---|---|---|---:|
| sparse_raw | 21366394 |  | 13 |
| fusion |  | 21366394 | - |
| candidate_filter |  | 21366394 | - |
| pre_assembly |  | 21366394 | - |
| context_budget |  | 21366394 | - |

`sparse`在`fusion/FUSION_THRESHOLD_OR_TOPK_LOSS`阶段排名靠前的非Gold文档：

- sourceStage=fusion rank=1 `11666252` Maintaining the norm: T-cell homeostasis；dense=null，sparse=0.30723786，fusion=0.23502827557335282，rerank=null

  > The persistence of naive and memory T cells has long been of interest to immunologists, but the factors that influence the survival and homeostasis of these subsets have remained obscure. In recent years, it has become evident that the homeostasis of both naive and memory T-cell pools is highly dynamic and tightly regulated by internal stimuli, including cytokines and self-peptide–MHC ligands for the T-cell receptor. These homeostatic mechanisms might have a vital influence on the capacity of the T-cell pool to respond to both foreign and self-antigens.

- sourceStage=fusion rank=2 `22210434` The kinase TAK1 integrates antigen and cytokine receptor signaling for T cell development, survival and function；dense=null，sparse=0.30659795，fusion=0.2346536285320209，rerank=null

  > The kinase TAK1 is critical for innate and B cell immunity. The function of TAK1 in T cells is unclear, however. We show here that T cell–specific deletion of the gene encoding TAK1 resulted in reduced development of thymocytes, especially of regulatory T cells expressing the transcription factor Foxp3. In mature thymocytes, TAK1 was required for interleukin 7–mediated survival and T cell receptor–dependent activation of transcription factor NF-κB and the kinase Jnk. In effector T cells, TAK1 was dispensable for T cell receptor–dependent NF-κB activation and cytokine production, but was import…

- sourceStage=fusion rank=3 `20610557` Alkylating agent melphalan augments the efficacy of adoptive immunotherapy using tumor-specific CD4+ T cells.；dense=null，sparse=0.30576676，fusion=0.23416644485574134，rerank=null

  > In recent years, the immune-potentiating effects of some widely used chemotherapeutic agents have been increasingly appreciated. This provides a rationale for combining conventional chemotherapy with immunotherapy strategies to achieve durable therapeutic benefits. Previous studies have implicated the immunomodulatory effects of melphalan, an alkylating agent commonly used to treat multiple myeloma, but the underlying mechanisms remain obscure. In the present study, we investigated the impact of melphalan on endogenous immune cells as well as adoptively transferred tumor-specific CD4(+) T cell…

## queryId=268

问题：Cold exposure increases BAT recruitment.

原分类：`sparse_miss_hybrid_hit`

Gold文档：

- `970012` Cold Exposure Promotes Atherosclerotic Plaque Growth and Instability via UCP1-Dependent Lipolysis

  > Molecular mechanisms underlying the cold-associated high cardiovascular risk remain unknown. Here, we show that the cold-triggered food-intake-independent lipolysis significantly increased plasma levels of small low-density lipoprotein (LDL) remnants, leading to accelerated development of atherosclerotic lesions in mice. In two genetic mouse knockout models (apolipoprotein E(-/-) [ApoE(-/-)] and LDL receptor(-/-) [Ldlr(-/-)] mice), persistent cold exposure stimulated atherosclerotic plaque growth by increasing lipid deposition. Furthermore, marked increase of inflammatory cells and plaque-asso…

| 变体 | 首个覆盖损失 | 首个完全损失 | 最终排名复现 | Rerank同请求效果 |
|---|---|---|---:|---|
| dense | 未观察到Gold损失 | 未观察到Gold损失 | 是 | 不适用 |
| sparse | fusion/FUSION_THRESHOLD_OR_TOPK_LOSS | fusion/FUSION_THRESHOLD_OR_TOPK_LOSS | 是 | 不适用 |
| hybrid_rrf | 未观察到Gold损失 | 未观察到Gold损失 | 是 | 不适用 |
| hybrid_rrf_rerank | 未观察到Gold损失 | 未观察到Gold损失 | 是 | RERANK_ORDER_HARM (MRR Δ=-0.500000) |

重点失败变体：`sparse`。首个内部失效结论：fusion/FUSION_THRESHOLD_OR_TOPK_LOSS。该结论级别为阶段轨迹派生，不外推为模型根因。

重点变体Gold逐阶段路径：

| 阶段 | Gold仍存在 | Gold已缺失 | Gold最佳名次 |
|---|---|---|---:|
| sparse_raw | 970012 |  | 16 |
| fusion |  | 970012 | - |
| candidate_filter |  | 970012 | - |
| pre_assembly |  | 970012 | - |
| context_budget |  | 970012 | - |

`sparse`在`fusion/FUSION_THRESHOLD_OR_TOPK_LOSS`阶段排名靠前的非Gold文档：

- sourceStage=fusion rank=1 `12207167` Adverse effects of excessive consumption of amino acids.；dense=null，sparse=0.4014095，fusion=0.28643269508305746，rerank=null

  > PHENYLALANINE TOXICITY 158 Developing the 0. -M ethylphenylalanine Model. . . . . . .. . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . 160 Use of the a-Methyl phenylalanine Model in Brain Protein Synthesis . . . . . . . . . . . . . . . . . . . 161 TYROSINE TOXICITY 162 General Nutritional Observations . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . 162 Factors Affecting Tissue Concentrations of Tyrosine . ... .. .. .. ...... . . . . . . .. . . 163 Probable Cause of Tyrosine Toxicity . . .…

- sourceStage=fusion rank=2 `86217760` The Self-Incompatibility Genes of Brassica: Expression and Use in Genetic Ablation of Floral Tissues；dense=null，sparse=0.32300186，fusion=0.2441431639408277，rerank=null

  > INTRODUCTION . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . .. . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . 394 POLLINATION AND POLLEN TUBE GROWTH . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . 395 Interaction s between the M ale G ameto phyte and Pistil . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . 395 SelfIncom patibili ty Systems: Gameto phytic and S poro phyti c Determin ation of Pollen Phenoty pe . . . . . . . . . . . . . . . . .. . . . . . . . . . . .…

- sourceStage=fusion rank=6 `36271512` T-cell activation.；dense=null，sparse=0.2741873，fusion=0.21518602484893706，rerank=null

  > INTRODUCTION • • CELLULAR AND MOLECULAR REQUIREMENTS FOR T-CELL ACTIVATION . The T-Cell Antigen Receptor Complex . . . .. . . . ..... . . . . . . . . . . . . . . . . ...... . . . T-Cell Activation by Antibodies and Leetins . . . . . . . . . . .. . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . Other Cell Surface Structures (Accessory Molecules) Involved in Antigen Recognition and Activation . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . .…

## queryId=1014

问题：Rapamycin decreases the concentration of triacylglycerols in fruit flies.

原分类：`dense_only_success`

Gold文档：

- `6277638` Mechanisms of Life Span Extension by Rapamycin in the Fruit Fly Drosophila melanogaster

  > The target of rapamycin (TOR) pathway is a major nutrient-sensing pathway that, when genetically downregulated, increases life span in evolutionarily diverse organisms including mammals. The central component of this pathway, TOR kinase, is the target of the inhibitory drug rapamycin, a highly specific and well-described drug approved for human use. We show here that feeding rapamycin to adult Drosophila produces the life span extension seen in some TOR mutants. Increase in life span by rapamycin was associated with increased resistance to both starvation and paraquat. Analysis of the underlyi…

| 变体 | 首个覆盖损失 | 首个完全损失 | 最终排名复现 | Rerank同请求效果 |
|---|---|---|---:|---|
| dense | 未观察到Gold损失 | 未观察到Gold损失 | 是 | 不适用 |
| sparse | fusion/FUSION_THRESHOLD_OR_TOPK_LOSS | fusion/FUSION_THRESHOLD_OR_TOPK_LOSS | 是 | 不适用 |
| hybrid_rrf | 未观察到Gold损失 | 未观察到Gold损失 | 是 | 不适用 |
| hybrid_rrf_rerank | 未观察到Gold损失 | 未观察到Gold损失 | 是 | RERANK_ORDER_GAIN (MRR Δ=0.500000) |

重点失败变体：`sparse`。首个内部失效结论：fusion/FUSION_THRESHOLD_OR_TOPK_LOSS。该结论级别为阶段轨迹派生，不外推为模型根因。

重点变体Gold逐阶段路径：

| 阶段 | Gold仍存在 | Gold已缺失 | Gold最佳名次 |
|---|---|---|---:|
| sparse_raw | 6277638 |  | 49 |
| fusion |  | 6277638 | - |
| candidate_filter |  | 6277638 | - |
| pre_assembly |  | 6277638 | - |
| context_budget |  | 6277638 | - |

`sparse`在`fusion/FUSION_THRESHOLD_OR_TOPK_LOSS`阶段排名靠前的非Gold文档：

- sourceStage=fusion rank=1 `10530014` A point mutation in KINDLIN3 ablates activation of three integrin subfamilies in humans；dense=null，sparse=0.38032642，fusion=0.2755336813737145，rerank=null

  > Monogenic deficiency diseases provide unique opportunities to define the contributions of individual molecules to human physiology and to identify pathologies arising from their dysfunction. Here we describe a deficiency disease in two human siblings that presented with severe bleeding, frequent infections and osteopetrosis at an early age. These symptoms are consistent with but more severe than those reported for people with leukocyte adhesion deficiency III (LAD-III). Mechanistically, these symptoms arose from an inability to activate the integrins expressed on hematopoietic cells, including…

- sourceStage=fusion rank=2 `36271512` T-cell activation.；dense=null，sparse=0.37260327，fusion=0.27145736728428455，rerank=null

  > INTRODUCTION • • CELLULAR AND MOLECULAR REQUIREMENTS FOR T-CELL ACTIVATION . The T-Cell Antigen Receptor Complex . . . .. . . . ..... . . . . . . . . . . . . . . . . ...... . . . T-Cell Activation by Antibodies and Leetins . . . . . . . . . . .. . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . Other Cell Surface Structures (Accessory Molecules) Involved in Antigen Recognition and Activation . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . .…

- sourceStage=fusion rank=3 `8065561` Specific and cooperative binding of E. coli single-stranded DNA binding protein to mRNA.；dense=null，sparse=0.3701979，fusion=0.2701784172928597，rerank=null

  > Fluorometric titration of E. coli single-stranded DNA binding protein with various RNAs showed that the protein specifically and cooperatively binds to its own mRNA. The binding inhibited in vitro expression of ssb and bla but not nusA. This inhibition takes place at a physiological concentration of SSB. The function of the protein in gene regulation is discussed.

## queryId=1020

问题：Rapid up-regulation and higher basal expression of interferon-induced genes increase survival of granule cell neurons that are infected by West Nile virus.

原分类：`dense_only_success`

Gold文档：

- `9433958` Differential innate immune response programs in neuronal subtypes determine susceptibility to infection in the brain by positive stranded RNA viruses

  > Although susceptibility of neurons in the brain to microbial infection is a major determinant of clinical outcome, little is known about the molecular factors governing this vulnerability. Here we show that two types of neurons from distinct brain regions showed differential permissivity to replication of several positive-stranded RNA viruses. Granule cell neurons of the cerebellum and cortical neurons from the cerebral cortex have unique innate immune programs that confer differential susceptibility to viral infection ex vivo and in vivo. By transducing cortical neurons with genes that were e…

| 变体 | 首个覆盖损失 | 首个完全损失 | 最终排名复现 | Rerank同请求效果 |
|---|---|---|---:|---|
| dense | 未观察到Gold损失 | 未观察到Gold损失 | 是 | 不适用 |
| sparse | fusion/FUSION_THRESHOLD_OR_TOPK_LOSS | fusion/FUSION_THRESHOLD_OR_TOPK_LOSS | 是 | 不适用 |
| hybrid_rrf | 未观察到Gold损失 | 未观察到Gold损失 | 是 | 不适用 |
| hybrid_rrf_rerank | 未观察到Gold损失 | 未观察到Gold损失 | 是 | RERANK_ORDER_GAIN (MRR Δ=0.500000) |

重点失败变体：`sparse`。首个内部失效结论：fusion/FUSION_THRESHOLD_OR_TOPK_LOSS。该结论级别为阶段轨迹派生，不外推为模型根因。

重点变体Gold逐阶段路径：

| 阶段 | Gold仍存在 | Gold已缺失 | Gold最佳名次 |
|---|---|---|---:|
| sparse_raw | 9433958 |  | 26 |
| fusion |  | 9433958 | - |
| candidate_filter |  | 9433958 | - |
| pre_assembly |  | 9433958 | - |
| context_budget |  | 9433958 | - |

`sparse`在`fusion/FUSION_THRESHOLD_OR_TOPK_LOSS`阶段排名靠前的非Gold文档：

- sourceStage=fusion rank=1 `25238950` FGF-2 enhances intestinal stem cell survival and its expression is induced after radiation injury.；dense=null，sparse=0.3969075，fusion=0.2841329866150765，rerank=null

  > Fibroblast growth factors (FGFs) have mitogenic activity toward a wide variety of cells of mesenchymal, neuronal, and epithelial origin and regulate events in normal embryonic development, angiogenesis, wound repair, and neoplasia. FGF-2 is expressed in many normal adult tissues and can regulate migration and replication of intestinal epithelial cells in culture. However, little is known about the effects of FGF-2 on intestinal epithelial stem cells during either normal epithelial renewal or regeneration of a functional epithelium after injury. In this study, we investigated the expression of…

- sourceStage=fusion rank=2 `15561961` expression by oxidized linoleic；dense=null，sparse=0.39666992，fusion=0.2840112143318731，rerank=null

  > Hypercholesterolemia is associated with impairments in endothelium-dependent vascular relaxations. Paradoxically, endothelial production of nitrogen oxides is increased in early stages of hypercholesterolemia. Prior work has shown that oxidized low density lipoprotein (LDL) has both stimulatory and inhibitory effects on endothelial nitric oxide synthase expression (eNOS) and has focused on lysophosphatidyl choline (LPC) as a component of oxidized LDL which may modulate this effect. Another biologically active component of oxidized LDL is 13-hydroperoxyoctadecadienoic acid (13-HPODE), an oxidiz…

- sourceStage=fusion rank=3 `515489` Oncofetal long noncoding RNA PVT1 promotes proliferation and stem cell-like property of hepatocellular carcinoma cells by stabilizing NOP2.；dense=null，sparse=0.38671443，fusion=0.27887099292678447，rerank=null

  > UNLABELLED Many protein-coding oncofetal genes are highly expressed in murine and human fetal liver and silenced in adult liver. The protein products of these hepatic oncofetal genes have been used as clinical markers for the recurrence of hepatocellular carcinoma (HCC) and as therapeutic targets for HCC. Herein we examined the expression profiles of long noncoding RNAs (lncRNAs) found in fetal and adult liver in mice. Many fetal hepatic lncRNAs were identified; one of these, lncRNA-mPvt1, is an oncofetal RNA that was found to promote cell proliferation, cell cycling, and the expression of ste…

## queryId=1021

问题：Rapid up-regulation and higher basal expression of interferon-induced genes reduce survival of granule cell neurons that are infected by West Nile virus.

原分类：`dense_only_success`

Gold文档：

- `9433958` Differential innate immune response programs in neuronal subtypes determine susceptibility to infection in the brain by positive stranded RNA viruses

  > Although susceptibility of neurons in the brain to microbial infection is a major determinant of clinical outcome, little is known about the molecular factors governing this vulnerability. Here we show that two types of neurons from distinct brain regions showed differential permissivity to replication of several positive-stranded RNA viruses. Granule cell neurons of the cerebellum and cortical neurons from the cerebral cortex have unique innate immune programs that confer differential susceptibility to viral infection ex vivo and in vivo. By transducing cortical neurons with genes that were e…

| 变体 | 首个覆盖损失 | 首个完全损失 | 最终排名复现 | Rerank同请求效果 |
|---|---|---|---:|---|
| dense | 未观察到Gold损失 | 未观察到Gold损失 | 是 | 不适用 |
| sparse | fusion/FUSION_THRESHOLD_OR_TOPK_LOSS | fusion/FUSION_THRESHOLD_OR_TOPK_LOSS | 是 | 不适用 |
| hybrid_rrf | 未观察到Gold损失 | 未观察到Gold损失 | 是 | 不适用 |
| hybrid_rrf_rerank | 未观察到Gold损失 | 未观察到Gold损失 | 是 | RERANK_ORDER_GAIN (MRR Δ=0.500000) |

重点失败变体：`sparse`。首个内部失效结论：fusion/FUSION_THRESHOLD_OR_TOPK_LOSS。该结论级别为阶段轨迹派生，不外推为模型根因。

重点变体Gold逐阶段路径：

| 阶段 | Gold仍存在 | Gold已缺失 | Gold最佳名次 |
|---|---|---|---:|
| sparse_raw | 9433958 |  | 25 |
| fusion |  | 9433958 | - |
| candidate_filter |  | 9433958 | - |
| pre_assembly |  | 9433958 | - |
| context_budget |  | 9433958 | - |

`sparse`在`fusion/FUSION_THRESHOLD_OR_TOPK_LOSS`阶段排名靠前的非Gold文档：

- sourceStage=fusion rank=1 `25238950` FGF-2 enhances intestinal stem cell survival and its expression is induced after radiation injury.；dense=null，sparse=0.3969075，fusion=0.2841329866150765，rerank=null

  > Fibroblast growth factors (FGFs) have mitogenic activity toward a wide variety of cells of mesenchymal, neuronal, and epithelial origin and regulate events in normal embryonic development, angiogenesis, wound repair, and neoplasia. FGF-2 is expressed in many normal adult tissues and can regulate migration and replication of intestinal epithelial cells in culture. However, little is known about the effects of FGF-2 on intestinal epithelial stem cells during either normal epithelial renewal or regeneration of a functional epithelium after injury. In this study, we investigated the expression of…

- sourceStage=fusion rank=2 `15561961` expression by oxidized linoleic；dense=null，sparse=0.39666992，fusion=0.2840112143318731，rerank=null

  > Hypercholesterolemia is associated with impairments in endothelium-dependent vascular relaxations. Paradoxically, endothelial production of nitrogen oxides is increased in early stages of hypercholesterolemia. Prior work has shown that oxidized low density lipoprotein (LDL) has both stimulatory and inhibitory effects on endothelial nitric oxide synthase expression (eNOS) and has focused on lysophosphatidyl choline (LPC) as a component of oxidized LDL which may modulate this effect. Another biologically active component of oxidized LDL is 13-hydroperoxyoctadecadienoic acid (13-HPODE), an oxidiz…

- sourceStage=fusion rank=3 `515489` Oncofetal long noncoding RNA PVT1 promotes proliferation and stem cell-like property of hepatocellular carcinoma cells by stabilizing NOP2.；dense=null，sparse=0.38671443，fusion=0.27887099292678447，rerank=null

  > UNLABELLED Many protein-coding oncofetal genes are highly expressed in murine and human fetal liver and silenced in adult liver. The protein products of these hepatic oncofetal genes have been used as clinical markers for the recurrence of hepatocellular carcinoma (HCC) and as therapeutic targets for HCC. Herein we examined the expression profiles of long noncoding RNAs (lncRNAs) found in fetal and adult liver in mice. Many fetal hepatic lncRNAs were identified; one of these, lncRNA-mPvt1, is an oncofetal RNA that was found to promote cell proliferation, cell cycling, and the expression of ste…

## queryId=820

问题：N-terminal cleavage increases success identifying transcription start sites.

原分类：`sparse_only_success`

Gold文档：

- `8646760` Identification and Functional Characterization of N-Terminally Acetylated Proteins in Drosophila melanogaster

  > Protein modifications play a major role for most biological processes in living organisms. Amino-terminal acetylation of proteins is a common modification found throughout the tree of life: the N-terminus of a nascent polypeptide chain becomes co-translationally acetylated, often after the removal of the initiating methionine residue. While the enzymes and protein complexes involved in these processes have been extensively studied, only little is known about the biological function of such N-terminal modification events. To identify common principles of N-terminal acetylation, we analyzed the…

| 变体 | 首个覆盖损失 | 首个完全损失 | 最终排名复现 | Rerank同请求效果 |
|---|---|---|---:|---|
| dense | fusion/FUSION_THRESHOLD_OR_TOPK_LOSS | fusion/FUSION_THRESHOLD_OR_TOPK_LOSS | 是 | 不适用 |
| sparse | 未观察到Gold损失 | 未观察到Gold损失 | 是 | 不适用 |
| hybrid_rrf | 未观察到Gold损失 | 未观察到Gold损失 | 是 | 不适用 |
| hybrid_rrf_rerank | 未观察到Gold损失 | 未观察到Gold损失 | 是 | RERANK_ORDER_HARM (MRR Δ=-0.057143) |

重点失败变体：`dense`。首个内部失效结论：fusion/FUSION_THRESHOLD_OR_TOPK_LOSS。该结论级别为阶段轨迹派生，不外推为模型根因。

重点变体Gold逐阶段路径：

| 阶段 | Gold仍存在 | Gold已缺失 | Gold最佳名次 |
|---|---|---|---:|
| dense_raw | 8646760 |  | 59 |
| fusion |  | 8646760 | - |
| candidate_filter |  | 8646760 | - |
| pre_assembly |  | 8646760 | - |
| context_budget |  | 8646760 | - |

`dense`在`fusion/FUSION_THRESHOLD_OR_TOPK_LOSS`阶段排名靠前的非Gold文档：

- sourceStage=fusion rank=1 `16056410` Posttranslational Acetylation of α-Tubulin Constrains Protofilament Number in Native Microtubules；dense=0.8492099，sparse=null，fusion=0.92460495，rerank=null

  > BACKGROUND Microtubules are built from linear polymers of α-β tubulin dimers (protofilaments) that form a tubular quinary structure. Microtubules assembled from purified tubulin in vitro contain between 10 and 16 protofilaments; however, such structural polymorphisms are not found in cells. This discrepancy implies that factors other than tubulin constrain microtubule protofilament number, but the nature of these constraints is unknown. RESULTS Here, we show that acetylation of MEC-12 α-tubulin constrains protofilament number in C. elegans touch receptor neurons (TRNs). Whereas the sensory den…

- sourceStage=fusion rank=2 `25799020` Direct isolation and identification of promoters in the human genome.；dense=0.84852755，sparse=null，fusion=0.924263775，rerank=null

  > Transcriptional regulatory elements play essential roles in gene expression during animal development and cellular response to environmental signals, but our knowledge of these regions in the human genome is limited despite the availability of the complete genome sequence. Promoters mark the start of every transcript and are an important class of regulatory elements. A large, complex protein structure known as the pre-initiation complex (PIC) is assembled on all active promoters, and the presence of these proteins distinguishes promoters from other sequences in the genome. Using components of…

- sourceStage=fusion rank=3 `461550` Multiplex genome engineering using CRISPR/Cas systems.；dense=0.84808433，sparse=null，fusion=0.924042165，rerank=null

  > Functional elucidation of causal genetic variants and elements requires precise genome editing technologies. The type II prokaryotic CRISPR (clustered regularly interspaced short palindromic repeats)/Cas adaptive immune system has been shown to facilitate RNA-guided site-specific DNA cleavage. We engineered two different type II CRISPR/Cas systems and demonstrate that Cas9 nucleases can be directed by short RNAs to induce precise cleavage at endogenous genomic loci in human and mouse cells. Cas9 can also be converted into a nicking enzyme to facilitate homology-directed repair with minimal mut…

## queryId=821

问题：N-terminal cleavage reduces success identifying transcription start sites.

原分类：`sparse_only_success`

Gold文档：

- `8646760` Identification and Functional Characterization of N-Terminally Acetylated Proteins in Drosophila melanogaster

  > Protein modifications play a major role for most biological processes in living organisms. Amino-terminal acetylation of proteins is a common modification found throughout the tree of life: the N-terminus of a nascent polypeptide chain becomes co-translationally acetylated, often after the removal of the initiating methionine residue. While the enzymes and protein complexes involved in these processes have been extensively studied, only little is known about the biological function of such N-terminal modification events. To identify common principles of N-terminal acetylation, we analyzed the…

| 变体 | 首个覆盖损失 | 首个完全损失 | 最终排名复现 | Rerank同请求效果 |
|---|---|---|---:|---|
| dense | fusion/FUSION_THRESHOLD_OR_TOPK_LOSS | fusion/FUSION_THRESHOLD_OR_TOPK_LOSS | 是 | 不适用 |
| sparse | 未观察到Gold损失 | 未观察到Gold损失 | 是 | 不适用 |
| hybrid_rrf | 未观察到Gold损失 | 未观察到Gold损失 | 是 | 不适用 |
| hybrid_rrf_rerank | 未观察到Gold损失 | 未观察到Gold损失 | 是 | RERANK_ORDER_GAIN (MRR Δ=0.017857) |

重点失败变体：`dense`。首个内部失效结论：fusion/FUSION_THRESHOLD_OR_TOPK_LOSS。该结论级别为阶段轨迹派生，不外推为模型根因。

重点变体Gold逐阶段路径：

| 阶段 | Gold仍存在 | Gold已缺失 | Gold最佳名次 |
|---|---|---|---:|
| dense_raw | 8646760 |  | 74 |
| fusion |  | 8646760 | - |
| candidate_filter |  | 8646760 | - |
| pre_assembly |  | 8646760 | - |
| context_budget |  | 8646760 | - |

`dense`在`fusion/FUSION_THRESHOLD_OR_TOPK_LOSS`阶段排名靠前的非Gold文档：

- sourceStage=fusion rank=1 `34498325` A conserved modified wobble nucleoside (mcm5s2U) in lysyl-tRNA is required for viability in yeast.；dense=0.84422326，sparse=null，fusion=0.9221116300000001，rerank=null

  > Transfer RNAs specific for Gln, Lys, and Glu from all organisms (except Mycoplasma) and organelles have a 2-thiouridine derivative (xm(5)s(2)U) as wobble nucleoside. These tRNAs read the A- and G-ending codons in the split codon boxes His/Gln, Asn/Lys, and Asp/Glu. In eukaryotic cytoplasmic tRNAs the conserved constituent (xm(5)-) in position 5 of uridine is 5-methoxycarbonylmethyl (mcm(5)). A protein (Tuc1p) from yeast resembling the bacterial protein TtcA, which is required for the synthesis of 2-thiocytidine in position 32 of the tRNA, was shown instead to be required for the synthesis of 2…

- sourceStage=fusion rank=2 `6333347` AIR-2: An Aurora/Ipl1-related Protein Kinase Associated with Chromosomes and Midbody Microtubules Is  Required for Polar Body Extrusion and Cytokinesis  in Caenorhabditis elegans Embryos；dense=0.8434578，sparse=null，fusion=0.9217289，rerank=null

  > An emerging family of kinases related to the Drosophila Aurora and budding yeast Ipl1 proteins has been implicated in chromosome segregation and mitotic spindle formation in a number of organisms. Unlike other Aurora/Ipl1-related kinases, the Caenorhabditis elegans orthologue, AIR-2, is associated with meiotic and mitotic chromosomes. AIR-2 is initially localized to the chromosomes of the most mature prophase I–arrested oocyte residing next to the spermatheca. This localization is dependent on the presence of sperm in the spermatheca. After fertilization, AIR-2 remains associated with chromoso…

- sourceStage=fusion rank=3 `13072112` Distinction and relationship between elongation rate and processivity of RNA polymerase II in vivo.；dense=0.84343004，sparse=null，fusion=0.9217150199999999，rerank=null

  > A number of proteins and drugs have been implicated in the process of transcriptional elongation by RNA polymerase (Pol) II, but the factors that govern the elongation rate (nucleotide additions per min) and processivity (nucleotide additions per initiation event) in vivo are poorly understood. Here, we show that a mutation in the Rpb2 subunit of Pol II reduces both the elongation rate and processivity in vivo. In contrast, none of the putative elongation factors tested affect the elongation rate, although mutations in the THO complex and in Spt4 significantly reduce processivity. The drugs 6-…

## queryId=1

问题：0-dimensional biomaterials show inductive properties.

原分类：`persistent_miss`

Gold文档：

- `31715818` New opportunities: the use of nanotechnologies to manipulate and track stem cells.

  > Nanotechnologies are emerging platforms that could be useful in measuring, understanding, and manipulating stem cells. Examples include magnetic nanoparticles and quantum dots for stem cell labeling and in vivo tracking; nanoparticles, carbon nanotubes, and polyplexes for the intracellular delivery of genes/oligonucleotides and protein/peptides; and engineered nanometer-scale scaffolds for stem cell differentiation and transplantation. This review examines the use of nanotechnologies for stem cell tracking, differentiation, and transplantation. We further discuss their utility and the potentia…

| 变体 | 首个覆盖损失 | 首个完全损失 | 最终排名复现 | Rerank同请求效果 |
|---|---|---|---:|---|
| dense | dense_raw/DENSE_RAW_TOPK_MISS | dense_raw/DENSE_RAW_TOPK_MISS | 是 | 不适用 |
| sparse | sparse_raw/SPARSE_RAW_TOPK_MISS | sparse_raw/SPARSE_RAW_TOPK_MISS | 是 | 不适用 |
| hybrid_rrf | raw_union/RAW_RECALL_TOTAL_MISS | raw_union/RAW_RECALL_TOTAL_MISS | 是 | 不适用 |
| hybrid_rrf_rerank | raw_union/RAW_RECALL_TOTAL_MISS | raw_union/RAW_RECALL_TOTAL_MISS | 是 | RERANK_NEUTRAL (MRR Δ=0.000000) |

重点失败变体：`dense`。首个内部失效结论：dense_raw/DENSE_RAW_TOPK_MISS。该结论级别为阶段轨迹派生，不外推为模型根因。

重点变体Gold逐阶段路径：

| 阶段 | Gold仍存在 | Gold已缺失 | Gold最佳名次 |
|---|---|---|---:|
| dense_raw |  | 31715818 | - |
| fusion |  | 31715818 | - |
| candidate_filter |  | 31715818 | - |
| pre_assembly |  | 31715818 | - |
| context_budget |  | 31715818 | - |

`dense`在`dense_raw/DENSE_RAW_TOPK_MISS`阶段排名靠前的非Gold文档：

- sourceStage=dense_raw rank=1 `25404036` Three-Dimensional Modeling and Quantitative Analysis of Gap Junction Distributions in Cardiac Tissue；dense=0.8308179，sparse=null，fusion=null，rerank=null

  > Gap junctions play a fundamental role in intercellular communication in cardiac tissue. Various types of heart disease including hypertrophy and ischemia are associated with alterations of the spatial arrangement of gap junctions. Previous studies applied two-dimensional optical and electron-microscopy to visualize gap junction arrangements. In normal cardiomyocytes, gap junctions were primarily found at cell ends, but can be found also in more central regions. In this study, we extended these approaches toward three-dimensional reconstruction of gap junction distributions based on high-resolu…

- sourceStage=dense_raw rank=2 `19685306` Orientationally invariant indices of axon diameter and density from diffusion MRI.；dense=0.8301989，sparse=null，fusion=null，rerank=null

  > This paper proposes and tests a technique for imaging orientationally invariant indices of axon diameter and density in white matter using diffusion magnetic resonance imaging. Such indices potentially provide more specific markers of white matter microstructure than standard indices from diffusion tensor imaging. Orientational invariance allows for combination with tractography and presents new opportunities for mapping brain connectivity and quantifying disease processes. The technique uses a four-compartment tissue model combined with an optimized multishell high-angular-resolution pulsed-g…

- sourceStage=dense_raw rank=3 `7583104` IDEAL in meshes for prolapse, urinary incontinence, and hernia repair.；dense=0.8286524，sparse=null，fusion=null，rerank=null

  > PURPOSE Mesh surgeries are counted among the most frequently applied surgical procedures. Despite global spread of mesh applying surgeries, there is no current systematic analysis of incidence and possible prevention of adverse events after mesh implantation. MATERIALS AND METHODS Based on the recommendations of IDEAL an in vitro test system for biocompatibility of surgical meshes has been generated (Innovation). Coating strategies for biocompatibility optimization have been developed (Development). The native and modified alloplastic materials have been tested in an animal model over 2 years…

`sparse`在`sparse_raw/SPARSE_RAW_TOPK_MISS`阶段排名靠前的非Gold文档：

- sourceStage=sparse_raw rank=1 `12207167` Adverse effects of excessive consumption of amino acids.；dense=null，sparse=0.3476308，fusion=null，rerank=null

  > PHENYLALANINE TOXICITY 158 Developing the 0. -M ethylphenylalanine Model. . . . . . .. . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . 160 Use of the a-Methyl phenylalanine Model in Brain Protein Synthesis . . . . . . . . . . . . . . . . . . . 161 TYROSINE TOXICITY 162 General Nutritional Observations . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . 162 Factors Affecting Tissue Concentrations of Tyrosine . ... .. .. .. ...... . . . . . . .. . . 163 Probable Cause of Tyrosine Toxicity . . .…

- sourceStage=sparse_raw rank=2 `8317408` Human monocyte characteristics are altered in hypercholesterolaemia.；dense=null，sparse=0.31099623，fusion=null，rerank=null

  > Peripheral blood monocytes are involved during atherogenesis in adhering to endothelium, migrating into the subendothelial space and taking-up lipoproteins to become macrophage/foam cells. We have assessed whether peripheral blood monocyte characteristics are altered in human hyperlipidaemia in age/sex/smoking status matched pairs of patients and controls. Monocytes from the hypercholesterolaemic patients, as opposed to the controls, were more sensitive to stimulation by the agonist, N-formyl-methionyl-leucyl-phenylalanine, with respect to chemokinesis (stimulation index 1.48 +/- 0.17 vs. 1.10…

- sourceStage=sparse_raw rank=3 `36271512` T-cell activation.；dense=null，sparse=0.29757646，fusion=null，rerank=null

  > INTRODUCTION • • CELLULAR AND MOLECULAR REQUIREMENTS FOR T-CELL ACTIVATION . The T-Cell Antigen Receptor Complex . . . .. . . . ..... . . . . . . . . . . . . . . . . ...... . . . T-Cell Activation by Antibodies and Leetins . . . . . . . . . . .. . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . Other Cell Surface Structures (Accessory Molecules) Involved in Antigen Recognition and Activation . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . .…

`hybrid_rrf`在`raw_union/RAW_RECALL_TOTAL_MISS`阶段排名靠前的非Gold文档：

- sourceStage=sparse_raw rank=1 `12207167` Adverse effects of excessive consumption of amino acids.；dense=null，sparse=0.3476308，fusion=null，rerank=null

  > PHENYLALANINE TOXICITY 158 Developing the 0. -M ethylphenylalanine Model. . . . . . .. . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . 160 Use of the a-Methyl phenylalanine Model in Brain Protein Synthesis . . . . . . . . . . . . . . . . . . . 161 TYROSINE TOXICITY 162 General Nutritional Observations . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . 162 Factors Affecting Tissue Concentrations of Tyrosine . ... .. .. .. ...... . . . . . . .. . . 163 Probable Cause of Tyrosine Toxicity . . .…

- sourceStage=dense_raw rank=1 `25404036` Three-Dimensional Modeling and Quantitative Analysis of Gap Junction Distributions in Cardiac Tissue；dense=0.8308179，sparse=null，fusion=null，rerank=null

  > Gap junctions play a fundamental role in intercellular communication in cardiac tissue. Various types of heart disease including hypertrophy and ischemia are associated with alterations of the spatial arrangement of gap junctions. Previous studies applied two-dimensional optical and electron-microscopy to visualize gap junction arrangements. In normal cardiomyocytes, gap junctions were primarily found at cell ends, but can be found also in more central regions. In this study, we extended these approaches toward three-dimensional reconstruction of gap junction distributions based on high-resolu…

- sourceStage=dense_raw rank=2 `19685306` Orientationally invariant indices of axon diameter and density from diffusion MRI.；dense=0.8301989，sparse=null，fusion=null，rerank=null

  > This paper proposes and tests a technique for imaging orientationally invariant indices of axon diameter and density in white matter using diffusion magnetic resonance imaging. Such indices potentially provide more specific markers of white matter microstructure than standard indices from diffusion tensor imaging. Orientational invariance allows for combination with tractography and presents new opportunities for mapping brain connectivity and quantifying disease processes. The technique uses a four-compartment tissue model combined with an optimized multishell high-angular-resolution pulsed-g…

`hybrid_rrf_rerank`在`raw_union/RAW_RECALL_TOTAL_MISS`阶段排名靠前的非Gold文档：

- sourceStage=sparse_raw rank=1 `12207167` Adverse effects of excessive consumption of amino acids.；dense=null，sparse=0.3476308，fusion=null，rerank=null

  > PHENYLALANINE TOXICITY 158 Developing the 0. -M ethylphenylalanine Model. . . . . . .. . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . 160 Use of the a-Methyl phenylalanine Model in Brain Protein Synthesis . . . . . . . . . . . . . . . . . . . 161 TYROSINE TOXICITY 162 General Nutritional Observations . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . 162 Factors Affecting Tissue Concentrations of Tyrosine . ... .. .. .. ...... . . . . . . .. . . 163 Probable Cause of Tyrosine Toxicity . . .…

- sourceStage=dense_raw rank=1 `25404036` Three-Dimensional Modeling and Quantitative Analysis of Gap Junction Distributions in Cardiac Tissue；dense=0.8308179，sparse=null，fusion=null，rerank=null

  > Gap junctions play a fundamental role in intercellular communication in cardiac tissue. Various types of heart disease including hypertrophy and ischemia are associated with alterations of the spatial arrangement of gap junctions. Previous studies applied two-dimensional optical and electron-microscopy to visualize gap junction arrangements. In normal cardiomyocytes, gap junctions were primarily found at cell ends, but can be found also in more central regions. In this study, we extended these approaches toward three-dimensional reconstruction of gap junction distributions based on high-resolu…

- sourceStage=dense_raw rank=2 `19685306` Orientationally invariant indices of axon diameter and density from diffusion MRI.；dense=0.8301989，sparse=null，fusion=null，rerank=null

  > This paper proposes and tests a technique for imaging orientationally invariant indices of axon diameter and density in white matter using diffusion magnetic resonance imaging. Such indices potentially provide more specific markers of white matter microstructure than standard indices from diffusion tensor imaging. Orientational invariance allows for combination with tractography and presents new opportunities for mapping brain connectivity and quantifying disease processes. The technique uses a four-compartment tissue model combined with an optimized multishell high-angular-resolution pulsed-g…

## queryId=1100

问题：Statins increase blood cholesterol.

原分类：`persistent_miss`

Gold文档：

- `7662206` A Century of Cholesterol and Coronaries: From Plaques to Genes to Statins

  > One-fourth of all deaths in industrialized countries result from coronary heart disease. A century of research has revealed the essential causative agent: cholesterol-carrying low-density lipoprotein (LDL). LDL is controlled by specific receptors (LDLRs) in liver that remove it from blood. Mutations that eliminate LDLRs raise LDL and cause heart attacks in childhood, whereas mutations that raise LDLRs reduce LDL and diminish heart attacks. If we are to eliminate coronary disease, lowering LDL should be the primary goal. Effective means to achieve this goal are currently available. The key ques…

| 变体 | 首个覆盖损失 | 首个完全损失 | 最终排名复现 | Rerank同请求效果 |
|---|---|---|---:|---|
| dense | fusion/FUSION_THRESHOLD_OR_TOPK_LOSS | fusion/FUSION_THRESHOLD_OR_TOPK_LOSS | 是 | 不适用 |
| sparse | fusion/FUSION_THRESHOLD_OR_TOPK_LOSS | fusion/FUSION_THRESHOLD_OR_TOPK_LOSS | 是 | 不适用 |
| hybrid_rrf | fusion/FUSION_THRESHOLD_OR_TOPK_LOSS | fusion/FUSION_THRESHOLD_OR_TOPK_LOSS | 是 | 不适用 |
| hybrid_rrf_rerank | fusion/FUSION_THRESHOLD_OR_TOPK_LOSS | fusion/FUSION_THRESHOLD_OR_TOPK_LOSS | 是 | RERANK_NEUTRAL (MRR Δ=0.000000) |

重点失败变体：`dense`。首个内部失效结论：fusion/FUSION_THRESHOLD_OR_TOPK_LOSS。该结论级别为阶段轨迹派生，不外推为模型根因。

重点变体Gold逐阶段路径：

| 阶段 | Gold仍存在 | Gold已缺失 | Gold最佳名次 |
|---|---|---|---:|
| dense_raw | 7662206 |  | 19 |
| fusion |  | 7662206 | - |
| candidate_filter |  | 7662206 | - |
| pre_assembly |  | 7662206 | - |
| context_budget |  | 7662206 | - |

`dense`在`fusion/FUSION_THRESHOLD_OR_TOPK_LOSS`阶段排名靠前的非Gold文档：

- sourceStage=fusion rank=1 `21557614` Pleiotropic effects of statins.；dense=0.87856436，sparse=null，fusion=0.93928218，rerank=null

  > Statins are potent inhibitors of cholesterol biosynthesis. In clinical trials, statins are beneficial in the primary and secondary prevention of coronary heart disease. However, the overall benefits observed with statins appear to be greater than what might be expected from changes in lipid levels alone, suggesting effects beyond cholesterol lowering. Indeed, recent studies indicate that some of the cholesterol-independent or "pleiotropic" effects of statins involve improving endothelial function, enhancing the stability of atherosclerotic plaques, decreasing oxidative stress and inflammation,…

- sourceStage=fusion rank=2 `30981192` How to control residual cardiovascular risk despite statin treatment: focusing on HDL-cholesterol.；dense=0.8618319，sparse=null，fusion=0.9309159499999999，rerank=null

  > Lowering low-density lipoprotein-cholesterol (LDL-C) is the primary target in the management of dyslipidemia in patients at high risk of cardiovascular disease. However, patients who have achieved LDL-C levels below the currently recommended targets may still experience cardiovascular events. This may result, in part, from elevated triglyceride (TG) levels and low levels of high-density lipoprotein-cholesterol (HDL-C). Low HDL-C and high TG levels are common and are recognized as independent risk factors for cardiovascular morbidity and mortality. Furthermore, atherogenic dyslipidemia, charact…

- sourceStage=fusion rank=3 `4687948` HMG-CoA reductase inhibitors and the risk of hip fractures in elderly patients.；dense=0.8487698，sparse=null，fusion=0.9243849，rerank=null

  > CONTEXT Recent animal studies have found that 3-hydroxy-3-methylglutaryl coenzyme A (HMG-CoA) lipid-lowering drugs (statins) substantially increase bone formation, but whether statin use in humans results in clinically meaningful bone formation or a reduction in the risk of osteoporotic fractures is not known. OBJECTIVE To determine whether the use of statins is associated with reduced hip fracture risk. DESIGN Case-control study. SETTING AND PATIENTS A total of 6110 New Jersey residents aged 65 years or older and enrolled in Medicare and either Medicaid or the Pharmacy Assistance for the Aged…

`sparse`在`fusion/FUSION_THRESHOLD_OR_TOPK_LOSS`阶段排名靠前的非Gold文档：

- sourceStage=fusion rank=1 `12207167` Adverse effects of excessive consumption of amino acids.；dense=null，sparse=0.43972206，fusion=0.3054214922566374，rerank=null

  > PHENYLALANINE TOXICITY 158 Developing the 0. -M ethylphenylalanine Model. . . . . . .. . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . 160 Use of the a-Methyl phenylalanine Model in Brain Protein Synthesis . . . . . . . . . . . . . . . . . . . 161 TYROSINE TOXICITY 162 General Nutritional Observations . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . 162 Factors Affecting Tissue Concentrations of Tyrosine . ... .. .. .. ...... . . . . . . .. . . 163 Probable Cause of Tyrosine Toxicity . . .…

- sourceStage=fusion rank=2 `86217760` The Self-Incompatibility Genes of Brassica: Expression and Use in Genetic Ablation of Floral Tissues；dense=null，sparse=0.3538308，fusion=0.2613552594607835，rerank=null

  > INTRODUCTION . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . .. . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . 394 POLLINATION AND POLLEN TUBE GROWTH . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . 395 Interaction s between the M ale G ameto phyte and Pistil . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . 395 SelfIncom patibili ty Systems: Gameto phytic and S poro phyti c Determin ation of Pollen Phenoty pe . . . . . . . . . . . . . . . . .. . . . . . . . . . . .…

- sourceStage=fusion rank=6 `36271512` T-cell activation.；dense=null，sparse=0.3003571，fusion=0.23098047451734602，rerank=null

  > INTRODUCTION • • CELLULAR AND MOLECULAR REQUIREMENTS FOR T-CELL ACTIVATION . The T-Cell Antigen Receptor Complex . . . .. . . . ..... . . . . . . . . . . . . . . . . ...... . . . T-Cell Activation by Antibodies and Leetins . . . . . . . . . . .. . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . Other Cell Surface Structures (Accessory Molecules) Involved in Antigen Recognition and Activation . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . .…

`hybrid_rrf`在`fusion/FUSION_THRESHOLD_OR_TOPK_LOSS`阶段排名靠前的非Gold文档：

- sourceStage=fusion rank=1 `21557614` Pleiotropic effects of statins.；dense=0.87856436，sparse=0.2472398，fusion=0.9178082191780822，rerank=null

  > Statins are potent inhibitors of cholesterol biosynthesis. In clinical trials, statins are beneficial in the primary and secondary prevention of coronary heart disease. However, the overall benefits observed with statins appear to be greater than what might be expected from changes in lipid levels alone, suggesting effects beyond cholesterol lowering. Indeed, recent studies indicate that some of the cholesterol-independent or "pleiotropic" effects of statins involve improving endothelial function, enhancing the stability of atherosclerotic plaques, decreasing oxidative stress and inflammation,…

- sourceStage=fusion rank=2 `13933299` Midlife Serum Cholesterol and Increased Risk of Alzheimer’s and Vascular Dementia Three Decades Later；dense=0.845175，sparse=0.2035271，fusion=0.8116246498599439，rerank=null

  > Aims: To investigate midlife cholesterol in relation to Alzheimer’s disease (AD) and vascular dementia (VaD) in a large multiethnic cohort of women and men. Methods: The Kaiser Permanente Northern California Medical Group (healthcare delivery organization) formed the database for this study. The 9,844 participants underwent detailed health evaluations during 1964–1973 at ages 40–45 years; they were still members of the health plan in 1994. AD and VaD were ascertained by medical records between 1 January 1994 and 1 June 2007. Cox proportional hazards models – adjusted for age, education, race/e…

- sourceStage=fusion rank=3 `7552215` Long term pharmacotherapy for obesity and overweight: updated meta-analysis.；dense=0.83688474，sparse=0.23416325，fusion=0.7697619047619048，rerank=null

  > OBJECTIVE To summarise the long term efficacy of anti-obesity drugs in reducing weight and improving health status. DESIGN Updated meta-analysis of randomised trials. DATA SOURCES Medline, Embase, the Cochrane controlled trials register, the Current Science meta-register of controlled trials, and reference lists of identified articles. All data sources were searched from December 2002 (end date of last search) to December 2006. STUDIES REVIEWED Double blind randomised placebo controlled trials of approved anti-obesity drugs used in adults (age over 18) for one year or longer. RESULTS 30 trials…

`hybrid_rrf_rerank`在`fusion/FUSION_THRESHOLD_OR_TOPK_LOSS`阶段排名靠前的非Gold文档：

- sourceStage=fusion rank=1 `21557614` Pleiotropic effects of statins.；dense=0.87856436，sparse=0.2472398，fusion=0.9178082191780822，rerank=null

  > Statins are potent inhibitors of cholesterol biosynthesis. In clinical trials, statins are beneficial in the primary and secondary prevention of coronary heart disease. However, the overall benefits observed with statins appear to be greater than what might be expected from changes in lipid levels alone, suggesting effects beyond cholesterol lowering. Indeed, recent studies indicate that some of the cholesterol-independent or "pleiotropic" effects of statins involve improving endothelial function, enhancing the stability of atherosclerotic plaques, decreasing oxidative stress and inflammation,…

- sourceStage=fusion rank=2 `13933299` Midlife Serum Cholesterol and Increased Risk of Alzheimer’s and Vascular Dementia Three Decades Later；dense=0.845175，sparse=0.2035271，fusion=0.8116246498599439，rerank=null

  > Aims: To investigate midlife cholesterol in relation to Alzheimer’s disease (AD) and vascular dementia (VaD) in a large multiethnic cohort of women and men. Methods: The Kaiser Permanente Northern California Medical Group (healthcare delivery organization) formed the database for this study. The 9,844 participants underwent detailed health evaluations during 1964–1973 at ages 40–45 years; they were still members of the health plan in 1994. AD and VaD were ascertained by medical records between 1 January 1994 and 1 June 2007. Cox proportional hazards models – adjusted for age, education, race/e…

- sourceStage=fusion rank=3 `7552215` Long term pharmacotherapy for obesity and overweight: updated meta-analysis.；dense=0.83688474，sparse=0.23416325，fusion=0.7697619047619048，rerank=null

  > OBJECTIVE To summarise the long term efficacy of anti-obesity drugs in reducing weight and improving health status. DESIGN Updated meta-analysis of randomised trials. DATA SOURCES Medline, Embase, the Cochrane controlled trials register, the Current Science meta-register of controlled trials, and reference lists of identified articles. All data sources were searched from December 2002 (end date of last search) to December 2006. STUDIES REVIEWED Double blind randomised placebo controlled trials of approved anti-obesity drugs used in adults (age over 18) for one year or longer. RESULTS 30 trials…

## queryId=1110

问题：Suboptimal nutrition is not predictive of chronic disease

原分类：`persistent_miss`

Gold文档：

- `13770184` Global, regional, and national comparative risk assessment of 79 behavioural, environmental and occupational, and metabolic risks or clusters of risks, 1990–2015: a systematic analysis for the Global Burden of Disease Study 2015

  > BACKGROUND The Global Burden of Diseases, Injuries, and Risk Factors Study 2015 provides an up-to-date synthesis of the evidence for risk factor exposure and the attributable burden of disease. By providing national and subnational assessments spanning the past 25 years, this study can inform debates on the importance of addressing risks in context. METHODS We used the comparative risk assessment framework developed for previous iterations of the Global Burden of Disease Study to estimate attributable deaths, disability-adjusted life-years (DALYs), and trends in exposure by age group, sex, yea…

| 变体 | 首个覆盖损失 | 首个完全损失 | 最终排名复现 | Rerank同请求效果 |
|---|---|---|---:|---|
| dense | dense_raw/DENSE_RAW_TOPK_MISS | dense_raw/DENSE_RAW_TOPK_MISS | 是 | 不适用 |
| sparse | sparse_raw/SPARSE_RAW_TOPK_MISS | sparse_raw/SPARSE_RAW_TOPK_MISS | 是 | 不适用 |
| hybrid_rrf | raw_union/RAW_RECALL_TOTAL_MISS | raw_union/RAW_RECALL_TOTAL_MISS | 是 | 不适用 |
| hybrid_rrf_rerank | raw_union/RAW_RECALL_TOTAL_MISS | raw_union/RAW_RECALL_TOTAL_MISS | 是 | RERANK_NEUTRAL (MRR Δ=0.000000) |

重点失败变体：`dense`。首个内部失效结论：dense_raw/DENSE_RAW_TOPK_MISS。该结论级别为阶段轨迹派生，不外推为模型根因。

重点变体Gold逐阶段路径：

| 阶段 | Gold仍存在 | Gold已缺失 | Gold最佳名次 |
|---|---|---|---:|
| dense_raw |  | 13770184 | - |
| fusion |  | 13770184 | - |
| candidate_filter |  | 13770184 | - |
| pre_assembly |  | 13770184 | - |
| context_budget |  | 13770184 | - |

`dense`在`dense_raw/DENSE_RAW_TOPK_MISS`阶段排名靠前的非Gold文档：

- sourceStage=dense_raw rank=1 `21274919` The association between common physical impairments and dementia in low and middle income countries, and, among people with dementia, their association with cognitive function and disability. A 10/66 Dementia Research Group population-based study.；dense=0.8391108，sparse=null，fusion=null，rerank=null

  > OBJECTIVE Chronic physical comorbidity is common in dementia. However, there is an absence of evidence to support good practice guidelines for attention to these problems. We aimed to study the extent of this comorbidity and its impact on cognitive function and disability in population-based studies in low and middle income countries, where chronic diseases and impairments are likely to be both common and undertreated. METHODS A multicentre cross-sectional survey of all over 65 year old residents (n = 15 022) in 11 catchment areas in China, India, Cuba, Dominican Republic, Venezuela, Mexico an…

- sourceStage=dense_raw rank=2 `8529693` Maternal and child undernutrition: consequences for adult health and human capital；dense=0.8380196，sparse=null，fusion=null，rerank=null

  > In this paper we review the associations between maternal and child undernutrition with human capital and risk of adult diseases in low-income and middle-income countries. We analysed data from five long-standing prospective cohort studies from Brazil, Guatemala, India, the Philippines, and South Africa and noted that indices of maternal and child undernutrition (maternal height, birthweight, intrauterine growth restriction, and weight, height, and body-mass index at 2 years according to the new WHO growth standards) were related to adult outcomes (height, schooling, income or assets, offsprin…

- sourceStage=dense_raw rank=3 `32766786` Neoadjuvant androgen ablation before radical prostatectomy in cT2bNxMo prostate cancer: 5-year results.；dense=0.837456，sparse=null，fusion=null，rerank=null

  > PURPOSE In the initial report of the Lupron Depot Neoadjuvant Prostate Cancer Study Group patients who received 3 months of androgen deprivation had a significant decrease in the positive margin rate. We monitored these patients for 5 years and to our knowledge present the longest followup of any neoadjuvant trial. MATERIALS AND METHODS A multi-institutional prospective randomized trial was performed between February 1992 and April 1994 involving patients with stage cT2b prostate cancer, including 138 who received 3 months of leuprolide plus flutamide before radical prostatectomy and 144 who u…

`sparse`在`sparse_raw/SPARSE_RAW_TOPK_MISS`阶段排名靠前的非Gold文档：

- sourceStage=sparse_raw rank=1 `12236208` Hormone replacement therapy prevents bone loss in patients with inflammatory bowel disease.；dense=null，sparse=0.2974162，fusion=null，rerank=null

  > Patients with inflammatory bowel disease have an increased prevalence of osteoporosis, and suffer high rates of spinal bone loss. Hormone replacement therapy (HRT) is effective in the treatment and prevention of osteoporosis but has not been studied in patients with inflammatory bowel disease. A two year prospective study of HRT in inflammatory bowel disease was performed in 47 postmenopausal women aged 44 to 67 years with ulcerative colitis (25) or Crohn's disease (22). Patients had radial and spinal bone density measured annually by single photon absorptiometry and quantitative computed tomo…

- sourceStage=sparse_raw rank=2 `33409100` Effect of homocysteine lowering on mortality and vascular disease in advanced chronic kidney disease and end-stage renal disease: a randomized controlled trial.；dense=null，sparse=0.2678792，fusion=null，rerank=null

  > CONTEXT High plasma homocysteine levels are a risk factor for mortality and vascular disease in observational studies of patients with chronic kidney disease. Folic acid and B vitamins decrease homocysteine levels in this population but whether they lower mortality is unknown. OBJECTIVE To determine whether high doses of folic acid and B vitamins administered daily reduce mortality in patients with chronic kidney disease. DESIGN, SETTING, AND PARTICIPANTS Double-blind randomized controlled trial (2001-2006) in 36 US Department of Veterans Affairs medical centers. Median follow-up was 3.2 years…

- sourceStage=sparse_raw rank=3 `97884` The sacroiliac joint in the spondyloarthropathies.；dense=null，sparse=0.26510367，fusion=null，rerank=null

  > The term spondyloarthropathy (SpA) describes and defines a group of related inflammatory joint disease that share characteristic clinical features and a unique association with the major histocompatibility complex class I molecule HLA-B27. Five subgroups can be differentiated: ankylosing spondylitis, reactive arthritis, psoriatic arthritis, arthritis associated with inflammatory bowel disease, and undifferentiated SpA. The sacroiliac joints are centrally involved in the SpA, most clearly and pathognomonic in ankylosing spondylitis, in which most patients are affected early in the disease. Over…

`hybrid_rrf`在`raw_union/RAW_RECALL_TOTAL_MISS`阶段排名靠前的非Gold文档：

- sourceStage=sparse_raw rank=1 `12236208` Hormone replacement therapy prevents bone loss in patients with inflammatory bowel disease.；dense=null，sparse=0.2974162，fusion=null，rerank=null

  > Patients with inflammatory bowel disease have an increased prevalence of osteoporosis, and suffer high rates of spinal bone loss. Hormone replacement therapy (HRT) is effective in the treatment and prevention of osteoporosis but has not been studied in patients with inflammatory bowel disease. A two year prospective study of HRT in inflammatory bowel disease was performed in 47 postmenopausal women aged 44 to 67 years with ulcerative colitis (25) or Crohn's disease (22). Patients had radial and spinal bone density measured annually by single photon absorptiometry and quantitative computed tomo…

- sourceStage=dense_raw rank=1 `21274919` The association between common physical impairments and dementia in low and middle income countries, and, among people with dementia, their association with cognitive function and disability. A 10/66 Dementia Research Group population-based study.；dense=0.8391108，sparse=null，fusion=null，rerank=null

  > OBJECTIVE Chronic physical comorbidity is common in dementia. However, there is an absence of evidence to support good practice guidelines for attention to these problems. We aimed to study the extent of this comorbidity and its impact on cognitive function and disability in population-based studies in low and middle income countries, where chronic diseases and impairments are likely to be both common and undertreated. METHODS A multicentre cross-sectional survey of all over 65 year old residents (n = 15 022) in 11 catchment areas in China, India, Cuba, Dominican Republic, Venezuela, Mexico an…

- sourceStage=sparse_raw rank=2 `33409100` Effect of homocysteine lowering on mortality and vascular disease in advanced chronic kidney disease and end-stage renal disease: a randomized controlled trial.；dense=null，sparse=0.2678792，fusion=null，rerank=null

  > CONTEXT High plasma homocysteine levels are a risk factor for mortality and vascular disease in observational studies of patients with chronic kidney disease. Folic acid and B vitamins decrease homocysteine levels in this population but whether they lower mortality is unknown. OBJECTIVE To determine whether high doses of folic acid and B vitamins administered daily reduce mortality in patients with chronic kidney disease. DESIGN, SETTING, AND PARTICIPANTS Double-blind randomized controlled trial (2001-2006) in 36 US Department of Veterans Affairs medical centers. Median follow-up was 3.2 years…

`hybrid_rrf_rerank`在`raw_union/RAW_RECALL_TOTAL_MISS`阶段排名靠前的非Gold文档：

- sourceStage=sparse_raw rank=1 `12236208` Hormone replacement therapy prevents bone loss in patients with inflammatory bowel disease.；dense=null，sparse=0.2974162，fusion=null，rerank=null

  > Patients with inflammatory bowel disease have an increased prevalence of osteoporosis, and suffer high rates of spinal bone loss. Hormone replacement therapy (HRT) is effective in the treatment and prevention of osteoporosis but has not been studied in patients with inflammatory bowel disease. A two year prospective study of HRT in inflammatory bowel disease was performed in 47 postmenopausal women aged 44 to 67 years with ulcerative colitis (25) or Crohn's disease (22). Patients had radial and spinal bone density measured annually by single photon absorptiometry and quantitative computed tomo…

- sourceStage=dense_raw rank=1 `21274919` The association between common physical impairments and dementia in low and middle income countries, and, among people with dementia, their association with cognitive function and disability. A 10/66 Dementia Research Group population-based study.；dense=0.8391108，sparse=null，fusion=null，rerank=null

  > OBJECTIVE Chronic physical comorbidity is common in dementia. However, there is an absence of evidence to support good practice guidelines for attention to these problems. We aimed to study the extent of this comorbidity and its impact on cognitive function and disability in population-based studies in low and middle income countries, where chronic diseases and impairments are likely to be both common and undertreated. METHODS A multicentre cross-sectional survey of all over 65 year old residents (n = 15 022) in 11 catchment areas in China, India, Cuba, Dominican Republic, Venezuela, Mexico an…

- sourceStage=sparse_raw rank=2 `33409100` Effect of homocysteine lowering on mortality and vascular disease in advanced chronic kidney disease and end-stage renal disease: a randomized controlled trial.；dense=null，sparse=0.2678792，fusion=null，rerank=null

  > CONTEXT High plasma homocysteine levels are a risk factor for mortality and vascular disease in observational studies of patients with chronic kidney disease. Folic acid and B vitamins decrease homocysteine levels in this population but whether they lower mortality is unknown. OBJECTIVE To determine whether high doses of folic acid and B vitamins administered daily reduce mortality in patients with chronic kidney disease. DESIGN, SETTING, AND PARTICIPANTS Double-blind randomized controlled trial (2001-2006) in 36 US Department of Veterans Affairs medical centers. Median follow-up was 3.2 years…

## queryId=1204

问题：The combination of H3K4me3 and H3K79me2 is found in quiescent hair follicle stem cells.

原分类：`rerank_reorder_gain`

Gold文档：

- `31141365` Genome-wide maps of histone modifications unwind in vivo chromatin states of the hair follicle lineage.

  > Using mouse skin, where bountiful reservoirs of synchronized hair follicle stem cells (HF-SCs) fuel cycles of regeneration, we explore how adult SCs remodel chromatin in response to activating cues. By profiling global mRNA and chromatin changes in quiescent and activated HF-SCs and their committed, transit-amplifying (TA) progeny, we show that polycomb-group (PcG)-mediated H3K27-trimethylation features prominently in HF-lineage progression by mechanisms distinct from embryonic-SCs. In HF-SCs, PcG represses nonskin lineages and HF differentiation. In TA progeny, nonskin regulators remain PcG-r…

| 变体 | 首个覆盖损失 | 首个完全损失 | 最终排名复现 | Rerank同请求效果 |
|---|---|---|---:|---|
| dense | 未观察到Gold损失 | 未观察到Gold损失 | 是 | 不适用 |
| sparse | sparse_raw/SPARSE_RAW_TOPK_MISS | sparse_raw/SPARSE_RAW_TOPK_MISS | 是 | 不适用 |
| hybrid_rrf | 未观察到Gold损失 | 未观察到Gold损失 | 是 | 不适用 |
| hybrid_rrf_rerank | 未观察到Gold损失 | 未观察到Gold损失 | 是 | RERANK_ORDER_GAIN (MRR Δ=0.900000) |

重点失败变体：`hybrid_rrf_rerank`。首个内部失效结论：未观察到Gold损失。该结论级别为阶段轨迹派生，不外推为模型根因。

重点变体Gold逐阶段路径：

| 阶段 | Gold仍存在 | Gold已缺失 | Gold最佳名次 |
|---|---|---|---:|
| raw_union | 31141365 |  | - |
| fusion | 31141365 |  | 10 |
| candidate_filter | 31141365 |  | 10 |
| rerank_input | 31141365 |  | 10 |
| rerank_output | 31141365 |  | 1 |
| context_budget | 31141365 |  | 1 |

`sparse`在`sparse_raw/SPARSE_RAW_TOPK_MISS`阶段排名靠前的非Gold文档：

- sourceStage=sparse_raw rank=1 `6082738` Cell fusion hypothesis of the cancer stem cell.；dense=null，sparse=0.43493733，fusion=null，rerank=null

  > A major advance in recent cancer research is the identification of tumor cells with stem cell-like properties. Cancer stem cells (CSCs) often represent a rare population in the tumor mass and possess the exclusive ability to initiate the growth of a heterogeneous tumor. The origin of CSCs remains elusive and is likely to be cancer type specific. One possible but under-appreciated potential mechanism for the generation of CSCs is through fusion between stem cells and differentiated cells. The cell fusion hypothesis of CSCs adds an important functional underpinning to the potential multifaceted…

- sourceStage=sparse_raw rank=2 `4335423` Continuous single-cell imaging of blood generation from haemogenic endothelium；dense=null，sparse=0.42339587，fusion=null，rerank=null

  > Despite decades of research, the identity of the cells generating the first haematopoietic cells in mammalian embryos is unknown. Indeed, whether blood cells arise from mesodermal cells, mesenchymal progenitors, bipotent endothelial–haematopoietic precursors or haemogenic endothelial cells remains controversial. Proximity of endothelial and blood cells at sites of embryonic haematopoiesis, as well as their similar gene expression, led to the hypothesis of the endothelium generating blood. However, owing to lacking technology it has been impossible to observe blood cell emergence continuously a…

- sourceStage=sparse_raw rank=3 `27686445` Effect of age, sex, and sites on the cellularity of the adipose tissue in mice and rats rendered obese by a high-fat diet.；dense=null，sparse=0.41741762，fusion=null，rerank=null

  > Cell size and number of parametrial fat pads were determined in Swiss mice made obese by means of a high-fat diet (40% lard w/w) given ad lib. This diet and a control were introduced to two groups of mothers during gestation and lactation, and sucklings were given the same diets as their mothers at weaning and throughout life.2-wk old mice suckled by mothers fed a high-fat diet have fatter parametrial pads. This difference is due solely to an increase in fat cell size. After weaning, until the 18th wk, the two groups differed with a striking fat cell enlargement seen in the obese group. Later…

## queryId=237

问题：Cells lacking clpC have a defect in sporulation efficiency in Bacillus subtilis.

原分类：`rerank_reorder_gain`

Gold文档：

- `4942718` High-Throughput Genetic Screens Identify a Large and Diverse Collection of New Sporulation Genes in Bacillus subtilis

  > The differentiation of the bacterium Bacillus subtilis into a dormant spore is among the most well-characterized developmental pathways in biology. Classical genetic screens performed over the past half century identified scores of factors involved in every step of this morphological process. More recently, transcriptional profiling uncovered additional sporulation-induced genes required for successful spore development. Here, we used transposon-sequencing (Tn-seq) to assess whether there were any sporulation genes left to be discovered. Our screen identified 133 out of the 148 genes with know…

| 变体 | 首个覆盖损失 | 首个完全损失 | 最终排名复现 | Rerank同请求效果 |
|---|---|---|---:|---|
| dense | 未观察到Gold损失 | 未观察到Gold损失 | 是 | 不适用 |
| sparse | sparse_raw/SPARSE_RAW_TOPK_MISS | sparse_raw/SPARSE_RAW_TOPK_MISS | 是 | 不适用 |
| hybrid_rrf | 未观察到Gold损失 | 未观察到Gold损失 | 是 | 不适用 |
| hybrid_rrf_rerank | 未观察到Gold损失 | 未观察到Gold损失 | 是 | RERANK_ORDER_GAIN (MRR Δ=0.900000) |

重点失败变体：`hybrid_rrf_rerank`。首个内部失效结论：未观察到Gold损失。该结论级别为阶段轨迹派生，不外推为模型根因。

重点变体Gold逐阶段路径：

| 阶段 | Gold仍存在 | Gold已缺失 | Gold最佳名次 |
|---|---|---|---:|
| raw_union | 4942718 |  | - |
| fusion | 4942718 |  | 10 |
| candidate_filter | 4942718 |  | 10 |
| rerank_input | 4942718 |  | 10 |
| rerank_output | 4942718 |  | 1 |
| context_budget | 4942718 |  | 1 |

`sparse`在`sparse_raw/SPARSE_RAW_TOPK_MISS`阶段排名靠前的非Gold文档：

- sourceStage=sparse_raw rank=1 `712078` Pharmacological correction of a defect in PPARγ signaling ameliorates disease severity in Cftr-deficient mice；dense=null，sparse=0.33383307，fusion=null，rerank=null

  > Cystic fibrosis is caused by mutations in the cystic fibrosis transmembrane conductance regulator (encoded by Cftr) that impair its role as an apical chloride channel that supports bicarbonate transport. Individuals with cystic fibrosis show retained, thickened mucus that plugs airways and obstructs luminal organs as well as numerous other abnormalities that include inflammation of affected organs, alterations in lipid metabolism and insulin resistance. Here we show that colonic epithelial cells and whole lung tissue from Cftr-deficient mice show a defect in peroxisome proliferator-activated r…

- sourceStage=sparse_raw rank=2 `38793927` Osteoclast nuclei of myeloma patients show chromosome translocations specific for the myeloma cell clone: a new type of cancer-host partnership?；dense=null，sparse=0.3154673，fusion=null，rerank=null

  > A major clinical manifestation of bone cancers is bone destruction. It is widely accepted that this destruction is not caused by the malignant cells themselves, but by osteoclasts, multinucleated cells of monocytic origin that are considered to be the only cells able to degrade bone. The present study demonstrates that bone-resorbing osteoclasts from myeloma patients contain nuclei with translocated chromosomes of myeloma B-cell clone origin, in addition to nuclei without these translocations, by using combined FISH and immunohistochemistry on bone sections. These nuclei of malignant origin ar…

- sourceStage=sparse_raw rank=3 `4256553` Establishment in culture of pluripotential cells from mouse embryos；dense=null，sparse=0.31424123，fusion=null，rerank=null

  > Pluripotential cells are present in a mouse embryo until at least an early post-implantation stage, as shown by their ability to take part hi the formation of chimaeric animals1 and to form teratocarcinomas2. Until now it has not been possible to establish progressively growing cultures of these cells in vitro, and cell lines have only been obtained after teratocarcinoma formation in vivo. We report here the establishment in tissue culture of pluripotent cell lines which have been isolated directly from in vitro cultures of mouse blastocysts. These cells are able to differentiate either in vit…

## queryId=1207

问题：The composition of myosin-II isoform switches from the polarizable B isoform to the more homogenous A isoform during hematopoietic differentiation.

原分类：`rerank_reorder_gain`

Gold文档：

- `18909530` Contractile forces sustain and polarize hematopoiesis from stem and progenitor cells.

  > Self-renewal and differentiation of stem cells depend on asymmetric division and polarized motility processes that in other cell types are modulated by nonmuscle myosin-II (MII) forces and matrix mechanics. Here, mass spectrometry-calibrated intracellular flow cytometry of human hematopoiesis reveals MIIB to be a major isoform that is strongly polarized in hematopoietic stem cells and progenitors (HSC/Ps) and thereby downregulated in differentiated cells via asymmetric division. MIIA is constitutive and activated by dephosphorylation during cytokine-triggered differentiation of cells grown on…

| 变体 | 首个覆盖损失 | 首个完全损失 | 最终排名复现 | Rerank同请求效果 |
|---|---|---|---:|---|
| dense | 未观察到Gold损失 | 未观察到Gold损失 | 是 | 不适用 |
| sparse | sparse_raw/SPARSE_RAW_TOPK_MISS | sparse_raw/SPARSE_RAW_TOPK_MISS | 是 | 不适用 |
| hybrid_rrf | 未观察到Gold损失 | 未观察到Gold损失 | 是 | 不适用 |
| hybrid_rrf_rerank | 未观察到Gold损失 | 未观察到Gold损失 | 是 | RERANK_ORDER_GAIN (MRR Δ=0.888889) |

重点失败变体：`hybrid_rrf_rerank`。首个内部失效结论：未观察到Gold损失。该结论级别为阶段轨迹派生，不外推为模型根因。

重点变体Gold逐阶段路径：

| 阶段 | Gold仍存在 | Gold已缺失 | Gold最佳名次 |
|---|---|---|---:|
| raw_union | 18909530 |  | - |
| fusion | 18909530 |  | 9 |
| candidate_filter | 18909530 |  | 9 |
| rerank_input | 18909530 |  | 9 |
| rerank_output | 18909530 |  | 1 |
| context_budget | 18909530 |  | 1 |

`sparse`在`sparse_raw/SPARSE_RAW_TOPK_MISS`阶段排名靠前的非Gold文档：

- sourceStage=sparse_raw rank=1 `5775033` Pyruvate dehydrogenase activity and acetyl group accumulation during exercise after different diets.；dense=null，sparse=0.3740622，fusion=null，rerank=null

  > Pyruvate dehydrogenase activity (PDHa) and acetyl group accumulation were examined in human skeletal muscle at rest and during exercise after different diets. Five males cycled at 75% of maximal O2 uptake (VO2 max) to exhaustion after consuming a low-carbohydrate diet (LCD) for 3 days and again 1-2 wk later for the same duration after consuming a high-carbohydrate diet (HCD) for 3 days. Resting PDHa was lower after a LCD (0.20 +/- 0.04 vs. 0.69 +/- 0.05 mmol.min-1.kg wet wt-1; P < 0.05) and coincided with a greater intramuscular acetyl-CoA-to-CoASH ratio, acetyl-CoA content, and acetylcarnitin…

- sourceStage=sparse_raw rank=2 `44562221` Tissue-specific alterations in the glucocorticoid sensitivity of immune cells following repeated social defeat in mice；dense=null，sparse=0.36468017，fusion=null，rerank=null

  > Endogenous glucocorticoids (GC) play an important role in the termination of the inflammatory response following infection and tissue injury. However, recent findings indicate that stress can impair the anti-inflammatory capacities of these hormones. Lipopolysaccharide (LPS)-stimulated splenocytes of mice that were repeatedly subjected to social disruption (SDR) stress were less sensitive to the immunosuppressive effects of corticosterone (CORT) as demonstrated by an increased production of pro-inflammatory cytokines and enhanced cell survival. Myeloid cells expressing the marker CD11b were sh…

- sourceStage=sparse_raw rank=3 `10607877` Mechanical modulation of receptor-ligand interactions at cell-cell interfaces.；dense=null，sparse=0.3598556，fusion=null，rerank=null

  > Cell surface receptors have been extensively studied because they initiate and regulate signal transduction cascades leading to a variety of functional cellular outcomes. An important class of immune receptors (e.g., T-cell antigen receptors) whose ligands are anchored to the surfaces of other cells remain poorly understood. The mechanism by which ligand binding initiates receptor phosphorylation, a process termed "receptor triggering", remains controversial. Recently, direct measurements of the (two-dimensional) receptor-ligand complex lifetimes at cell-cell interface were found to be smaller…

## queryId=956

问题：Pleiotropic coupling of GLP-1R to intracellular effectors promotes distinct profiles of cellular signaling.

原分类：`rerank_reorder_harm`

Gold文档：

- `12956194` The Extracellular Surface of the GLP-1 Receptor Is a Molecular Trigger for Biased Agonism

  > Ligand-directed signal bias offers opportunities for sculpting molecular events, with the promise of better, safer therapeutics. Critical to the exploitation of signal bias is an understanding of the molecular events coupling ligand binding to intracellular signaling. Activation of class B G protein-coupled receptors is driven by interaction of the peptide N terminus with the receptor core. To understand how this drives signaling, we have used advanced analytical methods that enable separation of effects on pathway-specific signaling from those that modify agonist affinity and mapped the funct…

| 变体 | 首个覆盖损失 | 首个完全损失 | 最终排名复现 | Rerank同请求效果 |
|---|---|---|---:|---|
| dense | 未观察到Gold损失 | 未观察到Gold损失 | 是 | 不适用 |
| sparse | 未观察到Gold损失 | 未观察到Gold损失 | 是 | 不适用 |
| hybrid_rrf | 未观察到Gold损失 | 未观察到Gold损失 | 是 | 不适用 |
| hybrid_rrf_rerank | 未观察到Gold损失 | 未观察到Gold损失 | 是 | RERANK_ORDER_HARM (MRR Δ=-0.800000) |

重点失败变体：`hybrid_rrf_rerank`。首个内部失效结论：未观察到Gold损失。该结论级别为阶段轨迹派生，不外推为模型根因。

重点变体Gold逐阶段路径：

| 阶段 | Gold仍存在 | Gold已缺失 | Gold最佳名次 |
|---|---|---|---:|
| raw_union | 12956194 |  | - |
| fusion | 12956194 |  | 1 |
| candidate_filter | 12956194 |  | 1 |
| rerank_input | 12956194 |  | 1 |
| rerank_output | 12956194 |  | 5 |
| context_budget | 12956194 |  | 5 |

## queryId=212

问题：CR is associated with higher methylation age.

原分类：`rerank_reorder_harm`

Gold文档：

- `22038539` Caloric restriction delays age-related methylation drift

  > In mammals, caloric restriction consistently results in extended lifespan. Epigenetic information encoded by DNA methylation is tightly regulated, but shows a striking drift associated with age that includes both gains and losses of DNA methylation at various sites. Here, we report that epigenetic drift is conserved across species and the rate of drift correlates with lifespan when comparing mice, rhesus monkeys, and humans. Twenty-two to 30-year-old rhesus monkeys exposed to 30% caloric restriction since 7-14 years of age showed attenuation of age-related methylation drift compared to ad libi…

| 变体 | 首个覆盖损失 | 首个完全损失 | 最终排名复现 | Rerank同请求效果 |
|---|---|---|---:|---|
| dense | 未观察到Gold损失 | 未观察到Gold损失 | 是 | 不适用 |
| sparse | 未观察到Gold损失 | 未观察到Gold损失 | 是 | 不适用 |
| hybrid_rrf | 未观察到Gold损失 | 未观察到Gold损失 | 是 | 不适用 |
| hybrid_rrf_rerank | 未观察到Gold损失 | 未观察到Gold损失 | 是 | RERANK_ORDER_HARM (MRR Δ=-0.833333) |

重点失败变体：`hybrid_rrf_rerank`。首个内部失效结论：未观察到Gold损失。该结论级别为阶段轨迹派生，不外推为模型根因。

重点变体Gold逐阶段路径：

| 阶段 | Gold仍存在 | Gold已缺失 | Gold最佳名次 |
|---|---|---|---:|
| raw_union | 22038539 |  | - |
| fusion | 22038539 |  | 1 |
| candidate_filter | 22038539 |  | 1 |
| rerank_input | 22038539 |  | 1 |
| rerank_output | 22038539 |  | 6 |
| context_budget | 22038539 |  | 6 |

## queryId=36

问题：A deficiency of vitamin B12 increases blood levels of homocysteine.

原分类：`rerank_reorder_harm`

Gold文档：

- `11705328` Randomized trial of folic acid supplementation and serum homocysteine levels.

  > BACKGROUND Lowering serum homocysteine levels with folic acid is expected to reduce mortality from ischemic heart disease. Homocysteine reduction is known to be maximal at a folic acid dosage of 1 mg/d, but the effect of lower doses (relevant to food fortification) is unclear. METHODS We randomized 151 patients with ischemic heart disease to 1 of 5 dosages of folic acid (0.2, 0.4, 0.6, 0.8, and 1.0 mg/d) or placebo. Fasting blood samples for serum homocysteine and serum folate analysis were taken initially, after 3 months of supplementation, and 3 months after folic acid use was discontinued.…

- `5152028` Folic acid improves endothelial function in coronary artery disease via mechanisms largely independent of homocysteine lowering.

  > BACKGROUND Homocysteine is a risk factor for coronary artery disease (CAD), although a causal relation remains to be proven. The importance of determining direct causality rests in the fact that plasma homocysteine can be safely and inexpensively reduced by 25% with folic acid. This reduction is maximally achieved by doses of 0.4 mg/d. High-dose folic acid (5 mg/d) improves endothelial function in CAD, although the mechanism is controversial. It has been proposed that improvement occurs through reduction in total (tHcy) or free (non-protein bound) homocysteine (fHcy). We investigated the effec…

| 变体 | 首个覆盖损失 | 首个完全损失 | 最终排名复现 | Rerank同请求效果 |
|---|---|---|---:|---|
| dense | fusion/FUSION_THRESHOLD_OR_TOPK_LOSS | 未观察到Gold损失 | 是 | 不适用 |
| sparse | sparse_raw/SPARSE_RAW_TOPK_MISS | 未观察到Gold损失 | 是 | 不适用 |
| hybrid_rrf | fusion/FUSION_THRESHOLD_OR_TOPK_LOSS | 未观察到Gold损失 | 是 | 不适用 |
| hybrid_rrf_rerank | fusion/FUSION_THRESHOLD_OR_TOPK_LOSS | 未观察到Gold损失 | 是 | RERANK_ORDER_HARM (MRR Δ=-0.750000) |

重点失败变体：`hybrid_rrf_rerank`。首个内部失效结论：fusion/FUSION_THRESHOLD_OR_TOPK_LOSS。该结论级别为阶段轨迹派生，不外推为模型根因。

重点变体Gold逐阶段路径：

| 阶段 | Gold仍存在 | Gold已缺失 | Gold最佳名次 |
|---|---|---|---:|
| raw_union | 11705328, 5152028 |  | - |
| fusion | 11705328 | 5152028 | 1 |
| candidate_filter | 11705328 | 5152028 | 1 |
| rerank_input | 11705328 | 5152028 | 1 |
| rerank_output | 11705328 | 5152028 | 4 |
| context_budget | 11705328 | 5152028 | 4 |

`dense`在`fusion/FUSION_THRESHOLD_OR_TOPK_LOSS`阶段排名靠前的非Gold文档：

- sourceStage=fusion rank=1 `33409100` Effect of homocysteine lowering on mortality and vascular disease in advanced chronic kidney disease and end-stage renal disease: a randomized controlled trial.；dense=0.8622913，sparse=null，fusion=0.9311456499999999，rerank=null

  > CONTEXT High plasma homocysteine levels are a risk factor for mortality and vascular disease in observational studies of patients with chronic kidney disease. Folic acid and B vitamins decrease homocysteine levels in this population but whether they lower mortality is unknown. OBJECTIVE To determine whether high doses of folic acid and B vitamins administered daily reduce mortality in patients with chronic kidney disease. DESIGN, SETTING, AND PARTICIPANTS Double-blind randomized controlled trial (2001-2006) in 36 US Department of Veterans Affairs medical centers. Median follow-up was 3.2 years…

- sourceStage=fusion rank=2 `3215494` Hyperhomocysteinemia and atherosclerotic vascular disease: pathophysiology, screening, and treatment. off.；dense=0.8586432，sparse=null，fusion=0.9293216，rerank=null

  > Hyperhomocysteinemia has recently been identified as an important risk factor for atherosclerotic vascular disease. This article reviews homocysteine metabolism, causes of hyperhomocysteinemia, the pathophysiological findings of this disorder, and epidemiological studies of homocysteine and vascular disease. Screening for hyperhomocysteinemia should be considered for patients at high risk for vascular disease or abnormalities of homocysteine metabolism. For primary prevention of vascular disease, treatment of patients with homocysteine levels of 14 micromol/L or higher should be considered. Fo…

- sourceStage=fusion rank=3 `37424881` The effect of folate fortification on folic acid-based homocysteine-lowering intervention and stroke risk: a meta-analysis.；dense=0.85579365，sparse=null，fusion=0.9278968249999999，rerank=null

  > OBJECTIVE Folate and vitamin B12 are two vital regulators in the metabolic process of homocysteine, which is a risk factor of atherothrombotic events. Low folate intake or low plasma folate concentration is associated with increased stroke risk. Previous randomized controlled trials presented discordant findings in the effect of folic acid supplementation-based homocysteine lowering on stroke risk. The aim of the present review was to perform a meta-analysis of relevant randomized controlled trials to check the how different folate fortification status might affect the effects of folic acid su…

`sparse`在`sparse_raw/SPARSE_RAW_TOPK_MISS`阶段排名靠前的非Gold文档：

- sourceStage=sparse_raw rank=2 `36271512` T-cell activation.；dense=null，sparse=0.369547，fusion=null，rerank=null

  > INTRODUCTION • • CELLULAR AND MOLECULAR REQUIREMENTS FOR T-CELL ACTIVATION . The T-Cell Antigen Receptor Complex . . . .. . . . ..... . . . . . . . . . . . . . . . . ...... . . . T-Cell Activation by Antibodies and Leetins . . . . . . . . . . .. . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . Other Cell Surface Structures (Accessory Molecules) Involved in Antigen Recognition and Activation . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . .…

- sourceStage=sparse_raw rank=3 `21868715` A subset of 26S proteasomes is activated at critically low ATP concentrations and contributes to myocardial injury during cold ischemia.；dense=null，sparse=0.35289693，fusion=null，rerank=null

  > Molecular mechanisms leading to myocardial injury during warm or cold ischemia are insufficiently understood. Although proteasomes are thought to contribute to myocardial ischemia-reperfusion injury, their roles during the ischemic period remain elusive. Because donor hearts are commonly exposed to prolonged global cold ischemia prior to cardiac transplantation, we evaluated the role and regulation of the proteasome during cold ischemic storage of rat hearts in context of the myocardial ATP content. When measured at the actual tissue ATP concentration, cardiac proteasome peptidase activity inc…

- sourceStage=sparse_raw rank=4 `42441846` Gene--nutrition interactions in coronary artery disease: correlation between the MTHFR C677T polymorphism and folate and homocysteine status in a Korean population.；dense=null，sparse=0.35252392，fusion=null，rerank=null

  > INTRODUCTION Elevated plasma total homocysteine is a major risk for coronary artery disease (CAD). Methyltetrahydrofolate reductase (MTHFR) is a main regulatory enzyme in homocysteine metabolism; a common C677T mutation in the MTHFR gene results in decreased enzyme activity, and contributes to increased homocysteine levels and decreased folate levels. We investigated the frequency of MTHFR C677T alleles in a Korean population, determined the genotype-specific threshold levels of folate or vitamin B12, and investigated the relationship between the TT genotype and the risk of CAD. MATERIALS AND…

`hybrid_rrf`在`fusion/FUSION_THRESHOLD_OR_TOPK_LOSS`阶段排名靠前的非Gold文档：

- sourceStage=fusion rank=2 `42441846` Gene--nutrition interactions in coronary artery disease: correlation between the MTHFR C677T polymorphism and folate and homocysteine status in a Korean population.；dense=0.84965116，sparse=0.35252392，fusion=0.953125，rerank=null

  > INTRODUCTION Elevated plasma total homocysteine is a major risk for coronary artery disease (CAD). Methyltetrahydrofolate reductase (MTHFR) is a main regulatory enzyme in homocysteine metabolism; a common C677T mutation in the MTHFR gene results in decreased enzyme activity, and contributes to increased homocysteine levels and decreased folate levels. We investigated the frequency of MTHFR C677T alleles in a Korean population, determined the genotype-specific threshold levels of folate or vitamin B12, and investigated the relationship between the TT genotype and the risk of CAD. MATERIALS AND…

- sourceStage=fusion rank=3 `33409100` Effect of homocysteine lowering on mortality and vascular disease in advanced chronic kidney disease and end-stage renal disease: a randomized controlled trial.；dense=0.8622913，sparse=0.30781606，fusion=0.88125，rerank=null

  > CONTEXT High plasma homocysteine levels are a risk factor for mortality and vascular disease in observational studies of patients with chronic kidney disease. Folic acid and B vitamins decrease homocysteine levels in this population but whether they lower mortality is unknown. OBJECTIVE To determine whether high doses of folic acid and B vitamins administered daily reduce mortality in patients with chronic kidney disease. DESIGN, SETTING, AND PARTICIPANTS Double-blind randomized controlled trial (2001-2006) in 36 US Department of Veterans Affairs medical centers. Median follow-up was 3.2 years…

- sourceStage=fusion rank=4 `3215494` Hyperhomocysteinemia and atherosclerotic vascular disease: pathophysiology, screening, and treatment. off.；dense=0.8586432，sparse=0.29530084，fusion=0.8346321130844508，rerank=null

  > Hyperhomocysteinemia has recently been identified as an important risk factor for atherosclerotic vascular disease. This article reviews homocysteine metabolism, causes of hyperhomocysteinemia, the pathophysiological findings of this disorder, and epidemiological studies of homocysteine and vascular disease. Screening for hyperhomocysteinemia should be considered for patients at high risk for vascular disease or abnormalities of homocysteine metabolism. For primary prevention of vascular disease, treatment of patients with homocysteine levels of 14 micromol/L or higher should be considered. Fo…

`hybrid_rrf_rerank`在`fusion/FUSION_THRESHOLD_OR_TOPK_LOSS`阶段排名靠前的非Gold文档：

- sourceStage=fusion rank=2 `42441846` Gene--nutrition interactions in coronary artery disease: correlation between the MTHFR C677T polymorphism and folate and homocysteine status in a Korean population.；dense=0.84965116，sparse=0.35252392，fusion=0.953125，rerank=null

  > INTRODUCTION Elevated plasma total homocysteine is a major risk for coronary artery disease (CAD). Methyltetrahydrofolate reductase (MTHFR) is a main regulatory enzyme in homocysteine metabolism; a common C677T mutation in the MTHFR gene results in decreased enzyme activity, and contributes to increased homocysteine levels and decreased folate levels. We investigated the frequency of MTHFR C677T alleles in a Korean population, determined the genotype-specific threshold levels of folate or vitamin B12, and investigated the relationship between the TT genotype and the risk of CAD. MATERIALS AND…

- sourceStage=fusion rank=3 `33409100` Effect of homocysteine lowering on mortality and vascular disease in advanced chronic kidney disease and end-stage renal disease: a randomized controlled trial.；dense=0.8622913，sparse=0.30781606，fusion=0.88125，rerank=null

  > CONTEXT High plasma homocysteine levels are a risk factor for mortality and vascular disease in observational studies of patients with chronic kidney disease. Folic acid and B vitamins decrease homocysteine levels in this population but whether they lower mortality is unknown. OBJECTIVE To determine whether high doses of folic acid and B vitamins administered daily reduce mortality in patients with chronic kidney disease. DESIGN, SETTING, AND PARTICIPANTS Double-blind randomized controlled trial (2001-2006) in 36 US Department of Veterans Affairs medical centers. Median follow-up was 3.2 years…

- sourceStage=fusion rank=4 `3215494` Hyperhomocysteinemia and atherosclerotic vascular disease: pathophysiology, screening, and treatment. off.；dense=0.8586432，sparse=0.29530084，fusion=0.8346321130844508，rerank=null

  > Hyperhomocysteinemia has recently been identified as an important risk factor for atherosclerotic vascular disease. This article reviews homocysteine metabolism, causes of hyperhomocysteinemia, the pathophysiological findings of this disorder, and epidemiological studies of homocysteine and vascular disease. Screening for hyperhomocysteinemia should be considered for patients at high risk for vascular disease or abnormalities of homocysteine metabolism. For primary prevention of vascular disease, treatment of patients with homocysteine levels of 14 micromol/L or higher should be considered. Fo…

## 输入SHA-256

- failureReport: `61f6f34aadccf7c64311a7be6db2653793e9dc5c4410742a65b97b7ccb5536f0`
- diagnostics: `f74d1c923e4ea457ae290f0816d73b73267b5399141775d1482e46ef10554f40`
- diagnosticManifest: `55af55561896d6da69b2c6bd485bc8bc1bb969828719e3e1848548d14e7f0171`
- qrels: `5602d9f31c96d309a906692e1b722a9acfc4138c5d52e06d47bbb89a9c4ab7c3`
- documents: `0287493f09e9cb8d13d44bd46c01540229a7bad18d8c9da344f60429a89d6680`
- documentMap: `1718e1ed99f145f839156afccca3b13de7608a154232e5d829f20a36cb124c84`
