package cn.bugstack.ai.infrastructure.rag.worker;

import cn.bugstack.ai.types.exception.AppException;

import java.io.IOException;
import java.util.Set;

/** 将摄取异常收敛为可安全持久的稳定错误。 */
public final class RagIngestErrorClassifier {

    private static final Set<String> RETRYABLE_CODES = Set.of(
            "RAG_DOCLING_UNAVAILABLE", "RAG_DOCLING_INTERRUPTED",
            "RAG_EMBEDDING_UNAVAILABLE", "RAG_EMBEDDING_INTERRUPTED",
            "RAG_EMBEDDING_TRANSIENT_HTTP_ERROR",
            "RAG_QDRANT_UNAVAILABLE", "RAG_QDRANT_INTERRUPTED",
            "RAG_REMOTE_BUSY", "RAG_REMOTE_INTERRUPTED",
            "OBJECT_STORAGE_DOWNLOAD_FAILED", "RAG_OBJECT_DOWNLOAD_FAILED", "RAG_OBJECT_STORAGE_UNAVAILABLE",
            "RAG_WORKSPACE_CREATE_FAILED", "RAG_WORKSPACE_CLEANUP_FAILED");

    /** 分类且脱敏；绝不保存原始 exception message。 */
    public Failure classify(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof AppException appException) {
                String code = safeCode(appException.getCode());
                return new Failure(code, RETRYABLE_CODES.contains(code), safeSummary(code));
            }
            if (current instanceof IOException) {
                return new Failure("RAG_INGEST_IO_TRANSIENT", true, "摄取本地或网络 I/O 暂时失败");
            }
            current = current.getCause();
        }
        return new Failure("RAG_INGEST_INTERNAL_ERROR", false, "摄取流程发生内部错误");
    }

    private String safeCode(String code) {
        if (code == null || !code.matches("[A-Z0-9_]{1,128}")) return "RAG_INGEST_FAILED";
        return code;
    }

    private String safeSummary(String code) {
        if (code.contains("CANCEL") || code.contains("FENCE") || code.contains("LEASE")) {
            return "摄取任务已取消或租约失效";
        }
        if (code.contains("DOCLING")) return "文档解析阶段失败";
        if (code.contains("EMBEDDING")) return "向量化阶段失败";
        if (code.contains("QDRANT") || code.contains("INDEX")) return "向量索引阶段失败";
        if (code.contains("DOWNLOAD") || code.contains("STORAGE") || code.contains("OBJECT")) {
            return "文档对象读取阶段失败";
        }
        return "摄取任务执行失败";
    }

    public record Failure(String code, boolean retryable, String safeMessage) {
    }
}
