package cn.bugstack.ai.infrastructure.dao;

import cn.bugstack.ai.infrastructure.dao.po.ContextCompactionTaskPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** 上下文压缩任务 DAO。 */
@Mapper
public interface IContextCompactionTaskDao {
    /** 按任务唯一键幂等插入；重复请求返回 0。 */
    int insertIgnore(ContextCompactionTaskPO task);

    /** 按幂等任务键查询。 */
    ContextCompactionTaskPO queryByTaskKey(@Param("taskKey") String taskKey);

    /** 按公开任务 ID 查询。 */
    ContextCompactionTaskPO queryByTaskId(@Param("taskId") String taskId);

    /** 查询会话内仍可能改变记忆快照的任务。 */
    List<ContextCompactionTaskPO> queryUnfinished(@Param("tenantId") String tenantId,
                                                  @Param("userId") String userId,
                                                  @Param("sessionId") String sessionId);

    /** 查询会话最近一次压缩任务，不限定终态。 */
    ContextCompactionTaskPO queryLatest(@Param("tenantId") String tenantId,
                                        @Param("userId") String userId,
                                        @Param("sessionId") String sessionId);

    /** 仅从待处理态原子领取任务。 */
    int claim(@Param("taskId") String taskId);

    /** 仅将当前处理中任务推进为完成。 */
    int complete(@Param("taskId") String taskId);

    /** 记录可重试失败并释放任务。 */
    int retry(@Param("taskId") String taskId, @Param("errorMessage") String errorMessage);

    /** 记录不可再重试的死信终态。 */
    int dead(@Param("taskId") String taskId, @Param("errorMessage") String errorMessage);

    /** 将覆盖失效消息序列的任务标旧，阻止其摘要污染上下文。 */
    int staleOverlapping(@Param("tenantId") String tenantId,
                         @Param("userId") String userId,
                         @Param("sessionId") String sessionId,
                         @Param("runId") String runId,
                         @Param("minSequence") Integer minSequence,
                         @Param("maxSequence") Integer maxSequence,
                         @Param("reason") String reason);
}
