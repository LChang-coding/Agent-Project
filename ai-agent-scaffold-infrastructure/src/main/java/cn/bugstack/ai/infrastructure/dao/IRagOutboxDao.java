package cn.bugstack.ai.infrastructure.dao;

import cn.bugstack.ai.infrastructure.dao.po.RagOutboxCandidatePO;
import cn.bugstack.ai.infrastructure.dao.po.RagOutboxPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 租户 RAG 事务 Outbox DAO。
 */
@Mapper
public interface IRagOutboxDao {

    /** 全局扫描到期事件标识；不返回业务载荷。 */
    List<RagOutboxCandidatePO> queryDueCandidates(@Param("now") LocalDateTime now,
                                                  @Param("limit") int limit);

    /** 与业务任务在同一事务中新增待发布事件。 */
    int insert(RagOutboxPO outbox);

    /** 按租户和事件 ID 查询。 */
    RagOutboxPO queryByTenantAndEventId(@Param("tenantId") String tenantId,
                                       @Param("eventId") String eventId);

    /** 按租户和任务 ID 查询事件列表。 */
    List<RagOutboxPO> queryListByTenantAndTaskId(@Param("tenantId") String tenantId,
                                                 @Param("taskId") String taskId);

    /** 原子领取指定租户事件并递增尝试次数和栅栏令牌。 */
    int claimDue(@Param("tenantId") String tenantId,
                 @Param("eventId") String eventId,
                 @Param("leaseOwner") String leaseOwner,
                 @Param("now") LocalDateTime now,
                 @Param("leaseUntil") LocalDateTime leaseUntil);

    /** 当前栅栏持有者在租约到期前续租。 */
    int renewLease(@Param("tenantId") String tenantId,
                   @Param("eventId") String eventId,
                   @Param("leaseOwner") String leaseOwner,
                   @Param("fencingToken") long fencingToken,
                   @Param("now") LocalDateTime now,
                   @Param("leaseUntil") LocalDateTime leaseUntil);

    /** 当前租约持有者确认 Kafka 已发布。 */
    int markPublished(@Param("tenantId") String tenantId,
                      @Param("eventId") String eventId,
                      @Param("leaseOwner") String leaseOwner,
                      @Param("fencingToken") long fencingToken,
                      @Param("publishedAt") LocalDateTime publishedAt);

    /** 当前租约持有者释放事件并安排下一次重试。 */
    int markRetrying(@Param("tenantId") String tenantId,
                     @Param("eventId") String eventId,
                     @Param("leaseOwner") String leaseOwner,
                     @Param("fencingToken") long fencingToken,
                     @Param("now") LocalDateTime now,
                     @Param("nextRetryAt") LocalDateTime nextRetryAt,
                     @Param("errorMessage") String errorMessage);

    /** 当前租约持有者将不可恢复事件推进到死信态。 */
    int markDead(@Param("tenantId") String tenantId,
                 @Param("eventId") String eventId,
                 @Param("leaseOwner") String leaseOwner,
                 @Param("fencingToken") long fencingToken,
                 @Param("now") LocalDateTime now,
                 @Param("errorMessage") String errorMessage);

    /** 将达到最大尝试次数且未被领取的事件推进到死信态。 */
    int markExhaustedDead(@Param("tenantId") String tenantId,
                          @Param("eventId") String eventId,
                          @Param("errorMessage") String errorMessage);
}
