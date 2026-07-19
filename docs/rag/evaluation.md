# RAG 质量与性能评测方法

## 原则

- 所有最终数字必须可由原始 JSONL、qrels、manifest 和程序重新计算。
- 固定代码/JAR、数据、索引 generation、模型 revision、Profile、随机 seed、线程、并发和硬件后再比较组件差异。
- warmup 不进入正式指标；错误、降级、空结果和缺失查询保留并计入分母。
- 失败 run 独立保存，不能和后续 run 拼接。
- 没有 gold answer 时不报告回答正确率、Faithfulness 或 Answer Relevance；检索相关性不能冒充生成质量。
- 小样本 p99 等于或接近最大值，只作极值观察，不声称稳定 SLA 或统计显著。

## Java 工具链

评测实现位于 `ai-agent-scaffold-benchmark`，CLI 支持：

```text
prepare   BEIR corpus/queries/qrels -> 确定性 prepared 数据
run       创建独立租户并执行摄取、四组查询和评分
evaluate  复用已验证 targets，只执行 warmup、正式查询和评分
score     从 qrels 与 run.jsonl 独立重算质量指标
load      对既有 targets 做分级并发、阶段延迟和吞吐测试
```

构建：

```bash
cd /path/to/Agent-Project
export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home
mvn -pl ai-agent-scaffold-benchmark -am clean package -DskipTests=false
```

CLI 帮助是参数的权威入口：

```bash
java -jar ai-agent-scaffold-benchmark/target/ai-agent-scaffold-benchmark-cli-jar-with-dependencies.jar
```

本地 mini 闭环使用：

```bash
RAG_BENCHMARK_BASE_URL=http://127.0.0.1:8092/api \
RAG_BENCHMARK_PREPARED_DIR=/path/to/prepared \
RAG_BENCHMARK_WARMUP_QUERIES=10 \
bash ai-agent-scaffold-benchmark/scripts/run-local-rag-mini.sh
```

脚本要求在项目根目录运行环境具备 Java 17、curl、jq、openssl 和 git；应用8092必须已经启动，prepared目录必须存在且输出目录必须不存在。fresh run通过注册接口创建隔离owner租户，并把明文临时密码和Token限制在进程环境/受限临时目录，退出时只清除本地明文材料。服务端tenant/user/KB/Profile/Binding、对象和Qdrant point按manifest的`cleanup=keep`保留用于审计，当前没有自动删除资源；随机密码销毁后不能宣称这些资源可直接复跑。

复用`RAG_BENCHMARK_EXISTING_TARGETS`时，脚本不会再注册新租户，而是要求调用方从受控秘密源通过环境注入原租户的`RAG_BENCHMARK_USERNAME`和`RAG_BENCHMARK_PASSWORD`，登录并保留长跑刷新能力。没有原租户凭据时必须走受控密码恢复或从零新建run，不能用新租户访问旧targets。禁止把真实凭据写进命令历史、manifest或本文件。

## SciFact 数据快照

当前完整检索质量评测使用 BEIR SciFact test split：

| 项目 | 值 |
|---|---:|
| 文档 | 5183 |
| 查询 | 300 |
| qrel pairs | 339 |
| 生产摄取后的 child chunks | 7548 |
| 官方压缩包 MD5 | `5f7d1de60b170fc8027bb7898e2efca1` |
| 下载文件 SHA-256 | `536e14446a0ba56ed1398ab1055f39fe852686ecad24a6306c80c490fa8e0165` |
| 规范化 Markdown bytes | 7,957,673 |
| 规范化 Markdown SHA-256 | `0287493f09e9cb8d13d44bd46c01540229a7bad18d8c9da344f60429a89d6680` |
| document map SHA-256 | `1718e1ed99f145f839156afccca3b13de7608a154232e5d829f20a36cb124c84` |
| queries SHA-256 | `331f88f940774ac84e1fc6ef517720dd94d07deab77efbdc85f42fc405335ad0` |
| qrels SHA-256 | `5602d9f31c96d309a906692e1b722a9acfc4138c5d52e06d47bbb89a9c4ab7c3` |

来源 URL、revision、复合许可和下载日期保存在 prepared manifest；大型原始语料不提交 Git。

