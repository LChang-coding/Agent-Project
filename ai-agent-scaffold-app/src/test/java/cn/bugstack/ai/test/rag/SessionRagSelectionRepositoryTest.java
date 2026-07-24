package cn.bugstack.ai.test.rag;

import cn.bugstack.ai.domain.rag.model.valobj.RagBindingTargetType;
import cn.bugstack.ai.infrastructure.adapter.repository.SessionRagSelectionRepository;
import cn.bugstack.ai.infrastructure.dao.ISessionRagBindingSelectionDao;
import cn.bugstack.ai.infrastructure.dao.po.SessionRagBindingSelectionPO;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 会话RAG选择仓储映射测试。
 */
public class SessionRagSelectionRepositoryTest {

    @Test
    public void shouldReplaceSelectionsWithStableOrderAndNormalizedTarget() {
        ISessionRagBindingSelectionDao dao = mock(ISessionRagBindingSelectionDao.class);
        SessionRagSelectionRepository repository = new SessionRagSelectionRepository(dao);

        repository.replaceSelections("tenant-1", "user-1", "session-1",
                RagBindingTargetType.WORKFLOW, "wf-1", List.of("binding-2", "binding-1"));

        verify(dao).deleteBySession("tenant-1", "user-1", "session-1");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<SessionRagBindingSelectionPO>> captor = ArgumentCaptor.forClass(List.class);
        verify(dao).batchInsert(captor.capture());
        Assert.assertEquals(List.of("binding-2", "binding-1"),
                captor.getValue().stream().map(SessionRagBindingSelectionPO::getBindingId).toList());
        Assert.assertEquals(List.of(0, 1),
                captor.getValue().stream().map(SessionRagBindingSelectionPO::getSelectionOrder).toList());
        Assert.assertEquals("workflow", captor.getValue().get(0).getTargetType());
        Assert.assertEquals("wf-1", captor.getValue().get(0).getTargetId());
    }
}
