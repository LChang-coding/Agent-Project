package cn.bugstack.ai.benchmark.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 将真实debug阶段轨迹与既有失败case合并为可审计的内部失效报告。 */
public final class RagInternalDiagnosticReporter {

    private static final Pattern HEADING = Pattern.compile("^# ([^ ]+) — (.+)$");
    private static final int EXCERPT_LIMIT = 600;
    private static final double SCORE_TOLERANCE = 1.0e-9;
    private static final Map<String, Set<String>> OUTCOMES_BY_STAGE = Map.of(
            "dense_raw", Set.of("returned_by_vector_store"),
            "sparse_raw", Set.of("returned_by_vector_store"),
            "fusion", Set.of("kept_after_fusion_threshold_topk"),
            "candidate_filter", Set.of("kept", "discarded_tombstone", "discarded_duplicate_content_hash"),
            "pre_assembly", Set.of("kept_without_rerank"),
            "rerank_input", Set.of("sent_to_reranker"),
            "rerank_output", Set.of("kept_after_rerank"),
            "context_budget", Set.of("accepted_citation", "discarded_final_topk",
                    "discarded_document_tombstone", "discarded_empty_context",
                    "discarded_global_token_budget", "discarded_binding_token_budget"));
    private final ObjectMapper mapper;

    public RagInternalDiagnosticReporter(ObjectMapper mapper) { this.mapper = mapper; }

    public Report generate(Configuration configuration) throws IOException {
        configuration.validate();
        JsonNode failureRoot = mapper.readTree(configuration.failureReport().toFile());
        Map<String, SelectedQuery> selected = readSelectedQueries(failureRoot.path("cases"));
        Map<String, Map<String, RagDiagnosticCaseRunner.DiagnosticRecord>> records =
                readDiagnostics(configuration.diagnostics());
        Map<String, DocumentEvidence> documents = readDocuments(configuration.documents(), configuration.documentMap());
        verifyInputs(configuration, failureRoot, selected, records);
        List<QueryAnalysis> queryAnalyses = new ArrayList<>();
        int exactRankingMatches = 0;
        int expectedRankingComparisons = selected.size() * RagFailureCaseReporter.VARIANTS.size();
        Set<String> retrievalIds = new LinkedHashSet<>();
        for (SelectedQuery query : selected.values()) {
            Map<String, RagDiagnosticCaseRunner.DiagnosticRecord> variants = records.get(query.queryId());
            if (variants == null || !variants.keySet().equals(new LinkedHashSet<>(RagFailureCaseReporter.VARIANTS))) {
                throw new IllegalArgumentException("诊断缺少完整四变体: " + query.queryId());
            }
            Map<String, VariantAnalysis> analyses = new LinkedHashMap<>();
            for (String variant : RagFailureCaseReporter.VARIANTS) {
                RagDiagnosticCaseRunner.DiagnosticRecord record = variants.get(variant);
                if (!query.question().equals(record.question()) || !retrievalIds.add(record.retrievalId())) {
                    throw new IllegalArgumentException("诊断问题文本不一致或retrievalId重复: " + query.queryId());
                }
                List<String> expectedRanking = query.oldRankings().get(variant);
                if (expectedRanking == null) throw new IllegalArgumentException("失败报告缺少变体排名: " + query.queryId());
                boolean rankingMatch = expectedRanking.equals(record.rankedDocumentIds());
                if (!rankingMatch) throw new IllegalArgumentException("最终排名相对基线漂移: " + query.queryId() + "/" + variant);
                exactRankingMatches++;
                analyses.put(variant, analyzeVariant(record, query.goldDocumentIds(), rankingMatch, documents));
            }
            List<String> drift = observedDrift(variants);
            if (!drift.isEmpty()) throw new IllegalArgumentException("跨变体候选漂移: " + query.queryId() + "/" + String.join(",", drift));
            queryAnalyses.add(new QueryAnalysis(query.queryId(), query.question(), query.categories(),
                    query.goldDocuments(), analyses, true, List.of()));
        }
        if (!records.keySet().equals(selected.keySet())) throw new IllegalArgumentException("诊断query集合与失败报告不一致");
        Map<String, Integer> coverageLossCounts = countLosses(queryAnalyses, false);
        Map<String, Integer> totalLossCounts = countLosses(queryAnalyses, true);
        Map<String, Integer> rerankEffectCounts = new LinkedHashMap<>();
        queryAnalyses.stream().map(value -> value.variants().get("hybrid_rrf_rerank").rerankEffect().classification())
                .sorted().forEach(value -> rerankEffectCounts.merge(value, 1, Integer::sum));
        Map<String, String> hashes = new LinkedHashMap<>();
        hashes.put("failureReport", sha256(configuration.failureReport()));
        hashes.put("diagnostics", sha256(configuration.diagnostics()));
        hashes.put("diagnosticManifest", sha256(configuration.diagnosticManifest()));
        hashes.put("qrels", sha256(configuration.qrels()));
        hashes.put("documents", sha256(configuration.documents()));
        hashes.put("documentMap", sha256(configuration.documentMap()));
        Manifest manifest = new Manifest(3, "rag-internal-diagnostic-v3", selected.size(),
                records.values().stream().mapToInt(Map::size).sum(), exactRankingMatches,
                expectedRankingComparisons, true, List.of(), hashes,
                coverageLossCounts, totalLossCounts,
                java.util.Collections.unmodifiableMap(new LinkedHashMap<>(rerankEffectCounts)),
                List.of("firstObserved字段表示阶段轨迹中首个可观测损失，不等同于模型或索引的不可反驳根因。",
                        "fusion实现将score threshold与TopK合并，轨迹只能定位到FUSION_THRESHOLD_OR_TOPK_LOSS。",
                        "context outcome可直接证明淘汰分支，但未采集具体Token差额与扩展上下文组成。",
                        "当前报告只支持每条请求恰好一个binding/profile；多binding局部排名不会被混成全局排名。",
                        "Hybrid raw union只表达Dense/Sparse两路覆盖并集，不提供跨分支全局名次语义。",
                        "每个变体记录并校验其binding/profile单作用域；四个消融target的binding/profile本来不同，跨变体指纹比较会归一化这两个target局部ID。",
                        "跨变体可比性校验共享知识库/文档/版本/generation/chunk、outcome与分数容差；未采集完整模型/index冻结指纹。"));
        return new Report(manifest, List.copyOf(queryAnalyses));
    }

