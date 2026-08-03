package cn.bugstack.ai.infrastructure.adapter.repository;

import cn.bugstack.ai.domain.workflow.adapter.repository.IIntelligentWorkflowRunRepository;
import cn.bugstack.ai.domain.workflow.model.entity.IntelligentWorkflowRunEntity;
import cn.bugstack.ai.infrastructure.dao.IWorkflowRunEventDao;
import cn.bugstack.ai.infrastructure.dao.po.IntelligentWorkflowRunPO;
import org.springframework.stereotype.Repository;

/** 智能工作流运行扩展 MyBatis 仓储。 */
@Repository
public class IntelligentWorkflowRunRepository implements IIntelligentWorkflowRunRepository {
    private final IWorkflowRunEventDao dao;

    public IntelligentWorkflowRunRepository(IWorkflowRunEventDao dao) {
        this.dao = dao;
    }

    @Override
    public int insert(IntelligentWorkflowRunEntity run) {
        return dao.insertRun(toPO(run));
    }

    @Override
    public IntelligentWorkflowRunEntity query(String tenantId, String userId, String runId) {
        IntelligentWorkflowRunPO po = dao.queryRun(tenantId, userId, runId);
        return po == null ? null : toEntity(po);
    }

    @Override
    public int updateState(IntelligentWorkflowRunEntity run, long expectedRevision) {
        return dao.updateRunState(toPO(run), expectedRevision);
    }

    @Override
    public int cancelActive(String tenantId, String userId, String runId, java.time.LocalDateTime finishedAt) {
        return dao.cancelActiveRun(tenantId, userId, runId, finishedAt);
    }

    private IntelligentWorkflowRunPO toPO(IntelligentWorkflowRunEntity value) {
        IntelligentWorkflowRunPO po = new IntelligentWorkflowRunPO();
        po.setTenantId(value.getTenantId()); po.setUserId(value.getUserId()); po.setRunId(value.getRunId());
        po.setWorkflowId(value.getWorkflowId()); po.setWorkflowVersion(value.getWorkflowVersion());
        po.setDefinitionHash(value.getDefinitionHash()); po.setTraceId(value.getTraceId()); po.setStatus(value.getStatus());
        po.setCurrentNodeId(value.getCurrentNodeId()); po.setNextSequence(value.getNextSequence());
        po.setExecutedSteps(value.getExecutedSteps()); po.setUsedTokens(value.getUsedTokens());
        po.setMaxSteps(value.getMaxSteps()); po.setTokenBudget(value.getTokenBudget()); po.setVariablesJson(value.getVariablesJson());
        po.setRevision(value.getRevision()); po.setStartedAt(value.getStartedAt()); po.setFinishedAt(value.getFinishedAt());
        return po;
    }

    private IntelligentWorkflowRunEntity toEntity(IntelligentWorkflowRunPO po) {
        return IntelligentWorkflowRunEntity.builder().tenantId(po.getTenantId()).userId(po.getUserId()).runId(po.getRunId())
                .workflowId(po.getWorkflowId()).workflowVersion(po.getWorkflowVersion()).definitionHash(po.getDefinitionHash())
                .traceId(po.getTraceId()).status(po.getStatus()).currentNodeId(po.getCurrentNodeId())
                .nextSequence(po.getNextSequence()).executedSteps(po.getExecutedSteps()).usedTokens(po.getUsedTokens())
                .maxSteps(po.getMaxSteps()).tokenBudget(po.getTokenBudget()).variablesJson(po.getVariablesJson())
                .revision(po.getRevision()).startedAt(po.getStartedAt()).finishedAt(po.getFinishedAt()).build();
    }
}
