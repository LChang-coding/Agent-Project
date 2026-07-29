# SciFact RAG内部阶段失败证据报告

真实诊断查询：23；请求记录：92；旧run最终排名精确复现：92/92。

## 证据边界

- firstObserved字段表示阶段轨迹中首个可观测损失，不等同于模型或索引的不可反驳根因。
- fusion实现将score threshold与TopK合并，轨迹只能定位到FUSION_THRESHOLD_OR_TOPK_LOSS。
- context outcome可直接证明淘汰分支，但未采集具体Token差额与扩展上下文组成。
- 当前报告只支持每条请求恰好一个binding/profile；多binding局部排名不会被混成全局排名。
- Hybrid raw union只表达Dense/Sparse两路覆盖并集，不提供跨分支全局名次语义。
- 每个变体记录并校验其binding/profile单作用域；四个消融target的binding/profile本来不同，跨变体指纹比较会归一化这两个target局部ID。
- 跨变体可比性校验共享知识库/文档/版本/generation/chunk、outcome与分数容差；未采集完整模型/index冻结指纹。

## 内部失效总账

92条变体轨迹的首个完全损失分布：

| 分类码 | 变体轨迹数 |
|---|---:|
| DENSE_RAW_TOPK_MISS | 1 |
| FUSION_THRESHOLD_OR_TOPK_LOSS | 34 |
| NONE | 56 |
| SPARSE_RAW_TOPK_MISS | 1 |

同一次Hybrid+Rerank请求内，Rerank输入→输出的排序效果：

| 分类 | 查询数 |
|---|---:|
| RERANK_NEUTRAL | 9 |
| RERANK_ORDER_GAIN | 9 |
| RERANK_ORDER_HARM | 5 |

## queryId=324

问题：Deleting Raptor reduces G-CSF levels.

原分类：`dense_miss_hybrid_hit`, `sparse_only_success`

Gold文档：

- `2014909` Oncogenic mTOR signaling recruits myeloid-derived suppressor cells to promote tumor initiation

  > Myeloid-derived suppressor cells (MDSCs) play critical roles in primary and metastatic cancer progression. MDSC regulation is widely variable even among patients harbouring the same type of malignancy, and the mechanisms governing such heterogeneity are largely unknown. Here, integrating human tumour genomics and syngeneic mammary tumour models, we demonstrate that mTOR signalling in cancer cells dictates a mammary tumour's ability to stimulate MDSC accumulation through regulating G-CSF. Inhibiting this pathway or its activators (for example, FGFR) impairs tumour progression, which is partiall…

| 变体 | 首个覆盖损失 | 首个完全损失 | 最终排名复现 | Rerank同请求效果 |
|---|---|---|---:|---|
| dense | fusion/FUSION_THRESHOLD_OR_TOPK_LOSS | fusion/FUSION_THRESHOLD_OR_TOPK_LOSS | 是 | 不适用 |
| sparse | 未观察到Gold损失 | 未观察到Gold损失 | 是 | 不适用 |
| hybrid_rrf | 未观察到Gold损失 | 未观察到Gold损失 | 是 | 不适用 |
| hybrid_rrf_rerank | 未观察到Gold损失 | 未观察到Gold损失 | 是 | RERANK_ORDER_GAIN (MRR Δ=0.666667) |

重点失败变体：`dense`。首个内部失效结论：fusion/FUSION_THRESHOLD_OR_TOPK_LOSS。该结论级别为阶段轨迹派生，不外推为模型根因。

重点变体Gold逐阶段路径：

| 阶段 | Gold仍存在 | Gold已缺失 | Gold最佳名次 |
|---|---|---|---:|
| dense_raw | 2014909 |  | 16 |
| fusion |  | 2014909 | - |
| candidate_filter |  | 2014909 | - |
| pre_assembly |  | 2014909 | - |
| context_budget |  | 2014909 | - |

`dense`在`fusion/FUSION_THRESHOLD_OR_TOPK_LOSS`阶段排名靠前的非Gold文档：

- sourceStage=fusion rank=1 `3553087` Mitochondrial iron chelation ameliorates cigarette-smoke induced bronchitis and emphysema in mice；dense=0.79069763，sparse=null，fusion=0.895348815，rerank=null

  > Chronic obstructive pulmonary disease (COPD) is linked to both cigarette smoking and genetic determinants. We have previously identified iron-responsive element-binding protein 2 (IRP2) as an important COPD susceptibility gene and have shown that IRP2 protein is increased in the lungs of individuals with COPD. Here we demonstrate that mice deficient in Irp2 were protected from cigarette smoke (CS)-induced experimental COPD. By integrating RNA immunoprecipitation followed by sequencing (RIP-seq), RNA sequencing (RNA-seq), and gene expression and functional enrichment clustering analysis, we ide…

- sourceStage=fusion rank=2 `12827098` Tissue-resident macrophages self-maintain locally throughout adult life with minimal contribution from circulating monocytes.；dense=0.7906954，sparse=null，fusion=0.8953477000000001，rerank=null

  > Despite accumulating evidence suggesting local self-maintenance of tissue macrophages in the steady state, the dogma remains that tissue macrophages derive from monocytes. Using parabiosis and fate-mapping approaches, we confirmed that monocytes do not show significant contribution to tissue macrophages in the steady state. Similarly, we found that after depletion of lung macrophages, the majority of repopulation occurred by stochastic cellular proliferation in situ in a macrophage colony-stimulating factor (M-Csf)- and granulocyte macrophage (GM)-CSF-dependent manner but independently of inte…