    private void verifyInputs(Configuration configuration, JsonNode failureRoot,
                              Map<String, SelectedQuery> selected,
                              Map<String, Map<String, RagDiagnosticCaseRunner.DiagnosticRecord>> records) throws IOException {
        JsonNode diagnosticManifest = mapper.readTree(configuration.diagnosticManifest().toFile());
        int recordCount = records.values().stream().mapToInt(Map::size).sum();
        if (diagnosticManifest.path("schemaVersion").asInt() != 1
                || !"completed".equals(diagnosticManifest.path("status").asText())
                || diagnosticManifest.path("queryCount").asInt(-1) != selected.size()
                || diagnosticManifest.path("expectedRecordCount").asInt(-1) != recordCount
                || diagnosticManifest.path("completedRecordCount").asInt(-1) != recordCount
                || diagnosticManifest.path("runId").asText().isBlank()
                || diagnosticManifest.path("codeRevision").asText().isBlank()
                || diagnosticManifest.path("targetsSha256").asText().isBlank()
                || !sha256(configuration.diagnostics()).equals(diagnosticManifest.path("diagnosticJsonlSha256").asText())
                || !sha256(configuration.failureReport()).equals(diagnosticManifest.path("caseReportSha256").asText())) {
            throw new IllegalArgumentException("诊断manifest状态、计数或SHA-256校验失败");
        }
        String runId = diagnosticManifest.path("runId").asText();
        if (records.values().stream().flatMap(value -> value.values().stream()).anyMatch(record ->
                !runId.equals(record.runId()) || record.httpStatus() != 200 || record.retrievalId() == null
                        || record.retrievalId().isBlank() || record.elapsedMs() < 0 || record.responseBytes() < 1)) {
            throw new IllegalArgumentException("诊断记录runId、HTTP状态或基础字段与manifest不一致");
        }
        JsonNode failureHashes = failureRoot.path("manifest").path("inputSha256");
        if (!sha256(configuration.qrels()).equals(failureHashes.path("qrels").asText())
                || !sha256(configuration.documents()).equals(failureHashes.path("documents").asText())
                || !sha256(configuration.documentMap()).equals(failureHashes.path("documentMap").asText())) {
            throw new IllegalArgumentException("失败报告输入SHA-256与当前qrels/文档不一致");
        }
        Map<String, Map<String, Integer>> qrels = new BeirDatasetLoader(mapper)
                .loadQrels(configuration.qrels(), BeirDatasetLoader.Limits.defaults());
        for (SelectedQuery query : selected.values()) {
            Map<String, Integer> queryQrels = qrels.get(query.queryId());
            if (queryQrels == null) throw new IllegalArgumentException("qrels缺少诊断query: " + query.queryId());
            Set<String> positive = new LinkedHashSet<>();
            queryQrels.entrySet().stream().filter(value -> value.getValue() > 0)
                    .map(Map.Entry::getKey).sorted().forEach(positive::add);
            if (!positive.equals(query.goldDocumentIds())) {
                throw new IllegalArgumentException("失败报告Gold集合与qrels不一致: " + query.queryId());
            }
        }
    }

