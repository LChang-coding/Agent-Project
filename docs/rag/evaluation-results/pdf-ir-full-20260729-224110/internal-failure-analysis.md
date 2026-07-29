# SciFact RAG内部阶段失败证据报告

真实诊断查询：21；请求记录：84；旧run最终排名精确复现：84/84。

## 证据边界

- firstObserved字段表示阶段轨迹中首个可观测损失，不等同于模型或索引的不可反驳根因。
- fusion实现将score threshold与TopK合并，轨迹只能定位到FUSION_THRESHOLD_OR_TOPK_LOSS。
- context outcome可直接证明淘汰分支，但未采集具体Token差额与扩展上下文组成。
- 当前报告只支持每条请求恰好一个binding/profile；多binding局部排名不会被混成全局排名。
- Hybrid raw union只表达Dense/Sparse两路覆盖并集，不提供跨分支全局名次语义。
- 每个变体记录并校验其binding/profile单作用域；四个消融target的binding/profile本来不同，跨变体指纹比较会归一化这两个target局部ID。
- 跨变体可比性校验共享知识库/文档/版本/generation/chunk、outcome与分数容差；未采集完整模型/index冻结指纹。

## 内部失效总账

84条变体轨迹的首个完全损失分布：

| 分类码 | 变体轨迹数 |
|---|---:|
| FUSION_THRESHOLD_OR_TOPK_LOSS | 30 |
| NONE | 53 |
| SPARSE_RAW_TOPK_MISS | 1 |

同一次Hybrid+Rerank请求内，Rerank输入→输出的排序效果：

| 分类 | 查询数 |
|---|---:|
| RERANK_NEUTRAL | 7 |
| RERANK_ORDER_GAIN | 9 |
| RERANK_ORDER_HARM | 5 |

## queryId=1

问题：0-dimensional biomaterials show inductive properties.

原分类：`dense_miss_hybrid_hit`, `sparse_miss_hybrid_hit`, `rerank_reorder_harm`

Gold文档：

- `31715818` New opportunities: the use of nanotechnologies to manipulate and track stem cells.

  > Nanotechnologies are emerging platforms that could be useful in measuring, understanding, and manipulating stem cells. Examples include magnetic nanoparticles and quantum dots for stem cell labeling and in vivo tracking; nanoparticles, carbon nanotubes, and polyplexes for the intracellular delivery of genes/oligonucleotides and protein/peptides; and engineered nanometer-scale scaffolds for stem cell differentiation and transplantation. This review examines the use of nanotechnologies for stem cell tracking, differentiation, and transplantation. We further discuss their utility and the potentia…

| 变体 | 首个覆盖损失 | 首个完全损失 | 最终排名复现 | Rerank同请求效果 |
|---|---|---|---:|---|
| dense | fusion/FUSION_THRESHOLD_OR_TOPK_LOSS | fusion/FUSION_THRESHOLD_OR_TOPK_LOSS | 是 | 不适用 |
| sparse | fusion/FUSION_THRESHOLD_OR_TOPK_LOSS | fusion/FUSION_THRESHOLD_OR_TOPK_LOSS | 是 | 不适用 |
| hybrid_rrf | 未观察到Gold损失 | 未观察到Gold损失 | 是 | 不适用 |
| hybrid_rrf_rerank | 未观察到Gold损失 | 未观察到Gold损失 | 是 | RERANK_ORDER_HARM (MRR Δ=-0.888889) |

重点失败变体：`hybrid_rrf_rerank`。首个内部失效结论：未观察到Gold损失。该结论级别为阶段轨迹派生，不外推为模型根因。

重点变体Gold逐阶段路径：

| 阶段 | Gold仍存在 | Gold已缺失 | Gold最佳名次 |
|---|---|---|---:|
| raw_union | 31715818 |  | - |
| fusion | 31715818 |  | 1 |
| candidate_filter | 31715818 |  | 1 |
| rerank_input | 31715818 |  | 1 |
| rerank_output | 31715818 |  | 9 |
| context_budget | 31715818 |  | 9 |

`dense`在`fusion/FUSION_THRESHOLD_OR_TOPK_LOSS`阶段排名靠前的非Gold文档：

- sourceStage=fusion rank=1 `8290953` Scaffold-based three-dimensional human fibroblast culture provides a structural matrix that supports angiogenesis in infarcted heart tissue.；dense=0.80212593，sparse=null，fusion=0.9010629649999999，rerank=null

  > BACKGROUND We have developed techniques to implant angiogenic patches onto the epicardium over regions of infarcted cardiac tissue to stimulate revascularization of the damaged tissue. These experiments used a scaffold-based 3D human dermal fibroblast culture (3DFC) as an epicardial patch. The 3DFC contains viable cells that secrete angiogenic growth factors and has previously been shown to stimulate angiogenic activity. The hypothesis tested was that a viable 3DFC cardiac patch would stimulate an angiogenic response within an area of infarcted cardiac tissue. METHODS AND RESULTS A coronary oc…

- sourceStage=fusion rank=2 `4388470` Somatic sex identity is cell-autonomous in the chicken；dense=0.7984314，sparse=null，fusion=0.8992157000000001，rerank=null

  > In the mammalian model of sex determination, embryos are considered to be sexually indifferent until the transient action of a sex-determining gene initiates gonadal differentiation. Although this model is thought to apply to all vertebrates, this has yet to be established. Here we have examined three lateral gynandromorph chickens (a rare, naturally occurring phenomenon in which one side of the animal appears male and the other female) to investigate the sex-determining mechanism in birds. These studies demonstrated that gynandromorph birds are genuine male:female chimaeras, and indicated tha…

- sourceStage=fusion rank=3 `12631697` Activation of fast skeletal muscle troponin as a potential therapeutic approach for treating neuromuscular diseases；dense=0.7979694，sparse=null，fusion=0.8989847，rerank=null

  > Limited neural input results in muscle weakness in neuromuscular disease because of a reduction in the density of muscle innervation, the rate of neuromuscular junction activation or the efficiency of synaptic transmission. We developed a small-molecule fast-skeletal-troponin activator, CK-2017357, as a means to increase muscle strength by amplifying the response of muscle when neural input is otherwise diminished secondary to neuromuscular disease. Binding selectively to the fast-skeletal-troponin complex, CK-2017357 slows the rate of calcium release from troponin C and sensitizes muscle to c…

`sparse`在`fusion/FUSION_THRESHOLD_OR_TOPK_LOSS`阶段排名靠前的非Gold文档：

- sourceStage=fusion rank=1 `3441524` Human TRPML1 channel structures in open and closed conformations；dense=null，sparse=0.28171712，fusion=0.21979664280367886，rerank=null

  > Transient receptor potential mucolipin 1 (TRPML1) is a Ca2+-releasing cation channel that mediates the calcium signalling and homeostasis of lysosomes. Mutations in TRPML1 lead to mucolipidosis type IV, a severe lysosomal storage disorder. Here we report two electron cryo-microscopy structures of full-length human TRPML1: a 3.72-Å apo structure at pH 7.0 in the closed state, and a 3.49-Å agonist-bound structure at pH 6.0 in an open state. Several aromatic and hydrophobic residues in pore helix 1, helices S5 and S6, and helix S6 of a neighbouring subunit, form a hydrophobic cavity to house the…

- sourceStage=fusion rank=2 `10582939` Induction therapy with autologous mesenchymal stem cells in living-related kidney transplants: a randomized controlled trial.；dense=null，sparse=0.24668017，fusion=0.19786965088247133，rerank=null

  > CONTEXT Antibody-based induction therapy plus calcineurin inhibitors (CNIs) reduce acute rejection rates in kidney recipients; however, opportunistic infections and toxic CNI effects remain challenging. Reportedly, mesenchymal stem cells (MSCs) have successfully treated graft-vs-host disease. OBJECTIVE To assess autologous MSCs as replacement of antibody induction for patients with end-stage renal disease who undergo ABO-compatible, cross-match-negative kidney transplants from a living-related donor. DESIGN, SETTING, AND PATIENTS One hundred fifty-nine patients were enrolled in this single-sit…

- sourceStage=fusion rank=3 `15663829` Mendelian Randomization Study of B-Type Natriuretic Peptide and Type 2 Diabetes: Evidence of Causal Association from Population Studies；dense=null，sparse=0.24528237，fusion=0.19696927854202256，rerank=null

  > BACKGROUND Genetic and epidemiological evidence suggests an inverse association between B-type natriuretic peptide (BNP) levels in blood and risk of type 2 diabetes (T2D), but the prospective association of BNP with T2D is uncertain, and it is unclear whether the association is confounded. METHODS AND FINDINGS We analysed the association between levels of the N-terminal fragment of pro-BNP (NT-pro-BNP) in blood and risk of incident T2D in a prospective case-cohort study and genotyped the variant rs198389 within the BNP locus in three T2D case-control studies. We combined our results with exist…

## queryId=1363

问题：Venules have a thinner or absent smooth layer compared to arterioles.

原分类：`dense_miss_hybrid_hit`, `sparse_only_success`

Gold文档：

- `8290953` Scaffold-based three-dimensional human fibroblast culture provides a structural matrix that supports angiogenesis in infarcted heart tissue.

  > BACKGROUND We have developed techniques to implant angiogenic patches onto the epicardium over regions of infarcted cardiac tissue to stimulate revascularization of the damaged tissue. These experiments used a scaffold-based 3D human dermal fibroblast culture (3DFC) as an epicardial patch. The 3DFC contains viable cells that secrete angiogenic growth factors and has previously been shown to stimulate angiogenic activity. The hypothesis tested was that a viable 3DFC cardiac patch would stimulate an angiogenic response within an area of infarcted cardiac tissue. METHODS AND RESULTS A coronary oc…

| 变体 | 首个覆盖损失 | 首个完全损失 | 最终排名复现 | Rerank同请求效果 |
|---|---|---|---:|---|
| dense | fusion/FUSION_THRESHOLD_OR_TOPK_LOSS | fusion/FUSION_THRESHOLD_OR_TOPK_LOSS | 是 | 不适用 |
| sparse | 未观察到Gold损失 | 未观察到Gold损失 | 是 | 不适用 |
| hybrid_rrf | 未观察到Gold损失 | 未观察到Gold损失 | 是 | 不适用 |
| hybrid_rrf_rerank | 未观察到Gold损失 | 未观察到Gold损失 | 是 | RERANK_ORDER_GAIN (MRR Δ=0.500000) |

重点失败变体：`dense`。首个内部失效结论：fusion/FUSION_THRESHOLD_OR_TOPK_LOSS。该结论级别为阶段轨迹派生，不外推为模型根因。

重点变体Gold逐阶段路径：

| 阶段 | Gold仍存在 | Gold已缺失 | Gold最佳名次 |
|---|---|---|---:|
| dense_raw | 8290953 |  | 30 |
| fusion |  | 8290953 | - |
| candidate_filter |  | 8290953 | - |
| pre_assembly |  | 8290953 | - |
| context_budget |  | 8290953 | - |

`dense`在`fusion/FUSION_THRESHOLD_OR_TOPK_LOSS`阶段排名靠前的非Gold文档：

- sourceStage=fusion rank=1 `4387784` Structure of the proton-gated urea channel from the gastric pathogen Helicobacter pylori；dense=0.79065096，sparse=null，fusion=0.89532548，rerank=null

  > Half the world's population is chronically infected with Helicobacter pylori, causing gastritis, gastric ulcers and an increased incidence of gastric adenocarcinoma. Its proton-gated inner-membrane urea channel, HpUreI, is essential for survival in the acidic environment of the stomach. The channel is closed at neutral pH and opens at acidic pH to allow the rapid access of urea to cytoplasmic urease. Urease produces NH(3) and CO(2), neutralizing entering protons and thus buffering the periplasm to a pH of roughly 6.1 even in gastric juice at a pH below 2.0. Here we report the structure of HpUr…

- sourceStage=fusion rank=2 `17741440` Netting neutrophils in autoimmune small-vessel vasculitis；dense=0.78882337，sparse=null，fusion=0.894411685，rerank=null

  > Small-vessel vasculitis (SVV) is a chronic autoinflammatory condition linked to antineutrophil cytoplasm autoantibodies (ANCAs). Here we show that chromatin fibers, so-called neutrophil extracellular traps (NETs), are released by ANCA-stimulated neutrophils and contain the targeted autoantigens proteinase-3 (PR3) and myeloperoxidase (MPO). Deposition of NETs in inflamed kidneys and circulating MPO-DNA complexes suggest that NET formation triggers vasculitis and promotes the autoimmune response against neutrophil components in individuals with SVV.

