package cn.bugstack.ai.test.context;

import cn.bugstack.ai.domain.context.adapter.port.ContextCompactionPublisher;
import cn.bugstack.ai.domain.context.adapter.repository.IContextCompactionTaskRepository;
import cn.bugstack.ai.domain.context.model.ContextCompactionTaskEntity;
import cn.bugstack.ai.domain.context.model.ContextCompactionTaskStatus;
import cn.bugstack.ai.domain.context.model.ContextPolicyProperties;
import cn.bugstack.ai.trigger.job.ContextCompactionRecoveryJob;
import org.junit.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class ContextCompactionRecoveryJobTest {

    @Test
    public void shouldContinueBatchWhenOneRepublishFails() {
        IContextCompactionTaskRepository repository = mock(IContextCompactionTaskRepository.class);
        ContextCompactionPublisher publisher = mock(ContextCompactionPublisher.class);
        ContextPolicyProperties properties = new ContextPolicyProperties();
        properties.setEnabled(true);
        properties.setCompactionMaxAttempts(3);
        when(repository.queryRecoverable(20, 3)).thenReturn(List.of(task("task-1"), task("task-2")));
        doThrow(new IllegalStateException("send failed")).doNothing().when(publisher).publish(any());
        ContextCompactionRecoveryJob job = new ContextCompactionRecoveryJob(repository, publisher, properties, 20);

        job.republishRecoverableTasks();

        verify(repository).queryRecoverable(20, 3);
        verify(publisher, times(2)).publish(any());
    }

    private ContextCompactionTaskEntity task(String taskId) {
        return ContextCompactionTaskEntity.builder().taskId(taskId).tenantId("tenant").userId("user")
                .sessionId("session").fromSequence(1).toSequence(10).expectedMemoryVersion(0)
                .policyVersion("v1").traceId("trace-1").status(ContextCompactionTaskStatus.PENDING)
                .attemptCount(0).build();
    }
}
