package cn.bugstack.ai.domain.rag.adapter.port;

import java.util.List;

/**
 * 候选文档重排模型端口。
 */
public interface RerankerPort {

    /** 对同一查询的候选文本进行相关性重排。 */
    RerankResult rerank(RerankCommand command);

    /** 重排请求。 */
    record RerankCommand(String tenantId, String traceId, String query, List<Candidate> candidates, int topK) {
        public RerankCommand {
            if (tenantId == null || tenantId.isBlank() || query == null || query.isBlank()
                    || candidates == null || candidates.isEmpty() || topK < 1 || topK > candidates.size()) {
                throw new IllegalArgumentException("重排请求参数非法");
            }
            candidates = List.copyOf(candidates);
        }
    }

    /** 待重排候选。 */
    record Candidate(String chunkId, String text) {
        public Candidate {
            if (chunkId == null || chunkId.isBlank() || text == null || text.isBlank()) {
                throw new IllegalArgumentException("重排候选参数非法");
            }
        }
    }

    /** 重排结果。 */
    record RerankResult(List<ScoredCandidate> candidates, String modelRevision) {
        public RerankResult {
            if (candidates == null || modelRevision == null || modelRevision.isBlank()) {
                throw new IllegalArgumentException("重排结果参数非法");
            }
            candidates = List.copyOf(candidates);
        }
    }

    /** 带模型分数的重排候选。 */
    record ScoredCandidate(String chunkId, double score, int rank) {
        public ScoredCandidate {
            if (chunkId == null || chunkId.isBlank() || !Double.isFinite(score) || rank < 1) {
                throw new IllegalArgumentException("重排分数参数非法");
            }
        }
    }
}
