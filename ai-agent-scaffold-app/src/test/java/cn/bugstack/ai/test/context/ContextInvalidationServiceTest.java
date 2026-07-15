package cn.bugstack.ai.test.context;

import cn.bugstack.ai.domain.context.adapter.repository.IContextCacheRepository;
import cn.bugstack.ai.domain.context.adapter.repository.IContextCompactionTaskRepository;
import cn.bugstack.ai.domain.context.adapter.repository.IConversationMemoryRepository;
import cn.bugstack.ai.domain.context.service.ContextInvalidationService;
import cn.bugstack.ai.domain.session.model.entity.ChatMessageEntity;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 上下文失效提交边界测试。
 */
public class ContextInvalidationServiceTest {

    @After
    public void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    public void shouldEvictRunCacheOnlyAfterCommit() {
        IContextCacheRepository cacheRepository = mock(IContextCacheRepository.class);
        ContextInvalidationService service = new ContextInvalidationService(
                mock(IContextCompactionTaskRepository.class), mock(IConversationMemoryRepository.class), cacheRepository);
        TransactionSynchronizationManager.initSynchronization();

        service.invalidateRun("tenant_1", "user_1", "session_1", "run_1",
                List.of(ChatMessageEntity.builder().sequenceNo(7).build()), "cancelled");

        verify(cacheRepository, never()).evictSession("tenant_1", "user_1", "session_1");
        List<TransactionSynchronization> synchronizations = TransactionSynchronizationManager.getSynchronizations();
        Assert.assertEquals(1, synchronizations.size());
        synchronizations.forEach(TransactionSynchronization::afterCommit);
        verify(cacheRepository).evictSession("tenant_1", "user_1", "session_1");
    }
}
