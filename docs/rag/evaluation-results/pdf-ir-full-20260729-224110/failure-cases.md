# RAG召回失败案例可复算报告

生成器：rag-failure-case-v1；查询数：200；run记录数：800。

## 证据边界

- run只保存最终Top10文档ID，没有逐候选分数或Dense/Sparse内部候选ID；对应字段明确标记为未采集。
- 首个失败步骤是基于四个消融终态排名的首个可观测步骤，不等同于内部算子级因果证明。
- 词项重合只用于提出可证伪推断，不作为失败原因的直接证明。

## 分类总账

| 类别 | 全量案例数 | 展示数 |
|---|---:|---:|
| dense_miss_hybrid_hit | 5 | 5 |
| sparse_miss_hybrid_hit | 21 | 5 |
| rerank_rescue | 0 | 0 |
| rerank_harm | 0 | 0 |
| dense_only_success | 30 | 5 |
| sparse_only_success | 2 | 2 |
| persistent_miss | 3 | 3 |
| rerank_reorder_gain | 28 | 5 |
| rerank_reorder_harm | 16 | 5 |

## dense_miss_hybrid_hit

### queryId=1

问题：0-dimensional biomaterials show inductive properties.

Gold文档：

- `31715818` New opportunities: the use of nanotechnologies to manipulate and track stem cells.

  > Nanotechnologies are emerging platforms that could be useful in measuring, understanding, and manipulating stem cells. Examples include magnetic nanoparticles and quantum dots for stem cell labeling and in vivo tracking; nanoparticles, carbon nanotubes, and polyplexes for the intracellular delivery of genes/oligonucleotides and protein/peptides; and engineered nanometer-scale scaffolds for stem cell differentiation and transplantation. This review examines the use of nanotechnologies for stem cell tracking, differentiation, and transplantation. We further discuss their utility and the potentia…

  本地源文件： `DOCX=prepared/docx/168-scifact-31715818.docx` `PDF=prepared/pdf/168-scifact-31715818.pdf`

| 变体 | Recall@10 | MRR@10 | nDCG@10 | Gold首名次 | 延迟ms | Top10文档ID（*为gold） |
|---|---:|---:|---:|---:|---:|---|
| dense | 0.000000 | 0.000000 | 0.000000 | Top10未命中 | 2239 | 8290953, 4388470, 12631697, 1469751, 11172205, 2177022, 11419230, 5373138, 3475317 |
| sparse | 0.000000 | 0.000000 | 0.000000 | Top10未命中 | 2218 | 3441524, 10582939, 15663829, 1471041, 13519661, 10984005, 24088502, 17077004, 19675911, 1642727 |
| hybrid_rrf | 1.000000 | 1.000000 | 1.000000 | 1 | 2519 | 31715818*, 10582939, 3441524, 24088502, 4456756, 4350400, 4381486, 1642727, 7521113, 24338780 |
| hybrid_rrf_rerank | 1.000000 | 0.111111 | 0.301030 | 9 | 17617 | 24338780, 4381486, 10582939, 4350400, 4456756, 1642727, 3441524, 24088502, 31715818*, 7521113 |

首个失败步骤中的关键错误召回文档（前3条非gold）：

- rank=1 `8290953` Scaffold-based three-dimensional human fibroblast culture provides a structural matrix that supports angiogenesis in infarcted heart tissue.（本地heading=`SCIFACT-EVIDENCE-8290953`）

  > BACKGROUND We have developed techniques to implant angiogenic patches onto the epicardium over regions of infarcted cardiac tissue to stimulate revascularization of the damaged tissue. These experiments used a scaffold-based 3D human dermal fibroblast culture (3DFC) as an epicardial patch. The 3DFC contains viable cells that secrete angiogenic growth factors and has previously been shown to stimulate angiogenic activity. The hypothesis tested was that a viable 3DFC cardiac patch would stimulate an angiogenic response within an area of infarcted cardiac tissue. METHODS AND RESULTS A coronary oc…

  本地源文件： `DOCX=prepared/docx/030-scifact-8290953.docx` `PDF=prepared/pdf/030-scifact-8290953.pdf`
- rank=2 `4388470` Somatic sex identity is cell-autonomous in the chicken（本地heading=`SCIFACT-EVIDENCE-4388470`）

  > In the mammalian model of sex determination, embryos are considered to be sexually indifferent until the transient action of a sex-determining gene initiates gonadal differentiation. Although this model is thought to apply to all vertebrates, this has yet to be established. Here we have examined three lateral gynandromorph chickens (a rare, naturally occurring phenomenon in which one side of the animal appears male and the other female) to investigate the sex-determining mechanism in birds. These studies demonstrated that gynandromorph birds are genuine male:female chimaeras, and indicated tha…

  本地源文件： `DOCX=prepared/docx/135-scifact-4388470.docx` `PDF=prepared/pdf/135-scifact-4388470.pdf`
- rank=3 `12631697` Activation of fast skeletal muscle troponin as a potential therapeutic approach for treating neuromuscular diseases（本地heading=`SCIFACT-EVIDENCE-12631697`）

  > Limited neural input results in muscle weakness in neuromuscular disease because of a reduction in the density of muscle innervation, the rate of neuromuscular junction activation or the efficiency of synaptic transmission. We developed a small-molecule fast-skeletal-troponin activator, CK-2017357, as a means to increase muscle strength by amplifying the response of muscle when neural input is otherwise diminished secondary to neuromuscular disease. Binding selectively to the fast-skeletal-troponin complex, CK-2017357 slows the rate of calcium release from troponin C and sensitizes muscle to c…

  本地源文件： `DOCX=prepared/docx/042-scifact-12631697.docx` `PDF=prepared/pdf/042-scifact-12631697.pdf`

首个可观测失败步骤：`dense_final_top10`。

直接证据：

- Dense gold首名次=Top10未命中
- Sparse gold首名次=Top10未命中
- Hybrid-RRF gold首名次=1
- Hybrid-RRF+Rerank gold首名次=9
- 分类规则=dense_miss_hybrid_hit
- 逐候选分数=未采集

推断：Top10截断、语义近邻竞争或Rerank排序偏好可能共同影响；query与gold最大词项Jaccard=0.0000。

其他可能解释：相关性标注不完整、gold摘要与claim粒度不同、分块边界或Top10截断；当前终态run不能排除这些因素（dense_miss_hybrid_hit）。

反证实验：固定同一索引和query，额外留存Dense/Sparse候选Top100、融合逐项贡献、Rerank输入输出分数及chunk文本，再复跑该query验证首个内部失效点（dense_miss_hybrid_hit）。

### queryId=1363

问题：Venules have a thinner or absent smooth layer compared to arterioles.

Gold文档：

- `8290953` Scaffold-based three-dimensional human fibroblast culture provides a structural matrix that supports angiogenesis in infarcted heart tissue.

  > BACKGROUND We have developed techniques to implant angiogenic patches onto the epicardium over regions of infarcted cardiac tissue to stimulate revascularization of the damaged tissue. These experiments used a scaffold-based 3D human dermal fibroblast culture (3DFC) as an epicardial patch. The 3DFC contains viable cells that secrete angiogenic growth factors and has previously been shown to stimulate angiogenic activity. The hypothesis tested was that a viable 3DFC cardiac patch would stimulate an angiogenic response within an area of infarcted cardiac tissue. METHODS AND RESULTS A coronary oc…

  本地源文件： `DOCX=prepared/docx/030-scifact-8290953.docx` `PDF=prepared/pdf/030-scifact-8290953.pdf`

| 变体 | Recall@10 | MRR@10 | nDCG@10 | Gold首名次 | 延迟ms | Top10文档ID（*为gold） |
|---|---:|---:|---:|---:|---:|---|
| dense | 0.000000 | 0.000000 | 0.000000 | Top10未命中 | 3186 | 4387784, 17741440, 2425364, 12991445, 4423559, 16760369, 18174210, 11718220, 13625993 |
| sparse | 1.000000 | 0.333333 | 0.500000 | 3 | 2332 | 13619127, 17077004, 8290953*, 32159283, 27768226, 23649163, 25742130 |
| hybrid_rrf | 1.000000 | 0.500000 | 0.630930 | 2 | 3145 | 17077004, 8290953*, 13734012, 11718220, 16495649, 123859, 13625993, 16760369, 23649163 |
| hybrid_rrf_rerank | 1.000000 | 1.000000 | 1.000000 | 1 | 10487 | 8290953*, 11718220, 16495649, 16760369, 23649163, 123859, 13625993, 13734012, 17077004 |

首个失败步骤中的关键错误召回文档（前3条非gold）：

- rank=1 `4387784` Structure of the proton-gated urea channel from the gastric pathogen Helicobacter pylori（本地heading=`SCIFACT-EVIDENCE-4387784`）

  > Half the world's population is chronically infected with Helicobacter pylori, causing gastritis, gastric ulcers and an increased incidence of gastric adenocarcinoma. Its proton-gated inner-membrane urea channel, HpUreI, is essential for survival in the acidic environment of the stomach. The channel is closed at neutral pH and opens at acidic pH to allow the rapid access of urea to cytoplasmic urease. Urease produces NH(3) and CO(2), neutralizing entering protons and thus buffering the periplasm to a pH of roughly 6.1 even in gastric juice at a pH below 2.0. Here we report the structure of HpUr…

  本地源文件： `DOCX=prepared/docx/020-scifact-4387784.docx` `PDF=prepared/pdf/020-scifact-4387784.pdf`
- rank=2 `17741440` Netting neutrophils in autoimmune small-vessel vasculitis（本地heading=`SCIFACT-EVIDENCE-17741440`）

  > Small-vessel vasculitis (SVV) is a chronic autoinflammatory condition linked to antineutrophil cytoplasm autoantibodies (ANCAs). Here we show that chromatin fibers, so-called neutrophil extracellular traps (NETs), are released by ANCA-stimulated neutrophils and contain the targeted autoantigens proteinase-3 (PR3) and myeloperoxidase (MPO). Deposition of NETs in inflamed kidneys and circulating MPO-DNA complexes suggest that NET formation triggers vasculitis and promotes the autoimmune response against neutrophil components in individuals with SVV.

  本地源文件： `DOCX=prepared/docx/124-scifact-17741440.docx` `PDF=prepared/pdf/124-scifact-17741440.pdf`
