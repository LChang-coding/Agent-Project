package cn.bugstack.ai.domain.rag.adapter.port;

import java.util.List;
import java.util.Map;

/**
 * 稀疏检索编码端口。
 * <p>实现必须提供真实的词项权重，不能复用 Dense 向量冒充混合检索。</p>
 */
public interface SparseEncoderPort {

    /** 批量编码查询或文档文本。 */
    SparseEncodingResult encode(SparseEncodingCommand command);

    /** 稀疏编码请求。 */
    record SparseEncodingCommand(String tenantId, String traceId, List<String> inputs, String vocabularyRevision) {
        public SparseEncodingCommand {
            if (tenantId == null || tenantId.isBlank() || inputs == null || inputs.isEmpty()
                    || inputs.stream().anyMatch(input -> input == null || input.isBlank())
                    || vocabularyRevision == null || vocabularyRevision.isBlank()) {
                throw new IllegalArgumentException("稀疏编码请求参数非法");
            }
            inputs = List.copyOf(inputs);
        }
    }

    /** 一条稀疏向量。 */
    record SparseVector(Map<Integer, Float> weights) {
        public SparseVector {
            if (weights == null || weights.isEmpty() || weights.entrySet().stream().anyMatch(entry ->
                    entry.getKey() == null || entry.getKey() < 0 || entry.getValue() == null
                            || !Float.isFinite(entry.getValue()))) {
                throw new IllegalArgumentException("稀疏向量参数非法");
            }
            weights = Map.copyOf(weights);
        }
    }

    /** 稀疏编码批量结果。 */
    record SparseEncodingResult(List<SparseVector> vectors, String vocabularyRevision) {
        public SparseEncodingResult {
            if (vectors == null || vectors.isEmpty() || vocabularyRevision == null || vocabularyRevision.isBlank()) {
                throw new IllegalArgumentException("稀疏编码结果参数非法");
            }
            vectors = List.copyOf(vectors);
        }
    }
}