- sourceStage=fusion rank=3 `2425364` Association between maternal serum 25-hydroxyvitamin D level and pregnancy and neonatal outcomes: systematic review and meta-analysis of observational studies.；dense=0.78496766，sparse=null，fusion=0.89248383，rerank=null

  > OBJECTIVE To assess the effect of 25-hydroxyvitamin D (25-OHD) levels on pregnancy outcomes and birth variables. DESIGN Systematic review and meta-analysis. DATA SOURCES Medline (1966 to August 2012), PubMed (2008 to August 2012), Embase (1980 to August 2012), CINAHL (1981 to August 2012), the Cochrane database of systematic reviews, and the Cochrane database of registered clinical trials. STUDY SELECTION Studies reporting on the association between serum 25-OHD levels during pregnancy and the outcomes of interest (pre-eclampsia, gestational diabetes, bacterial vaginosis, caesarean section, sm…

## queryId=502

问题：Healthcare delivery efficiency in crowded delivery centers is impaired by improving structural, logistical, and interpersonal elements.

原分类：`dense_miss_hybrid_hit`

Gold文档：

- `13071728` The HIV Treatment Gap: Estimates of the Financial Resources Needed versus Available for Scale-Up of Antiretroviral Therapy in 97 Countries from 2015 to 2020

  > BACKGROUND The World Health Organization (WHO) released revised guidelines in 2015 recommending that all people living with HIV, regardless of CD4 count, initiate antiretroviral therapy (ART) upon diagnosis. However, few studies have projected the global resources needed for rapid scale-up of ART. Under the Health Policy Project, we conducted modeling analyses for 97 countries to estimate eligibility for and numbers on ART from 2015 to 2020, along with the facility-level financial resources required. We compared the estimated financial requirements to estimated funding available. METHODS AND F…

| 变体 | 首个覆盖损失 | 首个完全损失 | 最终排名复现 | Rerank同请求效果 |
|---|---|---|---:|---|
| dense | fusion/FUSION_THRESHOLD_OR_TOPK_LOSS | fusion/FUSION_THRESHOLD_OR_TOPK_LOSS | 是 | 不适用 |
| sparse | fusion/FUSION_THRESHOLD_OR_TOPK_LOSS | fusion/FUSION_THRESHOLD_OR_TOPK_LOSS | 是 | 不适用 |
| hybrid_rrf | 未观察到Gold损失 | 未观察到Gold损失 | 是 | 不适用 |
| hybrid_rrf_rerank | 未观察到Gold损失 | 未观察到Gold损失 | 是 | RERANK_ORDER_GAIN (MRR Δ=0.800000) |

重点失败变体：`dense`。首个内部失效结论：fusion/FUSION_THRESHOLD_OR_TOPK_LOSS。该结论级别为阶段轨迹派生，不外推为模型根因。

重点变体Gold逐阶段路径：

| 阶段 | Gold仍存在 | Gold已缺失 | Gold最佳名次 |
|---|---|---|---:|
| dense_raw | 13071728 |  | 27 |
| fusion |  | 13071728 | - |
| candidate_filter |  | 13071728 | - |
| pre_assembly |  | 13071728 | - |
| context_budget |  | 13071728 | - |

`dense`在`fusion/FUSION_THRESHOLD_OR_TOPK_LOSS`阶段排名靠前的非Gold文档：

- sourceStage=fusion rank=1 `25649714` Mental health problems of homeless children and families: longitudinal study.；dense=0.7843363，sparse=null，fusion=0.89216815，rerank=null

  > OBJECTIVE To establish the mental health needs of homeless children and families before and after rehousing. DESIGN Cross sectional, longitudinal study. SETTING City of Birmingham. SUBJECTS 58 rehoused families with 103 children aged 2-16 years and 21 comparison families of low socioeconomic status in stable housing, with 54 children. MAIN OUTCOME MEASURES Children's mental health problems and level of communication; mothers' mental health problems and social support one year after rehousing. RESULTS Mental health problems remained significantly higher in rehoused mothers and their children th…

- sourceStage=fusion rank=2 `13625993` Assessing the cost effectiveness of using prognostic biomarkers with decision models: case study in prioritising patients waiting for coronary artery surgery；dense=0.78332853，sparse=null，fusion=0.891664265，rerank=null

  > OBJECTIVE To determine the effectiveness and cost effectiveness of using information from circulating biomarkers to inform the prioritisation process of patients with stable angina awaiting coronary artery bypass graft surgery. DESIGN Decision analytical model comparing four prioritisation strategies without biomarkers (no formal prioritisation, two urgency scores, and a risk score) and three strategies based on a risk score using biomarkers: a routinely assessed biomarker (estimated glomerular filtration rate), a novel biomarker (C reactive protein), or both. The order in which to perform cor…

- sourceStage=fusion rank=3 `13906581` Patient Outcomes with Teaching Versus Nonteaching Healthcare: A Systematic Review；dense=0.7784183，sparse=null，fusion=0.88920915，rerank=null

  > Background  Extensive debate exists in the healthcare community over whether outcomes of medical care at teaching hospitals and other healthcare units are better or worse than those at the respective nonteaching ones. Thus, our goal was to systematically evaluate the evidence pertaining to this question. Methods and Findings  We reviewed all studies that compared teaching versus nonteaching healthcare structures for mortality or any other patient outcome, regardless of health condition. Studies were retrieved from PubMed, contact with experts, and literature cross-referencing. Data were extrac…

`sparse`在`fusion/FUSION_THRESHOLD_OR_TOPK_LOSS`阶段排名靠前的非Gold文档：

- sourceStage=fusion rank=1 `15928989` Liver receptor homolog-1 is essential for pregnancy；dense=null，sparse=0.2654623，fusion=0.20977495734167662，rerank=null

  > Successful pregnancy requires coordination of an array of signals and factors from multiple tissues. One such element, liver receptor homolog-1 (Lrh-1), is an orphan nuclear receptor that regulates metabolism and hormone synthesis. It is strongly expressed in granulosa cells of ovarian follicles and in the corpus luteum of rodents and humans. Germline ablation of Nr5a2 (also called Lrh-1), the gene coding for Lrh-1, in mice is embryonically lethal at gastrulation. Depletion of Lrh-1 in the ovarian follicle shows that it regulates genes required for both steroid synthesis and ovulation. To stud…

- sourceStage=fusion rank=2 `30303335` Control of NFAT Isoform Activation and NFAT-Dependent Gene Expression through Two Coincident and Spatially Segregated Intracellular Ca2+ Signals；dense=null，sparse=0.25758022，fusion=0.20482209874452387，rerank=null

  > Excitation-transcription coupling, linking stimulation at the cell surface to changes in nuclear gene expression, is conserved throughout eukaryotes. How closely related coexpressed transcription factors are differentially activated remains unclear. Here, we show that two Ca2+-dependent transcription factor isoforms, NFAT1 and NFAT4, require distinct sub-cellular InsP3 and Ca2+ signals for physiologically sustained activation. NFAT1 is stimulated by sub-plasmalemmal Ca2+ microdomains, whereas NFAT4 additionally requires Ca2+ mobilization from the inner nuclear envelope by nuclear InsP3 recepto…

- sourceStage=fusion rank=3 `31715818` New opportunities: the use of nanotechnologies to manipulate and track stem cells.；dense=null，sparse=0.25711432，fusion=0.20452739731737368，rerank=null

  > Nanotechnologies are emerging platforms that could be useful in measuring, understanding, and manipulating stem cells. Examples include magnetic nanoparticles and quantum dots for stem cell labeling and in vivo tracking; nanoparticles, carbon nanotubes, and polyplexes for the intracellular delivery of genes/oligonucleotides and protein/peptides; and engineered nanometer-scale scaffolds for stem cell differentiation and transplantation. This review examines the use of nanotechnologies for stem cell tracking, differentiation, and transplantation. We further discuss their utility and the potentia…

## queryId=517

问题：High levels of copeptin decrease risk of diabetes.

原分类：`dense_miss_hybrid_hit`, `sparse_only_success`, `rerank_reorder_gain`

Gold文档：

- `15663829` Mendelian Randomization Study of B-Type Natriuretic Peptide and Type 2 Diabetes: Evidence of Causal Association from Population Studies

  > BACKGROUND Genetic and epidemiological evidence suggests an inverse association between B-type natriuretic peptide (BNP) levels in blood and risk of type 2 diabetes (T2D), but the prospective association of BNP with T2D is uncertain, and it is unclear whether the association is confounded. METHODS AND FINDINGS We analysed the association between levels of the N-terminal fragment of pro-BNP (NT-pro-BNP) in blood and risk of incident T2D in a prospective case-cohort study and genotyped the variant rs198389 within the BNP locus in three T2D case-control studies. We combined our results with exist…

| 变体 | 首个覆盖损失 | 首个完全损失 | 最终排名复现 | Rerank同请求效果 |
|---|---|---|---:|---|
| dense | fusion/FUSION_THRESHOLD_OR_TOPK_LOSS | fusion/FUSION_THRESHOLD_OR_TOPK_LOSS | 是 | 不适用 |
| sparse | 未观察到Gold损失 | 未观察到Gold损失 | 是 | 不适用 |
| hybrid_rrf | 未观察到Gold损失 | 未观察到Gold损失 | 是 | 不适用 |
| hybrid_rrf_rerank | 未观察到Gold损失 | 未观察到Gold损失 | 是 | RERANK_ORDER_GAIN (MRR Δ=0.875000) |

重点失败变体：`hybrid_rrf_rerank`。首个内部失效结论：未观察到Gold损失。该结论级别为阶段轨迹派生，不外推为模型根因。

重点变体Gold逐阶段路径：

| 阶段 | Gold仍存在 | Gold已缺失 | Gold最佳名次 |
|---|---|---|---:|
| raw_union | 15663829 |  | - |
| fusion | 15663829 |  | 8 |
| candidate_filter | 15663829 |  | 8 |
| rerank_input | 15663829 |  | 8 |
| rerank_output | 15663829 |  | 1 |
| context_budget | 15663829 |  | 1 |

`dense`在`fusion/FUSION_THRESHOLD_OR_TOPK_LOSS`阶段排名靠前的非Gold文档：

- sourceStage=fusion rank=1 `970012` Cold Exposure Promotes Atherosclerotic Plaque Growth and Instability via UCP1-Dependent Lipolysis；dense=0.82040405，sparse=null，fusion=0.910202025，rerank=null

  > Molecular mechanisms underlying the cold-associated high cardiovascular risk remain unknown. Here, we show that the cold-triggered food-intake-independent lipolysis significantly increased plasma levels of small low-density lipoprotein (LDL) remnants, leading to accelerated development of atherosclerotic lesions in mice. In two genetic mouse knockout models (apolipoprotein E(-/-) [ApoE(-/-)] and LDL receptor(-/-) [Ldlr(-/-)] mice), persistent cold exposure stimulated atherosclerotic plaque growth by increasing lipid deposition. Furthermore, marked increase of inflammatory cells and plaque-asso…

- sourceStage=fusion rank=2 `13619127` Diabetes treatments and risk of amputation, blindness, severe kidney failure, hyperglycaemia, and hypoglycaemia: open cohort study in primary care；dense=0.8136003，sparse=null，fusion=0.90680015，rerank=null

  > OBJECTIVE To assess the risks of amputation, blindness, severe kidney failure, hyperglycaemia, and hypoglycaemia in patients with type 2 diabetes associated with prescribed diabetes drugs, particularly newer agents including gliptins or glitazones (thiazolidinediones). DESIGN Open cohort study in primary care. SETTING 1243 practices contributing data to the QResearch database in England. PARTICIPANTS 469,688 patients with type 2 diabetes aged 25-84 years between 1 April 2007 and 31 January 2015. EXPOSURES Hypoglycaemic agents (glitazones, gliptins, metformin, sulphonylureas, insulin, and other…

- sourceStage=fusion rank=3 `16760369` Comparative determinants of 4-year cardiovascular event rates in stable outpatients at risk of or with atherothrombosis.；dense=0.81074464，sparse=null，fusion=0.90537232，rerank=null

  > CONTEXT Clinicians and trialists have difficulty with identifying which patients are highest risk for cardiovascular events. Prior ischemic events, polyvascular disease, and diabetes mellitus have all been identified as predictors of ischemic events, but their comparative contributions to future risk remain unclear. OBJECTIVE To categorize the risk of cardiovascular events in stable outpatients with various initial manifestations of atherothrombosis using simple clinical descriptors. DESIGN, SETTING, AND PATIENTS Outpatients with coronary artery disease, cerebrovascular disease, or peripheral…

## queryId=768

问题：Mercaptopurine is anabolized into the inactive methylmercaptopurine by thiopurine methyltrasnferase (TPMT).

原分类：`dense_miss_hybrid_hit`, `rerank_reorder_gain`

Gold文档：

- `6421792` Activating mutations in the NT5C2 nucleotidase gene drive chemotherapy resistance in relapsed ALL

  > Acute lymphoblastic leukemia (ALL) is an aggressive hematological tumor resulting from the malignant transformation of lymphoid progenitors. Despite intensive chemotherapy, 20% of pediatric patients and over 50% of adult patients with ALL do not achieve a complete remission or relapse after intensified chemotherapy, making disease relapse and resistance to therapy the most substantial challenge in the treatment of this disease. Using whole-exome sequencing, we identify mutations in the cytosolic 5'-nucleotidase II gene (NT5C2), which encodes a 5'-nucleotidase enzyme that is responsible for the…

| 变体 | 首个覆盖损失 | 首个完全损失 | 最终排名复现 | Rerank同请求效果 |
|---|---|---|---:|---|
| dense | fusion/FUSION_THRESHOLD_OR_TOPK_LOSS | fusion/FUSION_THRESHOLD_OR_TOPK_LOSS | 是 | 不适用 |
| sparse | fusion/FUSION_THRESHOLD_OR_TOPK_LOSS | fusion/FUSION_THRESHOLD_OR_TOPK_LOSS | 是 | 不适用 |
| hybrid_rrf | 未观察到Gold损失 | 未观察到Gold损失 | 是 | 不适用 |
| hybrid_rrf_rerank | 未观察到Gold损失 | 未观察到Gold损失 | 是 | RERANK_ORDER_GAIN (MRR Δ=0.888889) |

重点失败变体：`hybrid_rrf_rerank`。首个内部失效结论：未观察到Gold损失。该结论级别为阶段轨迹派生，不外推为模型根因。

重点变体Gold逐阶段路径：

| 阶段 | Gold仍存在 | Gold已缺失 | Gold最佳名次 |
|---|---|---|---:|
| raw_union | 6421792 |  | - |
| fusion | 6421792 |  | 9 |
| candidate_filter | 6421792 |  | 9 |
| rerank_input | 6421792 |  | 9 |
| rerank_output | 6421792 |  | 1 |
| context_budget | 6421792 |  | 1 |

`dense`在`fusion/FUSION_THRESHOLD_OR_TOPK_LOSS`阶段排名靠前的非Gold文档：

- sourceStage=fusion rank=1 `11603066` Using Structural Information to Change the Phosphotransfer Specificity of a Two-Component Chemotaxis Signalling Complex；dense=0.81163955，sparse=null，fusion=0.905819775，rerank=null

  > Two-component signal transduction pathways comprising histidine protein kinases (HPKs) and their response regulators (RRs) are widely used to control bacterial responses to environmental challenges. Some bacteria have over 150 different two-component pathways, and the specificity of the phosphotransfer reactions within these systems is tightly controlled to prevent unwanted crosstalk. One of the best understood two-component signalling pathways is the chemotaxis pathway. Here, we present the 1.40 A crystal structure of the histidine-containing phosphotransfer domain of the chemotaxis HPK, CheA…

- sourceStage=fusion rank=2 `24221369` A Conserved Histidine in the RNA Sensor RIG-I Controls Immune Tolerance to N1-2'O-Methylated Self RNA.；dense=0.8102411，sparse=null，fusion=0.90512055，rerank=null

  > The cytosolic helicase retinoic acid-inducible gene-I (RIG-I) initiates immune responses to most RNA viruses by detecting viral 5'-triphosphorylated RNA (pppRNA). Although endogenous mRNA is also 5'-triphosphorylated, backbone modifications and the 5'-ppp-linked methylguanosine ((m7)G) cap prevent immunorecognition. Here we show that the methylation status of endogenous capped mRNA at the 5'-terminal nucleotide (N1) was crucial to prevent RIG-I activation. Moreover, we identified a single conserved amino acid (H830) in the RIG-I RNA binding pocket as the mediator of steric exclusion of N1-2'O-…

- sourceStage=fusion rank=4 `20231138` Replication Fork Slowing and Reversal upon DNA Damage Require PCNA Polyubiquitination and ZRANB3 DNA Translocase Activity；dense=0.8050468，sparse=null，fusion=0.9025234，rerank=null

  > DNA damage tolerance during eukaryotic replication is orchestrated by PCNA ubiquitination. While monoubiquitination activates mutagenic translesion synthesis, polyubiquitination activates an error-free pathway, elusive in mammals, enabling damage bypass by template switching. Fork reversal is driven in vitro by multiple enzymes, including the DNA translocase ZRANB3, shown to bind polyubiquitinated PCNA. However, whether this interaction promotes fork remodeling and template switching in vivo was unknown. Here we show that damage-induced fork reversal in mammalian cells requires PCNA ubiquitina…

`sparse`在`fusion/FUSION_THRESHOLD_OR_TOPK_LOSS`阶段排名靠前的非Gold文档：

- sourceStage=fusion rank=1 `20381484` GAPDH Mediates Nitrosylation of Nuclear Proteins；dense=null，sparse=0.2714081，fusion=0.21347048205843583，rerank=null

  > S-nitrosylation of proteins by nitric oxide is a major mode of signalling in cells. S-nitrosylation can mediate the regulation of a range of proteins, including prominent nuclear proteins, such as HDAC2 (ref. 2) and PARP1 (ref. 3). The high reactivity of the nitric oxide group with protein thiols, but the selective nature of nitrosylation within the cell, implies the existence of targeting mechanisms. Specificity of nitric oxide signalling is often achieved by the binding of nitric oxide synthase (NOS) to target proteins, either directly or through scaffolding proteins such as PSD-95 (ref. 5)…

- sourceStage=fusion rank=2 `56893404` Macrosomia and Hyperinsulinaemic Hypoglycaemia in Patients with Heterozygous Mutations in the HNF4A Gene；dense=null，sparse=0.26316494，fusion=0.20833774882953923，rerank=null

  > Background  Macrosomia is associated with considerable neonatal and maternal morbidity. Factors that predict macrosomia are poorly understood. The increased rate of macrosomia in the offspring of pregnant women with diabetes and in congenital hyperinsulinaemia is mediated by increased foetal insulin secretion. We assessed the in utero and neonatal role of two key regulators of pancreatic insulin secretion by studying birthweight and the incidence of neonatal hypoglycaemia in patients with heterozygous mutations in the maturity-onset diabetes of the young (MODY) genes HNF4A (encoding HNF-4α) an…

- sourceStage=fusion rank=3 `17741440` Netting neutrophils in autoimmune small-vessel vasculitis；dense=null，sparse=0.2620721，fusion=0.20765224110413344，rerank=null

  > Small-vessel vasculitis (SVV) is a chronic autoinflammatory condition linked to antineutrophil cytoplasm autoantibodies (ANCAs). Here we show that chromatin fibers, so-called neutrophil extracellular traps (NETs), are released by ANCA-stimulated neutrophils and contain the targeted autoantigens proteinase-3 (PR3) and myeloperoxidase (MPO). Deposition of NETs in inflamed kidneys and circulating MPO-DNA complexes suggest that NET formation triggers vasculitis and promotes the autoimmune response against neutrophil components in individuals with SVV.

## queryId=1204

问题：The combination of H3K4me3 and H3K79me2 is found in quiescent hair follicle stem cells.

原分类：`sparse_miss_hybrid_hit`

Gold文档：

- `31141365` Genome-wide maps of histone modifications unwind in vivo chromatin states of the hair follicle lineage.

  > Using mouse skin, where bountiful reservoirs of synchronized hair follicle stem cells (HF-SCs) fuel cycles of regeneration, we explore how adult SCs remodel chromatin in response to activating cues. By profiling global mRNA and chromatin changes in quiescent and activated HF-SCs and their committed, transit-amplifying (TA) progeny, we show that polycomb-group (PcG)-mediated H3K27-trimethylation features prominently in HF-lineage progression by mechanisms distinct from embryonic-SCs. In HF-SCs, PcG represses nonskin lineages and HF differentiation. In TA progeny, nonskin regulators remain PcG-r…

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
| sparse_raw | 31141365 |  | 13 |
| fusion |  | 31141365 | - |
| candidate_filter |  | 31141365 | - |
| pre_assembly |  | 31141365 | - |
| context_budget |  | 31141365 | - |

`sparse`在`fusion/FUSION_THRESHOLD_OR_TOPK_LOSS`阶段排名靠前的非Gold文档：

- sourceStage=fusion rank=1 `45638119` ALDH1 is a marker of normal and malignant human mammary stem cells and a predictor of poor clinical outcome.；dense=null，sparse=0.34935322，fusion=0.2589042030077195，rerank=null

  > Application of stem cell biology to breast cancer research has been limited by the lack of simple methods for identification and isolation of normal and malignant stem cells. Utilizing in vitro and in vivo experimental systems, we show that normal and cancer human mammary epithelial cells with increased aldehyde dehydrogenase activity (ALDH) have stem/progenitor properties. These cells contain the subpopulation of normal breast epithelium with the broadest lineage differentiation potential and greatest growth capacity in a xenotransplant model. In breast carcinomas, high ALDH activity identifi…

- sourceStage=fusion rank=2 `24142891` Tmem27: a cleaved and shed plasma membrane protein that stimulates pancreatic beta cell proliferation.；dense=null，sparse=0.33087474，fusion=0.24861448643919712，rerank=null

  > The signals and molecular mechanisms that regulate the replication of terminally differentiated beta cells are unknown. Here, we report the identification and characterization of transmembrane protein 27 (Tmem27, collectrin) in pancreatic beta cells. Expression of Tmem27 is reduced in Tcf1(-/-) mice and is increased in islets of mouse models with hypertrophy of the endocrine pancreas. Tmem27 forms dimers and its extracellular domain is glycosylated, cleaved and shed from the plasma membrane of beta cells. This cleavage process is beta cell specific and does not occur in other cell types. Overe…

- sourceStage=fusion rank=3 `15928989` Liver receptor homolog-1 is essential for pregnancy；dense=null，sparse=0.32191932，fusion=0.24352418118830427，rerank=null

  > Successful pregnancy requires coordination of an array of signals and factors from multiple tissues. One such element, liver receptor homolog-1 (Lrh-1), is an orphan nuclear receptor that regulates metabolism and hormone synthesis. It is strongly expressed in granulosa cells of ovarian follicles and in the corpus luteum of rodents and humans. Germline ablation of Nr5a2 (also called Lrh-1), the gene coding for Lrh-1, in mice is embryonically lethal at gastrulation. Depletion of Lrh-1 in the ovarian follicle shows that it regulates genes required for both steroid synthesis and ovulation. To stud…

## queryId=1395

问题：p16INK4A accumulation is  linked to an abnormal wound response caused by the microinvasive step of advanced Oral Potentially Malignant Lesions (OPMLs).

原分类：`sparse_miss_hybrid_hit`

Gold文档：

- `17717391` Monitoring Tumorigenesis and Senescence In Vivo with a p16INK4a-Luciferase Model

  > Monitoring cancer and aging in vivo remains experimentally challenging. Here, we describe a luciferase knockin mouse (p16(LUC)), which faithfully reports expression of p16(INK4a), a tumor suppressor and aging biomarker. Lifelong assessment of luminescence in p16(+/LUC) mice revealed an exponential increase with aging, which was highly variable in a cohort of contemporaneously housed, syngeneic mice. Expression of p16(INK4a) with aging did not predict cancer development, suggesting that the accumulation of senescent cells is not a principal determinant of cancer-related death. In 14 of 14 teste…

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
| sparse_raw | 17717391 |  | 14 |
| fusion |  | 17717391 | - |
| candidate_filter |  | 17717391 | - |
| pre_assembly |  | 17717391 | - |
| context_budget |  | 17717391 | - |

`sparse`在`fusion/FUSION_THRESHOLD_OR_TOPK_LOSS`阶段排名靠前的非Gold文档：

- sourceStage=fusion rank=1 `11603066` Using Structural Information to Change the Phosphotransfer Specificity of a Two-Component Chemotaxis Signalling Complex；dense=null，sparse=0.2784471，fusion=0.21780103376979776，rerank=null

  > Two-component signal transduction pathways comprising histidine protein kinases (HPKs) and their response regulators (RRs) are widely used to control bacterial responses to environmental challenges. Some bacteria have over 150 different two-component pathways, and the specificity of the phosphotransfer reactions within these systems is tightly controlled to prevent unwanted crosstalk. One of the best understood two-component signalling pathways is the chemotaxis pathway. Here, we present the 1.40 A crystal structure of the histidine-containing phosphotransfer domain of the chemotaxis HPK, CheA…

- sourceStage=fusion rank=2 `17741440` Netting neutrophils in autoimmune small-vessel vasculitis；dense=null，sparse=0.27102482，fusion=0.21323330255659365，rerank=null

  > Small-vessel vasculitis (SVV) is a chronic autoinflammatory condition linked to antineutrophil cytoplasm autoantibodies (ANCAs). Here we show that chromatin fibers, so-called neutrophil extracellular traps (NETs), are released by ANCA-stimulated neutrophils and contain the targeted autoantigens proteinase-3 (PR3) and myeloperoxidase (MPO). Deposition of NETs in inflamed kidneys and circulating MPO-DNA complexes suggest that NET formation triggers vasculitis and promotes the autoimmune response against neutrophil components in individuals with SVV.

- sourceStage=fusion rank=3 `20381484` GAPDH Mediates Nitrosylation of Nuclear Proteins；dense=null，sparse=0.2694251，fusion=0.2122418250592335，rerank=null

  > S-nitrosylation of proteins by nitric oxide is a major mode of signalling in cells. S-nitrosylation can mediate the regulation of a range of proteins, including prominent nuclear proteins, such as HDAC2 (ref. 2) and PARP1 (ref. 3). The high reactivity of the nitric oxide group with protein thiols, but the selective nature of nitrosylation within the cell, implies the existence of targeting mechanisms. Specificity of nitric oxide signalling is often achieved by the binding of nitric oxide synthase (NOS) to target proteins, either directly or through scaffolding proteins such as PSD-95 (ref. 5)…

## queryId=5

问题：1/2000 in UK have abnormal PrP positivity.

原分类：`sparse_miss_hybrid_hit`

Gold文档：

- `13734012` Prevalent abnormal prion protein in human appendixes after bovine spongiform encephalopathy epizootic: large scale survey

  > OBJECTIVES To carry out a further survey of archived appendix samples to understand better the differences between existing estimates of the prevalence of subclinical infection with prions after the bovine spongiform encephalopathy epizootic and to see whether a broader birth cohort was affected, and to understand better the implications for the management of blood and blood products and for the handling of surgical instruments. DESIGN Irreversibly unlinked and anonymised large scale survey of archived appendix samples. SETTING Archived appendix samples from the pathology departments of 41 UK…

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
| sparse_raw | 13734012 |  | 14 |
| fusion |  | 13734012 | - |
| candidate_filter |  | 13734012 | - |
| pre_assembly |  | 13734012 | - |
| context_budget |  | 13734012 | - |

`sparse`在`fusion/FUSION_THRESHOLD_OR_TOPK_LOSS`阶段排名靠前的非Gold文档：

- sourceStage=fusion rank=1 `17077004` Stable partnership and progression to AIDS or death in HIV infected patients receiving highly active antiretroviral therapy: Swiss HIV cohort study.；dense=null，sparse=0.24104878，fusion=0.19422989964987514，rerank=null

  > OBJECTIVES To explore the association between a stable partnership and clinical outcome in HIV infected patients receiving highly active antiretroviral therapy (HAART). DESIGN Prospective cohort study of adults with HIV (Swiss HIV cohort study). SETTING Seven outpatient clinics throughout Switzerland. PARTICIPANTS The 3736 patients in the cohort who started HAART before 2002 (median age 36 years, 29% female, median follow up 3.6 years). MAIN OUTCOME MEASURES Time to AIDS or death (primary endpoint), death alone, increases in CD4 cell count of at least 50 and 100 above baseline, optimal viral s…

- sourceStage=fusion rank=2 `24512064` HTLV-I/II associated disease in England and Wales, 1993-7: retrospective review of serology requests.；dense=null，sparse=0.21206573，fusion=0.17496223575267655，rerank=null

  > Apart from HIV two exogenous retroviruses (human T cell leukaemia viruses type I (HTLV-I) and type II (HTLV-II)) infect humans. HTLV-I infection is endemic in Japan, the Caribbean, Africa, and Melanesia and is found among immigrants from these regions in Europe. HTLV-I infection is associated with a 1-5% lifetime risk of adult T cell leukaemia/lymphoma, 1 a 0.25% lifetime risk of HTLV-I associated myelopathy, 2 and other inflammatory conditions (uveitis, alveolitis, and arthritis).1 HTLV-II infection is endemic in some native American and African peoples and among injecting drug users and has…

- sourceStage=fusion rank=3 `29564505` Inflammatory biomarkers and exacerbations in chronic obstructive pulmonary disease.；dense=null，sparse=0.20021053，fusion=0.16681284241023947，rerank=null

  > IMPORTANCE Exacerbations of respiratory symptoms in chronic obstructive pulmonary disease (COPD) have profound and long-lasting adverse effects on patients. OBJECTIVE To test the hypothesis that elevated levels of inflammatory biomarkers in individuals with stable COPD are associated with an increased risk of having exacerbations. DESIGN, SETTING, AND PARTICIPANTS Prospective cohort study examining 61,650 participants with spirometry measurements from the Copenhagen City Heart Study (2001-2003) and the Copenhagen General Population Study (2003-2008). Of these, 6574 had COPD, defined as a ratio…

## queryId=115

问题：Anthrax spores can be disposed of easily after they are dispersed.

原分类：`sparse_miss_hybrid_hit`, `dense_only_success`

Gold文档：

- `33872649` Secondary aerosolization of viable Bacillus anthracis spores in a contaminated US Senate Office.

  > CONTEXT Bioterrorist attacks involving letters and mail-handling systems in Washington, DC, resulted in Bacillus anthracis (anthrax) spore contamination in the Hart Senate Office Building and other facilities in the US Capitol's vicinity. OBJECTIVE To provide information about the nature and extent of indoor secondary aerosolization of B anthracis spores. DESIGN Stationary and personal air samples, surface dust, and swab samples were collected under semiquiescent (minimal activities) and then simulated active office conditions to estimate secondary aerosolization of B anthracis spores. Nominal…

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
| sparse_raw | 33872649 |  | 42 |
| fusion |  | 33872649 | - |
| candidate_filter |  | 33872649 | - |
| pre_assembly |  | 33872649 | - |
| context_budget |  | 33872649 | - |

`sparse`在`fusion/FUSION_THRESHOLD_OR_TOPK_LOSS`阶段排名靠前的非Gold文档：

- sourceStage=fusion rank=1 `6157837` Renal considerations in angiotensin converting enzyme inhibitor therapy: a statement for healthcare professionals from the Council on the Kidney in Cardiovascular Disease and the Council for High Blood Pressure Research of the American Heart Association.；dense=null，sparse=0.21878421，fusion=0.17951021042519086，rerank=null

  > Angiotensin converting enzyme (ACE) inhibitors are now one of the most frequently used classes of antihypertensive drugs. Beyond their utility in the management of hypertension, their use has been extended to the long-term management of patients with congestive heart failure (CHF), as well as diabetic and nondiabetic nephropathies. Although ACE inhibitor therapy usually improves renal blood flow (RBF) and sodium excretion rates in CHF and reduces the rate of progressive renal injury in chronic renal disease, its use can also be associated with a syndrome of “functional renal insufficiency” and…

- sourceStage=fusion rank=2 `8551160` Mitochondria: Dynamic Organelles in Disease, Aging, and Development；dense=null，sparse=0.20778957，fusion=0.17204120250848，rerank=null

  > Mitochondria are the primary energy-generating system in most eukaryotic cells. Additionally, they participate in intermediary metabolism, calcium signaling, and apoptosis. Given these well-established functions, it might be expected that mitochondrial dysfunction would give rise to a simple and predictable set of defects in all tissues. However, mitochondrial dysfunction has pleiotropic effects in multicellular organisms. Clearly, much about the basic biology of mitochondria remains to be understood. Here we discuss recent work that suggests that the dynamics (fusion and fission) of these org…

- sourceStage=fusion rank=3 `4381486` Haematopoietic stem cells do not asymmetrically segregate chromosomes or retain BrdU；dense=null，sparse=0.2056236，fusion=0.17055372837757984，rerank=null

  > Stem cells are proposed to segregate chromosomes asymmetrically during self-renewing divisions so that older (‘immortal’) DNA strands are retained in daughter stem cells whereas newly synthesized strands segregate to differentiating cells. Stem cells are also proposed to retain DNA labels, such as 5-bromo-2-deoxyuridine (BrdU), either because they segregate chromosomes asymmetrically or because they divide slowly. However, the purity of stem cells among BrdU-label-retaining cells has not been documented in any tissue, and the ‘immortal strand hypothesis’ has not been tested in a system with de…

## queryId=1049

问题：Ribosomopathies have a low degree of cell and tissue specific pathology.

原分类：`dense_only_success`

Gold文档：

- `12486491` Ribosome-Mediated Specificity in Hox mRNA Translation and Vertebrate Tissue Patterning

  > Historically, the ribosome has been viewed as a complex ribozyme with constitutive rather than regulatory capacity in mRNA translation. Here we identify mutations of the Ribosomal Protein L38 (Rpl38) gene in mice exhibiting surprising tissue-specific patterning defects, including pronounced homeotic transformations of the axial skeleton. In Rpl38 mutant embryos, global protein synthesis is unchanged; however the translation of a select subset of Homeobox mRNAs is perturbed. Our data reveal that RPL38 facilitates 80S complex formation on these mRNAs as a regulatory component of the ribosome to…

| 变体 | 首个覆盖损失 | 首个完全损失 | 最终排名复现 | Rerank同请求效果 |
|---|---|---|---:|---|
| dense | 未观察到Gold损失 | 未观察到Gold损失 | 是 | 不适用 |
| sparse | fusion/FUSION_THRESHOLD_OR_TOPK_LOSS | fusion/FUSION_THRESHOLD_OR_TOPK_LOSS | 是 | 不适用 |
| hybrid_rrf | 未观察到Gold损失 | 未观察到Gold损失 | 是 | 不适用 |
| hybrid_rrf_rerank | 未观察到Gold损失 | 未观察到Gold损失 | 是 | RERANK_ORDER_GAIN (MRR Δ=0.750000) |

重点失败变体：`sparse`。首个内部失效结论：fusion/FUSION_THRESHOLD_OR_TOPK_LOSS。该结论级别为阶段轨迹派生，不外推为模型根因。

重点变体Gold逐阶段路径：

| 阶段 | Gold仍存在 | Gold已缺失 | Gold最佳名次 |
|---|---|---|---:|
| sparse_raw | 12486491 |  | 30 |
| fusion |  | 12486491 | - |
| candidate_filter |  | 12486491 | - |
| pre_assembly |  | 12486491 | - |
| context_budget |  | 12486491 | - |

`sparse`在`fusion/FUSION_THRESHOLD_OR_TOPK_LOSS`阶段排名靠前的非Gold文档：

- sourceStage=fusion rank=1 `24142891` Tmem27: a cleaved and shed plasma membrane protein that stimulates pancreatic beta cell proliferation.；dense=null，sparse=0.2786563，fusion=0.21792900875708354，rerank=null

  > The signals and molecular mechanisms that regulate the replication of terminally differentiated beta cells are unknown. Here, we report the identification and characterization of transmembrane protein 27 (Tmem27, collectrin) in pancreatic beta cells. Expression of Tmem27 is reduced in Tcf1(-/-) mice and is increased in islets of mouse models with hypertrophy of the endocrine pancreas. Tmem27 forms dimers and its extracellular domain is glycosylated, cleaved and shed from the plasma membrane of beta cells. This cleavage process is beta cell specific and does not occur in other cell types. Overe…

- sourceStage=fusion rank=2 `8290953` Scaffold-based three-dimensional human fibroblast culture provides a structural matrix that supports angiogenesis in infarcted heart tissue.；dense=null，sparse=0.27777174，fusion=0.2173876063341329，rerank=null

  > BACKGROUND We have developed techniques to implant angiogenic patches onto the epicardium over regions of infarcted cardiac tissue to stimulate revascularization of the damaged tissue. These experiments used a scaffold-based 3D human dermal fibroblast culture (3DFC) as an epicardial patch. The 3DFC contains viable cells that secrete angiogenic growth factors and has previously been shown to stimulate angiogenic activity. The hypothesis tested was that a viable 3DFC cardiac patch would stimulate an angiogenic response within an area of infarcted cardiac tissue. METHODS AND RESULTS A coronary oc…

- sourceStage=fusion rank=3 `45638119` ALDH1 is a marker of normal and malignant human mammary stem cells and a predictor of poor clinical outcome.；dense=null，sparse=0.27612197，fusion=0.21637584532770016，rerank=null

  > Application of stem cell biology to breast cancer research has been limited by the lack of simple methods for identification and isolation of normal and malignant stem cells. Utilizing in vitro and in vivo experimental systems, we show that normal and cancer human mammary epithelial cells with increased aldehyde dehydrogenase activity (ALDH) have stem/progenitor properties. These cells contain the subpopulation of normal breast epithelium with the broadest lineage differentiation potential and greatest growth capacity in a xenotransplant model. In breast carcinomas, high ALDH activity identifi…

## queryId=1088

问题：Silencing of Bcl2 is important for the maintenance and progression of tumors.

原分类：`dense_only_success`, `rerank_reorder_gain`

Gold文档：

- `37549932` Antiapoptotic BCL-2 is required for maintenance of a model leukemia.

  > Resistance to apoptosis, often achieved by the overexpression of antiapoptotic proteins, is common and perhaps required in the genesis of cancer. However, it remains uncertain whether apoptotic defects are essential for tumor maintenance. To test this, we generated mice expressing a conditional BCL-2 gene and constitutive c-myc that develop lymphoblastic leukemia. Eliminating BCL-2 yielded rapid loss of leukemic cells and significantly prolonged survival, formally validating BCL-2 as a rational target for cancer therapy. Loss of this single molecule resulted in cell death, despite or perhaps a…

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
| raw_union | 37549932 |  | - |
| fusion | 37549932 |  | 7 |
| candidate_filter | 37549932 |  | 7 |
| rerank_input | 37549932 |  | 7 |
| rerank_output | 37549932 |  | 1 |
| context_budget | 37549932 |  | 1 |

`sparse`在`fusion/FUSION_THRESHOLD_OR_TOPK_LOSS`阶段排名靠前的非Gold文档：

- sourceStage=fusion rank=1 `1897324` A genetic screen identifies an LKB1–MARK signalling axis controlling the Hippo–YAP pathway；dense=null，sparse=0.3387772，fusion=0.25304972328480047，rerank=null

  > The Hippo–YAP pathway is an emerging signalling cascade involved in the regulation of stem cell activity and organ size. To identify components of this pathway, we performed an RNAi-based kinome screen in human cells. Our screen identified several kinases not previously associated with Hippo signalling that control multiple cellular processes. One of the hits, LKB1, is a common tumour suppressor whose mechanism of action is only partially understood. We demonstrate that LKB1 acts through its substrates of the microtubule affinity-regulating kinase family to regulate the localization of the pol…

- sourceStage=fusion rank=2 `15928989` Liver receptor homolog-1 is essential for pregnancy；dense=null，sparse=0.3368863，fusion=0.25199323233396886，rerank=null

  > Successful pregnancy requires coordination of an array of signals and factors from multiple tissues. One such element, liver receptor homolog-1 (Lrh-1), is an orphan nuclear receptor that regulates metabolism and hormone synthesis. It is strongly expressed in granulosa cells of ovarian follicles and in the corpus luteum of rodents and humans. Germline ablation of Nr5a2 (also called Lrh-1), the gene coding for Lrh-1, in mice is embryonically lethal at gastrulation. Depletion of Lrh-1 in the ovarian follicle shows that it regulates genes required for both steroid synthesis and ovulation. To stud…

- sourceStage=fusion rank=3 `45638119` ALDH1 is a marker of normal and malignant human mammary stem cells and a predictor of poor clinical outcome.；dense=null，sparse=0.33521724，fusion=0.251058202334176，rerank=null

  > Application of stem cell biology to breast cancer research has been limited by the lack of simple methods for identification and isolation of normal and malignant stem cells. Utilizing in vitro and in vivo experimental systems, we show that normal and cancer human mammary epithelial cells with increased aldehyde dehydrogenase activity (ALDH) have stem/progenitor properties. These cells contain the subpopulation of normal breast epithelium with the broadest lineage differentiation potential and greatest growth capacity in a xenotransplant model. In breast carcinomas, high ALDH activity identifi…

## queryId=1194

问题：The arm density of TatAd complexes is due to structural rearrangements within Class1 TatAd complexes such as the 'charge zipper mechanism'.

原分类：`dense_only_success`, `rerank_reorder_gain`

Gold文档：

- `11419230` Folding and Self-Assembly of the TatA Translocation Pore Based on a Charge Zipper Mechanism

  > We propose a concept for the folding and self-assembly of the pore-forming TatA complex from the Twin-arginine translocase and of other membrane proteins based on electrostatic "charge zippers. " Each subunit of TatA consists of a transmembrane segment, an amphiphilic helix (APH), and a C-terminal densely charged region (DCR). The sequence of charges in the DCR is complementary to the charge pattern on the APH, suggesting that the protein can be "zipped up" by a ladder of seven salt bridges. The length of the resulting hairpin matches the lipid bilayer thickness, hence a transmembrane pore cou…

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
| raw_union | 11419230 |  | - |
| fusion | 11419230 |  | 7 |
| candidate_filter | 11419230 |  | 7 |
| rerank_input | 11419230 |  | 7 |
| rerank_output | 11419230 |  | 1 |
| context_budget | 11419230 |  | 1 |

`sparse`在`fusion/FUSION_THRESHOLD_OR_TOPK_LOSS`阶段排名靠前的非Gold文档：

- sourceStage=fusion rank=1 `33499189` Full activation of the T cell receptor requires both clustering and conformational changes at CD3.；dense=null，sparse=0.27806842，fusion=0.21756927536007814，rerank=null

  > T cell receptor (TCR-CD3) triggering involves both receptor clustering and conformational changes at the cytoplasmic tails of the CD3 subunits. The mechanism by which TCRalphabeta ligand binding confers conformational changes to CD3 is unknown. By using well-defined ligands, we showed that induction of the conformational change requires both multivalent engagement and the mobility restriction of the TCR-CD3 imposed by the plasma membrane. The conformational change is elicited by cooperative rearrangements of two TCR-CD3 complexes and does not require accompanying changes in the structure of th…

- sourceStage=fusion rank=2 `11335781` Is autoimmunity the Achilles' heel of cancer immunotherapy?；dense=null，sparse=0.2598166，fusion=0.20623366924995273，rerank=null

  > The emergence of immuno-oncology as the first broadly successful strategy for metastatic cancer will require clinicians to integrate this new pillar of medicine with chemotherapy, radiation, and targeted small-molecule compounds. Of equal importance is gaining an understanding of the limitations and toxicities of immunotherapy. Immunotherapy was initially perceived to be a relatively less toxic approach to cancer treatment than other available therapies—and surely it is, when compared to those. However, as the use of immunotherapy becomes more common, especially as first- and second-line treat…

- sourceStage=fusion rank=3 `28937856` Stress-dependent regulation of FOXO transcription factors by the SIRT1 deacetylase.；dense=null，sparse=0.2514463，fusion=0.20092456224450064，rerank=null

  > The Sir2 deacetylase modulates organismal life-span in various species. However, the molecular mechanisms by which Sir2 increases longevity are largely unknown. We show that in mammalian cells, the Sir2 homolog SIRT1 appears to control the cellular response to stress by regulating the FOXO family of Forkhead transcription factors, a family of proteins that function as sensors of the insulin signaling pathway and as regulators of organismal longevity. SIRT1 and the FOXO transcription factor FOXO3 formed a complex in cells in response to oxidative stress, and SIRT1 deacetylated FOXO3 in vitro an…

## queryId=1196

问题：The availability of safe places to study is effective at decreasing homelessness.

原分类：`dense_only_success`

Gold文档：

- `25649714` Mental health problems of homeless children and families: longitudinal study.

  > OBJECTIVE To establish the mental health needs of homeless children and families before and after rehousing. DESIGN Cross sectional, longitudinal study. SETTING City of Birmingham. SUBJECTS 58 rehoused families with 103 children aged 2-16 years and 21 comparison families of low socioeconomic status in stable housing, with 54 children. MAIN OUTCOME MEASURES Children's mental health problems and level of communication; mothers' mental health problems and social support one year after rehousing. RESULTS Mental health problems remained significantly higher in rehoused mothers and their children th…

| 变体 | 首个覆盖损失 | 首个完全损失 | 最终排名复现 | Rerank同请求效果 |
|---|---|---|---:|---|
| dense | 未观察到Gold损失 | 未观察到Gold损失 | 是 | 不适用 |
| sparse | sparse_raw/SPARSE_RAW_TOPK_MISS | sparse_raw/SPARSE_RAW_TOPK_MISS | 是 | 不适用 |
| hybrid_rrf | fusion/FUSION_THRESHOLD_OR_TOPK_LOSS | fusion/FUSION_THRESHOLD_OR_TOPK_LOSS | 是 | 不适用 |
| hybrid_rrf_rerank | fusion/FUSION_THRESHOLD_OR_TOPK_LOSS | fusion/FUSION_THRESHOLD_OR_TOPK_LOSS | 是 | RERANK_NEUTRAL (MRR Δ=0.000000) |

重点失败变体：`sparse`。首个内部失效结论：sparse_raw/SPARSE_RAW_TOPK_MISS。该结论级别为阶段轨迹派生，不外推为模型根因。

重点变体Gold逐阶段路径：

| 阶段 | Gold仍存在 | Gold已缺失 | Gold最佳名次 |
|---|---|---|---:|
| sparse_raw |  | 25649714 | - |
| fusion |  | 25649714 | - |
| candidate_filter |  | 25649714 | - |
| pre_assembly |  | 25649714 | - |
| context_budget |  | 25649714 | - |

`sparse`在`sparse_raw/SPARSE_RAW_TOPK_MISS`阶段排名靠前的非Gold文档：

- sourceStage=sparse_raw rank=1 `33499189` Full activation of the T cell receptor requires both clustering and conformational changes at CD3.；dense=null，sparse=0.3044739，fusion=null，rerank=null

  > T cell receptor (TCR-CD3) triggering involves both receptor clustering and conformational changes at the cytoplasmic tails of the CD3 subunits. The mechanism by which TCRalphabeta ligand binding confers conformational changes to CD3 is unknown. By using well-defined ligands, we showed that induction of the conformational change requires both multivalent engagement and the mobility restriction of the TCR-CD3 imposed by the plasma membrane. The conformational change is elicited by cooperative rearrangements of two TCR-CD3 complexes and does not require accompanying changes in the structure of th…

- sourceStage=sparse_raw rank=2 `8780599` The Polymeal: a more natural, safer, and probably tastier (than the Polypill) strategy to reduce cardiovascular disease by more than 75%.；dense=null，sparse=0.30101314，fusion=null，rerank=null

  > OBJECTIVE Although the Polypill concept (proposed in 2003) is promising in terms of benefits for cardiovascular risk management, the potential costs and adverse effects are its main pitfalls. The objective of this study was to identify a tastier and safer alternative to the Polypill: the Polymeal. METHODS Data on the ingredients of the Polymeal were taken from the literature. The evidence based recipe included wine, fish, dark chocolate, fruits, vegetables, garlic, and almonds. Data from the Framingham heart study and the Framingham offspring study were used to build life tables to model the b…

- sourceStage=sparse_raw rank=3 `12789595` Computer assisted learning in undergraduate medical education.；dense=null，sparse=0.2942969，fusion=null，rerank=null

  > It is becoming “a truth universally acknowledged” that the education of undergraduate medical students will be enhanced through the use of computer assisted learning. Access to the wide range of online options illustrated in the figure must surely make learning more exciting, effective, and likely to be retained. This assumption is potentially but by no means inevitably correct. ### Box 1: Why fund computer assisted learning? Computer assisted learning is inevitable —Individual lecturers and departments are already beginning to introduce a wide range of computer based applications, sometimes i…

`hybrid_rrf`在`fusion/FUSION_THRESHOLD_OR_TOPK_LOSS`阶段排名靠前的非Gold文档：

- sourceStage=fusion rank=1 `12789595` Computer assisted learning in undergraduate medical education.；dense=0.76220745，sparse=0.2942969，fusion=0.8802308802308801，rerank=null

  > It is becoming “a truth universally acknowledged” that the education of undergraduate medical students will be enhanced through the use of computer assisted learning. Access to the wide range of online options illustrated in the figure must surely make learning more exciting, effective, and likely to be retained. This assumption is potentially but by no means inevitably correct. ### Box 1: Why fund computer assisted learning? Computer assisted learning is inevitable —Individual lecturers and departments are already beginning to introduce a wide range of computer based applications, sometimes i…

- sourceStage=fusion rank=2 `8780599` The Polymeal: a more natural, safer, and probably tastier (than the Polypill) strategy to reduce cardiovascular disease by more than 75%.；dense=0.75190467，sparse=0.30101314，fusion=0.7769822128429303，rerank=null

  > OBJECTIVE Although the Polypill concept (proposed in 2003) is promising in terms of benefits for cardiovascular risk management, the potential costs and adverse effects are its main pitfalls. The objective of this study was to identify a tastier and safer alternative to the Polypill: the Polymeal. METHODS Data on the ingredients of the Polymeal were taken from the literature. The evidence based recipe included wine, fish, dark chocolate, fruits, vegetables, garlic, and almonds. Data from the Framingham heart study and the Framingham offspring study were used to build life tables to model the b…

- sourceStage=fusion rank=3 `13071728` The HIV Treatment Gap: Estimates of the Financial Resources Needed versus Available for Scale-Up of Antiretroviral Therapy in 97 Countries from 2015 to 2020；dense=0.7550271，sparse=0.26574463，fusion=0.7708791208791208，rerank=null

  > BACKGROUND The World Health Organization (WHO) released revised guidelines in 2015 recommending that all people living with HIV, regardless of CD4 count, initiate antiretroviral therapy (ART) upon diagnosis. However, few studies have projected the global resources needed for rapid scale-up of ART. Under the Health Policy Project, we conducted modeling analyses for 97 countries to estimate eligibility for and numbers on ART from 2015 to 2020, along with the facility-level financial resources required. We compared the estimated financial requirements to estimated funding available. METHODS AND F…

`hybrid_rrf_rerank`在`fusion/FUSION_THRESHOLD_OR_TOPK_LOSS`阶段排名靠前的非Gold文档：

- sourceStage=fusion rank=1 `12789595` Computer assisted learning in undergraduate medical education.；dense=0.76220745，sparse=0.2942969，fusion=0.8802308802308801，rerank=null

  > It is becoming “a truth universally acknowledged” that the education of undergraduate medical students will be enhanced through the use of computer assisted learning. Access to the wide range of online options illustrated in the figure must surely make learning more exciting, effective, and likely to be retained. This assumption is potentially but by no means inevitably correct. ### Box 1: Why fund computer assisted learning? Computer assisted learning is inevitable —Individual lecturers and departments are already beginning to introduce a wide range of computer based applications, sometimes i…

- sourceStage=fusion rank=2 `8780599` The Polymeal: a more natural, safer, and probably tastier (than the Polypill) strategy to reduce cardiovascular disease by more than 75%.；dense=0.75190467，sparse=0.30101314，fusion=0.7769822128429303，rerank=null

  > OBJECTIVE Although the Polypill concept (proposed in 2003) is promising in terms of benefits for cardiovascular risk management, the potential costs and adverse effects are its main pitfalls. The objective of this study was to identify a tastier and safer alternative to the Polypill: the Polymeal. METHODS Data on the ingredients of the Polymeal were taken from the literature. The evidence based recipe included wine, fish, dark chocolate, fruits, vegetables, garlic, and almonds. Data from the Framingham heart study and the Framingham offspring study were used to build life tables to model the b…

- sourceStage=fusion rank=3 `13071728` The HIV Treatment Gap: Estimates of the Financial Resources Needed versus Available for Scale-Up of Antiretroviral Therapy in 97 Countries from 2015 to 2020；dense=0.7550271，sparse=0.26574463，fusion=0.7708791208791208，rerank=null

  > BACKGROUND The World Health Organization (WHO) released revised guidelines in 2015 recommending that all people living with HIV, regardless of CD4 count, initiate antiretroviral therapy (ART) upon diagnosis. However, few studies have projected the global resources needed for rapid scale-up of ART. Under the Health Policy Project, we conducted modeling analyses for 97 countries to estimate eligibility for and numbers on ART from 2015 to 2020, along with the facility-level financial resources required. We compared the estimated financial requirements to estimated funding available. METHODS AND F…

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
| dense_raw | 30655442 |  | 12 |
| fusion |  | 30655442 | - |
| candidate_filter |  | 30655442 | - |
| pre_assembly |  | 30655442 | - |
| context_budget |  | 30655442 | - |

`dense`在`fusion/FUSION_THRESHOLD_OR_TOPK_LOSS`阶段排名靠前的非Gold文档：

- sourceStage=fusion rank=1 `16472469` Targeting BRCA1 and BRCA2 Deficiencies with G-Quadruplex-Interacting Compounds；dense=0.78809094，sparse=null，fusion=0.89404547，rerank=null

  > G-quadruplex (G4)-forming genomic sequences, including telomeres, represent natural replication fork barriers. Stalled replication forks can be stabilized and restarted by homologous recombination (HR), which also repairs DNA double-strand breaks (DSBs) arising at collapsed forks. We have previously shown that HR facilitates telomere replication. Here, we demonstrate that the replication efficiency of guanine-rich (G-rich) telomeric repeats is decreased significantly in cells lacking HR. Treatment with the G4-stabilizing compound pyridostatin (PDS) increases telomere fragility in BRCA2-deficie…

- sourceStage=fusion rank=2 `13770184` Global, regional, and national comparative risk assessment of 79 behavioural, environmental and occupational, and metabolic risks or clusters of risks, 1990–2015: a systematic analysis for the Global Burden of Disease Study 2015；dense=0.7864126，sparse=null，fusion=0.8932063，rerank=null

  > BACKGROUND The Global Burden of Diseases, Injuries, and Risk Factors Study 2015 provides an up-to-date synthesis of the evidence for risk factor exposure and the attributable burden of disease. By providing national and subnational assessments spanning the past 25 years, this study can inform debates on the importance of addressing risks in context. METHODS We used the comparative risk assessment framework developed for previous iterations of the Global Burden of Disease Study to estimate attributable deaths, disability-adjusted life-years (DALYs), and trends in exposure by age group, sex, yea…

- sourceStage=fusion rank=4 `13734012` Prevalent abnormal prion protein in human appendixes after bovine spongiform encephalopathy epizootic: large scale survey；dense=0.78254706，sparse=null，fusion=0.89127353，rerank=null

  > OBJECTIVES To carry out a further survey of archived appendix samples to understand better the differences between existing estimates of the prevalence of subclinical infection with prions after the bovine spongiform encephalopathy epizootic and to see whether a broader birth cohort was affected, and to understand better the implications for the management of blood and blood products and for the handling of surgical instruments. DESIGN Irreversibly unlinked and anonymised large scale survey of archived appendix samples. SETTING Archived appendix samples from the pathology departments of 41 UK…

`sparse`在`fusion/FUSION_THRESHOLD_OR_TOPK_LOSS`阶段排名靠前的非Gold文档：

- sourceStage=fusion rank=1 `5476778` Autoimmunity due to molecular mimicry as a cause of neurological disease；dense=null，sparse=0.25035027，fusion=0.20022411000079202，rerank=null

  > One hypothesis that couples infection with autoimmune disease is molecular mimicry. Molecular mimicry is characterized by an immune response to an environmental agent that cross-reacts with a host antigen, resulting in disease. This hypothesis has been implicated in the pathogenesis of diabetes, lupus and multiple sclerosis (MS). There is limited direct evidence linking causative agents with pathogenic immune reactions in these diseases. Our study establishes a clear link between viral infection, autoimmunity and neurological disease in humans. As a model for molecular mimicry, we studied pati…

- sourceStage=fusion rank=2 `8780599` The Polymeal: a more natural, safer, and probably tastier (than the Polypill) strategy to reduce cardiovascular disease by more than 75%.；dense=null，sparse=0.24958509，fusion=0.19973436942977607，rerank=null

  > OBJECTIVE Although the Polypill concept (proposed in 2003) is promising in terms of benefits for cardiovascular risk management, the potential costs and adverse effects are its main pitfalls. The objective of this study was to identify a tastier and safer alternative to the Polypill: the Polymeal. METHODS Data on the ingredients of the Polymeal were taken from the literature. The evidence based recipe included wine, fish, dark chocolate, fruits, vegetables, garlic, and almonds. Data from the Framingham heart study and the Framingham offspring study were used to build life tables to model the b…

- sourceStage=fusion rank=3 `791050` The relation between past exposure to fine particulate air pollution and prevalent anxiety: observational cohort study；dense=null，sparse=0.24857648，fusion=0.1990879084956013，rerank=null

  > OBJECTIVE To determine whether higher past exposure to particulate air pollution is associated with prevalent high symptoms of anxiety. DESIGN Observational cohort study. SETTING Nurses' Health Study. PARTICIPANTS 71,271 women enrolled in the Nurses' Health Study residing throughout the contiguous United States who had valid estimates on exposure to particulate matter for at least one exposure period of interest and data on anxiety symptoms. MAIN OUTCOME MEASURES Meaningfully high symptoms of anxiety, defined as a score of 6 points or greater on the phobic anxiety subscale of the Crown-Crisp i…

`hybrid_rrf`在`fusion/FUSION_THRESHOLD_OR_TOPK_LOSS`阶段排名靠前的非Gold文档：

- sourceStage=fusion rank=1 `13770184` Global, regional, and national comparative risk assessment of 79 behavioural, environmental and occupational, and metabolic risks or clusters of risks, 1990–2015: a systematic analysis for the Global Burden of Disease Study 2015；dense=0.77746445，sparse=0.21971084，fusion=0.8535225048923678，rerank=null

  > BACKGROUND The Global Burden of Diseases, Injuries, and Risk Factors Study 2015 provides an up-to-date synthesis of the evidence for risk factor exposure and the attributable burden of disease. By providing national and subnational assessments spanning the past 25 years, this study can inform debates on the importance of addressing risks in context. METHODS We used the comparative risk assessment framework developed for previous iterations of the Global Burden of Disease Study to estimate attributable deaths, disability-adjusted life-years (DALYs), and trends in exposure by age group, sex, yea…

- sourceStage=fusion rank=2 `13519661` Linkage Disequilibrium Mapping of       CHEK2: Common Variation and Breast Cancer Risk；dense=0.77890503，sparse=0.21068704，fusion=0.84984520123839，rerank=null

  > Background Checkpoint kinase 2 (CHEK2) averts cancer development by promoting cell cycle arrest and activating DNA repair in genetically damaged cells. Previous investigation has established a role for the CHEK2 gene in breast cancer aetiology, but studies have largely been limited to the rare 1100delC mutation. Whether common polymorphisms in this gene influence breast cancer risk remains unknown. In this study, we aimed to assess the importance of common CHEK2 variants on population risk for breast cancer by capturing the majority of diversity in the gene using haplotype tagging single nucle…

- sourceStage=fusion rank=3 `25742130` Mass screening programmes and trends in cervical cancer in Finland and the Netherlands.；dense=0.7731986，sparse=0.23203726，fusion=0.8462495216226559，rerank=null

  > With respect to cervical cancer management, Finland and the Netherlands are comparable in relevant characteristics, e.g., fertility rate, age-of-mother at first birth and a national screening programme for several years. The aim of this study is to compare trends in incidence of and mortality from cervical cancer in Finland and the Netherlands in relation to the introduction and intensity of the screening programmes. Therefore, incidence and mortality rates were calculated using the Cancer Registries of Finland and the Netherlands. Data on screening intensity were obtained from the Finnish Can…

`hybrid_rrf_rerank`在`fusion/FUSION_THRESHOLD_OR_TOPK_LOSS`阶段排名靠前的非Gold文档：

- sourceStage=fusion rank=1 `13770184` Global, regional, and national comparative risk assessment of 79 behavioural, environmental and occupational, and metabolic risks or clusters of risks, 1990–2015: a systematic analysis for the Global Burden of Disease Study 2015；dense=0.77746445，sparse=0.21971084，fusion=0.8535225048923678，rerank=null

  > BACKGROUND The Global Burden of Diseases, Injuries, and Risk Factors Study 2015 provides an up-to-date synthesis of the evidence for risk factor exposure and the attributable burden of disease. By providing national and subnational assessments spanning the past 25 years, this study can inform debates on the importance of addressing risks in context. METHODS We used the comparative risk assessment framework developed for previous iterations of the Global Burden of Disease Study to estimate attributable deaths, disability-adjusted life-years (DALYs), and trends in exposure by age group, sex, yea…

- sourceStage=fusion rank=2 `13519661` Linkage Disequilibrium Mapping of       CHEK2: Common Variation and Breast Cancer Risk；dense=0.77890503，sparse=0.21068704，fusion=0.84984520123839，rerank=null

  > Background Checkpoint kinase 2 (CHEK2) averts cancer development by promoting cell cycle arrest and activating DNA repair in genetically damaged cells. Previous investigation has established a role for the CHEK2 gene in breast cancer aetiology, but studies have largely been limited to the rare 1100delC mutation. Whether common polymorphisms in this gene influence breast cancer risk remains unknown. In this study, we aimed to assess the importance of common CHEK2 variants on population risk for breast cancer by capturing the majority of diversity in the gene using haplotype tagging single nucle…

- sourceStage=fusion rank=3 `25742130` Mass screening programmes and trends in cervical cancer in Finland and the Netherlands.；dense=0.7731986，sparse=0.23203726，fusion=0.8462495216226559，rerank=null

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
| dense_raw | 16760369 |  | 18 |
| fusion |  | 16760369 | - |
| candidate_filter |  | 16760369 | - |
| pre_assembly |  | 16760369 | - |
| context_budget |  | 16760369 | - |

`dense`在`fusion/FUSION_THRESHOLD_OR_TOPK_LOSS`阶段排名靠前的非Gold文档：

- sourceStage=fusion rank=1 `4687948` HMG-CoA reductase inhibitors and the risk of hip fractures in elderly patients.；dense=0.7991014，sparse=null，fusion=0.8995507，rerank=null

  > CONTEXT Recent animal studies have found that 3-hydroxy-3-methylglutaryl coenzyme A (HMG-CoA) lipid-lowering drugs (statins) substantially increase bone formation, but whether statin use in humans results in clinically meaningful bone formation or a reduction in the risk of osteoporotic fractures is not known. OBJECTIVE To determine whether the use of statins is associated with reduced hip fracture risk. DESIGN Case-control study. SETTING AND PATIENTS A total of 6110 New Jersey residents aged 65 years or older and enrolled in Medicare and either Medicaid or the Pharmacy Assistance for the Aged…

- sourceStage=fusion rank=3 `11718220` Effectiveness of thigh-length graduated compression stockings to reduce the risk of deep vein thrombosis after stroke (CLOTS trial 1): a multicentre, randomised controlled trial；dense=0.7869238，sparse=null，fusion=0.8934618999999999，rerank=null

  > BACKGROUND Deep vein thrombosis (DVT) and pulmonary embolism are common after stroke. In small trials of patients undergoing surgery, graduated compression stockings (GCS) reduce the risk of DVT. National stroke guidelines extrapolating from these trials recommend their use in patients with stroke despite insufficient evidence. We assessed the effectiveness of thigh-length GCS to reduce DVT after stroke. METHODS In this outcome-blinded, randomised controlled trial, 2518 patients who were admitted to hospital within 1 week of an acute stroke and who were immobile were enrolled from 64 centres i…

- sourceStage=fusion rank=4 `13619127` Diabetes treatments and risk of amputation, blindness, severe kidney failure, hyperglycaemia, and hypoglycaemia: open cohort study in primary care；dense=0.78248787，sparse=null，fusion=0.891243935，rerank=null

  > OBJECTIVE To assess the risks of amputation, blindness, severe kidney failure, hyperglycaemia, and hypoglycaemia in patients with type 2 diabetes associated with prescribed diabetes drugs, particularly newer agents including gliptins or glitazones (thiazolidinediones). DESIGN Open cohort study in primary care. SETTING 1243 practices contributing data to the QResearch database in England. PARTICIPANTS 469,688 patients with type 2 diabetes aged 25-84 years between 1 April 2007 and 31 January 2015. EXPOSURES Hypoglycaemic agents (glitazones, gliptins, metformin, sulphonylureas, insulin, and other…

`sparse`在`fusion/FUSION_THRESHOLD_OR_TOPK_LOSS`阶段排名靠前的非Gold文档：

- sourceStage=fusion rank=1 `13734012` Prevalent abnormal prion protein in human appendixes after bovine spongiform encephalopathy epizootic: large scale survey；dense=null，sparse=0.31115437，fusion=0.2373132997299166，rerank=null

  > OBJECTIVES To carry out a further survey of archived appendix samples to understand better the differences between existing estimates of the prevalence of subclinical infection with prions after the bovine spongiform encephalopathy epizootic and to see whether a broader birth cohort was affected, and to understand better the implications for the management of blood and blood products and for the handling of surgical instruments. DESIGN Irreversibly unlinked and anonymised large scale survey of archived appendix samples. SETTING Archived appendix samples from the pathology departments of 41 UK…

- sourceStage=fusion rank=2 `4687948` HMG-CoA reductase inhibitors and the risk of hip fractures in elderly patients.；dense=null，sparse=0.30522293，fusion=0.23384735510277926，rerank=null

  > CONTEXT Recent animal studies have found that 3-hydroxy-3-methylglutaryl coenzyme A (HMG-CoA) lipid-lowering drugs (statins) substantially increase bone formation, but whether statin use in humans results in clinically meaningful bone formation or a reduction in the risk of osteoporotic fractures is not known. OBJECTIVE To determine whether the use of statins is associated with reduced hip fracture risk. DESIGN Case-control study. SETTING AND PATIENTS A total of 6110 New Jersey residents aged 65 years or older and enrolled in Medicare and either Medicaid or the Pharmacy Assistance for the Aged…

- sourceStage=fusion rank=3 `18340282` Gene–environment interactions in 7610 women with breast cancer: prospective evidence from the Million Women Study；dense=null，sparse=0.30165523，fusion=0.23174741133256924，rerank=null

  > BACKGROUND Information is scarce about the combined effects on breast cancer incidence of low-penetrance genetic susceptibility polymorphisms and environmental factors (reproductive, behavioural, and anthropometric risk factors for breast cancer). To test for evidence of gene-environment interactions, we compared genotypic relative risks for breast cancer across the other risk factors in a large UK prospective study. METHODS We tested gene-environment interactions in 7610 women who developed breast cancer and 10 196 controls without the disease, studying the effects of 12 polymorphisms (FGFR2-…

`hybrid_rrf`在`fusion/FUSION_THRESHOLD_OR_TOPK_LOSS`阶段排名靠前的非Gold文档：

- sourceStage=fusion rank=1 `4687948` HMG-CoA reductase inhibitors and the risk of hip fractures in elderly patients.；dense=0.79063344，sparse=0.30522293，fusion=0.9838709677419354，rerank=null

  > CONTEXT Recent animal studies have found that 3-hydroxy-3-methylglutaryl coenzyme A (HMG-CoA) lipid-lowering drugs (statins) substantially increase bone formation, but whether statin use in humans results in clinically meaningful bone formation or a reduction in the risk of osteoporotic fractures is not known. OBJECTIVE To determine whether the use of statins is associated with reduced hip fracture risk. DESIGN Case-control study. SETTING AND PATIENTS A total of 6110 New Jersey residents aged 65 years or older and enrolled in Medicare and either Medicaid or the Pharmacy Assistance for the Aged…

- sourceStage=fusion rank=4 `13734012` Prevalent abnormal prion protein in human appendixes after bovine spongiform encephalopathy epizootic: large scale survey；dense=0.7663518，sparse=0.31115437，fusion=0.8426966292134831，rerank=null

  > OBJECTIVES To carry out a further survey of archived appendix samples to understand better the differences between existing estimates of the prevalence of subclinical infection with prions after the bovine spongiform encephalopathy epizootic and to see whether a broader birth cohort was affected, and to understand better the implications for the management of blood and blood products and for the handling of surgical instruments. DESIGN Irreversibly unlinked and anonymised large scale survey of archived appendix samples. SETTING Archived appendix samples from the pathology departments of 41 UK…

- sourceStage=fusion rank=5 `24088502` Clinical outcomes following institution of the Canadian universal leukoreduction program for red blood cell transfusions.；dense=0.7633425，sparse=0.2907132，fusion=0.7653472740851381，rerank=null

  > CONTEXT A number of countries have implemented a policy of universal leukoreduction of their blood supply, but the potential role of leukoreduction in decreasing postoperative mortality and infection is unclear. OBJECTIVE To evaluate clinical outcomes following adoption of a national universal prestorage leukoreduction program for blood transfusions. DESIGN, SETTING, AND POPULATION Retrospective before-and-after cohort study conducted from August 1998 to August 2000 in 23 academic and community hospitals throughout Canada, enrolling 14 786 patients who received red blood cell transfusions foll…

`hybrid_rrf_rerank`在`fusion/FUSION_THRESHOLD_OR_TOPK_LOSS`阶段排名靠前的非Gold文档：

- sourceStage=fusion rank=1 `4687948` HMG-CoA reductase inhibitors and the risk of hip fractures in elderly patients.；dense=0.79063344，sparse=0.30522293，fusion=0.9838709677419354，rerank=null

  > CONTEXT Recent animal studies have found that 3-hydroxy-3-methylglutaryl coenzyme A (HMG-CoA) lipid-lowering drugs (statins) substantially increase bone formation, but whether statin use in humans results in clinically meaningful bone formation or a reduction in the risk of osteoporotic fractures is not known. OBJECTIVE To determine whether the use of statins is associated with reduced hip fracture risk. DESIGN Case-control study. SETTING AND PATIENTS A total of 6110 New Jersey residents aged 65 years or older and enrolled in Medicare and either Medicaid or the Pharmacy Assistance for the Aged…

- sourceStage=fusion rank=4 `13734012` Prevalent abnormal prion protein in human appendixes after bovine spongiform encephalopathy epizootic: large scale survey；dense=0.7663518，sparse=0.31115437，fusion=0.8426966292134831，rerank=null

  > OBJECTIVES To carry out a further survey of archived appendix samples to understand better the differences between existing estimates of the prevalence of subclinical infection with prions after the bovine spongiform encephalopathy epizootic and to see whether a broader birth cohort was affected, and to understand better the implications for the management of blood and blood products and for the handling of surgical instruments. DESIGN Irreversibly unlinked and anonymised large scale survey of archived appendix samples. SETTING Archived appendix samples from the pathology departments of 41 UK…

- sourceStage=fusion rank=5 `24088502` Clinical outcomes following institution of the Canadian universal leukoreduction program for red blood cell transfusions.；dense=0.7633425，sparse=0.2907132，fusion=0.7653472740851381，rerank=null

  > CONTEXT A number of countries have implemented a policy of universal leukoreduction of their blood supply, but the potential role of leukoreduction in decreasing postoperative mortality and infection is unclear. OBJECTIVE To evaluate clinical outcomes following adoption of a national universal prestorage leukoreduction program for blood transfusions. DESIGN, SETTING, AND POPULATION Retrospective before-and-after cohort study conducted from August 1998 to August 2000 in 23 academic and community hospitals throughout Canada, enrolling 14 786 patients who received red blood cell transfusions foll…

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
| dense_raw | 18399038 |  | 47 |
| fusion |  | 18399038 | - |
| candidate_filter |  | 18399038 | - |
| pre_assembly |  | 18399038 | - |
| context_budget |  | 18399038 | - |

`dense`在`fusion/FUSION_THRESHOLD_OR_TOPK_LOSS`阶段排名靠前的非Gold文档：

- sourceStage=fusion rank=1 `3863543` Mesenchymal Inflammation Drives Genotoxic Stress in Hematopoietic Stem Cells and Predicts Disease Evolution in Human Pre-leukemia.；dense=0.8230708，sparse=null，fusion=0.9115354，rerank=null

  > Mesenchymal niche cells may drive tissue failure and malignant transformation in the hematopoietic system, but the underlying molecular mechanisms and relevance to human disease remain poorly defined. Here, we show that perturbation of mesenchymal cells in a mouse model of the pre-leukemic disorder Shwachman-Diamond syndrome (SDS) induces mitochondrial dysfunction, oxidative stress, and activation of DNA damage responses in hematopoietic stem and progenitor cells. Massive parallel RNA sequencing of highly purified mesenchymal cells in the SDS mouse model and a range of human pre-leukemic syndr…

- sourceStage=fusion rank=2 `4388470` Somatic sex identity is cell-autonomous in the chicken；dense=0.81575125，sparse=null，fusion=0.907875625，rerank=null

  > In the mammalian model of sex determination, embryos are considered to be sexually indifferent until the transient action of a sex-determining gene initiates gonadal differentiation. Although this model is thought to apply to all vertebrates, this has yet to be established. Here we have examined three lateral gynandromorph chickens (a rare, naturally occurring phenomenon in which one side of the animal appears male and the other female) to investigate the sex-determining mechanism in birds. These studies demonstrated that gynandromorph birds are genuine male:female chimaeras, and indicated tha…

- sourceStage=fusion rank=3 `11369420` Tetraspanin 3 Is Required for the Development and Propagation of Acute Myelogenous Leukemia.；dense=0.8111024，sparse=null，fusion=0.9055512，rerank=null

  > Acute Myelogenous Leukemia (AML) is an aggressive cancer that strikes both adults and children and is frequently resistant to therapy. Thus, identifying signals needed for AML propagation is a critical step toward developing new approaches for treating this disease. Here, we show that Tetraspanin 3 is a target of the RNA binding protein Musashi 2, which plays a key role in AML. We generated Tspan3 knockout mice that were born without overt defects. However, Tspan3 deletion impaired leukemia stem cell self-renewal and disease propagation and markedly improved survival in mouse models of AML. Ad…

`sparse`在`fusion/FUSION_THRESHOLD_OR_TOPK_LOSS`阶段排名靠前的非Gold文档：

- sourceStage=fusion rank=1 `1606628` Estimates of global prevalence of childhood underweight in 1990 and 2015.；dense=null，sparse=0.31574675，fusion=0.23997532199870533，rerank=null

  > CONTEXT One key target of the United Nations Millennium Development goals is to reduce the prevalence of underweight among children younger than 5 years by half between 1990 and 2015. OBJECTIVE To estimate trends in childhood underweight by geographic regions of the world. DESIGN, SETTING, AND PARTICIPANTS Time series study of prevalence of underweight, defined as weight 2 SDs below the mean weight for age of the National Center for Health Statistics and World Health Organization (WHO) reference population. National prevalence rates derived from the WHO Global Database on Child Growth and Maln…

- sourceStage=fusion rank=2 `14637235` Histone levels are regulated by phosphorylation and ubiquitylation dependent proteolysis；dense=null，sparse=0.30849263，fusion=0.23576183994249933，rerank=null

  > Histone levels are tightly regulated to prevent harmful effects such as genomic instability and hypersensitivity to DNA-damaging agents due to the accumulation of these highly basic proteins when DNA replication slows down or stops. Although chromosomal histones are stable, excess (non-chromatin bound) histones are rapidly degraded in a Rad53 (radiation sensitive 53) kinase-dependent manner in Saccharomyces cerevisiae. Here we demonstrate that excess histones associate with Rad53 in vivo and seem to undergo modifications such as tyrosine phosphorylation and polyubiquitylation, before their pro…

- sourceStage=fusion rank=3 `4388470` Somatic sex identity is cell-autonomous in the chicken；dense=null，sparse=0.29399842，fusion=0.22720152934962626，rerank=null

  > In the mammalian model of sex determination, embryos are considered to be sexually indifferent until the transient action of a sex-determining gene initiates gonadal differentiation. Although this model is thought to apply to all vertebrates, this has yet to be established. Here we have examined three lateral gynandromorph chickens (a rare, naturally occurring phenomenon in which one side of the animal appears male and the other female) to investigate the sex-determining mechanism in birds. These studies demonstrated that gynandromorph birds are genuine male:female chimaeras, and indicated tha…

`hybrid_rrf`在`fusion/FUSION_THRESHOLD_OR_TOPK_LOSS`阶段排名靠前的非Gold文档：

- sourceStage=fusion rank=1 `4388470` Somatic sex identity is cell-autonomous in the chicken；dense=0.81575125，sparse=0.29399842，fusion=0.9760624679979518，rerank=null

  > In the mammalian model of sex determination, embryos are considered to be sexually indifferent until the transient action of a sex-determining gene initiates gonadal differentiation. Although this model is thought to apply to all vertebrates, this has yet to be established. Here we have examined three lateral gynandromorph chickens (a rare, naturally occurring phenomenon in which one side of the animal appears male and the other female) to investigate the sex-determining mechanism in birds. These studies demonstrated that gynandromorph birds are genuine male:female chimaeras, and indicated tha…

- sourceStage=fusion rank=2 `3863543` Mesenchymal Inflammation Drives Genotoxic Stress in Hematopoietic Stem Cells and Predicts Disease Evolution in Human Pre-leukemia.；dense=0.8230708，sparse=0.27997386，fusion=0.9621212121212123，rerank=null

  > Mesenchymal niche cells may drive tissue failure and malignant transformation in the hematopoietic system, but the underlying molecular mechanisms and relevance to human disease remain poorly defined. Here, we show that perturbation of mesenchymal cells in a mouse model of the pre-leukemic disorder Shwachman-Diamond syndrome (SDS) induces mitochondrial dysfunction, oxidative stress, and activation of DNA damage responses in hematopoietic stem and progenitor cells. Massive parallel RNA sequencing of highly purified mesenchymal cells in the SDS mouse model and a range of human pre-leukemic syndr…

- sourceStage=fusion rank=3 `19736671` Evolution of metastasis revealed by mutational landscapes of chemically induced skin cancers；dense=0.800969，sparse=0.26125172，fusion=0.7809034572733202，rerank=null

  > Human tumors show a high level of genetic heterogeneity, but the processes that influence the timing and route of metastatic dissemination of the subclones are unknown. Here we have used whole-exome sequencing of 103 matched benign, malignant and metastatic skin tumors from genetically heterogeneous mice to demonstrate that most metastases disseminate synchronously from the primary tumor, supporting parallel rather than linear evolution as the predominant model of metastasis. Shared mutations between primary carcinomas and their matched metastases have the distinct A-to-T signature of the init…

`hybrid_rrf_rerank`在`fusion/FUSION_THRESHOLD_OR_TOPK_LOSS`阶段排名靠前的非Gold文档：

- sourceStage=fusion rank=1 `4388470` Somatic sex identity is cell-autonomous in the chicken；dense=0.81575125，sparse=0.29399842，fusion=0.9760624679979518，rerank=null

  > In the mammalian model of sex determination, embryos are considered to be sexually indifferent until the transient action of a sex-determining gene initiates gonadal differentiation. Although this model is thought to apply to all vertebrates, this has yet to be established. Here we have examined three lateral gynandromorph chickens (a rare, naturally occurring phenomenon in which one side of the animal appears male and the other female) to investigate the sex-determining mechanism in birds. These studies demonstrated that gynandromorph birds are genuine male:female chimaeras, and indicated tha…

- sourceStage=fusion rank=2 `3863543` Mesenchymal Inflammation Drives Genotoxic Stress in Hematopoietic Stem Cells and Predicts Disease Evolution in Human Pre-leukemia.；dense=0.8230708，sparse=0.27997386，fusion=0.9621212121212123，rerank=null

  > Mesenchymal niche cells may drive tissue failure and malignant transformation in the hematopoietic system, but the underlying molecular mechanisms and relevance to human disease remain poorly defined. Here, we show that perturbation of mesenchymal cells in a mouse model of the pre-leukemic disorder Shwachman-Diamond syndrome (SDS) induces mitochondrial dysfunction, oxidative stress, and activation of DNA damage responses in hematopoietic stem and progenitor cells. Massive parallel RNA sequencing of highly purified mesenchymal cells in the SDS mouse model and a range of human pre-leukemic syndr…

- sourceStage=fusion rank=3 `19736671` Evolution of metastasis revealed by mutational landscapes of chemically induced skin cancers；dense=0.800969，sparse=0.26125172，fusion=0.7809034572733202，rerank=null

  > Human tumors show a high level of genetic heterogeneity, but the processes that influence the timing and route of metastatic dissemination of the subclones are unknown. Here we have used whole-exome sequencing of 103 matched benign, malignant and metastatic skin tumors from genetically heterogeneous mice to demonstrate that most metastases disseminate synchronously from the primary tumor, supporting parallel rather than linear evolution as the predominant model of metastasis. Shared mutations between primary carcinomas and their matched metastases have the distinct A-to-T signature of the init…

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
| hybrid_rrf_rerank | 未观察到Gold损失 | 未观察到Gold损失 | 是 | RERANK_ORDER_GAIN (MRR Δ=0.875000) |

重点失败变体：`hybrid_rrf_rerank`。首个内部失效结论：未观察到Gold损失。该结论级别为阶段轨迹派生，不外推为模型根因。

重点变体Gold逐阶段路径：

| 阶段 | Gold仍存在 | Gold已缺失 | Gold最佳名次 |
|---|---|---|---:|
| raw_union | 9650982 |  | - |
| fusion | 9650982 |  | 8 |
| candidate_filter | 9650982 |  | 8 |
| rerank_input | 9650982 |  | 8 |
| rerank_output | 9650982 |  | 1 |
| context_budget | 9650982 |  | 1 |

`sparse`在`fusion/FUSION_THRESHOLD_OR_TOPK_LOSS`阶段排名靠前的非Gold文档：

- sourceStage=fusion rank=1 `17930286` Headache, migraine, and structural brain lesions and function: population based Epidemiology of Vascular Ageing-MRI study；dense=null，sparse=0.26483282，fusion=0.20938167939064073，rerank=null

  > OBJECTIVE To evaluate the association of overall and specific headaches with volume of white matter hyperintensities, brain infarcts, and cognition. DESIGN Population based, cross sectional study. SETTING Epidemiology of Vascular Ageing study, Nantes, France. PARTICIPANTS 780 participants (mean age 69, 58.5% women) with detailed headache assessment. MAIN OUTCOME MEASURES Brain scans were evaluated for volume of white matter hyperintensities (by fully automated imaging processing) and for classification of infarcts (by visual reading with a standardised assessment grid). Cognitive function was…

- sourceStage=fusion rank=2 `2095573` LDL-cholesterol concentrations: a genome-wide association study；dense=null，sparse=0.2587512，fusion=0.20556182985168156，rerank=null

  > BACKGROUND LDL cholesterol has a causal role in the development of cardiovascular disease. Improved understanding of the biological mechanisms that underlie the metabolism and regulation of LDL cholesterol might help to identify novel therapeutic targets. We therefore did a genome-wide association study of LDL-cholesterol concentrations. METHODS We used genome-wide association data from up to 11,685 participants with measures of circulating LDL-cholesterol concentrations across five studies, including data for 293 461 autosomal single nucleotide polymorphisms (SNPs) with a minor allele frequen…

- sourceStage=fusion rank=3 `56893404` Macrosomia and Hyperinsulinaemic Hypoglycaemia in Patients with Heterozygous Mutations in the HNF4A Gene；dense=null，sparse=0.25848293，fusion=0.20539247997587062，rerank=null

  > Background  Macrosomia is associated with considerable neonatal and maternal morbidity. Factors that predict macrosomia are poorly understood. The increased rate of macrosomia in the offspring of pregnant women with diabetes and in congenital hyperinsulinaemia is mediated by increased foetal insulin secretion. We assessed the in utero and neonatal role of two key regulators of pancreatic insulin secretion by studying birthweight and the incidence of neonatal hypoglycaemia in patients with heterozygous mutations in the maturity-onset diabetes of the young (MODY) genes HNF4A (encoding HNF-4α) an…

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
| hybrid_rrf_rerank | 未观察到Gold损失 | 未观察到Gold损失 | 是 | RERANK_ORDER_HARM (MRR Δ=-0.750000) |

重点失败变体：`hybrid_rrf_rerank`。首个内部失效结论：未观察到Gold损失。该结论级别为阶段轨迹派生，不外推为模型根因。

重点变体Gold逐阶段路径：

| 阶段 | Gold仍存在 | Gold已缺失 | Gold最佳名次 |
|---|---|---|---:|
| raw_union | 10874408 |  | - |
| fusion | 10874408 |  | 1 |
| candidate_filter | 10874408 |  | 1 |
| rerank_input | 10874408 |  | 1 |
| rerank_output | 10874408 |  | 4 |
| context_budget | 10874408 |  | 4 |

## queryId=508

问题：Hematopoietic Stem Cell purification reaches purity rate of up to 50%.

原分类：`rerank_reorder_harm`

Gold文档：

- `13980338` Combined Single-Cell Functional and Gene Expression Analysis Resolves Heterogeneity within Stem Cell Populations

  > Heterogeneity within the self-renewal durability of adult hematopoietic stem cells (HSCs) challenges our understanding of the molecular framework underlying HSC function. Gene expression studies have been hampered by the presence of multiple HSC subtypes and contaminating non-HSCs in bulk HSC populations. To gain deeper insight into the gene expression program of murine HSCs, we combined single-cell functional assays with flow cytometric index sorting and single-cell gene expression assays. Through bioinformatic integration of these datasets, we designed an unbiased sorting strategy that separ…

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
| raw_union | 13980338 |  | - |
| fusion | 13980338 |  | 1 |
| candidate_filter | 13980338 |  | 1 |
| rerank_input | 13980338 |  | 1 |
| rerank_output | 13980338 |  | 4 |
| context_budget | 13980338 |  | 4 |

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
| hybrid_rrf_rerank | 未观察到Gold损失 | 未观察到Gold损失 | 是 | RERANK_ORDER_HARM (MRR Δ=-0.666667) |

重点失败变体：`hybrid_rrf_rerank`。首个内部失效结论：未观察到Gold损失。该结论级别为阶段轨迹派生，不外推为模型根因。

重点变体Gold逐阶段路径：

| 阶段 | Gold仍存在 | Gold已缺失 | Gold最佳名次 |
|---|---|---|---:|
| raw_union | 13625993 |  | - |
| fusion | 13625993 |  | 1 |
| candidate_filter | 13625993 |  | 1 |
| rerank_input | 13625993 |  | 1 |
| rerank_output | 13625993 |  | 3 |
| context_budget | 13625993 |  | 3 |

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

## 输入SHA-256

- failureReport: `f052d702e772e97b2d01cb06ecc797e2dca3027deca1454d3de54936527738e7`
- diagnostics: `1f1039b37ec4431d0b1b633661d70f48c1f0ed55c54c65840c06eef068771384`
- diagnosticManifest: `3d1bf47d17b8557f495de2384cbc26e787fc07284551642477ea61dcc905edc0`
- qrels: `2a808171a79832d5798afb879c2d912f5c8863b09c6427fe454f20dc2a025f73`
- documents: `7e1479ca549e3e48dd442b03770e88f160ef90334a8e18f09cfa6349fee24e08`
- documentMap: `8a93c2134c689d3fd78d90ddee9414b3a08bc43e20b56ef55d781ea9f61ef17b`
