package cn.bugstack.ai.test.agent;

import cn.bugstack.ai.domain.agent.adapter.repository.IParentResumeRepository;
import cn.bugstack.ai.domain.agent.model.entity.SubagentTaskEntity;
import cn.bugstack.ai.domain.agent.model.valobj.SubagentTaskStatus;
import cn.bugstack.ai.infrastructure.adapter.repository.SubagentTaskRepository;
import cn.bugstack.ai.infrastructure.dao.ISubagentTaskDao;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

import java.time.LocalDateTime;
import java.util.List;

/** 并发重放时依赖稳定 taskId 唯一键收敛为已有批次。 */
public class SubagentTaskRepositoryIdempotencyTest {
    @Test
    public void shouldTreatFirstTaskDuplicateAsConcurrentReplay() {
        ISubagentTaskDao dao = Mockito.mock(ISubagentTaskDao.class);
        IParentResumeRepository parentResumeRepository = Mockito.mock(IParentResumeRepository.class);
        Mockito.when(parentResumeRepository.tryPrepareWait(ArgumentMatchers.any(), ArgumentMatchers.any()))
                .thenReturn(false);
        SubagentTaskRepository repository = new SubagentTaskRepository(dao, new ObjectMapper(), parentResumeRepository);
        SubagentTaskEntity task = SubagentTaskEntity.builder().tenantId("tenant").userId("user")
                .parentRunId("run").parentSessionId("session").parentAgentId("supervisor")
                .taskId("stable-task").childAgentId("research").instruction("work")
                .functionCallId("call").status(SubagentTaskStatus.READY).attempt(0).fencingToken(0L)
                .createdAt(LocalDateTime.now()).build();

        Assert.assertEquals(0, repository.createBatchAndEnqueue(List.of(task)));
        Mockito.verify(parentResumeRepository).tryPrepareWait(
                Mockito.same(task), ArgumentMatchers.any(LocalDateTime.class));
        Mockito.verifyNoInteractions(dao);
    }
}
