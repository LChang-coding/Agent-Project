package cn.bugstack.ai.test.rag;

import cn.bugstack.ai.domain.rag.model.entity.RagKnowledgeBaseEntity;
import cn.bugstack.ai.domain.rag.model.valobj.RagKnowledgeBaseStatus;
import cn.bugstack.ai.domain.rag.model.valobj.RagVisibility;
import cn.bugstack.ai.infrastructure.adapter.repository.RagRepository;
import cn.bugstack.ai.infrastructure.dao.IRagAgentBindingDao;
import cn.bugstack.ai.infrastructure.dao.IRagChunkDao;
import cn.bugstack.ai.infrastructure.dao.IRagDocumentDao;
import cn.bugstack.ai.infrastructure.dao.IRagDocumentVersionDao;
import cn.bugstack.ai.infrastructure.dao.IRagIngestTaskDao;
import cn.bugstack.ai.infrastructure.dao.IRagKnowledgeBaseDao;
import cn.bugstack.ai.infrastructure.dao.IRagRetrievalProfileDao;
import cn.bugstack.ai.infrastructure.rag.persistence.RagPersistenceCodec;
import cn.bugstack.ai.infrastructure.rag.persistence.RagPersistenceMapper;
import cn.bugstack.ai.types.exception.AppException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Assert;
import org.junit.Test;
import org.springframework.dao.DuplicateKeyException;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 知识库数据库并发冲突转换测试。 */
public class RagKnowledgeBaseConflictMappingTest {

    @Test
    public void shouldMapDuplicateKeyToStableApplicationConflict() {
        IRagKnowledgeBaseDao knowledgeBaseDao = mock(IRagKnowledgeBaseDao.class);
        RagPersistenceCodec codec = new RagPersistenceCodec(new ObjectMapper());
        RagRepository repository = new RagRepository(knowledgeBaseDao, mock(IRagDocumentDao.class),
                mock(IRagDocumentVersionDao.class), mock(IRagIngestTaskDao.class), mock(IRagChunkDao.class),
                mock(IRagRetrievalProfileDao.class), mock(IRagAgentBindingDao.class),
                new RagPersistenceMapper(codec), codec);
        when(knowledgeBaseDao.insert(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new DuplicateKeyException("uk_rag_kb_tenant_name"));
        RagKnowledgeBaseEntity entity = new RagKnowledgeBaseEntity(
                "tenant-a", "admin-1", "kb-1", "企业手册", null, RagVisibility.TENANT,
                RagKnowledgeBaseStatus.ACTIVE, null, 768, "rag_hash_kb-1", 0L, 0L);

        try {
            repository.insertKnowledgeBase("tenant-a", entity);
            Assert.fail("预期重复键被转换为稳定业务异常");
        } catch (AppException e) {
            Assert.assertEquals("RAG_KNOWLEDGE_BASE_CONFLICT", e.getCode());
        }
    }
}
