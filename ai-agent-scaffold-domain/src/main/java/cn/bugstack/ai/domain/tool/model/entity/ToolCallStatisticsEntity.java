package cn.bugstack.ai.domain.tool.model.entity;

import lombok.Builder;
import lombok.Data;

/**
 * 会话工具调用聚合统计。
 */
@Data
@Builder
public class ToolCallStatisticsEntity {
    /** 会话内成功调用总次数。 */
    private Long callCount;
    /** 会话内成功调用的去重工具数。 */
    private Long toolCount;
}