## 四组组件消融

同一数据快照固定：

1. Dense only。
2. Sparse only（稳定 hashing log-TF）。
3. Dense + Sparse + RRF。
4. Dense + Sparse + RRF + Cross-Encoder Rerank。

当前 SciFact Profile 固定 Dense/Sparse 各召回100，Fusion/最终 TopK=10，neighborWindow=0；Rerank组把10个业务候选按3/3/3/1串行请求，所有子批共享总 deadline，再按原始分数全局排序。任何Rerank fallback都使该完整run不满足发布门禁。

## 质量指标

程序输出：

- Recall@1/5/10
- Precision@10
- MRR@10
- graded nDCG@10
- MAP@10（分母为该查询全部正例）
- Success@1/5/10

未知 query、重复 ranked document 或非法 run 直接拒绝；缺失查询按零分。SciFact没有gold answer，因此 `answerMetrics=not_evaluated_no_gold_answers`。

完整 run 门禁：

```bash
set -euo pipefail
RUN=/path/to/run
PREPARED=/path/to/prepared

test "$(jq -r '.status' "$RUN/run-manifest.json")" = completed
test -s "$RUN/metrics.json"

jq -se '
  length == 1200 and
  (map(select(.errorCode != null)) | length) == 0 and
  (map(select(.degraded == true)) | length) == 0 and
  (map(select((.rankedDocumentIds // []) | length == 0)) | length) == 0 and
  (map((.variant|tostring)+"/"+(.queryId|tostring)) | unique | length) == 1200 and
  ([group_by(.variant)[] | {key:.[0].variant,value:length}] | from_entries) ==
    {dense:300,sparse:300,hybrid_rrf:300,hybrid_rrf_rerank:300} and
  (map(select(.variant == "hybrid_rrf_rerank" and
    (((.candidateCounts.rerankCandidateCount // 0) <= 0) or
     ((.stageTimingsMs.rerankMs // 0) <= 0)))) | length) == 0
' "$RUN/run.jsonl" >/dev/null

test "$(jq -r '.mode' "$RUN/run-manifest.json")" = evaluate_existing_targets
test "$(jq -r '.targetsSha256' "$RUN/run-manifest.json")" != null
test "$(jq -r '.sourceSha256' "$RUN/targets.json")" != null
test "$(jq -r '.targetsSha256' "$RUN/run-manifest.json")" = \
     "$(jq -r '.sourceSha256' "$RUN/targets.json")"

jq -r '.generatedFiles[] | [.name,.sha256] | @tsv' "$PREPARED/manifest.json" |
while IFS=$'\t' read -r name expected; do
  test "$(shasum -a 256 "$PREPARED/$name" | awk '{print $1}')" = "$expected"
done

test "$(wc -l < "$PREPARED/queries.jsonl" | tr -d ' ')" = 300
test "$(tail -n +2 "$PREPARED/qrels.tsv" | cut -f1 | sort -u | wc -l | tr -d ' ')" = 300

CLI=ai-agent-scaffold-benchmark/target/ai-agent-scaffold-benchmark-cli-jar-with-dependencies.jar
RECOMPUTED="$RUN/metrics-recomputed.json"
test ! -e "$RECOMPUTED"
java -jar "$CLI" score \
  --qrels "$PREPARED/qrels.tsv" \
  --run "$RUN/run.jsonl" \
  --out "$RECOMPUTED"

test "$(jq -cS '.variants' "$RUN/metrics.json")" = \
     "$(jq -cS '.variants' "$RECOMPUTED")"
test "$(jq -r '.manifest.runSha256' "$RECOMPUTED")" = \
     "$(shasum -a 256 "$RUN/run.jsonl" | awk '{print $1}')"
test "$(jq -r '.manifest.qrelsSha256' "$RECOMPUTED")" = \
     "$(shasum -a 256 "$PREPARED/qrels.tsv" | awk '{print $1}')"
jq -e '[.variants[] | select(.queryCount != 300 or .missingRunCount != 0)] | length == 0' \
  "$RECOMPUTED" >/dev/null
```

