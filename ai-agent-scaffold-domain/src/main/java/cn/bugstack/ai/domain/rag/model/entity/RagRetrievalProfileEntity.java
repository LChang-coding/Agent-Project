package cn.bugstack.ai.domain.rag.model.entity;

import cn.bugstack.ai.domain.rag.model.valobj.RagRetrievalMode;
import cn.bugstack.ai.domain.rag.model.valobj.RagFusionStrategy;

import java.math.BigDecimal;

/**
 * 可版本化的检索配置实体。
 *
 * @param tenantId 检索配置所属租户
 * @param profileId 检索配置标识
 * @param name 检索配置展示名称
 * @param mode 启用的召回通道
 * @param fusionStrategy 多路候选的融合策略
 * @param denseWeight 稠密召回的加权融合权重
 * @param sparseWeight 稀疏召回的加权融合权重
 * @param denseTopK 稠密召回候选数
 * @param sparseTopK 稀疏召回候选数
 * @param fusionTopK 融合后保留的候选数
 * @param rerankEnabled 是否调用重排模型
 * @param rerankTopK 送入重排阶段的候选数
 * @param finalTopK 最终组装引用前保留的候选数
 * @param neighborWindow 主命中两侧允许扩展的相邻分块数
 * @param maxContextTokens 该配置允许的最大上下文 Token 数
 * @param scoreThreshold 候选可进入最终结果的最低分数
 * @param queryRewriteEnabled 是否在召回前重写查询
 * @param deduplicateEnabled 是否在结果组装前去除重复候选
 * @param revision 乐观并发控制版本号
 */
public record RagRetrievalProfileEntity(String tenantId,
                                        String profileId,
                                        String name,
                                        RagRetrievalMode mode,
                                        RagFusionStrategy fusionStrategy,
                                        BigDecimal denseWeight,
                                        BigDecimal sparseWeight,
                                        int denseTopK,
                                        int sparseTopK,
                                        int fusionTopK,
                                        boolean rerankEnabled,
                                        int rerankTopK,
                                        int finalTopK,
                                        int neighborWindow,
                                        int maxContextTokens,
                                        BigDecimal scoreThreshold,
                                        boolean queryRewriteEnabled,
                                        boolean deduplicateEnabled,
                                        long revision) {

    /** 校验召回模式、融合策略、各阶段候选数、Token 预算和分数阈值。 */
    public RagRetrievalProfileEntity {
        requireText(tenantId, "租户ID");
        requireText(profileId, "检索配置ID");
        requireText(name, "检索配置名称");
        if (mode == null || fusionStrategy == null || denseWeight == null || sparseWeight == null
                || denseWeight.signum() < 0 || sparseWeight.signum() < 0
                || denseTopK < 0 || sparseTopK < 0 || fusionTopK < 1
                || rerankTopK < 0 || rerankTopK > fusionTopK || finalTopK < 1
                || finalTopK > (rerankEnabled ? rerankTopK : fusionTopK)
                || neighborWindow < 0 || maxContextTokens < 1 || revision < 0) {
            throw new IllegalArgumentException("检索配置参数非法");
        }
        if ((mode == RagRetrievalMode.DENSE && denseTopK == 0)
                || (mode == RagRetrievalMode.SPARSE && sparseTopK == 0)
                || (mode == RagRetrievalMode.HYBRID && (denseTopK == 0 || sparseTopK == 0))) {
            throw new IllegalArgumentException("检索模式缺少必要候选数");
        }
        if (mode == RagRetrievalMode.HYBRID && fusionStrategy == RagFusionStrategy.NONE) {
            throw new IllegalArgumentException("混合检索必须配置融合策略");
        }
        if (mode == RagRetrievalMode.HYBRID && denseWeight.add(sparseWeight).signum() == 0) {
            throw new IllegalArgumentException("混合检索至少一个融合权重必须大于0");
        }
        if (scoreThreshold != null && (scoreThreshold.signum() < 0 || scoreThreshold.doubleValue() > 1D)) {
            throw new IllegalArgumentException("检索分数阈值必须位于0到1之间");
        }
    }

    /** 校验检索配置身份和名称。 */
    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + "不能为空");
        }
    }
}
