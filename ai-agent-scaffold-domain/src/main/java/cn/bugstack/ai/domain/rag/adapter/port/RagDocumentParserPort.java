package cn.bugstack.ai.domain.rag.adapter.port;

import cn.bugstack.ai.domain.rag.model.document.DocumentIr;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Docling 文档解析端口。
 */
public interface RagDocumentParserPort {

    /** 解析受控文档内容。 */
    ParsedDocument parse(ParseCommand command);

    /**
     * 文档解析命令。
     *
     * <p>上层必须先把 MinIO 对象流式落入受控临时目录，再把路径交给解析适配器；禁止把整份文档
     * 聚合为 byte[]，避免多任务并发时按文件大小放大堆内存。适配器不得信任原始文件名拼接路径。</p>
     */
    record ParseCommand(String tenantId, String jobId, String versionId, String fileName,
                        String mimeType, Path workspaceRoot, Path contentPath, long contentLength,
                        OcrMode ocrMode) {
        public ParseCommand {
            requireText(tenantId, "租户ID");
            requireText(jobId, "任务ID");
            requireText(versionId, "文档版本ID");
            requireText(fileName, "文件名");
            requireText(mimeType, "文件类型");
            if (workspaceRoot == null || contentPath == null || contentLength < 1 || ocrMode == null) {
                throw new IllegalArgumentException("待解析文档路径或长度非法");
            }
            workspaceRoot = workspaceRoot.toAbsolutePath().normalize();
            contentPath = contentPath.normalize();
            if (!contentPath.toAbsolutePath().normalize().startsWith(workspaceRoot)) {
                throw new IllegalArgumentException("待解析文档必须位于受控工作目录");
            }
        }

        /** 兼容旧调用并把文件父目录作为最小受控范围。 */
        public ParseCommand(String tenantId, String jobId, String versionId, String fileName,
                            String mimeType, Path contentPath, long contentLength, boolean ocrEnabled) {
            this(tenantId, jobId, versionId, fileName, mimeType,
                    contentPath == null ? null : contentPath.toAbsolutePath().normalize().getParent(),
                    contentPath, contentLength, ocrEnabled ? OcrMode.AUTO : OcrMode.DISABLED);
        }

        /** 是否允许解析器执行OCR。 */
        public boolean ocrEnabled() {
            return ocrMode != OcrMode.DISABLED;
        }
    }

    /** OCR执行策略。 */
    enum OcrMode {
        DISABLED,
        AUTO,
        FORCED
    }

    /** 解析后的结构化文档；Markdown字段仅保留兼容和展示用途。 */
    record ParsedDocument(String normalizedMarkdown, List<ParsedSection> sections,
                          int pageCount, String parserVersion, Map<String, String> metadata,
                          DocumentIr documentIr, String parserOutputJson,
                          List<String> warnings, boolean ocrApplied) {
        public ParsedDocument {
            requireText(normalizedMarkdown, "规范化正文");
            requireText(parserVersion, "解析器版本");
            if (pageCount < 0) {
                throw new IllegalArgumentException("文档页数非法");
            }
            sections = sections == null ? List.of() : List.copyOf(sections);
            metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
            if (documentIr == null) {
                documentIr = legacyIr(normalizedMarkdown, sections, pageCount, parserVersion);
            }
            parserOutputJson = parserOutputJson == null ? "" : parserOutputJson;
        }

        /** 兼容旧解析器与测试的Markdown结果构造。 */
        public ParsedDocument(String normalizedMarkdown, List<ParsedSection> sections,
                              int pageCount, String parserVersion, Map<String, String> metadata) {
            this(normalizedMarkdown, sections, pageCount, parserVersion, metadata,
                    null, "", List.of(), false);
        }

        private static DocumentIr legacyIr(String markdown, List<ParsedSection> sections,
                                           int pageCount, String parserVersion) {
            List<DocumentIr.Block> blocks = new ArrayList<>();
            List<ParsedSection> source = sections == null || sections.isEmpty()
                    ? List.of(new ParsedSection("", markdown, pageCount > 0 ? 1 : null, 0))
                    : sections;
            int offset = 0;
            for (ParsedSection section : source) {
                String text = section.content();
                int page = section.pageNumber() == null ? 1 : section.pageNumber();
                List<String> heading = section.headingPath() == null || section.headingPath().isBlank()
                        ? List.of() : List.of(section.headingPath().split("\\s*(?:>|/)\\s*"));
                blocks.add(new DocumentIr.Block("legacy-" + section.order(),
                        DocumentIr.BlockType.PARAGRAPH, text, text,
                        new DocumentIr.SourceSpan(offset, offset + text.length(), "legacy-markdown"),
                        null, null, section.order(), "", 0, heading, "und", 1.0,
                        Set.of(), false, true, "", List.of()));
                offset += text.length() + 1;
            }
            return new DocumentIr("1.0", "legacy", "", "text/markdown", "und",
                    "legacy-markdown", parserVersion,
                    List.of(new DocumentIr.Page(1, 0, 0, blocks)), Map.of(),
                    List.of("LEGACY_MARKDOWN_FALLBACK"), Set.of());
        }
    }

    /** 解析后的结构化章节。 */
    record ParsedSection(String headingPath, String content, Integer pageNumber, int order) {
        public ParsedSection {
            requireText(content, "章节正文");
            if (order < 0 || pageNumber != null && pageNumber < 1) {
                throw new IllegalArgumentException("章节序号或页码非法");
            }
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + "不能为空");
        }
    }
}
