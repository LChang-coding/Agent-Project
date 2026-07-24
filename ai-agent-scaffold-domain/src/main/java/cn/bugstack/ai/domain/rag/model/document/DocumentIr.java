package cn.bugstack.ai.domain.rag.model.document;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 文档解析后的规范中间表示。
 * <p>同时保留原文、规范化文本、来源坐标和清洗轨迹，抑制内容时不会物理删除块。</p>
 */
public record DocumentIr(String schemaVersion,
                         String documentId,
                         String sourceName,
                         String mediaType,
                         String detectedLanguage,
                         String parserName,
                         String parserRevision,
                         List<Page> pages,
                         Map<String, String> metadata,
                         List<String> warnings,
                         Set<Flag> flags) {

    public DocumentIr {
        schemaVersion = requireText(schemaVersion, "schemaVersion");
        documentId = requireText(documentId, "documentId");
        sourceName = sourceName == null ? "" : sourceName;
        mediaType = mediaType == null ? "application/octet-stream" : mediaType;
        detectedLanguage = detectedLanguage == null ? "und" : detectedLanguage;
        parserName = parserName == null ? "unknown" : parserName;
        parserRevision = parserRevision == null ? "unknown" : parserRevision;
        pages = List.copyOf(Objects.requireNonNullElse(pages, List.of()));
        metadata = Map.copyOf(Objects.requireNonNullElse(metadata, Map.of()));
        warnings = List.copyOf(Objects.requireNonNullElse(warnings, List.of()));
        flags = Set.copyOf(Objects.requireNonNullElse(flags, Set.of()));
    }

    /**
     * 兼容只提供基础来源字段的IR构造。
     */
    public DocumentIr(String documentId,
                      String sourceName,
                      String mediaType,
                      String detectedLanguage,
                      List<Page> pages,
                      Set<Flag> flags) {
        this("1.0", documentId, sourceName, mediaType, detectedLanguage, "unknown", "unknown",
                pages, Map.of(), List.of(), flags);
    }

    /**
     * 按页序返回全部块。
     *
     * @return 不可变块列表
     */
    public List<Block> blocks() {
        return pages.stream().flatMap(page -> page.blocks().stream()).toList();
    }

    /**
     * 替换页面并保留文档元数据。
     *
     * @param updatedPages 新页面
     * @param updatedFlags 新文档标记
     * @return 新文档IR
     */
    public DocumentIr withPages(List<Page> updatedPages, Set<Flag> updatedFlags) {
        return new DocumentIr(schemaVersion, documentId, sourceName, mediaType, detectedLanguage,
                parserName, parserRevision, updatedPages, metadata, warnings, updatedFlags);
    }

    /**
     * 文档页。
     */
    public record Page(int pageNumber, double width, double height, List<Block> blocks) {
        public Page {
            if (pageNumber < 1 || width < 0 || height < 0) {
                throw new IllegalArgumentException("页码和页面尺寸不合法");
            }
            blocks = List.copyOf(Objects.requireNonNullElse(blocks, List.of()));
        }

        /**
         * 替换页面块。
         *
         * @param updatedBlocks 新块列表
         * @return 新页面
         */
        public Page withBlocks(List<Block> updatedBlocks) {
            return new Page(pageNumber, width, height, updatedBlocks);
        }
    }

    /**
     * 保留版面和清洗轨迹的内容块。
     */
    public record Block(String blockId,
                        BlockType type,
                        String rawText,
                        String normalizedText,
                        SourceSpan sourceSpan,
                        BoundingBox boundingBox,
                        Table table,
                        int readingOrder,
                        String regionId,
                        int columnIndex,
                        List<String> headingPath,
                        String language,
                        double confidence,
                        Set<Flag> flags,
                        boolean suppressed,
                        boolean retrievable,
                        String suppressionReason,
                        List<CleaningChange> cleaningChanges) {
        public Block {
            blockId = requireText(blockId, "blockId");
            type = Objects.requireNonNullElse(type, BlockType.PARAGRAPH);
            rawText = rawText == null ? "" : rawText;
            normalizedText = normalizedText == null ? rawText : normalizedText;
            if (readingOrder < 0 || columnIndex < 0) {
                throw new IllegalArgumentException("阅读顺序和分栏序号不能小于零");
            }
            regionId = regionId == null ? "" : regionId;
            headingPath = List.copyOf(Objects.requireNonNullElse(headingPath, List.of()));
            language = language == null ? "und" : language;
            if (!Double.isFinite(confidence) || confidence < 0 || confidence > 1) {
                throw new IllegalArgumentException("块置信度必须位于0到1之间");
            }
            flags = Set.copyOf(Objects.requireNonNullElse(flags, Set.of()));
            if (suppressed == retrievable) {
                throw new IllegalArgumentException("suppressed与retrievable状态必须相反");
            }
            suppressionReason = suppressionReason == null ? "" : suppressionReason;
            if (suppressed && suppressionReason.isBlank()) {
                throw new IllegalArgumentException("抑制块必须提供原因");
            }
            if (!suppressed && !suppressionReason.isBlank()) {
                throw new IllegalArgumentException("未抑制块不能携带抑制原因");
            }
            cleaningChanges = List.copyOf(Objects.requireNonNullElse(cleaningChanges, List.of()));
        }

        /**
         * 兼容基础块构造。
         */
        public Block(String blockId,
                     BlockType type,
                     String rawText,
                     String normalizedText,
                     SourceSpan sourceSpan,
                     BoundingBox boundingBox,
                     Table table,
                     Set<Flag> flags,
                     boolean suppressed,
                     List<CleaningChange> cleaningChanges) {
            this(blockId, type, rawText, normalizedText, sourceSpan, boundingBox, table,
                    0, "", 0, List.of(), "und", 1.0, flags, suppressed, !suppressed,
                    suppressed ? "legacy_suppressed" : "", cleaningChanges);
        }

        /**
         * 应用一次可逆清洗变更。
         *
         * @param cleaner 清洗器名称
         * @param nextText 清洗后文本
         * @param addedFlags 新增标记
         * @param nextSuppressed 新抑制状态
         * @return 新内容块
         */
        public Block apply(String cleaner, String nextText, Set<Flag> addedFlags,
                           boolean nextSuppressed, String nextSuppressionReason) {
            return evolve(cleaner, nextText, table, addedFlags, nextSuppressed, nextSuppressionReason);
        }

        /**
         * 同时替换文本与表格规范内容并记录可逆变更。
         *
         * @param cleaner 清洗器名称
         * @param nextText 新规范文本
         * @param nextTable 新表格
         * @param addedFlags 新增标记
         * @param nextSuppressed 新抑制状态
         * @param nextSuppressionReason 抑制原因
         * @return 新内容块
         */
        public Block evolve(String cleaner, String nextText, Table nextTable, Set<Flag> addedFlags,
                            boolean nextSuppressed, String nextSuppressionReason) {
            String safeText = nextText == null ? "" : nextText;
            Set<Flag> mergedFlags = new java.util.LinkedHashSet<>(flags);
            mergedFlags.addAll(Objects.requireNonNullElse(addedFlags, Set.of()));
            if (nextSuppressed) {
                mergedFlags.add(Flag.SUPPRESSED);
            }
            List<CleaningChange> changes = new java.util.ArrayList<>(cleaningChanges);
            changes.add(new CleaningChange(cleaner, normalizedText, safeText, addedFlags,
                    suppressed, nextSuppressed, suppressionReason,
                    nextSuppressed ? requireText(nextSuppressionReason, "suppressionReason") : ""));
            return new Block(blockId, type, rawText, safeText, sourceSpan, boundingBox, nextTable,
                    readingOrder, regionId, columnIndex, headingPath, language, confidence,
                    mergedFlags, nextSuppressed, !nextSuppressed,
                    nextSuppressed ? requireText(nextSuppressionReason, "suppressionReason") : "", changes);
        }

        /**
         * 应用不改变抑制原因的清洗变更。
         */
        public Block apply(String cleaner, String nextText, Set<Flag> addedFlags, boolean nextSuppressed) {
            String reason = nextSuppressed
                    ? (suppressionReason.isBlank() ? cleaner : suppressionReason)
                    : "";
            return apply(cleaner, nextText, addedFlags, nextSuppressed, reason);
        }
    }

    /**
     * 文本在解析器源字符流中的半开区间。
     */
    public record SourceSpan(int startOffset, int endOffset, String sourceLocation) {
        public SourceSpan {
            if (startOffset < 0 || endOffset < startOffset) {
                throw new IllegalArgumentException("来源文本区间不合法");
            }
            sourceLocation = sourceLocation == null ? "" : sourceLocation;
        }

        /**
         * 兼容仅提供字符区间的来源坐标。
         */
        public SourceSpan(int startOffset, int endOffset) {
            this(startOffset, endOffset, "");
        }

        /**
         * 返回区间长度。
         *
         * @return 字符长度
         */
        public int length() {
            return endOffset - startOffset;
        }
    }

    /**
     * 页面坐标系中的边界框。
     */
    public record BoundingBox(int pageNumber, double left, double top, double right, double bottom) {
        public BoundingBox {
            if (pageNumber < 1 || left < 0 || top < 0 || right < left || bottom < top) {
                throw new IllegalArgumentException("边界框不合法");
            }
        }
    }

    /**
     * 表格及其行。
     */
    public record Table(List<TableRow> rows) {
        public Table {
            rows = List.copyOf(Objects.requireNonNullElse(rows, List.of()));
        }

        /**
         * 返回表格全部单元格。
         *
         * @return 不可变单元格列表
         */
        public List<TableCell> cells() {
            return rows.stream().flatMap(row -> row.cells().stream()).toList();
        }
    }

    /**
     * 表格行。
     */
    public record TableRow(int rowIndex, List<TableCell> cells) {
        public TableRow {
            if (rowIndex < 0) {
                throw new IllegalArgumentException("表格行号不能小于零");
            }
            cells = List.copyOf(Objects.requireNonNullElse(cells, List.of()));
        }
    }

    /**
     * 保留原文、规范文本和坐标的表格单元格。
     */
    public record TableCell(int rowIndex,
                            int columnIndex,
                            int rowSpan,
                            int columnSpan,
                            String rawText,
                            String normalizedText,
                            SourceSpan sourceSpan,
                            BoundingBox boundingBox,
                            Set<Flag> flags) {
        public TableCell {
            if (rowIndex < 0 || columnIndex < 0 || rowSpan < 1 || columnSpan < 1) {
                throw new IllegalArgumentException("表格单元格位置不合法");
            }
            rawText = rawText == null ? "" : rawText;
            normalizedText = normalizedText == null ? rawText : normalizedText;
            flags = Set.copyOf(Objects.requireNonNullElse(flags, Set.of()));
        }
    }

    /**
     * 单步清洗的前后镜像。
     */
    public record CleaningChange(String cleaner,
                                 String beforeText,
                                 String afterText,
                                 Set<Flag> addedFlags,
                                 boolean suppressedBefore,
                                 boolean suppressedAfter,
                                 String suppressionReasonBefore,
                                 String suppressionReasonAfter) {
        public CleaningChange {
            cleaner = requireText(cleaner, "cleaner");
            beforeText = beforeText == null ? "" : beforeText;
            afterText = afterText == null ? "" : afterText;
            addedFlags = Set.copyOf(Objects.requireNonNullElse(addedFlags, Set.of()));
            suppressionReasonBefore = suppressionReasonBefore == null ? "" : suppressionReasonBefore;
            suppressionReasonAfter = suppressionReasonAfter == null ? "" : suppressionReasonAfter;
        }

        /**
         * 兼容不记录抑制原因的清洗轨迹。
         */
        public CleaningChange(String cleaner,
                              String beforeText,
                              String afterText,
                              Set<Flag> addedFlags,
                              boolean suppressedBefore,
                              boolean suppressedAfter) {
            this(cleaner, beforeText, afterText, addedFlags, suppressedBefore, suppressedAfter, "", "");
        }
    }

    /**
     * 内容块类型。
     */
    public enum BlockType {
        TITLE, HEADING, PARAGRAPH, LIST_ITEM, QUOTE, CAPTION, HEADER, FOOTER, FOOTNOTE,
        PAGE_NUMBER, WATERMARK, TABLE, FORMULA, CODE, IMAGE, IMAGE_TEXT, OTHER
    }

    /**
     * 清洗、风险和质量标记。
     */
    public enum Flag {
        UNICODE_NORMALIZED,
        CONTROL_CHARACTER,
        ZERO_WIDTH_CHARACTER,
        DEHYPHENATED,
        REPEATED_HEADER,
        REPEATED_FOOTER,
        DUPLICATE_BLOCK,
        LANGUAGE_MISMATCH,
        SENSITIVE_CONTENT,
        PROMPT_INJECTION,
        OCR_TEXT,
        REPLACEMENT_CHARACTER,
        TABLE_STRUCTURE,
        SUPPRESSED
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + "不能为空");
        }
        return value;
    }
}
