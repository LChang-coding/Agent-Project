package cn.bugstack.ai.domain.rag.adapter.port;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Qdrant 等可重建向量索引端口。
 * <p>tenantId 始终作为独立首参，具体实现还必须下推 payload filter。</p>
 */
public interface VectorStorePort {

    /**
     * 幂等批量写入指定文档版本的向量点。
     *
     * @param tenantId 向量点所属租户
     * @param versionId 向量点所属不可变文档版本
     * @param points 按稳定点标识写入的向量点批次
     */
    void upsert(String tenantId, String versionId, List<VectorPoint> points);

    /**
     * 删除租户指定文档版本的全部向量点。
     *
     * @param tenantId 向量点所属租户
     * @param versionId 待删除的文档版本标识
     */
    void deleteVersion(String tenantId, String versionId);

    /**
     * 精确统计租户指定文档版本的向量点数。
     *
     * @param tenantId 向量点所属租户
     * @param versionId 待统计的文档版本标识
     * @return 该版本当前存在的向量点数
     */
    long countVersion(String tenantId, String versionId);

    /**
     * 读取版本内的可信点标识与内容摘要，用于激活前精确核验。
     *
     * @param tenantId 向量点所属租户
     * @param versionId 待核验的文档版本标识
     * @return 该版本内全部点的最小核验快照
     */
    List<VectorPointSnapshot> listVersionPointSnapshots(String tenantId, String versionId);

    /**
     * 在服务端已确定的知识库与激活代际范围内检索候选。
     *
     * @param tenantId 检索所属租户
     * @param command 包含可信范围、查询向量和候选数量的命令
     * @return 向量存储返回的原始候选集
     */
    List<VectorSearchHit> search(String tenantId, VectorSearchCommand command);

    /** 一个同时支持 Dense 和 Sparse 的向量点。 */
    record VectorPoint(String pointId, String knowledgeBaseId, String documentId, String versionId,
                       long generation,
                       String chunkId, List<Float> denseVector,
                       SparseEncoderPort.SparseVector sparseVector, Map<String, String> payload) {
        public VectorPoint {
            if (pointId == null || pointId.isBlank() || knowledgeBaseId == null || knowledgeBaseId.isBlank()
                    || documentId == null || documentId.isBlank() || versionId == null || versionId.isBlank()
                    || generation < 1
                    || chunkId == null || chunkId.isBlank() || denseVector == null || denseVector.isEmpty()) {
                throw new IllegalArgumentException("向量点参数非法");
            }
            denseVector = List.copyOf(denseVector);
            payload = payload == null ? Map.of() : Map.copyOf(payload);
        }
    }

    /** 租户内向量检索请求。 */
    record VectorSearchCommand(Set<KnowledgeBaseScope> scopes, List<Float> denseVector,
                               SparseEncoderPort.SparseVector sparseVector, int topK) {
        public VectorSearchCommand {
            if (scopes == null || scopes.isEmpty() || topK < 1
                    || (denseVector == null || denseVector.isEmpty()) && sparseVector == null) {
                throw new IllegalArgumentException("向量检索请求参数非法");
            }
            scopes = Set.copyOf(scopes);
            denseVector = denseVector == null ? List.of() : List.copyOf(denseVector);
        }
    }

    /** 检索只允许命中知识库当前可见的索代引代。 */
    record KnowledgeBaseScope(String knowledgeBaseId, long activeGeneration) {
        public KnowledgeBaseScope {
            if (knowledgeBaseId == null || knowledgeBaseId.isBlank() || activeGeneration < 1) {
                throw new IllegalArgumentException("知识库检索范围非法");
            }
        }
    }

    /** 向量索引原始候选。 */
    record VectorSearchHit(String pointId, String knowledgeBaseId, String documentId,
                           String versionId, long generation, String chunkId, double score,
                           Map<String, String> payload) {
        public VectorSearchHit {
            if (pointId == null || pointId.isBlank() || knowledgeBaseId == null || knowledgeBaseId.isBlank()
                    || documentId == null || documentId.isBlank() || versionId == null || versionId.isBlank()
                    || generation < 1 || chunkId == null || chunkId.isBlank() || !Double.isFinite(score)) {
                throw new IllegalArgumentException("向量检索结果参数非法");
            }
            payload = payload == null ? Map.of() : Map.copyOf(payload);
        }
    }

    /** 激活门禁所需的最小向量点快照。 */
    record VectorPointSnapshot(String pointId, String chunkId, String contentHash) {
        public VectorPointSnapshot {
            if (pointId == null || pointId.isBlank() || chunkId == null || chunkId.isBlank()
                    || contentHash == null || !contentHash.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("向量点核验快照参数非法");
            }
        }
    }
}
