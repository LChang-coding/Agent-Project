package cn.bugstack.ai.domain.rag.service;

import cn.bugstack.ai.domain.context.model.RagContextEvidence;
import org.junit.Assert;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 工作流引用证据祖先链隔离测试。 */
public class RagWorkflowEvidenceLineageTest {

    @Test
    public void shouldPropagateAncestorsAndExcludeUnrelatedSiblingFromTerminal() {
        RagWorkflowEvidenceLineage lineage = new RagWorkflowEvidenceLineage();
        Map<String, List<RagContextEvidence>> provenance = new LinkedHashMap<>();
        provenance.put("left", List.of(evidence("ret-left")));
        provenance.put("sibling", List.of(evidence("ret-sibling")));
        provenance.put("terminal", lineage.merge(List.of("left"), provenance, List.of(evidence("ret-terminal"))));

        List<String> retrievalIds = lineage.terminal(List.of("terminal"), provenance).stream()
                .map(RagContextEvidence::retrievalId).toList();

        Assert.assertEquals(List.of("ret-left", "ret-terminal"), retrievalIds);
        Assert.assertFalse(retrievalIds.contains("ret-sibling"));
    }

    private RagContextEvidence evidence(String retrievalId) {
        return new RagContextEvidence(retrievalId, List.of());
    }
}