- sourceStage=fusion rank=3 `26851674` Dissection of signaling cascades through gp130 in vivo: reciprocal roles for STAT3- and SHP2-mediated signals in immune responses.；dense=0.79059124，sparse=null，fusion=0.89529562，rerank=null

  > We generated a series of knockin mouse lines, in which the cytokine receptor gp130-dependent STAT3 and/or SHP2 signals were disrupted, by replacing the mouse gp130 gene with human gp130 mutant cDNAs. The SHP2 signal-deficient mice (gp130F759/F759 were born normal but displayed splenomegaly and lymphadenopathy and an enhanced acute phase reaction. In contrast, the STAT3 signal-deficient mice (gp130FXQ/FXXQ) died perinatally, like the gp130-deficient mice (gp130D/D). The gp130F759/F759 mice showed prolonged gp130-induced STAT3 activation, indicating a negative regulatory role for SHP2. Th1-type…

## queryId=1175

问题：The PPR MDA5 has two N-terminal CARD domains.

原分类：`dense_miss_hybrid_hit`, `rerank_reorder_gain`

Gold文档：

- `31272411` Immune signaling by RIG-I-like receptors.

  > The RIG-I-like receptors (RLRs) RIG-I, MDA5, and LGP2 play a major role in pathogen sensing of RNA virus infection to initiate and modulate antiviral immunity. The RLRs detect viral RNA ligands or processed self RNA in the cytoplasm to trigger innate immunity and inflammation and to impart gene expression that serves to control infection. Importantly, RLRs cooperate in signaling crosstalk networks with Toll-like receptors and other factors to impart innate immunity and to modulate the adaptive immune response. RLR regulation occurs at a variety of levels ranging from autoregulation to ligand a…

| 变体 | 首个覆盖损失 | 首个完全损失 | 最终排名复现 | Rerank同请求效果 |
|---|---|---|---:|---|
| dense | fusion/FUSION_THRESHOLD_OR_TOPK_LOSS | fusion/FUSION_THRESHOLD_OR_TOPK_LOSS | 是 | 不适用 |
| sparse | fusion/FUSION_THRESHOLD_OR_TOPK_LOSS | fusion/FUSION_THRESHOLD_OR_TOPK_LOSS | 是 | 不适用 |
| hybrid_rrf | 未观察到Gold损失 | 未观察到Gold损失 | 是 | 不适用 |
| hybrid_rrf_rerank | 未观察到Gold损失 | 未观察到Gold损失 | 是 | RERANK_ORDER_GAIN (MRR Δ=0.857143) |

重点失败变体：`hybrid_rrf_rerank`。首个内部失效结论：未观察到Gold损失。该结论级别为阶段轨迹派生，不外推为模型根因。

重点变体Gold逐阶段路径：

| 阶段 | Gold仍存在 | Gold已缺失 | Gold最佳名次 |
|---|---|---|---:|
| raw_union | 31272411 |  | - |
| fusion | 31272411 |  | 7 |
| candidate_filter | 31272411 |  | 7 |
| rerank_input | 31272411 |  | 7 |
| rerank_output | 31272411 |  | 1 |
| context_budget | 31272411 |  | 1 |

`dense`在`fusion/FUSION_THRESHOLD_OR_TOPK_LOSS`阶段排名靠前的非Gold文档：

- sourceStage=fusion rank=1 `4423559` Planar cell polarity signalling couples cell division and morphogenesis during neurulation；dense=0.7913904，sparse=null，fusion=0.8956952，rerank=null

  > Environmental and genetic aberrations lead to neural tube closure defects (NTDs) in 1 out of every 1,000 births. Mouse and frog models for these birth defects have indicated that Van Gogh-like 2 (Vangl2, also known as Strabismus) and other components of planar cell polarity (PCP) signalling might control neurulation by promoting the convergence of neural progenitors to the midline. Here we show a novel role for PCP signalling during neurulation in zebrafish. We demonstrate that non-canonical Wnt/PCP signalling polarizes neural progenitors along the anteroposterior axis. This polarity is transi…

- sourceStage=fusion rank=2 `15319019` N348I in the Connection Domain of HIV-1 Reverse Transcriptase Confers Zidovudine and Nevirapine Resistance；dense=0.78464067，sparse=null，fusion=0.892320335，rerank=null

  > Background The catalytically active 66-kDa subunit of the human immunodeficiency virus type 1 (HIV-1) reverse transcriptase (RT) consists of DNA polymerase, connection, and ribonuclease H (RNase H) domains. Almost all known RT inhibitor resistance mutations identified to date map to the polymerase domain of the enzyme. However, the connection and RNase H domains are not routinely analysed in clinical samples and none of the genotyping assays available for patient management sequence the entire RT coding region. The British Columbia Centre for Excellence in HIV/AIDS (the Centre) genotypes clini…

- sourceStage=fusion rank=3 `11603066` Using Structural Information to Change the Phosphotransfer Specificity of a Two-Component Chemotaxis Signalling Complex；dense=0.78186536，sparse=null，fusion=0.8909326799999999，rerank=null

  > Two-component signal transduction pathways comprising histidine protein kinases (HPKs) and their response regulators (RRs) are widely used to control bacterial responses to environmental challenges. Some bacteria have over 150 different two-component pathways, and the specificity of the phosphotransfer reactions within these systems is tightly controlled to prevent unwanted crosstalk. One of the best understood two-component signalling pathways is the chemotaxis pathway. Here, we present the 1.40 A crystal structure of the histidine-containing phosphotransfer domain of the chemotaxis HPK, CheA…

`sparse`在`fusion/FUSION_THRESHOLD_OR_TOPK_LOSS`阶段排名靠前的非Gold文档：

- sourceStage=fusion rank=1 `8646760` Identification and Functional Characterization of N-Terminally Acetylated Proteins in Drosophila melanogaster；dense=null，sparse=0.27589336，fusion=0.2162354383598328，rerank=null

  > Protein modifications play a major role for most biological processes in living organisms. Amino-terminal acetylation of proteins is a common modification found throughout the tree of life: the N-terminus of a nascent polypeptide chain becomes co-translationally acetylated, often after the removal of the initiating methionine residue. While the enzymes and protein complexes involved in these processes have been extensively studied, only little is known about the biological function of such N-terminal modification events. To identify common principles of N-terminal acetylation, we analyzed the…

- sourceStage=fusion rank=3 `11603066` Using Structural Information to Change the Phosphotransfer Specificity of a Two-Component Chemotaxis Signalling Complex；dense=null，sparse=0.26116493，fusion=0.20708229652405574，rerank=null

  > Two-component signal transduction pathways comprising histidine protein kinases (HPKs) and their response regulators (RRs) are widely used to control bacterial responses to environmental challenges. Some bacteria have over 150 different two-component pathways, and the specificity of the phosphotransfer reactions within these systems is tightly controlled to prevent unwanted crosstalk. One of the best understood two-component signalling pathways is the chemotaxis pathway. Here, we present the 1.40 A crystal structure of the histidine-containing phosphotransfer domain of the chemotaxis HPK, CheA…

- sourceStage=fusion rank=4 `39381118` At the gates of death.；dense=null，sparse=0.25063586，fusion=0.20040674349446527，rerank=null

  > Apoptosis that proceeds via the mitochondrial pathway involves mitochondrial outer membrane permeabilization (MOMP), responsible for the release of cytochrome c and other proteins of the mitochondrial intermembrane space. This essential step is controlled and mediated by proteins of the Bcl-2 family. The proapoptotic proteins Bax and Bak are required for MOMP, while the antiapoptotic Bcl-2 proteins, including Bcl-2, Bcl-xL, Mcl-1, and others, prevent MOMP. Different proapoptotic BH3-only proteins act to interfere with the function of the antiapoptotic Bcl-2 members and/or activate Bax and Bak.…

## queryId=1049

问题：Ribosomopathies have a low degree of cell and tissue specific pathology.

原分类：`sparse_miss_hybrid_hit`, `dense_only_success`

Gold文档：

- `12486491` Ribosome-Mediated Specificity in Hox mRNA Translation and Vertebrate Tissue Patterning

  > Historically, the ribosome has been viewed as a complex ribozyme with constitutive rather than regulatory capacity in mRNA translation. Here we identify mutations of the Ribosomal Protein L38 (Rpl38) gene in mice exhibiting surprising tissue-specific patterning defects, including pronounced homeotic transformations of the axial skeleton. In Rpl38 mutant embryos, global protein synthesis is unchanged; however the translation of a select subset of Homeobox mRNAs is perturbed. Our data reveal that RPL38 facilitates 80S complex formation on these mRNAs as a regulatory component of the ribosome to…

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
| sparse_raw | 12486491 |  | 17 |
| fusion |  | 12486491 | - |
| candidate_filter |  | 12486491 | - |
| pre_assembly |  | 12486491 | - |
| context_budget |  | 12486491 | - |

`sparse`在`fusion/FUSION_THRESHOLD_OR_TOPK_LOSS`阶段排名靠前的非Gold文档：

- sourceStage=fusion rank=1 `45638119` ALDH1 is a marker of normal and malignant human mammary stem cells and a predictor of poor clinical outcome.；dense=null，sparse=0.28897157，fusion=0.22418769872480587，rerank=null

  > Application of stem cell biology to breast cancer research has been limited by the lack of simple methods for identification and isolation of normal and malignant stem cells. Utilizing in vitro and in vivo experimental systems, we show that normal and cancer human mammary epithelial cells with increased aldehyde dehydrogenase activity (ALDH) have stem/progenitor properties. These cells contain the subpopulation of normal breast epithelium with the broadest lineage differentiation potential and greatest growth capacity in a xenotransplant model. In breast carcinomas, high ALDH activity identifi…

- sourceStage=fusion rank=2 `24142891` Tmem27: a cleaved and shed plasma membrane protein that stimulates pancreatic beta cell proliferation.；dense=null，sparse=0.28595415，fusion=0.22236729824309834，rerank=null

  > The signals and molecular mechanisms that regulate the replication of terminally differentiated beta cells are unknown. Here, we report the identification and characterization of transmembrane protein 27 (Tmem27, collectrin) in pancreatic beta cells. Expression of Tmem27 is reduced in Tcf1(-/-) mice and is increased in islets of mouse models with hypertrophy of the endocrine pancreas. Tmem27 forms dimers and its extracellular domain is glycosylated, cleaved and shed from the plasma membrane of beta cells. This cleavage process is beta cell specific and does not occur in other cell types. Overe…

- sourceStage=fusion rank=3 `7521113` Fate mapping reveals origins and dynamics of monocytes and tissue macrophages under homeostasis.；dense=null，sparse=0.28139037，fusion=0.21959769371452353，rerank=null

  > Mononuclear phagocytes, including monocytes, macrophages, and dendritic cells, contribute to tissue integrity as well as to innate and adaptive immune defense. Emerging evidence for labor division indicates that manipulation of these cells could bear therapeutic potential. However, specific ontogenies of individual populations and the overall functional organization of this cellular network are not well defined. Here we report a fate-mapping study of the murine monocyte and macrophage compartment taking advantage of constitutive and conditional CX(3)CR1 promoter-driven Cre recombinase expressi…

## queryId=1207

问题：The composition of myosin-II isoform switches from the polarizable B isoform to the more homogenous A isoform during hematopoietic differentiation.

原分类：`sparse_miss_hybrid_hit`, `dense_only_success`

Gold文档：

- `18909530` Contractile forces sustain and polarize hematopoiesis from stem and progenitor cells.

  > Self-renewal and differentiation of stem cells depend on asymmetric division and polarized motility processes that in other cell types are modulated by nonmuscle myosin-II (MII) forces and matrix mechanics. Here, mass spectrometry-calibrated intracellular flow cytometry of human hematopoiesis reveals MIIB to be a major isoform that is strongly polarized in hematopoietic stem cells and progenitors (HSC/Ps) and thereby downregulated in differentiated cells via asymmetric division. MIIA is constitutive and activated by dephosphorylation during cytokine-triggered differentiation of cells grown on…

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
| sparse_raw | 18909530 |  | 13 |
| fusion |  | 18909530 | - |
| candidate_filter |  | 18909530 | - |
| pre_assembly |  | 18909530 | - |
| context_budget |  | 18909530 | - |

`sparse`在`fusion/FUSION_THRESHOLD_OR_TOPK_LOSS`阶段排名靠前的非Gold文档：

- sourceStage=fusion rank=1 `6173523` A culture-independent sequence-based metagenomics approach to the investigation of an outbreak of Shiga-toxigenic Escherichia coli O104:H4.；dense=null，sparse=0.30266076，fusion=0.23234042913828154，rerank=null

  > IMPORTANCE Identification of the bacterium responsible for an outbreak can aid in disease management. However, traditional culture-based diagnosis can be difficult, particularly if no specific diagnostic test is available for an outbreak strain. OBJECTIVE To explore the potential of metagenomics, which is the direct sequencing of DNA extracted from microbiologically complex samples, as an open-ended clinical discovery platform capable of identifying and characterizing bacterial strains from an outbreak without laboratory culture. DESIGN, SETTING, AND PATIENTS In a retrospective investigation,…

- sourceStage=fusion rank=2 `306006` The stimulatory potency of T cell antigens is influenced by the formation of the immunological synapse.；dense=null，sparse=0.29787195，fusion=0.22950796494215012，rerank=null

  > T cell activation is predicated on the interaction between the T cell receptor and peptide-major histocompatibility (pMHC) ligands. The factors that determine the stimulatory potency of a pMHC molecule remain unclear. We describe results showing that a peptide exhibiting many hallmarks of a weak agonist stimulates T cells to proliferate more than the wild-type agonist ligand. An in silico approach suggested that the inability to form the central supramolecular activation cluster (cSMAC) could underlie the increased proliferation. This conclusion was supported by experiments that showed that en…

- sourceStage=fusion rank=3 `12956194` The Extracellular Surface of the GLP-1 Receptor Is a Molecular Trigger for Biased Agonism；dense=null，sparse=0.29563946，fusion=0.2281803457884804，rerank=null

  > Ligand-directed signal bias offers opportunities for sculpting molecular events, with the promise of better, safer therapeutics. Critical to the exploitation of signal bias is an understanding of the molecular events coupling ligand binding to intracellular signaling. Activation of class B G protein-coupled receptors is driven by interaction of the peptide N terminus with the receptor core. To understand how this drives signaling, we have used advanced analytical methods that enable separation of effects on pathway-specific signaling from those that modify agonist affinity and mapped the funct…

## queryId=1221

问题：The genomic aberrations found in matasteses are very similar to those found in the primary tumor.

原分类：`sparse_miss_hybrid_hit`

Gold文档：

- `19736671` Evolution of metastasis revealed by mutational landscapes of chemically induced skin cancers

  > Human tumors show a high level of genetic heterogeneity, but the processes that influence the timing and route of metastatic dissemination of the subclones are unknown. Here we have used whole-exome sequencing of 103 matched benign, malignant and metastatic skin tumors from genetically heterogeneous mice to demonstrate that most metastases disseminate synchronously from the primary tumor, supporting parallel rather than linear evolution as the predominant model of metastasis. Shared mutations between primary carcinomas and their matched metastases have the distinct A-to-T signature of the init…

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
| sparse_raw | 19736671 |  | 16 |
| fusion |  | 19736671 | - |
| candidate_filter |  | 19736671 | - |
| pre_assembly |  | 19736671 | - |
| context_budget |  | 19736671 | - |

`sparse`在`fusion/FUSION_THRESHOLD_OR_TOPK_LOSS`阶段排名靠前的非Gold文档：

- sourceStage=fusion rank=1 `12789595` Computer assisted learning in undergraduate medical education.；dense=null，sparse=0.27920318，fusion=0.2182633567249262，rerank=null

  > It is becoming “a truth universally acknowledged” that the education of undergraduate medical students will be enhanced through the use of computer assisted learning. Access to the wide range of online options illustrated in the figure must surely make learning more exciting, effective, and likely to be retained. This assumption is potentially but by no means inevitably correct. ### Box 1: Why fund computer assisted learning? Computer assisted learning is inevitable —Individual lecturers and departments are already beginning to introduce a wide range of computer based applications, sometimes i…

- sourceStage=fusion rank=2 `4687948` HMG-CoA reductase inhibitors and the risk of hip fractures in elderly patients.；dense=null，sparse=0.27854618，fusion=0.21786164970591831，rerank=null

  > CONTEXT Recent animal studies have found that 3-hydroxy-3-methylglutaryl coenzyme A (HMG-CoA) lipid-lowering drugs (statins) substantially increase bone formation, but whether statin use in humans results in clinically meaningful bone formation or a reduction in the risk of osteoporotic fractures is not known. OBJECTIVE To determine whether the use of statins is associated with reduced hip fracture risk. DESIGN Case-control study. SETTING AND PATIENTS A total of 6110 New Jersey residents aged 65 years or older and enrolled in Medicare and either Medicaid or the Pharmacy Assistance for the Aged…

- sourceStage=fusion rank=4 `17755060` Control of Nutrient Stress-Induced Metabolic Reprogramming by PKCζ in Tumorigenesis；dense=null，sparse=0.27182597，fusion=0.21372890349141083，rerank=null

  > Tumor cells have high-energetic and anabolic needs and are known to adapt their metabolism to be able to survive and keep proliferating under conditions of nutrient stress. We show that PKCζ deficiency promotes the plasticity necessary for cancer cells to reprogram their metabolism to utilize glutamine through the serine biosynthetic pathway in the absence of glucose. PKCζ represses the expression of two key enzymes of the pathway, PHGDH and PSAT1, and phosphorylates PHGDH at key residues to inhibit its enzymatic activity. Interestingly, the loss of PKCζ in mice results in enhanced intestinal…

## queryId=1194

问题：The arm density of TatAd complexes is due to structural rearrangements within Class1 TatAd complexes such as the 'charge zipper mechanism'.

原分类：`sparse_miss_hybrid_hit`, `dense_only_success`

Gold文档：

- `11419230` Folding and Self-Assembly of the TatA Translocation Pore Based on a Charge Zipper Mechanism

  > We propose a concept for the folding and self-assembly of the pore-forming TatA complex from the Twin-arginine translocase and of other membrane proteins based on electrostatic "charge zippers. " Each subunit of TatA consists of a transmembrane segment, an amphiphilic helix (APH), and a C-terminal densely charged region (DCR). The sequence of charges in the DCR is complementary to the charge pattern on the APH, suggesting that the protein can be "zipped up" by a ladder of seven salt bridges. The length of the resulting hairpin matches the lipid bilayer thickness, hence a transmembrane pore cou…

| 变体 | 首个覆盖损失 | 首个完全损失 | 最终排名复现 | Rerank同请求效果 |
|---|---|---|---:|---|
| dense | 未观察到Gold损失 | 未观察到Gold损失 | 是 | 不适用 |
| sparse | fusion/FUSION_THRESHOLD_OR_TOPK_LOSS | fusion/FUSION_THRESHOLD_OR_TOPK_LOSS | 是 | 不适用 |
| hybrid_rrf | 未观察到Gold损失 | 未观察到Gold损失 | 是 | 不适用 |
| hybrid_rrf_rerank | 未观察到Gold损失 | 未观察到Gold损失 | 是 | RERANK_ORDER_GAIN (MRR Δ=0.666667) |

重点失败变体：`sparse`。首个内部失效结论：fusion/FUSION_THRESHOLD_OR_TOPK_LOSS。该结论级别为阶段轨迹派生，不外推为模型根因。

重点变体Gold逐阶段路径：

| 阶段 | Gold仍存在 | Gold已缺失 | Gold最佳名次 |
|---|---|---|---:|
| sparse_raw | 11419230 |  | 11 |
| fusion |  | 11419230 | - |
| candidate_filter |  | 11419230 | - |
| pre_assembly |  | 11419230 | - |
| context_budget |  | 11419230 | - |

`sparse`在`fusion/FUSION_THRESHOLD_OR_TOPK_LOSS`阶段排名靠前的非Gold文档：

- sourceStage=fusion rank=1 `11335781` Is autoimmunity the Achilles' heel of cancer immunotherapy?；dense=null，sparse=0.28569272，fusion=0.22220917607746896，rerank=null

  > The emergence of immuno-oncology as the first broadly successful strategy for metastatic cancer will require clinicians to integrate this new pillar of medicine with chemotherapy, radiation, and targeted small-molecule compounds. Of equal importance is gaining an understanding of the limitations and toxicities of immunotherapy. Immunotherapy was initially perceived to be a relatively less toxic approach to cancer treatment than other available therapies—and surely it is, when compared to those. However, as the use of immunotherapy becomes more common, especially as first- and second-line treat…

- sourceStage=fusion rank=2 `33499189` Full activation of the T cell receptor requires both clustering and conformational changes at CD3.；dense=null，sparse=0.26618055，fusion=0.2102232181658453，rerank=null

  > T cell receptor (TCR-CD3) triggering involves both receptor clustering and conformational changes at the cytoplasmic tails of the CD3 subunits. The mechanism by which TCRalphabeta ligand binding confers conformational changes to CD3 is unknown. By using well-defined ligands, we showed that induction of the conformational change requires both multivalent engagement and the mobility restriction of the TCR-CD3 imposed by the plasma membrane. The conformational change is elicited by cooperative rearrangements of two TCR-CD3 complexes and does not require accompanying changes in the structure of th…

- sourceStage=fusion rank=4 `8460275` The Utilization of Extracellular Proteins as Nutrients Is Suppressed by mTORC1；dense=null，sparse=0.25004798，fusion=0.2000307060213801，rerank=null

  > Despite being surrounded by diverse nutrients, mammalian cells preferentially metabolize glucose and free amino acids. Recently, Ras-induced macropinocytosis of extracellular proteins was shown to reduce a transformed cell's dependence on extracellular glutamine. Here, we demonstrate that protein macropinocytosis can also serve as an essential amino acid source. Lysosomal degradation of extracellular proteins can sustain cell survival and induce activation of mTORC1 but fails to elicit significant cell accumulation. Unlike its growth-promoting activity under amino-acid-replete conditions, we d…

## queryId=1196

问题：The availability of safe places to study is effective at decreasing homelessness.

原分类：`sparse_miss_hybrid_hit`, `dense_only_success`

Gold文档：

- `25649714` Mental health problems of homeless children and families: longitudinal study.

  > OBJECTIVE To establish the mental health needs of homeless children and families before and after rehousing. DESIGN Cross sectional, longitudinal study. SETTING City of Birmingham. SUBJECTS 58 rehoused families with 103 children aged 2-16 years and 21 comparison families of low socioeconomic status in stable housing, with 54 children. MAIN OUTCOME MEASURES Children's mental health problems and level of communication; mothers' mental health problems and social support one year after rehousing. RESULTS Mental health problems remained significantly higher in rehoused mothers and their children th…

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
| sparse_raw | 25649714 |  | 94 |
| fusion |  | 25649714 | - |
| candidate_filter |  | 25649714 | - |
| pre_assembly |  | 25649714 | - |
| context_budget |  | 25649714 | - |

`sparse`在`fusion/FUSION_THRESHOLD_OR_TOPK_LOSS`阶段排名靠前的非Gold文档：

- sourceStage=fusion rank=1 `39381118` At the gates of death.；dense=null，sparse=0.32245097，fusion=0.24382829860225366，rerank=null

  > Apoptosis that proceeds via the mitochondrial pathway involves mitochondrial outer membrane permeabilization (MOMP), responsible for the release of cytochrome c and other proteins of the mitochondrial intermembrane space. This essential step is controlled and mediated by proteins of the Bcl-2 family. The proapoptotic proteins Bax and Bak are required for MOMP, while the antiapoptotic Bcl-2 proteins, including Bcl-2, Bcl-xL, Mcl-1, and others, prevent MOMP. Different proapoptotic BH3-only proteins act to interfere with the function of the antiapoptotic Bcl-2 members and/or activate Bax and Bak.…

- sourceStage=fusion rank=3 `33499189` Full activation of the T cell receptor requires both clustering and conformational changes at CD3.；dense=null，sparse=0.30503097，fusion=0.23373465995216958，rerank=null

  > T cell receptor (TCR-CD3) triggering involves both receptor clustering and conformational changes at the cytoplasmic tails of the CD3 subunits. The mechanism by which TCRalphabeta ligand binding confers conformational changes to CD3 is unknown. By using well-defined ligands, we showed that induction of the conformational change requires both multivalent engagement and the mobility restriction of the TCR-CD3 imposed by the plasma membrane. The conformational change is elicited by cooperative rearrangements of two TCR-CD3 complexes and does not require accompanying changes in the structure of th…

- sourceStage=fusion rank=4 `12789595` Computer assisted learning in undergraduate medical education.；dense=null，sparse=0.2987476，fusion=0.23002745106131475，rerank=null

  > It is becoming “a truth universally acknowledged” that the education of undergraduate medical students will be enhanced through the use of computer assisted learning. Access to the wide range of online options illustrated in the figure must surely make learning more exciting, effective, and likely to be retained. This assumption is potentially but by no means inevitably correct. ### Box 1: Why fund computer assisted learning? Computer assisted learning is inevitable —Individual lecturers and departments are already beginning to introduce a wide range of computer based applications, sometimes i…

## queryId=1200

问题：The binding orientation of the ML-SA1 activator at hTRPML2 is different from the binding orientation of the ML-SA1 activator at hTRPML1.

原分类：`dense_only_success`

Gold文档：

- `3441524` Human TRPML1 channel structures in open and closed conformations

  > Transient receptor potential mucolipin 1 (TRPML1) is a Ca2+-releasing cation channel that mediates the calcium signalling and homeostasis of lysosomes. Mutations in TRPML1 lead to mucolipidosis type IV, a severe lysosomal storage disorder. Here we report two electron cryo-microscopy structures of full-length human TRPML1: a 3.72-Å apo structure at pH 7.0 in the closed state, and a 3.49-Å agonist-bound structure at pH 6.0 in an open state. Several aromatic and hydrophobic residues in pore helix 1, helices S5 and S6, and helix S6 of a neighbouring subunit, form a hydrophobic cavity to house the…

| 变体 | 首个覆盖损失 | 首个完全损失 | 最终排名复现 | Rerank同请求效果 |
|---|---|---|---:|---|
| dense | 未观察到Gold损失 | 未观察到Gold损失 | 是 | 不适用 |
| sparse | fusion/FUSION_THRESHOLD_OR_TOPK_LOSS | fusion/FUSION_THRESHOLD_OR_TOPK_LOSS | 是 | 不适用 |
| hybrid_rrf | 未观察到Gold损失 | 未观察到Gold损失 | 是 | 不适用 |
| hybrid_rrf_rerank | 未观察到Gold损失 | 未观察到Gold损失 | 是 | RERANK_ORDER_GAIN (MRR Δ=0.666667) |

重点失败变体：`sparse`。首个内部失效结论：fusion/FUSION_THRESHOLD_OR_TOPK_LOSS。该结论级别为阶段轨迹派生，不外推为模型根因。

重点变体Gold逐阶段路径：

| 阶段 | Gold仍存在 | Gold已缺失 | Gold最佳名次 |
|---|---|---|---:|
| sparse_raw | 3441524 |  | 13 |
| fusion |  | 3441524 | - |
| candidate_filter |  | 3441524 | - |
| pre_assembly |  | 3441524 | - |
| context_budget |  | 3441524 | - |

`sparse`在`fusion/FUSION_THRESHOLD_OR_TOPK_LOSS`阶段排名靠前的非Gold文档：

- sourceStage=fusion rank=1 `39381118` At the gates of death.；dense=null，sparse=0.39862722，fusion=0.2850132003007921，rerank=null

  > Apoptosis that proceeds via the mitochondrial pathway involves mitochondrial outer membrane permeabilization (MOMP), responsible for the release of cytochrome c and other proteins of the mitochondrial intermembrane space. This essential step is controlled and mediated by proteins of the Bcl-2 family. The proapoptotic proteins Bax and Bak are required for MOMP, while the antiapoptotic Bcl-2 proteins, including Bcl-2, Bcl-xL, Mcl-1, and others, prevent MOMP. Different proapoptotic BH3-only proteins act to interfere with the function of the antiapoptotic Bcl-2 members and/or activate Bax and Bak.…

- sourceStage=fusion rank=3 `33499189` Full activation of the T cell receptor requires both clustering and conformational changes at CD3.；dense=null，sparse=0.33453706，fusion=0.25067648552225297，rerank=null

  > T cell receptor (TCR-CD3) triggering involves both receptor clustering and conformational changes at the cytoplasmic tails of the CD3 subunits. The mechanism by which TCRalphabeta ligand binding confers conformational changes to CD3 is unknown. By using well-defined ligands, we showed that induction of the conformational change requires both multivalent engagement and the mobility restriction of the TCR-CD3 imposed by the plasma membrane. The conformational change is elicited by cooperative rearrangements of two TCR-CD3 complexes and does not require accompanying changes in the structure of th…

- sourceStage=fusion rank=5 `4387784` Structure of the proton-gated urea channel from the gastric pathogen Helicobacter pylori；dense=null，sparse=0.31309837，fusion=0.2384424329153649，rerank=null

  > Half the world's population is chronically infected with Helicobacter pylori, causing gastritis, gastric ulcers and an increased incidence of gastric adenocarcinoma. Its proton-gated inner-membrane urea channel, HpUreI, is essential for survival in the acidic environment of the stomach. The channel is closed at neutral pH and opens at acidic pH to allow the rapid access of urea to cytoplasmic urease. Urease produces NH(3) and CO(2), neutralizing entering protons and thus buffering the periplasm to a pH of roughly 6.1 even in gastric juice at a pH below 2.0. Here we report the structure of HpUr…

## queryId=1363

问题：Venules have a thinner or absent smooth layer compared to arterioles.

原分类：`sparse_only_success`

Gold文档：

- `8290953` Scaffold-based three-dimensional human fibroblast culture provides a structural matrix that supports angiogenesis in infarcted heart tissue.

  > BACKGROUND We have developed techniques to implant angiogenic patches onto the epicardium over regions of infarcted cardiac tissue to stimulate revascularization of the damaged tissue. These experiments used a scaffold-based 3D human dermal fibroblast culture (3DFC) as an epicardial patch. The 3DFC contains viable cells that secrete angiogenic growth factors and has previously been shown to stimulate angiogenic activity. The hypothesis tested was that a viable 3DFC cardiac patch would stimulate an angiogenic response within an area of infarcted cardiac tissue. METHODS AND RESULTS A coronary oc…

| 变体 | 首个覆盖损失 | 首个完全损失 | 最终排名复现 | Rerank同请求效果 |
|---|---|---|---:|---|
| dense | dense_raw/DENSE_RAW_TOPK_MISS | dense_raw/DENSE_RAW_TOPK_MISS | 是 | 不适用 |
| sparse | 未观察到Gold损失 | 未观察到Gold损失 | 是 | 不适用 |
| hybrid_rrf | fusion/FUSION_THRESHOLD_OR_TOPK_LOSS | fusion/FUSION_THRESHOLD_OR_TOPK_LOSS | 是 | 不适用 |
| hybrid_rrf_rerank | fusion/FUSION_THRESHOLD_OR_TOPK_LOSS | fusion/FUSION_THRESHOLD_OR_TOPK_LOSS | 是 | RERANK_NEUTRAL (MRR Δ=0.000000) |

重点失败变体：`dense`。首个内部失效结论：dense_raw/DENSE_RAW_TOPK_MISS。该结论级别为阶段轨迹派生，不外推为模型根因。

重点变体Gold逐阶段路径：

| 阶段 | Gold仍存在 | Gold已缺失 | Gold最佳名次 |
|---|---|---|---:|
| dense_raw |  | 8290953 | - |
| fusion |  | 8290953 | - |
| candidate_filter |  | 8290953 | - |
| pre_assembly |  | 8290953 | - |
| context_budget |  | 8290953 | - |

`dense`在`dense_raw/DENSE_RAW_TOPK_MISS`阶段排名靠前的非Gold文档：

- sourceStage=dense_raw rank=1 `17741440` Netting neutrophils in autoimmune small-vessel vasculitis；dense=0.7834492，sparse=null，fusion=null，rerank=null

  > Small-vessel vasculitis (SVV) is a chronic autoinflammatory condition linked to antineutrophil cytoplasm autoantibodies (ANCAs). Here we show that chromatin fibers, so-called neutrophil extracellular traps (NETs), are released by ANCA-stimulated neutrophils and contain the targeted autoantigens proteinase-3 (PR3) and myeloperoxidase (MPO). Deposition of NETs in inflamed kidneys and circulating MPO-DNA complexes suggest that NET formation triggers vasculitis and promotes the autoimmune response against neutrophil components in individuals with SVV.

- sourceStage=dense_raw rank=2 `16760369` Comparative determinants of 4-year cardiovascular event rates in stable outpatients at risk of or with atherothrombosis.；dense=0.7802452，sparse=null，fusion=null，rerank=null

  > CONTEXT Clinicians and trialists have difficulty with identifying which patients are highest risk for cardiovascular events. Prior ischemic events, polyvascular disease, and diabetes mellitus have all been identified as predictors of ischemic events, but their comparative contributions to future risk remain unclear. OBJECTIVE To categorize the risk of cardiovascular events in stable outpatients with various initial manifestations of atherothrombosis using simple clinical descriptors. DESIGN, SETTING, AND PATIENTS Outpatients with coronary artery disease, cerebrovascular disease, or peripheral…

- sourceStage=dense_raw rank=4 `12991445` Influence of smoking and plasma factors on patency of femoropopliteal vein grafts.；dense=0.7788795，sparse=null，fusion=null，rerank=null

  > OBJECTIVE To determine the effects of smoking, plasma lipids, lipoproteins, apolipoproteins, and fibrinogen on the patency of saphenous vein femoropopliteal bypass grafts at one year. DESIGN Prospective study of patients with saphenous vein femoropopliteal bypass grafts entered into a multicentre trial. SETTING Surgical wards, outpatient clinics, and home visits coordinated by two tertiary referral centres in London and Birmingham. PATIENTS 157 Patients (mean age 66.6 (SD 8.2) years), 113 with patent grafts and 44 with occluded grafts one year after bypass. MAIN OUTCOME MEASURE Cumulative perc…

`hybrid_rrf`在`fusion/FUSION_THRESHOLD_OR_TOPK_LOSS`阶段排名靠前的非Gold文档：

- sourceStage=fusion rank=1 `16760369` Comparative determinants of 4-year cardiovascular event rates in stable outpatients at risk of or with atherothrombosis.；dense=0.77180624，sparse=0.17805597，fusion=0.7498491704374056，rerank=null

  > CONTEXT Clinicians and trialists have difficulty with identifying which patients are highest risk for cardiovascular events. Prior ischemic events, polyvascular disease, and diabetes mellitus have all been identified as predictors of ischemic events, but their comparative contributions to future risk remain unclear. OBJECTIVE To categorize the risk of cardiovascular events in stable outpatients with various initial manifestations of atherothrombosis using simple clinical descriptors. DESIGN, SETTING, AND PATIENTS Outpatients with coronary artery disease, cerebrovascular disease, or peripheral…

- sourceStage=fusion rank=2 `16495649` Ethnographic study of incidence and severity of intravenous drug errors.；dense=0.7712263，sparse=0.17841618，fusion=0.7440130893946154，rerank=null

  > OBJECTIVES To determine the incidence and clinical importance of errors in the preparation and administration of intravenous drugs and the stages of the process in which errors occur. DESIGN Prospective ethnographic study using disguised observation. PARTICIPANTS Nurses who prepared and administered intravenous drugs. SETTING 10 wards in a teaching and non-teaching hospital in the United Kingdom. MAIN OUTCOME MEASURES Number, type, and clinical importance of errors. RESULTS 249 errors were identified. At least one error occurred in 212 out of 430 intravenous drug doses (49%, 95% confidence int…

- sourceStage=fusion rank=3 `13619127` Diabetes treatments and risk of amputation, blindness, severe kidney failure, hyperglycaemia, and hypoglycaemia: open cohort study in primary care；dense=0.7603735，sparse=0.23650163，fusion=0.7033333333333334，rerank=null

  > OBJECTIVE To assess the risks of amputation, blindness, severe kidney failure, hyperglycaemia, and hypoglycaemia in patients with type 2 diabetes associated with prescribed diabetes drugs, particularly newer agents including gliptins or glitazones (thiazolidinediones). DESIGN Open cohort study in primary care. SETTING 1243 practices contributing data to the QResearch database in England. PARTICIPANTS 469,688 patients with type 2 diabetes aged 25-84 years between 1 April 2007 and 31 January 2015. EXPOSURES Hypoglycaemic agents (glitazones, gliptins, metformin, sulphonylureas, insulin, and other…

`hybrid_rrf_rerank`在`fusion/FUSION_THRESHOLD_OR_TOPK_LOSS`阶段排名靠前的非Gold文档：

- sourceStage=fusion rank=1 `16760369` Comparative determinants of 4-year cardiovascular event rates in stable outpatients at risk of or with atherothrombosis.；dense=0.77180624，sparse=0.17805597，fusion=0.7498491704374056，rerank=null

  > CONTEXT Clinicians and trialists have difficulty with identifying which patients are highest risk for cardiovascular events. Prior ischemic events, polyvascular disease, and diabetes mellitus have all been identified as predictors of ischemic events, but their comparative contributions to future risk remain unclear. OBJECTIVE To categorize the risk of cardiovascular events in stable outpatients with various initial manifestations of atherothrombosis using simple clinical descriptors. DESIGN, SETTING, AND PATIENTS Outpatients with coronary artery disease, cerebrovascular disease, or peripheral…

- sourceStage=fusion rank=2 `16495649` Ethnographic study of incidence and severity of intravenous drug errors.；dense=0.7712263，sparse=0.17841618，fusion=0.7440130893946154，rerank=null

  > OBJECTIVES To determine the incidence and clinical importance of errors in the preparation and administration of intravenous drugs and the stages of the process in which errors occur. DESIGN Prospective ethnographic study using disguised observation. PARTICIPANTS Nurses who prepared and administered intravenous drugs. SETTING 10 wards in a teaching and non-teaching hospital in the United Kingdom. MAIN OUTCOME MEASURES Number, type, and clinical importance of errors. RESULTS 249 errors were identified. At least one error occurred in 212 out of 430 intravenous drug doses (49%, 95% confidence int…

- sourceStage=fusion rank=3 `13619127` Diabetes treatments and risk of amputation, blindness, severe kidney failure, hyperglycaemia, and hypoglycaemia: open cohort study in primary care；dense=0.7603735，sparse=0.23650163，fusion=0.7033333333333334，rerank=null

  > OBJECTIVE To assess the risks of amputation, blindness, severe kidney failure, hyperglycaemia, and hypoglycaemia in patients with type 2 diabetes associated with prescribed diabetes drugs, particularly newer agents including gliptins or glitazones (thiazolidinediones). DESIGN Open cohort study in primary care. SETTING 1243 practices contributing data to the QResearch database in England. PARTICIPANTS 469,688 patients with type 2 diabetes aged 25-84 years between 1 April 2007 and 31 January 2015. EXPOSURES Hypoglycaemic agents (glitazones, gliptins, metformin, sulphonylureas, insulin, and other…

## queryId=1191

问题：The amount of publicly available DNA data doubles every 10 years.

原分类：`persistent_miss`

Gold文档：

- `30655442` The EMBL nucleotide sequence database.

  > The EMBL Nucleotide Sequence Database (http://www.ebi.ac.uk/embl. html ) constitutes Europe's primary nucleotide sequence resource. DNA and RNA sequences are directly submitted from researchers and genome sequencing groups and collected from the scientific literature and patent applications (Fig. 1). In collaboration with DDBJ and GenBank the database is produced, maintained and distributed at the European Bioinformatics Institute. Database releases are produced quarterly and are distributed on CD-ROM. EBI's network services allow access to the most up-to-date data collection via Internet and…

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
| dense_raw | 30655442 |  | 30 |
| fusion |  | 30655442 | - |
| candidate_filter |  | 30655442 | - |
| pre_assembly |  | 30655442 | - |
| context_budget |  | 30655442 | - |

`dense`在`fusion/FUSION_THRESHOLD_OR_TOPK_LOSS`阶段排名靠前的非Gold文档：

- sourceStage=fusion rank=1 `10874408` Mapping Meiotic Single-Strand DNA Reveals a New Landscape of DNA Double-Strand Breaks in Saccharomyces cerevisiae；dense=0.78274363，sparse=null，fusion=0.8913718150000001，rerank=null

  > DNA double-strand breaks (DSBs), which are formed by the Spo11 protein, initiate meiotic recombination. Previous DSB-mapping studies have used rad50S or sae2Δ mutants, which are defective in break processing, to accumulate Spo11-linked DSBs, and report large (≥ 50 kb) “DSB-hot” regions that are separated by “DSB-cold” domains of similar size. Substantial recombination occurs in some DSB-cold regions, suggesting that DSB patterns are not normal in rad50S or sae2Δ mutants. We therefore developed a novel method to map genome-wide, single-strand DNA (ssDNA)–associated DSBs that accumulate in proce…

- sourceStage=fusion rank=2 `13519661` Linkage Disequilibrium Mapping of       CHEK2: Common Variation and Breast Cancer Risk；dense=0.7814723，sparse=null，fusion=0.89073615，rerank=null

  > Background Checkpoint kinase 2 (CHEK2) averts cancer development by promoting cell cycle arrest and activating DNA repair in genetically damaged cells. Previous investigation has established a role for the CHEK2 gene in breast cancer aetiology, but studies have largely been limited to the rare 1100delC mutation. Whether common polymorphisms in this gene influence breast cancer risk remains unknown. In this study, we aimed to assess the importance of common CHEK2 variants on population risk for breast cancer by capturing the majority of diversity in the gene using haplotype tagging single nucle…

- sourceStage=fusion rank=3 `14079881` Perceived age as clinically useful biomarker of ageing: cohort study.；dense=0.7813046，sparse=null，fusion=0.8906523，rerank=null

  > OBJECTIVE To determine whether perceived age correlates with survival and important age related phenotypes. DESIGN Follow-up study, with survival of twins determined up to January 2008, by which time 675 (37%) had died. SETTING Population based twin cohort in Denmark. PARTICIPANTS 20 nurses, 10 young men, and 11 older women (assessors); 1826 twins aged >or=70. MAIN OUTCOME MEASURES Assessors: perceived age of twins from photographs. Twins: physical and cognitive tests and molecular biomarker of ageing (leucocyte telomere length). RESULTS For all three groups of assessors, perceived age was sig…

`sparse`在`fusion/FUSION_THRESHOLD_OR_TOPK_LOSS`阶段排名靠前的非Gold文档：

- sourceStage=fusion rank=1 `39381118` At the gates of death.；dense=null，sparse=0.26342404，fusion=0.2085001010428771，rerank=null

  > Apoptosis that proceeds via the mitochondrial pathway involves mitochondrial outer membrane permeabilization (MOMP), responsible for the release of cytochrome c and other proteins of the mitochondrial intermembrane space. This essential step is controlled and mediated by proteins of the Bcl-2 family. The proapoptotic proteins Bax and Bak are required for MOMP, while the antiapoptotic Bcl-2 proteins, including Bcl-2, Bcl-xL, Mcl-1, and others, prevent MOMP. Different proapoptotic BH3-only proteins act to interfere with the function of the antiapoptotic Bcl-2 members and/or activate Bax and Bak.…

- sourceStage=fusion rank=2 `17628888` ATPase-Dependent Control of the Mms21 SUMO Ligase during DNA Repair；dense=null，sparse=0.2423585，fusion=0.19507935913828417，rerank=null

  > Modification of proteins by SUMO is essential for the maintenance of genome integrity. During DNA replication, the Mms21-branch of the SUMO pathway counteracts recombination intermediates at damaged replication forks, thus facilitating sister chromatid disjunction. The Mms21 SUMO ligase docks to the arm region of the Smc5 protein in the Smc5/6 complex; together, they cooperate during recombinational DNA repair. Yet how the activity of the SUMO ligase is controlled remains unknown. Here we show that the SUMO ligase and the chromosome disjunction functions of Mms21 depend on its docking to an in…

- sourceStage=fusion rank=4 `791050` The relation between past exposure to fine particulate air pollution and prevalent anxiety: observational cohort study；dense=null，sparse=0.2390777，fusion=0.1929481097109568，rerank=null

  > OBJECTIVE To determine whether higher past exposure to particulate air pollution is associated with prevalent high symptoms of anxiety. DESIGN Observational cohort study. SETTING Nurses' Health Study. PARTICIPANTS 71,271 women enrolled in the Nurses' Health Study residing throughout the contiguous United States who had valid estimates on exposure to particulate matter for at least one exposure period of interest and data on anxiety symptoms. MAIN OUTCOME MEASURES Meaningfully high symptoms of anxiety, defined as a score of 6 points or greater on the phobic anxiety subscale of the Crown-Crisp i…

`hybrid_rrf`在`fusion/FUSION_THRESHOLD_OR_TOPK_LOSS`阶段排名靠前的非Gold文档：

- sourceStage=fusion rank=1 `44172171` Kinetics and Fidelity of the Repair of Cas9-Induced Double-Strand DNA Breaks；dense=0.772509，sparse=0.22936304，fusion=0.9037532923617206，rerank=null

  > The RNA-guided DNA endonuclease Cas9 is a powerful tool for genome editing. Little is known about the kinetics and fidelity of the double-strand break (DSB) repair process that follows a Cas9 cutting event in living cells. Here, we developed a strategy to measure the kinetics of DSB repair for single loci in human cells. Quantitative modeling of repaired DNA in time series after Cas9 activation reveals variable and often slow repair rates, with half-life times up to ∼10 hr. Furthermore, repair of the DSBs tends to be error prone. Both classical and microhomology-mediated end joining pathways c…

- sourceStage=fusion rank=2 `10874408` Mapping Meiotic Single-Strand DNA Reveals a New Landscape of DNA Double-Strand Breaks in Saccharomyces cerevisiae；dense=0.78274363，sparse=0.21526188，fusion=0.8860759493670887，rerank=null

  > DNA double-strand breaks (DSBs), which are formed by the Spo11 protein, initiate meiotic recombination. Previous DSB-mapping studies have used rad50S or sae2Δ mutants, which are defective in break processing, to accumulate Spo11-linked DSBs, and report large (≥ 50 kb) “DSB-hot” regions that are separated by “DSB-cold” domains of similar size. Substantial recombination occurs in some DSB-cold regions, suggesting that DSB patterns are not normal in rad50S or sae2Δ mutants. We therefore developed a novel method to map genome-wide, single-strand DNA (ssDNA)–associated DSBs that accumulate in proce…

- sourceStage=fusion rank=3 `25742130` Mass screening programmes and trends in cervical cancer in Finland and the Netherlands.；dense=0.7722478，sparse=0.22784764，fusion=0.8656400966183575，rerank=null

  > With respect to cervical cancer management, Finland and the Netherlands are comparable in relevant characteristics, e.g., fertility rate, age-of-mother at first birth and a national screening programme for several years. The aim of this study is to compare trends in incidence of and mortality from cervical cancer in Finland and the Netherlands in relation to the introduction and intensity of the screening programmes. Therefore, incidence and mortality rates were calculated using the Cancer Registries of Finland and the Netherlands. Data on screening intensity were obtained from the Finnish Can…

`hybrid_rrf_rerank`在`fusion/FUSION_THRESHOLD_OR_TOPK_LOSS`阶段排名靠前的非Gold文档：

- sourceStage=fusion rank=1 `44172171` Kinetics and Fidelity of the Repair of Cas9-Induced Double-Strand DNA Breaks；dense=0.772509，sparse=0.22936304，fusion=0.9037532923617206，rerank=null

  > The RNA-guided DNA endonuclease Cas9 is a powerful tool for genome editing. Little is known about the kinetics and fidelity of the double-strand break (DSB) repair process that follows a Cas9 cutting event in living cells. Here, we developed a strategy to measure the kinetics of DSB repair for single loci in human cells. Quantitative modeling of repaired DNA in time series after Cas9 activation reveals variable and often slow repair rates, with half-life times up to ∼10 hr. Furthermore, repair of the DSBs tends to be error prone. Both classical and microhomology-mediated end joining pathways c…

- sourceStage=fusion rank=2 `10874408` Mapping Meiotic Single-Strand DNA Reveals a New Landscape of DNA Double-Strand Breaks in Saccharomyces cerevisiae；dense=0.78274363，sparse=0.21526188，fusion=0.8860759493670887，rerank=null

  > DNA double-strand breaks (DSBs), which are formed by the Spo11 protein, initiate meiotic recombination. Previous DSB-mapping studies have used rad50S or sae2Δ mutants, which are defective in break processing, to accumulate Spo11-linked DSBs, and report large (≥ 50 kb) “DSB-hot” regions that are separated by “DSB-cold” domains of similar size. Substantial recombination occurs in some DSB-cold regions, suggesting that DSB patterns are not normal in rad50S or sae2Δ mutants. We therefore developed a novel method to map genome-wide, single-strand DNA (ssDNA)–associated DSBs that accumulate in proce…

- sourceStage=fusion rank=3 `25742130` Mass screening programmes and trends in cervical cancer in Finland and the Netherlands.；dense=0.7722478，sparse=0.22784764，fusion=0.8656400966183575，rerank=null

  > With respect to cervical cancer management, Finland and the Netherlands are comparable in relevant characteristics, e.g., fertility rate, age-of-mother at first birth and a national screening programme for several years. The aim of this study is to compare trends in incidence of and mortality from cervical cancer in Finland and the Netherlands in relation to the introduction and intensity of the screening programmes. Therefore, incidence and mortality rates were calculated using the Cancer Registries of Finland and the Netherlands. Data on screening intensity were obtained from the Finnish Can…

## queryId=1199

问题：The benefits of colchicine were achieved with effective widespread use of secondary prevention strategies such as high-dose statins.

原分类：`persistent_miss`

Gold文档：

- `16760369` Comparative determinants of 4-year cardiovascular event rates in stable outpatients at risk of or with atherothrombosis.

  > CONTEXT Clinicians and trialists have difficulty with identifying which patients are highest risk for cardiovascular events. Prior ischemic events, polyvascular disease, and diabetes mellitus have all been identified as predictors of ischemic events, but their comparative contributions to future risk remain unclear. OBJECTIVE To categorize the risk of cardiovascular events in stable outpatients with various initial manifestations of atherothrombosis using simple clinical descriptors. DESIGN, SETTING, AND PATIENTS Outpatients with coronary artery disease, cerebrovascular disease, or peripheral…

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
| dense_raw | 16760369 |  | 24 |
| fusion |  | 16760369 | - |
| candidate_filter |  | 16760369 | - |
| pre_assembly |  | 16760369 | - |
| context_budget |  | 16760369 | - |

`dense`在`fusion/FUSION_THRESHOLD_OR_TOPK_LOSS`阶段排名靠前的非Gold文档：

- sourceStage=fusion rank=1 `11718220` Effectiveness of thigh-length graduated compression stockings to reduce the risk of deep vein thrombosis after stroke (CLOTS trial 1): a multicentre, randomised controlled trial；dense=0.79654473，sparse=null，fusion=0.898272365，rerank=null

  > BACKGROUND Deep vein thrombosis (DVT) and pulmonary embolism are common after stroke. In small trials of patients undergoing surgery, graduated compression stockings (GCS) reduce the risk of DVT. National stroke guidelines extrapolating from these trials recommend their use in patients with stroke despite insufficient evidence. We assessed the effectiveness of thigh-length GCS to reduce DVT after stroke. METHODS In this outcome-blinded, randomised controlled trial, 2518 patients who were admitted to hospital within 1 week of an acute stroke and who were immobile were enrolled from 64 centres i…

- sourceStage=fusion rank=2 `4687948` HMG-CoA reductase inhibitors and the risk of hip fractures in elderly patients.；dense=0.79502106，sparse=null，fusion=0.8975105299999999，rerank=null

  > CONTEXT Recent animal studies have found that 3-hydroxy-3-methylglutaryl coenzyme A (HMG-CoA) lipid-lowering drugs (statins) substantially increase bone formation, but whether statin use in humans results in clinically meaningful bone formation or a reduction in the risk of osteoporotic fractures is not known. OBJECTIVE To determine whether the use of statins is associated with reduced hip fracture risk. DESIGN Case-control study. SETTING AND PATIENTS A total of 6110 New Jersey residents aged 65 years or older and enrolled in Medicare and either Medicaid or the Pharmacy Assistance for the Aged…

- sourceStage=fusion rank=9 `1469751` Aptamer-functionalized lipid nanoparticles targeting osteoblasts as a novel RNA interference–based bone anabolic strategy；dense=0.7834872，sparse=null，fusion=0.8917436000000001，rerank=null

  > Currently, major concerns about the safety and efficacy of RNA interference (RNAi)-based bone anabolic strategies still exist because of the lack of direct osteoblast-specific delivery systems for osteogenic siRNAs. Here we screened the aptamer CH6 by cell-SELEX, specifically targeting both rat and human osteoblasts, and then we developed CH6 aptamer–functionalized lipid nanoparticles (LNPs) encapsulating osteogenic pleckstrin homology domain-containing family O member 1 (Plekho1) siRNA (CH6-LNPs-siRNA). Our results showed that CH6 facilitated in vitro osteoblast-selective uptake of Plekho1 si…

`sparse`在`fusion/FUSION_THRESHOLD_OR_TOPK_LOSS`阶段排名靠前的非Gold文档：

- sourceStage=fusion rank=1 `4687948` HMG-CoA reductase inhibitors and the risk of hip fractures in elderly patients.；dense=null，sparse=0.30113286，fusion=0.2314389784914048，rerank=null

  > CONTEXT Recent animal studies have found that 3-hydroxy-3-methylglutaryl coenzyme A (HMG-CoA) lipid-lowering drugs (statins) substantially increase bone formation, but whether statin use in humans results in clinically meaningful bone formation or a reduction in the risk of osteoporotic fractures is not known. OBJECTIVE To determine whether the use of statins is associated with reduced hip fracture risk. DESIGN Case-control study. SETTING AND PATIENTS A total of 6110 New Jersey residents aged 65 years or older and enrolled in Medicare and either Medicaid or the Pharmacy Assistance for the Aged…

- sourceStage=fusion rank=2 `24088502` Clinical outcomes following institution of the Canadian universal leukoreduction program for red blood cell transfusions.；dense=null，sparse=0.28385141，fusion=0.22109366223307728，rerank=null

  > CONTEXT A number of countries have implemented a policy of universal leukoreduction of their blood supply, but the potential role of leukoreduction in decreasing postoperative mortality and infection is unclear. OBJECTIVE To evaluate clinical outcomes following adoption of a national universal prestorage leukoreduction program for blood transfusions. DESIGN, SETTING, AND POPULATION Retrospective before-and-after cohort study conducted from August 1998 to August 2000 in 23 academic and community hospitals throughout Canada, enrolling 14 786 patients who received red blood cell transfusions foll…

- sourceStage=fusion rank=3 `18340282` Gene–environment interactions in 7610 women with breast cancer: prospective evidence from the Million Women Study；dense=null，sparse=0.28088653，fusion=0.2192907204668629，rerank=null

  > BACKGROUND Information is scarce about the combined effects on breast cancer incidence of low-penetrance genetic susceptibility polymorphisms and environmental factors (reproductive, behavioural, and anthropometric risk factors for breast cancer). To test for evidence of gene-environment interactions, we compared genotypic relative risks for breast cancer across the other risk factors in a large UK prospective study. METHODS We tested gene-environment interactions in 7610 women who developed breast cancer and 10 196 controls without the disease, studying the effects of 12 polymorphisms (FGFR2-…

`hybrid_rrf`在`fusion/FUSION_THRESHOLD_OR_TOPK_LOSS`阶段排名靠前的非Gold文档：

- sourceStage=fusion rank=1 `4687948` HMG-CoA reductase inhibitors and the risk of hip fractures in elderly patients.；dense=0.79109395，sparse=0.30113286，fusion=0.9765625000000001，rerank=null

  > CONTEXT Recent animal studies have found that 3-hydroxy-3-methylglutaryl coenzyme A (HMG-CoA) lipid-lowering drugs (statins) substantially increase bone formation, but whether statin use in humans results in clinically meaningful bone formation or a reduction in the risk of osteoporotic fractures is not known. OBJECTIVE To determine whether the use of statins is associated with reduced hip fracture risk. DESIGN Case-control study. SETTING AND PATIENTS A total of 6110 New Jersey residents aged 65 years or older and enrolled in Medicare and either Medicaid or the Pharmacy Assistance for the Aged…

- sourceStage=fusion rank=3 `24088502` Clinical outcomes following institution of the Canadian universal leukoreduction program for red blood cell transfusions.；dense=0.7677549，sparse=0.28385141，fusion=0.8346321130844508，rerank=null

  > CONTEXT A number of countries have implemented a policy of universal leukoreduction of their blood supply, but the potential role of leukoreduction in decreasing postoperative mortality and infection is unclear. OBJECTIVE To evaluate clinical outcomes following adoption of a national universal prestorage leukoreduction program for blood transfusions. DESIGN, SETTING, AND POPULATION Retrospective before-and-after cohort study conducted from August 1998 to August 2000 in 23 academic and community hospitals throughout Canada, enrolling 14 786 patients who received red blood cell transfusions foll…

- sourceStage=fusion rank=5 `13843341` Cost effectiveness of ward based non-invasive ventilation for acute exacerbations of chronic obstructive pulmonary disease: economic analysis of randomised controlled trial.；dense=0.77154994，sparse=0.24377766，fusion=0.7105440344734716，rerank=null

  > OBJECTIVE To evaluate the cost effectiveness of standard treatment with and without the addition of ward based non-invasive ventilation in patients admitted to hospital with an acute exacerbation of chronic obstructive pulmonary disease. DESIGN Incremental cost effectiveness analysis of a randomised controlled trial. SETTING Medical wards in 14 hospitals in the United Kingdom. PARTICIPANTS The trial comprised 236 patients admitted to hospital with an acute exacerbation of chronic obstructive pulmonary disease and mild to moderate acidosis (pH 7.25-7.35) secondary to respiratory failure. The ec…

`hybrid_rrf_rerank`在`fusion/FUSION_THRESHOLD_OR_TOPK_LOSS`阶段排名靠前的非Gold文档：

- sourceStage=fusion rank=1 `4687948` HMG-CoA reductase inhibitors and the risk of hip fractures in elderly patients.；dense=0.79109395，sparse=0.30113286，fusion=0.9765625000000001，rerank=null

  > CONTEXT Recent animal studies have found that 3-hydroxy-3-methylglutaryl coenzyme A (HMG-CoA) lipid-lowering drugs (statins) substantially increase bone formation, but whether statin use in humans results in clinically meaningful bone formation or a reduction in the risk of osteoporotic fractures is not known. OBJECTIVE To determine whether the use of statins is associated with reduced hip fracture risk. DESIGN Case-control study. SETTING AND PATIENTS A total of 6110 New Jersey residents aged 65 years or older and enrolled in Medicare and either Medicaid or the Pharmacy Assistance for the Aged…

- sourceStage=fusion rank=3 `24088502` Clinical outcomes following institution of the Canadian universal leukoreduction program for red blood cell transfusions.；dense=0.7677549，sparse=0.28385141，fusion=0.8346321130844508，rerank=null

  > CONTEXT A number of countries have implemented a policy of universal leukoreduction of their blood supply, but the potential role of leukoreduction in decreasing postoperative mortality and infection is unclear. OBJECTIVE To evaluate clinical outcomes following adoption of a national universal prestorage leukoreduction program for blood transfusions. DESIGN, SETTING, AND POPULATION Retrospective before-and-after cohort study conducted from August 1998 to August 2000 in 23 academic and community hospitals throughout Canada, enrolling 14 786 patients who received red blood cell transfusions foll…

- sourceStage=fusion rank=5 `13843341` Cost effectiveness of ward based non-invasive ventilation for acute exacerbations of chronic obstructive pulmonary disease: economic analysis of randomised controlled trial.；dense=0.77154994，sparse=0.24377766，fusion=0.7105440344734716，rerank=null

  > OBJECTIVE To evaluate the cost effectiveness of standard treatment with and without the addition of ward based non-invasive ventilation in patients admitted to hospital with an acute exacerbation of chronic obstructive pulmonary disease. DESIGN Incremental cost effectiveness analysis of a randomised controlled trial. SETTING Medical wards in 14 hospitals in the United Kingdom. PARTICIPANTS The trial comprised 236 patients admitted to hospital with an acute exacerbation of chronic obstructive pulmonary disease and mild to moderate acidosis (pH 7.25-7.35) secondary to respiratory failure. The ec…

## queryId=437

问题：Functional consequences of genomic alterations due to Myelodysplastic syndrome (MDS) are poorly understood due to the lack of an animal model.

原分类：`persistent_miss`

Gold文档：

- `18399038` Establishment of human iPSC-based models for the study and targeting of glioma initiating cells

  > Glioma tumour-initiating cells (GTICs) can originate upon the transformation of neural progenitor cells (NPCs). Studies on GTICs have focused on primary tumours from which GTICs could be isolated and the use of human embryonic material. Recently, the somatic genomic landscape of human gliomas has been reported. RTK (receptor tyrosine kinase) and p53 signalling were found dysregulated in ∼90% and 86% of all primary tumours analysed, respectively. Here we report on the use of human-induced pluripotent stem cells (hiPSCs) for modelling gliomagenesis. Dysregulation of RTK and p53 signalling in hiP…

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
| dense_raw | 18399038 |  | 36 |
| fusion |  | 18399038 | - |
| candidate_filter |  | 18399038 | - |
| pre_assembly |  | 18399038 | - |
| context_budget |  | 18399038 | - |

`dense`在`fusion/FUSION_THRESHOLD_OR_TOPK_LOSS`阶段排名靠前的非Gold文档：

- sourceStage=fusion rank=1 `3863543` Mesenchymal Inflammation Drives Genotoxic Stress in Hematopoietic Stem Cells and Predicts Disease Evolution in Human Pre-leukemia.；dense=0.819407，sparse=null，fusion=0.9097035，rerank=null

  > Mesenchymal niche cells may drive tissue failure and malignant transformation in the hematopoietic system, but the underlying molecular mechanisms and relevance to human disease remain poorly defined. Here, we show that perturbation of mesenchymal cells in a mouse model of the pre-leukemic disorder Shwachman-Diamond syndrome (SDS) induces mitochondrial dysfunction, oxidative stress, and activation of DNA damage responses in hematopoietic stem and progenitor cells. Massive parallel RNA sequencing of highly purified mesenchymal cells in the SDS mouse model and a range of human pre-leukemic syndr…

- sourceStage=fusion rank=2 `4388470` Somatic sex identity is cell-autonomous in the chicken；dense=0.811682，sparse=null，fusion=0.905841，rerank=null

  > In the mammalian model of sex determination, embryos are considered to be sexually indifferent until the transient action of a sex-determining gene initiates gonadal differentiation. Although this model is thought to apply to all vertebrates, this has yet to be established. Here we have examined three lateral gynandromorph chickens (a rare, naturally occurring phenomenon in which one side of the animal appears male and the other female) to investigate the sex-determining mechanism in birds. These studies demonstrated that gynandromorph birds are genuine male:female chimaeras, and indicated tha…

- sourceStage=fusion rank=3 `11369420` Tetraspanin 3 Is Required for the Development and Propagation of Acute Myelogenous Leukemia.；dense=0.8093397，sparse=null，fusion=0.90466985，rerank=null

  > Acute Myelogenous Leukemia (AML) is an aggressive cancer that strikes both adults and children and is frequently resistant to therapy. Thus, identifying signals needed for AML propagation is a critical step toward developing new approaches for treating this disease. Here, we show that Tetraspanin 3 is a target of the RNA binding protein Musashi 2, which plays a key role in AML. We generated Tspan3 knockout mice that were born without overt defects. However, Tspan3 deletion impaired leukemia stem cell self-renewal and disease propagation and markedly improved survival in mouse models of AML. Ad…

`sparse`在`fusion/FUSION_THRESHOLD_OR_TOPK_LOSS`阶段排名靠前的非Gold文档：

- sourceStage=fusion rank=1 `1606628` Estimates of global prevalence of childhood underweight in 1990 and 2015.；dense=null，sparse=0.3119836，fusion=0.23779535049066164，rerank=null

  > CONTEXT One key target of the United Nations Millennium Development goals is to reduce the prevalence of underweight among children younger than 5 years by half between 1990 and 2015. OBJECTIVE To estimate trends in childhood underweight by geographic regions of the world. DESIGN, SETTING, AND PARTICIPANTS Time series study of prevalence of underweight, defined as weight 2 SDs below the mean weight for age of the National Center for Health Statistics and World Health Organization (WHO) reference population. National prevalence rates derived from the WHO Global Database on Child Growth and Maln…

- sourceStage=fusion rank=2 `14637235` Histone levels are regulated by phosphorylation and ubiquitylation dependent proteolysis；dense=null，sparse=0.3017927，fusion=0.23182853921365515，rerank=null

  > Histone levels are tightly regulated to prevent harmful effects such as genomic instability and hypersensitivity to DNA-damaging agents due to the accumulation of these highly basic proteins when DNA replication slows down or stops. Although chromosomal histones are stable, excess (non-chromatin bound) histones are rapidly degraded in a Rad53 (radiation sensitive 53) kinase-dependent manner in Saccharomyces cerevisiae. Here we demonstrate that excess histones associate with Rad53 in vivo and seem to undergo modifications such as tyrosine phosphorylation and polyubiquitylation, before their pro…

- sourceStage=fusion rank=3 `5476778` Autoimmunity due to molecular mimicry as a cause of neurological disease；dense=null，sparse=0.29858845，fusion=0.2299330861906249，rerank=null

  > One hypothesis that couples infection with autoimmune disease is molecular mimicry. Molecular mimicry is characterized by an immune response to an environmental agent that cross-reacts with a host antigen, resulting in disease. This hypothesis has been implicated in the pathogenesis of diabetes, lupus and multiple sclerosis (MS). There is limited direct evidence linking causative agents with pathogenic immune reactions in these diseases. Our study establishes a clear link between viral infection, autoimmunity and neurological disease in humans. As a model for molecular mimicry, we studied pati…

`hybrid_rrf`在`fusion/FUSION_THRESHOLD_OR_TOPK_LOSS`阶段排名靠前的非Gold文档：

- sourceStage=fusion rank=1 `4388470` Somatic sex identity is cell-autonomous in the chicken；dense=0.811682，sparse=0.2857865，fusion=0.961166253101737，rerank=null

  > In the mammalian model of sex determination, embryos are considered to be sexually indifferent until the transient action of a sex-determining gene initiates gonadal differentiation. Although this model is thought to apply to all vertebrates, this has yet to be established. Here we have examined three lateral gynandromorph chickens (a rare, naturally occurring phenomenon in which one side of the animal appears male and the other female) to investigate the sex-determining mechanism in birds. These studies demonstrated that gynandromorph birds are genuine male:female chimaeras, and indicated tha…

- sourceStage=fusion rank=2 `3863543` Mesenchymal Inflammation Drives Genotoxic Stress in Hematopoietic Stem Cells and Predicts Disease Evolution in Human Pre-leukemia.；dense=0.819407，sparse=0.26309156，fusion=0.8961038961038961，rerank=null

  > Mesenchymal niche cells may drive tissue failure and malignant transformation in the hematopoietic system, but the underlying molecular mechanisms and relevance to human disease remain poorly defined. Here, we show that perturbation of mesenchymal cells in a mouse model of the pre-leukemic disorder Shwachman-Diamond syndrome (SDS) induces mitochondrial dysfunction, oxidative stress, and activation of DNA damage responses in hematopoietic stem and progenitor cells. Massive parallel RNA sequencing of highly purified mesenchymal cells in the SDS mouse model and a range of human pre-leukemic syndr…

- sourceStage=fusion rank=3 `5476778` Autoimmunity due to molecular mimicry as a cause of neurological disease；dense=0.7999233，sparse=0.2786945，fusion=0.8857323232323232，rerank=null

  > One hypothesis that couples infection with autoimmune disease is molecular mimicry. Molecular mimicry is characterized by an immune response to an environmental agent that cross-reacts with a host antigen, resulting in disease. This hypothesis has been implicated in the pathogenesis of diabetes, lupus and multiple sclerosis (MS). There is limited direct evidence linking causative agents with pathogenic immune reactions in these diseases. Our study establishes a clear link between viral infection, autoimmunity and neurological disease in humans. As a model for molecular mimicry, we studied pati…

`hybrid_rrf_rerank`在`fusion/FUSION_THRESHOLD_OR_TOPK_LOSS`阶段排名靠前的非Gold文档：

- sourceStage=fusion rank=1 `4388470` Somatic sex identity is cell-autonomous in the chicken；dense=0.811682，sparse=0.2857865，fusion=0.961166253101737，rerank=null

  > In the mammalian model of sex determination, embryos are considered to be sexually indifferent until the transient action of a sex-determining gene initiates gonadal differentiation. Although this model is thought to apply to all vertebrates, this has yet to be established. Here we have examined three lateral gynandromorph chickens (a rare, naturally occurring phenomenon in which one side of the animal appears male and the other female) to investigate the sex-determining mechanism in birds. These studies demonstrated that gynandromorph birds are genuine male:female chimaeras, and indicated tha…

- sourceStage=fusion rank=2 `3863543` Mesenchymal Inflammation Drives Genotoxic Stress in Hematopoietic Stem Cells and Predicts Disease Evolution in Human Pre-leukemia.；dense=0.819407，sparse=0.26309156，fusion=0.8961038961038961，rerank=null

  > Mesenchymal niche cells may drive tissue failure and malignant transformation in the hematopoietic system, but the underlying molecular mechanisms and relevance to human disease remain poorly defined. Here, we show that perturbation of mesenchymal cells in a mouse model of the pre-leukemic disorder Shwachman-Diamond syndrome (SDS) induces mitochondrial dysfunction, oxidative stress, and activation of DNA damage responses in hematopoietic stem and progenitor cells. Massive parallel RNA sequencing of highly purified mesenchymal cells in the SDS mouse model and a range of human pre-leukemic syndr…

- sourceStage=fusion rank=3 `5476778` Autoimmunity due to molecular mimicry as a cause of neurological disease；dense=0.7999233，sparse=0.2786945，fusion=0.8857323232323232，rerank=null

  > One hypothesis that couples infection with autoimmune disease is molecular mimicry. Molecular mimicry is characterized by an immune response to an environmental agent that cross-reacts with a host antigen, resulting in disease. This hypothesis has been implicated in the pathogenesis of diabetes, lupus and multiple sclerosis (MS). There is limited direct evidence linking causative agents with pathogenic immune reactions in these diseases. Our study establishes a clear link between viral infection, autoimmunity and neurological disease in humans. As a model for molecular mimicry, we studied pati…

## queryId=502

问题：Healthcare delivery efficiency in crowded delivery centers is impaired by improving structural, logistical, and interpersonal elements.

原分类：`persistent_miss`

Gold文档：

- `13071728` The HIV Treatment Gap: Estimates of the Financial Resources Needed versus Available for Scale-Up of Antiretroviral Therapy in 97 Countries from 2015 to 2020

  > BACKGROUND The World Health Organization (WHO) released revised guidelines in 2015 recommending that all people living with HIV, regardless of CD4 count, initiate antiretroviral therapy (ART) upon diagnosis. However, few studies have projected the global resources needed for rapid scale-up of ART. Under the Health Policy Project, we conducted modeling analyses for 97 countries to estimate eligibility for and numbers on ART from 2015 to 2020, along with the facility-level financial resources required. We compared the estimated financial requirements to estimated funding available. METHODS AND F…

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
| dense_raw | 13071728 |  | 49 |
| fusion |  | 13071728 | - |
| candidate_filter |  | 13071728 | - |
| pre_assembly |  | 13071728 | - |
| context_budget |  | 13071728 | - |

`dense`在`fusion/FUSION_THRESHOLD_OR_TOPK_LOSS`阶段排名靠前的非Gold文档：

- sourceStage=fusion rank=1 `5289038` Partitioning regulatory mechanisms of within-host malaria dynamics using the effective propagation number.；dense=0.7846915，sparse=null，fusion=0.89234575，rerank=null

  > Immune clearance and resource limitation (via red blood cell depletion) shape the peaks and troughs of malaria parasitemia, which in turn affect disease severity and transmission. Quantitatively partitioning the relative roles of these effects through time is challenging. Using data from rodent malaria, we estimated the effective propagation number, which reflects the relative importance of contrasting within-host control mechanisms through time and is sensitive to the inoculating parasite dose. Our analysis showed that the capacity of innate responses to restrict initial parasite growth satur…

- sourceStage=fusion rank=2 `13906581` Patient Outcomes with Teaching Versus Nonteaching Healthcare: A Systematic Review；dense=0.781641，sparse=null，fusion=0.8908205，rerank=null

  > Background  Extensive debate exists in the healthcare community over whether outcomes of medical care at teaching hospitals and other healthcare units are better or worse than those at the respective nonteaching ones. Thus, our goal was to systematically evaluate the evidence pertaining to this question. Methods and Findings  We reviewed all studies that compared teaching versus nonteaching healthcare structures for mortality or any other patient outcome, regardless of health condition. Studies were retrieved from PubMed, contact with experts, and literature cross-referencing. Data were extrac…

- sourceStage=fusion rank=3 `25649714` Mental health problems of homeless children and families: longitudinal study.；dense=0.77760476，sparse=null，fusion=0.88880238，rerank=null

  > OBJECTIVE To establish the mental health needs of homeless children and families before and after rehousing. DESIGN Cross sectional, longitudinal study. SETTING City of Birmingham. SUBJECTS 58 rehoused families with 103 children aged 2-16 years and 21 comparison families of low socioeconomic status in stable housing, with 54 children. MAIN OUTCOME MEASURES Children's mental health problems and level of communication; mothers' mental health problems and social support one year after rehousing. RESULTS Mental health problems remained significantly higher in rehoused mothers and their children th…

`sparse`在`fusion/FUSION_THRESHOLD_OR_TOPK_LOSS`阶段排名靠前的非Gold文档：

- sourceStage=fusion rank=1 `15928989` Liver receptor homolog-1 is essential for pregnancy；dense=null，sparse=0.26248363，fusion=0.20791052158038678，rerank=null

  > Successful pregnancy requires coordination of an array of signals and factors from multiple tissues. One such element, liver receptor homolog-1 (Lrh-1), is an orphan nuclear receptor that regulates metabolism and hormone synthesis. It is strongly expressed in granulosa cells of ovarian follicles and in the corpus luteum of rodents and humans. Germline ablation of Nr5a2 (also called Lrh-1), the gene coding for Lrh-1, in mice is embryonically lethal at gastrulation. Depletion of Lrh-1 in the ovarian follicle shows that it regulates genes required for both steroid synthesis and ovulation. To stud…

- sourceStage=fusion rank=2 `8551160` Mitochondria: Dynamic Organelles in Disease, Aging, and Development；dense=null，sparse=0.25552392，fusion=0.20351975452606275，rerank=null

  > Mitochondria are the primary energy-generating system in most eukaryotic cells. Additionally, they participate in intermediary metabolism, calcium signaling, and apoptosis. Given these well-established functions, it might be expected that mitochondrial dysfunction would give rise to a simple and predictable set of defects in all tissues. However, mitochondrial dysfunction has pleiotropic effects in multicellular organisms. Clearly, much about the basic biology of mitochondria remains to be understood. Here we discuss recent work that suggests that the dynamics (fusion and fission) of these org…

- sourceStage=fusion rank=3 `18909530` Contractile forces sustain and polarize hematopoiesis from stem and progenitor cells.；dense=null，sparse=0.25526726，fusion=0.20335690106344365，rerank=null

  > Self-renewal and differentiation of stem cells depend on asymmetric division and polarized motility processes that in other cell types are modulated by nonmuscle myosin-II (MII) forces and matrix mechanics. Here, mass spectrometry-calibrated intracellular flow cytometry of human hematopoiesis reveals MIIB to be a major isoform that is strongly polarized in hematopoietic stem cells and progenitors (HSC/Ps) and thereby downregulated in differentiated cells via asymmetric division. MIIA is constitutive and activated by dephosphorylation during cytokine-triggered differentiation of cells grown on…

`hybrid_rrf`在`fusion/FUSION_THRESHOLD_OR_TOPK_LOSS`阶段排名靠前的非Gold文档：

- sourceStage=fusion rank=1 `3475317` Inflammatory signaling in human Tuberculosis granulomas is spatially organized；dense=0.7657046，sparse=0.25475466，fusion=0.8626384493670886，rerank=null

  > Granulomas are the pathological hallmark of tuberculosis (TB). However, their function and mechanisms of formation remain poorly understood. To understand the role of granulomas in TB, we analyzed the proteomes of granulomas from subjects with tuberculosis in an unbiased manner. Using laser-capture microdissection, mass spectrometry and confocal microscopy, we generated detailed molecular maps of human granulomas. We found that the centers of granulomas have a pro-inflammatory environment that is characterized by the presence of antimicrobial peptides, reactive oxygen species and pro-inflammat…

- sourceStage=fusion rank=2 `13906581` Patient Outcomes with Teaching Versus Nonteaching Healthcare: A Systematic Review；dense=0.7717501，sparse=0.23533827，fusion=0.8140474100087797，rerank=null

  > Background  Extensive debate exists in the healthcare community over whether outcomes of medical care at teaching hospitals and other healthcare units are better or worse than those at the respective nonteaching ones. Thus, our goal was to systematically evaluate the evidence pertaining to this question. Methods and Findings  We reviewed all studies that compared teaching versus nonteaching healthcare structures for mortality or any other patient outcome, regardless of health condition. Studies were retrieved from PubMed, contact with experts, and literature cross-referencing. Data were extrac…

- sourceStage=fusion rank=3 `15928989` Liver receptor homolog-1 is essential for pregnancy；dense=0.7614788，sparse=0.26248363，fusion=0.8049999999999999，rerank=null

  > Successful pregnancy requires coordination of an array of signals and factors from multiple tissues. One such element, liver receptor homolog-1 (Lrh-1), is an orphan nuclear receptor that regulates metabolism and hormone synthesis. It is strongly expressed in granulosa cells of ovarian follicles and in the corpus luteum of rodents and humans. Germline ablation of Nr5a2 (also called Lrh-1), the gene coding for Lrh-1, in mice is embryonically lethal at gastrulation. Depletion of Lrh-1 in the ovarian follicle shows that it regulates genes required for both steroid synthesis and ovulation. To stud…

`hybrid_rrf_rerank`在`fusion/FUSION_THRESHOLD_OR_TOPK_LOSS`阶段排名靠前的非Gold文档：

- sourceStage=fusion rank=1 `3475317` Inflammatory signaling in human Tuberculosis granulomas is spatially organized；dense=0.7657046，sparse=0.25475466，fusion=0.8626384493670886，rerank=null

  > Granulomas are the pathological hallmark of tuberculosis (TB). However, their function and mechanisms of formation remain poorly understood. To understand the role of granulomas in TB, we analyzed the proteomes of granulomas from subjects with tuberculosis in an unbiased manner. Using laser-capture microdissection, mass spectrometry and confocal microscopy, we generated detailed molecular maps of human granulomas. We found that the centers of granulomas have a pro-inflammatory environment that is characterized by the presence of antimicrobial peptides, reactive oxygen species and pro-inflammat…

- sourceStage=fusion rank=2 `13906581` Patient Outcomes with Teaching Versus Nonteaching Healthcare: A Systematic Review；dense=0.7717501，sparse=0.23533827，fusion=0.8140474100087797，rerank=null

  > Background  Extensive debate exists in the healthcare community over whether outcomes of medical care at teaching hospitals and other healthcare units are better or worse than those at the respective nonteaching ones. Thus, our goal was to systematically evaluate the evidence pertaining to this question. Methods and Findings  We reviewed all studies that compared teaching versus nonteaching healthcare structures for mortality or any other patient outcome, regardless of health condition. Studies were retrieved from PubMed, contact with experts, and literature cross-referencing. Data were extrac…

- sourceStage=fusion rank=3 `15928989` Liver receptor homolog-1 is essential for pregnancy；dense=0.7614788，sparse=0.26248363，fusion=0.8049999999999999，rerank=null

  > Successful pregnancy requires coordination of an array of signals and factors from multiple tissues. One such element, liver receptor homolog-1 (Lrh-1), is an orphan nuclear receptor that regulates metabolism and hormone synthesis. It is strongly expressed in granulosa cells of ovarian follicles and in the corpus luteum of rodents and humans. Germline ablation of Nr5a2 (also called Lrh-1), the gene coding for Lrh-1, in mice is embryonically lethal at gastrulation. Depletion of Lrh-1 in the ovarian follicle shows that it regulates genes required for both steroid synthesis and ovulation. To stud…

## queryId=887

问题：Only a minority of cells survive development after differentiation into stress-resistant spores.

原分类：`persistent_miss`

Gold文档：

- `18855191` Exploitative and Hierarchical Antagonism in a Cooperative Bacterium

  > Social organisms that cooperate with some members of their own species, such as close relatives, may fail to cooperate with other genotypes of the same species. Such noncooperation may take the form of outright antagonism or social exploitation. Myxococcus xanthus is a highly social prokaryote that cooperatively develops into spore-bearing, multicellular fruiting bodies in response to starvation. Here we have characterized the nature of social interactions among nine developmentally proficient strains of M. xanthus isolated from spatially distant locations. Strains were competed against one an…

| 变体 | 首个覆盖损失 | 首个完全损失 | 最终排名复现 | Rerank同请求效果 |
|---|---|---|---:|---|
| dense | fusion/FUSION_THRESHOLD_OR_TOPK_LOSS | fusion/FUSION_THRESHOLD_OR_TOPK_LOSS | 是 | 不适用 |
| sparse | sparse_raw/SPARSE_RAW_TOPK_MISS | sparse_raw/SPARSE_RAW_TOPK_MISS | 是 | 不适用 |
| hybrid_rrf | fusion/FUSION_THRESHOLD_OR_TOPK_LOSS | fusion/FUSION_THRESHOLD_OR_TOPK_LOSS | 是 | 不适用 |
| hybrid_rrf_rerank | fusion/FUSION_THRESHOLD_OR_TOPK_LOSS | fusion/FUSION_THRESHOLD_OR_TOPK_LOSS | 是 | RERANK_NEUTRAL (MRR Δ=0.000000) |

重点失败变体：`dense`。首个内部失效结论：fusion/FUSION_THRESHOLD_OR_TOPK_LOSS。该结论级别为阶段轨迹派生，不外推为模型根因。

重点变体Gold逐阶段路径：

| 阶段 | Gold仍存在 | Gold已缺失 | Gold最佳名次 |
|---|---|---|---:|
| dense_raw | 18855191 |  | 57 |
| fusion |  | 18855191 | - |
| candidate_filter |  | 18855191 | - |
| pre_assembly |  | 18855191 | - |
| context_budget |  | 18855191 | - |

`dense`在`fusion/FUSION_THRESHOLD_OR_TOPK_LOSS`阶段排名靠前的非Gold文档：

- sourceStage=fusion rank=1 `4942718` High-Throughput Genetic Screens Identify a Large and Diverse Collection of New Sporulation Genes in Bacillus subtilis；dense=0.8016154，sparse=null，fusion=0.9008077，rerank=null

  > The differentiation of the bacterium Bacillus subtilis into a dormant spore is among the most well-characterized developmental pathways in biology. Classical genetic screens performed over the past half century identified scores of factors involved in every step of this morphological process. More recently, transcriptional profiling uncovered additional sporulation-induced genes required for successful spore development. Here, we used transposon-sequencing (Tn-seq) to assess whether there were any sporulation genes left to be discovered. Our screen identified 133 out of the 148 genes with know…

- sourceStage=fusion rank=2 `4381486` Haematopoietic stem cells do not asymmetrically segregate chromosomes or retain BrdU；dense=0.79931295，sparse=null，fusion=0.899656475，rerank=null

  > Stem cells are proposed to segregate chromosomes asymmetrically during self-renewing divisions so that older (‘immortal’) DNA strands are retained in daughter stem cells whereas newly synthesized strands segregate to differentiating cells. Stem cells are also proposed to retain DNA labels, such as 5-bromo-2-deoxyuridine (BrdU), either because they segregate chromosomes asymmetrically or because they divide slowly. However, the purity of stem cells among BrdU-label-retaining cells has not been documented in any tissue, and the ‘immortal strand hypothesis’ has not been tested in a system with de…

- sourceStage=fusion rank=3 `9559146` Senescent Cells, Tumor Suppression, and Organismal Aging: Good Citizens, Bad Neighbors；dense=0.79626524，sparse=null，fusion=0.8981326199999999，rerank=null

  > Cells from organisms with renewable tissues can permanently withdraw from the cell cycle in response to diverse stress, including dysfunctional telomeres, DNA damage, strong mitogenic signals, and disrupted chromatin. This response, termed cellular senescence, is controlled by the p53 and RB tumor suppressor proteins and constitutes a potent anticancer mechanism. Nonetheless, senescent cells acquire phenotypic changes that may contribute to aging and certain age-related diseases, including late-life cancer. Thus, the senescence response may be antagonistically pleiotropic, promoting early-life…

`sparse`在`sparse_raw/SPARSE_RAW_TOPK_MISS`阶段排名靠前的非Gold文档：

- sourceStage=sparse_raw rank=1 `33872649` Secondary aerosolization of viable Bacillus anthracis spores in a contaminated US Senate Office.；dense=null，sparse=0.28155342，fusion=null，rerank=null

  > CONTEXT Bioterrorist attacks involving letters and mail-handling systems in Washington, DC, resulted in Bacillus anthracis (anthrax) spore contamination in the Hart Senate Office Building and other facilities in the US Capitol's vicinity. OBJECTIVE To provide information about the nature and extent of indoor secondary aerosolization of B anthracis spores. DESIGN Stationary and personal air samples, surface dust, and swab samples were collected under semiquiescent (minimal activities) and then simulated active office conditions to estimate secondary aerosolization of B anthracis spores. Nominal…

- sourceStage=sparse_raw rank=2 `3863543` Mesenchymal Inflammation Drives Genotoxic Stress in Hematopoietic Stem Cells and Predicts Disease Evolution in Human Pre-leukemia.；dense=null，sparse=0.267903，fusion=null，rerank=null

  > Mesenchymal niche cells may drive tissue failure and malignant transformation in the hematopoietic system, but the underlying molecular mechanisms and relevance to human disease remain poorly defined. Here, we show that perturbation of mesenchymal cells in a mouse model of the pre-leukemic disorder Shwachman-Diamond syndrome (SDS) induces mitochondrial dysfunction, oxidative stress, and activation of DNA damage responses in hematopoietic stem and progenitor cells. Massive parallel RNA sequencing of highly purified mesenchymal cells in the SDS mouse model and a range of human pre-leukemic syndr…

- sourceStage=sparse_raw rank=3 `123859` Tracking the fate of glomerular epithelial cells in vivo using serial multiphoton imaging in novel mouse models with fluorescent lineage tags；dense=null，sparse=0.25944448，fusion=null，rerank=null

  > Podocytes are critical in the maintenance of a healthy glomerular filter; however, they have been difficult to study in the intact kidney because of technical limitations. Here we report the development of serial multiphoton microscopy (MPM) of the same glomeruli over several days to visualize the motility of podocytes and parietal epithelial cells (PECs) in vivo. In podocin-GFP mice, podocytes formed sporadic multicellular clusters after unilateral ureteral ligation and migrated into the parietal Bowman's capsule. The tracking of single cells in podocin-confetti mice featuring cell-specific e…

`hybrid_rrf`在`fusion/FUSION_THRESHOLD_OR_TOPK_LOSS`阶段排名靠前的非Gold文档：

- sourceStage=fusion rank=1 `2356950` Epigenetic regulation of miR-184 by MBD1 governs neural stem cell proliferation and differentiation.；dense=0.79163957，sparse=0.2434516，fusion=0.8799294312992942，rerank=null

  > Methyl-CpG binding protein 1 (MBD1) regulates gene expression via a DNA methylation-mediated epigenetic mechanism. We have previously demonstrated that MBD1 deficiency impairs adult neural stem/progenitor cell (aNSC) differentiation and neurogenesis, but the underlying mechanism was unclear. Here, we show that MBD1 regulates the expression of several microRNAs in aNSCs and, specifically, that miR-184 is directly repressed by MBD1. High levels of miR-184 promoted proliferation but inhibited differentiation of aNSCs, whereas inhibition of miR-184 rescued the phenotypes associated with MBD1 defic…

- sourceStage=fusion rank=2 `28937856` Stress-dependent regulation of FOXO transcription factors by the SIRT1 deacetylase.；dense=0.78960353，sparse=0.2473342，fusion=0.8714285714285713，rerank=null

  > The Sir2 deacetylase modulates organismal life-span in various species. However, the molecular mechanisms by which Sir2 increases longevity are largely unknown. We show that in mammalian cells, the Sir2 homolog SIRT1 appears to control the cellular response to stress by regulating the FOXO family of Forkhead transcription factors, a family of proteins that function as sensors of the insulin signaling pathway and as regulators of organismal longevity. SIRT1 and the FOXO transcription factor FOXO3 formed a complex in cells in response to oxidative stress, and SIRT1 deacetylated FOXO3 in vitro an…

- sourceStage=fusion rank=3 `18909530` Contractile forces sustain and polarize hematopoiesis from stem and progenitor cells.；dense=0.7939721，sparse=0.23925689，fusion=0.8653346653346653，rerank=null

  > Self-renewal and differentiation of stem cells depend on asymmetric division and polarized motility processes that in other cell types are modulated by nonmuscle myosin-II (MII) forces and matrix mechanics. Here, mass spectrometry-calibrated intracellular flow cytometry of human hematopoiesis reveals MIIB to be a major isoform that is strongly polarized in hematopoietic stem cells and progenitors (HSC/Ps) and thereby downregulated in differentiated cells via asymmetric division. MIIA is constitutive and activated by dephosphorylation during cytokine-triggered differentiation of cells grown on…

`hybrid_rrf_rerank`在`fusion/FUSION_THRESHOLD_OR_TOPK_LOSS`阶段排名靠前的非Gold文档：

- sourceStage=fusion rank=1 `2356950` Epigenetic regulation of miR-184 by MBD1 governs neural stem cell proliferation and differentiation.；dense=0.79163957，sparse=0.2434516，fusion=0.8799294312992942，rerank=null

  > Methyl-CpG binding protein 1 (MBD1) regulates gene expression via a DNA methylation-mediated epigenetic mechanism. We have previously demonstrated that MBD1 deficiency impairs adult neural stem/progenitor cell (aNSC) differentiation and neurogenesis, but the underlying mechanism was unclear. Here, we show that MBD1 regulates the expression of several microRNAs in aNSCs and, specifically, that miR-184 is directly repressed by MBD1. High levels of miR-184 promoted proliferation but inhibited differentiation of aNSCs, whereas inhibition of miR-184 rescued the phenotypes associated with MBD1 defic…

- sourceStage=fusion rank=2 `28937856` Stress-dependent regulation of FOXO transcription factors by the SIRT1 deacetylase.；dense=0.78960353，sparse=0.2473342，fusion=0.8714285714285713，rerank=null

  > The Sir2 deacetylase modulates organismal life-span in various species. However, the molecular mechanisms by which Sir2 increases longevity are largely unknown. We show that in mammalian cells, the Sir2 homolog SIRT1 appears to control the cellular response to stress by regulating the FOXO family of Forkhead transcription factors, a family of proteins that function as sensors of the insulin signaling pathway and as regulators of organismal longevity. SIRT1 and the FOXO transcription factor FOXO3 formed a complex in cells in response to oxidative stress, and SIRT1 deacetylated FOXO3 in vitro an…

- sourceStage=fusion rank=3 `18909530` Contractile forces sustain and polarize hematopoiesis from stem and progenitor cells.；dense=0.7939721，sparse=0.23925689，fusion=0.8653346653346653，rerank=null

  > Self-renewal and differentiation of stem cells depend on asymmetric division and polarized motility processes that in other cell types are modulated by nonmuscle myosin-II (MII) forces and matrix mechanics. Here, mass spectrometry-calibrated intracellular flow cytometry of human hematopoiesis reveals MIIB to be a major isoform that is strongly polarized in hematopoietic stem cells and progenitors (HSC/Ps) and thereby downregulated in differentiated cells via asymmetric division. MIIA is constitutive and activated by dephosphorylation during cytokine-triggered differentiation of cells grown on…

## queryId=1225

问题：The locus rs647161 is associated with colorectal carcinoma.

原分类：`rerank_reorder_gain`

Gold文档：

- `9650982` Genome-wide association analyses in East Asians identify new susceptibility loci for colorectal cancer

  > To identify new genetic factors for colorectal cancer (CRC), we conducted a                 genome-wide association study in east Asians. By analyzing genome-wide data in 2,098                 cases and 5,749 controls, we selected 64 promising SNPs for replication in an                 independent set of samples, including up to 5,358 cases and 5,922 controls. We                 identified four SNPs with association P values of 8.58 ×                     10(-7) to 3.77 × 10(-10)                 in the combined analysis of all east Asian samples. Three of the four were                 replicate…

| 变体 | 首个覆盖损失 | 首个完全损失 | 最终排名复现 | Rerank同请求效果 |
|---|---|---|---:|---|
| dense | 未观察到Gold损失 | 未观察到Gold损失 | 是 | 不适用 |
| sparse | fusion/FUSION_THRESHOLD_OR_TOPK_LOSS | fusion/FUSION_THRESHOLD_OR_TOPK_LOSS | 是 | 不适用 |
| hybrid_rrf | 未观察到Gold损失 | 未观察到Gold损失 | 是 | 不适用 |
| hybrid_rrf_rerank | 未观察到Gold损失 | 未观察到Gold损失 | 是 | RERANK_ORDER_GAIN (MRR Δ=0.857143) |

重点失败变体：`hybrid_rrf_rerank`。首个内部失效结论：未观察到Gold损失。该结论级别为阶段轨迹派生，不外推为模型根因。

重点变体Gold逐阶段路径：

| 阶段 | Gold仍存在 | Gold已缺失 | Gold最佳名次 |
|---|---|---|---:|
| raw_union | 9650982 |  | - |
| fusion | 9650982 |  | 7 |
| candidate_filter | 9650982 |  | 7 |
| rerank_input | 9650982 |  | 7 |
| rerank_output | 9650982 |  | 1 |
| context_budget | 9650982 |  | 1 |

`sparse`在`fusion/FUSION_THRESHOLD_OR_TOPK_LOSS`阶段排名靠前的非Gold文档：

- sourceStage=fusion rank=1 `56893404` Macrosomia and Hyperinsulinaemic Hypoglycaemia in Patients with Heterozygous Mutations in the HNF4A Gene；dense=null，sparse=0.25498843，fusion=0.2031799050131482，rerank=null

  > Background  Macrosomia is associated with considerable neonatal and maternal morbidity. Factors that predict macrosomia are poorly understood. The increased rate of macrosomia in the offspring of pregnant women with diabetes and in congenital hyperinsulinaemia is mediated by increased foetal insulin secretion. We assessed the in utero and neonatal role of two key regulators of pancreatic insulin secretion by studying birthweight and the incidence of neonatal hypoglycaemia in patients with heterozygous mutations in the maturity-onset diabetes of the young (MODY) genes HNF4A (encoding HNF-4α) an…

- sourceStage=fusion rank=2 `2095573` LDL-cholesterol concentrations: a genome-wide association study；dense=null，sparse=0.25000384，fusion=0.20000245759245025，rerank=null

  > BACKGROUND LDL cholesterol has a causal role in the development of cardiovascular disease. Improved understanding of the biological mechanisms that underlie the metabolism and regulation of LDL cholesterol might help to identify novel therapeutic targets. We therefore did a genome-wide association study of LDL-cholesterol concentrations. METHODS We used genome-wide association data from up to 11,685 participants with measures of circulating LDL-cholesterol concentrations across five studies, including data for 293 461 autosomal single nucleotide polymorphisms (SNPs) with a minor allele frequen…

- sourceStage=fusion rank=3 `23649163` Clinical features and treatment of peristomal pyoderma gangrenosum.；dense=null，sparse=0.2447027，fusion=0.1965952994237098，rerank=null

  > CONTEXT Peristomal pyoderma gangrenosum (PPG), an unusual variant of pyoderma gangrenosum, has been reported almost exclusively in patients with inflammatory bowel disease (IBD) and is frequently misdiagnosed. OBJECTIVE To better characterize the clinical manifestations, diagnosis, and management of PPG. DESIGN, SETTING, AND PATIENTS Retrospective analysis of 7 patients with PPG observed in a university-affiliated community setting between 1988 and December 1999. MAIN OUTCOME MEASURES Clinical and histopathologic features, associated disorders, and microbiologic findings. RESULTS Two patients…

## queryId=768

问题：Mercaptopurine is anabolized into the inactive methylmercaptopurine by thiopurine methyltrasnferase (TPMT).

原分类：`rerank_reorder_gain`

Gold文档：

- `6421792` Activating mutations in the NT5C2 nucleotidase gene drive chemotherapy resistance in relapsed ALL

  > Acute lymphoblastic leukemia (ALL) is an aggressive hematological tumor resulting from the malignant transformation of lymphoid progenitors. Despite intensive chemotherapy, 20% of pediatric patients and over 50% of adult patients with ALL do not achieve a complete remission or relapse after intensified chemotherapy, making disease relapse and resistance to therapy the most substantial challenge in the treatment of this disease. Using whole-exome sequencing, we identify mutations in the cytosolic 5'-nucleotidase II gene (NT5C2), which encodes a 5'-nucleotidase enzyme that is responsible for the…

| 变体 | 首个覆盖损失 | 首个完全损失 | 最终排名复现 | Rerank同请求效果 |
|---|---|---|---:|---|
| dense | 未观察到Gold损失 | 未观察到Gold损失 | 是 | 不适用 |
| sparse | fusion/FUSION_THRESHOLD_OR_TOPK_LOSS | fusion/FUSION_THRESHOLD_OR_TOPK_LOSS | 是 | 不适用 |
| hybrid_rrf | 未观察到Gold损失 | 未观察到Gold损失 | 是 | 不适用 |
| hybrid_rrf_rerank | 未观察到Gold损失 | 未观察到Gold损失 | 是 | RERANK_ORDER_GAIN (MRR Δ=0.833333) |

重点失败变体：`hybrid_rrf_rerank`。首个内部失效结论：未观察到Gold损失。该结论级别为阶段轨迹派生，不外推为模型根因。

重点变体Gold逐阶段路径：

| 阶段 | Gold仍存在 | Gold已缺失 | Gold最佳名次 |
|---|---|---|---:|
| raw_union | 6421792 |  | - |
| fusion | 6421792 |  | 6 |
| candidate_filter | 6421792 |  | 6 |
| rerank_input | 6421792 |  | 6 |
| rerank_output | 6421792 |  | 1 |
| context_budget | 6421792 |  | 1 |

`sparse`在`fusion/FUSION_THRESHOLD_OR_TOPK_LOSS`阶段排名靠前的非Gold文档：

- sourceStage=fusion rank=1 `20381484` GAPDH Mediates Nitrosylation of Nuclear Proteins；dense=null，sparse=0.2698819，fusion=0.21252519624068977，rerank=null

  > S-nitrosylation of proteins by nitric oxide is a major mode of signalling in cells. S-nitrosylation can mediate the regulation of a range of proteins, including prominent nuclear proteins, such as HDAC2 (ref. 2) and PARP1 (ref. 3). The high reactivity of the nitric oxide group with protein thiols, but the selective nature of nitrosylation within the cell, implies the existence of targeting mechanisms. Specificity of nitric oxide signalling is often achieved by the binding of nitric oxide synthase (NOS) to target proteins, either directly or through scaffolding proteins such as PSD-95 (ref. 5)…

- sourceStage=fusion rank=2 `306006` The stimulatory potency of T cell antigens is influenced by the formation of the immunological synapse.；dense=null，sparse=0.26636645，fusion=0.21033915577911905，rerank=null

  > T cell activation is predicated on the interaction between the T cell receptor and peptide-major histocompatibility (pMHC) ligands. The factors that determine the stimulatory potency of a pMHC molecule remain unclear. We describe results showing that a peptide exhibiting many hallmarks of a weak agonist stimulates T cells to proliferate more than the wild-type agonist ligand. An in silico approach suggested that the inability to form the central supramolecular activation cluster (cSMAC) could underlie the increased proliferation. This conclusion was supported by experiments that showed that en…

- sourceStage=fusion rank=3 `21366394` CX3CR1 is required for airway inflammation by promoting T helper cell survival and maintenance in inflamed lung；dense=null，sparse=0.25598264，fusion=0.20381065139562754，rerank=null

  > Allergic asthma is a T helper type 2 (T(H)2)-dominated disease of the lung. In people with asthma, a fraction of CD4(+) T cells express the CX3CL1 receptor, CX3CR1, and CX3CL1 expression is increased in airway smooth muscle, lung endothelium and epithelium upon allergen challenge. Here we found that untreated CX3CR1-deficient mice or wild-type (WT) mice treated with CX3CR1-blocking reagents show reduced lung disease upon allergen sensitization and challenge. Transfer of WT CD4(+) T cells into CX3CR1-deficient mice restored the cardinal features of asthma, and CX3CR1-blocking reagents prevented…

## queryId=1278

问题：The treatment of cancer patients with co-IR blockade does not cause any adverse autoimmune events.

原分类：`rerank_reorder_gain`

Gold文档：

- `11335781` Is autoimmunity the Achilles' heel of cancer immunotherapy?

  > The emergence of immuno-oncology as the first broadly successful strategy for metastatic cancer will require clinicians to integrate this new pillar of medicine with chemotherapy, radiation, and targeted small-molecule compounds. Of equal importance is gaining an understanding of the limitations and toxicities of immunotherapy. Immunotherapy was initially perceived to be a relatively less toxic approach to cancer treatment than other available therapies—and surely it is, when compared to those. However, as the use of immunotherapy becomes more common, especially as first- and second-line treat…

| 变体 | 首个覆盖损失 | 首个完全损失 | 最终排名复现 | Rerank同请求效果 |
|---|---|---|---:|---|
| dense | 未观察到Gold损失 | 未观察到Gold损失 | 是 | 不适用 |
| sparse | fusion/FUSION_THRESHOLD_OR_TOPK_LOSS | fusion/FUSION_THRESHOLD_OR_TOPK_LOSS | 是 | 不适用 |
| hybrid_rrf | 未观察到Gold损失 | 未观察到Gold损失 | 是 | 不适用 |
| hybrid_rrf_rerank | 未观察到Gold损失 | 未观察到Gold损失 | 是 | RERANK_ORDER_GAIN (MRR Δ=0.800000) |

重点失败变体：`hybrid_rrf_rerank`。首个内部失效结论：未观察到Gold损失。该结论级别为阶段轨迹派生，不外推为模型根因。

重点变体Gold逐阶段路径：

| 阶段 | Gold仍存在 | Gold已缺失 | Gold最佳名次 |
|---|---|---|---:|
| raw_union | 11335781 |  | - |
| fusion | 11335781 |  | 5 |
| candidate_filter | 11335781 |  | 5 |
| rerank_input | 11335781 |  | 5 |
| rerank_output | 11335781 |  | 1 |
| context_budget | 11335781 |  | 1 |

`sparse`在`fusion/FUSION_THRESHOLD_OR_TOPK_LOSS`阶段排名靠前的非Gold文档：

- sourceStage=fusion rank=1 `15476777` Chemotherapy options in elderly and frail patients with metastatic colorectal cancer (MRC FOCUS2): an open-label, randomised factorial trial；dense=null，sparse=0.31442168，fusion=0.23920914025094292，rerank=null

  > BACKGROUND Elderly and frail patients with cancer, although often treated with chemotherapy, are under-represented in clinical trials. We designed FOCUS2 to investigate reduced-dose chemotherapy options and to seek objective predictors of outcome in frail patients with advanced colorectal cancer. METHODS We undertook an open, 2 × 2 factorial trial in 61 UK centres for patients with previously untreated advanced colorectal cancer who were considered unfit for full-dose chemotherapy. After comprehensive health assessment (CHA), patients were randomly assigned by minimisation to: 48-h intravenous…

- sourceStage=fusion rank=2 `18340282` Gene–environment interactions in 7610 women with breast cancer: prospective evidence from the Million Women Study；dense=null，sparse=0.2909567，fusion=0.22538068085474905，rerank=null

  > BACKGROUND Information is scarce about the combined effects on breast cancer incidence of low-penetrance genetic susceptibility polymorphisms and environmental factors (reproductive, behavioural, and anthropometric risk factors for breast cancer). To test for evidence of gene-environment interactions, we compared genotypic relative risks for breast cancer across the other risk factors in a large UK prospective study. METHODS We tested gene-environment interactions in 7610 women who developed breast cancer and 10 196 controls without the disease, studying the effects of 12 polymorphisms (FGFR2-…

- sourceStage=fusion rank=3 `23649163` Clinical features and treatment of peristomal pyoderma gangrenosum.；dense=null，sparse=0.27712977，fusion=0.21699421351676737，rerank=null

  > CONTEXT Peristomal pyoderma gangrenosum (PPG), an unusual variant of pyoderma gangrenosum, has been reported almost exclusively in patients with inflammatory bowel disease (IBD) and is frequently misdiagnosed. OBJECTIVE To better characterize the clinical manifestations, diagnosis, and management of PPG. DESIGN, SETTING, AND PATIENTS Retrospective analysis of 7 patients with PPG observed in a university-affiliated community setting between 1988 and December 1999. MAIN OUTCOME MEASURES Clinical and histopathologic features, associated disorders, and microbiologic findings. RESULTS Two patients…

## queryId=198

问题：CCL19 is absent within dLNs.

原分类：`rerank_reorder_gain`

Gold文档：

- `2177022` Immobilized chemokine fields and soluble chemokine gradients cooperatively shape migration patterns of dendritic cells.

  > Chemokines orchestrate immune cell trafficking by eliciting either directed or random migration and by activating integrins in order to induce cell adhesion. Analyzing dendritic cell (DC) migration, we showed that these distinct cellular responses depended on the mode of chemokine presentation within tissues. The surface-immobilized form of the chemokine CCL21, the heparan sulfate-anchoring ligand of the CC-chemokine receptor 7 (CCR7), caused random movement of DCs that was confined to the chemokine-presenting surface because it triggered integrin-mediated adhesion. Upon direct contact with CC…

| 变体 | 首个覆盖损失 | 首个完全损失 | 最终排名复现 | Rerank同请求效果 |
|---|---|---|---:|---|
| dense | 未观察到Gold损失 | 未观察到Gold损失 | 是 | 不适用 |
| sparse | fusion/FUSION_THRESHOLD_OR_TOPK_LOSS | fusion/FUSION_THRESHOLD_OR_TOPK_LOSS | 是 | 不适用 |
| hybrid_rrf | 未观察到Gold损失 | 未观察到Gold损失 | 是 | 不适用 |
| hybrid_rrf_rerank | 未观察到Gold损失 | 未观察到Gold损失 | 是 | RERANK_ORDER_GAIN (MRR Δ=0.750000) |

重点失败变体：`hybrid_rrf_rerank`。首个内部失效结论：未观察到Gold损失。该结论级别为阶段轨迹派生，不外推为模型根因。

重点变体Gold逐阶段路径：

| 阶段 | Gold仍存在 | Gold已缺失 | Gold最佳名次 |
|---|---|---|---:|
| raw_union | 2177022 |  | - |
| fusion | 2177022 |  | 4 |
| candidate_filter | 2177022 |  | 4 |
| rerank_input | 2177022 |  | 4 |
| rerank_output | 2177022 |  | 1 |
| context_budget | 2177022 |  | 1 |

`sparse`在`fusion/FUSION_THRESHOLD_OR_TOPK_LOSS`阶段排名靠前的非Gold文档：

- sourceStage=fusion rank=1 `5289038` Partitioning regulatory mechanisms of within-host malaria dynamics using the effective propagation number.；dense=null，sparse=0.18845375，fusion=0.15857053755772993，rerank=null

  > Immune clearance and resource limitation (via red blood cell depletion) shape the peaks and troughs of malaria parasitemia, which in turn affect disease severity and transmission. Quantitatively partitioning the relative roles of these effects through time is challenging. Using data from rodent malaria, we estimated the effective propagation number, which reflects the relative importance of contrasting within-host control mechanisms through time and is sensitive to the inoculating parasite dose. Our analysis showed that the capacity of innate responses to restrict initial parasite growth satur…

- sourceStage=fusion rank=2 `4456756` Autocrine BDNF–TrkB signalling within a single dendritic spine；dense=null，sparse=0.1823465，fusion=0.1542242481370732，rerank=null

  > Brain-derived neurotrophic factor (BDNF) and its receptor TrkB are crucial for many forms of neuronal plasticity, including structural long-term potentiation (sLTP), which is a correlate of an animal’s learning. However, it is unknown whether BDNF release and TrkB activation occur during sLTP, and if so, when and where. Here, using a fluorescence resonance energy transfer-based sensor for TrkB and two-photon fluorescence lifetime imaging microscopy, we monitor TrkB activity in single dendritic spines of CA1 pyramidal neurons in cultured murine hippocampal slices. In response to sLTP induction,…

- sourceStage=fusion rank=3 `11369420` Tetraspanin 3 Is Required for the Development and Propagation of Acute Myelogenous Leukemia.；dense=null，sparse=0.18150328，fusion=0.15362063150599123，rerank=null

  > Acute Myelogenous Leukemia (AML) is an aggressive cancer that strikes both adults and children and is frequently resistant to therapy. Thus, identifying signals needed for AML propagation is a critical step toward developing new approaches for treating this disease. Here, we show that Tetraspanin 3 is a target of the RNA binding protein Musashi 2, which plays a key role in AML. We generated Tspan3 knockout mice that were born without overt defects. However, Tspan3 deletion impaired leukemia stem cell self-renewal and disease propagation and markedly improved survival in mouse models of AML. Ad…

## queryId=1303

问题：Tirasemtiv has no effect on fast-twitch muscle.

原分类：`rerank_reorder_harm`

Gold文档：

- `12631697` Activation of fast skeletal muscle troponin as a potential therapeutic approach for treating neuromuscular diseases

  > Limited neural input results in muscle weakness in neuromuscular disease because of a reduction in the density of muscle innervation, the rate of neuromuscular junction activation or the efficiency of synaptic transmission. We developed a small-molecule fast-skeletal-troponin activator, CK-2017357, as a means to increase muscle strength by amplifying the response of muscle when neural input is otherwise diminished secondary to neuromuscular disease. Binding selectively to the fast-skeletal-troponin complex, CK-2017357 slows the rate of calcium release from troponin C and sensitizes muscle to c…

| 变体 | 首个覆盖损失 | 首个完全损失 | 最终排名复现 | Rerank同请求效果 |
|---|---|---|---:|---|
| dense | 未观察到Gold损失 | 未观察到Gold损失 | 是 | 不适用 |
| sparse | 未观察到Gold损失 | 未观察到Gold损失 | 是 | 不适用 |
| hybrid_rrf | 未观察到Gold损失 | 未观察到Gold损失 | 是 | 不适用 |
| hybrid_rrf_rerank | 未观察到Gold损失 | 未观察到Gold损失 | 是 | RERANK_ORDER_HARM (MRR Δ=-0.750000) |

重点失败变体：`hybrid_rrf_rerank`。首个内部失效结论：未观察到Gold损失。该结论级别为阶段轨迹派生，不外推为模型根因。

重点变体Gold逐阶段路径：

| 阶段 | Gold仍存在 | Gold已缺失 | Gold最佳名次 |
|---|---|---|---:|
| raw_union | 12631697 |  | - |
| fusion | 12631697 |  | 1 |
| candidate_filter | 12631697 |  | 1 |
| rerank_input | 12631697 |  | 1 |
| rerank_output | 12631697 |  | 4 |
| context_budget | 12631697 |  | 4 |

## queryId=294

问题：Crossover hot spots are not found within gene promoters in Saccharomyces cerevisiae.

原分类：`rerank_reorder_harm`

Gold文档：

- `10874408` Mapping Meiotic Single-Strand DNA Reveals a New Landscape of DNA Double-Strand Breaks in Saccharomyces cerevisiae

  > DNA double-strand breaks (DSBs), which are formed by the Spo11 protein, initiate meiotic recombination. Previous DSB-mapping studies have used rad50S or sae2Δ mutants, which are defective in break processing, to accumulate Spo11-linked DSBs, and report large (≥ 50 kb) “DSB-hot” regions that are separated by “DSB-cold” domains of similar size. Substantial recombination occurs in some DSB-cold regions, suggesting that DSB patterns are not normal in rad50S or sae2Δ mutants. We therefore developed a novel method to map genome-wide, single-strand DNA (ssDNA)–associated DSBs that accumulate in proce…

| 变体 | 首个覆盖损失 | 首个完全损失 | 最终排名复现 | Rerank同请求效果 |
|---|---|---|---:|---|
| dense | 未观察到Gold损失 | 未观察到Gold损失 | 是 | 不适用 |
| sparse | 未观察到Gold损失 | 未观察到Gold损失 | 是 | 不适用 |
| hybrid_rrf | 未观察到Gold损失 | 未观察到Gold损失 | 是 | 不适用 |
| hybrid_rrf_rerank | 未观察到Gold损失 | 未观察到Gold损失 | 是 | RERANK_ORDER_HARM (MRR Δ=-0.666667) |

重点失败变体：`hybrid_rrf_rerank`。首个内部失效结论：未观察到Gold损失。该结论级别为阶段轨迹派生，不外推为模型根因。

重点变体Gold逐阶段路径：

| 阶段 | Gold仍存在 | Gold已缺失 | Gold最佳名次 |
|---|---|---|---:|
| raw_union | 10874408 |  | - |
| fusion | 10874408 |  | 1 |
| candidate_filter | 10874408 |  | 1 |
| rerank_input | 10874408 |  | 1 |
| rerank_output | 10874408 |  | 3 |
| context_budget | 10874408 |  | 3 |

## queryId=421

问题：Flexible molecules experience greater steric hindrance in the tumor microenviroment than rigid molecules.

原分类：`rerank_reorder_harm`

Gold文档：

- `11172205` Quantum dots spectrally distinguish multiple species within the tumor milieu in vivo

  > A solid tumor is an organ composed of cancer and host cells embedded in an extracellular matrix and nourished by blood vessels. A prerequisite to understanding tumor pathophysiology is the ability to distinguish and monitor each component in dynamic studies. Standard fluorophores hamper simultaneous intravital imaging of these components. Here, we used multiphoton microscopy techniques and transgenic mice that expressed green fluorescent protein, and combined them with the use of quantum dot preparations. We show that these fluorescent semiconductor nanocrystals can be customized to concurrent…

| 变体 | 首个覆盖损失 | 首个完全损失 | 最终排名复现 | Rerank同请求效果 |
|---|---|---|---:|---|
| dense | 未观察到Gold损失 | 未观察到Gold损失 | 是 | 不适用 |
| sparse | 未观察到Gold损失 | 未观察到Gold损失 | 是 | 不适用 |
| hybrid_rrf | 未观察到Gold损失 | 未观察到Gold损失 | 是 | 不适用 |
| hybrid_rrf_rerank | 未观察到Gold损失 | 未观察到Gold损失 | 是 | RERANK_ORDER_HARM (MRR Δ=-0.666667) |

重点失败变体：`hybrid_rrf_rerank`。首个内部失效结论：未观察到Gold损失。该结论级别为阶段轨迹派生，不外推为模型根因。

重点变体Gold逐阶段路径：

| 阶段 | Gold仍存在 | Gold已缺失 | Gold最佳名次 |
|---|---|---|---:|
| raw_union | 11172205 |  | - |
| fusion | 11172205 |  | 1 |
| candidate_filter | 11172205 |  | 1 |
| rerank_input | 11172205 |  | 1 |
| rerank_output | 11172205 |  | 3 |
| context_budget | 11172205 |  | 3 |

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
| hybrid_rrf_rerank | 未观察到Gold损失 | 未观察到Gold损失 | 是 | RERANK_ORDER_HARM (MRR Δ=-0.750000) |

重点失败变体：`hybrid_rrf_rerank`。首个内部失效结论：未观察到Gold损失。该结论级别为阶段轨迹派生，不外推为模型根因。

重点变体Gold逐阶段路径：

| 阶段 | Gold仍存在 | Gold已缺失 | Gold最佳名次 |
|---|---|---|---:|
| raw_union | 12956194 |  | - |
| fusion | 12956194 |  | 1 |
| candidate_filter | 12956194 |  | 1 |
| rerank_input | 12956194 |  | 1 |
| rerank_output | 12956194 |  | 4 |
| context_budget | 12956194 |  | 4 |

## queryId=213

问题：CRP is not predictive of postoperative mortality following Coronary Artery Bypass Graft (CABG) surgery.

原分类：`rerank_reorder_harm`

Gold文档：

- `13625993` Assessing the cost effectiveness of using prognostic biomarkers with decision models: case study in prioritising patients waiting for coronary artery surgery

  > OBJECTIVE To determine the effectiveness and cost effectiveness of using information from circulating biomarkers to inform the prioritisation process of patients with stable angina awaiting coronary artery bypass graft surgery. DESIGN Decision analytical model comparing four prioritisation strategies without biomarkers (no formal prioritisation, two urgency scores, and a risk score) and three strategies based on a risk score using biomarkers: a routinely assessed biomarker (estimated glomerular filtration rate), a novel biomarker (C reactive protein), or both. The order in which to perform cor…

| 变体 | 首个覆盖损失 | 首个完全损失 | 最终排名复现 | Rerank同请求效果 |
|---|---|---|---:|---|
| dense | 未观察到Gold损失 | 未观察到Gold损失 | 是 | 不适用 |
| sparse | 未观察到Gold损失 | 未观察到Gold损失 | 是 | 不适用 |
| hybrid_rrf | 未观察到Gold损失 | 未观察到Gold损失 | 是 | 不适用 |
| hybrid_rrf_rerank | 未观察到Gold损失 | 未观察到Gold损失 | 是 | RERANK_ORDER_HARM (MRR Δ=-0.500000) |

重点失败变体：`hybrid_rrf_rerank`。首个内部失效结论：未观察到Gold损失。该结论级别为阶段轨迹派生，不外推为模型根因。

重点变体Gold逐阶段路径：

| 阶段 | Gold仍存在 | Gold已缺失 | Gold最佳名次 |
|---|---|---|---:|
| raw_union | 13625993 |  | - |
| fusion | 13625993 |  | 1 |
| candidate_filter | 13625993 |  | 1 |
| rerank_input | 13625993 |  | 1 |
| rerank_output | 13625993 |  | 2 |
| context_budget | 13625993 |  | 2 |

## 输入SHA-256

- failureReport: `624d0b7ce9e01f96989d6c390296a3066691b64b75ac53eaaf2a90ec109306fc`
- diagnostics: `9b73f0e5e5bf20d27f087855d8820e699c6e9931e9fad752f4ed89f14189c1fe`
- diagnosticManifest: `9d5b2da84f6a379b46e9f853d46f7b951aa910d1ce7a9eb26631a3d7f2098a17`
- qrels: `2a808171a79832d5798afb879c2d912f5c8863b09c6427fe454f20dc2a025f73`
- documents: `7e1479ca549e3e48dd442b03770e88f160ef90334a8e18f09cfa6349fee24e08`
- documentMap: `8a93c2134c689d3fd78d90ddee9414b3a08bc43e20b56ef55d781ea9f61ef17b`
