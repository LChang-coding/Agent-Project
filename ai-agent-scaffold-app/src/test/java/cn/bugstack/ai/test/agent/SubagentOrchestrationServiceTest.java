package cn.bugstack.ai.test.agent;

import cn.bugstack.ai.domain.agent.adapter.repository.IAgentTenantOverrideRepository;
import cn.bugstack.ai.domain.agent.adapter.repository.ISubagentTaskRepository;
import cn.bugstack.ai.domain.agent.model.entity.SubagentTaskEntity;
import cn.bugstack.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import cn.bugstack.ai.domain.agent.model.valobj.properties.AiAgentAutoConfigProperties;
import cn.bugstack.ai.domain.agent.service.AgentAvailabilityService;
import cn.bugstack.ai.domain.agent.service.SubagentOrchestrationService;
import cn.bugstack.ai.types.exception.AppException;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;

/** 主 Agent 委派边界与幂等测试。 */
public class SubagentOrchestrationServiceTest {
    @Test
    public void shouldCreateOnlyWhitelistedTasksAndReplayFunctionCall() {
        ISubagentTaskRepository tasks = Mockito.mock(ISubagentTaskRepository.class);
        IAgentTenantOverrideRepository overrides = Mockito.mock(IAgentTenantOverrideRepository.class);
        Mockito.when(overrides.query("tenant", "research")).thenReturn(null);
        Mockito.when(tasks.queryByFunctionCall("tenant", "root-run", "call-1")).thenReturn(List.of());
        Mockito.when(tasks.createBatchAndEnqueue(ArgumentMatchers.anyList())).thenAnswer(value -> value.<List<?>>getArgument(0).size());
        SubagentOrchestrationService service = new SubagentOrchestrationService(tasks,
                new AgentAvailabilityService(overrides, properties()));

        List<SubagentTaskEntity> created = service.delegate(supervisor(), "call-1",
                List.of(new SubagentOrchestrationService.TaskRequest("research", "调研方案")));

        Assert.assertEquals(1, created.size());
        Assert.assertEquals("root-run", created.get(0).getParentRunId());
        Assert.assertEquals("READY", created.get(0).getStatus().name());
        Assert.assertEquals(Boolean.FALSE, created.get(0).getSummaryTruncated());

        Mockito.when(tasks.queryByFunctionCall("tenant", "root-run", "call-1")).thenReturn(created);
        Assert.assertSame(created, service.delegate(supervisor(), "call-1",
                List.of(new SubagentOrchestrationService.TaskRequest("research", "不会重复创建"))));
        Mockito.verify(tasks, Mockito.times(1)).createBatchAndEnqueue(ArgumentMatchers.anyList());
    }

    @Test
    public void shouldRejectAgentOutsideTrustedWhitelist() {
        ISubagentTaskRepository tasks = Mockito.mock(ISubagentTaskRepository.class);
        Mockito.when(tasks.queryByFunctionCall(ArgumentMatchers.anyString(), ArgumentMatchers.anyString(),
                ArgumentMatchers.anyString())).thenReturn(List.of());
        SubagentOrchestrationService service = new SubagentOrchestrationService(tasks,
                new AgentAvailabilityService(Mockito.mock(IAgentTenantOverrideRepository.class), properties()));
        try {
            service.delegate(supervisor(), "call-2",
                    List.of(new SubagentOrchestrationService.TaskRequest("planning", "越权")));
            Assert.fail("白名单外 Agent 必须拒绝");
        } catch (AppException exception) {
            Assert.assertEquals("SUBAGENT_TASK_INVALID", exception.getCode());
        }
    }

    @Test
    public void shouldUseStableTaskIdsForConcurrentFunctionCallRetries() {
        ISubagentTaskRepository tasks = Mockito.mock(ISubagentTaskRepository.class);
        Mockito.when(tasks.queryByFunctionCall("tenant", "root-run", "call-stable")).thenReturn(List.of());
        Mockito.when(tasks.createBatchAndEnqueue(ArgumentMatchers.anyList()))
                .thenAnswer(value -> value.<List<?>>getArgument(0).size());
        SubagentOrchestrationService service = new SubagentOrchestrationService(tasks,
                new AgentAvailabilityService(Mockito.mock(IAgentTenantOverrideRepository.class), properties()));

        List<SubagentTaskEntity> first = service.delegate(supervisor(), "call-stable",
                List.of(new SubagentOrchestrationService.TaskRequest("research", "same request")));
        List<SubagentTaskEntity> retry = service.delegate(supervisor(), "call-stable",
                List.of(new SubagentOrchestrationService.TaskRequest("research", "same request")));

        Assert.assertEquals(first.get(0).getTaskId(), retry.get(0).getTaskId());
    }

    @Test
    public void shouldReplayConcurrentWinnerWhenStableTaskInsertConflicts() {
        ISubagentTaskRepository tasks = Mockito.mock(ISubagentTaskRepository.class);
        SubagentTaskEntity winner = SubagentTaskEntity.builder().tenantId("tenant").parentRunId("root-run")
                .taskId("winner").childAgentId("research").status(cn.bugstack.ai.domain.agent.model.valobj.SubagentTaskStatus.READY)
                .build();
        Mockito.when(tasks.queryByFunctionCall("tenant", "root-run", "call-race"))
                .thenReturn(List.of(), List.of(winner));
        Mockito.when(tasks.createBatchAndEnqueue(ArgumentMatchers.anyList())).thenReturn(0);
        SubagentOrchestrationService service = new SubagentOrchestrationService(tasks,
                new AgentAvailabilityService(Mockito.mock(IAgentTenantOverrideRepository.class), properties()));

        List<SubagentTaskEntity> replay = service.delegate(supervisor(), "call-race",
                List.of(new SubagentOrchestrationService.TaskRequest("research", "race request")));

        Assert.assertEquals(List.of(winner), replay);
        Mockito.verify(tasks, Mockito.times(2)).queryByFunctionCall("tenant", "root-run", "call-race");
    }

    private SubagentOrchestrationService.TrustedSupervisor supervisor() {
        return new SubagentOrchestrationService.TrustedSupervisor("tenant", "user", "root-run", "session",
                "supervisor", "SUPERVISOR", List.of("research"), "trace");
    }

    private AiAgentAutoConfigProperties properties() {
        AiAgentConfigTableVO.Agent agent = new AiAgentConfigTableVO.Agent();
        agent.setAgentId("research"); agent.setAgentName("Research");
        AiAgentConfigTableVO table = new AiAgentConfigTableVO(); table.setAgent(agent);
        AiAgentAutoConfigProperties properties = new AiAgentAutoConfigProperties();
        properties.setTables(Map.of("research", table)); return properties;
    }
}
