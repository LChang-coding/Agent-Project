package cn.bugstack.ai.infrastructure.adapter.repository;

import cn.bugstack.ai.domain.workflow.adapter.repository.IWorkflowRouteIntentRepository;
import cn.bugstack.ai.domain.workflow.model.entity.WorkflowRouteIntentEntity;
import cn.bugstack.ai.domain.workflow.model.valobj.WorkflowRouteIntentStatus;
import cn.bugstack.ai.infrastructure.dao.IWorkflowRouteIntentDao;
import cn.bugstack.ai.infrastructure.dao.po.WorkflowRouteIntentPO;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;

/** 工作流路由意图 MyBatis 仓储。 */
@Repository
public class WorkflowRouteIntentRepository implements IWorkflowRouteIntentRepository {
    /** 读写模型提交的路由意图及其消费状态。 */
    private final IWorkflowRouteIntentDao dao;

    /** 注入路由意图 DAO。 */
    public WorkflowRouteIntentRepository(IWorkflowRouteIntentDao dao) {
        this.dao = dao;
    }

    /**
     * 以数据库唯一键登记路由意图。
     * 重复函数调用不会覆盖第一次已经登记的权威意图。
     */
    @Override
    public int claim(WorkflowRouteIntentEntity intent) {
        return dao.insertIgnore(toPO(intent));
    }

    /** 按运行和节点执行标识查询该节点登记的路由意图。 */
    @Override
    public WorkflowRouteIntentEntity queryByNode(String tenantId, String runId, String nodeExecutionId) {
        return toEntity(dao.queryByNode(tenantId, runId, nodeExecutionId));
    }

    /** 按模型函数调用标识查询意图，用于识别重复工具调用。 */
    @Override
    public WorkflowRouteIntentEntity queryByFunctionCall(String tenantId, String functionCallId) {
        return toEntity(dao.queryByFunctionCall(tenantId, functionCallId));
    }

    /** 仅把 PENDING 意图原子推进为已消费状态。 */
    @Override
    public int consume(String tenantId, String runId, String nodeExecutionId, LocalDateTime consumedAt) {
        return dao.consume(tenantId, runId, nodeExecutionId, WorkflowRouteIntentStatus.PENDING.name(), consumedAt);
    }

    /** 将领域意图及解析后的目标信息完整复制到持久化对象。 */
    private WorkflowRouteIntentPO toPO(WorkflowRouteIntentEntity value) {
        WorkflowRouteIntentPO po = new WorkflowRouteIntentPO();
        po.setTenantId(value.getTenantId()); po.setUserId(value.getUserId()); po.setRunId(value.getRunId());
        po.setNodeExecutionId(value.getNodeExecutionId()); po.setWorkflowId(value.getWorkflowId());
        po.setWorkflowVersion(value.getWorkflowVersion()); po.setDefinitionHash(value.getDefinitionHash());
        po.setNodeId(value.getNodeId()); po.setRouteKey(value.getRouteKey());
        po.setNormalizedRouteKey(value.getNormalizedRouteKey()); po.setResolvedEdgeId(value.getResolvedEdgeId());
        po.setResolvedTargetNodeId(value.getResolvedTargetNodeId()); po.setReason(value.getReason());
        po.setFunctionCallId(value.getFunctionCallId()); po.setSource(value.getSource());
        po.setStatus(value.getStatus().name()); po.setTraceId(value.getTraceId()); po.setConsumedAt(value.getConsumedAt());
        return po;
    }

    /** 将数据库记录恢复为领域意图；未查询到记录时返回空值。 */
    private WorkflowRouteIntentEntity toEntity(WorkflowRouteIntentPO value) {
        if (value == null) return null;
        return WorkflowRouteIntentEntity.builder().tenantId(value.getTenantId()).userId(value.getUserId())
                .runId(value.getRunId()).nodeExecutionId(value.getNodeExecutionId()).workflowId(value.getWorkflowId())
                .workflowVersion(value.getWorkflowVersion()).definitionHash(value.getDefinitionHash())
                .nodeId(value.getNodeId()).routeKey(value.getRouteKey()).normalizedRouteKey(value.getNormalizedRouteKey())
                .resolvedEdgeId(value.getResolvedEdgeId()).resolvedTargetNodeId(value.getResolvedTargetNodeId())
                .reason(value.getReason()).functionCallId(value.getFunctionCallId()).source(value.getSource())
                .status(WorkflowRouteIntentStatus.valueOf(value.getStatus())).traceId(value.getTraceId())
                .consumedAt(value.getConsumedAt()).build();
    }
}
