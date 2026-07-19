package cn.bugstack.ai.domain.rag.service;

import cn.bugstack.ai.domain.context.model.RagContextEvidence;
import cn.bugstack.ai.domain.rag.model.entity.RagAnswerCitationValidation;
import cn.bugstack.ai.domain.session.model.entity.ChatMessageEntity;
import cn.bugstack.ai.domain.session.service.SessionDomain;
import cn.bugstack.ai.domain.rag.adapter.repository.IRagRepository;
import cn.bugstack.ai.domain.rag.model.entity.RagChunkEntity;
import cn.bugstack.ai.domain.rag.model.entity.RagDocumentEntity;
import cn.bugstack.ai.domain.rag.model.entity.RagDocumentVersionEntity;
import cn.bugstack.ai.domain.rag.model.entity.RagKnowledgeBaseEntity;
import cn.bugstack.ai.domain.rag.model.valobj.RagDocumentStatus;
import cn.bugstack.ai.domain.rag.model.valobj.RagDocumentVersionStatus;
import cn.bugstack.ai.domain.rag.model.valobj.RagKnowledgeBaseStatus;
import cn.bugstack.ai.domain.rag.model.valobj.RagVisibility;
import cn.bugstack.ai.types.exception.AppException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 引用元数据无损回放与可信范围查询测试。 */
public class RagAnswerCitationMetadataServiceTest {

    @Test
    public void shouldRoundTripVersionedMetadataAndQueryScopedAssistantMessage() throws Exception {
        SessionDomain sessionDomain = Mockito.mock(SessionDomain.class);
        ObjectMapper mapper = new ObjectMapper();
        RagAnswerCitationValidation validation = validation();
        String metadata = mapper.writeValueAsString(Map.of("schema", "rag-citations/v1", "validation", validation));
        ChatMessageEntity message = ChatMessageEntity.builder().messageId("msg-1").runId("run-1")
                .role(SessionDomain.ROLE_ASSISTANT).metadata(metadata).build();
        Mockito.when(sessionDomain.queryRunMessages("tenant-1", "user-1", "session-1", "run-1"))
                .thenReturn(List.of(message));
        RagAnswerCitationMetadataService service = new RagAnswerCitationMetadataService(sessionDomain, mapper,
                Mockito.mock(IRagRepository.class));

        RagAnswerCitationMetadataService.AnswerSnapshot snapshot = service.queryRunAnswer(
                "tenant-1", "user-1", "session-1", "run-1");

        Assert.assertEquals("msg-1", snapshot.messageId());
        Assert.assertEquals(validation, snapshot.validation());
        Mockito.verify(sessionDomain).assertSessionAccess("tenant-1", "user-1", "session-1", null);
    }

    @Test
    public void shouldIgnoreUnknownOrMalformedMetadata() {
        RagAnswerCitationMetadataService service = new RagAnswerCitationMetadataService(
                Mockito.mock(SessionDomain.class), new ObjectMapper(), Mockito.mock(IRagRepository.class));
        Assert.assertNull(service.parse(ChatMessageEntity.builder().metadata("{bad").build()));
        Assert.assertNull(service.parse(ChatMessageEntity.builder()
                .metadata("{\"schema\":\"future/v2\",\"validation\":{}}").build()));
    }

