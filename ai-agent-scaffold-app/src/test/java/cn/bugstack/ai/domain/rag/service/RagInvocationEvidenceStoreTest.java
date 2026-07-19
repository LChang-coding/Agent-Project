package cn.bugstack.ai.domain.rag.service;

import cn.bugstack.ai.domain.context.model.RagContextEvidence;
import org.junit.Assert;
import org.junit.Test;

import java.util.List;

/** RAG 短期证据仓隔离、冲突和清理测试。 */
public class RagInvocationEvidenceStoreTest {

    @Test
    public void shouldIsolateScopeMergeInvocationsAndClearTerminalRun() {
        RagInvocationEvidenceStore store = new RagInvocationEvidenceStore();
        store.record("tenant-1", "user-1", "session-1", "run-1", "invoke-b", List.of(evidence("ret-b")));
        store.record("tenant-1", "user-1", "session-1", "run-1", "invoke-a", List.of(evidence("ret-a")));
        store.record("tenant-1", "user-2", "session-2", "run-2", "invoke-a", List.of(evidence("ret-x")));

        Assert.assertEquals(List.of("ret-a", "ret-b"), store.snapshot(
                "tenant-1", "user-1", "session-1", "run-1").stream().map(RagContextEvidence::retrievalId).toList());
        store.clear("tenant-1", "user-1", "session-1", "run-1");
        Assert.assertTrue(store.snapshot("tenant-1", "user-1", "session-1", "run-1").isEmpty());
        Assert.assertEquals(1, store.snapshot("tenant-1", "user-2", "session-2", "run-2").size());
    }

    @Test(expected = IllegalStateException.class)
    public void shouldFailClosedWhenInvocationEvidenceChanges() {
        RagInvocationEvidenceStore store = new RagInvocationEvidenceStore();
        store.record("tenant-1", "user-1", "session-1", "run-1", "invoke-a", List.of(evidence("ret-a")));
        RagContextEvidence conflict = new RagContextEvidence("ret-b", List.of(
                new RagContextEvidence.CitationReference("cite_0123456789abcdef01234567", "kb-1", "doc-2",
                        "文档2", "ver-2", 1, 1, "chunk-2", "b".repeat(64), null, null)));
        store.record("tenant-1", "user-1", "session-1", "run-1", "invoke-a", List.of(conflict));
    }

    private RagContextEvidence evidence(String retrievalId) {
        return new RagContextEvidence(retrievalId, List.of(new RagContextEvidence.CitationReference(
                "cite_0123456789abcdef01234567", "kb-1", "doc-1", "文档", "ver-1", 1, 1,
                "chunk-1", "a".repeat(64), null, null)));
    }
}
