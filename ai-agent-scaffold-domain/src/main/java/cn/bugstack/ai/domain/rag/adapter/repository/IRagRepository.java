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

    /**
     * 按租户查询知识库。
     * @param tenantId 知识库所属租户
     * @param knowledgeBaseId 知识库标识
     * @return 匹配的知识库，不存在时为空
     */
    Optional<RagKnowledgeBaseEntity> findKnowledgeBase(String tenantId, String knowledgeBaseId);

    /**
     * 查询租户知识库列表。
     * @param tenantId 知识库所属租户
     * @return 该租户可见的知识库列表
     */
    List<RagKnowledgeBaseEntity> listKnowledgeBases(String tenantId);

    /**
     * 新增租户知识库。
     * @param tenantId 知识库所属租户
     * @param knowledgeBase 待持久化的知识库
     * @return 实际插入行数
     */
    int insertKnowledgeBase(String tenantId, RagKnowledgeBaseEntity knowledgeBase);

    /**
     * 按乐观版本更新租户知识库。
     * @param tenantId 知识库所属租户
     * @param knowledgeBase 更新后的知识库
     * @param expectedRevision 更新前的预期版本号
     * @return 实际更新行数，0 表示版本已变化
     */
    int updateKnowledgeBase(String tenantId, RagKnowledgeBaseEntity knowledgeBase, long expectedRevision);

    /**
     * 按租户查询逻辑文档。
     * @param tenantId 文档所属租户
     * @param documentId 逻辑文档标识
     * @return 匹配的文档，不存在时为空
     */
    Optional<RagDocumentEntity> findDocument(String tenantId, String documentId);

    /**
     * 按租户批量查询逻辑文档；实现必须限制批次并去重。
     * @param tenantId 文档所属租户
     * @param documentIds 待查询的文档标识集合
     * @return 位于输入范围内的文档列表
     */
    List<RagDocumentEntity> listDocumentsByIds(String tenantId, List<String> documentIds);

    /**
     * 查询租户知识库下的文档。
     * @param tenantId 文档所属租户
     * @param knowledgeBaseId 知识库标识
     * @return 该知识库下的文档列表
     */
    List<RagDocumentEntity> listDocuments(String tenantId, String knowledgeBaseId);

    /**
     * 新增租户逻辑文档。
     * @param tenantId 文档所属租户
     * @param document 待持久化的逻辑文档
     * @return 实际插入行数
     */
    int insertDocument(String tenantId, RagDocumentEntity document);

    /**
     * 按乐观版本更新租户逻辑文档。
     * @param tenantId 文档所属租户
     * @param document 更新后的逻辑文档
     * @param expectedRevision 更新前的预期版本号
     * @return 实际更新行数，0 表示版本已变化
     */
    int updateDocument(String tenantId, RagDocumentEntity document, long expectedRevision);

    /**
     * 按租户查询不可变文档版本。
     * @param tenantId 文档版本所属租户
     * @param versionId 文档版本标识
     * @return 匹配的文档版本，不存在时为空
     */
    Optional<RagDocumentVersionEntity> findDocumentVersion(String tenantId, String versionId);

    /**
     * 查询租户文档的版本列表。
     * @param tenantId 文档所属租户
     * @param documentId 逻辑文档标识
     * @return 该文档的版本列表
     */
    List<RagDocumentVersionEntity> listDocumentVersions(String tenantId, String documentId);

    /**
     * 按乐观版本更新文档版本状态。
     * @param tenantId 文档版本所属租户
     * @param version 更新后的文档版本
     * @param expectedRevision 更新前的预期版本号
     * @return 实际更新行数，0 表示版本已变化
     */
    int updateDocumentVersion(String tenantId, RagDocumentVersionEntity version, long expectedRevision);

    /**
     * 按租户查询摄取任务。
     * @param tenantId 任务所属租户
     * @param jobId 摄取任务标识
     * @return 匹配的摄取任务，不存在时为空
     */
    Optional<RagIngestJobEntity> findIngestJob(String tenantId, String jobId);

    /**
     * 按租户和知识库查询最新摄取任务。
     * @param tenantId 任务所属租户
     * @param knowledgeBaseId 知识库标识
     * @param limit 最大返回数量
     * @return 按创建时间倒序排列的最新任务
     */
    List<RagIngestJobEntity> listIngestJobs(String tenantId, String knowledgeBaseId, int limit);

    /**
     * 按租户和幂等键查询摄取任务。
     * @param tenantId 任务所属租户
     * @param idempotencyKey 上传或删除命令的幂等键
     * @return 已登记的任务，不存在时为空
     */
    Optional<RagIngestJobEntity> findIngestJobByIdempotencyKey(String tenantId, String idempotencyKey);

    /**
     * 新增租户摄取任务。
     * @param tenantId 任务所属租户
     * @param job 待持久化的摄取任务
     * @return 实际插入行数
     */
    int insertIngestJob(String tenantId, RagIngestJobEntity job);

    /**
     * 按任务版本号做 CAS 更新。
     * @param tenantId 任务所属租户
     * @param job 更新后的摄取任务
     * @param expectedRevision 更新前的预期版本号
     * @return 实际更新行数，0 表示版本已变化
     */
    int updateIngestJob(String tenantId, RagIngestJobEntity job, long expectedRevision);

    /**
     * 原子恢复失败摄取任务、不可变版本和逻辑文档，知识库状态在同一事务中加锁校验。
     *
     * @param tenantId 相关聚合所属租户
     * @param requeuedJob 已转为等待执行的摄取任务
     * @param expectedTaskRevision 恢复前的预期任务版本号
     * @param queuedVersion 已转为等待处理的文档版本
     * @param expectedVersionRevision 恢复前的预期文档版本号
     * @param processingDocument 已转为处理中的逻辑文档
     * @param expectedDocumentRevision 恢复前的预期文档版本号
     * @param expectedKnowledgeBaseRevision 事务内必须匹配的知识库版本号
     */
    void requeueFailedIngestJob(String tenantId, RagIngestJobEntity requeuedJob,
                                long expectedTaskRevision, RagDocumentVersionEntity queuedVersion,
                                long expectedVersionRevision, RagDocumentEntity processingDocument,
                                long expectedDocumentRevision, long expectedKnowledgeBaseRevision);

    /**
     * 扫描全局到期任务的最小候选投影。
     * <p>这是唯一不以 tenantId 为首参的 Worker 发现方法；它不返回任务内容，
     * 也不允许修改数据。</p>
     *
     * @param now 判断重试时间和租约是否到期的时刻
     * @param limit 单次扫描的最大候选数
     * @return 待尝试领取的租户与任务标识投影
     */
    List<RagIngestJobCandidate> listDueIngestJobCandidates(Instant now, int limit);

    /**
     * 原子领取一条到期任务，并分配单调递增的 fencing token。
     * @param tenantId 任务所属租户
     * @param jobId 待领取的任务标识
     * @param leaseOwner 执行实例标识
     * @param now 领取时刻
     * @param leaseUntil 租约到期时刻
     * @return 领取成功后的任务，不可领取时为空
     */
    Optional<RagIngestJobEntity> claimDueIngestJob(String tenantId, String jobId, String leaseOwner,
                                                   Instant now, Instant leaseUntil);

    /**
     * 为租约已过期的 cancel_requested 任务重新分配清理租约，不改回 running。
     * @param tenantId 任务所属租户
     * @param jobId 待接管清理的任务标识
     * @param leaseOwner 新的执行实例标识
     * @param now 租约到期校验时刻
     * @param leaseUntil 新的租约到期时刻
     * @return 重新领取后的取消任务，不可领取时为空
     */
    Optional<RagIngestJobEntity> claimCancelledIngestJobForCleanup(String tenantId, String jobId,
                                                                   String leaseOwner, Instant now,
                                                                   Instant leaseUntil);

    /**
     * 当前执行实例按租约、fencing token 和业务版本号做 CAS 更新。
     * @param tenantId 任务所属租户
     * @param job 更新后的任务
     * @param expectedRevision 更新前的预期业务版本号
     * @param leaseOwner 当前执行实例标识
     * @param expectedFencingToken 领取时获得的 fencing token
     * @param now 租约有效性校验时刻
     * @return 实际更新行数，0 表示版本或执行权已变化
     */
    int updateClaimedIngestJob(String tenantId, RagIngestJobEntity job, long expectedRevision,
                               String leaseOwner, long expectedFencingToken, Instant now);

    /**
     * 独立续租，不修改业务版本号，避免与检查点或生命周期更新争用版本号。
     * @param tenantId 任务所属租户
     * @param jobId 任务标识
     * @param leaseOwner 当前执行实例标识
     * @param expectedFencingToken 领取时获得的 fencing token
     * @param now 租约有效性校验时刻
     * @param leaseUntil 新的租约到期时刻
     * @return 实际更新行数，0 表示执行权已变化
     */
    int heartbeatClaimedIngestJob(String tenantId, String jobId, String leaseOwner,
                                  long expectedFencingToken, Instant now, Instant leaseUntil);

    /**
     * 在同一事务中激活文档版本、文档与知识库的索引代际，并完成任务。
     * @param tenantId 相关聚合所属租户
     * @param completedJob 已转为完成状态的摄取任务
     * @param expectedTaskRevision 完成前的预期任务版本号
     * @param leaseOwner 当前执行实例标识
     * @param expectedFencingToken 领取时获得的 fencing token
     * @param activation 待原子激活的版本、文档与索引代际
     * @param now 租约有效性与完成时间的校验时刻
     */
    void completeClaimedIngestJob(String tenantId, RagIngestJobEntity completedJob,
                                  long expectedTaskRevision, String leaseOwner,
                                  long expectedFencingToken, RagIndexActivation activation, Instant now);

    /**
     * 在同一事务中把全部版本、文档和删除任务改为终态。
     * @param tenantId 相关聚合所属租户
     * @param completedJob 已转为完成状态的删除任务
     * @param expectedTaskRevision 完成前的预期任务版本号
     * @param leaseOwner 当前执行实例标识
     * @param expectedFencingToken 领取时获得的 fencing token
     * @param deletedDocument 已转为删除终态的逻辑文档
     * @param deletedVersions 已转为删除终态的全部文档版本
     * @param now 租约有效性与完成时间的校验时刻
     */
    void completeClaimedDeleteJob(String tenantId, RagIngestJobEntity completedJob,
                                  long expectedTaskRevision, String leaseOwner,
                                  long expectedFencingToken, RagDocumentEntity deletedDocument,
                                  List<RagDocumentVersionEntity> deletedVersions, Instant now);

    /**
     * 在同一事务中关闭版本、清理文档目标索引代际并取消已领取任务。
     * @param tenantId 相关聚合所属租户
     * @param cancelledJob 已转为取消终态的任务
     * @param expectedTaskRevision 取消前的预期任务版本号
     * @param expectedVersionRevision 关闭前的预期文档版本号
     * @param expectedDocumentRevision 更新前的预期文档版本号
     * @param leaseOwner 当前执行实例标识
     * @param expectedFencingToken 领取时获得的 fencing token
     * @param now 租约有效性与取消时间的校验时刻
     */
    void cancelClaimedIngestJob(String tenantId, RagIngestJobEntity cancelledJob,
                                long expectedTaskRevision, long expectedVersionRevision,
                                long expectedDocumentRevision, String leaseOwner,
                                long expectedFencingToken, Instant now);

    /**
     * 在同一事务中关闭从未被执行实例领取的版本、文档目标索引代际与任务。
     * @param tenantId 相关聚合所属租户
     * @param cancelledJob 已转为取消终态的任务
     * @param expectedTaskRevision 取消前的预期任务版本号
     * @param expectedVersionRevision 关闭前的预期文档版本号
     * @param expectedDocumentRevision 更新前的预期文档版本号
     */
    void cancelUnclaimedIngestJob(String tenantId, RagIngestJobEntity cancelledJob,
                                  long expectedTaskRevision, long expectedVersionRevision,
                                  long expectedDocumentRevision);

    /**
     * 在同一事务中关闭版本、清理文档目标索引代际并把任务改为失败终态。
     * @param tenantId 相关聚合所属租户
     * @param failedJob 已转为失败终态的任务
     * @param expectedTaskRevision 失败处理前的预期任务版本号
     * @param expectedVersionRevision 关闭前的预期文档版本号
     * @param expectedDocumentRevision 更新前的预期文档版本号
     * @param leaseOwner 当前执行实例标识
     * @param expectedFencingToken 领取时获得的 fencing token
     * @param now 租约有效性与失败时间的校验时刻
     */
    void failClaimedIngestJob(String tenantId, RagIngestJobEntity failedJob,
                              long expectedTaskRevision, long expectedVersionRevision,
                              long expectedDocumentRevision, String leaseOwner,
                              long expectedFencingToken, Instant now);

    /**
     * 在同一事务中将已完成副作用清理的 cancel_requested 任务收口为失败终态。
     * <p>普通失败从 running 收口，失败补偿清理则从 cancel_requested 收口，
     * 两者不能共用同一个持久化前置状态。</p>
     */
    void failAfterCleanupClaimedIngestJob(String tenantId, RagIngestJobEntity failedJob,
                                          long expectedTaskRevision, long expectedVersionRevision,
                                          long expectedDocumentRevision, String leaseOwner,
                                          long expectedFencingToken, Instant now);

    /**
     * 查询租户文档版本的分块。
     * @param tenantId 分块所属租户
     * @param versionId 文档版本标识
     * @return 该版本的有序分块列表
     */
    List<RagChunkEntity> listChunks(String tenantId, String versionId);

    /**
     * 按租户批量读取主命中、父分块和相邻分块；实现必须限制批次并保持输入范围。
     * @param tenantId 分块所属租户
     * @param chunkIds 待读取的分块标识集合
     * @return 位于输入范围内的分块列表
     */
    List<RagChunkEntity> listChunksByIds(String tenantId, List<String> chunkIds);

    /**
     * 幂等批量保存租户分块。
     * @param tenantId 分块所属租户
     * @param versionId 分块所属文档版本
     * @param chunks 待写入的分块批次
     * @return 实际写入行数
     */
    int upsertChunks(String tenantId, String versionId, List<RagChunkEntity> chunks);

    /**
     * 软删除指定版本的业务分块记录，用于可恢复摄取补偿。
     * @param tenantId 分块所属租户
     * @param versionId 待软删除的文档版本标识
     * @return 实际更新行数
     */
    int deleteChunks(String tenantId, String versionId);

    /**
     * 物理删除指定版本的业务分块正文，用于不可逆文档删除。
     * @param tenantId 分块所属租户
     * @param versionId 待物理删除的文档版本标识
     * @return 实际删除行数
     */
    int purgeChunks(String tenantId, String versionId);

    /**
     * 统计指定版本全部业务分块，包含已软删记录。
     * @param tenantId 分块所属租户
     * @param versionId 待统计的文档版本标识
     * @return 该版本的全部分块数
     */
    long countAllChunks(String tenantId, String versionId);

    /**
     * 按租户查询检索配置。
     * @param tenantId 检索配置所属租户
     * @param profileId 检索配置标识
     * @return 匹配的检索配置，不存在时为空
     */
    Optional<RagRetrievalProfileEntity> findRetrievalProfile(String tenantId, String profileId);

    /**
     * 查询租户启用的检索配置。
     * @param tenantId 检索配置所属租户
     * @return 该租户当前可用的检索配置列表
     */
    List<RagRetrievalProfileEntity> listRetrievalProfiles(String tenantId);

    /**
     * 新增租户检索配置。
     * @param tenantId 检索配置所属租户
     * @param profile 待持久化的检索配置
     * @return 实际插入行数
     */
    int insertRetrievalProfile(String tenantId, RagRetrievalProfileEntity profile);

    /**
     * 按预期版本号 CAS 更新租户检索配置。
     * @param tenantId 检索配置所属租户
     * @param profile 更新后的检索配置
     * @param expectedRevision 更新前的预期版本号
     * @return 实际更新行数，0 表示版本已变化
     */
    int updateRetrievalProfile(String tenantId, RagRetrievalProfileEntity profile, long expectedRevision);

    /**
     * 查询租户目标的知识库绑定。
     * @param tenantId 绑定所属租户
     * @param targetType 绑定目标类型
     * @param targetId Agent 或工作流标识
     * @return 目标当前启用的知识库绑定
     */
    List<RagAgentBindingEntity> listBindings(String tenantId, RagBindingTargetType targetType, String targetId);

    /**
     * 查询租户全部启用绑定。
     * @param tenantId 绑定所属租户
     * @return 该租户当前启用的知识库绑定
     */
    List<RagAgentBindingEntity> listBindings(String tenantId);

    /**
     * 按租户查询绑定。
     * @param tenantId 绑定所属租户
     * @param bindingId 绑定标识
     * @return 匹配的绑定，不存在时为空
     */
    Optional<RagAgentBindingEntity> findBinding(String tenantId, String bindingId);

    /**
     * 新增租户绑定。
     * @param tenantId 绑定所属租户
     * @param binding 待持久化的知识库绑定
     * @return 实际插入行数
     */
    int insertBinding(String tenantId, RagAgentBindingEntity binding);

    /**
     * 按预期版本号 CAS 软删除租户绑定。
     * @param tenantId 绑定所属租户
     * @param bindingId 待删除的绑定标识
     * @param expectedRevision 删除前的预期版本号
     * @return 实际更新行数，0 表示版本已变化
     */
    int deleteBinding(String tenantId, String bindingId, long expectedRevision);
}
