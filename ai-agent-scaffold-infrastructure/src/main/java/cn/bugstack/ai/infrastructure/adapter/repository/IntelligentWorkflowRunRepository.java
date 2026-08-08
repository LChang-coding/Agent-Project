package cn.bugstack.ai.infrastructure.adapter.repository;

import cn.bugstack.ai.domain.workflow.adapter.repository.IIntelligentWorkflowRunRepository;
import cn.bugstack.ai.domain.workflow.model.entity.IntelligentWorkflowRunEntity;
import cn.bugstack.ai.infrastructure.dao.IWorkflowRunEventDao;
import cn.bugstack.ai.infrastructure.dao.po.IntelligentWorkflowRunPO;
import org.springframework.stereotype.Repository;

/** 智能工作流运行扩展 MyBatis 仓储。 */
@Repository
public class IntelligentWorkflowRunRepository implements IIntelligentWorkflowRunRepository {
    /** 保存智能工作流运行和事件的 MyBatis DAO。 */
    private final IWorkflowRunEventDao dao;

    /** 注入运行 DAO，仓储只负责领域实体与持久化对象之间的转换。 */
    public IntelligentWorkflowRunRepository(IWorkflowRunEventDao dao) {
        this.dao = dao;
    }

    /** 新建运行快照；数据库唯一约束负责拒绝重复 runId。 */
    @Override
    public int insert(IntelligentWorkflowRunEntity run) {
        return dao.insertRun(toPO(run));
    }

    /** 在可信租户和用户范围内查询运行，不存在时返回空值。 */
    @Override
    public IntelligentWorkflowRunEntity query(String tenantId, String userId, String runId) {
        IntelligentWorkflowRunPO po = dao.queryRun(tenantId, userId, runId);
        return po == null ? null : toEntity(po);
    }

    /** 按预期 revision 更新运行状态，返回值用于识别并发修改。 */
    @Override
    public int updateState(IntelligentWorkflowRunEntity run, long expectedRevision) {
        return dao.updateRunState(toPO(run), expectedRevision);
    }

    /** 仅把仍处于活动状态的运行推进到取消终态。 */
    @Override
    public int cancelActive(String tenantId, String userId, String runId, java.time.LocalDateTime finishedAt) {
        return dao.cancelActiveRun(tenantId, userId, runId, finishedAt);
    }

    /** 将领域运行的状态、预算和进度完整复制到持久化对象。 */
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

    /** 将数据库快照恢复为可供领域服务继续判断的运行实体。 */
    private IntelligentWorkflowRunEntity toEntity(IntelligentWorkflowRunPO po) {
        return IntelligentWorkflowRunEntity.builder().tenantId(po.getTenantId()).userId(po.getUserId()).runId(po.getRunId())
                .workflowId(po.getWorkflowId()).workflowVersion(po.getWorkflowVersion()).definitionHash(po.getDefinitionHash())
                .traceId(po.getTraceId()).status(po.getStatus()).currentNodeId(po.getCurrentNodeId())
                .nextSequence(po.getNextSequence()).executedSteps(po.getExecutedSteps()).usedTokens(po.getUsedTokens())
                .maxSteps(po.getMaxSteps()).tokenBudget(po.getTokenBudget()).variablesJson(po.getVariablesJson())
                .revision(po.getRevision()).startedAt(po.getStartedAt()).finishedAt(po.getFinishedAt()).build();
    }
}