    private Map<String, Integer> countLosses(List<QueryAnalysis> queries, boolean total) {
        Map<String, Integer> values = new LinkedHashMap<>();
        queries.stream().flatMap(value -> value.variants().values().stream()).map(value -> {
                    FirstObserved first = total ? value.firstObservedTotalLoss() : value.firstObservedCoverageLoss();
                    return first == null ? "NONE" : first.code();
                }).sorted().forEach(value -> values.merge(value, 1, Integer::sum));
        return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    public void write(Report report, Path json, Path markdown) throws IOException {
        if (Files.exists(json) || Files.exists(markdown)) throw new IllegalArgumentException("禁止覆盖既有内部诊断报告");
        if (json.getParent() != null) Files.createDirectories(json.getParent());
        if (markdown.getParent() != null) Files.createDirectories(markdown.getParent());
        mapper.copy().enable(SerializationFeature.INDENT_OUTPUT).writeValue(json.toFile(), report);
        Files.writeString(markdown, markdown(report), StandardCharsets.UTF_8);
    }

    private VariantAnalysis analyzeVariant(RagDiagnosticCaseRunner.DiagnosticRecord record, Set<String> gold,
                                           boolean rankingMatch, Map<String, DocumentEvidence> documents) {
        Map<String, List<RagBenchmarkHttpClient.DiagnosticCandidate>> byStage = new LinkedHashMap<>();
        Set<String> scopes = new LinkedHashSet<>();
        for (RagBenchmarkHttpClient.DiagnosticCandidate candidate : record.diagnostics()) {
            if (candidate.benchmarkDocumentId() == null || !documents.containsKey(candidate.benchmarkDocumentId())) {
                throw new IllegalArgumentException("诊断候选无法回源本地文档: " + record.queryId());
            }
            Set<String> allowedOutcomes = OUTCOMES_BY_STAGE.get(candidate.stage());
            if (allowedOutcomes == null || !allowedOutcomes.contains(candidate.outcome())) {
                throw new IllegalArgumentException("诊断stage/outcome非法: " + record.queryId() + "/"
                        + candidate.stage() + "/" + candidate.outcome());
            }
            scopes.add(candidate.bindingId() + "\u0000" + candidate.profileId());
            byStage.computeIfAbsent(candidate.stage(), ignored -> new ArrayList<>()).add(candidate);
        }
        if (scopes.size() != 1) throw new IllegalArgumentException("诊断记录当前只支持单binding/profile: " + record.queryId());
        validateStageClosure(record, byStage);
        List<String> stages = stages(record.variant());
        List<StageAnalysis> stageAnalyses = new ArrayList<>();
        int previousGoldCount = gold.size();
        FirstObserved firstCoverage = null;
        FirstObserved firstTotal = null;
        Set<String> previousPresent = new LinkedHashSet<>(gold);
        for (String stage : stages) {
            List<RagBenchmarkHttpClient.DiagnosticCandidate> candidates = "raw_union".equals(stage)
                    ? union(byStage.getOrDefault("dense_raw", List.of()), byStage.getOrDefault("sparse_raw", List.of()))
                    : byStage.getOrDefault(stage, List.of());
            List<DocumentRank> ranking = documentRanking(stage, candidates);
            Set<String> present = new LinkedHashSet<>();
            ranking.forEach(value -> { if (gold.contains(value.documentId())) present.add(value.documentId()); });
            Set<String> missing = new LinkedHashSet<>(gold);
            missing.removeAll(present);
            Set<String> lost = new LinkedHashSet<>(previousPresent);
            lost.removeAll(present);
            Set<String> gained = new LinkedHashSet<>(present);
            gained.removeAll(previousPresent);
            if (!gained.isEmpty()) throw new IllegalArgumentException("流水线后阶段重新引入gold: " + record.queryId());
            if (firstCoverage == null && present.size() < previousGoldCount) {
                firstCoverage = new FirstObserved(stage, lossCode(record.variant(), stage, candidates, lost),
                        List.copyOf(lost), "derived_from_stage_trace");
            }
            if (firstTotal == null && previousGoldCount > 0 && present.isEmpty()) {
                firstTotal = new FirstObserved(stage, lossCode(record.variant(), stage, candidates, lost),
                        List.copyOf(lost), "derived_from_stage_trace");
            }
            String semantics = "raw_union".equals(stage) ? "parallel_branch_local_min_for_coverage_only" : "global_stage_rank";
            stageAnalyses.add(new StageAnalysis(stage, semantics, ranking, List.copyOf(present), List.copyOf(missing),
                    gold.isEmpty() ? 0D : present.size() / (double) gold.size(),
                    "raw_union".equals(stage) ? null : bestGoldRank(ranking, gold)));
            previousGoldCount = present.size();
            previousPresent = present;
        }
        RerankEffect rerankEffect = rerankEffect(record.variant(), stageAnalyses, gold);
        List<CompetingDocument> competitors = competitors(firstTotal == null ? firstCoverage : firstTotal,
                stageAnalyses, gold, documents);
        Map<String, List<String>> goldOutcomes = new LinkedHashMap<>();
        for (String goldId : gold) {
            List<String> outcomes = byStage.getOrDefault("context_budget", List.of()).stream()
                    .filter(value -> goldId.equals(value.benchmarkDocumentId())).map(RagBenchmarkHttpClient.DiagnosticCandidate::outcome)
                    .distinct().sorted().toList();
            goldOutcomes.put(goldId, outcomes);
        }
        return new VariantAnalysis(record.variant(), record.retrievalId(), rankingMatch, record.rankedDocumentIds(),
                record.elapsedMs(), record.stageTimingsMs(), List.copyOf(stageAnalyses), firstCoverage, firstTotal,
                rerankEffect, goldOutcomes, competitors);
    }

    private List<DocumentRank> documentRanking(String stage,
                                                List<RagBenchmarkHttpClient.DiagnosticCandidate> candidates) {
        Map<String, RagBenchmarkHttpClient.DiagnosticCandidate> best = new LinkedHashMap<>();
        for (RagBenchmarkHttpClient.DiagnosticCandidate value : candidates) {
            if (!isLive(stage, value.outcome())) continue;
            best.compute(value.benchmarkDocumentId(), (ignored, current) -> current == null || value.rank() < current.rank()
                    ? value : current);
        }
        return best.values().stream().sorted(Comparator.comparingInt(RagBenchmarkHttpClient.DiagnosticCandidate::rank)
                        .thenComparing(RagBenchmarkHttpClient.DiagnosticCandidate::benchmarkDocumentId))
                .map(value -> new DocumentRank(value.rank(), value.benchmarkDocumentId(), value.chunkId(),
                        value.stage(), value.denseScore(), value.sparseScore(), value.fusionScore(),
                        value.rerankScore(), value.outcome()))
                .toList();
    }

    private boolean isLive(String stage, String outcome) {
        if ("context_budget".equals(stage)) return "accepted_citation".equals(outcome);
        return Set.of("returned_by_vector_store", "kept_after_fusion_threshold_topk", "kept",
                "kept_without_rerank", "sent_to_reranker", "kept_after_rerank").contains(outcome);
    }

    private void validateStageClosure(RagDiagnosticCaseRunner.DiagnosticRecord record,
                                      Map<String, List<RagBenchmarkHttpClient.DiagnosticCandidate>> byStage) {
        Set<String> expected = switch (record.variant()) {
            case "dense" -> Set.of("dense_raw", "fusion", "candidate_filter", "pre_assembly", "context_budget");
            case "sparse" -> Set.of("sparse_raw", "fusion", "candidate_filter", "pre_assembly", "context_budget");
            case "hybrid_rrf" -> Set.of("dense_raw", "sparse_raw", "fusion", "candidate_filter", "pre_assembly", "context_budget");
            case "hybrid_rrf_rerank" -> Set.of("dense_raw", "sparse_raw", "fusion", "candidate_filter",
                    "rerank_input", "rerank_output", "context_budget");
            default -> throw new IllegalArgumentException("未知变体: " + record.variant());
        };
        if (!byStage.keySet().equals(expected)) {
            throw new IllegalArgumentException("诊断阶段闭包不完整: " + record.queryId() + "/" + record.variant());
        }
        byStage.forEach((stage, candidates) -> {
            if ("candidate_filter".equals(stage)) {
                validateCandidateFilterRanks(record, byStage.get("fusion"), candidates);
            } else {
                requireContinuousRanks(record, stage, candidates);
            }
        });
        Map<String, Integer> counts = record.candidateCounts();
        requireCount(record, byStage, "dense_raw", counts.getOrDefault("denseCandidateCount", 0));
        requireCount(record, byStage, "sparse_raw", counts.getOrDefault("sparseCandidateCount", 0));
        int fusion = counts.getOrDefault("fusionCandidateCount", -1);
        requireCount(record, byStage, "fusion", fusion);
        requireCount(record, byStage, "candidate_filter", fusion);
        int assembled = liveCount(byStage.getOrDefault("candidate_filter", List.of()), "kept");
        if ("hybrid_rrf_rerank".equals(record.variant())) {
            int rerank = counts.getOrDefault("rerankCandidateCount", -1);
            requireCount(record, byStage, "rerank_input", rerank);
            requireCount(record, byStage, "rerank_output", rerank);
            requireCount(record, byStage, "context_budget", rerank);
        } else {
            requireCount(record, byStage, "pre_assembly", assembled);
            requireCount(record, byStage, "context_budget", assembled);
            if (counts.getOrDefault("rerankCandidateCount", -1) != 0) {
                throw new IllegalArgumentException("非Rerank变体候选数非法: " + record.queryId());
            }
        }
    }

    private void validateCandidateFilterRanks(RagDiagnosticCaseRunner.DiagnosticRecord record,
                                              List<RagBenchmarkHttpClient.DiagnosticCandidate> fusion,
                                              List<RagBenchmarkHttpClient.DiagnosticCandidate> candidates) {
        Map<Integer, RagBenchmarkHttpClient.DiagnosticCandidate> fusionByRank = new LinkedHashMap<>();
        fusion.forEach(candidate -> {
            if (candidate.rank() < 1 || fusionByRank.putIfAbsent(candidate.rank(), candidate) != null) {
                throw new IllegalArgumentException("融合阶段rank非法: " + record.queryId() + "/" + record.variant());
            }
        });
        Set<String> chunks = new LinkedHashSet<>();
        List<RagBenchmarkHttpClient.DiagnosticCandidate> kept = new ArrayList<>();
        for (RagBenchmarkHttpClient.DiagnosticCandidate candidate : candidates) {
            if (!chunks.add(candidate.chunkId())) {
                throw new IllegalArgumentException("候选过滤阶段chunk重复: "
                        + record.queryId() + "/" + record.variant());
            }
            if ("kept".equals(candidate.outcome())) {
                kept.add(candidate);
                continue;
            }
            RagBenchmarkHttpClient.DiagnosticCandidate upstream = fusionByRank.get(candidate.rank());
            if (upstream == null || !upstream.chunkId().equals(candidate.chunkId())) {
                throw new IllegalArgumentException("候选过滤淘汰项未保留融合阶段rank: "
                        + record.queryId() + "/" + record.variant());
            }
        }
        if (!chunks.equals(fusion.stream().map(RagBenchmarkHttpClient.DiagnosticCandidate::chunkId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new)))) {
            throw new IllegalArgumentException("候选过滤阶段与融合阶段chunk集合不闭合: "
                    + record.queryId() + "/" + record.variant());
        }
        requireContinuousRanks(record, "candidate_filter/kept", kept);
    }

