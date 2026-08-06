package cn.bugstack.ai.test.workflow;

import cn.bugstack.ai.domain.workflow.model.entity.WorkflowRouteIntentEntity;
import cn.bugstack.ai.domain.workflow.model.valobj.WorkflowRouteIntentStatus;
import cn.bugstack.ai.infrastructure.adapter.repository.WorkflowRouteIntentRepository;
import cn.bugstack.ai.infrastructure.dao.IWorkflowRouteIntentDao;
import cn.bugstack.ai.infrastructure.dao.po.WorkflowRouteIntentPO;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 路由意图仓储的领域映射与条件消费测试。 */
public class WorkflowRouteIntentRepositoryTest {

    @Test
    public void shouldMapAllResolvedRouteFactsWhenClaiming() {
        IWorkflowRouteIntentDao dao = mock(IWorkflowRouteIntentDao.class);
        when(dao.insertIgnore(any())).thenReturn(1);
        WorkflowRouteIntentRepository repository = new WorkflowRouteIntentRepository(dao);

        Assert.assertEquals(1, repository.claim(intent()));

        ArgumentCaptor<WorkflowRouteIntentPO> captor = ArgumentCaptor.forClass(WorkflowRouteIntentPO.class);
        verify(dao).insertIgnore(captor.capture());
        WorkflowRouteIntentPO value = captor.getValue();
        Assert.assertEquals("node-exec-1", value.getNodeExecutionId());
        Assert.assertEquals("正确", value.getRouteKey());
        Assert.assertEquals("正确", value.getNormalizedRouteKey());
        Assert.assertEquals("edge-1", value.getResolvedEdgeId());
        Assert.assertEquals("target-1", value.getResolvedTargetNodeId());
        Assert.assertEquals("PENDING", value.getStatus());
    }

    @Test
    public void shouldReadCurrentNodeAndConditionallyConsumePendingIntent() {
        IWorkflowRouteIntentDao dao = mock(IWorkflowRouteIntentDao.class);
        WorkflowRouteIntentPO po = new WorkflowRouteIntentPO();
        po.setTenantId("tenant-1");
        po.setRunId("run-1");
        po.setNodeExecutionId("node-exec-1");
        po.setStatus("PENDING");
        when(dao.queryByNode("tenant-1", "run-1", "node-exec-1")).thenReturn(po);
        when(dao.consume(eq("tenant-1"), eq("run-1"), eq("node-exec-1"), eq("PENDING"), any())).thenReturn(1);
        WorkflowRouteIntentRepository repository = new WorkflowRouteIntentRepository(dao);

        Assert.assertEquals(WorkflowRouteIntentStatus.PENDING,
                repository.queryByNode("tenant-1", "run-1", "node-exec-1").getStatus());
        Assert.assertEquals(1, repository.consume("tenant-1", "run-1", "node-exec-1", LocalDateTime.now()));
    }

    private WorkflowRouteIntentEntity intent() {
        return WorkflowRouteIntentEntity.builder().tenantId("tenant-1").userId("user-1").runId("run-1")
                .nodeExecutionId("node-exec-1").workflowId("workflow-1").workflowVersion(3)
                .definitionHash("a".repeat(64)).nodeId("node-1").routeKey("正确")
                .normalizedRouteKey("正确").resolvedEdgeId("edge-1").resolvedTargetNodeId("target-1")
                .reason("满足条件").functionCallId("call-1").source("MODEL_TOOL")
                .status(WorkflowRouteIntentStatus.PENDING).traceId("trace-1").build();
    }
}