以上命令使用Bash、jq、shasum、awk、cut和sort；任一步非零即门禁失败。它验证1200条、唯一键、四组数量、0 error/degraded/empty、Rerank真实执行、prepared文件hash、qrels覆盖，并通过独立score把质量指标绑定到实际run/qrels hash。

其中targets比较只适用于manifest `mode=evaluate_existing_targets` 的复评分，命令会先核对模式并拒绝null。fresh `run`生成的manifest没有targets来源hash字段，只能在结束后记录`targets.json`文件自身SHA-256，不能使用`null == null`冒充来源校验。另须人工把manifest `codeRevision` 与运行JAR的构建记录/SHA-256对应；当前schema没有保存JAR hash，不能从manifest自动证明。数据库使用受限只读账号按本次tenant/run_id核对 `rag_retrieval_record` 唯一retrieval、状态和 `rag_retrieval_citation` 的 `(retrieval_id,rank_no)`/citation唯一性，SQL结果与凭据不得进入公开产物。

## 延迟与性能

质量 run 按 variant 输出 `elapsedMs` 以及 configuration、embedding、dense、sparse、fusion、rerank、hydration、assembly、audit、pipeline total、service 的 mean/p50/p95/p99/max，分位数算法为 nearest-rank。

并发压测使用 `load`：

```bash
java -jar ai-agent-scaffold-benchmark/target/ai-agent-scaffold-benchmark-cli-jar-with-dependencies.jar load \
  --base-url http://127.0.0.1:8092/api \
  --prepared /path/to/prepared \
  --targets /path/to/targets.json \
  --out /path/to/empty-output \
  --run-id example-load \
  --code-revision "$(git rev-parse HEAD)" \
  --concurrency-levels 1,2,4,8,16 \
  --warmup-per-variant 10 \
  --requests-per-variant 100 \
  --phase-timeout-seconds 7200 \
  --request-timeout-seconds 240 \
  --cli-jar-sha256 "$(shasum -a 256 ai-agent-scaffold-benchmark/target/ai-agent-scaffold-benchmark-cli-jar-with-dependencies.jar | awk '{print $1}')" \
  --app-jar-sha256 "$(shasum -a 256 ai-agent-scaffold-app/target/ai-agent-scaffold-app.jar | awk '{print $1}')" \
  --resource-evidence /path/to/evidence-manifest.json
```

每轮必须记录端点、请求数、并发、线程、warmup、超时、CLI/App JAR hash、吞吐、状态码、响应大小、error/degraded/empty rate、wall与阶段p50/p95/p99，并同步采集Java/GC、MySQL和RAG服务器各容器CPU/内存。`load`对warmup和measured逐条flush，任一错误、降级、空排名、重复排名、非法Rerank或缺失传输证据都会立即失败。工具使用closed-loop固定请求数，不代表open-loop指定到达率下的过载能力。项目内可使用`run-local-rag-load.sh`在登录、Qdrant隧道、质量run门禁和资源采样闭环下启动。

## 已验证的 SciFact 质量结果

最终可发布质量产物位于本机受控目录`/tmp/rag-quality-scifact-20260719/run-scifact-quality-resume-3d9510c-r11`。它通过严格断点门禁复用了553条健康前缀，只请求剩余647条，没有从头重跑1200条。最终为1200条、1200个唯一variant/query，四组各300，0 error、0 degraded、0 empty、0重复排名；300条Rerank均有正候选数和正耗时。

| 变体 | Recall@10 | MRR@10 | nDCG@10 | MAP@10 |
|---|---:|---:|---:|---:|
| Dense | 0.797944 | 0.655835 | 0.683385 | 0.641088 |
| Sparse | 0.487778 | 0.321922 | 0.355442 | 0.310521 |
| Hybrid RRF | 0.750667 | 0.566630 | 0.604539 | 0.552843 |
| Hybrid RRF + Rerank | 0.750667 | 0.646028 | 0.663244 | 0.628238 |

Rerank相对未重排Hybrid的MRR/nDCG/MAP分别提高约0.079398/0.058705/0.075396，Recall@10不变。但Dense在四项总体指标上仍略高，所以不能声称“组件越多质量必然越好”。CLI `score`生成独立`metrics-independent.json`，再用第二套标准库实现复算，最大绝对浮点差为`4.44e-16`。

