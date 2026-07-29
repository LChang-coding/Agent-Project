package cn.bugstack.ai.domain.rag.service;

import cn.bugstack.ai.domain.rag.adapter.port.RagDocumentParserPort;
import cn.bugstack.ai.domain.rag.model.document.DocumentIr;
import cn.bugstack.ai.domain.rag.model.valobj.RagPreprocessingStrategy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** 在格式解析完成后执行可审计的预处理消融，不触碰下载、解析和索引副作用。 */
public final class DocumentPreprocessingStrategyExecutor {

    private static final Pattern MARKDOWN_DECORATION = Pattern.compile(
            "(?m)^(?:#{1,6}\\s+|[-*+]\\s+|>\\s+)|`{1,3}|\\*{1,2}|_{1,2}|\\[(.+?)]\\(.+?\\)");
    private final DocumentIrCleaner cleaner;

    public DocumentPreprocessingStrategyExecutor(DocumentIrCleaner cleaner) {
        if (cleaner == null) throw new IllegalArgumentException("Cleaner不能为空");
        this.cleaner = cleaner;
    }

    public Result execute(RagDocumentParserPort.ParsedDocument parsed, RagPreprocessingStrategy strategy) {
        if (parsed == null || strategy == null) throw new IllegalArgumentException("解析结果和策略不能为空");
        DocumentIr source = parsed.documentIr();
        List<DocumentIrCleaner.CleaningAudit> audits = List.of();
        DocumentIr candidate = source;
        if (strategy.cleanerEnabled()) {
            DocumentIrCleaner.CleaningResult cleaned = cleaner.cleanWithAudit(source);
            candidate = cleaned.document();
            audits = cleaned.audits();
        }
        if (!strategy.structurePreserved()) {
            String text = switch (strategy) {
                case LEGACY_MARKDOWN_FLATTEN -> parsed.normalizedMarkdown();
                case RAW_TEXT_CHUNK -> plainText(parsed.normalizedMarkdown());
                case IR_NO_STRUCTURED_CHUNKING -> retrievableText(candidate);
                default -> throw new IllegalStateException("不支持的扁平化策略: " + strategy);
            };
            candidate = flatten(candidate, text, strategy);
        } else {
            candidate = stamp(candidate, strategy);
        }
        return new Result(strategy, candidate, audits);
    }

    private DocumentIr flatten(DocumentIr source, String text, RagPreprocessingStrategy strategy) {
        String normalized = text == null ? "" : text.strip();
        if (normalized.isBlank()) throw new IllegalArgumentException("扁平化预处理结果不能为空");
        DocumentIr.Block block = new DocumentIr.Block("benchmark-flat-0", DocumentIr.BlockType.PARAGRAPH,
                normalized, normalized, new DocumentIr.SourceSpan(0, normalized.length(),
                strategy.revision()), null, null, 0, "", 0, List.of(), source.detectedLanguage(),
                1.0, Set.of(), false, true, "", List.of());
        Set<DocumentIr.Flag> flags = new LinkedHashSet<>(source.flags());
        List<String> warnings = new ArrayList<>(source.warnings());
        warnings.add("BENCHMARK_PREPROCESSING_STRATEGY=" + strategy.name());
        Map<String, String> metadata = metadata(source, strategy);
        return new DocumentIr(source.schemaVersion(), source.documentId(), source.sourceName(),
                source.mediaType(), source.detectedLanguage(), source.parserName(), source.parserRevision(),
                List.of(new DocumentIr.Page(1, 0, 0, List.of(block))), metadata, warnings, flags);
    }

    private DocumentIr stamp(DocumentIr source, RagPreprocessingStrategy strategy) {
        List<String> warnings = new ArrayList<>(source.warnings());
        warnings.add("BENCHMARK_PREPROCESSING_STRATEGY=" + strategy.name());
        return new DocumentIr(source.schemaVersion(), source.documentId(), source.sourceName(),
                source.mediaType(), source.detectedLanguage(), source.parserName(), source.parserRevision(),
                source.pages(), metadata(source, strategy), warnings, source.flags());
    }

    private Map<String, String> metadata(DocumentIr source, RagPreprocessingStrategy strategy) {
        Map<String, String> metadata = new LinkedHashMap<>(source.metadata());
        metadata.put("preprocessing_strategy", strategy.name());
        metadata.put("preprocessing_strategy_revision", strategy.revision());
        metadata.put("preprocessing_cleaner_enabled", Boolean.toString(strategy.cleanerEnabled()));
        metadata.put("preprocessing_structure_preserved", Boolean.toString(strategy.structurePreserved()));
        return metadata;
    }

    private String retrievableText(DocumentIr document) {
        return document.blocks().stream().filter(DocumentIr.Block::retrievable)
                .filter(block -> block.type() != DocumentIr.BlockType.HEADER
                        && block.type() != DocumentIr.BlockType.FOOTER
                        && block.type() != DocumentIr.BlockType.PAGE_NUMBER
                        && block.type() != DocumentIr.BlockType.WATERMARK)
                .map(DocumentIr.Block::normalizedText).filter(value -> !value.isBlank())
                .collect(java.util.stream.Collectors.joining("\n\n"));
    }

    private String plainText(String markdown) {
        String value = markdown == null ? "" : MARKDOWN_DECORATION.matcher(markdown).replaceAll("$1");
        return value.replaceAll("(?m)^\\s*\\|?\\s*:?-{3,}:?\\s*(?:\\|\\s*:?-{3,}:?\\s*)+\\|?\\s*$", "")
                .replace('|', ' ').replaceAll("[ \\t]+", " ").replaceAll("\\n{3,}", "\n\n").strip();
    }

    public record Result(RagPreprocessingStrategy strategy, DocumentIr document,
                         List<DocumentIrCleaner.CleaningAudit> cleaningAudits) {
        public Result {
            if (strategy == null || document == null) throw new IllegalArgumentException("策略结果不能为空");
            cleaningAudits = List.copyOf(cleaningAudits == null ? List.of() : cleaningAudits);
        }
    }
}
