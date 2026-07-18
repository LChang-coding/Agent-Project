package cn.bugstack.ai.domain.rag.adapter.repository;

import cn.bugstack.ai.domain.rag.model.entity.RagAgentBindingEntity;
import cn.bugstack.ai.domain.rag.model.entity.RagChunkEntity;
import cn.bugstack.ai.domain.rag.model.entity.RagDocumentEntity;
import cn.bugstack.ai.domain.rag.model.entity.RagDocumentVersionEntity;
import cn.bugstack.ai.domain.rag.model.entity.RagIngestJobEntity;
import cn.bugstack.ai.domain.rag.model.entity.RagKnowledgeBaseEntity;
import cn.bugstack.ai.domain.rag.model.entity.RagRetrievalProfileEntity;
import cn.bugstack.ai.domain.rag.model.valobj.RagBindingTargetType;
import cn.bugstack.ai.domain.rag.model.valobj.RagIndexActivation;
import cn.bugstack.ai.domain.rag.model.valobj.RagIngestJobCandidate;

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

    /**
     * 扫描全局到期任务的最小候选投影。
     * <p>这是唯一不以 tenantId 为首参的 Worker 发现方法；它不返回任务内容，
     * 也不允许修改数据。</p>
     */
    List<RagIngestJobCandidate> listDueIngestJobCandidates(Instant now, int limit);

    /** 领取一条到期任务；实现必须原子分配单调递增 fencing token。 */
    Optional<RagIngestJobEntity> claimDueIngestJob(String tenantId, String jobId, String leaseOwner,
                                                   Instant now, Instant leaseUntil);

    /** 为宕机 Worker 遗留的 cancel_requested 任务重新分配清理租约，不改回 running。 */
    Optional<RagIngestJobEntity> claimCancelledIngestJobForCleanup(String tenantId, String jobId,
                                                                   String leaseOwner, Instant now,
                                                                   Instant leaseUntil);

    /** 当前 Worker 按租约、fencing token 和 revision 做 CAS 更新。 */
    int updateClaimedIngestJob(String tenantId, RagIngestJobEntity job, long expectedRevision,
                               String leaseOwner, long expectedFencingToken, Instant now);

    /** 独立续租，不修改 row revision，避免与 checkpoint/lifecycle CAS 争用版本号。 */
    int heartbeatClaimedIngestJob(String tenantId, String jobId, String leaseOwner,
                                  long expectedFencingToken, Instant now, Instant leaseUntil);

    /** 在同一事务中激活版本/文档/知识库 generation 并完成任务。 */
    void completeClaimedIngestJob(String tenantId, RagIngestJobEntity completedJob,
                                  long expectedTaskRevision, String leaseOwner,
                                  long expectedFencingToken, RagIndexActivation activation, Instant now);

    /** 在同一事务中关闭版本、清理文档目标 generation 并取消任务。 */
    void cancelClaimedIngestJob(String tenantId, RagIngestJobEntity cancelledJob,
                                long expectedTaskRevision, long expectedVersionRevision,
                                long expectedDocumentRevision, String leaseOwner,
                                long expectedFencingToken, Instant now);

    /** 在同一事务中关闭版本、清理文档目标 generation 并关闭失败任务。 */
    void failClaimedIngestJob(String tenantId, RagIngestJobEntity failedJob,
                              long expectedTaskRevision, long expectedVersionRevision,
                              long expectedDocumentRevision, String leaseOwner,
                              long expectedFencingToken, Instant now);

    /** 查询租户文档版本的分块。 */
    List<RagChunkEntity> listChunks(String tenantId, String versionId);

    /** 按租户批量读取主命中、父块和相邻块；实现必须限制批次并保持输入范围。 */
    List<RagChunkEntity> listChunksByIds(String tenantId, List<String> chunkIds);

    /** 幂等批量保存租户分块。 */
    int upsertChunks(String tenantId, String versionId, List<RagChunkEntity> chunks);

    /** 删除指定版本的业务分块记录。 */
    int deleteChunks(String tenantId, String versionId);

    /** 按租户查询检索配置。 */
    Optional<RagRetrievalProfileEntity> findRetrievalProfile(String tenantId, String profileId);

    /** 查询租户启用的检索配置。 */
    List<RagRetrievalProfileEntity> listRetrievalProfiles(String tenantId);

    /** 新增租户检索配置。 */
    int insertRetrievalProfile(String tenantId, RagRetrievalProfileEntity profile);

    /** revision CAS 更新租户检索配置。 */
    int updateRetrievalProfile(String tenantId, RagRetrievalProfileEntity profile, long expectedRevision);

    /** 查询租户目标的知识库绑定。 */
    List<RagAgentBindingEntity> listBindings(String tenantId, RagBindingTargetType targetType, String targetId);

    /** 查询租户全部启用绑定。 */
    List<RagAgentBindingEntity> listBindings(String tenantId);

    /** 按租户查询绑定。 */
    Optional<RagAgentBindingEntity> findBinding(String tenantId, String bindingId);

    /** 新增租户绑定。 */
    int insertBinding(String tenantId, RagAgentBindingEntity binding);

    /** revision CAS 软删除租户绑定。 */
    int deleteBinding(String tenantId, String bindingId, long expectedRevision);
}
