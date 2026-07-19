package cn.bugstack.ai.domain.rag.model.valobj;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** RAG原件与解析产物的服务端对象键范围契约。 */
public final class RagObjectStorageScope {

    private RagObjectStorageScope() {
    }

    public static String versionPrefix(String tenantId, String knowledgeBaseId,
                                       String documentId, String versionId) {
        return "tenants/" + shortHash(tenantId) + "/rag/" + shortHash(knowledgeBaseId)
                + "/" + requireSegment(documentId, "documentId") + "/"
                + requireSegment(versionId, "versionId") + "/";
    }

    public static String sourceObjectKey(String tenantId, String knowledgeBaseId,
                                         String documentId, String versionId, String fileName) {
        return versionPrefix(tenantId, knowledgeBaseId, documentId, versionId)
                + requireSegment(fileName, "fileName");
    }

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

    private static String shortHash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(requireSegment(value, "scope").getBytes(StandardCharsets.UTF_8))).substring(0, 24);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 不支持 SHA-256", e);
        }
    }

    private static String requireSegment(String value, String field) {
        if (value == null || value.isBlank() || ".".equals(value) || "..".equals(value)
                || value.contains("/") || value.contains("\\")) {
            throw new IllegalArgumentException(field + "不是合法对象键段");
        }
        return value;
    }
}
