package cn.bugstack.ai.infrastructure.dao;

import cn.bugstack.ai.infrastructure.dao.po.ContextCompactionTaskPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** 上下文压缩任务 DAO。 */
@Mapper
public interface IContextCompactionTaskDao {
    int insertIgnore(ContextCompactionTaskPO task);
    ContextCompactionTaskPO queryByTaskKey(@Param("taskKey") String taskKey);
    ContextCompactionTaskPO queryByTaskId(@Param("taskId") String taskId);
    List<ContextCompactionTaskPO> queryUnfinished(@Param("tenantId") String tenantId,
                                                  @Param("userId") String userId,
                                                  @Param("sessionId") String sessionId);
    int claim(@Param("taskId") String taskId);
    int complete(@Param("taskId") String taskId);
    int retry(@Param("taskId") String taskId, @Param("errorMessage") String errorMessage);
    int dead(@Param("taskId") String taskId, @Param("errorMessage") String errorMessage);
}
