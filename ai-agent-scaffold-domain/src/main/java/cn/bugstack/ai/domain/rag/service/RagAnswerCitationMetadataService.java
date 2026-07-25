package cn.bugstack.ai.domain.rag.service;

import cn.bugstack.ai.domain.rag.model.entity.RagAnswerCitationValidation;
import cn.bugstack.ai.domain.context.model.RagContextEvidence;
import cn.bugstack.ai.domain.rag.adapter.repository.IRagRepository;
import cn.bugstack.ai.domain.rag.model.entity.RagChunkEntity;
import cn.bugstack.ai.domain.rag.model.entity.RagDocumentEntity;
import cn.bugstack.ai.domain.rag.model.entity.RagDocumentVersionEntity;
import cn.bugstack.ai.domain.rag.model.entity.RagKnowledgeBaseEntity;
import cn.bugstack.ai.domain.rag.model.valobj.RagDocumentStatus;
import cn.bugstack.ai.domain.rag.model.valobj.RagDocumentVersionStatus;
import cn.bugstack.ai.domain.rag.model.valobj.RagVisibility;
import cn.bugstack.ai.domain.session.model.entity.ChatMessageEntity;
import cn.bugstack.ai.domain.session.service.SessionDomain;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import cn.bugstack.ai.types.exception.AppException;

/** 解析并按可信会话范围读取最终回答引用元数据。 */
@Service
public class RagAnswerCitationMetadataService {
    /** 只解析当前已知结构，避免把任意消息元数据误当引用。 */
    private static final String SCHEMA = "rag-citations/v1";
    /** 会话域提供消息真实性和用户访问校验。 */
    private final SessionDomain sessionDomain;
    /** 负责版本化引用快照的 JSON 反序列化。 */
    private final ObjectMapper objectMapper;
    /** 实时复核知识库、文档、版本和分块生命周期。 */
    private final IRagRepository ragRepository;

    /** 注入可信消息源、序列化器与 RAG 仓储。 */
    public RagAnswerCitationMetadataService(SessionDomain sessionDomain, ObjectMapper objectMapper,
                                            IRagRepository ragRepository) {
        this.sessionDomain = sessionDomain;
        this.objectMapper = objectMapper;
        this.ragRepository = ragRepository;
    }

    /** 查询某一运行已落库的助手引用快照。 */
    public AnswerSnapshot queryRunAnswer(String tenantId, String userId, String sessionId, String runId) {
        sessionDomain.assertSessionAccess(tenantId, userId, sessionId, null);
        return sessionDomain.queryRunMessages(tenantId, userId, sessionId, runId).stream()
                .filter(message -> SessionDomain.ROLE_ASSISTANT.equals(message.getRole()))
                .map(message -> new AnswerSnapshot(message.getMessageId(), parse(message)))
                .filter(snapshot -> snapshot.validation() != null)
                .findFirst().orElse(null);
    }

    /** 解析可信数据库消息上的版本化引用快照；旧消息或非法元数据返回空。 */
    public RagAnswerCitationValidation parse(ChatMessageEntity message) {
        if (message == null || message.getMetadata() == null || message.getMetadata().isBlank()) return null;
        try {
            JsonNode root = objectMapper.readTree(message.getMetadata());
            if (!SCHEMA.equals(root.path("schema").asText()) || !root.has("validation")) return null;
            return objectMapper.treeToValue(root.get("validation"), RagAnswerCitationValidation.class);
        } catch (Exception ignored) {
            return null;
        }
    }

    /** 在当前权限与活动版本下解析引用正文，任何范围或生命周期漂移都拒绝返回。 */
    public CitationSource resolveSource(String tenantId, String userId, String sessionId,
                                        String messageId, String citationId) {
        ChatMessageEntity message = sessionDomain.queryValidMessage(tenantId, userId, sessionId, messageId);
        RagAnswerCitationValidation validation = parse(message);
        if (message == null || !SessionDomain.ROLE_ASSISTANT.equals(message.getRole()) || validation == null
                || validation.status() == RagAnswerCitationValidation.Status.INVALID_CITATIONS) {
            throw unavailable();
        }
        RagContextEvidence.CitationReference reference = validation.usedCitations().stream()
                .filter(value -> value.citationId().equals(citationId)).findFirst().orElseThrow(this::unavailable);
        RagKnowledgeBaseEntity knowledgeBase = ragRepository.findKnowledgeBase(tenantId, reference.knowledgeBaseId())
                .orElseThrow(this::unavailable);
        if (knowledgeBase.visibility() == RagVisibility.PRIVATE && !knowledgeBase.ownerUserId().equals(userId)) {
            throw unavailable();
        }
        RagDocumentEntity document = ragRepository.findDocument(tenantId, reference.documentId())
                .orElseThrow(this::unavailable);
        if (document.visibility() == RagVisibility.PRIVATE && !document.ownerUserId().equals(userId)) {
            throw unavailable();
        }
        RagDocumentVersionEntity version = ragRepository.findDocumentVersion(tenantId, reference.versionId())
                .orElseThrow(this::unavailable);
        RagChunkEntity chunk = ragRepository.listChunksByIds(tenantId, java.util.List.of(reference.chunkId())).stream()
                .filter(value -> value.chunkId().equals(reference.chunkId())).findFirst().orElseThrow(this::unavailable);
        boolean current = knowledgeBase.status().searchable()
                && knowledgeBase.currentGeneration() == reference.generation()
                && document.status() == RagDocumentStatus.READY
                && document.knowledgeBaseId().equals(reference.knowledgeBaseId())
                && reference.versionId().equals(document.activeVersionId())
                && document.activeGeneration() == reference.generation()
                && version.status() == RagDocumentVersionStatus.READY
                && version.knowledgeBaseId().equals(reference.knowledgeBaseId())
                && version.documentId().equals(reference.documentId())
                && version.versionNumber() == reference.documentVersion()
                && version.generation() == reference.generation()
                && chunk.knowledgeBaseId().equals(reference.knowledgeBaseId())
                && chunk.documentId().equals(reference.documentId())
                && chunk.versionId().equals(reference.versionId())
                && chunk.versionNumber() == reference.documentVersion()
                && chunk.generation() == reference.generation()
                && chunk.visibility() == document.visibility()
                && chunk.ownerUserId().equals(document.ownerUserId());
        current = current && chunk.contentHash().equals(reference.contentHash());
        if (!current) throw unavailable();
        String excerpt = chunk.content().length() <= 1_200 ? chunk.content() : chunk.content().substring(0, 1_200);
        return new CitationSource(reference.citationId(), reference.documentId(), reference.documentName(),
                reference.documentVersion(), reference.pageNumber(), reference.headingPath(), excerpt);
    }

    /** 对不存在、越权和版本漂移统一返回不可用，避免泄露资源存在性。 */
    private AppException unavailable() {
        return new AppException("RAG_CITATION_UNAVAILABLE", "引用不存在、已失效或无权访问");
    }

    /** 消息与引用快照。 */
    public record AnswerSnapshot(String messageId, RagAnswerCitationValidation validation) { }

    /** 经实时授权和版本校验的引用来源。 */
    public record CitationSource(String citationId, String documentId, String documentName,
                                 int documentVersion, Integer pageNumber, String headingPath, String excerpt) { }
}
