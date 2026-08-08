package cn.bugstack.ai.infrastructure.dao;

import cn.bugstack.ai.infrastructure.dao.po.WorkflowInvocationPO;
import org.apache.ibatis.annotations.Param;

/** 智能工作流外部调用账本 DAO。 */
public interface IWorkflowInvocationDao {
    /** 按幂等键登记外部调用，重复记录不覆盖首次调用。 */
    int insertIgnore(WorkflowInvocationPO invocation);

    /** 更新调用终态并保存下游请求标识。 */
    int finish(@Param("tenantId") String tenantId, @Param("invocationId") String invocationId,
               @Param("status") String status, @Param("downstreamRequestId") String downstreamRequestId);
}
