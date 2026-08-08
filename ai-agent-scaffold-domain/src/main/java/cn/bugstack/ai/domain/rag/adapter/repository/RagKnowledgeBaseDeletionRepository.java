package cn.bugstack.ai.domain.rag.adapter.repository;

import cn.bugstack.ai.domain.rag.model.entity.RagKnowledgeBaseDeleteRegistration;
import cn.bugstack.ai.domain.rag.model.entity.RagKnowledgeBaseDeleteTaskEntity;
import cn.bugstack.ai.domain.rag.model.valobj.RagKnowledgeBaseDeleteCandidate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 知识库级联删除任务账本和聚合事务端口。 */
public interface RagKnowledgeBaseDeletionRepository {

    /**
     * 在租户范围内按任务标识读取删除任务。
     *
     * @param tenantId 任务所属租户
     * @param taskId 删除任务标识
     * @return 匹配的删除任务，不存在时为空
     */
    Optional<RagKnowledgeBaseDeleteTaskEntity> findByTaskId(String tenantId, String taskId);

    /**
     * 查询知识库当前唯一的非终态删除任务。
     *
     * @param tenantId 知识库所属租户
     * @param knowledgeBaseId 知识库标识
     * @return 当前非终态删除任务，不存在时为空
     */
    Optional<RagKnowledgeBaseDeleteTaskEntity> findByKnowledgeBaseId(String tenantId,
                                                                      String knowledgeBaseId);

    /**
     * 锁定知识库聚合后原子写入删除中状态、绑定停用和删除任务。
     *
     * @param tenantId 知识库所属租户
     * @param registration 已通过一致性校验的删除登记命令
     * @return 登记成功时返回 {@code true}；唯一键已存在时返回 {@code false}
     */
    boolean register(String tenantId, RagKnowledgeBaseDeleteRegistration registration);

    /**
     * 按预期版本号更新尚未领取的删除任务。
     *
     * @param tenantId 任务所属租户
     * @param task 更新后的删除任务
     * @param expectedRevision 更新前的预期版本号
     * @return 实际更新行数，0 表示状态或版本号已变化
     */
    int update(String tenantId, RagKnowledgeBaseDeleteTaskEntity task, long expectedRevision);

    /**
     * 全局扫描到期候选的最小投影，不返回租户业务正文。
     *
     * @param now 判断重试时间和租约是否到期的时刻
     * @param limit 单次扫描的最大候选数
     * @return 待尝试领取的租户与任务标识集合
     */
    List<RagKnowledgeBaseDeleteCandidate> listDueCandidates(Instant now, int limit);

    /**
     * 在指定租户内原子领取到期删除任务，并生成新的单调递增 fencing token。
     *
     * @param tenantId 任务所属租户
     * @param taskId 待领取的删除任务标识
     * @param leaseOwner 本次执行实例标识
     * @param now 领取时刻
     * @param leaseUntil 本次租约到期时刻
     * @return 领取成功后的任务；任务不可领取时为空
     */
    Optional<RagKnowledgeBaseDeleteTaskEntity> claim(String tenantId, String taskId,
                                                       String leaseOwner, Instant now,
                                                       Instant leaseUntil);

    /**
     * 按执行实例和 fencing token 续租，不改变业务版本号。
     *
     * @param tenantId 任务所属租户
     * @param taskId 删除任务标识
     * @param leaseOwner 当前执行实例标识
     * @param fencingToken 领取时获得的 fencing token
     * @param now 续租校验时刻
     * @param leaseUntil 新的租约到期时刻
     * @return 实际更新行数，0 表示任务执行权已变化
     */
    int heartbeat(String tenantId, String taskId, String leaseOwner, long fencingToken,
                  Instant now, Instant leaseUntil);

    /**
     * 同时校验业务版本号、租约和 fencing token 更新删除进度。
     *
     * @param tenantId 任务所属租户
     * @param task 包含新阶段与检查点的删除任务
     * @param expectedRevision 更新前的预期业务版本号
     * @param leaseOwner 当前执行实例标识
     * @param fencingToken 领取时获得的 fencing token
     * @param now 租约有效性校验时刻
     * @return 实际更新行数，0 表示版本或执行权已变化
     */
    int updateClaimed(String tenantId, RagKnowledgeBaseDeleteTaskEntity task,
                      long expectedRevision, String leaseOwner, long fencingToken, Instant now);

    /**
     * 在零残留验证后，在同一事务中把知识库和删除任务改为终态。
     *
     * @param tenantId 任务所属租户
     * @param taskId 删除任务标识
     * @param expectedTaskRevision 完成前的预期任务版本号
     * @param leaseOwner 当前执行实例标识
     * @param fencingToken 领取时获得的 fencing token
     * @param now 租约有效性与完成时间的校验时刻
     */
    void completeClaimed(String tenantId, String taskId, long expectedTaskRevision,
                         String leaseOwner, long fencingToken, Instant now);
}
