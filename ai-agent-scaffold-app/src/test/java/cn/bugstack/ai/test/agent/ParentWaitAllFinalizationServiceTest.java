package cn.bugstack.ai.test.agent;

import cn.bugstack.ai.domain.agent.adapter.repository.IParentResumeRepository;
import cn.bugstack.ai.domain.agent.service.ParentWaitAllFinalizationService;
import cn.bugstack.ai.domain.run.model.ChatRunEntity;
import cn.bugstack.ai.domain.run.model.RunStatus;
import cn.bugstack.ai.domain.run.service.RunControlService;
import cn.bugstack.ai.types.exception.AppException;
import org.junit.Test;
import org.mockito.InOrder;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 主 Agent 自身输出与 WAIT_ALL 父恢复屏障的事务收口测试。 */
public class ParentWaitAllFinalizationServiceTest {

    @Test
    public void shouldLeaveOrdinaryRunToNormalAssistantPersistence() {
        IParentResumeRepository repository = mock(IParentResumeRepository.class);
        RunControlService runControlService = mock(RunControlService.class);
        ParentWaitAllFinalizationService service = new ParentWaitAllFinalizationService(repository, runControlService);
        when(repository.isAwaitingSummary("tenant-1", "run-1")).thenReturn(false);

        assertFalse(service.completeAsDraftIfWaiting("tenant-1", "user-1", "run-1", "主 Agent 草稿"));

        verify(runControlService, never()).complete(any(), any(), any());
        verify(repository, never()).markParentReady(any(), any(), any(), any());
    }

    @Test
    public void shouldCompleteOriginalRunBeforeOpeningParentReadyBarrier() {
        IParentResumeRepository repository = mock(IParentResumeRepository.class);
        RunControlService runControlService = mock(RunControlService.class);
        ParentWaitAllFinalizationService service = new ParentWaitAllFinalizationService(repository, runControlService);
        when(repository.isAwaitingSummary("tenant-1", "run-1")).thenReturn(true);
        when(runControlService.complete("tenant-1", "user-1", "run-1"))
                .thenReturn(terminal(RunStatus.COMPLETED));
        when(repository.markParentReady(eq("tenant-1"), eq("run-1"), eq("主 Agent 草稿"), any()))
                .thenReturn(true);

        assertTrue(service.completeAsDraftIfWaiting("tenant-1", "user-1", "run-1", "主 Agent 草稿"));

        InOrder order = inOrder(runControlService, repository);
        order.verify(runControlService).complete("tenant-1", "user-1", "run-1");
        order.verify(repository).markParentReady(eq("tenant-1"), eq("run-1"), eq("主 Agent 草稿"), any());
    }

    @Test
    public void shouldPersistFailureAsHiddenDraftAndStillReleaseWaitAll() {
        IParentResumeRepository repository = mock(IParentResumeRepository.class);
        RunControlService runControlService = mock(RunControlService.class);
        ParentWaitAllFinalizationService service = new ParentWaitAllFinalizationService(repository, runControlService);
        when(repository.isAwaitingSummary("tenant-1", "run-1")).thenReturn(true);
        when(runControlService.fail("tenant-1", "user-1", "run-1", "模型调用失败"))
                .thenReturn(terminal(RunStatus.FAILED));
        when(repository.markParentReady(eq("tenant-1"), eq("run-1"), eq("失败草稿"), any()))
                .thenReturn(true);

        assertTrue(service.failAsDraftIfWaiting("tenant-1", "user-1", "run-1", "失败草稿", "模型调用失败"));

        InOrder order = inOrder(runControlService, repository);
        order.verify(runControlService).fail("tenant-1", "user-1", "run-1", "模型调用失败");
        order.verify(repository).markParentReady(eq("tenant-1"), eq("run-1"), eq("失败草稿"), any());
    }

    @Test
    public void shouldRejectLostParentReadyCasSoOuterTransactionCanRollback() {
        IParentResumeRepository repository = mock(IParentResumeRepository.class);
        RunControlService runControlService = mock(RunControlService.class);
        ParentWaitAllFinalizationService service = new ParentWaitAllFinalizationService(repository, runControlService);
        when(repository.isAwaitingSummary("tenant-1", "run-1")).thenReturn(true);
        when(runControlService.complete("tenant-1", "user-1", "run-1"))
                .thenReturn(terminal(RunStatus.COMPLETED));
        when(repository.markParentReady(eq("tenant-1"), eq("run-1"), eq("主 Agent 草稿"), any()))
                .thenReturn(false);

        try {
            service.completeAsDraftIfWaiting("tenant-1", "user-1", "run-1", "主 Agent 草稿");
            fail("父恢复 CAS 丢失时必须失败并触发事务回滚");
        } catch (AppException exception) {
            assertTrue(exception.getMessage().contains("父 Agent"));
        }
    }

    private ChatRunEntity terminal(RunStatus status) {
        return ChatRunEntity.builder().runId("run-1").status(status).build();
    }
}
