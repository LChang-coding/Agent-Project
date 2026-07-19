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
  --request-timeout-seconds 120
```

每轮必须记录端点、请求数、并发、线程、warmup、超时、吞吐、状态码、响应大小、error/degraded/empty rate、wall与阶段p50/p95/p99，并同步采集Java/Hikari/GC、MySQL、网络和RAG服务器各容器CPU/内存。工具使用closed-loop固定请求数，不代表open-loop指定到达率下的过载能力。

## 当前运行状态

完整 SciFact 第四次复评分目录为本机受控临时目录，运行代码 revision 为 `1e9ebbf422ada2f8be1781b0c3ee26f048f12143`。此前因Qdrant瞬态错误、Reranker降级和Embedding间歇超时产生的失败run均独立保留，不参与最终聚合。

本节故意不抄写运行中的部分 Recall/MRR/nDCG/MAP 或 p95/p99。只有评测器写出完成状态和 `metrics.json`，并通过上述1200条门禁后，才在最终报告中加入四组绝对值、差值、延迟分布、资源证据和瓶颈结论。