### 召回失败案例与因果边界

项目内的[失败案例Markdown](evaluation-results/scifact-r11-failure-cases.md)和[机器可读JSON](evaluation-results/scifact-r11-failure-cases.json)由benchmark CLI直接读取上述r11 `run.jsonl`、本地queries/qrels、document-map和已固化Markdown语料生成。报告展示完整问题、gold标题与正文片段、四变体Top10/名次/逐query指标，并展示首个可观测失败步骤中前三条错误召回文档的标题与正文片段。

全量300查询的确定性分类计数为：Dense未命中而Hybrid命中10，Sparse未命中而Hybrid命中81，Dense单路成功98，Sparse单路成功3，四变体持续Top10漏召回45，Rerank MRR改善67、MRR下降29。严格以Recall@10从0到1/1到0定义时，Rerank rescue和harm均为0；这与Hybrid和Rerank的Recall@10完全相同一致，说明本轮Rerank只改变候选名次，没有改变Top10文档集合的命中性。报告每类最多展示3个代表case，按指标差和queryId确定性排序，共21个case；两次独立生成已做字节级`cmp`。

复算命令：

```bash
java -jar ai-agent-scaffold-benchmark/target/ai-agent-scaffold-benchmark-cli-jar-with-dependencies.jar failure-cases \
  --queries docs/rag/evaluation-data/scifact/prepared/queries.jsonl \
  --qrels docs/rag/evaluation-data/scifact/prepared/qrels.tsv \
  --documents docs/rag/evaluation-data/scifact/prepared/documents/benchmark-0001.md \
  --document-map docs/rag/evaluation-data/scifact/prepared/document-map.jsonl \
  --run /tmp/rag-quality-scifact-20260719/run-scifact-quality-resume-3d9510c-r11/run.jsonl \
  --out-json /path/to/new-report.json \
  --out-markdown /path/to/new-report.md \
  --max-per-category 3
```

报告JSON SHA-256为`61f6f34aadccf7c64311a7be6db2653793e9dc5c4410742a65b97b7ccb5536f0`，Markdown为`6da9cb37537682b733476eba8942418fb05dd226a6be08c933e8f1afbe3a4657`，本次生成CLI JAR为`8b667a993403eac4fb7b4d48d3fac213ae4d40bc8fea4313b46c350265e5f91d`。原始run没有保存逐候选分数、Dense/Sparse Top100文档ID或RRF逐项贡献，所以报告明确把这些字段标为“未采集”；首个失效点是基于四个消融终态的首个可观测步骤，内部算子级因果仍需增加候选/分数留痕后复跑代表query，不能由当前结果编造。

关键SHA-256：正式run `24331ecdbb58978e37a92bff9c1afad5c09d1abadba68b88fdbf4d0b0ee792a5`，metrics `e48c03a097ae8b02198b5af70cd6ff7703cf91daa4cf9fac0ba26ee046674c3e`，independent metrics `e3ac2eb39bb494df425de0140900bc47d0656f2b7c42989cbd80a06dc320b352`，CLI JAR `970621ed164b1d4af02a90cb81cde1d4cd5deda331e9e70cb787327e3a04932d`，App JAR `484270ca47b4dfca65e8de075cf6e553b6679fbb4a5866441fb40a9b0c0775eb`。质量run由120/240秒客户策略的两个分段构成，其合并延迟不用作性能结论。

## 已验证的稳定容量结果

性能评测固定为同一SciFact prepared/targets、同一App JAR、Rerank Top10和3/3/3/1子批。两个独立completed run分别以`2,1`和`1,2`顺序执行，每轮40 warmup+160 measured；合计320条正式样本。每轮各160个唯一“并发×变体×query”组合，合并后仍为160个组合且每个恰有2次独立观测；若将runId纳入键则320个全部唯一。全部0 error/degraded/empty。原始目录为`/tmp/rag-load-stable-r1-ebbe5d0`、`/tmp/rag-load-stable-r2-48f9099`，资源证据目录分别在名为上述目录加`-evidence`后缀的同级路径。两轮CLI JAR均为`f1deb4d207d916827ebb4e673a196786e73520fafd42fecbda960c4ccd74559d`，App JAR均为`484270ca47b4dfca65e8de075cf6e553b6679fbb4a5866441fb40a9b0c0775eb`。

