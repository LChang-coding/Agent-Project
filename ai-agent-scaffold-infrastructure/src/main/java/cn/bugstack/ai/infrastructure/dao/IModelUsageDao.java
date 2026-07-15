package cn.bugstack.ai.infrastructure.dao;

import cn.bugstack.ai.infrastructure.dao.po.ModelUsagePO;
import cn.bugstack.ai.infrastructure.dao.po.ModelUsageSummaryPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 模型用量 DAO。
 * <p>负责 `model_usage` 表的基础持久化操作。</p>
 */
@Mapper
public interface IModelUsageDao {

    /**
     * 新增模型用量记录。
     *
     * @param modelUsage 模型用量持久化对象
     * @return 影响行数
     */
    int insert(ModelUsagePO modelUsage);

    /**
     * 幂等写入模型用量终态。
     */
    int upsert(ModelUsagePO modelUsage);

    /**
     * 按主键更新模型用量记录。
     *
     * @param modelUsage 模型用量持久化对象
     * @return 影响行数
     */
    int updateById(ModelUsagePO modelUsage);

    /**
     * 按主键查询模型用量记录。
     *
     * @param id 主键ID
     * @return 模型用量持久化对象
     */
    ModelUsagePO queryById(@Param("id") Long id);

    /**
     * 按租户业务ID查询模型用量列表。
     *
     * @param tenantId 租户业务ID
     * @return 模型用量持久化对象列表
     */
    List<ModelUsagePO> queryListByTenantId(@Param("tenantId") String tenantId);

    /**
     * 按用户业务ID查询模型用量列表。
     *
     * @param userId 用户业务ID
     * @return 模型用量持久化对象列表
     */
    List<ModelUsagePO> queryListByUserId(@Param("userId") String userId);

    /**
     * 按会话业务ID查询模型用量列表。
     *
     * @param sessionId 会话业务ID
     * @return 模型用量持久化对象列表
     */
    List<ModelUsagePO> queryListBySessionId(@Param("sessionId") String sessionId);

    ModelUsagePO queryLatest(@Param("tenantId") String tenantId, @Param("userId") String userId,
                             @Param("sessionId") String sessionId);

    ModelUsageSummaryPO summarizeSession(@Param("tenantId") String tenantId, @Param("userId") String userId,
                                         @Param("sessionId") String sessionId, @Param("runId") String runId);

    ModelUsageSummaryPO summarizeRecent(@Param("tenantId") String tenantId, @Param("userId") String userId,
                                        @Param("days") int days);

    int cancelRunning(@Param("tenantId") String tenantId, @Param("userId") String userId,
                      @Param("sessionId") String sessionId, @Param("runId") String runId,
                      @Param("reason") String reason);
}
