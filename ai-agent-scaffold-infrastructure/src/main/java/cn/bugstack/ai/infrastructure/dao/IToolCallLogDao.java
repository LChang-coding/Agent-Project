package cn.bugstack.ai.infrastructure.dao;

import cn.bugstack.ai.infrastructure.dao.po.ToolCallLogPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 工具调用日志 DAO。
 * <p>负责 `tool_call_log` 表的基础持久化操作。</p>
 */
@Mapper
public interface IToolCallLogDao {

    /**
     * 新增工具调用日志；参数是日志持久化对象；返回影响行数。
     */
    int insert(ToolCallLogPO toolCallLog);

    /**
     * 幂等新增 started 日志；参数是日志；返回影响行数。
     */
    int insertIgnore(ToolCallLogPO toolCallLog);

    /**
     * 按主键更新工具调用日志；参数是日志持久化对象；返回影响行数。
     */
    int updateById(ToolCallLogPO toolCallLog);

    /**
     * 按幂等键完成日志；参数是状态和执行结果；返回影响行数。
     */
    int finishByIdempotencyKey(@Param("idempotencyKey") String idempotencyKey,
                               @Param("outputJson") String outputJson,
                               @Param("status") String status,
                               @Param("errorType") String errorType,
                               @Param("errorMessage") String errorMessage,
                               @Param("costMs") Long costMs);

    /**
     * 按幂等键查询日志；参数是幂等键；返回日志或空。
     */
    ToolCallLogPO queryByIdempotencyKey(@Param("idempotencyKey") String idempotencyKey);

    /**
     * 按会话查询工具调用日志；参数是租户、用户和会话；返回调用日志列表。
     */
    List<ToolCallLogPO> queryListBySessionId(@Param("tenantId") String tenantId,
                                             @Param("userId") String userId,
                                             @Param("sessionId") String sessionId);
}
