package cn.bugstack.ai.domain.rag.adapter.port;

import java.util.List;

/**
 * Dense Embedding 模型端口。
 */
public interface EmbeddingPort {

    /** 批量生成文档或查询向量。 */
    EmbeddingResult embed(EmbeddingCommand command);

    /**
     * Embedding 请求。
     *
     * @param tenantId 计费与限流租户
     * @param traceId 调用链路标识
     * @param inputType 决定模型查询或文档前缀
     * @param inputs 保持顺序的非空文本批次
     */
    record EmbeddingCommand(String tenantId, String traceId, EmbeddingInputType inputType, List<String> inputs) {
        public EmbeddingCommand {
            if (tenantId == null || tenantId.isBlank() || inputType == null || inputs == null || inputs.isEmpty()
                    || inputs.stream().anyMatch(input -> input == null || input.isBlank())) {
                throw new IllegalArgumentException("Embedding 请求参数非法");
            }
            inputs = List.copyOf(inputs);
        }
    }

    /** Embedding 输入类型，用于强制添加正确的模型前缀。 */
    enum EmbeddingInputType {
        /** 检索问题编码。 */
        QUERY,
        /** 索引文档分块编码。 */
        PASSAGE
    }

    /**
     * Embedding 批量结果。
     *
     * @param vectors 与输入一一对应的 Dense 向量
     * @param dimensions 模型固定维度
     * @param modelRevision 可审计模型版本
     */
    record EmbeddingResult(List<List<Float>> vectors, int dimensions, String modelRevision) {
        public EmbeddingResult {
            if (vectors == null || vectors.isEmpty() || dimensions < 1 || modelRevision == null || modelRevision.isBlank()) {
                throw new IllegalArgumentException("Embedding 结果参数非法");
            }
            vectors = vectors.stream().map(List::copyOf).toList();
            if (vectors.stream().anyMatch(vector -> vector.size() != dimensions)) {
                throw new IllegalArgumentException("Embedding 向量维度不一致");
            }
        }
    }
}
