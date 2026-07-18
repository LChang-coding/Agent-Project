package cn.bugstack.ai.infrastructure.rag.persistence;

import cn.bugstack.ai.domain.rag.model.entity.RagRetrievalAuditCommand;
import cn.bugstack.ai.domain.rag.model.entity.RagRetrievalResult;
import cn.bugstack.ai.infrastructure.dao.IRagRetrievalCitationDao;
import cn.bugstack.ai.infrastructure.dao.IRagRetrievalRecordDao;
import cn.bugstack.ai.infrastructure.dao.po.RagRetrievalCitationPO;
import cn.bugstack.ai.infrastructure.dao.po.RagRetrievalRecordPO;
import cn.bugstack.ai.infrastructure.rag.config.RagProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 检索审计脱敏、指标映射和原子写入前置行为测试。 */
public class RagRetrievalAuditRepositoryTest {

    private IRagRetrievalRecordDao recordDao;
    private IRagRetrievalCitationDao citationDao;
    private RagProperties properties;
    private RagRetrievalAuditRepository repository;

    @Before
    public void setUp() {
        recordDao = Mockito.mock(IRagRetrievalRecordDao.class);
        citationDao = Mockito.mock(IRagRetrievalCitationDao.class);
        properties = new RagProperties();
        repository = new RagRetrievalAuditRepository(recordDao, citationDao, properties, new ObjectMapper());
    }

    @Test
    public void shouldHideQueryAndCitationContentByDefault() {
        when(recordDao.insert(any())).thenReturn(1);
        when(citationDao.insertBatch(anyString(), anyString(), anyList())).thenReturn(1);

        repository.record(command("success", null, "Injected secret query"));

        ArgumentCaptor<RagRetrievalRecordPO> record = ArgumentCaptor.forClass(RagRetrievalRecordPO.class);
        verify(recordDao).insert(record.capture());
        Assert.assertNull(record.getValue().getQueryText());
        Assert.assertEquals(64, record.getValue().getQueryHash().length());
        Assert.assertFalse(record.getValue().getRequestSnapshot().contains("Injected secret query"));
        Assert.assertEquals(Long.valueOf(6), record.getValue().getAssembleMs());
        Assert.assertTrue(record.getValue().getStageMetrics().contains("\"configurationMs\":7"));
        Assert.assertTrue(record.getValue().getStageMetrics().contains("\"hydrationMs\":8"));
        ArgumentCaptor<List<RagRetrievalCitationPO>> citations = ArgumentCaptor.forClass(List.class);
        verify(citationDao).insertBatch(Mockito.eq("tenant-a"), Mockito.eq("ret-a"), citations.capture());
        Assert.assertNull(citations.getValue().get(0).getContentSnapshot());
        Assert.assertEquals("chunk-a", citations.getValue().get(0).getChunkId());
    }

    @Test
    public void shouldPersistContentOnlyWhenBothRetentionSwitchesEnabled() {
        properties.getAudit().setStoreQueryText(true);
        properties.getAudit().setStoreCitationContent(true);
        when(recordDao.insert(any())).thenReturn(1);
        when(citationDao.insertBatch(anyString(), anyString(), anyList())).thenReturn(1);

        repository.record(command("success", null, "allowed query"));

        ArgumentCaptor<RagRetrievalRecordPO> record = ArgumentCaptor.forClass(RagRetrievalRecordPO.class);
        verify(recordDao).insert(record.capture());
        Assert.assertEquals("allowed query", record.getValue().getQueryText());
        ArgumentCaptor<List<RagRetrievalCitationPO>> citations = ArgumentCaptor.forClass(List.class);
        verify(citationDao).insertBatch(anyString(), anyString(), citations.capture());
        Assert.assertEquals("引用正文", citations.getValue().get(0).getContentSnapshot());
    }

    @Test
    public void shouldStoreOnlyStableErrorSummaryOnFailure() {
        when(recordDao.insert(any())).thenReturn(1);

        repository.record(command("failed", "RAG_QDRANT_HTTP_ERROR", "private query"));

        ArgumentCaptor<RagRetrievalRecordPO> record = ArgumentCaptor.forClass(RagRetrievalRecordPO.class);
        verify(recordDao).insert(record.capture());
        Assert.assertEquals("RAG_QDRANT_HTTP_ERROR:HttpFailure", record.getValue().getErrorMessage());
        Assert.assertEquals(0, record.getValue().getFinalCount().intValue());
        verify(citationDao, never()).insertBatch(anyString(), anyString(), anyList());
    }

    @Test
    public void shouldStopBeforeCitationWhenMainRecordInsertFails() {
        when(recordDao.insert(any())).thenReturn(0);

        Assert.assertThrows(IllegalStateException.class,
                () -> repository.record(command("success", null, "query")));

        verify(citationDao, never()).insertBatch(anyString(), anyString(), anyList());
    }

    private RagRetrievalAuditCommand command(String status, String errorCode, String query) {
        RagRetrievalResult result = "failed".equals(status)
                ? RagRetrievalResult.empty("ret-a", 7)
                : new RagRetrievalResult("ret-a", List.of(citation()), 10, false, List.of(),
                new RagRetrievalResult.Metrics(5, 4, 3, 2, 1, 2, 3, 1, 4, 12,
                        7, 8, 6, 0, 0));
        return new RagRetrievalAuditCommand("ret-a", "tenant-a", "user-a", "session-a", "run-a",
                "agent-a", "profile-a", 2, query, true, true, true, result, status, errorCode,
                errorCode == null ? null : "HttpFailure", "trace-a", Map.of("profileIds", List.of("profile-a")));
    }

    private RagRetrievalResult.Citation citation() {
        return new RagRetrievalResult.Citation("cite-a", 1, "kb-a", "doc-a", "A.md", "ver-a",
                1, 3, "chunk-a", "引用正文", 2, "章节", "a".repeat(64),
                0.8, 0.7, 0.9, 0.95, Map.of("profile_id", "profile-a"));
    }
}