| 轮次 | 并发 | 成功吞吐 req/s | Dense p50/p95 ms | Sparse p50/p95 ms | Hybrid p50/p95 ms | Rerank p50/p95 ms |
|---|---:|---:|---:|---:|---:|---:|
| stable-r1（顺序 2,1） | 1 | 0.1969 | 1194 / 2535 | 276 / 582 | 1153 / 2439 | 15054 / 24862 |
| stable-r1（顺序 2,1） | 2 | 0.3199 | 892 / 1691 | 312 / 583 | 1173 / 2270 | 20984 / 33212 |
| stable-r2（顺序 1,2） | 1 | 0.3004 | 500 / 828 | 242 / 435 | 1030 / 1808 | 10966 / 15169 |
| stable-r2（顺序 1,2） | 2 | 0.3726 | 572 / 1241 | 257 / 540 | 844 / 1219 | 18210 / 28004 |
| 两轮合并（每变体40样本） | 1 | 0.2379 | 695 / 1788 | 255 / 582 | 1130 / 2207 | 13866 / 24493 |
| 两轮合并（每变体40样本） | 2 | 0.3442 | 630 / 1526 | 299 / 570 | 986 / 2006 | 20691 / 28701 |

合并Rerank stage p95在并发1/2下为21764/27798ms，而Dense/Sparse/Hybrid的p95均不超过2.21s。两轮的长尾差异很大，说明公网路径和CPU推理队列有显著抖动；每组40样本的p99仍不足以作稳定SLA。

并发4诊断run在第39条measured时命中Hybrid+Rerank fallback，失败样本为67.19s。当时Reranker容器峰值CPU 566.50%、内存限额占比67.07%，前后无restart/OOM；App自身峰值仅16.2% CPU。当前App `maxConcurrency=2`，一次Top10又被拆成4个串行子批，远端queue time最高11.41s，60秒Rerank总deadline被耗尽。因此当前完整Rerank链路的已验证健康边界是并发2，并发4不可作发布容量。

子批A/B也保留失败证据：批次10在当前`max-concurrent-requests=8`部署下的每次重试均返回`no permits available`，证明该组合不可用；这与permit上限一致，但不从日志反推TEI内部permit的精确分配算法。批次8和5虽通过最小功能门禁，同query正式Rerank分别为15.215s和19.439s，都没有优于批次3的8.930s。样本不足以证明精确倍数，但不支持改默认值，所以已恢复批次3。

两轮completed evidence中，Reranker峰值CPU为518.03%/539.45%、内存均67.84%左右；Embedding峰值290.53%/285.69%，Qdrant内存仅3.68%/3.71%，App峰值35.3%/20.3% CPU、约967MiB RSS。两轮前后8容器均running、restart=0、OOM=false，证据manifest的四个文件hash均已与实际文件复核一致。结论是Reranker CPU推理与排队是当前首要瓶颈，其次是Embedding CPU；MySQL、Qdrant内存和本地Java资源都不是本轮先触发的边界。

两轮`load-manifest.json`的SHA-256分别为`b6027a7db13abd99ffb3aafd375e6adca2fb6e29b407794858b5f23c288e2a3c`、`1a31c7e435dffd7d6d970deb99a44864b60475b8c6c0cf8eaca1c28117f327f3`；`load-report.json`分别为`79bd1c5fb7db020b9fe14756cddb4006c2124d5aae78750937f51d7fd814a759`、`97d7a5a64bf8500c73fa28a27190c21293d291678d57a6ef88fd64ea6bf5143b`；`evidence-manifest.json`分别为`c5d306d57c1f5fb1896191a38f4a3f1a9eba9f142a1d745504d2225e5ab86092`、`98edf3c75ad4f5d846247188305acf62495ec704db248d885c65ca5f5f6f4692`。

上述性能数据仅代表当前closed-loop、本地Java→公网RAG服务器、指定CPU模型和当时资源限额。它不证明open-loop到达率、跨地域生产SLA、生成答案正确率，也不能排除更长时间窗的更高p99。
