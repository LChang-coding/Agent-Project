package cn.bugstack.ai.domain.rag.model.entity;

import cn.bugstack.ai.domain.rag.model.valobj.RagRetrievalMode;

/**
 * 可版本化的检索配置实体。
 */
public record RagRetrievalProfileEntity(String tenantId,
                                        String profileId,
                                        String name,
                                        RagRetrievalMode mode,
                                        int denseTopK,
                                        int sparseTopK,
                                        int fusionTopK,
                                        boolean rerankEnabled,
                                        int rerankTopK,
                                        long revision) {

    public RagRetrievalProfileEntity {
        requireText(tenantId, "租户ID");
        requireText(profileId, "检索配置ID");
        requireText(name, "检索配置名称");
        if (mode == null || denseTopK < 0 || sparseTopK < 0 || fusionTopK < 1
                || rerankTopK < 0 || rerankTopK > fusionTopK || revision < 0) {
            throw new IllegalArgumentException("检索配置参数非法");
        }
        if ((mode == RagRetrievalMode.DENSE && denseTopK == 0)
                || (mode == RagRetrievalMode.SPARSE && sparseTopK == 0)
                || (mode == RagRetrievalMode.HYBRID && (denseTopK == 0 || sparseTopK == 0))) {
            throw new IllegalArgumentException("检索模式缺少必要候选数");
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + "不能为空");
        }
    }
}
