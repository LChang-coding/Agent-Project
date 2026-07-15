package cn.bugstack.ai.domain.asset.model;

import lombok.Builder;
import lombok.Data;

/**
 * 附件文本解析结果。
 */
@Data
@Builder
public class AssetParseResultEntity {
    private String parseStatus;
    private String extractedText;
    private String errorSummary;
}