    private void requireContinuousRanks(RagDiagnosticCaseRunner.DiagnosticRecord record, String stage,
                                        List<RagBenchmarkHttpClient.DiagnosticCandidate> candidates) {
        Set<Integer> ranks = new LinkedHashSet<>();
        candidates.forEach(candidate -> {
            if (candidate.rank() < 1 || !ranks.add(candidate.rank())) {
                throw new IllegalArgumentException("诊断阶段rank不连续或重复: "
                        + record.queryId() + "/" + record.variant() + "/" + stage);
            }
        });
        for (int rank = 1; rank <= candidates.size(); rank++) {
            if (!ranks.contains(rank)) throw new IllegalArgumentException("诊断阶段rank不连续或重复: "
                    + record.queryId() + "/" + record.variant() + "/" + stage);
        }
    }

    private void requireCount(RagDiagnosticCaseRunner.DiagnosticRecord record,
                              Map<String, List<RagBenchmarkHttpClient.DiagnosticCandidate>> stages,
                              String stage, int expected) {
        int actual = stages.getOrDefault(stage, List.of()).size();
        if (actual != expected) throw new IllegalArgumentException("诊断阶段计数不一致: " + record.queryId()
                + "/" + record.variant() + "/" + stage + " expected=" + expected + " actual=" + actual);
    }

    private int liveCount(List<RagBenchmarkHttpClient.DiagnosticCandidate> values, String outcome) {
        return (int) values.stream().filter(value -> outcome.equals(value.outcome())).count();
    }

    private String lossCode(String variant, String stage,
                            List<RagBenchmarkHttpClient.DiagnosticCandidate> candidates, Set<String> lost) {
        if ("raw_union".equals(stage)) return "RAW_RECALL_TOTAL_MISS";
        if ("dense_raw".equals(stage)) return "DENSE_RAW_TOPK_MISS";
        if ("sparse_raw".equals(stage)) return "SPARSE_RAW_TOPK_MISS";
        if ("fusion".equals(stage)) return "FUSION_THRESHOLD_OR_TOPK_LOSS";
        if ("candidate_filter".equals(stage)) {
            Set<String> outcomes = outcomes(candidates, lost);
            if (outcomes.equals(Set.of("discarded_duplicate_content_hash"))) return "CONTENT_HASH_DEDUP_LOSS";
            if (outcomes.equals(Set.of("discarded_tombstone"))) return "TOMBSTONE_FILTER_LOSS";
            return "MIXED_CANDIDATE_FILTER_LOSS";
        }
        if ("rerank_input".equals(stage)) return "RERANK_INPUT_TOPK_LOSS";
        if ("rerank_output".equals(stage)) return "RERANK_OUTPUT_TOPK_LOSS";
        if ("pre_assembly".equals(stage)) return "FINAL_TOPK_PREASSEMBLY_LOSS";
        if ("context_budget".equals(stage)) {
            Set<String> outcomes = outcomes(candidates, lost);
            if (outcomes.equals(Set.of("discarded_global_token_budget"))) return "GLOBAL_TOKEN_BUDGET_LOSS";
            if (outcomes.equals(Set.of("discarded_binding_token_budget"))) return "BINDING_TOKEN_BUDGET_LOSS";
            if (outcomes.equals(Set.of("discarded_empty_context"))) return "EMPTY_CONTEXT_LOSS";
            if (outcomes.equals(Set.of("discarded_document_tombstone"))) return "DOCUMENT_TOMBSTONE_LOSS";
            if (outcomes.equals(Set.of("discarded_final_topk"))) return "FINAL_TOPK_CONTEXT_LOSS";
            return "MIXED_CONTEXT_LOSS";
        }
        return variant.toUpperCase(Locale.ROOT) + "_UNCLASSIFIED_LOSS";
    }

