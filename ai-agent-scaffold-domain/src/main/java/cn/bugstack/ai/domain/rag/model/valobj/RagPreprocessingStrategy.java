package cn.bugstack.ai.domain.rag.model.valobj;

/** RAG 文档预处理消融策略；生产流量只能使用完整 IR 链路。 */
public enum RagPreprocessingStrategy {
    /** 旧链路：格式解析后立即压成单块 Markdown。 */
    LEGACY_MARKDOWN_FLATTEN("legacy-markdown-flatten-v1", false, false),
    /** 纯文本基线：移除 Markdown 标记后按单块文本切分。 */
    RAW_TEXT_CHUNK("raw-text-chunk-v1", false, false),
    /** 保留格式专用 IR，但不执行 Cleaner Chain。 */
    IR_NO_CLEANER("document-ir-no-cleaner-v1", false, true),
    /** 执行 Cleaner，但在分块前丢弃标题、表格和页面结构。 */
    IR_NO_STRUCTURED_CHUNKING("document-ir-flat-chunk-v1", true, false),
    /** 生产默认：格式专用 IR、Cleaner 和结构感知分块全部启用。 */
    IR_FULL("document-ir-full-v1", true, true);

    private final String revision;
    private final boolean cleanerEnabled;
    private final boolean structurePreserved;

    RagPreprocessingStrategy(String revision, boolean cleanerEnabled, boolean structurePreserved) {
        this.revision = revision;
        this.cleanerEnabled = cleanerEnabled;
        this.structurePreserved = structurePreserved;
    }

    public String revision() {
        return revision;
    }

    public boolean cleanerEnabled() {
        return cleanerEnabled;
    }

    public boolean structurePreserved() {
        return structurePreserved;
    }
}
