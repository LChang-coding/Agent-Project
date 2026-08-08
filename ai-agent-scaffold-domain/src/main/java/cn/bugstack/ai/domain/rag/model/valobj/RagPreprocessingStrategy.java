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

    /** 产物追溯与消融评测使用的稳定策略版本。 */
    private final String revision;
    /** 是否在分块前执行文档 IR 清洗规则。 */
    private final boolean cleanerEnabled;
    /** 是否在分块前保留标题、表格和页面结构。 */
    private final boolean structurePreserved;

    RagPreprocessingStrategy(String revision, boolean cleanerEnabled, boolean structurePreserved) {
        this.revision = revision;
        this.cleanerEnabled = cleanerEnabled;
        this.structurePreserved = structurePreserved;
    }

    /**
     * 返回用于产物追溯和消融评测的策略版本。
     * @return 稳定的预处理策略版本
     */
    public String revision() {
        return revision;
    }

    /**
     * 判断该策略是否执行文档 IR 清洗规则。
     * @return 需要执行清洗时返回 {@code true}
     */
    public boolean cleanerEnabled() {
        return cleanerEnabled;
    }

    /**
     * 判断该策略在分块前是否保留标题、表格和页面结构。
     * @return 保留结构化 IR 时返回 {@code true}
     */
    public boolean structurePreserved() {
        return structurePreserved;
    }
}