    private Set<String> outcomes(List<RagBenchmarkHttpClient.DiagnosticCandidate> candidates, Set<String> documents) {
        Set<String> values = new LinkedHashSet<>();
        candidates.stream().filter(value -> documents.contains(value.benchmarkDocumentId()))
                .map(RagBenchmarkHttpClient.DiagnosticCandidate::outcome).sorted().forEach(values::add);
        return values;
    }

    private RerankEffect rerankEffect(String variant, List<StageAnalysis> stages, Set<String> gold) {
        if (!"hybrid_rrf_rerank".equals(variant)) return null;
        StageAnalysis input = stage(stages, "rerank_input");
        StageAnalysis output = stage(stages, "rerank_output");
        double before = reciprocalRank(input.ranking(), gold);
        double after = reciprocalRank(output.ranking(), gold);
        double recallBefore = input.goldRecallFraction();
        double recallAfter = output.goldRecallFraction();
        List<GoldRankDelta> deltas = gold.stream().sorted().map(id -> {
            Integer rankBefore = rank(input.ranking(), id);
            Integer rankAfter = rank(output.ranking(), id);
            if (rankBefore == null && rankAfter != null) {
                throw new IllegalArgumentException("Rerank输出重新引入输入中不存在的Gold: " + id);
            }
            String direction = rankBefore == null ? "NEUTRAL"
                    : rankAfter == null || rankAfter > rankBefore ? "HARM"
                    : rankAfter < rankBefore ? "GAIN" : "NEUTRAL";
            return new GoldRankDelta(id, rankBefore, rankAfter, direction);
        }).toList();
        boolean gain = deltas.stream().anyMatch(value -> "GAIN".equals(value.direction()));
        boolean harm = deltas.stream().anyMatch(value -> "HARM".equals(value.direction()));
        String classification = gain && harm ? "RERANK_MIXED"
                : gain ? "RERANK_ORDER_GAIN" : harm ? "RERANK_ORDER_HARM" : "RERANK_NEUTRAL";
        return new RerankEffect("same_retrieval_input_output_per_gold", classification,
                recallBefore, recallAfter, recallAfter - recallBefore, before, after, after - before, deltas);
    }

    private List<CompetingDocument> competitors(FirstObserved failure, List<StageAnalysis> stages, Set<String> gold,
                                                 Map<String, DocumentEvidence> documents) {
        if (failure == null) return List.of();
        StageAnalysis stage = stage(stages, failure.stage());
        return stage.ranking().stream().filter(value -> !gold.contains(value.documentId())).limit(3).map(value -> {
            DocumentEvidence document = documents.get(value.documentId());
            return new CompetingDocument(value.sourceStage(), value.rank(), value.documentId(), document.title(), document.excerpt(),
                    value.denseScore(), value.sparseScore(), value.fusionScore(), value.rerankScore());
        }).toList();
    }

    private List<String> observedDrift(Map<String, RagDiagnosticCaseRunner.DiagnosticRecord> values) {
        List<String> result = new ArrayList<>();
        if (!sameFingerprint(fingerprint(values.get("dense"), "dense_raw"), fingerprint(values.get("hybrid_rrf"), "dense_raw"))
                || !sameFingerprint(fingerprint(values.get("dense"), "dense_raw"), fingerprint(values.get("hybrid_rrf_rerank"), "dense_raw"))) {
            result.add("DENSE_RAW_CROSS_VARIANT_DRIFT");
        }
        if (!sameFingerprint(fingerprint(values.get("sparse"), "sparse_raw"), fingerprint(values.get("hybrid_rrf"), "sparse_raw"))
                || !sameFingerprint(fingerprint(values.get("sparse"), "sparse_raw"), fingerprint(values.get("hybrid_rrf_rerank"), "sparse_raw"))) {
            result.add("SPARSE_RAW_CROSS_VARIANT_DRIFT");
        }
        if (!sameFingerprint(fingerprint(values.get("hybrid_rrf"), "fusion"), fingerprint(values.get("hybrid_rrf_rerank"), "fusion"))) {
            result.add("FUSION_CROSS_VARIANT_DRIFT");
        }
        return result;
    }

    private List<CandidateFingerprint> fingerprint(RagDiagnosticCaseRunner.DiagnosticRecord record, String stage) {
        return record.diagnostics().stream().filter(value -> stage.equals(value.stage()))
                .sorted(Comparator.comparingInt(RagBenchmarkHttpClient.DiagnosticCandidate::rank)
                        .thenComparing(RagBenchmarkHttpClient.DiagnosticCandidate::chunkId))
                .map(value -> new CandidateFingerprint(value.bindingId(), value.profileId(), value.rank(),
                        value.knowledgeBaseId(), value.documentId(), value.versionId(), value.generation(),
                        value.chunkId(), value.outcome(), value.denseScore(), value.sparseScore(),
                        value.fusionScore(), value.rerankScore())).toList();
    }

