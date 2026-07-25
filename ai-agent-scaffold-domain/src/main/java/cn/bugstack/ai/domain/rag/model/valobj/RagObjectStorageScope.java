package cn.bugstack.ai.domain.rag.model.valobj;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/** RAG原件与解析产物的服务端对象键范围契约。 */
public final class RagObjectStorageScope {

    /** 禁止实例化纯对象键规范工具。 */
    private RagObjectStorageScope() {
    }

    /** 构造租户、知识库、文档和不可变版本共同限定的对象前缀。 */
    public static String versionPrefix(String tenantId, String knowledgeBaseId,
                                       String documentId, String versionId) {
        return "tenants/" + shortHash(tenantId) + "/rag/" + shortHash(knowledgeBaseId)
                + "/" + requireSegment(documentId, "documentId") + "/"
                + requireSegment(versionId, "versionId") + "/";
    }

    /** 构造版本原始上传文件的对象键。 */
    public static String sourceObjectKey(String tenantId, String knowledgeBaseId,
                                         String documentId, String versionId, String fileName) {
        return versionPrefix(tenantId, knowledgeBaseId, documentId, versionId)
                + requireSegment(fileName, "fileName");
    }

    /** 每个不可变版本唯一的规范化 Markdown 解析产物键，重试会覆盖同一对象。 */
    public static String parsedObjectKey(String tenantId, String knowledgeBaseId,
                                         String documentId, String versionId) {
        return versionPrefix(tenantId, knowledgeBaseId, documentId, versionId) + "parsed/normalized.md";
    }

    /** Docling或本地格式解析器的原始结构化响应。 */
    public static String parserOutputObjectKey(String tenantId, String knowledgeBaseId,
                                               String documentId, String versionId) {
        return versionPrefix(tenantId, knowledgeBaseId, documentId, versionId)
                + "parsed/parser-output.json";
    }

    /** 预处理主事实源：版本化Canonical Document IR。 */
    public static String documentIrObjectKey(String tenantId, String knowledgeBaseId,
                                             String documentId, String versionId) {
        return versionPrefix(tenantId, knowledgeBaseId, documentId, versionId)
                + "ir/document-ir-v1.json";
    }

    /** 仅用于展示和兼容调试的规范化Markdown。 */
    public static String normalizedMarkdownObjectKey(String tenantId, String knowledgeBaseId,
                                                     String documentId, String versionId) {
        return versionPrefix(tenantId, knowledgeBaseId, documentId, versionId)
                + "normalized/normalized.md";
    }

    /** 解析与清洗质量报告。 */
    public static String qualityReportObjectKey(String tenantId, String knowledgeBaseId,
                                                String documentId, String versionId) {
        return versionPrefix(tenantId, knowledgeBaseId, documentId, versionId)
                + "quality/quality-report.json";
    }

    /** 可复算的分块清单。 */
    public static String chunkManifestObjectKey(String tenantId, String knowledgeBaseId,
                                                String documentId, String versionId) {
        return versionPrefix(tenantId, knowledgeBaseId, documentId, versionId)
                + "chunks/chunk-manifest.json";
    }

    /** 当前IR预处理版本应存在并在取消/删除时一并清理的全部衍生产物。 */
    public static List<String> preprocessingArtifactObjectKeys(String tenantId, String knowledgeBaseId,
                                                               String documentId, String versionId) {
        return List.of(parserOutputObjectKey(tenantId, knowledgeBaseId, documentId, versionId),
                documentIrObjectKey(tenantId, knowledgeBaseId, documentId, versionId),
                normalizedMarkdownObjectKey(tenantId, knowledgeBaseId, documentId, versionId),
                qualityReportObjectKey(tenantId, knowledgeBaseId, documentId, versionId),
                chunkManifestObjectKey(tenantId, knowledgeBaseId, documentId, versionId));
    }

    /** 校验对象键位于可信版本前缀内且不含空段或路径穿越。 */
    public static boolean containsVersionObject(String objectKey, String tenantId, String knowledgeBaseId,
                                                String documentId, String versionId) {
        String prefix = versionPrefix(tenantId, knowledgeBaseId, documentId, versionId);
        if (objectKey == null || !objectKey.startsWith(prefix) || objectKey.length() <= prefix.length()
                || objectKey.contains("\\")) return false;
        for (String segment : objectKey.split("/", -1)) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) return false;
        }
        return true;
    }

    /** 生成不暴露原始长标识的稳定短路径段。 */
    private static String shortHash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(requireSegment(value, "scope").getBytes(StandardCharsets.UTF_8))).substring(0, 24);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 不支持 SHA-256", e);
        }
    }

    /** 校验并返回对象键必需的路径语义段。 */
    private static String requireSegment(String value, String field) {
        if (value == null || value.isBlank() || ".".equals(value) || "..".equals(value)
                || value.contains("/") || value.contains("\\")) {
            throw new IllegalArgumentException(field + "不是合法对象键段");
        }
        return value;
    }
}