    @Test
    public void shouldResolveBoundedExcerptOnlyWhenCurrentScopeAndGenerationMatch() throws Exception {
        SessionDomain sessionDomain = Mockito.mock(SessionDomain.class);
        IRagRepository repository = Mockito.mock(IRagRepository.class);
        ObjectMapper mapper = new ObjectMapper();
        String metadata = mapper.writeValueAsString(Map.of("schema", "rag-citations/v1", "validation", validation()));
        Mockito.when(sessionDomain.queryValidMessage("tenant-1", "user-1", "session-1", "msg-1"))
                .thenReturn(ChatMessageEntity.builder().messageId("msg-1").role(SessionDomain.ROLE_ASSISTANT)
                        .metadata(metadata).build());
        Mockito.when(repository.findKnowledgeBase("tenant-1", "kb-1")).thenReturn(Optional.of(
                new RagKnowledgeBaseEntity("tenant-1", "user-1", "kb-1", "知识库", null,
                        RagVisibility.PRIVATE, RagKnowledgeBaseStatus.ACTIVE, null, 768, "alias", 1, 1)));
        Mockito.when(repository.findDocument("tenant-1", "doc-1")).thenReturn(Optional.of(
                new RagDocumentEntity("tenant-1", "user-1", RagVisibility.PRIVATE, "kb-1", "doc-1",
                        "文档", "ver-1", 1, null, RagDocumentStatus.READY, 1)));
        Mockito.when(repository.findDocumentVersion("tenant-1", "ver-1")).thenReturn(Optional.of(
                new RagDocumentVersionEntity("tenant-1", "kb-1", "doc-1", "ver-1", 1, 1,
                        "bucket", "object", null, null, "文档.md", "a".repeat(64), "text/markdown", 10,
                        RagDocumentVersionStatus.READY, "p1", "c1", "e1", 1)));
        Mockito.when(repository.listChunksByIds("tenant-1", List.of("chunk-1"))).thenReturn(List.of(
                new RagChunkEntity("tenant-1", "user-1", RagVisibility.PRIVATE, "kb-1", "doc-1",
                        "ver-1", 1, 1, "chunk-1", 0, null, null, null, "正文内容", 4, 2,
                        "章节", "b".repeat(64), "point-1", Map.of())));
        RagAnswerCitationMetadataService service = new RagAnswerCitationMetadataService(sessionDomain, mapper, repository);

        RagAnswerCitationMetadataService.CitationSource source = service.resolveSource(
                "tenant-1", "user-1", "session-1", "msg-1", "cite_0123456789abcdef01234567");

        Assert.assertEquals("正文内容", source.excerpt());
        Assert.assertEquals("doc-1", source.documentId());
    }

    @Test(expected = AppException.class)
    public void shouldRejectCitationWhenTrustedUserDoesNotOwnPrivateKnowledgeBase() throws Exception {
        SessionDomain sessionDomain = Mockito.mock(SessionDomain.class);
        IRagRepository repository = Mockito.mock(IRagRepository.class);
        ObjectMapper mapper = new ObjectMapper();
        String metadata = mapper.writeValueAsString(Map.of("schema", "rag-citations/v1", "validation", validation()));
        Mockito.when(sessionDomain.queryValidMessage("tenant-1", "user-2", "session-1", "msg-1"))
                .thenReturn(ChatMessageEntity.builder().role(SessionDomain.ROLE_ASSISTANT).metadata(metadata).build());
        Mockito.when(repository.findKnowledgeBase("tenant-1", "kb-1")).thenReturn(Optional.of(
                new RagKnowledgeBaseEntity("tenant-1", "user-1", "kb-1", "知识库", null,
                        RagVisibility.PRIVATE, RagKnowledgeBaseStatus.ACTIVE, null, 768, "alias", 1, 1)));
        Mockito.when(repository.findDocument("tenant-1", "doc-1")).thenReturn(Optional.empty());

        new RagAnswerCitationMetadataService(sessionDomain, mapper, repository).resolveSource(
                "tenant-1", "user-2", "session-1", "msg-1", "cite_0123456789abcdef01234567");
    }

    private RagAnswerCitationValidation validation() {
        var reference = new RagContextEvidence.CitationReference("cite_0123456789abcdef01234567",
                "kb-1", "doc-1", "文档", "ver-1", 1, 1, "chunk-1", "b".repeat(64), 2, "章节");
        return new RagAnswerCitationValidation(RagAnswerCitationValidation.Status.VALID,
                List.of("ret-1"), List.of(reference.citationId()), List.of(reference.citationId()),
                List.of(), List.of(reference));
    }
}
