package cn.bugstack.ai.domain.context.model;

import lombok.Builder;
import lombok.Data;

/**
 * 上下文组装结果。
 * <p>包含可注入模型请求的文本和观测字段。</p>
 */
@Data
@Builder
public class ContextAssemblyResult {

    private String instruction;
    private Integer estimatedTokenCount;
    private Integer memoryVersion;
    private Integer coveredToSequence;
    private Boolean cacheHit;
    private String trimReason;
}
