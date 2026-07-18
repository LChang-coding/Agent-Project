package cn.bugstack.ai.test.rag;

import cn.bugstack.ai.domain.rag.adapter.port.SparseEncoderPort;
import cn.bugstack.ai.domain.rag.service.DeterministicSparseEncoder;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/** 确定性稀疏编码器属性与边界测试。 */
public class DeterministicSparseEncoderTest {

    @Test
    public void shouldEncodeChineseAndEnglishDeterministicallyWithStableSortedIndices() {
        DeterministicSparseEncoder encoder = new DeterministicSparseEncoder();
        var command = new SparseEncoderPort.SparseEncodingCommand("tenant-a", "trace-1",
                List.of("年假申请 annual leave policy v2"), "sparse-v1");

        var first = encoder.encode(command);
        var second = encoder.encode(command);

        Assert.assertEquals(first, second);
        List<Integer> indices = new ArrayList<>(first.vectors().get(0).weights().keySet());
        List<Integer> sorted = indices.stream().sorted().toList();
        Assert.assertEquals(sorted, indices);
        Assert.assertTrue(indices.stream().allMatch(index -> index >= 0
                && index < DeterministicSparseEncoder.DEFAULT_DIMENSION));
    }

    @Test
    public void shouldL2NormalizeEverySparseVector() {
        DeterministicSparseEncoder encoder = new DeterministicSparseEncoder();
        var result = encoder.encode(new SparseEncoderPort.SparseEncodingCommand("tenant-a", null,
                List.of("重试 重试 retry retry retry", "表格 timeout 120"), "sparse-v1"));

        for (var vector : result.vectors()) {
            double norm = Math.sqrt(vector.weights().values().stream()
                    .mapToDouble(weight -> weight * weight).sum());
            Assert.assertEquals(1D, norm, 1.0e-6D);
        }
    }

    @Test
    public void shouldApplyLogTermFrequencyBeforeNormalization() {
        DeterministicSparseEncoder encoder = new DeterministicSparseEncoder();
        int retryIndex = encoder.encode(new SparseEncoderPort.SparseEncodingCommand("tenant-a", null,
                List.of("retry"), "sparse-v1")).vectors().get(0).weights().keySet().iterator().next();
        int policyIndex = encoder.encode(new SparseEncoderPort.SparseEncodingCommand("tenant-a", null,
                List.of("policy"), "sparse-v1")).vectors().get(0).weights().keySet().iterator().next();

        var mixed = encoder.encode(new SparseEncoderPort.SparseEncodingCommand("tenant-a", null,
                List.of("retry retry retry policy"), "sparse-v1")).vectors().get(0);

        Assert.assertTrue(mixed.weights().get(retryIndex) > mixed.weights().get(policyIndex));
    }

    @Test
    public void shouldChangeIndexSpaceWhenVocabularyRevisionChanges() {
        DeterministicSparseEncoder encoder = new DeterministicSparseEncoder();
        var v1 = encoder.encode(command("sparse-v1")).vectors().get(0);
        var v2 = encoder.encode(command("sparse-v2")).vectors().get(0);

        Assert.assertNotEquals(v1.weights().keySet(), v2.weights().keySet());
    }

    @Test
    public void shouldMergeHashCollisionsAndRemainNormalized() {
        DeterministicSparseEncoder encoder = new DeterministicSparseEncoder(1024);
        StringBuilder manyUniqueTerms = new StringBuilder();
        for (int i = 0; i < 5000; i++) manyUniqueTerms.append("term").append(i).append(' ');

        var vector = encoder.encode(new SparseEncoderPort.SparseEncodingCommand("tenant-a", null,
                List.of(manyUniqueTerms.toString()), "collision-v1")).vectors().get(0);

        Assert.assertTrue(vector.weights().size() <= 1024);
        double norm = Math.sqrt(vector.weights().values().stream().mapToDouble(value -> value * value).sum());
        Assert.assertEquals(1D, norm, 1.0e-6D);
    }

    private SparseEncoderPort.SparseEncodingCommand command(String revision) {
        return new SparseEncoderPort.SparseEncodingCommand("tenant-a", "trace-1",
                List.of("知识库 retrieval policy"), revision);
    }
}
