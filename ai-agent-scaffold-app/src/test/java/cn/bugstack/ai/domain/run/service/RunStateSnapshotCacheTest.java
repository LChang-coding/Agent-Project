package cn.bugstack.ai.domain.run.service;

import cn.bugstack.ai.domain.asset.service.AssetService;
import cn.bugstack.ai.domain.context.service.ContextInvalidationService;
import cn.bugstack.ai.domain.run.adapter.repository.IChatRunRepository;
import cn.bugstack.ai.domain.run.model.ChatRunEntity;
import cn.bugstack.ai.domain.run.model.RunStatus;
import cn.bugstack.ai.domain.session.model.entity.ChatSessionEntity;
import cn.bugstack.ai.domain.session.service.SessionDomain;
import cn.bugstack.ai.domain.usage.service.ModelUsageService;
import cn.bugstack.ai.types.exception.AppException;
import org.junit.Assert;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 运行状态只读快照测试。
 */
public class RunStateSnapshotCacheTest {

    @Test
    public void shouldShareOneDatabaseReadAcrossHighFrequencyChecks() {
        AtomicLong now = new AtomicLong();
        IChatRunRepository repository = mock(IChatRunRepository.class);
        when(repository.query("tenant-1", "user-1", "run-1")).thenReturn(run(RunStatus.RUNNING, 1L));
        RunControlService service = service(repository, mock(SessionDomain.class),
                new RunStateSnapshotCache(200L, 16, now::get));

        for (int i = 0; i < 100; i++) {
            service.requireExecutable("tenant-1", "user-1", "run-1", 1L);
            Assert.assertFalse(service.cancelled("tenant-1", "user-1", "run-1"));
        }

        verify(repository, times(1)).query("tenant-1", "user-1", "run-1");
    }

    @Test
    public void shouldReloadAfterTtlExpires() {
        AtomicLong now = new AtomicLong();
        IChatRunRepository repository = mock(IChatRunRepository.class);
        when(repository.query("tenant-1", "user-1", "run-1")).thenReturn(run(RunStatus.RUNNING, 1L));
        RunControlService service = service(repository, mock(SessionDomain.class),
                new RunStateSnapshotCache(200L, 16, now::get));

        service.requireExecutable("tenant-1", "user-1", "run-1", 1L);
        now.set(201L);
        Assert.assertFalse(service.cancelled("tenant-1", "user-1", "run-1"));

        verify(repository, times(2)).query("tenant-1", "user-1", "run-1");
    }

    @Test
    public void shouldInvalidateImmediatelyAfterLocalRevisionChange() {
        AtomicLong now = new AtomicLong();
        IChatRunRepository repository = mock(IChatRunRepository.class);
        SessionDomain sessionDomain = mock(SessionDomain.class);
        ChatRunEntity revisionOne = run(RunStatus.RUNNING, 1L);
        ChatRunEntity revisionTwo = run(RunStatus.RUNNING, 2L);
        when(repository.query("tenant-1", "user-1", "run-1"))
                .thenReturn(revisionOne, revisionOne, revisionTwo, revisionTwo);
        when(sessionDomain.assertSessionAccess("tenant-1", "user-1", "session-1", null))
                .thenReturn(ChatSessionEntity.builder().tenantId("tenant-1").userId("user-1")
                        .sessionId("session-1").contextRevision(2L).build());
        when(repository.updateContextRevision("tenant-1", "user-1", "run-1", 2L, 0)).thenReturn(1);
        RunControlService service = service(repository, sessionDomain,
                new RunStateSnapshotCache(200L, 16, now::get));

        service.requireExecutable("tenant-1", "user-1", "run-1", 1L);
        service.refreshContextRevision("tenant-1", "user-1", "run-1");
        ChatRunEntity refreshed = service.requireExecutable("tenant-1", "user-1", "run-1", 2L);

        Assert.assertEquals(Long.valueOf(2L), refreshed.getCurrentContextRevision());
        verify(repository, times(4)).query("tenant-1", "user-1", "run-1");
    }

