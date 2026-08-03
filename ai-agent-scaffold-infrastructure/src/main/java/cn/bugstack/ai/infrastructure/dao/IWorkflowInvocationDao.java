package cn.bugstack.ai.infrastructure.dao;

import cn.bugstack.ai.infrastructure.dao.po.WorkflowInvocationPO;
import org.apache.ibatis.annotations.Param;

/** 智能工作流外部调用账本 DAO。 */
public interface IWorkflowInvocationDao {
    int insertIgnore(WorkflowInvocationPO invocation);
    int finish(@Param("tenantId") String tenantId, @Param("invocationId") String invocationId,
               @Param("status") String status, @Param("downstreamRequestId") String downstreamRequestId);
}
