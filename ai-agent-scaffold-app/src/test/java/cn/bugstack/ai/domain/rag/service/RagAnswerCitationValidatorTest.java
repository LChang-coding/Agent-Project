package cn.bugstack.ai.domain.rag.service;

import cn.bugstack.ai.domain.context.model.RagContextEvidence;
import cn.bugstack.ai.domain.rag.model.entity.RagAnswerCitationValidation;
import org.junit.Assert;
import org.junit.Test;

import java.util.List;

/** 最终回答引用白名单校验测试。 */
public class RagAnswerCitationValidatorTest {
    private static final String ALLOWED = "cite_0123456789abcdef01234567";
    private static final String UNKNOWN = "cite_aaaaaaaaaaaaaaaaaaaaaaaa";

    @Test
    public void shouldAcceptOnlyActuallyInjectedCitationAndKeepStableOrder() {
        RagAnswerCitationValidator validator = new RagAnswerCitationValidator();
        RagAnswerCitationValidation result = validator.validate(
                "依据 " + ALLOWED + "，重复 " + ALLOWED, List.of(evidence("ret-1", ALLOWED)));

        Assert.assertEquals(RagAnswerCitationValidation.Status.VALID, result.status());
        Assert.assertEquals(List.of(ALLOWED), result.usedCitationIds());
        Assert.assertTrue(result.invalidCitationIds().isEmpty());
        Assert.assertEquals("doc-1", result.usedCitations().get(0).documentId());
    }

    @Test
    public void shouldReportUnknownCitationWithoutExposingItAsUsedEvidence() {
        RagAnswerCitationValidation result = new RagAnswerCitationValidator().validate(
                ALLOWED + " " + UNKNOWN, List.of(evidence("ret-1", ALLOWED)));

        Assert.assertEquals(RagAnswerCitationValidation.Status.INVALID_CITATIONS, result.status());
        Assert.assertEquals(List.of(ALLOWED), result.usedCitationIds());
        Assert.assertEquals(List.of(UNKNOWN), result.invalidCitationIds());
        Assert.assertEquals(1, result.usedCitations().size());
    }

    @Test
    public void shouldRejectMalformedBoundaryAndDistinguishUnusedRagFromNoRag() {
        RagAnswerCitationValidator validator = new RagAnswerCitationValidator();
        Assert.assertEquals(RagAnswerCitationValidation.Status.RAG_AVAILABLE_UNUSED,
                validator.validate("前缀x" + ALLOWED, List.of(evidence("ret-1", ALLOWED))).status());
        Assert.assertEquals(RagAnswerCitationValidation.Status.NO_RAG,
                validator.validate("没有引用", List.of()).status());
    }

    @Test(expected = IllegalStateException.class)
    public void shouldFailClosedWhenSameCitationIdMapsToDifferentEvidence() {
        RagContextEvidence first = evidence("ret-1", ALLOWED);
        RagContextEvidence.CitationReference changed = new RagContextEvidence.CitationReference(ALLOWED,
                "kb-1", "doc-2", "文档2", "ver-2", 2, 2, "chunk-2", "b".repeat(64), null, null);
        new RagAnswerCitationValidator().validate(ALLOWED,
                List.of(first, new RagContextEvidence("ret-2", List.of(changed))));
    }

    private RagContextEvidence evidence(String retrievalId, String citationId) {
        return new RagContextEvidence(retrievalId, List.of(new RagContextEvidence.CitationReference(citationId,
                "kb-1", "doc-1", "文档1", "ver-1", 1, 1, "chunk-1", "a".repeat(64), 3, "章节")));
    }
}
