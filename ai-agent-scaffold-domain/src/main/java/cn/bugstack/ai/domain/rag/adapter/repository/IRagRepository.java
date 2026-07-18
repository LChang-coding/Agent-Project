package cn.bugstack.ai.domain.rag.adapter.repository;

import cn.bugstack.ai.domain.rag.model.entity.RagAgentBindingEntity;
import cn.bugstack.ai.domain.rag.model.entity.RagChunkEntity;
import cn.bugstack.ai.domain.rag.model.entity.RagDocumentEntity;
import cn.bugstack.ai.domain.rag.model.entity.RagDocumentVersionEntity;
import cn.bugstack.ai.domain.rag.model.entity.RagIngestJobEntity;
import cn.bugstack.ai.domain.rag.model.entity.RagKnowledgeBaseEntity;
import cn.bugstack.ai.domain.rag.model.entity.RagRetrievalProfileEntity;
import cn.bugstack.ai.domain.rag.model.valobj.RagBindingTargetType;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * RAG 业务仓储端口。
 * <p>所有生产方法都以可信 tenantId 为首参，禁止仅凭资源 ID 查询或修改。</p>
 */
public interface IRagRepository {

    /** 按租户查询知识库。 */
    Optional<RagKnowledgeBaseEntity> findKnowledgeBase(String tenantId, String knowledgeBaseId);

    /** 查询租户知识库列表。 */
    List<RagKnowledgeBaseEntity> listKnowledgeBases(String tenantId);

    /** 新增租户知识库。 */
    int insertKnowledgeBase(String tenantId, RagKnowledgeBaseEntity knowledgeBase);

    /** 按乐观版本更新租户知识库。 */
    int updateKnowledgeBase(String tenantId, RagKnowledgeBaseEntity knowledgeBase, long expectedRevision);

    /** 按租户查询逻辑文档。 */
    Optional<RagDocumentEntity> findDocument(String tenantId, String documentId);

    /** 查询租户知识库下的文档。 */
    List<RagDocumentEntity> listDocuments(String tenantId, String knowledgeBaseId);

    /** 新增租户逻辑文档。 */
    int insertDocument(String tenantId, RagDocumentEntity document);

    /** 按乐观版本更新租户逻辑文档。 */
    int updateDocument(String tenantId, RagDocumentEntity document, long expectedRevision);

    /** 按租户查询不可变文档版本。 */
    Optional<RagDocumentVersionEntity> findDocumentVersion(String tenantId, String versionId);

    /** 查询租户文档的版本列表。 */
    List<RagDocumentVersionEntity> listDocumentVersions(String tenantId, String documentId);

    /** 新增租户文档版本。 */
    int insertDocumentVersion(String tenantId, RagDocumentVersionEntity version);

    /** 按乐观版本更新文档版本状态。 */
    int updateDocumentVersion(String tenantId, RagDocumentVersionEntity version, long expectedRevision);

    /** 按租户查询摄取任务。 */
    Optional<RagIngestJobEntity> findIngestJob(String tenantId, String jobId);

    /** 按租户和幂等键查询摄取任务。 */
    Optional<RagIngestJobEntity> findIngestJobByIdempotencyKey(String tenantId, String idempotencyKey);

    /** 新增租户摄取任务。 */
    int insertIngestJob(String tenantId, RagIngestJobEntity job);

    /** 按任务 revision 做 CAS 更新。 */
    int updateIngestJob(String tenantId, RagIngestJobEntity job, long expectedRevision);

    /** 领取一条到期任务；实现必须原子分配单调递增 fencing token。 */
    Optional<RagIngestJobEntity> claimDueIngestJob(String tenantId, String leaseOwner,
                                                   Instant now, Instant leaseUntil);

    /** 查询租户文档版本的分块。 */
    List<RagChunkEntity> listChunks(String tenantId, String versionId);

    /** 幂等批量保存租户分块。 */
    int upsertChunks(String tenantId, String versionId, List<RagChunkEntity> chunks);

    /** 删除指定版本的业务分块记录。 */
    int deleteChunks(String tenantId, String versionId);

    /** 按租户查询检索配置。 */
    Optional<RagRetrievalProfileEntity> findRetrievalProfile(String tenantId, String profileId);

    /** 查询租户目标的知识库绑定。 */
    List<RagAgentBindingEntity> listBindings(String tenantId, RagBindingTargetType targetType, String targetId);
}