- rank=3 `2425364` Association between maternal serum 25-hydroxyvitamin D level and pregnancy and neonatal outcomes: systematic review and meta-analysis of observational studies.（本地heading=`SCIFACT-EVIDENCE-2425364`）

  > OBJECTIVE To assess the effect of 25-hydroxyvitamin D (25-OHD) levels on pregnancy outcomes and birth variables. DESIGN Systematic review and meta-analysis. DATA SOURCES Medline (1966 to August 2012), PubMed (2008 to August 2012), Embase (1980 to August 2012), CINAHL (1981 to August 2012), the Cochrane database of systematic reviews, and the Cochrane database of registered clinical trials. STUDY SELECTION Studies reporting on the association between serum 25-OHD levels during pregnancy and the outcomes of interest (pre-eclampsia, gestational diabetes, bacterial vaginosis, caesarean section, sm…

  本地源文件： `DOCX=prepared/docx/104-scifact-2425364.docx` `PDF=prepared/pdf/104-scifact-2425364.pdf`

首个可观测失败步骤：`dense_final_top10`。

直接证据：

- Dense gold首名次=Top10未命中
- Sparse gold首名次=3
- Hybrid-RRF gold首名次=2
- Hybrid-RRF+Rerank gold首名次=1
- 分类规则=dense_miss_hybrid_hit
- 逐候选分数=未采集

推断：Dense语义向量把主题相近干扰文档排在gold之前，而精确术语帮助Sparse命中；最大词项Jaccard=0.0260。

其他可能解释：相关性标注不完整、gold摘要与claim粒度不同、分块边界或Top10截断；当前终态run不能排除这些因素（dense_miss_hybrid_hit）。

反证实验：固定同一索引和query，额外留存Dense/Sparse候选Top100、融合逐项贡献、Rerank输入输出分数及chunk文本，再复跑该query验证首个内部失效点（dense_miss_hybrid_hit）。

### queryId=502

问题：Healthcare delivery efficiency in crowded delivery centers is impaired by improving structural, logistical, and interpersonal elements.

Gold文档：

- `13071728` The HIV Treatment Gap: Estimates of the Financial Resources Needed versus Available for Scale-Up of Antiretroviral Therapy in 97 Countries from 2015 to 2020

  > BACKGROUND The World Health Organization (WHO) released revised guidelines in 2015 recommending that all people living with HIV, regardless of CD4 count, initiate antiretroviral therapy (ART) upon diagnosis. However, few studies have projected the global resources needed for rapid scale-up of ART. Under the Health Policy Project, we conducted modeling analyses for 97 countries to estimate eligibility for and numbers on ART from 2015 to 2020, along with the facility-level financial resources required. We compared the estimated financial requirements to estimated funding available. METHODS AND F…

  本地源文件： `DOCX=prepared/docx/162-scifact-13071728.docx` `PDF=prepared/pdf/162-scifact-13071728.pdf`

| 变体 | Recall@10 | MRR@10 | nDCG@10 | Gold首名次 | 延迟ms | Top10文档ID（*为gold） |
|---|---:|---:|---:|---:|---:|---|
| dense | 0.000000 | 0.000000 | 0.000000 | Top10未命中 | 2197 | 25649714, 13625993, 13906581, 16495649, 1642727, 13843341, 1215116, 2177022 |
| sparse | 0.000000 | 0.000000 | 0.000000 | Top10未命中 | 1683 | 15928989, 30303335, 31715818, 18909530, 5531479, 20231138, 8774475, 2425364, 39381118, 36606083 |
| hybrid_rrf | 1.000000 | 0.200000 | 0.386853 | 5 | 1797 | 15928989, 13906581, 1469751, 3475317, 13071728*, 2425364, 5531479, 13905670, 13770184, 12789595 |
| hybrid_rrf_rerank | 1.000000 | 1.000000 | 1.000000 | 1 | 7245 | 13071728*, 13906581, 5531479, 3475317, 15928989, 2425364, 13905670, 12789595, 13770184, 1469751 |

首个失败步骤中的关键错误召回文档（前3条非gold）：

- rank=1 `25649714` Mental health problems of homeless children and families: longitudinal study.（本地heading=`SCIFACT-EVIDENCE-25649714`）

  > OBJECTIVE To establish the mental health needs of homeless children and families before and after rehousing. DESIGN Cross sectional, longitudinal study. SETTING City of Birmingham. SUBJECTS 58 rehoused families with 103 children aged 2-16 years and 21 comparison families of low socioeconomic status in stable housing, with 54 children. MAIN OUTCOME MEASURES Children's mental health problems and level of communication; mothers' mental health problems and social support one year after rehousing. RESULTS Mental health problems remained significantly higher in rehoused mothers and their children th…

  本地源文件： `DOCX=prepared/docx/003-scifact-25649714.docx` `PDF=prepared/pdf/003-scifact-25649714.pdf`
- rank=2 `13625993` Assessing the cost effectiveness of using prognostic biomarkers with decision models: case study in prioritising patients waiting for coronary artery surgery（本地heading=`SCIFACT-EVIDENCE-13625993`）

  > OBJECTIVE To determine the effectiveness and cost effectiveness of using information from circulating biomarkers to inform the prioritisation process of patients with stable angina awaiting coronary artery bypass graft surgery. DESIGN Decision analytical model comparing four prioritisation strategies without biomarkers (no formal prioritisation, two urgency scores, and a risk score) and three strategies based on a risk score using biomarkers: a routinely assessed biomarker (estimated glomerular filtration rate), a novel biomarker (C reactive protein), or both. The order in which to perform cor…

  本地源文件： `DOCX=prepared/docx/180-scifact-13625993.docx` `PDF=prepared/pdf/180-scifact-13625993.pdf`
- rank=3 `13906581` Patient Outcomes with Teaching Versus Nonteaching Healthcare: A Systematic Review（本地heading=`SCIFACT-EVIDENCE-13906581`）

  > Background  Extensive debate exists in the healthcare community over whether outcomes of medical care at teaching hospitals and other healthcare units are better or worse than those at the respective nonteaching ones. Thus, our goal was to systematically evaluate the evidence pertaining to this question. Methods and Findings  We reviewed all studies that compared teaching versus nonteaching healthcare structures for mortality or any other patient outcome, regardless of health condition. Studies were retrieved from PubMed, contact with experts, and literature cross-referencing. Data were extrac…

  本地源文件： `DOCX=prepared/docx/196-scifact-13906581.docx` `PDF=prepared/pdf/196-scifact-13906581.pdf`

首个可观测失败步骤：`dense_final_top10`。

直接证据：

- Dense gold首名次=Top10未命中
- Sparse gold首名次=Top10未命中
- Hybrid-RRF gold首名次=5
- Hybrid-RRF+Rerank gold首名次=1
- 分类规则=dense_miss_hybrid_hit
- 逐候选分数=未采集

推断：Top10截断、语义近邻竞争或Rerank排序偏好可能共同影响；query与gold最大词项Jaccard=0.0235。

其他可能解释：相关性标注不完整、gold摘要与claim粒度不同、分块边界或Top10截断；当前终态run不能排除这些因素（dense_miss_hybrid_hit）。

反证实验：固定同一索引和query，额外留存Dense/Sparse候选Top100、融合逐项贡献、Rerank输入输出分数及chunk文本，再复跑该query验证首个内部失效点（dense_miss_hybrid_hit）。

### queryId=517

问题：High levels of copeptin decrease risk of diabetes.

Gold文档：

- `15663829` Mendelian Randomization Study of B-Type Natriuretic Peptide and Type 2 Diabetes: Evidence of Causal Association from Population Studies

  > BACKGROUND Genetic and epidemiological evidence suggests an inverse association between B-type natriuretic peptide (BNP) levels in blood and risk of type 2 diabetes (T2D), but the prospective association of BNP with T2D is uncertain, and it is unclear whether the association is confounded. METHODS AND FINDINGS We analysed the association between levels of the N-terminal fragment of pro-BNP (NT-pro-BNP) in blood and risk of incident T2D in a prospective case-cohort study and genotyped the variant rs198389 within the BNP locus in three T2D case-control studies. We combined our results with exist…

  本地源文件： `DOCX=prepared/docx/062-scifact-15663829.docx` `PDF=prepared/pdf/062-scifact-15663829.pdf`

| 变体 | Recall@10 | MRR@10 | nDCG@10 | Gold首名次 | 延迟ms | Top10文档ID（*为gold） |
|---|---:|---:|---:|---:|---:|---|
| dense | 0.000000 | 0.000000 | 0.000000 | Top10未命中 | 1642 | 970012, 13619127, 16760369, 13282296, 3553087, 29564505 |
| sparse | 1.000000 | 0.333333 | 0.500000 | 3 | 1627 | 13282296, 2425364, 15663829*, 16760369, 791050, 18340282, 10582939, 4687948, 17755060 |
| hybrid_rrf | 1.000000 | 0.142857 | 0.333333 | 7 | 1815 | 13282296, 16760369, 2425364, 29564505, 970012, 13619127, 15663829*, 4687948 |
| hybrid_rrf_rerank | 1.000000 | 1.000000 | 1.000000 | 1 | 7957 | 15663829*, 13619127, 29564505, 13282296, 2425364, 16760369, 970012, 4687948 |

首个失败步骤中的关键错误召回文档（前3条非gold）：

- rank=1 `970012` Cold Exposure Promotes Atherosclerotic Plaque Growth and Instability via UCP1-Dependent Lipolysis（本地heading=`SCIFACT-EVIDENCE-970012`）

  > Molecular mechanisms underlying the cold-associated high cardiovascular risk remain unknown. Here, we show that the cold-triggered food-intake-independent lipolysis significantly increased plasma levels of small low-density lipoprotein (LDL) remnants, leading to accelerated development of atherosclerotic lesions in mice. In two genetic mouse knockout models (apolipoprotein E(-/-) [ApoE(-/-)] and LDL receptor(-/-) [Ldlr(-/-)] mice), persistent cold exposure stimulated atherosclerotic plaque growth by increasing lipid deposition. Furthermore, marked increase of inflammatory cells and plaque-asso…

  本地源文件： `DOCX=prepared/docx/047-scifact-970012.docx` `PDF=prepared/pdf/047-scifact-970012.pdf`
- rank=2 `13619127` Diabetes treatments and risk of amputation, blindness, severe kidney failure, hyperglycaemia, and hypoglycaemia: open cohort study in primary care（本地heading=`SCIFACT-EVIDENCE-13619127`）

  > OBJECTIVE To assess the risks of amputation, blindness, severe kidney failure, hyperglycaemia, and hypoglycaemia in patients with type 2 diabetes associated with prescribed diabetes drugs, particularly newer agents including gliptins or glitazones (thiazolidinediones). DESIGN Open cohort study in primary care. SETTING 1243 practices contributing data to the QResearch database in England. PARTICIPANTS 469,688 patients with type 2 diabetes aged 25-84 years between 1 April 2007 and 31 January 2015. EXPOSURES Hypoglycaemic agents (glitazones, gliptins, metformin, sulphonylureas, insulin, and other…

  本地源文件： `DOCX=prepared/docx/025-scifact-13619127.docx` `PDF=prepared/pdf/025-scifact-13619127.pdf`
- rank=3 `16760369` Comparative determinants of 4-year cardiovascular event rates in stable outpatients at risk of or with atherothrombosis.（本地heading=`SCIFACT-EVIDENCE-16760369`）

  > CONTEXT Clinicians and trialists have difficulty with identifying which patients are highest risk for cardiovascular events. Prior ischemic events, polyvascular disease, and diabetes mellitus have all been identified as predictors of ischemic events, but their comparative contributions to future risk remain unclear. OBJECTIVE To categorize the risk of cardiovascular events in stable outpatients with various initial manifestations of atherothrombosis using simple clinical descriptors. DESIGN, SETTING, AND PATIENTS Outpatients with coronary artery disease, cerebrovascular disease, or peripheral…

  本地源文件： `DOCX=prepared/docx/179-scifact-16760369.docx` `PDF=prepared/pdf/179-scifact-16760369.pdf`

首个可观测失败步骤：`dense_final_top10`。

直接证据：

- Dense gold首名次=Top10未命中
- Sparse gold首名次=3
- Hybrid-RRF gold首名次=7
- Hybrid-RRF+Rerank gold首名次=1
- 分类规则=dense_miss_hybrid_hit
- 逐候选分数=未采集

推断：Dense语义向量把主题相近干扰文档排在gold之前，而精确术语帮助Sparse命中；最大词项Jaccard=0.0635。

其他可能解释：相关性标注不完整、gold摘要与claim粒度不同、分块边界或Top10截断；当前终态run不能排除这些因素（dense_miss_hybrid_hit）。

反证实验：固定同一索引和query，额外留存Dense/Sparse候选Top100、融合逐项贡献、Rerank输入输出分数及chunk文本，再复跑该query验证首个内部失效点（dense_miss_hybrid_hit）。

### queryId=768

问题：Mercaptopurine is anabolized into the inactive methylmercaptopurine by thiopurine methyltrasnferase (TPMT).

Gold文档：

- `6421792` Activating mutations in the NT5C2 nucleotidase gene drive chemotherapy resistance in relapsed ALL

  > Acute lymphoblastic leukemia (ALL) is an aggressive hematological tumor resulting from the malignant transformation of lymphoid progenitors. Despite intensive chemotherapy, 20% of pediatric patients and over 50% of adult patients with ALL do not achieve a complete remission or relapse after intensified chemotherapy, making disease relapse and resistance to therapy the most substantial challenge in the treatment of this disease. Using whole-exome sequencing, we identify mutations in the cytosolic 5'-nucleotidase II gene (NT5C2), which encodes a 5'-nucleotidase enzyme that is responsible for the…

  本地源文件： `DOCX=prepared/docx/051-scifact-6421792.docx` `PDF=prepared/pdf/051-scifact-6421792.pdf`

| 变体 | Recall@10 | MRR@10 | nDCG@10 | Gold首名次 | 延迟ms | Top10文档ID（*为gold） |
|---|---:|---:|---:|---:|---:|---|
| dense | 0.000000 | 0.000000 | 0.000000 | Top10未命中 | 3063 | 11603066, 24221369, 20231138, 3441524, 52873726, 23895668, 4387784, 1469751, 18421962 |
| sparse | 0.000000 | 0.000000 | 0.000000 | Top10未命中 | 1809 | 20381484, 56893404, 17741440, 11603066, 24294572, 6923961, 33499189, 23460562, 18174210, 17717391 |
| hybrid_rrf | 1.000000 | 0.111111 | 0.301030 | 9 | 1954 | 11603066, 24221369, 33499189, 24294572, 24341590, 20381484, 4423559, 11419230, 6421792*, 16472469 |
| hybrid_rrf_rerank | 1.000000 | 1.000000 | 1.000000 | 1 | 12683 | 6421792*, 24341590, 20381484, 11603066, 16472469, 24221369, 24294572, 4423559, 11419230, 33499189 |

首个失败步骤中的关键错误召回文档（前3条非gold）：

- rank=1 `11603066` Using Structural Information to Change the Phosphotransfer Specificity of a Two-Component Chemotaxis Signalling Complex（本地heading=`SCIFACT-EVIDENCE-11603066`）

  > Two-component signal transduction pathways comprising histidine protein kinases (HPKs) and their response regulators (RRs) are widely used to control bacterial responses to environmental challenges. Some bacteria have over 150 different two-component pathways, and the specificity of the phosphotransfer reactions within these systems is tightly controlled to prevent unwanted crosstalk. One of the best understood two-component signalling pathways is the chemotaxis pathway. Here, we present the 1.40 A crystal structure of the histidine-containing phosphotransfer domain of the chemotaxis HPK, CheA…

  本地源文件： `DOCX=prepared/docx/170-scifact-11603066.docx` `PDF=prepared/pdf/170-scifact-11603066.pdf`
- rank=2 `24221369` A Conserved Histidine in the RNA Sensor RIG-I Controls Immune Tolerance to N1-2'O-Methylated Self RNA.（本地heading=`SCIFACT-EVIDENCE-24221369`）

  > The cytosolic helicase retinoic acid-inducible gene-I (RIG-I) initiates immune responses to most RNA viruses by detecting viral 5'-triphosphorylated RNA (pppRNA). Although endogenous mRNA is also 5'-triphosphorylated, backbone modifications and the 5'-ppp-linked methylguanosine ((m7)G) cap prevent immunorecognition. Here we show that the methylation status of endogenous capped mRNA at the 5'-terminal nucleotide (N1) was crucial to prevent RIG-I activation. Moreover, we identified a single conserved amino acid (H830) in the RIG-I RNA binding pocket as the mediator of steric exclusion of N1-2'O-…

  本地源文件： `DOCX=prepared/docx/189-scifact-24221369.docx` `PDF=prepared/pdf/189-scifact-24221369.pdf`
- rank=3 `20231138` Replication Fork Slowing and Reversal upon DNA Damage Require PCNA Polyubiquitination and ZRANB3 DNA Translocase Activity（本地heading=`SCIFACT-EVIDENCE-20231138`）

  > DNA damage tolerance during eukaryotic replication is orchestrated by PCNA ubiquitination. While monoubiquitination activates mutagenic translesion synthesis, polyubiquitination activates an error-free pathway, elusive in mammals, enabling damage bypass by template switching. Fork reversal is driven in vitro by multiple enzymes, including the DNA translocase ZRANB3, shown to bind polyubiquitinated PCNA. However, whether this interaction promotes fork remodeling and template switching in vivo was unknown. Here we show that damage-induced fork reversal in mammalian cells requires PCNA ubiquitina…

  本地源文件： `DOCX=prepared/docx/146-scifact-20231138.docx` `PDF=prepared/pdf/146-scifact-20231138.pdf`

首个可观测失败步骤：`dense_final_top10`。

直接证据：

- Dense gold首名次=Top10未命中
- Sparse gold首名次=Top10未命中
- Hybrid-RRF gold首名次=9
- Hybrid-RRF+Rerank gold首名次=1
- 分类规则=dense_miss_hybrid_hit
- 逐候选分数=未采集

推断：Top10截断、语义近邻竞争或Rerank排序偏好可能共同影响；query与gold最大词项Jaccard=0.0256。

其他可能解释：相关性标注不完整、gold摘要与claim粒度不同、分块边界或Top10截断；当前终态run不能排除这些因素（dense_miss_hybrid_hit）。

反证实验：固定同一索引和query，额外留存Dense/Sparse候选Top100、融合逐项贡献、Rerank输入输出分数及chunk文本，再复跑该query验证首个内部失效点（dense_miss_hybrid_hit）。

## sparse_miss_hybrid_hit

### queryId=1

问题：0-dimensional biomaterials show inductive properties.

Gold文档：

- `31715818` New opportunities: the use of nanotechnologies to manipulate and track stem cells.

  > Nanotechnologies are emerging platforms that could be useful in measuring, understanding, and manipulating stem cells. Examples include magnetic nanoparticles and quantum dots for stem cell labeling and in vivo tracking; nanoparticles, carbon nanotubes, and polyplexes for the intracellular delivery of genes/oligonucleotides and protein/peptides; and engineered nanometer-scale scaffolds for stem cell differentiation and transplantation. This review examines the use of nanotechnologies for stem cell tracking, differentiation, and transplantation. We further discuss their utility and the potentia…

  本地源文件： `DOCX=prepared/docx/168-scifact-31715818.docx` `PDF=prepared/pdf/168-scifact-31715818.pdf`

| 变体 | Recall@10 | MRR@10 | nDCG@10 | Gold首名次 | 延迟ms | Top10文档ID（*为gold） |
|---|---:|---:|---:|---:|---:|---|
| dense | 0.000000 | 0.000000 | 0.000000 | Top10未命中 | 2239 | 8290953, 4388470, 12631697, 1469751, 11172205, 2177022, 11419230, 5373138, 3475317 |
| sparse | 0.000000 | 0.000000 | 0.000000 | Top10未命中 | 2218 | 3441524, 10582939, 15663829, 1471041, 13519661, 10984005, 24088502, 17077004, 19675911, 1642727 |
| hybrid_rrf | 1.000000 | 1.000000 | 1.000000 | 1 | 2519 | 31715818*, 10582939, 3441524, 24088502, 4456756, 4350400, 4381486, 1642727, 7521113, 24338780 |
| hybrid_rrf_rerank | 1.000000 | 0.111111 | 0.301030 | 9 | 17617 | 24338780, 4381486, 10582939, 4350400, 4456756, 1642727, 3441524, 24088502, 31715818*, 7521113 |

首个失败步骤中的关键错误召回文档（前3条非gold）：

- rank=1 `3441524` Human TRPML1 channel structures in open and closed conformations（本地heading=`SCIFACT-EVIDENCE-3441524`）

  > Transient receptor potential mucolipin 1 (TRPML1) is a Ca2+-releasing cation channel that mediates the calcium signalling and homeostasis of lysosomes. Mutations in TRPML1 lead to mucolipidosis type IV, a severe lysosomal storage disorder. Here we report two electron cryo-microscopy structures of full-length human TRPML1: a 3.72-Å apo structure at pH 7.0 in the closed state, and a 3.49-Å agonist-bound structure at pH 6.0 in an open state. Several aromatic and hydrophobic residues in pore helix 1, helices S5 and S6, and helix S6 of a neighbouring subunit, form a hydrophobic cavity to house the…

  本地源文件： `DOCX=prepared/docx/194-scifact-3441524.docx` `PDF=prepared/pdf/194-scifact-3441524.pdf`
- rank=2 `10582939` Induction therapy with autologous mesenchymal stem cells in living-related kidney transplants: a randomized controlled trial.（本地heading=`SCIFACT-EVIDENCE-10582939`）

  > CONTEXT Antibody-based induction therapy plus calcineurin inhibitors (CNIs) reduce acute rejection rates in kidney recipients; however, opportunistic infections and toxic CNI effects remain challenging. Reportedly, mesenchymal stem cells (MSCs) have successfully treated graft-vs-host disease. OBJECTIVE To assess autologous MSCs as replacement of antibody induction for patients with end-stage renal disease who undergo ABO-compatible, cross-match-negative kidney transplants from a living-related donor. DESIGN, SETTING, AND PATIENTS One hundred fifty-nine patients were enrolled in this single-sit…

  本地源文件： `DOCX=prepared/docx/048-scifact-10582939.docx` `PDF=prepared/pdf/048-scifact-10582939.pdf`
- rank=3 `15663829` Mendelian Randomization Study of B-Type Natriuretic Peptide and Type 2 Diabetes: Evidence of Causal Association from Population Studies（本地heading=`SCIFACT-EVIDENCE-15663829`）

  > BACKGROUND Genetic and epidemiological evidence suggests an inverse association between B-type natriuretic peptide (BNP) levels in blood and risk of type 2 diabetes (T2D), but the prospective association of BNP with T2D is uncertain, and it is unclear whether the association is confounded. METHODS AND FINDINGS We analysed the association between levels of the N-terminal fragment of pro-BNP (NT-pro-BNP) in blood and risk of incident T2D in a prospective case-cohort study and genotyped the variant rs198389 within the BNP locus in three T2D case-control studies. We combined our results with exist…

  本地源文件： `DOCX=prepared/docx/062-scifact-15663829.docx` `PDF=prepared/pdf/062-scifact-15663829.pdf`

首个可观测失败步骤：`sparse_final_top10`。

直接证据：

- Dense gold首名次=Top10未命中
- Sparse gold首名次=Top10未命中
- Hybrid-RRF gold首名次=1
- Hybrid-RRF+Rerank gold首名次=9
- 分类规则=sparse_miss_hybrid_hit
- 逐候选分数=未采集

推断：Top10截断、语义近邻竞争或Rerank排序偏好可能共同影响；query与gold最大词项Jaccard=0.0000。

其他可能解释：相关性标注不完整、gold摘要与claim粒度不同、分块边界或Top10截断；当前终态run不能排除这些因素（sparse_miss_hybrid_hit）。

反证实验：固定同一索引和query，额外留存Dense/Sparse候选Top100、融合逐项贡献、Rerank输入输出分数及chunk文本，再复跑该query验证首个内部失效点（sparse_miss_hybrid_hit）。

### queryId=1204

问题：The combination of H3K4me3 and H3K79me2 is found in quiescent hair follicle stem cells.

Gold文档：

- `31141365` Genome-wide maps of histone modifications unwind in vivo chromatin states of the hair follicle lineage.

  > Using mouse skin, where bountiful reservoirs of synchronized hair follicle stem cells (HF-SCs) fuel cycles of regeneration, we explore how adult SCs remodel chromatin in response to activating cues. By profiling global mRNA and chromatin changes in quiescent and activated HF-SCs and their committed, transit-amplifying (TA) progeny, we show that polycomb-group (PcG)-mediated H3K27-trimethylation features prominently in HF-lineage progression by mechanisms distinct from embryonic-SCs. In HF-SCs, PcG represses nonskin lineages and HF differentiation. In TA progeny, nonskin regulators remain PcG-r…

  本地源文件： `DOCX=prepared/docx/085-scifact-31141365.docx` `PDF=prepared/pdf/085-scifact-31141365.pdf`

| 变体 | Recall@10 | MRR@10 | nDCG@10 | Gold首名次 | 延迟ms | Top10文档ID（*为gold） |
|---|---:|---:|---:|---:|---:|---|
| dense | 1.000000 | 1.000000 | 1.000000 | 1 | 4216 | 31141365*, 4942718, 13980338, 12580014, 4381486, 18909530, 11603066, 14637235, 4350400, 26851674 |
| sparse | 0.000000 | 0.000000 | 0.000000 | Top10未命中 | 2725 | 45638119, 24142891, 15928989, 18399038, 31715818, 3863543, 11172205, 23460562, 16999023, 56893404 |
| hybrid_rrf | 1.000000 | 1.000000 | 1.000000 | 1 | 2890 | 31141365*, 18399038, 18909530, 12580014, 3863543, 16999023, 45638119, 1897324, 5373138, 123859 |
| hybrid_rrf_rerank | 1.000000 | 1.000000 | 1.000000 | 1 | 9170 | 31141365*, 18909530, 16999023, 45638119, 18399038, 5373138, 3863543, 1897324, 123859, 12580014 |

首个失败步骤中的关键错误召回文档（前3条非gold）：

- rank=1 `45638119` ALDH1 is a marker of normal and malignant human mammary stem cells and a predictor of poor clinical outcome.（本地heading=`SCIFACT-EVIDENCE-45638119`）

  > Application of stem cell biology to breast cancer research has been limited by the lack of simple methods for identification and isolation of normal and malignant stem cells. Utilizing in vitro and in vivo experimental systems, we show that normal and cancer human mammary epithelial cells with increased aldehyde dehydrogenase activity (ALDH) have stem/progenitor properties. These cells contain the subpopulation of normal breast epithelium with the broadest lineage differentiation potential and greatest growth capacity in a xenotransplant model. In breast carcinomas, high ALDH activity identifi…

  本地源文件： `DOCX=prepared/docx/050-scifact-45638119.docx` `PDF=prepared/pdf/050-scifact-45638119.pdf`
- rank=2 `24142891` Tmem27: a cleaved and shed plasma membrane protein that stimulates pancreatic beta cell proliferation.（本地heading=`SCIFACT-EVIDENCE-24142891`）

  > The signals and molecular mechanisms that regulate the replication of terminally differentiated beta cells are unknown. Here, we report the identification and characterization of transmembrane protein 27 (Tmem27, collectrin) in pancreatic beta cells. Expression of Tmem27 is reduced in Tcf1(-/-) mice and is increased in islets of mouse models with hypertrophy of the endocrine pancreas. Tmem27 forms dimers and its extracellular domain is glycosylated, cleaved and shed from the plasma membrane of beta cells. This cleavage process is beta cell specific and does not occur in other cell types. Overe…

  本地源文件： `DOCX=prepared/docx/006-scifact-24142891.docx` `PDF=prepared/pdf/006-scifact-24142891.pdf`
- rank=3 `15928989` Liver receptor homolog-1 is essential for pregnancy（本地heading=`SCIFACT-EVIDENCE-15928989`）

  > Successful pregnancy requires coordination of an array of signals and factors from multiple tissues. One such element, liver receptor homolog-1 (Lrh-1), is an orphan nuclear receptor that regulates metabolism and hormone synthesis. It is strongly expressed in granulosa cells of ovarian follicles and in the corpus luteum of rodents and humans. Germline ablation of Nr5a2 (also called Lrh-1), the gene coding for Lrh-1, in mice is embryonically lethal at gastrulation. Depletion of Lrh-1 in the ovarian follicle shows that it regulates genes required for both steroid synthesis and ovulation. To stud…

  本地源文件： `DOCX=prepared/docx/045-scifact-15928989.docx` `PDF=prepared/pdf/045-scifact-15928989.pdf`

首个可观测失败步骤：`sparse_final_top10`。

直接证据：

- Dense gold首名次=1
- Sparse gold首名次=Top10未命中
- Hybrid-RRF gold首名次=1
- Hybrid-RRF+Rerank gold首名次=1
- 分类规则=sparse_miss_hybrid_hit
- 逐候选分数=未采集

推断：稀疏词项匹配对同义改写或词形差异不敏感；query与gold标题/片段最大词项Jaccard=0.1154。

其他可能解释：相关性标注不完整、gold摘要与claim粒度不同、分块边界或Top10截断；当前终态run不能排除这些因素（sparse_miss_hybrid_hit）。

反证实验：固定同一索引和query，额外留存Dense/Sparse候选Top100、融合逐项贡献、Rerank输入输出分数及chunk文本，再复跑该query验证首个内部失效点（sparse_miss_hybrid_hit）。

### queryId=1395

问题：p16INK4A accumulation is  linked to an abnormal wound response caused by the microinvasive step of advanced Oral Potentially Malignant Lesions (OPMLs).

Gold文档：

- `17717391` Monitoring Tumorigenesis and Senescence In Vivo with a p16INK4a-Luciferase Model

  > Monitoring cancer and aging in vivo remains experimentally challenging. Here, we describe a luciferase knockin mouse (p16(LUC)), which faithfully reports expression of p16(INK4a), a tumor suppressor and aging biomarker. Lifelong assessment of luminescence in p16(+/LUC) mice revealed an exponential increase with aging, which was highly variable in a cohort of contemporaneously housed, syngeneic mice. Expression of p16(INK4a) with aging did not predict cancer development, suggesting that the accumulation of senescent cells is not a principal determinant of cancer-related death. In 14 of 14 teste…

  本地源文件： `DOCX=prepared/docx/188-scifact-17717391.docx` `PDF=prepared/pdf/188-scifact-17717391.pdf`

| 变体 | Recall@10 | MRR@10 | nDCG@10 | Gold首名次 | 延迟ms | Top10文档ID（*为gold） |
|---|---:|---:|---:|---:|---:|---|
| dense | 1.000000 | 1.000000 | 1.000000 | 1 | 2005 | 17717391*, 3441524, 20231138, 23649163, 1834762, 7975937, 5304891, 12580014, 5531479, 19005293 |
| sparse | 0.000000 | 0.000000 | 0.000000 | Top10未命中 | 1628 | 11603066, 17741440, 20381484, 24294572, 3475317, 5483793, 18174210, 49556906, 33499189, 306006 |
| hybrid_rrf | 1.000000 | 1.000000 | 1.000000 | 1 | 2149 | 17717391*, 5304891, 49556906, 24294572, 3475317, 11603066, 19005293, 306006, 17741440, 11419230 |
| hybrid_rrf_rerank | 1.000000 | 1.000000 | 1.000000 | 1 | 11082 | 17717391*, 49556906, 24294572, 3475317, 17741440, 19005293, 306006, 11603066, 5304891, 11419230 |

首个失败步骤中的关键错误召回文档（前3条非gold）：

- rank=1 `11603066` Using Structural Information to Change the Phosphotransfer Specificity of a Two-Component Chemotaxis Signalling Complex（本地heading=`SCIFACT-EVIDENCE-11603066`）

  > Two-component signal transduction pathways comprising histidine protein kinases (HPKs) and their response regulators (RRs) are widely used to control bacterial responses to environmental challenges. Some bacteria have over 150 different two-component pathways, and the specificity of the phosphotransfer reactions within these systems is tightly controlled to prevent unwanted crosstalk. One of the best understood two-component signalling pathways is the chemotaxis pathway. Here, we present the 1.40 A crystal structure of the histidine-containing phosphotransfer domain of the chemotaxis HPK, CheA…

  本地源文件： `DOCX=prepared/docx/170-scifact-11603066.docx` `PDF=prepared/pdf/170-scifact-11603066.pdf`
- rank=2 `17741440` Netting neutrophils in autoimmune small-vessel vasculitis（本地heading=`SCIFACT-EVIDENCE-17741440`）

  > Small-vessel vasculitis (SVV) is a chronic autoinflammatory condition linked to antineutrophil cytoplasm autoantibodies (ANCAs). Here we show that chromatin fibers, so-called neutrophil extracellular traps (NETs), are released by ANCA-stimulated neutrophils and contain the targeted autoantigens proteinase-3 (PR3) and myeloperoxidase (MPO). Deposition of NETs in inflamed kidneys and circulating MPO-DNA complexes suggest that NET formation triggers vasculitis and promotes the autoimmune response against neutrophil components in individuals with SVV.

  本地源文件： `DOCX=prepared/docx/124-scifact-17741440.docx` `PDF=prepared/pdf/124-scifact-17741440.pdf`
- rank=3 `20381484` GAPDH Mediates Nitrosylation of Nuclear Proteins（本地heading=`SCIFACT-EVIDENCE-20381484`）

  > S-nitrosylation of proteins by nitric oxide is a major mode of signalling in cells. S-nitrosylation can mediate the regulation of a range of proteins, including prominent nuclear proteins, such as HDAC2 (ref. 2) and PARP1 (ref. 3). The high reactivity of the nitric oxide group with protein thiols, but the selective nature of nitrosylation within the cell, implies the existence of targeting mechanisms. Specificity of nitric oxide signalling is often achieved by the binding of nitric oxide synthase (NOS) to target proteins, either directly or through scaffolding proteins such as PSD-95 (ref. 5)…

  本地源文件： `DOCX=prepared/docx/074-scifact-20381484.docx` `PDF=prepared/pdf/074-scifact-20381484.pdf`

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

### queryId=5

问题：1/2000 in UK have abnormal PrP positivity.

Gold文档：

- `13734012` Prevalent abnormal prion protein in human appendixes after bovine spongiform encephalopathy epizootic: large scale survey

  > OBJECTIVES To carry out a further survey of archived appendix samples to understand better the differences between existing estimates of the prevalence of subclinical infection with prions after the bovine spongiform encephalopathy epizootic and to see whether a broader birth cohort was affected, and to understand better the implications for the management of blood and blood products and for the handling of surgical instruments. DESIGN Irreversibly unlinked and anonymised large scale survey of archived appendix samples. SETTING Archived appendix samples from the pathology departments of 41 UK…

  本地源文件： `DOCX=prepared/docx/027-scifact-13734012.docx` `PDF=prepared/pdf/027-scifact-13734012.pdf`

| 变体 | Recall@10 | MRR@10 | nDCG@10 | Gold首名次 | 延迟ms | Top10文档ID（*为gold） |
|---|---:|---:|---:|---:|---:|---|
| dense | 1.000000 | 1.000000 | 1.000000 | 1 | 2189 | 13734012*, 13625993, 5476778, 39368721, 1606628, 12471115, 26851674, 13282296 |
| sparse | 0.000000 | 0.000000 | 0.000000 | Top10未命中 | 2513 | 17077004, 24512064, 29564505, 791050, 19799455, 30655442, 11718220, 15319019, 19675911 |
| hybrid_rrf | 1.000000 | 1.000000 | 1.000000 | 1 | 2592 | 13734012*, 5476778, 17077004, 24512064, 970012, 791050, 29564505, 26851674 |
| hybrid_rrf_rerank | 1.000000 | 1.000000 | 1.000000 | 1 | 10850 | 13734012*, 29564505, 791050, 17077004, 26851674, 24512064, 5476778, 970012 |

首个失败步骤中的关键错误召回文档（前3条非gold）：

- rank=1 `17077004` Stable partnership and progression to AIDS or death in HIV infected patients receiving highly active antiretroviral therapy: Swiss HIV cohort study.（本地heading=`SCIFACT-EVIDENCE-17077004`）

  > OBJECTIVES To explore the association between a stable partnership and clinical outcome in HIV infected patients receiving highly active antiretroviral therapy (HAART). DESIGN Prospective cohort study of adults with HIV (Swiss HIV cohort study). SETTING Seven outpatient clinics throughout Switzerland. PARTICIPANTS The 3736 patients in the cohort who started HAART before 2002 (median age 36 years, 29% female, median follow up 3.6 years). MAIN OUTCOME MEASURES Time to AIDS or death (primary endpoint), death alone, increases in CD4 cell count of at least 50 and 100 above baseline, optimal viral s…

  本地源文件： `DOCX=prepared/docx/100-scifact-17077004.docx` `PDF=prepared/pdf/100-scifact-17077004.pdf`
- rank=2 `24512064` HTLV-I/II associated disease in England and Wales, 1993-7: retrospective review of serology requests.（本地heading=`SCIFACT-EVIDENCE-24512064`）

  > Apart from HIV two exogenous retroviruses (human T cell leukaemia viruses type I (HTLV-I) and type II (HTLV-II)) infect humans. HTLV-I infection is endemic in Japan, the Caribbean, Africa, and Melanesia and is found among immigrants from these regions in Europe. HTLV-I infection is associated with a 1-5% lifetime risk of adult T cell leukaemia/lymphoma, 1 a 0.25% lifetime risk of HTLV-I associated myelopathy, 2 and other inflammatory conditions (uveitis, alveolitis, and arthritis).1 HTLV-II infection is endemic in some native American and African peoples and among injecting drug users and has…

  本地源文件： `DOCX=prepared/docx/017-scifact-24512064.docx` `PDF=prepared/pdf/017-scifact-24512064.pdf`
- rank=3 `29564505` Inflammatory biomarkers and exacerbations in chronic obstructive pulmonary disease.（本地heading=`SCIFACT-EVIDENCE-29564505`）

  > IMPORTANCE Exacerbations of respiratory symptoms in chronic obstructive pulmonary disease (COPD) have profound and long-lasting adverse effects on patients. OBJECTIVE To test the hypothesis that elevated levels of inflammatory biomarkers in individuals with stable COPD are associated with an increased risk of having exacerbations. DESIGN, SETTING, AND PARTICIPANTS Prospective cohort study examining 61,650 participants with spirometry measurements from the Copenhagen City Heart Study (2001-2003) and the Copenhagen General Population Study (2003-2008). Of these, 6574 had COPD, defined as a ratio…

  本地源文件： `DOCX=prepared/docx/059-scifact-29564505.docx` `PDF=prepared/pdf/059-scifact-29564505.pdf`

首个可观测失败步骤：`sparse_final_top10`。

直接证据：

- Dense gold首名次=1
- Sparse gold首名次=Top10未命中
- Hybrid-RRF gold首名次=1
- Hybrid-RRF+Rerank gold首名次=1
- 分类规则=sparse_miss_hybrid_hit
- 逐候选分数=未采集

推断：稀疏词项匹配对同义改写或词形差异不敏感；query与gold标题/片段最大词项Jaccard=0.0455。

其他可能解释：相关性标注不完整、gold摘要与claim粒度不同、分块边界或Top10截断；当前终态run不能排除这些因素（sparse_miss_hybrid_hit）。

反证实验：固定同一索引和query，额外留存Dense/Sparse候选Top100、融合逐项贡献、Rerank输入输出分数及chunk文本，再复跑该query验证首个内部失效点（sparse_miss_hybrid_hit）。

### queryId=115

问题：Anthrax spores can be disposed of easily after they are dispersed.

Gold文档：

- `33872649` Secondary aerosolization of viable Bacillus anthracis spores in a contaminated US Senate Office.

  > CONTEXT Bioterrorist attacks involving letters and mail-handling systems in Washington, DC, resulted in Bacillus anthracis (anthrax) spore contamination in the Hart Senate Office Building and other facilities in the US Capitol's vicinity. OBJECTIVE To provide information about the nature and extent of indoor secondary aerosolization of B anthracis spores. DESIGN Stationary and personal air samples, surface dust, and swab samples were collected under semiquiescent (minimal activities) and then simulated active office conditions to estimate secondary aerosolization of B anthracis spores. Nominal…

  本地源文件： `DOCX=prepared/docx/120-scifact-33872649.docx` `PDF=prepared/pdf/120-scifact-33872649.pdf`

| 变体 | Recall@10 | MRR@10 | nDCG@10 | Gold首名次 | 延迟ms | Top10文档ID（*为gold） |
|---|---:|---:|---:|---:|---:|---|
| dense | 1.000000 | 1.000000 | 1.000000 | 1 | 2097 | 33872649*, 18855191, 6173523, 4942718, 16787954, 1469751, 1215116 |
| sparse | 0.000000 | 0.000000 | 0.000000 | Top10未命中 | 1686 | 6157837, 8551160, 4381486, 14717500, 44172171, 27768226, 13625993, 10536636 |
| hybrid_rrf | 1.000000 | 0.500000 | 0.630930 | 2 | 1839 | 4381486, 33872649*, 1215116, 6173523, 11419230, 4942718, 13625993, 14376683 |
| hybrid_rrf_rerank | 1.000000 | 1.000000 | 1.000000 | 1 | 10099 | 33872649*, 4942718, 6173523, 14376683, 4381486, 13625993, 1215116, 11419230 |

首个失败步骤中的关键错误召回文档（前3条非gold）：

- rank=1 `6157837` Renal considerations in angiotensin converting enzyme inhibitor therapy: a statement for healthcare professionals from the Council on the Kidney in Cardiovascular Disease and the Council for High Blood Pressure Research of the American Heart Association.（本地heading=`SCIFACT-EVIDENCE-6157837`）

  > Angiotensin converting enzyme (ACE) inhibitors are now one of the most frequently used classes of antihypertensive drugs. Beyond their utility in the management of hypertension, their use has been extended to the long-term management of patients with congestive heart failure (CHF), as well as diabetic and nondiabetic nephropathies. Although ACE inhibitor therapy usually improves renal blood flow (RBF) and sodium excretion rates in CHF and reduces the rate of progressive renal injury in chronic renal disease, its use can also be associated with a syndrome of “functional renal insufficiency” and…

  本地源文件： `DOCX=prepared/docx/023-scifact-6157837.docx` `PDF=prepared/pdf/023-scifact-6157837.pdf`
- rank=2 `8551160` Mitochondria: Dynamic Organelles in Disease, Aging, and Development（本地heading=`SCIFACT-EVIDENCE-8551160`）

  > Mitochondria are the primary energy-generating system in most eukaryotic cells. Additionally, they participate in intermediary metabolism, calcium signaling, and apoptosis. Given these well-established functions, it might be expected that mitochondrial dysfunction would give rise to a simple and predictable set of defects in all tissues. However, mitochondrial dysfunction has pleiotropic effects in multicellular organisms. Clearly, much about the basic biology of mitochondria remains to be understood. Here we discuss recent work that suggests that the dynamics (fusion and fission) of these org…

  本地源文件： `DOCX=prepared/docx/086-scifact-8551160.docx` `PDF=prepared/pdf/086-scifact-8551160.pdf`
- rank=3 `4381486` Haematopoietic stem cells do not asymmetrically segregate chromosomes or retain BrdU（本地heading=`SCIFACT-EVIDENCE-4381486`）

  > Stem cells are proposed to segregate chromosomes asymmetrically during self-renewing divisions so that older (‘immortal’) DNA strands are retained in daughter stem cells whereas newly synthesized strands segregate to differentiating cells. Stem cells are also proposed to retain DNA labels, such as 5-bromo-2-deoxyuridine (BrdU), either because they segregate chromosomes asymmetrically or because they divide slowly. However, the purity of stem cells among BrdU-label-retaining cells has not been documented in any tissue, and the ‘immortal strand hypothesis’ has not been tested in a system with de…

  本地源文件： `DOCX=prepared/docx/019-scifact-4381486.docx` `PDF=prepared/pdf/019-scifact-4381486.pdf`

首个可观测失败步骤：`sparse_final_top10`。

直接证据：

- Dense gold首名次=1
- Sparse gold首名次=Top10未命中
- Hybrid-RRF gold首名次=2
- Hybrid-RRF+Rerank gold首名次=1
- 分类规则=sparse_miss_hybrid_hit
- 逐候选分数=未采集

推断：稀疏词项匹配对同义改写或词形差异不敏感；query与gold标题/片段最大词项Jaccard=0.0429。

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
| dense | 1.000000 | 1.000000 | 1.000000 | 1 | 2775 | 12486491*, 1049501, 3475317, 5476778, 23460562, 13905670, 1215116, 18174210, 4350400, 17741440 |
| sparse | 0.000000 | 0.000000 | 0.000000 | Top10未命中 | 2476 | 24142891, 8290953, 45638119, 23460562, 5483793, 7521113, 123859, 4350400, 17755060 |
| hybrid_rrf | 1.000000 | 0.250000 | 0.430677 | 4 | 2657 | 1215116, 3475317, 23460562, 12486491*, 7521113, 14376683, 1049501, 3553087, 45638119 |
| hybrid_rrf_rerank | 1.000000 | 1.000000 | 1.000000 | 1 | 13505 | 12486491*, 23460562, 1049501, 14376683, 1215116, 3553087, 3475317, 45638119, 7521113 |

首个失败步骤中的关键错误召回文档（前3条非gold）：

- rank=1 `24142891` Tmem27: a cleaved and shed plasma membrane protein that stimulates pancreatic beta cell proliferation.（本地heading=`SCIFACT-EVIDENCE-24142891`）

  > The signals and molecular mechanisms that regulate the replication of terminally differentiated beta cells are unknown. Here, we report the identification and characterization of transmembrane protein 27 (Tmem27, collectrin) in pancreatic beta cells. Expression of Tmem27 is reduced in Tcf1(-/-) mice and is increased in islets of mouse models with hypertrophy of the endocrine pancreas. Tmem27 forms dimers and its extracellular domain is glycosylated, cleaved and shed from the plasma membrane of beta cells. This cleavage process is beta cell specific and does not occur in other cell types. Overe…

  本地源文件： `DOCX=prepared/docx/006-scifact-24142891.docx` `PDF=prepared/pdf/006-scifact-24142891.pdf`
- rank=2 `8290953` Scaffold-based three-dimensional human fibroblast culture provides a structural matrix that supports angiogenesis in infarcted heart tissue.（本地heading=`SCIFACT-EVIDENCE-8290953`）

  > BACKGROUND We have developed techniques to implant angiogenic patches onto the epicardium over regions of infarcted cardiac tissue to stimulate revascularization of the damaged tissue. These experiments used a scaffold-based 3D human dermal fibroblast culture (3DFC) as an epicardial patch. The 3DFC contains viable cells that secrete angiogenic growth factors and has previously been shown to stimulate angiogenic activity. The hypothesis tested was that a viable 3DFC cardiac patch would stimulate an angiogenic response within an area of infarcted cardiac tissue. METHODS AND RESULTS A coronary oc…

  本地源文件： `DOCX=prepared/docx/030-scifact-8290953.docx` `PDF=prepared/pdf/030-scifact-8290953.pdf`
- rank=3 `45638119` ALDH1 is a marker of normal and malignant human mammary stem cells and a predictor of poor clinical outcome.（本地heading=`SCIFACT-EVIDENCE-45638119`）

  > Application of stem cell biology to breast cancer research has been limited by the lack of simple methods for identification and isolation of normal and malignant stem cells. Utilizing in vitro and in vivo experimental systems, we show that normal and cancer human mammary epithelial cells with increased aldehyde dehydrogenase activity (ALDH) have stem/progenitor properties. These cells contain the subpopulation of normal breast epithelium with the broadest lineage differentiation potential and greatest growth capacity in a xenotransplant model. In breast carcinomas, high ALDH activity identifi…

  本地源文件： `DOCX=prepared/docx/050-scifact-45638119.docx` `PDF=prepared/pdf/050-scifact-45638119.pdf`

首个可观测失败步骤：`sparse_final_top10`。

直接证据：

- Dense gold首名次=1
- Sparse gold首名次=Top10未命中
- Hybrid-RRF gold首名次=4
- Hybrid-RRF+Rerank gold首名次=1
- 分类规则=dense_only_success
- 逐候选分数=未采集

推断：稀疏词项匹配对同义改写或词形差异不敏感；query与gold标题/片段最大词项Jaccard=0.0533。

其他可能解释：相关性标注不完整、gold摘要与claim粒度不同、分块边界或Top10截断；当前终态run不能排除这些因素（dense_only_success）。

反证实验：固定同一索引和query，额外留存Dense/Sparse候选Top100、融合逐项贡献、Rerank输入输出分数及chunk文本，再复跑该query验证首个内部失效点（dense_only_success）。

### queryId=1088

问题：Silencing of Bcl2 is important for the maintenance and progression of tumors.

Gold文档：

- `37549932` Antiapoptotic BCL-2 is required for maintenance of a model leukemia.

  > Resistance to apoptosis, often achieved by the overexpression of antiapoptotic proteins, is common and perhaps required in the genesis of cancer. However, it remains uncertain whether apoptotic defects are essential for tumor maintenance. To test this, we generated mice expressing a conditional BCL-2 gene and constitutive c-myc that develop lymphoblastic leukemia. Eliminating BCL-2 yielded rapid loss of leukemic cells and significantly prolonged survival, formally validating BCL-2 as a rational target for cancer therapy. Loss of this single molecule resulted in cell death, despite or perhaps a…

  本地源文件： `DOCX=prepared/docx/043-scifact-37549932.docx` `PDF=prepared/pdf/043-scifact-37549932.pdf`

| 变体 | Recall@10 | MRR@10 | nDCG@10 | Gold首名次 | 延迟ms | Top10文档ID（*为gold） |
|---|---:|---:|---:|---:|---:|---|
| dense | 1.000000 | 1.000000 | 1.000000 | 1 | 3244 | 37549932*, 23895668, 9559146, 6923961, 17755060, 33370, 7975937, 11369420, 17717391, 16472469 |
| sparse | 0.000000 | 0.000000 | 0.000000 | Top10未命中 | 3284 | 1897324, 15928989, 45638119, 13734012, 11369420, 17628888, 13519661, 11335781, 12580014, 11172205 |
| hybrid_rrf | 1.000000 | 0.142857 | 0.333333 | 7 | 3320 | 11369420, 1897324, 45638119, 17755060, 17628888, 39381118, 37549932*, 15928989, 6923961, 2988714 |
| hybrid_rrf_rerank | 1.000000 | 1.000000 | 1.000000 | 1 | 29005 | 37549932*, 39381118, 6923961, 17755060, 45638119, 1897324, 11369420, 15928989, 17628888, 2988714 |

首个失败步骤中的关键错误召回文档（前3条非gold）：

- rank=1 `1897324` A genetic screen identifies an LKB1–MARK signalling axis controlling the Hippo–YAP pathway（本地heading=`SCIFACT-EVIDENCE-1897324`）

  > The Hippo–YAP pathway is an emerging signalling cascade involved in the regulation of stem cell activity and organ size. To identify components of this pathway, we performed an RNAi-based kinome screen in human cells. Our screen identified several kinases not previously associated with Hippo signalling that control multiple cellular processes. One of the hits, LKB1, is a common tumour suppressor whose mechanism of action is only partially understood. We demonstrate that LKB1 acts through its substrates of the microtubule affinity-regulating kinase family to regulate the localization of the pol…

  本地源文件： `DOCX=prepared/docx/026-scifact-1897324.docx` `PDF=prepared/pdf/026-scifact-1897324.pdf`
- rank=2 `15928989` Liver receptor homolog-1 is essential for pregnancy（本地heading=`SCIFACT-EVIDENCE-15928989`）

  > Successful pregnancy requires coordination of an array of signals and factors from multiple tissues. One such element, liver receptor homolog-1 (Lrh-1), is an orphan nuclear receptor that regulates metabolism and hormone synthesis. It is strongly expressed in granulosa cells of ovarian follicles and in the corpus luteum of rodents and humans. Germline ablation of Nr5a2 (also called Lrh-1), the gene coding for Lrh-1, in mice is embryonically lethal at gastrulation. Depletion of Lrh-1 in the ovarian follicle shows that it regulates genes required for both steroid synthesis and ovulation. To stud…

  本地源文件： `DOCX=prepared/docx/045-scifact-15928989.docx` `PDF=prepared/pdf/045-scifact-15928989.pdf`
- rank=3 `45638119` ALDH1 is a marker of normal and malignant human mammary stem cells and a predictor of poor clinical outcome.（本地heading=`SCIFACT-EVIDENCE-45638119`）

  > Application of stem cell biology to breast cancer research has been limited by the lack of simple methods for identification and isolation of normal and malignant stem cells. Utilizing in vitro and in vivo experimental systems, we show that normal and cancer human mammary epithelial cells with increased aldehyde dehydrogenase activity (ALDH) have stem/progenitor properties. These cells contain the subpopulation of normal breast epithelium with the broadest lineage differentiation potential and greatest growth capacity in a xenotransplant model. In breast carcinomas, high ALDH activity identifi…

  本地源文件： `DOCX=prepared/docx/050-scifact-45638119.docx` `PDF=prepared/pdf/050-scifact-45638119.pdf`

首个可观测失败步骤：`sparse_final_top10`。

直接证据：

- Dense gold首名次=1
- Sparse gold首名次=Top10未命中
- Hybrid-RRF gold首名次=7
- Hybrid-RRF+Rerank gold首名次=1
- 分类规则=dense_only_success
- 逐候选分数=未采集

推断：稀疏词项匹配对同义改写或词形差异不敏感；query与gold标题/片段最大词项Jaccard=0.0811。

其他可能解释：相关性标注不完整、gold摘要与claim粒度不同、分块边界或Top10截断；当前终态run不能排除这些因素（dense_only_success）。

反证实验：固定同一索引和query，额外留存Dense/Sparse候选Top100、融合逐项贡献、Rerank输入输出分数及chunk文本，再复跑该query验证首个内部失效点（dense_only_success）。

### queryId=115

问题：Anthrax spores can be disposed of easily after they are dispersed.

Gold文档：

- `33872649` Secondary aerosolization of viable Bacillus anthracis spores in a contaminated US Senate Office.

  > CONTEXT Bioterrorist attacks involving letters and mail-handling systems in Washington, DC, resulted in Bacillus anthracis (anthrax) spore contamination in the Hart Senate Office Building and other facilities in the US Capitol's vicinity. OBJECTIVE To provide information about the nature and extent of indoor secondary aerosolization of B anthracis spores. DESIGN Stationary and personal air samples, surface dust, and swab samples were collected under semiquiescent (minimal activities) and then simulated active office conditions to estimate secondary aerosolization of B anthracis spores. Nominal…

  本地源文件： `DOCX=prepared/docx/120-scifact-33872649.docx` `PDF=prepared/pdf/120-scifact-33872649.pdf`

| 变体 | Recall@10 | MRR@10 | nDCG@10 | Gold首名次 | 延迟ms | Top10文档ID（*为gold） |
|---|---:|---:|---:|---:|---:|---|
| dense | 1.000000 | 1.000000 | 1.000000 | 1 | 2097 | 33872649*, 18855191, 6173523, 4942718, 16787954, 1469751, 1215116 |
| sparse | 0.000000 | 0.000000 | 0.000000 | Top10未命中 | 1686 | 6157837, 8551160, 4381486, 14717500, 44172171, 27768226, 13625993, 10536636 |
| hybrid_rrf | 1.000000 | 0.500000 | 0.630930 | 2 | 1839 | 4381486, 33872649*, 1215116, 6173523, 11419230, 4942718, 13625993, 14376683 |
| hybrid_rrf_rerank | 1.000000 | 1.000000 | 1.000000 | 1 | 10099 | 33872649*, 4942718, 6173523, 14376683, 4381486, 13625993, 1215116, 11419230 |

首个失败步骤中的关键错误召回文档（前3条非gold）：

- rank=1 `6157837` Renal considerations in angiotensin converting enzyme inhibitor therapy: a statement for healthcare professionals from the Council on the Kidney in Cardiovascular Disease and the Council for High Blood Pressure Research of the American Heart Association.（本地heading=`SCIFACT-EVIDENCE-6157837`）

  > Angiotensin converting enzyme (ACE) inhibitors are now one of the most frequently used classes of antihypertensive drugs. Beyond their utility in the management of hypertension, their use has been extended to the long-term management of patients with congestive heart failure (CHF), as well as diabetic and nondiabetic nephropathies. Although ACE inhibitor therapy usually improves renal blood flow (RBF) and sodium excretion rates in CHF and reduces the rate of progressive renal injury in chronic renal disease, its use can also be associated with a syndrome of “functional renal insufficiency” and…

  本地源文件： `DOCX=prepared/docx/023-scifact-6157837.docx` `PDF=prepared/pdf/023-scifact-6157837.pdf`
- rank=2 `8551160` Mitochondria: Dynamic Organelles in Disease, Aging, and Development（本地heading=`SCIFACT-EVIDENCE-8551160`）

  > Mitochondria are the primary energy-generating system in most eukaryotic cells. Additionally, they participate in intermediary metabolism, calcium signaling, and apoptosis. Given these well-established functions, it might be expected that mitochondrial dysfunction would give rise to a simple and predictable set of defects in all tissues. However, mitochondrial dysfunction has pleiotropic effects in multicellular organisms. Clearly, much about the basic biology of mitochondria remains to be understood. Here we discuss recent work that suggests that the dynamics (fusion and fission) of these org…

  本地源文件： `DOCX=prepared/docx/086-scifact-8551160.docx` `PDF=prepared/pdf/086-scifact-8551160.pdf`
- rank=3 `4381486` Haematopoietic stem cells do not asymmetrically segregate chromosomes or retain BrdU（本地heading=`SCIFACT-EVIDENCE-4381486`）

  > Stem cells are proposed to segregate chromosomes asymmetrically during self-renewing divisions so that older (‘immortal’) DNA strands are retained in daughter stem cells whereas newly synthesized strands segregate to differentiating cells. Stem cells are also proposed to retain DNA labels, such as 5-bromo-2-deoxyuridine (BrdU), either because they segregate chromosomes asymmetrically or because they divide slowly. However, the purity of stem cells among BrdU-label-retaining cells has not been documented in any tissue, and the ‘immortal strand hypothesis’ has not been tested in a system with de…

  本地源文件： `DOCX=prepared/docx/019-scifact-4381486.docx` `PDF=prepared/pdf/019-scifact-4381486.pdf`

首个可观测失败步骤：`sparse_final_top10`。

直接证据：

- Dense gold首名次=1
- Sparse gold首名次=Top10未命中
- Hybrid-RRF gold首名次=2
- Hybrid-RRF+Rerank gold首名次=1
- 分类规则=dense_only_success
- 逐候选分数=未采集

推断：稀疏词项匹配对同义改写或词形差异不敏感；query与gold标题/片段最大词项Jaccard=0.0429。

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
| dense | 1.000000 | 1.000000 | 1.000000 | 1 | 2225 | 11419230*, 33499189, 17628888, 15319019, 52873726, 17587795, 306006, 11603066 |
| sparse | 0.000000 | 0.000000 | 0.000000 | Top10未命中 | 1656 | 33499189, 11335781, 28937856, 10991183, 17628888, 5289038, 24221369, 20381484, 3203590, 8646760 |
| hybrid_rrf | 1.000000 | 0.166667 | 0.356207 | 6 | 2538 | 33499189, 17628888, 10991183, 3203590, 2831620, 11419230*, 12631697, 12486491, 1897324 |
| hybrid_rrf_rerank | 1.000000 | 1.000000 | 1.000000 | 1 | 11064 | 11419230*, 10991183, 33499189, 17628888, 12631697, 3203590, 2831620, 1897324, 12486491 |

首个失败步骤中的关键错误召回文档（前3条非gold）：

- rank=1 `33499189` Full activation of the T cell receptor requires both clustering and conformational changes at CD3.（本地heading=`SCIFACT-EVIDENCE-33499189`）

  > T cell receptor (TCR-CD3) triggering involves both receptor clustering and conformational changes at the cytoplasmic tails of the CD3 subunits. The mechanism by which TCRalphabeta ligand binding confers conformational changes to CD3 is unknown. By using well-defined ligands, we showed that induction of the conformational change requires both multivalent engagement and the mobility restriction of the TCR-CD3 imposed by the plasma membrane. The conformational change is elicited by cooperative rearrangements of two TCR-CD3 complexes and does not require accompanying changes in the structure of th…

  本地源文件： `DOCX=prepared/docx/156-scifact-33499189.docx` `PDF=prepared/pdf/156-scifact-33499189.pdf`
- rank=2 `11335781` Is autoimmunity the Achilles' heel of cancer immunotherapy?（本地heading=`SCIFACT-EVIDENCE-11335781`）

  > The emergence of immuno-oncology as the first broadly successful strategy for metastatic cancer will require clinicians to integrate this new pillar of medicine with chemotherapy, radiation, and targeted small-molecule compounds. Of equal importance is gaining an understanding of the limitations and toxicities of immunotherapy. Immunotherapy was initially perceived to be a relatively less toxic approach to cancer treatment than other available therapies—and surely it is, when compared to those. However, as the use of immunotherapy becomes more common, especially as first- and second-line treat…

  本地源文件： `DOCX=prepared/docx/018-scifact-11335781.docx` `PDF=prepared/pdf/018-scifact-11335781.pdf`
- rank=3 `28937856` Stress-dependent regulation of FOXO transcription factors by the SIRT1 deacetylase.（本地heading=`SCIFACT-EVIDENCE-28937856`）

  > The Sir2 deacetylase modulates organismal life-span in various species. However, the molecular mechanisms by which Sir2 increases longevity are largely unknown. We show that in mammalian cells, the Sir2 homolog SIRT1 appears to control the cellular response to stress by regulating the FOXO family of Forkhead transcription factors, a family of proteins that function as sensors of the insulin signaling pathway and as regulators of organismal longevity. SIRT1 and the FOXO transcription factor FOXO3 formed a complex in cells in response to oxidative stress, and SIRT1 deacetylated FOXO3 in vitro an…

  本地源文件： `DOCX=prepared/docx/092-scifact-28937856.docx` `PDF=prepared/pdf/092-scifact-28937856.pdf`

首个可观测失败步骤：`sparse_final_top10`。

直接证据：

- Dense gold首名次=1
- Sparse gold首名次=Top10未命中
- Hybrid-RRF gold首名次=6
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
| dense | 1.000000 | 1.000000 | 1.000000 | 1 | 2247 | 25649714*, 13906581, 27768226, 4687948, 33872649, 16495649, 1642727, 1606628 |
| sparse | 0.000000 | 0.000000 | 0.000000 | Top10未命中 | 1799 | 33499189, 8780599, 12789595, 791050, 17628888, 4387784, 25742130, 8646760, 13071728 |
| hybrid_rrf | 0.000000 | 0.000000 | 0.000000 | Top10未命中 | 2641 | 12789595, 8780599, 13071728, 1606628, 1642727, 13625993, 11718220, 27768226 |
| hybrid_rrf_rerank | 0.000000 | 0.000000 | 0.000000 | Top10未命中 | 11880 | 13071728, 12789595, 8780599, 13625993, 1642727, 11718220, 27768226, 1606628 |

首个失败步骤中的关键错误召回文档（前3条非gold）：

- rank=1 `33499189` Full activation of the T cell receptor requires both clustering and conformational changes at CD3.（本地heading=`SCIFACT-EVIDENCE-33499189`）

  > T cell receptor (TCR-CD3) triggering involves both receptor clustering and conformational changes at the cytoplasmic tails of the CD3 subunits. The mechanism by which TCRalphabeta ligand binding confers conformational changes to CD3 is unknown. By using well-defined ligands, we showed that induction of the conformational change requires both multivalent engagement and the mobility restriction of the TCR-CD3 imposed by the plasma membrane. The conformational change is elicited by cooperative rearrangements of two TCR-CD3 complexes and does not require accompanying changes in the structure of th…

  本地源文件： `DOCX=prepared/docx/156-scifact-33499189.docx` `PDF=prepared/pdf/156-scifact-33499189.pdf`
- rank=2 `8780599` The Polymeal: a more natural, safer, and probably tastier (than the Polypill) strategy to reduce cardiovascular disease by more than 75%.（本地heading=`SCIFACT-EVIDENCE-8780599`）

  > OBJECTIVE Although the Polypill concept (proposed in 2003) is promising in terms of benefits for cardiovascular risk management, the potential costs and adverse effects are its main pitfalls. The objective of this study was to identify a tastier and safer alternative to the Polypill: the Polymeal. METHODS Data on the ingredients of the Polymeal were taken from the literature. The evidence based recipe included wine, fish, dark chocolate, fruits, vegetables, garlic, and almonds. Data from the Framingham heart study and the Framingham offspring study were used to build life tables to model the b…

  本地源文件： `DOCX=prepared/docx/140-scifact-8780599.docx` `PDF=prepared/pdf/140-scifact-8780599.pdf`
- rank=3 `12789595` Computer assisted learning in undergraduate medical education.（本地heading=`SCIFACT-EVIDENCE-12789595`）

  > It is becoming “a truth universally acknowledged” that the education of undergraduate medical students will be enhanced through the use of computer assisted learning. Access to the wide range of online options illustrated in the figure must surely make learning more exciting, effective, and likely to be retained. This assumption is potentially but by no means inevitably correct. ### Box 1: Why fund computer assisted learning? Computer assisted learning is inevitable —Individual lecturers and departments are already beginning to introduce a wide range of computer based applications, sometimes i…

  本地源文件： `DOCX=prepared/docx/069-scifact-12789595.docx` `PDF=prepared/pdf/069-scifact-12789595.pdf`

首个可观测失败步骤：`sparse_final_top10`。

直接证据：

- Dense gold首名次=1
- Sparse gold首名次=Top10未命中
- Hybrid-RRF gold首名次=Top10未命中
- Hybrid-RRF+Rerank gold首名次=Top10未命中
- 分类规则=dense_only_success
- 逐候选分数=未采集

推断：稀疏词项匹配对同义改写或词形差异不敏感；query与gold标题/片段最大词项Jaccard=0.0615。

其他可能解释：相关性标注不完整、gold摘要与claim粒度不同、分块边界或Top10截断；当前终态run不能排除这些因素（dense_only_success）。

反证实验：固定同一索引和query，额外留存Dense/Sparse候选Top100、融合逐项贡献、Rerank输入输出分数及chunk文本，再复跑该query验证首个内部失效点（dense_only_success）。

## sparse_only_success

### queryId=1363

问题：Venules have a thinner or absent smooth layer compared to arterioles.

Gold文档：

- `8290953` Scaffold-based three-dimensional human fibroblast culture provides a structural matrix that supports angiogenesis in infarcted heart tissue.

  > BACKGROUND We have developed techniques to implant angiogenic patches onto the epicardium over regions of infarcted cardiac tissue to stimulate revascularization of the damaged tissue. These experiments used a scaffold-based 3D human dermal fibroblast culture (3DFC) as an epicardial patch. The 3DFC contains viable cells that secrete angiogenic growth factors and has previously been shown to stimulate angiogenic activity. The hypothesis tested was that a viable 3DFC cardiac patch would stimulate an angiogenic response within an area of infarcted cardiac tissue. METHODS AND RESULTS A coronary oc…

  本地源文件： `DOCX=prepared/docx/030-scifact-8290953.docx` `PDF=prepared/pdf/030-scifact-8290953.pdf`

| 变体 | Recall@10 | MRR@10 | nDCG@10 | Gold首名次 | 延迟ms | Top10文档ID（*为gold） |
|---|---:|---:|---:|---:|---:|---|
| dense | 0.000000 | 0.000000 | 0.000000 | Top10未命中 | 3186 | 4387784, 17741440, 2425364, 12991445, 4423559, 16760369, 18174210, 11718220, 13625993 |
| sparse | 1.000000 | 0.333333 | 0.500000 | 3 | 2332 | 13619127, 17077004, 8290953*, 32159283, 27768226, 23649163, 25742130 |
| hybrid_rrf | 1.000000 | 0.500000 | 0.630930 | 2 | 3145 | 17077004, 8290953*, 13734012, 11718220, 16495649, 123859, 13625993, 16760369, 23649163 |
| hybrid_rrf_rerank | 1.000000 | 1.000000 | 1.000000 | 1 | 10487 | 8290953*, 11718220, 16495649, 16760369, 23649163, 123859, 13625993, 13734012, 17077004 |

首个失败步骤中的关键错误召回文档（前3条非gold）：

- rank=1 `4387784` Structure of the proton-gated urea channel from the gastric pathogen Helicobacter pylori（本地heading=`SCIFACT-EVIDENCE-4387784`）

  > Half the world's population is chronically infected with Helicobacter pylori, causing gastritis, gastric ulcers and an increased incidence of gastric adenocarcinoma. Its proton-gated inner-membrane urea channel, HpUreI, is essential for survival in the acidic environment of the stomach. The channel is closed at neutral pH and opens at acidic pH to allow the rapid access of urea to cytoplasmic urease. Urease produces NH(3) and CO(2), neutralizing entering protons and thus buffering the periplasm to a pH of roughly 6.1 even in gastric juice at a pH below 2.0. Here we report the structure of HpUr…

  本地源文件： `DOCX=prepared/docx/020-scifact-4387784.docx` `PDF=prepared/pdf/020-scifact-4387784.pdf`
- rank=2 `17741440` Netting neutrophils in autoimmune small-vessel vasculitis（本地heading=`SCIFACT-EVIDENCE-17741440`）

  > Small-vessel vasculitis (SVV) is a chronic autoinflammatory condition linked to antineutrophil cytoplasm autoantibodies (ANCAs). Here we show that chromatin fibers, so-called neutrophil extracellular traps (NETs), are released by ANCA-stimulated neutrophils and contain the targeted autoantigens proteinase-3 (PR3) and myeloperoxidase (MPO). Deposition of NETs in inflamed kidneys and circulating MPO-DNA complexes suggest that NET formation triggers vasculitis and promotes the autoimmune response against neutrophil components in individuals with SVV.

  本地源文件： `DOCX=prepared/docx/124-scifact-17741440.docx` `PDF=prepared/pdf/124-scifact-17741440.pdf`
- rank=3 `2425364` Association between maternal serum 25-hydroxyvitamin D level and pregnancy and neonatal outcomes: systematic review and meta-analysis of observational studies.（本地heading=`SCIFACT-EVIDENCE-2425364`）

  > OBJECTIVE To assess the effect of 25-hydroxyvitamin D (25-OHD) levels on pregnancy outcomes and birth variables. DESIGN Systematic review and meta-analysis. DATA SOURCES Medline (1966 to August 2012), PubMed (2008 to August 2012), Embase (1980 to August 2012), CINAHL (1981 to August 2012), the Cochrane database of systematic reviews, and the Cochrane database of registered clinical trials. STUDY SELECTION Studies reporting on the association between serum 25-OHD levels during pregnancy and the outcomes of interest (pre-eclampsia, gestational diabetes, bacterial vaginosis, caesarean section, sm…

  本地源文件： `DOCX=prepared/docx/104-scifact-2425364.docx` `PDF=prepared/pdf/104-scifact-2425364.pdf`

首个可观测失败步骤：`dense_final_top10`。

直接证据：

- Dense gold首名次=Top10未命中
- Sparse gold首名次=3
- Hybrid-RRF gold首名次=2
- Hybrid-RRF+Rerank gold首名次=1
- 分类规则=sparse_only_success
- 逐候选分数=未采集

推断：Dense语义向量把主题相近干扰文档排在gold之前，而精确术语帮助Sparse命中；最大词项Jaccard=0.0260。

其他可能解释：相关性标注不完整、gold摘要与claim粒度不同、分块边界或Top10截断；当前终态run不能排除这些因素（sparse_only_success）。

反证实验：固定同一索引和query，额外留存Dense/Sparse候选Top100、融合逐项贡献、Rerank输入输出分数及chunk文本，再复跑该query验证首个内部失效点（sparse_only_success）。

### queryId=517

问题：High levels of copeptin decrease risk of diabetes.

Gold文档：

- `15663829` Mendelian Randomization Study of B-Type Natriuretic Peptide and Type 2 Diabetes: Evidence of Causal Association from Population Studies

  > BACKGROUND Genetic and epidemiological evidence suggests an inverse association between B-type natriuretic peptide (BNP) levels in blood and risk of type 2 diabetes (T2D), but the prospective association of BNP with T2D is uncertain, and it is unclear whether the association is confounded. METHODS AND FINDINGS We analysed the association between levels of the N-terminal fragment of pro-BNP (NT-pro-BNP) in blood and risk of incident T2D in a prospective case-cohort study and genotyped the variant rs198389 within the BNP locus in three T2D case-control studies. We combined our results with exist…

  本地源文件： `DOCX=prepared/docx/062-scifact-15663829.docx` `PDF=prepared/pdf/062-scifact-15663829.pdf`

| 变体 | Recall@10 | MRR@10 | nDCG@10 | Gold首名次 | 延迟ms | Top10文档ID（*为gold） |
|---|---:|---:|---:|---:|---:|---|
| dense | 0.000000 | 0.000000 | 0.000000 | Top10未命中 | 1642 | 970012, 13619127, 16760369, 13282296, 3553087, 29564505 |
| sparse | 1.000000 | 0.333333 | 0.500000 | 3 | 1627 | 13282296, 2425364, 15663829*, 16760369, 791050, 18340282, 10582939, 4687948, 17755060 |
| hybrid_rrf | 1.000000 | 0.142857 | 0.333333 | 7 | 1815 | 13282296, 16760369, 2425364, 29564505, 970012, 13619127, 15663829*, 4687948 |
| hybrid_rrf_rerank | 1.000000 | 1.000000 | 1.000000 | 1 | 7957 | 15663829*, 13619127, 29564505, 13282296, 2425364, 16760369, 970012, 4687948 |

首个失败步骤中的关键错误召回文档（前3条非gold）：

- rank=1 `970012` Cold Exposure Promotes Atherosclerotic Plaque Growth and Instability via UCP1-Dependent Lipolysis（本地heading=`SCIFACT-EVIDENCE-970012`）

  > Molecular mechanisms underlying the cold-associated high cardiovascular risk remain unknown. Here, we show that the cold-triggered food-intake-independent lipolysis significantly increased plasma levels of small low-density lipoprotein (LDL) remnants, leading to accelerated development of atherosclerotic lesions in mice. In two genetic mouse knockout models (apolipoprotein E(-/-) [ApoE(-/-)] and LDL receptor(-/-) [Ldlr(-/-)] mice), persistent cold exposure stimulated atherosclerotic plaque growth by increasing lipid deposition. Furthermore, marked increase of inflammatory cells and plaque-asso…

  本地源文件： `DOCX=prepared/docx/047-scifact-970012.docx` `PDF=prepared/pdf/047-scifact-970012.pdf`
- rank=2 `13619127` Diabetes treatments and risk of amputation, blindness, severe kidney failure, hyperglycaemia, and hypoglycaemia: open cohort study in primary care（本地heading=`SCIFACT-EVIDENCE-13619127`）

  > OBJECTIVE To assess the risks of amputation, blindness, severe kidney failure, hyperglycaemia, and hypoglycaemia in patients with type 2 diabetes associated with prescribed diabetes drugs, particularly newer agents including gliptins or glitazones (thiazolidinediones). DESIGN Open cohort study in primary care. SETTING 1243 practices contributing data to the QResearch database in England. PARTICIPANTS 469,688 patients with type 2 diabetes aged 25-84 years between 1 April 2007 and 31 January 2015. EXPOSURES Hypoglycaemic agents (glitazones, gliptins, metformin, sulphonylureas, insulin, and other…

  本地源文件： `DOCX=prepared/docx/025-scifact-13619127.docx` `PDF=prepared/pdf/025-scifact-13619127.pdf`
- rank=3 `16760369` Comparative determinants of 4-year cardiovascular event rates in stable outpatients at risk of or with atherothrombosis.（本地heading=`SCIFACT-EVIDENCE-16760369`）

  > CONTEXT Clinicians and trialists have difficulty with identifying which patients are highest risk for cardiovascular events. Prior ischemic events, polyvascular disease, and diabetes mellitus have all been identified as predictors of ischemic events, but their comparative contributions to future risk remain unclear. OBJECTIVE To categorize the risk of cardiovascular events in stable outpatients with various initial manifestations of atherothrombosis using simple clinical descriptors. DESIGN, SETTING, AND PATIENTS Outpatients with coronary artery disease, cerebrovascular disease, or peripheral…

  本地源文件： `DOCX=prepared/docx/179-scifact-16760369.docx` `PDF=prepared/pdf/179-scifact-16760369.pdf`

首个可观测失败步骤：`dense_final_top10`。

直接证据：

- Dense gold首名次=Top10未命中
- Sparse gold首名次=3
- Hybrid-RRF gold首名次=7
- Hybrid-RRF+Rerank gold首名次=1
- 分类规则=sparse_only_success
- 逐候选分数=未采集

推断：Dense语义向量把主题相近干扰文档排在gold之前，而精确术语帮助Sparse命中；最大词项Jaccard=0.0635。

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
| dense | 0.000000 | 0.000000 | 0.000000 | Top10未命中 | 3296 | 16472469, 13770184, 13734012, 27768226, 29564505, 25742130, 13519661, 14079881, 10984005 |
| sparse | 0.000000 | 0.000000 | 0.000000 | Top10未命中 | 4139 | 5476778, 8780599, 791050, 16495649, 17628888, 9650982, 25742130, 4381486, 7662395, 13770184 |
| hybrid_rrf | 0.000000 | 0.000000 | 0.000000 | Top10未命中 | 4071 | 13770184, 13519661, 25742130, 22038539, 6173523, 44172171, 29564505, 10984005 |
| hybrid_rrf_rerank | 0.000000 | 0.000000 | 0.000000 | Top10未命中 | 19254 | 29564505, 6173523, 13770184, 44172171, 10984005, 22038539, 25742130, 13519661 |

首个失败步骤中的关键错误召回文档（前3条非gold）：

- rank=1 `16472469` Targeting BRCA1 and BRCA2 Deficiencies with G-Quadruplex-Interacting Compounds（本地heading=`SCIFACT-EVIDENCE-16472469`）

  > G-quadruplex (G4)-forming genomic sequences, including telomeres, represent natural replication fork barriers. Stalled replication forks can be stabilized and restarted by homologous recombination (HR), which also repairs DNA double-strand breaks (DSBs) arising at collapsed forks. We have previously shown that HR facilitates telomere replication. Here, we demonstrate that the replication efficiency of guanine-rich (G-rich) telomeric repeats is decreased significantly in cells lacking HR. Treatment with the G4-stabilizing compound pyridostatin (PDS) increases telomere fragility in BRCA2-deficie…

  本地源文件： `DOCX=prepared/docx/052-scifact-16472469.docx` `PDF=prepared/pdf/052-scifact-16472469.pdf`
- rank=2 `13770184` Global, regional, and national comparative risk assessment of 79 behavioural, environmental and occupational, and metabolic risks or clusters of risks, 1990–2015: a systematic analysis for the Global Burden of Disease Study 2015（本地heading=`SCIFACT-EVIDENCE-13770184`）

  > BACKGROUND The Global Burden of Diseases, Injuries, and Risk Factors Study 2015 provides an up-to-date synthesis of the evidence for risk factor exposure and the attributable burden of disease. By providing national and subnational assessments spanning the past 25 years, this study can inform debates on the importance of addressing risks in context. METHODS We used the comparative risk assessment framework developed for previous iterations of the Global Burden of Disease Study to estimate attributable deaths, disability-adjusted life-years (DALYs), and trends in exposure by age group, sex, yea…

  本地源文件： `DOCX=prepared/docx/072-scifact-13770184.docx` `PDF=prepared/pdf/072-scifact-13770184.pdf`
- rank=3 `13734012` Prevalent abnormal prion protein in human appendixes after bovine spongiform encephalopathy epizootic: large scale survey（本地heading=`SCIFACT-EVIDENCE-13734012`）

  > OBJECTIVES To carry out a further survey of archived appendix samples to understand better the differences between existing estimates of the prevalence of subclinical infection with prions after the bovine spongiform encephalopathy epizootic and to see whether a broader birth cohort was affected, and to understand better the implications for the management of blood and blood products and for the handling of surgical instruments. DESIGN Irreversibly unlinked and anonymised large scale survey of archived appendix samples. SETTING Archived appendix samples from the pathology departments of 41 UK…

  本地源文件： `DOCX=prepared/docx/027-scifact-13734012.docx` `PDF=prepared/pdf/027-scifact-13734012.pdf`

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
| dense | 0.000000 | 0.000000 | 0.000000 | Top10未命中 | 3306 | 4687948, 11718220, 13619127, 24341590, 13625993, 1469751, 15476777 |
| sparse | 0.000000 | 0.000000 | 0.000000 | Top10未命中 | 3923 | 13734012, 4687948, 18340282, 791050, 24088502, 13625993, 20381484, 11335781, 4942718 |
| hybrid_rrf | 0.000000 | 0.000000 | 0.000000 | Top10未命中 | 3398 | 4687948, 13734012, 24088502, 34873974, 29564505, 11718220, 13843341, 39368721 |
| hybrid_rrf_rerank | 0.000000 | 0.000000 | 0.000000 | Top10未命中 | 14160 | 4687948, 13843341, 24088502, 39368721, 29564505, 11718220, 13734012, 34873974 |

首个失败步骤中的关键错误召回文档（前3条非gold）：

- rank=1 `4687948` HMG-CoA reductase inhibitors and the risk of hip fractures in elderly patients.（本地heading=`SCIFACT-EVIDENCE-4687948`）

  > CONTEXT Recent animal studies have found that 3-hydroxy-3-methylglutaryl coenzyme A (HMG-CoA) lipid-lowering drugs (statins) substantially increase bone formation, but whether statin use in humans results in clinically meaningful bone formation or a reduction in the risk of osteoporotic fractures is not known. OBJECTIVE To determine whether the use of statins is associated with reduced hip fracture risk. DESIGN Case-control study. SETTING AND PATIENTS A total of 6110 New Jersey residents aged 65 years or older and enrolled in Medicare and either Medicaid or the Pharmacy Assistance for the Aged…

  本地源文件： `DOCX=prepared/docx/036-scifact-4687948.docx` `PDF=prepared/pdf/036-scifact-4687948.pdf`
- rank=2 `11718220` Effectiveness of thigh-length graduated compression stockings to reduce the risk of deep vein thrombosis after stroke (CLOTS trial 1): a multicentre, randomised controlled trial（本地heading=`SCIFACT-EVIDENCE-11718220`）

  > BACKGROUND Deep vein thrombosis (DVT) and pulmonary embolism are common after stroke. In small trials of patients undergoing surgery, graduated compression stockings (GCS) reduce the risk of DVT. National stroke guidelines extrapolating from these trials recommend their use in patients with stroke despite insufficient evidence. We assessed the effectiveness of thigh-length GCS to reduce DVT after stroke. METHODS In this outcome-blinded, randomised controlled trial, 2518 patients who were admitted to hospital within 1 week of an acute stroke and who were immobile were enrolled from 64 centres i…

  本地源文件： `DOCX=prepared/docx/186-scifact-11718220.docx` `PDF=prepared/pdf/186-scifact-11718220.pdf`
- rank=3 `13619127` Diabetes treatments and risk of amputation, blindness, severe kidney failure, hyperglycaemia, and hypoglycaemia: open cohort study in primary care（本地heading=`SCIFACT-EVIDENCE-13619127`）

  > OBJECTIVE To assess the risks of amputation, blindness, severe kidney failure, hyperglycaemia, and hypoglycaemia in patients with type 2 diabetes associated with prescribed diabetes drugs, particularly newer agents including gliptins or glitazones (thiazolidinediones). DESIGN Open cohort study in primary care. SETTING 1243 practices contributing data to the QResearch database in England. PARTICIPANTS 469,688 patients with type 2 diabetes aged 25-84 years between 1 April 2007 and 31 January 2015. EXPOSURES Hypoglycaemic agents (glitazones, gliptins, metformin, sulphonylureas, insulin, and other…

  本地源文件： `DOCX=prepared/docx/025-scifact-13619127.docx` `PDF=prepared/pdf/025-scifact-13619127.pdf`

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
| dense | 0.000000 | 0.000000 | 0.000000 | Top10未命中 | 3213 | 3863543, 4388470, 11369420, 1084345, 22038539, 26851674, 2014909, 5483793, 12486491, 52873726 |
| sparse | 0.000000 | 0.000000 | 0.000000 | Top10未命中 | 2987 | 1606628, 14637235, 4388470, 11603066, 3475317, 3863543, 49556906, 33370, 6157837, 6923961 |
| hybrid_rrf | 0.000000 | 0.000000 | 0.000000 | Top10未命中 | 4968 | 4388470, 3863543, 19736671, 49556906, 1084345, 123859, 5483793, 14637235, 33370, 4709641 |
| hybrid_rrf_rerank | 0.000000 | 0.000000 | 0.000000 | Top10未命中 | 10837 | 3863543, 4709641, 4388470, 14637235, 19736671, 49556906, 1084345, 33370, 5483793, 123859 |

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

## rerank_reorder_gain

### queryId=768

问题：Mercaptopurine is anabolized into the inactive methylmercaptopurine by thiopurine methyltrasnferase (TPMT).

Gold文档：

- `6421792` Activating mutations in the NT5C2 nucleotidase gene drive chemotherapy resistance in relapsed ALL

  > Acute lymphoblastic leukemia (ALL) is an aggressive hematological tumor resulting from the malignant transformation of lymphoid progenitors. Despite intensive chemotherapy, 20% of pediatric patients and over 50% of adult patients with ALL do not achieve a complete remission or relapse after intensified chemotherapy, making disease relapse and resistance to therapy the most substantial challenge in the treatment of this disease. Using whole-exome sequencing, we identify mutations in the cytosolic 5'-nucleotidase II gene (NT5C2), which encodes a 5'-nucleotidase enzyme that is responsible for the…

  本地源文件： `DOCX=prepared/docx/051-scifact-6421792.docx` `PDF=prepared/pdf/051-scifact-6421792.pdf`

| 变体 | Recall@10 | MRR@10 | nDCG@10 | Gold首名次 | 延迟ms | Top10文档ID（*为gold） |
|---|---:|---:|---:|---:|---:|---|
| dense | 0.000000 | 0.000000 | 0.000000 | Top10未命中 | 3063 | 11603066, 24221369, 20231138, 3441524, 52873726, 23895668, 4387784, 1469751, 18421962 |
| sparse | 0.000000 | 0.000000 | 0.000000 | Top10未命中 | 1809 | 20381484, 56893404, 17741440, 11603066, 24294572, 6923961, 33499189, 23460562, 18174210, 17717391 |
| hybrid_rrf | 1.000000 | 0.111111 | 0.301030 | 9 | 1954 | 11603066, 24221369, 33499189, 24294572, 24341590, 20381484, 4423559, 11419230, 6421792*, 16472469 |
| hybrid_rrf_rerank | 1.000000 | 1.000000 | 1.000000 | 1 | 12683 | 6421792*, 24341590, 20381484, 11603066, 16472469, 24221369, 24294572, 4423559, 11419230, 33499189 |

首个失败步骤中的关键错误召回文档（前3条非gold）：

- rank=1 `11603066` Using Structural Information to Change the Phosphotransfer Specificity of a Two-Component Chemotaxis Signalling Complex（本地heading=`SCIFACT-EVIDENCE-11603066`）

  > Two-component signal transduction pathways comprising histidine protein kinases (HPKs) and their response regulators (RRs) are widely used to control bacterial responses to environmental challenges. Some bacteria have over 150 different two-component pathways, and the specificity of the phosphotransfer reactions within these systems is tightly controlled to prevent unwanted crosstalk. One of the best understood two-component signalling pathways is the chemotaxis pathway. Here, we present the 1.40 A crystal structure of the histidine-containing phosphotransfer domain of the chemotaxis HPK, CheA…

  本地源文件： `DOCX=prepared/docx/170-scifact-11603066.docx` `PDF=prepared/pdf/170-scifact-11603066.pdf`
- rank=2 `24221369` A Conserved Histidine in the RNA Sensor RIG-I Controls Immune Tolerance to N1-2'O-Methylated Self RNA.（本地heading=`SCIFACT-EVIDENCE-24221369`）

  > The cytosolic helicase retinoic acid-inducible gene-I (RIG-I) initiates immune responses to most RNA viruses by detecting viral 5'-triphosphorylated RNA (pppRNA). Although endogenous mRNA is also 5'-triphosphorylated, backbone modifications and the 5'-ppp-linked methylguanosine ((m7)G) cap prevent immunorecognition. Here we show that the methylation status of endogenous capped mRNA at the 5'-terminal nucleotide (N1) was crucial to prevent RIG-I activation. Moreover, we identified a single conserved amino acid (H830) in the RIG-I RNA binding pocket as the mediator of steric exclusion of N1-2'O-…

  本地源文件： `DOCX=prepared/docx/189-scifact-24221369.docx` `PDF=prepared/pdf/189-scifact-24221369.pdf`
- rank=3 `33499189` Full activation of the T cell receptor requires both clustering and conformational changes at CD3.（本地heading=`SCIFACT-EVIDENCE-33499189`）

  > T cell receptor (TCR-CD3) triggering involves both receptor clustering and conformational changes at the cytoplasmic tails of the CD3 subunits. The mechanism by which TCRalphabeta ligand binding confers conformational changes to CD3 is unknown. By using well-defined ligands, we showed that induction of the conformational change requires both multivalent engagement and the mobility restriction of the TCR-CD3 imposed by the plasma membrane. The conformational change is elicited by cooperative rearrangements of two TCR-CD3 complexes and does not require accompanying changes in the structure of th…

  本地源文件： `DOCX=prepared/docx/156-scifact-33499189.docx` `PDF=prepared/pdf/156-scifact-33499189.pdf`

首个可观测失败步骤：`hybrid_rrf_rank_order`。

直接证据：

- Dense gold首名次=Top10未命中
- Sparse gold首名次=Top10未命中
- Hybrid-RRF gold首名次=9
- Hybrid-RRF+Rerank gold首名次=1
- 分类规则=rerank_reorder_gain
- 逐候选分数=未采集

推断：Top10截断、语义近邻竞争或Rerank排序偏好可能共同影响；query与gold最大词项Jaccard=0.0256。

其他可能解释：相关性标注不完整、gold摘要与claim粒度不同、分块边界或Top10截断；当前终态run不能排除这些因素（rerank_reorder_gain）。

反证实验：固定同一索引和query，额外留存Dense/Sparse候选Top100、融合逐项贡献、Rerank输入输出分数及chunk文本，再复跑该query验证首个内部失效点（rerank_reorder_gain）。

### queryId=1225

问题：The locus rs647161 is associated with colorectal carcinoma.

Gold文档：

- `9650982` Genome-wide association analyses in East Asians identify new susceptibility loci for colorectal cancer

  > To identify new genetic factors for colorectal cancer (CRC), we conducted a                 genome-wide association study in east Asians. By analyzing genome-wide data in 2,098                 cases and 5,749 controls, we selected 64 promising SNPs for replication in an                 independent set of samples, including up to 5,358 cases and 5,922 controls. We                 identified four SNPs with association P values of 8.58 ×                     10(-7) to 3.77 × 10(-10)                 in the combined analysis of all east Asian samples. Three of the four were                 replicate…

  本地源文件： `DOCX=prepared/docx/033-scifact-9650982.docx` `PDF=prepared/pdf/033-scifact-9650982.pdf`

| 变体 | Recall@10 | MRR@10 | nDCG@10 | Gold首名次 | 延迟ms | Top10文档ID（*为gold） |
|---|---:|---:|---:|---:|---:|---|
| dense | 1.000000 | 1.000000 | 1.000000 | 1 | 3073 | 9650982*, 17717391, 15476777, 13905670, 7521113, 2095573, 5304891, 19736671, 13519661 |
| sparse | 0.000000 | 0.000000 | 0.000000 | Top10未命中 | 2338 | 17930286, 2095573, 56893404, 23649163, 1897324, 14376683, 24512064, 17000834, 5476778, 13905670 |
| hybrid_rrf | 1.000000 | 0.125000 | 0.315465 | 8 | 2671 | 2095573, 13905670, 5304891, 14376683, 23649163, 56893404, 24341590, 9650982*, 24512064, 18340282 |
| hybrid_rrf_rerank | 1.000000 | 1.000000 | 1.000000 | 1 | 8269 | 9650982*, 24341590, 23649163, 18340282, 56893404, 5304891, 14376683, 24512064, 2095573, 13905670 |

首个失败步骤中的关键错误召回文档（前3条非gold）：

- rank=1 `2095573` LDL-cholesterol concentrations: a genome-wide association study（本地heading=`SCIFACT-EVIDENCE-2095573`）

  > BACKGROUND LDL cholesterol has a causal role in the development of cardiovascular disease. Improved understanding of the biological mechanisms that underlie the metabolism and regulation of LDL cholesterol might help to identify novel therapeutic targets. We therefore did a genome-wide association study of LDL-cholesterol concentrations. METHODS We used genome-wide association data from up to 11,685 participants with measures of circulating LDL-cholesterol concentrations across five studies, including data for 293 461 autosomal single nucleotide polymorphisms (SNPs) with a minor allele frequen…

  本地源文件： `DOCX=prepared/docx/021-scifact-2095573.docx` `PDF=prepared/pdf/021-scifact-2095573.pdf`
- rank=2 `13905670` Human SNP Links Differential Outcomes in Inflammatory and Infectious Disease to a FOXO3-Regulated Pathway（本地heading=`SCIFACT-EVIDENCE-13905670`）

  > The clinical course and eventual outcome, or prognosis, of complex diseases varies enormously between affected individuals. This variability critically determines the impact a disease has on a patient's life but is very poorly understood. Here, we exploit existing genome-wide association study data to gain insight into the role of genetics in prognosis. We identify a noncoding polymorphism in FOXO3A (rs12212067: T > G) at which the minor (G) allele, despite not being associated with disease susceptibility, is associated with a milder course of Crohn's disease and rheumatoid arthritis and with…

  本地源文件： `DOCX=prepared/docx/163-scifact-13905670.docx` `PDF=prepared/pdf/163-scifact-13905670.pdf`
- rank=3 `5304891` Inter-individual variability and genetic influences on cytokine responses to bacteria and fungi（本地heading=`SCIFACT-EVIDENCE-5304891`）

  > Little is known about the inter-individual variation of cytokine responses to different pathogens in healthy individuals. To systematically describe cytokine responses elicited by distinct pathogens and to determine the effect of genetic variation on cytokine production, we profiled cytokines produced by peripheral blood mononuclear cells from 197 individuals of European origin from the 200 Functional Genomics (200FG) cohort in the Human Functional Genomics Project (http://www.humanfunctionalgenomics.org), obtained over three different years. We compared bacteria- and fungi-induced cytokine pr…

  本地源文件： `DOCX=prepared/docx/103-scifact-5304891.docx` `PDF=prepared/pdf/103-scifact-5304891.pdf`

首个可观测失败步骤：`hybrid_rrf_rank_order`。

直接证据：

- Dense gold首名次=1
- Sparse gold首名次=Top10未命中
- Hybrid-RRF gold首名次=8
- Hybrid-RRF+Rerank gold首名次=1
- 分类规则=rerank_reorder_gain
- 逐候选分数=未采集

推断：稀疏词项匹配对同义改写或词形差异不敏感；query与gold标题/片段最大词项Jaccard=0.0476。

其他可能解释：相关性标注不完整、gold摘要与claim粒度不同、分块边界或Top10截断；当前终态run不能排除这些因素（rerank_reorder_gain）。

反证实验：固定同一索引和query，额外留存Dense/Sparse候选Top100、融合逐项贡献、Rerank输入输出分数及chunk文本，再复跑该query验证首个内部失效点（rerank_reorder_gain）。

### queryId=1088

问题：Silencing of Bcl2 is important for the maintenance and progression of tumors.

Gold文档：

- `37549932` Antiapoptotic BCL-2 is required for maintenance of a model leukemia.

  > Resistance to apoptosis, often achieved by the overexpression of antiapoptotic proteins, is common and perhaps required in the genesis of cancer. However, it remains uncertain whether apoptotic defects are essential for tumor maintenance. To test this, we generated mice expressing a conditional BCL-2 gene and constitutive c-myc that develop lymphoblastic leukemia. Eliminating BCL-2 yielded rapid loss of leukemic cells and significantly prolonged survival, formally validating BCL-2 as a rational target for cancer therapy. Loss of this single molecule resulted in cell death, despite or perhaps a…

  本地源文件： `DOCX=prepared/docx/043-scifact-37549932.docx` `PDF=prepared/pdf/043-scifact-37549932.pdf`

| 变体 | Recall@10 | MRR@10 | nDCG@10 | Gold首名次 | 延迟ms | Top10文档ID（*为gold） |
|---|---:|---:|---:|---:|---:|---|
| dense | 1.000000 | 1.000000 | 1.000000 | 1 | 3244 | 37549932*, 23895668, 9559146, 6923961, 17755060, 33370, 7975937, 11369420, 17717391, 16472469 |
| sparse | 0.000000 | 0.000000 | 0.000000 | Top10未命中 | 3284 | 1897324, 15928989, 45638119, 13734012, 11369420, 17628888, 13519661, 11335781, 12580014, 11172205 |
| hybrid_rrf | 1.000000 | 0.142857 | 0.333333 | 7 | 3320 | 11369420, 1897324, 45638119, 17755060, 17628888, 39381118, 37549932*, 15928989, 6923961, 2988714 |
| hybrid_rrf_rerank | 1.000000 | 1.000000 | 1.000000 | 1 | 29005 | 37549932*, 39381118, 6923961, 17755060, 45638119, 1897324, 11369420, 15928989, 17628888, 2988714 |

首个失败步骤中的关键错误召回文档（前3条非gold）：

- rank=1 `11369420` Tetraspanin 3 Is Required for the Development and Propagation of Acute Myelogenous Leukemia.（本地heading=`SCIFACT-EVIDENCE-11369420`）

  > Acute Myelogenous Leukemia (AML) is an aggressive cancer that strikes both adults and children and is frequently resistant to therapy. Thus, identifying signals needed for AML propagation is a critical step toward developing new approaches for treating this disease. Here, we show that Tetraspanin 3 is a target of the RNA binding protein Musashi 2, which plays a key role in AML. We generated Tspan3 knockout mice that were born without overt defects. However, Tspan3 deletion impaired leukemia stem cell self-renewal and disease propagation and markedly improved survival in mouse models of AML. Ad…

  本地源文件： `DOCX=prepared/docx/028-scifact-11369420.docx` `PDF=prepared/pdf/028-scifact-11369420.pdf`
- rank=2 `1897324` A genetic screen identifies an LKB1–MARK signalling axis controlling the Hippo–YAP pathway（本地heading=`SCIFACT-EVIDENCE-1897324`）

  > The Hippo–YAP pathway is an emerging signalling cascade involved in the regulation of stem cell activity and organ size. To identify components of this pathway, we performed an RNAi-based kinome screen in human cells. Our screen identified several kinases not previously associated with Hippo signalling that control multiple cellular processes. One of the hits, LKB1, is a common tumour suppressor whose mechanism of action is only partially understood. We demonstrate that LKB1 acts through its substrates of the microtubule affinity-regulating kinase family to regulate the localization of the pol…

  本地源文件： `DOCX=prepared/docx/026-scifact-1897324.docx` `PDF=prepared/pdf/026-scifact-1897324.pdf`
- rank=3 `45638119` ALDH1 is a marker of normal and malignant human mammary stem cells and a predictor of poor clinical outcome.（本地heading=`SCIFACT-EVIDENCE-45638119`）

  > Application of stem cell biology to breast cancer research has been limited by the lack of simple methods for identification and isolation of normal and malignant stem cells. Utilizing in vitro and in vivo experimental systems, we show that normal and cancer human mammary epithelial cells with increased aldehyde dehydrogenase activity (ALDH) have stem/progenitor properties. These cells contain the subpopulation of normal breast epithelium with the broadest lineage differentiation potential and greatest growth capacity in a xenotransplant model. In breast carcinomas, high ALDH activity identifi…

  本地源文件： `DOCX=prepared/docx/050-scifact-45638119.docx` `PDF=prepared/pdf/050-scifact-45638119.pdf`

首个可观测失败步骤：`hybrid_rrf_rank_order`。

直接证据：

- Dense gold首名次=1
- Sparse gold首名次=Top10未命中
- Hybrid-RRF gold首名次=7
- Hybrid-RRF+Rerank gold首名次=1
- 分类规则=rerank_reorder_gain
- 逐候选分数=未采集

推断：稀疏词项匹配对同义改写或词形差异不敏感；query与gold标题/片段最大词项Jaccard=0.0811。

其他可能解释：相关性标注不完整、gold摘要与claim粒度不同、分块边界或Top10截断；当前终态run不能排除这些因素（rerank_reorder_gain）。

反证实验：固定同一索引和query，额外留存Dense/Sparse候选Top100、融合逐项贡献、Rerank输入输出分数及chunk文本，再复跑该query验证首个内部失效点（rerank_reorder_gain）。

### queryId=517

问题：High levels of copeptin decrease risk of diabetes.

Gold文档：

- `15663829` Mendelian Randomization Study of B-Type Natriuretic Peptide and Type 2 Diabetes: Evidence of Causal Association from Population Studies

  > BACKGROUND Genetic and epidemiological evidence suggests an inverse association between B-type natriuretic peptide (BNP) levels in blood and risk of type 2 diabetes (T2D), but the prospective association of BNP with T2D is uncertain, and it is unclear whether the association is confounded. METHODS AND FINDINGS We analysed the association between levels of the N-terminal fragment of pro-BNP (NT-pro-BNP) in blood and risk of incident T2D in a prospective case-cohort study and genotyped the variant rs198389 within the BNP locus in three T2D case-control studies. We combined our results with exist…

  本地源文件： `DOCX=prepared/docx/062-scifact-15663829.docx` `PDF=prepared/pdf/062-scifact-15663829.pdf`

| 变体 | Recall@10 | MRR@10 | nDCG@10 | Gold首名次 | 延迟ms | Top10文档ID（*为gold） |
|---|---:|---:|---:|---:|---:|---|
| dense | 0.000000 | 0.000000 | 0.000000 | Top10未命中 | 1642 | 970012, 13619127, 16760369, 13282296, 3553087, 29564505 |
| sparse | 1.000000 | 0.333333 | 0.500000 | 3 | 1627 | 13282296, 2425364, 15663829*, 16760369, 791050, 18340282, 10582939, 4687948, 17755060 |
| hybrid_rrf | 1.000000 | 0.142857 | 0.333333 | 7 | 1815 | 13282296, 16760369, 2425364, 29564505, 970012, 13619127, 15663829*, 4687948 |
| hybrid_rrf_rerank | 1.000000 | 1.000000 | 1.000000 | 1 | 7957 | 15663829*, 13619127, 29564505, 13282296, 2425364, 16760369, 970012, 4687948 |

首个失败步骤中的关键错误召回文档（前3条非gold）：

- rank=1 `13282296` Hypoglycemic episodes and risk of dementia in older patients with type 2 diabetes mellitus.（本地heading=`SCIFACT-EVIDENCE-13282296`）

  > CONTEXT Although acute hypoglycemia may be associated with cognitive impairment in children with type 1 diabetes, no studies to date have evaluated whether hypoglycemia is a risk factor for dementia in older patients with type 2 diabetes. OBJECTIVE To determine if hypoglycemic episodes severe enough to require hospitalization are associated with an increased risk of dementia in a population of older patients with type 2 diabetes followed up for 27 years. DESIGN, SETTING, AND PATIENTS A longitudinal cohort study from 1980-2007 of 16,667 patients with a mean age of 65 years and type 2 diabetes w…

  本地源文件： `DOCX=prepared/docx/155-scifact-13282296.docx` `PDF=prepared/pdf/155-scifact-13282296.pdf`
- rank=2 `16760369` Comparative determinants of 4-year cardiovascular event rates in stable outpatients at risk of or with atherothrombosis.（本地heading=`SCIFACT-EVIDENCE-16760369`）

  > CONTEXT Clinicians and trialists have difficulty with identifying which patients are highest risk for cardiovascular events. Prior ischemic events, polyvascular disease, and diabetes mellitus have all been identified as predictors of ischemic events, but their comparative contributions to future risk remain unclear. OBJECTIVE To categorize the risk of cardiovascular events in stable outpatients with various initial manifestations of atherothrombosis using simple clinical descriptors. DESIGN, SETTING, AND PATIENTS Outpatients with coronary artery disease, cerebrovascular disease, or peripheral…

  本地源文件： `DOCX=prepared/docx/179-scifact-16760369.docx` `PDF=prepared/pdf/179-scifact-16760369.pdf`
- rank=3 `2425364` Association between maternal serum 25-hydroxyvitamin D level and pregnancy and neonatal outcomes: systematic review and meta-analysis of observational studies.（本地heading=`SCIFACT-EVIDENCE-2425364`）

  > OBJECTIVE To assess the effect of 25-hydroxyvitamin D (25-OHD) levels on pregnancy outcomes and birth variables. DESIGN Systematic review and meta-analysis. DATA SOURCES Medline (1966 to August 2012), PubMed (2008 to August 2012), Embase (1980 to August 2012), CINAHL (1981 to August 2012), the Cochrane database of systematic reviews, and the Cochrane database of registered clinical trials. STUDY SELECTION Studies reporting on the association between serum 25-OHD levels during pregnancy and the outcomes of interest (pre-eclampsia, gestational diabetes, bacterial vaginosis, caesarean section, sm…

  本地源文件： `DOCX=prepared/docx/104-scifact-2425364.docx` `PDF=prepared/pdf/104-scifact-2425364.pdf`

首个可观测失败步骤：`hybrid_rrf_rank_order`。

直接证据：

- Dense gold首名次=Top10未命中
- Sparse gold首名次=3
- Hybrid-RRF gold首名次=7
- Hybrid-RRF+Rerank gold首名次=1
- 分类规则=rerank_reorder_gain
- 逐候选分数=未采集

推断：Dense语义向量把主题相近干扰文档排在gold之前，而精确术语帮助Sparse命中；最大词项Jaccard=0.0635。

其他可能解释：相关性标注不完整、gold摘要与claim粒度不同、分块边界或Top10截断；当前终态run不能排除这些因素（rerank_reorder_gain）。

反证实验：固定同一索引和query，额外留存Dense/Sparse候选Top100、融合逐项贡献、Rerank输入输出分数及chunk文本，再复跑该query验证首个内部失效点（rerank_reorder_gain）。

### queryId=1194

问题：The arm density of TatAd complexes is due to structural rearrangements within Class1 TatAd complexes such as the 'charge zipper mechanism'.

Gold文档：

- `11419230` Folding and Self-Assembly of the TatA Translocation Pore Based on a Charge Zipper Mechanism

  > We propose a concept for the folding and self-assembly of the pore-forming TatA complex from the Twin-arginine translocase and of other membrane proteins based on electrostatic "charge zippers. " Each subunit of TatA consists of a transmembrane segment, an amphiphilic helix (APH), and a C-terminal densely charged region (DCR). The sequence of charges in the DCR is complementary to the charge pattern on the APH, suggesting that the protein can be "zipped up" by a ladder of seven salt bridges. The length of the resulting hairpin matches the lipid bilayer thickness, hence a transmembrane pore cou…

  本地源文件： `DOCX=prepared/docx/141-scifact-11419230.docx` `PDF=prepared/pdf/141-scifact-11419230.pdf`

| 变体 | Recall@10 | MRR@10 | nDCG@10 | Gold首名次 | 延迟ms | Top10文档ID（*为gold） |
|---|---:|---:|---:|---:|---:|---|
| dense | 1.000000 | 1.000000 | 1.000000 | 1 | 2225 | 11419230*, 33499189, 17628888, 15319019, 52873726, 17587795, 306006, 11603066 |
| sparse | 0.000000 | 0.000000 | 0.000000 | Top10未命中 | 1656 | 33499189, 11335781, 28937856, 10991183, 17628888, 5289038, 24221369, 20381484, 3203590, 8646760 |
| hybrid_rrf | 1.000000 | 0.166667 | 0.356207 | 6 | 2538 | 33499189, 17628888, 10991183, 3203590, 2831620, 11419230*, 12631697, 12486491, 1897324 |
| hybrid_rrf_rerank | 1.000000 | 1.000000 | 1.000000 | 1 | 11064 | 11419230*, 10991183, 33499189, 17628888, 12631697, 3203590, 2831620, 1897324, 12486491 |

首个失败步骤中的关键错误召回文档（前3条非gold）：

- rank=1 `33499189` Full activation of the T cell receptor requires both clustering and conformational changes at CD3.（本地heading=`SCIFACT-EVIDENCE-33499189`）

  > T cell receptor (TCR-CD3) triggering involves both receptor clustering and conformational changes at the cytoplasmic tails of the CD3 subunits. The mechanism by which TCRalphabeta ligand binding confers conformational changes to CD3 is unknown. By using well-defined ligands, we showed that induction of the conformational change requires both multivalent engagement and the mobility restriction of the TCR-CD3 imposed by the plasma membrane. The conformational change is elicited by cooperative rearrangements of two TCR-CD3 complexes and does not require accompanying changes in the structure of th…

  本地源文件： `DOCX=prepared/docx/156-scifact-33499189.docx` `PDF=prepared/pdf/156-scifact-33499189.pdf`
- rank=2 `17628888` ATPase-Dependent Control of the Mms21 SUMO Ligase during DNA Repair（本地heading=`SCIFACT-EVIDENCE-17628888`）

  > Modification of proteins by SUMO is essential for the maintenance of genome integrity. During DNA replication, the Mms21-branch of the SUMO pathway counteracts recombination intermediates at damaged replication forks, thus facilitating sister chromatid disjunction. The Mms21 SUMO ligase docks to the arm region of the Smc5 protein in the Smc5/6 complex; together, they cooperate during recombinational DNA repair. Yet how the activity of the SUMO ligase is controlled remains unknown. Here we show that the SUMO ligase and the chromosome disjunction functions of Mms21 depend on its docking to an in…

  本地源文件： `DOCX=prepared/docx/130-scifact-17628888.docx` `PDF=prepared/pdf/130-scifact-17628888.pdf`
- rank=3 `10991183` The Rho GEFs LARG and GEF-H1 regulate the mechanical response to force on integrins（本地heading=`SCIFACT-EVIDENCE-10991183`）

  > How individual cells respond to mechanical forces is of considerable interest to biologists as force affects many aspects of cell behaviour. The application of force on integrins triggers cytoskeletal rearrangements and growth of the associated adhesion complex, resulting in increased cellular stiffness, also known as reinforcement. Although RhoA has been shown to play a role during reinforcement, the molecular mechanisms that regulate its activity are unknown. By combining biochemical and biophysical approaches, we identified two guanine nucleotide exchange factors (GEFs), LARG and GEF-H1, as…

  本地源文件： `DOCX=prepared/docx/076-scifact-10991183.docx` `PDF=prepared/pdf/076-scifact-10991183.pdf`

首个可观测失败步骤：`hybrid_rrf_rank_order`。

直接证据：

- Dense gold首名次=1
- Sparse gold首名次=Top10未命中
- Hybrid-RRF gold首名次=6
- Hybrid-RRF+Rerank gold首名次=1
- 分类规则=rerank_reorder_gain
- 逐候选分数=未采集

推断：稀疏词项匹配对同义改写或词形差异不敏感；query与gold标题/片段最大词项Jaccard=0.0854。

其他可能解释：相关性标注不完整、gold摘要与claim粒度不同、分块边界或Top10截断；当前终态run不能排除这些因素（rerank_reorder_gain）。

反证实验：固定同一索引和query，额外留存Dense/Sparse候选Top100、融合逐项贡献、Rerank输入输出分数及chunk文本，再复跑该query验证首个内部失效点（rerank_reorder_gain）。

## rerank_reorder_harm

### queryId=1

问题：0-dimensional biomaterials show inductive properties.

Gold文档：

- `31715818` New opportunities: the use of nanotechnologies to manipulate and track stem cells.

  > Nanotechnologies are emerging platforms that could be useful in measuring, understanding, and manipulating stem cells. Examples include magnetic nanoparticles and quantum dots for stem cell labeling and in vivo tracking; nanoparticles, carbon nanotubes, and polyplexes for the intracellular delivery of genes/oligonucleotides and protein/peptides; and engineered nanometer-scale scaffolds for stem cell differentiation and transplantation. This review examines the use of nanotechnologies for stem cell tracking, differentiation, and transplantation. We further discuss their utility and the potentia…

  本地源文件： `DOCX=prepared/docx/168-scifact-31715818.docx` `PDF=prepared/pdf/168-scifact-31715818.pdf`

| 变体 | Recall@10 | MRR@10 | nDCG@10 | Gold首名次 | 延迟ms | Top10文档ID（*为gold） |
|---|---:|---:|---:|---:|---:|---|
| dense | 0.000000 | 0.000000 | 0.000000 | Top10未命中 | 2239 | 8290953, 4388470, 12631697, 1469751, 11172205, 2177022, 11419230, 5373138, 3475317 |
| sparse | 0.000000 | 0.000000 | 0.000000 | Top10未命中 | 2218 | 3441524, 10582939, 15663829, 1471041, 13519661, 10984005, 24088502, 17077004, 19675911, 1642727 |
| hybrid_rrf | 1.000000 | 1.000000 | 1.000000 | 1 | 2519 | 31715818*, 10582939, 3441524, 24088502, 4456756, 4350400, 4381486, 1642727, 7521113, 24338780 |
| hybrid_rrf_rerank | 1.000000 | 0.111111 | 0.301030 | 9 | 17617 | 24338780, 4381486, 10582939, 4350400, 4456756, 1642727, 3441524, 24088502, 31715818*, 7521113 |

首个失败步骤中的关键错误召回文档（前3条非gold）：

- rank=1 `24338780` Lethal autoimmune myocarditis in interferon-gamma receptor-deficient mice: enhanced disease severity by impaired inducible nitric oxide synthase induction.（本地heading=`SCIFACT-EVIDENCE-24338780`）

  > BACKGROUND Interferon-gamma (IFN-gamma) is an essential cytokine in the regulation of inflammatory responses in autoimmune diseases. Little is known about its role in inflammatory heart disease. METHODS AND RESULTS We showed that IFN-gamma receptor-deficient mice (IFN-gammaR(-/-)) on a BALB/c background immunized with a peptide derived from cardiac alpha-myosin heavy chain develop severe myocarditis with high mortality. Although myocarditis subsided in wild-type mice after 3 weeks, IFN-gammaR(-/-) mice showed persistent disease. The persistent inflammation was accompanied by vigorous in vitro…

  本地源文件： `DOCX=prepared/docx/101-scifact-24338780.docx` `PDF=prepared/pdf/101-scifact-24338780.pdf`
- rank=2 `4381486` Haematopoietic stem cells do not asymmetrically segregate chromosomes or retain BrdU（本地heading=`SCIFACT-EVIDENCE-4381486`）

  > Stem cells are proposed to segregate chromosomes asymmetrically during self-renewing divisions so that older (‘immortal’) DNA strands are retained in daughter stem cells whereas newly synthesized strands segregate to differentiating cells. Stem cells are also proposed to retain DNA labels, such as 5-bromo-2-deoxyuridine (BrdU), either because they segregate chromosomes asymmetrically or because they divide slowly. However, the purity of stem cells among BrdU-label-retaining cells has not been documented in any tissue, and the ‘immortal strand hypothesis’ has not been tested in a system with de…

  本地源文件： `DOCX=prepared/docx/019-scifact-4381486.docx` `PDF=prepared/pdf/019-scifact-4381486.pdf`
- rank=3 `10582939` Induction therapy with autologous mesenchymal stem cells in living-related kidney transplants: a randomized controlled trial.（本地heading=`SCIFACT-EVIDENCE-10582939`）

  > CONTEXT Antibody-based induction therapy plus calcineurin inhibitors (CNIs) reduce acute rejection rates in kidney recipients; however, opportunistic infections and toxic CNI effects remain challenging. Reportedly, mesenchymal stem cells (MSCs) have successfully treated graft-vs-host disease. OBJECTIVE To assess autologous MSCs as replacement of antibody induction for patients with end-stage renal disease who undergo ABO-compatible, cross-match-negative kidney transplants from a living-related donor. DESIGN, SETTING, AND PATIENTS One hundred fifty-nine patients were enrolled in this single-sit…

  本地源文件： `DOCX=prepared/docx/048-scifact-10582939.docx` `PDF=prepared/pdf/048-scifact-10582939.pdf`

首个可观测失败步骤：`rerank_final_top10`。

直接证据：

- Dense gold首名次=Top10未命中
- Sparse gold首名次=Top10未命中
- Hybrid-RRF gold首名次=1
- Hybrid-RRF+Rerank gold首名次=9
- 分类规则=rerank_reorder_harm
- 逐候选分数=未采集

推断：Top10截断、语义近邻竞争或Rerank排序偏好可能共同影响；query与gold最大词项Jaccard=0.0000。

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
| dense | 1.000000 | 0.500000 | 0.630930 | 2 | 1839 | 32275758, 10874408*, 11603066, 31141365, 24341590, 14376683, 36606083, 10300888, 13734012 |
| sparse | 1.000000 | 0.500000 | 0.630930 | 2 | 1644 | 5373138, 10874408*, 1471041, 13906581, 4381486, 13519661, 18750453, 27768226, 12827098, 14637235 |
| hybrid_rrf | 1.000000 | 1.000000 | 1.000000 | 1 | 2208 | 10874408*, 5373138, 36606083, 14637235, 1471041, 4381486, 11603066, 10300888, 13519661 |
| hybrid_rrf_rerank | 1.000000 | 0.250000 | 0.430677 | 4 | 9256 | 14637235, 36606083, 10300888, 10874408*, 5373138, 11603066, 4381486, 1471041, 13519661 |

首个失败步骤中的关键错误召回文档（前3条非gold）：

- rank=1 `14637235` Histone levels are regulated by phosphorylation and ubiquitylation dependent proteolysis（本地heading=`SCIFACT-EVIDENCE-14637235`）

  > Histone levels are tightly regulated to prevent harmful effects such as genomic instability and hypersensitivity to DNA-damaging agents due to the accumulation of these highly basic proteins when DNA replication slows down or stops. Although chromosomal histones are stable, excess (non-chromatin bound) histones are rapidly degraded in a Rad53 (radiation sensitive 53) kinase-dependent manner in Saccharomyces cerevisiae. Here we demonstrate that excess histones associate with Rad53 in vivo and seem to undergo modifications such as tyrosine phosphorylation and polyubiquitylation, before their pro…

  本地源文件： `DOCX=prepared/docx/066-scifact-14637235.docx` `PDF=prepared/pdf/066-scifact-14637235.pdf`
- rank=2 `36606083` Quantitative, genome-wide analysis of eukaryotic replication initiation and termination.（本地heading=`SCIFACT-EVIDENCE-36606083`）

  > Many fundamental aspects of DNA replication, such as the exact locations where DNA synthesis is initiated and terminated, how frequently origins are used, and how fork progression is influenced by transcription, are poorly understood. Via the deep sequencing of Okazaki fragments, we comprehensively document replication fork directionality throughout the S. cerevisiae genome, which permits the systematic analysis of initiation, origin efficiency, fork progression, and termination. We show that leading-strand initiation preferentially occurs within a nucleosome-free region at replication origins…

  本地源文件： `DOCX=prepared/docx/061-scifact-36606083.docx` `PDF=prepared/pdf/061-scifact-36606083.pdf`
- rank=3 `10300888` Domestication and Divergence of Saccharomyces cerevisiae Beer Yeasts（本地heading=`SCIFACT-EVIDENCE-10300888`）

  > Whereas domestication of livestock, pets, and crops is well documented, it is still unclear to what extent microbes associated with the production of food have also undergone human selection and where the plethora of industrial strains originates from. Here, we present the genomes and phenomes of 157 industrial Saccharomyces cerevisiae yeasts. Our analyses reveal that today's industrial yeasts can be divided into five sublineages that are genetically and phenotypically separated from wild strains and originate from only a few ancestors through complex patterns of domestication and local diverg…

  本地源文件： `DOCX=prepared/docx/056-scifact-10300888.docx` `PDF=prepared/pdf/056-scifact-10300888.pdf`

首个可观测失败步骤：`rerank_final_top10`。

直接证据：

- Dense gold首名次=2
- Sparse gold首名次=2
- Hybrid-RRF gold首名次=1
- Hybrid-RRF+Rerank gold首名次=4
- 分类规则=rerank_reorder_harm
- 逐候选分数=未采集

推断：Top10截断、语义近邻竞争或Rerank排序偏好可能共同影响；query与gold最大词项Jaccard=0.0789。

其他可能解释：相关性标注不完整、gold摘要与claim粒度不同、分块边界或Top10截断；当前终态run不能排除这些因素（rerank_reorder_harm）。

反证实验：固定同一索引和query，额外留存Dense/Sparse候选Top100、融合逐项贡献、Rerank输入输出分数及chunk文本，再复跑该query验证首个内部失效点（rerank_reorder_harm）。

### queryId=508

问题：Hematopoietic Stem Cell purification reaches purity rate of up to 50%.

Gold文档：

- `13980338` Combined Single-Cell Functional and Gene Expression Analysis Resolves Heterogeneity within Stem Cell Populations

  > Heterogeneity within the self-renewal durability of adult hematopoietic stem cells (HSCs) challenges our understanding of the molecular framework underlying HSC function. Gene expression studies have been hampered by the presence of multiple HSC subtypes and contaminating non-HSCs in bulk HSC populations. To gain deeper insight into the gene expression program of murine HSCs, we combined single-cell functional assays with flow cytometric index sorting and single-cell gene expression assays. Through bioinformatic integration of these datasets, we designed an unbiased sorting strategy that separ…

  本地源文件： `DOCX=prepared/docx/165-scifact-13980338.docx` `PDF=prepared/pdf/165-scifact-13980338.pdf`

| 变体 | Recall@10 | MRR@10 | nDCG@10 | Gold首名次 | 延迟ms | Top10文档ID（*为gold） |
|---|---:|---:|---:|---:|---:|---|
| dense | 1.000000 | 0.250000 | 0.430677 | 4 | 2007 | 4381486, 16472469, 18909530, 13980338*, 17077004, 27910499, 10582939, 31141365, 18174210, 24088502 |
| sparse | 1.000000 | 1.000000 | 1.000000 | 1 | 1630 | 13980338*, 3898784, 17077004, 1606628, 4381486, 16495649, 45638119, 27910499, 33370, 11718220 |
| hybrid_rrf | 1.000000 | 1.000000 | 1.000000 | 1 | 3705 | 13980338*, 4381486, 27910499, 17077004, 45638119, 33370, 18909530, 18174210, 10582939 |
| hybrid_rrf_rerank | 1.000000 | 0.250000 | 0.430677 | 4 | 11450 | 4381486, 18909530, 10582939, 13980338*, 27910499, 45638119, 33370, 17077004, 18174210 |

首个失败步骤中的关键错误召回文档（前3条非gold）：

- rank=1 `4381486` Haematopoietic stem cells do not asymmetrically segregate chromosomes or retain BrdU（本地heading=`SCIFACT-EVIDENCE-4381486`）

  > Stem cells are proposed to segregate chromosomes asymmetrically during self-renewing divisions so that older (‘immortal’) DNA strands are retained in daughter stem cells whereas newly synthesized strands segregate to differentiating cells. Stem cells are also proposed to retain DNA labels, such as 5-bromo-2-deoxyuridine (BrdU), either because they segregate chromosomes asymmetrically or because they divide slowly. However, the purity of stem cells among BrdU-label-retaining cells has not been documented in any tissue, and the ‘immortal strand hypothesis’ has not been tested in a system with de…

  本地源文件： `DOCX=prepared/docx/019-scifact-4381486.docx` `PDF=prepared/pdf/019-scifact-4381486.pdf`
- rank=2 `18909530` Contractile forces sustain and polarize hematopoiesis from stem and progenitor cells.（本地heading=`SCIFACT-EVIDENCE-18909530`）

  > Self-renewal and differentiation of stem cells depend on asymmetric division and polarized motility processes that in other cell types are modulated by nonmuscle myosin-II (MII) forces and matrix mechanics. Here, mass spectrometry-calibrated intracellular flow cytometry of human hematopoiesis reveals MIIB to be a major isoform that is strongly polarized in hematopoietic stem cells and progenitors (HSC/Ps) and thereby downregulated in differentiated cells via asymmetric division. MIIA is constitutive and activated by dephosphorylation during cytokine-triggered differentiation of cells grown on…

  本地源文件： `DOCX=prepared/docx/177-scifact-18909530.docx` `PDF=prepared/pdf/177-scifact-18909530.pdf`
- rank=3 `10582939` Induction therapy with autologous mesenchymal stem cells in living-related kidney transplants: a randomized controlled trial.（本地heading=`SCIFACT-EVIDENCE-10582939`）

  > CONTEXT Antibody-based induction therapy plus calcineurin inhibitors (CNIs) reduce acute rejection rates in kidney recipients; however, opportunistic infections and toxic CNI effects remain challenging. Reportedly, mesenchymal stem cells (MSCs) have successfully treated graft-vs-host disease. OBJECTIVE To assess autologous MSCs as replacement of antibody induction for patients with end-stage renal disease who undergo ABO-compatible, cross-match-negative kidney transplants from a living-related donor. DESIGN, SETTING, AND PATIENTS One hundred fifty-nine patients were enrolled in this single-sit…

  本地源文件： `DOCX=prepared/docx/048-scifact-10582939.docx` `PDF=prepared/pdf/048-scifact-10582939.pdf`

首个可观测失败步骤：`rerank_final_top10`。

直接证据：

- Dense gold首名次=4
- Sparse gold首名次=1
- Hybrid-RRF gold首名次=1
- Hybrid-RRF+Rerank gold首名次=4
- 分类规则=rerank_reorder_harm
- 逐候选分数=未采集

推断：Top10截断、语义近邻竞争或Rerank排序偏好可能共同影响；query与gold最大词项Jaccard=0.0685。

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
| dense | 1.000000 | 1.000000 | 1.000000 | 1 | 2243 | 13625993*, 12991445, 24088502, 13843341, 4687948, 10582939, 16760369, 13906581, 11718220 |
| sparse | 1.000000 | 1.000000 | 1.000000 | 1 | 1703 | 13625993*, 24088502, 17717391, 18872233, 24142891, 17077004, 20381484 |
| hybrid_rrf | 1.000000 | 1.000000 | 1.000000 | 1 | 2554 | 13625993*, 24088502, 12991445, 18872233, 29564505, 4687948, 10582939 |
| hybrid_rrf_rerank | 1.000000 | 0.333333 | 0.500000 | 3 | 10145 | 10582939, 24088502, 13625993*, 29564505, 18872233, 12991445, 4687948 |

首个失败步骤中的关键错误召回文档（前3条非gold）：

- rank=1 `10582939` Induction therapy with autologous mesenchymal stem cells in living-related kidney transplants: a randomized controlled trial.（本地heading=`SCIFACT-EVIDENCE-10582939`）

  > CONTEXT Antibody-based induction therapy plus calcineurin inhibitors (CNIs) reduce acute rejection rates in kidney recipients; however, opportunistic infections and toxic CNI effects remain challenging. Reportedly, mesenchymal stem cells (MSCs) have successfully treated graft-vs-host disease. OBJECTIVE To assess autologous MSCs as replacement of antibody induction for patients with end-stage renal disease who undergo ABO-compatible, cross-match-negative kidney transplants from a living-related donor. DESIGN, SETTING, AND PATIENTS One hundred fifty-nine patients were enrolled in this single-sit…

  本地源文件： `DOCX=prepared/docx/048-scifact-10582939.docx` `PDF=prepared/pdf/048-scifact-10582939.pdf`
- rank=2 `24088502` Clinical outcomes following institution of the Canadian universal leukoreduction program for red blood cell transfusions.（本地heading=`SCIFACT-EVIDENCE-24088502`）

  > CONTEXT A number of countries have implemented a policy of universal leukoreduction of their blood supply, but the potential role of leukoreduction in decreasing postoperative mortality and infection is unclear. OBJECTIVE To evaluate clinical outcomes following adoption of a national universal prestorage leukoreduction program for blood transfusions. DESIGN, SETTING, AND POPULATION Retrospective before-and-after cohort study conducted from August 1998 to August 2000 in 23 academic and community hospitals throughout Canada, enrolling 14 786 patients who received red blood cell transfusions foll…

  本地源文件： `DOCX=prepared/docx/139-scifact-24088502.docx` `PDF=prepared/pdf/139-scifact-24088502.pdf`
- rank=4 `29564505` Inflammatory biomarkers and exacerbations in chronic obstructive pulmonary disease.（本地heading=`SCIFACT-EVIDENCE-29564505`）

  > IMPORTANCE Exacerbations of respiratory symptoms in chronic obstructive pulmonary disease (COPD) have profound and long-lasting adverse effects on patients. OBJECTIVE To test the hypothesis that elevated levels of inflammatory biomarkers in individuals with stable COPD are associated with an increased risk of having exacerbations. DESIGN, SETTING, AND PARTICIPANTS Prospective cohort study examining 61,650 participants with spirometry measurements from the Copenhagen City Heart Study (2001-2003) and the Copenhagen General Population Study (2003-2008). Of these, 6574 had COPD, defined as a ratio…

  本地源文件： `DOCX=prepared/docx/059-scifact-29564505.docx` `PDF=prepared/pdf/059-scifact-29564505.pdf`

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

### queryId=421

问题：Flexible molecules experience greater steric hindrance in the tumor microenviroment than rigid molecules.

Gold文档：

- `11172205` Quantum dots spectrally distinguish multiple species within the tumor milieu in vivo

  > A solid tumor is an organ composed of cancer and host cells embedded in an extracellular matrix and nourished by blood vessels. A prerequisite to understanding tumor pathophysiology is the ability to distinguish and monitor each component in dynamic studies. Standard fluorophores hamper simultaneous intravital imaging of these components. Here, we used multiphoton microscopy techniques and transgenic mice that expressed green fluorescent protein, and combined them with the use of quantum dot preparations. We show that these fluorescent semiconductor nanocrystals can be customized to concurrent…

  本地源文件： `DOCX=prepared/docx/145-scifact-11172205.docx` `PDF=prepared/pdf/145-scifact-11172205.pdf`

| 变体 | Recall@10 | MRR@10 | nDCG@10 | Gold首名次 | 延迟ms | Top10文档ID（*为gold） |
|---|---:|---:|---:|---:|---:|---|
| dense | 1.000000 | 0.333333 | 0.500000 | 3 | 2215 | 16787954, 18399038, 11172205*, 2177022, 5483793, 1469751, 11041152, 16472469, 10991183, 5531479 |
| sparse | 1.000000 | 0.250000 | 0.430677 | 4 | 1525 | 25742130, 6173523, 6157837, 11172205*, 6923961, 33370, 4387784, 12991445, 12580014, 17755060 |
| hybrid_rrf | 1.000000 | 1.000000 | 1.000000 | 1 | 2612 | 11172205*, 4387784, 18399038, 23895668, 17755060, 5531479, 6173523, 18855191, 19736671, 13980338 |
| hybrid_rrf_rerank | 1.000000 | 0.333333 | 0.500000 | 3 | 8107 | 23895668, 17755060, 11172205*, 19736671, 18399038, 5531479, 13980338, 6173523, 4387784, 18855191 |

首个失败步骤中的关键错误召回文档（前3条非gold）：

- rank=1 `23895668` mTORC2 Regulates Amino Acid Metabolism in Cancer by Phosphorylation of the Cystine-Glutamate Antiporter xCT.（本地heading=`SCIFACT-EVIDENCE-23895668`）

  > Mutations in cancer reprogram amino acid metabolism to drive tumor growth, but the molecular mechanisms are not well understood. Using an unbiased proteomic screen, we identified mTORC2 as a critical regulator of amino acid metabolism in cancer via phosphorylation of the cystine-glutamate antiporter xCT. mTORC2 phosphorylates serine 26 at the cytosolic N terminus of xCT, inhibiting its activity. Genetic inhibition of mTORC2, or pharmacologic inhibition of the mammalian target of rapamycin (mTOR) kinase, promotes glutamate secretion, cystine uptake, and incorporation into glutathione, linking g…

  本地源文件： `DOCX=prepared/docx/065-scifact-23895668.docx` `PDF=prepared/pdf/065-scifact-23895668.pdf`
- rank=2 `17755060` Control of Nutrient Stress-Induced Metabolic Reprogramming by PKCζ in Tumorigenesis（本地heading=`SCIFACT-EVIDENCE-17755060`）

  > Tumor cells have high-energetic and anabolic needs and are known to adapt their metabolism to be able to survive and keep proliferating under conditions of nutrient stress. We show that PKCζ deficiency promotes the plasticity necessary for cancer cells to reprogram their metabolism to utilize glutamine through the serine biosynthetic pathway in the absence of glucose. PKCζ represses the expression of two key enzymes of the pathway, PHGDH and PSAT1, and phosphorylates PHGDH at key residues to inhibit its enzymatic activity. Interestingly, the loss of PKCζ in mice results in enhanced intestinal…

  本地源文件： `DOCX=prepared/docx/159-scifact-17755060.docx` `PDF=prepared/pdf/159-scifact-17755060.pdf`
- rank=4 `19736671` Evolution of metastasis revealed by mutational landscapes of chemically induced skin cancers（本地heading=`SCIFACT-EVIDENCE-19736671`）

  > Human tumors show a high level of genetic heterogeneity, but the processes that influence the timing and route of metastatic dissemination of the subclones are unknown. Here we have used whole-exome sequencing of 103 matched benign, malignant and metastatic skin tumors from genetically heterogeneous mice to demonstrate that most metastases disseminate synchronously from the primary tumor, supporting parallel rather than linear evolution as the predominant model of metastasis. Shared mutations between primary carcinomas and their matched metastases have the distinct A-to-T signature of the init…

  本地源文件： `DOCX=prepared/docx/126-scifact-19736671.docx` `PDF=prepared/pdf/126-scifact-19736671.pdf`

首个可观测失败步骤：`rerank_final_top10`。

直接证据：

- Dense gold首名次=3
- Sparse gold首名次=4
- Hybrid-RRF gold首名次=1
- Hybrid-RRF+Rerank gold首名次=3
- 分类规则=rerank_reorder_harm
- 逐候选分数=未采集

推断：Top10截断、语义近邻竞争或Rerank排序偏好可能共同影响；query与gold最大词项Jaccard=0.0366。

其他可能解释：相关性标注不完整、gold摘要与claim粒度不同、分块边界或Top10截断；当前终态run不能排除这些因素（rerank_reorder_harm）。

反证实验：固定同一索引和query，额外留存Dense/Sparse候选Top100、融合逐项贡献、Rerank输入输出分数及chunk文本，再复跑该query验证首个内部失效点（rerank_reorder_harm）。

## 输入SHA-256

- queries: `146e928420eabd22ee95322f1711cdee9bd42cfa456db44090a35e8c414eaf35`
- qrels: `2a808171a79832d5798afb879c2d912f5c8863b09c6427fe454f20dc2a025f73`
- documents: `7e1479ca549e3e48dd442b03770e88f160ef90334a8e18f09cfa6349fee24e08`
- documentMap: `8a93c2134c689d3fd78d90ddee9414b3a08bc43e20b56ef55d781ea9f61ef17b`
- run: `839bc25bf08055824850a351f03f613fe4ffe3c6d05583ce3547936fefe1ab08`
