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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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

    @Test
    public void shouldRunTwoAtATimeAndWaitForWholeBatch() throws Exception {
        IScheduleRepository repository = mock(IScheduleRepository.class);
        CountDownLatch firstWaveStarted = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxActive = new AtomicInteger();
        ScheduleTaskHandler handler = new ScheduleTaskHandler() {
            @Override
            public String taskType() {
                return "agent_prompt";
            }

            @Override
            public String execute(ScheduleTaskContext context) throws Exception {
                int current = active.incrementAndGet();
                maxActive.accumulateAndGet(current, Math::max);
                firstWaveStarted.countDown();
                try {
                    if (!release.await(3, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("测试未释放调度 handler");
                    }
                    return "ok";
                } finally {
                    active.decrementAndGet();
                }
            }
        };
        when(repository.claimDueTask(any(), any(), any()))
                .thenReturn(task(1), task(2), task(3));
        when(repository.findConfig(any())).thenReturn(config());
        when(repository.beginExecution(any())).thenAnswer(invocation -> invocation.getArgument(0));
        SchedulerProperties properties = new SchedulerProperties();
        properties.setHeartbeatSeconds(60);
        ScheduleDispatcher dispatcher = new ScheduleDispatcher(repository, new CronScheduleSupport(), properties,
                List.of(handler));
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            Future<Integer> result = caller.submit(() -> dispatcher.dispatchBatch(3));
            assertTrue(firstWaveStarted.await(2, TimeUnit.SECONDS));
            assertEquals(2, maxActive.get());
            verify(repository, times(2)).claimDueTask(any(), any(), any());

            release.countDown();
            assertEquals(Integer.valueOf(3), result.get(3, TimeUnit.SECONDS));
            assertEquals(2, maxActive.get());
            assertEquals(0, active.get());
            verify(repository, times(3)).claimDueTask(any(), any(), any());
            verify(repository, times(3)).finishSuccess(any(), any(), any(), anyLong(), any(), anyLong(),
                    eq("ok"), any(), any());
        } finally {
            release.countDown();
            dispatcher.shutdown();
            caller.shutdownNow();
        }
    }

    @Test
    public void shouldNotClaimAfterShutdown() {
        IScheduleRepository repository = mock(IScheduleRepository.class);
        SchedulerProperties properties = new SchedulerProperties();
        ScheduleDispatcher dispatcher = new ScheduleDispatcher(repository, new CronScheduleSupport(), properties,
                List.of());

        dispatcher.shutdown();

        assertEquals(0, dispatcher.dispatchBatch(10));
        verify(repository, never()).claimDueTask(any(), any(), any());
    }

    @Test
    public void shouldInterruptRunningHandlerAndReleaseBatchOnShutdown() throws Exception {
        IScheduleRepository repository = mock(IScheduleRepository.class);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        ScheduleTaskHandler handler = new ScheduleTaskHandler() {
            @Override
            public String taskType() {
                return "agent_prompt";
            }

            @Override
            public String execute(ScheduleTaskContext context) throws Exception {
                started.countDown();
                try {
                    new CountDownLatch(1).await();
                    return "unexpected";
                } catch (InterruptedException e) {
                    interrupted.countDown();
                    throw e;
                }
            }
        };
        when(repository.claimDueTask(any(), any(), any())).thenReturn(task()).thenReturn(null);
        when(repository.findConfig(any())).thenReturn(config());
        when(repository.beginExecution(any())).thenAnswer(invocation -> invocation.getArgument(0));
        SchedulerProperties properties = new SchedulerProperties();
        properties.setHeartbeatSeconds(60);
        ScheduleDispatcher dispatcher = new ScheduleDispatcher(repository, new CronScheduleSupport(), properties,
                List.of(handler));
        ExecutorService callers = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> batch = callers.submit(() -> dispatcher.dispatchBatch(10));
            assertTrue(started.await(2, TimeUnit.SECONDS));

            Future<?> shutdown = callers.submit(dispatcher::shutdown);
            shutdown.get(2, TimeUnit.SECONDS);

            assertTrue(interrupted.await(1, TimeUnit.SECONDS));
            assertEquals(Integer.valueOf(1), batch.get(2, TimeUnit.SECONDS));
            verify(repository).finishFailure(any(), eq("task-1"), eq("lease-1"), eq(7L), any(), anyLong(),
                    any(), eq(false), eq(1), any(), any(), any());
            assertEquals(0, dispatcher.dispatchBatch(1));
        } finally {
            dispatcher.shutdown();
            callers.shutdownNow();
        }
    }

    private ScheduleTaskEntity task() {
        LocalDateTime planned = LocalDateTime.now().minusMinutes(1);
        return ScheduleTaskEntity.builder().tenantId("tenant-1").userId("user-1").configId("config-1")
                .taskId("task-1").businessKey("business-1").configVersion(3).cronExpr("0 * * * * *")
                .timezone("UTC").misfirePolicy("fire_once_now").maxRetries(3).nextFireTime(planned)
                .retryCount(0).leaseOwner("lease-1").fencingToken(7).build();
    }

    private ScheduleTaskEntity task(int index) {
        ScheduleTaskEntity task = task();
        task.setTaskId("task-" + index);
        task.setConfigId("config-" + index);
        task.setBusinessKey("business-" + index);
        task.setLeaseOwner("lease-" + index);
        task.setFencingToken((long) index);
        return task;
    }

    private ScheduleConfigEntity config() {
        return ScheduleConfigEntity.builder().tenantId("tenant-1").ownerUserId("user-1")
                .runAsUserId("user-1").runAsRoleCode("member").configId("config-1")
                .agentId("agent-1").taskType("agent_prompt").taskPayload("{\"message\":\"hello\"}")
                .enabled(true).status("active").build();
    }
}
