package cn.bugstack.ai.test.schedule;

import cn.bugstack.ai.domain.schedule.adapter.IScheduleRepository;
import cn.bugstack.ai.domain.schedule.model.ScheduleConfigEntity;
import cn.bugstack.ai.domain.schedule.model.ScheduleExecutionEntity;
import cn.bugstack.ai.domain.schedule.model.ScheduleTaskEntity;
import cn.bugstack.ai.domain.schedule.service.CronScheduleSupport;
import cn.bugstack.ai.domain.schedule.service.ScheduleDispatcher;
import cn.bugstack.ai.domain.schedule.service.ScheduleTaskContext;
import cn.bugstack.ai.domain.schedule.service.ScheduleTaskHandler;
import cn.bugstack.ai.domain.schedule.service.SchedulerProperties;
import cn.bugstack.ai.types.context.TenantContextHolder;
import org.junit.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 派发租约、身份和结果推进测试。
 */
public class ScheduleDispatcherTest {

    @Test
    public void shouldExecuteWithPersistedIdentityAndCommitWithFence() throws Exception {
        IScheduleRepository repository = mock(IScheduleRepository.class);
        ScheduleTaskHandler handler = mock(ScheduleTaskHandler.class);
        when(handler.taskType()).thenReturn("agent_prompt");
        when(handler.execute(any(ScheduleTaskContext.class))).thenAnswer(invocation -> {
            assertEquals("tenant-1", TenantContextHolder.getTenantId());
            assertEquals("user-1", TenantContextHolder.getUserId());
            assertEquals("member", TenantContextHolder.getRoleCode());
            return "[\"ok\"]";
        });
        ScheduleTaskEntity task = task();
        when(repository.claimDueTask(any(), any(), any())).thenReturn(task).thenReturn(null);
        when(repository.findConfig("config-1")).thenReturn(config());
        when(repository.beginExecution(any())).thenAnswer(invocation -> invocation.getArgument(0));
        SchedulerProperties properties = new SchedulerProperties();
        properties.setHeartbeatSeconds(60);
        ScheduleDispatcher dispatcher = new ScheduleDispatcher(repository, new CronScheduleSupport(), properties,
                List.of(handler));

        assertEquals(1, dispatcher.dispatchBatch(10));

        verify(repository).finishSuccess(any(), eq("task-1"), eq("lease-1"), eq(7L), any(), anyLong(),
                eq("[\"ok\"]"), eq(task.getNextFireTime()), any());
        assertNull(TenantContextHolder.get());
        dispatcher.shutdown();
    }

    private ScheduleTaskEntity task() {
        LocalDateTime planned = LocalDateTime.now().minusMinutes(1);
        return ScheduleTaskEntity.builder().tenantId("tenant-1").userId("user-1").configId("config-1")
                .taskId("task-1").businessKey("business-1").configVersion(3).cronExpr("0 * * * * *")
                .timezone("UTC").misfirePolicy("fire_once_now").maxRetries(3).nextFireTime(planned)
                .retryCount(0).leaseOwner("lease-1").fencingToken(7).build();
    }

    private ScheduleConfigEntity config() {
        return ScheduleConfigEntity.builder().tenantId("tenant-1").ownerUserId("user-1")
                .runAsUserId("user-1").runAsRoleCode("member").configId("config-1")
                .agentId("agent-1").taskType("agent_prompt").taskPayload("{\"message\":\"hello\"}")
                .enabled(true).status("active").build();
    }
}
