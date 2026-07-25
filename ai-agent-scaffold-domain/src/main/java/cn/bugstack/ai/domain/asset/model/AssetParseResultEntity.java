package cn.bugstack.ai.domain.asset.model;

import lombok.Builder;
import lombok.Data;

/**
 * 附件文本解析结果。
 */
@Data
@Builder
public class AssetParseResultEntity {
    /** ready、failed 或不支持等解析状态。 */
    private String parseStatus;
    /** 已截断并可注入上下文的文本。 */
    private String extractedText;
    /** 面向审计的稳定错误摘要。 */
    private String errorSummary;
}