    private boolean sameFingerprint(List<CandidateFingerprint> left, List<CandidateFingerprint> right) {
        if (left.size() != right.size()) return false;
        for (int index = 0; index < left.size(); index++) {
            CandidateFingerprint a = left.get(index);
            CandidateFingerprint b = right.get(index);
            if (!a.sameIdentity(b) || !sameScore(a.denseScore(), b.denseScore())
                    || !sameScore(a.sparseScore(), b.sparseScore())
                    || !sameScore(a.fusionScore(), b.fusionScore())
                    || !sameScore(a.rerankScore(), b.rerankScore())) return false;
        }
        return true;
    }

    private boolean sameScore(Double left, Double right) {
        if (left == null || right == null) return left == right;
        return Math.abs(left - right) <= SCORE_TOLERANCE;
    }

    private Map<String, SelectedQuery> readSelectedQueries(JsonNode cases) {
        if (!cases.isObject()) throw new IllegalArgumentException("失败报告缺少cases");
        Map<String, MutableSelectedQuery> values = new LinkedHashMap<>();
        cases.fields().forEachRemaining(category -> category.getValue().forEach(node -> {
            String id = node.path("queryId").asText();
            String question = node.path("question").asText();
            MutableSelectedQuery value = values.computeIfAbsent(id, ignored -> new MutableSelectedQuery(id, question));
            if (!value.question.equals(question)) throw new IllegalArgumentException("失败报告问题文本冲突");
            value.categories.add(category.getKey());
            node.path("goldDocuments").forEach(gold -> value.gold.putIfAbsent(gold.path("documentId").asText(),
                    new GoldDocument(gold.path("documentId").asText(), gold.path("title").asText(),
                            gold.path("excerpt").asText(), gold.path("headingMarker").asText())));
            node.path("variants").fields().forEachRemaining(variant -> {
                List<String> ranking = new ArrayList<>();
                variant.getValue().path("ranking").forEach(item -> ranking.add(item.path("documentId").asText()));
                List<String> previous = value.rankings.putIfAbsent(variant.getKey(), List.copyOf(ranking));
                if (previous != null && !previous.equals(ranking)) throw new IllegalArgumentException("失败报告重复query排名冲突");
            });
        }));
        Map<String, SelectedQuery> result = new LinkedHashMap<>();
        values.values().forEach(value -> result.put(value.queryId, new SelectedQuery(value.queryId, value.question,
                List.copyOf(value.categories), List.copyOf(value.gold.values()),
                java.util.Collections.unmodifiableSet(new LinkedHashSet<>(value.gold.keySet())),
                java.util.Collections.unmodifiableMap(new LinkedHashMap<>(value.rankings)))));
        return result;
    }

