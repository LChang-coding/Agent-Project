package cn.bugstack.ai.domain.rag.adapter.port;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

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
                        String mimeType, Path contentPath, long contentLength, boolean ocrEnabled) {
        public ParseCommand {
            requireText(tenantId, "租户ID");
            requireText(jobId, "任务ID");
            requireText(versionId, "文档版本ID");
            requireText(fileName, "文件名");
            requireText(mimeType, "文件类型");
            if (contentPath == null || contentLength < 1) {
                throw new IllegalArgumentException("待解析文档路径或长度非法");
            }
            contentPath = contentPath.normalize();
        }
    }

    /** 解析后的规范化文档。 */
    record ParsedDocument(String normalizedMarkdown, List<ParsedSection> sections,
                          int pageCount, String parserVersion, Map<String, String> metadata) {
        public ParsedDocument {
            requireText(normalizedMarkdown, "规范化正文");
            requireText(parserVersion, "解析器版本");
            if (pageCount < 0) {
                throw new IllegalArgumentException("文档页数非法");
            }
            sections = sections == null ? List.of() : List.copyOf(sections);
            metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
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