    @Test
    public void shouldInvalidateImmediatelyAfterLocalCancellation() {
        AtomicLong now = new AtomicLong();
        IChatRunRepository repository = mock(IChatRunRepository.class);
        SessionDomain sessionDomain = mock(SessionDomain.class);
        ChatRunEntity running = run(RunStatus.RUNNING, 1L);
        AtomicReference<ChatRunEntity> stored = new AtomicReference<>(running);
        when(repository.query("tenant-1", "user-1", "run-1")).thenAnswer(invocation -> stored.get());
        when(repository.lock("tenant-1", "user-1", "run-1")).thenReturn(running);
        when(repository.transition(eq("tenant-1"), eq("user-1"), eq("run-1"), eq(RunStatus.RUNNING),
                eq(RunStatus.CANCEL_REQUESTED), eq(0), eq("用户取消"), any(), isNull()))
                .thenAnswer(invocation -> {
                    stored.set(run(RunStatus.CANCEL_REQUESTED, 1L, 1));
                    return 1;
                });
        when(sessionDomain.queryRunMessages("tenant-1", "user-1", "session-1", "run-1"))
                .thenReturn(java.util.List.of());
        when(sessionDomain.incrementContextRevision("tenant-1", "user-1", "session-1")).thenReturn(2L);
        when(repository.updateContextRevision("tenant-1", "user-1", "run-1", 2L, 1)).thenAnswer(invocation -> {
            stored.set(run(RunStatus.CANCEL_REQUESTED, 2L, 2));
            return 1;
        });
        when(repository.transition(eq("tenant-1"), eq("user-1"), eq("run-1"),
                eq(RunStatus.CANCEL_REQUESTED), eq(RunStatus.CANCELLED), eq(2), eq("用户取消"), any(), any()))
                .thenAnswer(invocation -> {
                    stored.set(run(RunStatus.CANCELLED, 2L, 3));
                    return 1;
                });
        RunControlService service = service(repository, sessionDomain,
                new RunStateSnapshotCache(200L, 16, now::get));

        service.requireExecutable("tenant-1", "user-1", "run-1", 1L);
        service.cancel("tenant-1", "user-1", "run-1", "用户取消");

        Assert.assertTrue(service.cancelled("tenant-1", "user-1", "run-1"));
        verify(repository, times(4)).query("tenant-1", "user-1", "run-1");
    }

    @Test
    public void shouldKeepToolAuthorizationOnDatabaseRowLock() {
        AtomicLong now = new AtomicLong();
        IChatRunRepository repository = mock(IChatRunRepository.class);
        when(repository.query("tenant-1", "user-1", "run-1")).thenReturn(run(RunStatus.RUNNING, 1L));
        when(repository.lock("tenant-1", "user-1", "run-1")).thenReturn(run(RunStatus.CANCELLED, 1L));
        RunControlService service = service(repository, mock(SessionDomain.class),
                new RunStateSnapshotCache(200L, 16, now::get));
        service.requireExecutable("tenant-1", "user-1", "run-1", 1L);

        try {
            service.authorizeToolDispatch("tenant-1", "user-1", "run-1", 1L);
            Assert.fail("数据库锁中已取消的运行必须拒绝工具外发");
        } catch (AppException expected) {
            Assert.assertEquals("RUN_NOT_EXECUTABLE", expected.getCode());
        }
        verify(repository).lock("tenant-1", "user-1", "run-1");
        verify(repository, times(1)).query("tenant-1", "user-1", "run-1");
    }

    @Test
    public void shouldEnforceCapacityAndRecycleExpiredEntries() {
        AtomicLong now = new AtomicLong();
        RunStateSnapshotCache cache = new RunStateSnapshotCache(100L, 2, now::get);
        cache.get("tenant-1", "user-1", "run-1", () -> run("run-1"));
        cache.get("tenant-1", "user-1", "run-2", () -> run("run-2"));
        cache.get("tenant-1", "user-1", "run-3", () -> run("run-3"));
        Assert.assertEquals(2, cache.size());

        now.set(101L);
        cache.get("tenant-1", "user-1", "run-4", () -> run("run-4"));
        Assert.assertEquals(1, cache.size());
    }

    private RunControlService service(IChatRunRepository repository, SessionDomain sessionDomain,
                                      RunStateSnapshotCache cache) {
        return new RunControlService(repository, sessionDomain, mock(ActiveRunRegistry.class),
                mock(ContextInvalidationService.class), mock(ModelUsageService.class), mock(AssetService.class), cache);
    }

    private ChatRunEntity run(RunStatus status, Long revision) {
        return run(status, revision, 0);
    }

    private ChatRunEntity run(RunStatus status, Long revision, int version) {
        return ChatRunEntity.builder().runId("run-1").tenantId("tenant-1").userId("user-1")
                .sessionId("session-1").status(status).version(version).currentContextRevision(revision).build();
    }

    private ChatRunEntity run(String runId) {
        return ChatRunEntity.builder().runId(runId).tenantId("tenant-1").userId("user-1")
                .sessionId("session-1").status(RunStatus.RUNNING).version(0).build();
    }
}
