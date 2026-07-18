package cn.bugstack.ai.benchmark.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 逐查询 run.jsonl 的严格读写与分组评分。 */
public final class RagBenchmarkRunIO {

    private final ObjectMapper objectMapper;

    public RagBenchmarkRunIO(ObjectMapper objectMapper) { this.objectMapper = objectMapper; }

    public Map<String, Map<String, List<String>>> read(Path path) throws IOException {
        if (path == null || !Files.isRegularFile(path)) throw new IllegalArgumentException("run文件不存在");
        Map<String, Map<String, List<String>>> variants = new LinkedHashMap<>();
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isBlank()) continue;
                if (line.length() > 2_000_000) throw new IllegalArgumentException("run单行超过上限");
                RunRecord record = objectMapper.readValue(line, RunRecord.class);
                validate(record, lineNumber);
                Map<String, List<String>> runs = variants.computeIfAbsent(record.variant(), ignored -> new LinkedHashMap<>());
                if (runs.putIfAbsent(record.queryId(), record.rankedDocumentIds()) != null) {
                    throw new IllegalArgumentException("同一variant/query出现重复run记录");
                }
            }
        }
        if (variants.isEmpty()) throw new IllegalArgumentException("run文件为空");
        return variants;
    }

    public void writeReport(Path path, Map<String, RagRetrievalScorer.AggregateMetrics> metrics,
                            Map<String, Object> manifest) throws IOException {
        if (Files.exists(path)) throw new IllegalArgumentException("禁止覆盖既有评分报告");
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("manifest", manifest == null ? Map.of() : Map.copyOf(manifest));
        report.put("variants", metrics);
        objectMapper.copy().enable(SerializationFeature.INDENT_OUTPUT).writeValue(path.toFile(), report);
    }

    public void append(Path path, RunRecord record) throws IOException {
        validate(record, -1);
        try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE)) {
            writer.write(objectMapper.writeValueAsString(record));
            writer.newLine();
        }
    }

    private void validate(RunRecord record, int lineNumber) {
        String suffix = lineNumber > 0 ? "，行号" + lineNumber : "";
        if (record == null || blank(record.variant()) || blank(record.queryId())
                || record.rankedDocumentIds() == null || record.elapsedMs() < 0) {
            throw new IllegalArgumentException("run记录非法" + suffix);
        }
    }

    private boolean blank(String value) { return value == null || value.isBlank(); }

    public record RunRecord(String variant, String queryId, List<String> rankedDocumentIds,
                            long elapsedMs, boolean degraded, String errorCode,
                            Map<String, Long> stageTimingsMs) {
        public RunRecord {
            rankedDocumentIds = rankedDocumentIds == null ? List.of() : List.copyOf(rankedDocumentIds);
            stageTimingsMs = stageTimingsMs == null ? Map.of() : Map.copyOf(stageTimingsMs);
        }
    }
}