    private Map<String, Map<String, RagDiagnosticCaseRunner.DiagnosticRecord>> readDiagnostics(Path path)
            throws IOException {
        Map<String, Map<String, RagDiagnosticCaseRunner.DiagnosticRecord>> result = new LinkedHashMap<>();
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                RagDiagnosticCaseRunner.DiagnosticRecord record = mapper.readValue(line,
                        RagDiagnosticCaseRunner.DiagnosticRecord.class);
                if (!RagFailureCaseReporter.VARIANTS.contains(record.variant())) throw new IllegalArgumentException("未知诊断变体");
                if (result.computeIfAbsent(record.queryId(), ignored -> new LinkedHashMap<>())
                        .putIfAbsent(record.variant(), record) != null) throw new IllegalArgumentException("重复诊断记录");
            }
        }
        return result;
    }

    private Map<String, DocumentEvidence> readDocuments(Path markdown, Path mapPath) throws IOException {
        Map<String, String> markerToId = new LinkedHashMap<>();
        Set<String> documentIds = new LinkedHashSet<>();
        for (String line : Files.readAllLines(mapPath, StandardCharsets.UTF_8)) {
            if (line.isBlank()) continue;
            JsonNode node = mapper.readTree(line);
            String headingMarker = node.path("headingMarker").asText();
            String documentId = node.path("documentId").asText();
            if (headingMarker.isBlank() || documentId.isBlank()
                    || markerToId.putIfAbsent(headingMarker, documentId) != null
                    || !documentIds.add(documentId)) {
                throw new IllegalArgumentException("document-map含空值或重复marker/documentId");
            }
        }
        Map<String, DocumentEvidence> result = new LinkedHashMap<>();
        String marker = null;
        String title = null;
        StringBuilder body = new StringBuilder();
        for (String line : Files.readAllLines(markdown, StandardCharsets.UTF_8)) {
            Matcher matcher = HEADING.matcher(line);
            if (matcher.matches()) {
                flush(result, markerToId, marker, title, body.toString());
                marker = matcher.group(1); title = matcher.group(2).strip(); body.setLength(0);
            } else if (marker != null) { if (!body.isEmpty()) body.append('\n'); body.append(line); }
        }
        flush(result, markerToId, marker, title, body.toString());
        if (result.size() != markerToId.size()) throw new IllegalArgumentException("本地文档与映射数量不一致");
        return result;
    }

    private void flush(Map<String, DocumentEvidence> output, Map<String, String> markerToId,
                       String marker, String title, String content) {
        if (marker == null) return;
        String id = markerToId.get(marker);
        if (id == null || content.isBlank()) throw new IllegalArgumentException("文档映射或正文非法");
        String value = content.strip();
        String excerpt = value.length() <= EXCERPT_LIMIT ? value : value.substring(0, EXCERPT_LIMIT).stripTrailing() + "…";
        if (title == null || title.isBlank()
                || output.putIfAbsent(id, new DocumentEvidence(id, title, excerpt, marker)) != null) {
            throw new IllegalArgumentException("本地文档标题为空或documentId重复");
        }
    }

    private List<RagBenchmarkHttpClient.DiagnosticCandidate> union(
            List<RagBenchmarkHttpClient.DiagnosticCandidate> left,
            List<RagBenchmarkHttpClient.DiagnosticCandidate> right) {
        List<RagBenchmarkHttpClient.DiagnosticCandidate> values = new ArrayList<>(left);
        values.addAll(right);
        return values;
    }

    private List<String> stages(String variant) {
        return switch (variant) {
            case "dense" -> List.of("dense_raw", "fusion", "candidate_filter", "pre_assembly", "context_budget");
            case "sparse" -> List.of("sparse_raw", "fusion", "candidate_filter", "pre_assembly", "context_budget");
            case "hybrid_rrf" -> List.of("raw_union", "fusion", "candidate_filter", "pre_assembly", "context_budget");
            case "hybrid_rrf_rerank" -> List.of("raw_union", "fusion", "candidate_filter", "rerank_input", "rerank_output", "context_budget");
            default -> throw new IllegalArgumentException("未知变体: " + variant);
        };
    }

    private StageAnalysis stage(List<StageAnalysis> values, String name) {
        return values.stream().filter(value -> name.equals(value.stage())).findFirst().orElseThrow();
    }
    private Integer rank(List<DocumentRank> values, String documentId) {
        return values.stream().filter(value -> documentId.equals(value.documentId())).map(DocumentRank::rank)
                .findFirst().orElse(null);
    }
    private Integer bestGoldRank(List<DocumentRank> values, Set<String> gold) {
        return values.stream().filter(value -> gold.contains(value.documentId())).map(DocumentRank::rank).min(Integer::compare).orElse(null);
    }
    private double reciprocalRank(List<DocumentRank> values, Set<String> gold) {
        Integer value = bestGoldRank(values, gold); return value == null ? 0D : 1D / value;
    }

    private String markdown(Report report) {
        StringBuilder out = new StringBuilder("# SciFact RAG内部阶段失败证据报告\n\n");
        out.append("真实诊断查询：").append(report.manifest().queryCount()).append("；请求记录：")
                .append(report.manifest().recordCount()).append("；旧run最终排名精确复现：")
                .append(report.manifest().exactFinalRankingMatches()).append("/")
                .append(report.manifest().expectedFinalRankingComparisons()).append("。\n\n");
        out.append("## 证据边界\n\n");
        report.manifest().limitations().forEach(value -> out.append("- ").append(value).append("\n"));
        out.append("\n## 内部失效总账\n\n").append(report.manifest().recordCount())
                .append("条变体轨迹的首个完全损失分布：\n\n")
                .append("| 分类码 | 变体轨迹数 |\n|---|---:|\n");
        report.manifest().firstObservedTotalLossCounts().forEach((key, value) -> out.append("| ")
                .append(key).append(" | ").append(value).append(" |\n"));
        out.append("\n同一次Hybrid+Rerank请求内，Rerank输入→输出的排序效果：\n\n")
                .append("| 分类 | 查询数 |\n|---|---:|\n");
        report.manifest().rerankEffectCounts().forEach((key, value) -> out.append("| ")
                .append(key).append(" | ").append(value).append(" |\n"));
        for (QueryAnalysis query : report.queries()) {
            out.append("\n## queryId=").append(query.queryId()).append("\n\n问题：").append(query.question())
                    .append("\n\n原分类：`").append(String.join("`, `", query.categories())).append("`\n\nGold文档：\n");
            for (GoldDocument gold : query.goldDocuments()) out.append("\n- `").append(gold.documentId()).append("` ")
                    .append(gold.title()).append("\n\n  > ").append(gold.excerpt().replace("\n", " ")).append("\n");
            out.append("\n| 变体 | 首个覆盖损失 | 首个完全损失 | 最终排名复现 | Rerank同请求效果 |\n|---|---|---|---:|---|\n");
            query.variants().forEach((name, value) -> out.append("| ").append(name).append(" | ")
                    .append(first(value.firstObservedCoverageLoss())).append(" | ")
                    .append(first(value.firstObservedTotalLoss())).append(" | ")
                    .append(value.finalRankingMatchesBaseline() ? "是" : "否").append(" | ")
                    .append(value.rerankEffect() == null ? "不适用" : value.rerankEffect().classification()
                            + " (MRR Δ=" + format(value.rerankEffect().mrrDelta()) + ")")
                    .append(" |\n"));
            String focus = focusVariant(query);
            VariantAnalysis evidence = query.variants().get(focus);
            out.append("\n重点失败变体：`").append(focus).append("`。首个内部失效结论：")
                    .append(first(evidence.firstObservedTotalLoss() == null ? evidence.firstObservedCoverageLoss()
                            : evidence.firstObservedTotalLoss())).append("。该结论级别为阶段轨迹派生，不外推为模型根因。\n");
            out.append("\n重点变体Gold逐阶段路径：\n\n| 阶段 | Gold仍存在 | Gold已缺失 | Gold最佳名次 |\n|---|---|---|---:|\n");
            evidence.stages().forEach(stage -> out.append("| ").append(stage.stage()).append(" | ")
                    .append(String.join(", ", stage.goldPresentIds())).append(" | ")
                    .append(String.join(", ", stage.goldMissingIds())).append(" | ")
                    .append(stage.bestGoldRank() == null ? "-" : stage.bestGoldRank()).append(" |\n"));
            for (Map.Entry<String, VariantAnalysis> entry : query.variants().entrySet()) {
                VariantAnalysis failed = entry.getValue();
                FirstObserved first = failed.firstObservedTotalLoss() == null
                        ? failed.firstObservedCoverageLoss() : failed.firstObservedTotalLoss();
                if (first == null || failed.competingDocuments().isEmpty()) continue;
                out.append("\n`").append(entry.getKey()).append("`在`").append(first.stage())
                        .append("/" ).append(first.code()).append("`阶段排名靠前的非Gold文档：\n");
                for (CompetingDocument value : failed.competingDocuments()) out.append("\n- sourceStage=")
                        .append(value.sourceStage()).append(" rank=").append(value.rank()).append(" `")
                        .append(value.documentId()).append("` ").append(value.title())
                        .append("；dense=").append(value.denseScore()).append("，sparse=").append(value.sparseScore())
                        .append("，fusion=").append(value.fusionScore()).append("，rerank=").append(value.rerankScore())
                        .append("\n\n  > ").append(value.excerpt().replace("\n", " ")).append("\n");
            }
            if (!query.crossVariantComparableByObservedFingerprint()) out.append("\n警告：跨变体候选指纹发生漂移：")
                    .append(String.join(", ", query.driftReasons())).append("；不得把跨变体差异归因于单一组件。\n");
        }
        out.append("\n## 输入SHA-256\n\n");
        report.manifest().inputSha256().forEach((key, value) -> out.append("- ").append(key).append(": `").append(value).append("`\n"));
        return out.toString();
    }

    private String focusVariant(QueryAnalysis query) {
        if (query.categories().stream().anyMatch(value -> value.startsWith("rerank_"))) {
            return "hybrid_rrf_rerank";
        }
        for (String variant : RagFailureCaseReporter.VARIANTS) {
            VariantAnalysis value = query.variants().get(variant);
            if (value.firstObservedTotalLoss() != null || value.firstObservedCoverageLoss() != null) return variant;
        }
        return "hybrid_rrf_rerank";
    }
    private String first(FirstObserved value) { return value == null ? "未观察到Gold损失" : value.stage() + "/" + value.code(); }
    private String format(double value) { return String.format(Locale.ROOT, "%.6f", value); }
    private String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(path)) {
                byte[] buffer = new byte[8192]; int read;
                while ((read = input.read(buffer)) >= 0) if (read > 0) digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }

    public record Configuration(Path failureReport, Path diagnostics, Path diagnosticManifest, Path qrels,
                                Path documents, Path documentMap) {
        void validate() {
            for (Path path : List.of(failureReport, diagnostics, diagnosticManifest, qrels, documents, documentMap))
                if (path == null || !Files.isRegularFile(path) || !Files.isReadable(path))
                    throw new IllegalArgumentException("内部诊断输入不存在或不可读: " + path);
        }
    }
    public record Report(Manifest manifest, List<QueryAnalysis> queries) {}
    public record Manifest(int schemaVersion, String generator, int queryCount, int recordCount,
                           int exactFinalRankingMatches, int expectedFinalRankingComparisons,
                           boolean integrityHealthy, List<String> integrityViolations,
                           Map<String, String> inputSha256, Map<String, Integer> firstObservedCoverageLossCounts,
                           Map<String, Integer> firstObservedTotalLossCounts,
                           Map<String, Integer> rerankEffectCounts, List<String> limitations) {}
    public record QueryAnalysis(String queryId, String question, List<String> categories,
                                List<GoldDocument> goldDocuments, Map<String, VariantAnalysis> variants,
                                boolean crossVariantComparableByObservedFingerprint, List<String> driftReasons) {}
    public record VariantAnalysis(String variant, String retrievalId, boolean finalRankingMatchesBaseline,
                                  List<String> finalRankedDocumentIds, long elapsedMs,
                                  Map<String, Long> stageTimingsMs, List<StageAnalysis> stages,
                                  FirstObserved firstObservedCoverageLoss, FirstObserved firstObservedTotalLoss,
                                  RerankEffect rerankEffect, Map<String, List<String>> goldContextOutcomes,
                                  List<CompetingDocument> competingDocuments) {}
    public record StageAnalysis(String stage, String rankingSemantics, List<DocumentRank> ranking, List<String> goldPresentIds,
                                List<String> goldMissingIds, double goldRecallFraction, Integer bestGoldRank) {}
    public record DocumentRank(int rank, String documentId, String chunkId, String sourceStage, Double denseScore,
                               Double sparseScore, Double fusionScore, Double rerankScore, String outcome) {}
    public record FirstObserved(String stage, String code, List<String> lostGoldDocumentIds, String evidenceLevel) {}
    public record RerankEffect(String basis, String classification, double recallBefore, double recallAfter,
                               double recallDelta, double mrrBefore, double mrrAfter,
                               double mrrDelta, List<GoldRankDelta> perGold) {}
    public record GoldRankDelta(String documentId, Integer rankBefore, Integer rankAfter, String direction) {}
    public record CompetingDocument(String sourceStage, int rank, String documentId, String title, String excerpt,
                                    Double denseScore, Double sparseScore, Double fusionScore, Double rerankScore) {}
    public record GoldDocument(String documentId, String title, String excerpt, String headingMarker) {}
    private record DocumentEvidence(String documentId, String title, String excerpt, String headingMarker) {}
    private record SelectedQuery(String queryId, String question, List<String> categories,
                                 List<GoldDocument> goldDocuments, Set<String> goldDocumentIds,
                                 Map<String, List<String>> oldRankings) {}
    private record CandidateFingerprint(String bindingId, String profileId, int rank, String knowledgeBaseId,
                                        String documentId, String versionId, long generation, String chunkId,
                                        String outcome, Double denseScore, Double sparseScore,
                                        Double fusionScore, Double rerankScore) {
        private boolean sameIdentity(CandidateFingerprint other) {
            return rank == other.rank && generation == other.generation
                    && java.util.Objects.equals(knowledgeBaseId, other.knowledgeBaseId)
                    && java.util.Objects.equals(documentId, other.documentId)
                    && java.util.Objects.equals(versionId, other.versionId)
                    && java.util.Objects.equals(chunkId, other.chunkId)
                    && java.util.Objects.equals(outcome, other.outcome);
        }
    }
    private static final class MutableSelectedQuery {
        private final String queryId; private final String question;
        private final Set<String> categories = new LinkedHashSet<>();
        private final Map<String, GoldDocument> gold = new LinkedHashMap<>();
        private final Map<String, List<String>> rankings = new LinkedHashMap<>();
        private MutableSelectedQuery(String queryId, String question) { this.queryId = queryId; this.question = question; }
    }
}
